/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
*/

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

metadata {
    definition(name: 'QolSys IQ Panel', namespace: 'nrgup', author: 'Jonathan Bradshaw',
               importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/master/Security/QolSysIQPanel.groovy') {
        capability 'Initialize'
        capability 'Refresh'

        command 'removeDevices'

        attribute 'retries', 'number'
        attribute 'errors', 'number'
        attribute 'state', 'enum', [
            'initializing',
            'error',
            'online'
        ]
    }

    preferences {
        section {
            input name: 'ipAddress',
                type: 'text',
                title: 'Panel IP Address',
                required: true

            input name: 'accessToken',
                type: 'text',
                title: 'Panel Access Token',
                required: true

            input name: 'repeat',
                title: 'Command Retries',
                type: 'number',
                required: true,
                range: '0..5',
                defaultValue: '3'

            input name: 'timeoutSecs',
                title: 'Command Timeout (sec)',
                type: 'number',
                required: true,
                range: '1..5',
                defaultValue: '3'

            // input name: 'heartbeatSecs',
            //     title: 'Heartbeat interval (seconds)',
            //     type: 'number',
            //     required: true,
            //     range: '1..300',
            //     defaultValue: '60'

            input name: 'logEnable',
                type: 'bool',
                title: 'Enable debug logging',
                required: false,
                defaultValue: true

            input name: 'txtEnable',
                type: 'bool',
                title: 'Enable descriptionText logging',
                required: false,
                defaultValue: true
        }
    }
}

// Buffer for recieved messages
@Field static ConcurrentHashMap<String, StringBuffer> buffers = new ConcurrentHashMap<>()

// Queue used for ACK tracking
@Field static ConcurrentHashMap<String, SynchronousQueue> queues = new ConcurrentHashMap<>()

// Called to check connection
// void heartbeat() {
//     connect()
//     refresh()
//     if (settings.heartbeatSecs) { runIn(settings.heartbeatSecs, 'heartbeat') }
// }

// Called when the device is first created
void installed() {
    LOG.info 'Driver installed'
}

// Called when the device is started
void initialize() {
    String version = '0.0.1'
    LOG.info "Driver v${version} initializing"
    sendEvent([ name: 'retries', value: 0, descriptionText: 'reset' ])
    sendEvent([ name: 'errors', value: 0, descriptionText: 'reset' ])
    sendEvent([ name: 'state', value: 'initializing' ])
    state.clear()
    buffers.remove(device.id)
    queues.remove(device.id)
    interfaces.rawSocket.close()
    unschedule()

    if (!settings.ipAddress || !accessToken) {
        LOG.error 'IP Address and access token of panel must be configured'
        return
    }

    LOG.info "connecting to panel at ${settings.ipAddress}"
    connect()
    refresh()
}

void parse(String message) {
    if (!message) { return }
    SynchronousQueue q = queues.computeIfAbsent(device.id) { k -> new SynchronousQueue() }
    StringBuffer sb = buffers.computeIfAbsent(device.id) { k -> new StringBuffer() }
    sb.append(message)
    if (message[-1] != '\n') { return }
    try {
        if (sb.length() >= 3 && sb.substring(0, 3) == 'ACK') {
            q.offer(true)
        } else if (sb.length() >= 2 && sb.substring(0, 1) == '{') {
            Map json = new JsonSlurper().parseText(sb.toString())
            LOG.debug "Received json: ${json}"
            sendEvent([ name: 'state', value: 'online' ])
            switch(json.event) {
                case 'ALARM':
                    processAlarm(json)
                    break
                case 'ARMING':
                    processArming(json)
                    break
                case 'ERROR':
                    processError(json)
                    break
                case 'INFO':
                    processSummary(json)
                    break
                case 'ZONE_EVENT':
                    processZoneEvent(json.zone)
                    break
                default:
                    LOG.error("Unhandled message: ${json}")
                    break
            }
        }
    } catch (e) {
        LOG.exception('Error parsing panel data', e)
    } finally {
        buffers.remove(device.id)
    }
}

void refresh() {
    LOG.info 'Requesting panel summary'
    sendCommand([
        action: 'INFO',
        info_type: 'SUMMARY'
    ])
}

// Command to remove all the child devices
void removeDevices() {
    LOG.info "Removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

// Socket status updates
void socketStatus(String message) {
    if (message.contains('error')) {
        buffers.remove(device.id)
        increaseErrorCount(message)
    } else {
        LOG.info "socket ${message}"
    }
}

// Called when the device is removed
void uninstalled() {
    LOG.info 'Driver uninstalled'
}

// Called when the settings are updated
void updated() {
    LOG.info 'Driver configuration updated'
    LOG.debug settings
    if (settings.logEnable == true) { runIn(1800, 'logsOff') }

    initialize()
}

// Connect to panel
private void connect() {
    try {
        interfaces.rawSocket.connect(
            settings.ipAddress,
            12345,
            byteInterface: false,
            secureSocket: true,
            ignoreSSLIssues: true,
            convertReceivedDataToString: true
        )
        scheduleSocketKeepAlive()
    } catch (e) {
        LOG.exception("Error connecting to panel at ${settings.ipAddress}", e)
    }
}

private void createPartition(String namespace, String driver, Map partition) {
    String dni = "${device.deviceNetworkId}-p${partition.partition_id}"
    String deviceName = "${device.name} P${partition.partition_id + 1}"
    String deviceLabel = "${device} ${partition.name.capitalize()}"
    LOG.info "initializing partition device for ${partition}"
    try {
        ChildDeviceWrapper dw = getChildDevice(dni) ?: addChildDevice(namespace, driver, dni, [ name: deviceName, label: deviceLabel ])
        dw.with {
            label = label ?: deviceLabel
            updateDataValue 'partition_id', partition.partition_id as String
        }

        dw.parse([
            [ name: 'secureArm', value: partition.secure_arm as String ]
        ])
    } catch (e) {
        LOG.exception("partition device creation failed", e)
    }
}

private ChildDeviceWrapper createZone(String namespace, String driver, Map zone) {
    String dni = "${device.deviceNetworkId}-z${zone.zone_id}"
    String deviceName = "${device.name} P${zone.partition_id + 1} ${zone.type.replaceAll('_', ' ')}"
    String deviceLabel = zone.name.capitalize()
    LOG.info "initializing zone device for ${zone}"
    try {
        ChildDeviceWrapper dw = getChildDevice(dni) ?: addChildDevice(namespace, driver, dni, [ name: deviceName, label: deviceLabel ])
        dw.with {
            label = label ?: deviceLabel
            updateDataValue 'id', zone.zone_id as String
            updateDataValue 'type', zone.type
            updateDataValue 'name', zone.name
            updateDataValue 'group', zone.group
            updateDataValue 'zone_id', zone.zone_id as String
            updateDataValue 'zone_physical_type', zone.zone_physical_type as String
            updateDataValue 'zone_alarm_type', zone.zone_alarm_type as String
            updateDataValue 'zone_type', zone.zone_type as String
            updateDataValue 'partition_id', zone.partition_id as String
            
        }
        return dw
    } catch (e) {
        LOG.exception("zone device creation failed", e)
    }

    return null
}

private void increaseErrorCount(msg = '') {
    int val = (device.currentValue('errors') ?: 0) as int
    sendEvent([ name: 'errors', value: val + 1, descriptionText: msg ])
}

private void increaseRetryCount(msg = '') {
    int val = (device.currentValue('retries') ?: 0) as int
    sendEvent([ name: 'retries', value: val + 1, descriptionText: msg ])
}

private void scheduleSocketKeepAlive() {
    runIn(29, 'socketKeepAlive')
}

private void socketKeepAlive() {
    LOG.debug '(sending socket keepalive)'
    interfaces.rawSocket.sendMessage('\n')
    scheduleSocketKeepAlive()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    LOG.warn 'debug logging disabled'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

private processArming(Map json) {
    String value
    switch (json.arming_type ?: json.status) {
        case 'ARM_STAY': value = 'armed home'; break
        case 'ARM_AWAY': value = 'armed away'; break
        case 'DISARM': value = 'disarmed'; break
        case 'EXIT_DELAY': value = 'exit delay'; break
        case 'ENTRY_DELAY': value = 'entry delay'; break
        default:
            LOG.error "Unknown arming type: ${json.arming_type ?: json.status}"
            return
    }

    String dni = "${device.deviceNetworkId}-p${json.partition_id}"
    getChildDevice(dni)?.parse([
        [ name: 'state', value: value, descriptionText: "state is ${value}" ],
        [ name: 'alarm', value: 'None', descriptionText: "alarm cleared" ],
    ])
}

private processAlarm(Map json) {
    String dni = "${device.deviceNetworkId}-p${json.partition_id}"
    String value = json.alarm_type ?: 'INTRUSION'
    getChildDevice(dni)?.parse([
        [ name: 'state', value: 'alarm', descriptionText: 'state is alarm' ],
        [ name: 'alarm', value: value, descriptionText: "alarm is ${value}" ]
    ])
}

private processError(Map json) {
    String dni = "${device.deviceNetworkId}-p${json.partition_id}"
    String value = "${json.error_type}: ${json.description}"
    getChildDevice(dni)?.parse([
        [ name: 'state', value: 'error', descriptionText: 'state is error' ],
        [ name: 'error', value: value, descriptionText: "error ${value}" ]
    ])
}

private void processSummary(Map json) {
    json.partition_list?.each { partition ->
        createPartition('nrgup', 'QolSys IQ Partition Child', partition)
        processArming(partition)

        partition.zone_list?.each { zone ->
            switch (zone?.type) {
                case 'Door_Window':
                case 'Door_Window_M':
                case 'TakeoverModule':
                case 'Tilt':
                    createZone('hubitat', 'Generic Component Contact Sensor', zone)
                    break
                case 'GlassBreak':
                case 'Panel Glass Break':
                    break
                case 'SmokeDetector':
                case 'Smoke_M':
                case 'Heat':
                    createZone('hubitat', 'Generic Component Smoke Detector', zone)
                    break
                case 'CODetector':
                    createZone('hubitat', 'Generic Component Carbon Monoxide Sensor', zone)
                    break
                case 'Motion':
                case 'Panel Motion':
                    createZone('hubitat', 'Generic Component Motion Sensor', zone)
                    break
                case 'Water':
                    createZone('hubitat', 'Generic Component Water Sensor', zone)
                    break
                case 'KeyFob':
                case 'Auxiliary Pendant':
                    createZone('hubitat', 'Generic Component Button Controller', zone)?.
                        sendEvent([ name: 'numberOfButtons', value: 1 ])
                    break
                case 'Bluetooth':
                case 'Z-Wave Siren':
                    break
                default:
                    LOG.warn "Unable to create zone for unknown type ${zone}"
            }

            if (zone.state) {
                processZoneState(zone)
            }
        }

        processPartitionState(partition.partition_id)
    }
}

private processZoneEvent(Map zone) {
    //Instead it looks like the panel is reporting motion with zone_event_type,
    //as ZONE_ACTIVE (motion) and ZONE_UPDATE (clear). (TODO?)
    String dni = "${device.deviceNetworkId}-z${zone.zone_id}"
    ChildDeviceWrapper dw = getChildDevice(dni)
    if (dw != null) {
        zone.type = dw.getDataValue('type')
        zone.partition_id = dw.getDataValue('partition_id') as int
        processZoneState(zone)
        processPartitionState(zone.partition_id)
    }
}

private processZoneState(Map zone) {
    LOG.debug "Zone state: ${zone}"
    boolean isOpen = zone.status == 'Open'

    Map event = [:]
    switch (zone?.type) {
        case 'Door_Window':
        case 'Door_Window_M':
        case 'TakeoverModule':
        case 'Tilt':
            event.name = 'contact'
            event.value = isOpen ? 'open' : 'closed'
            break
        case 'GlassBreak':
        case 'Panel Glass Break':
            break
        case 'SmokeDetector':
        case 'Smoke_M':
        case 'Heat':
            event.name = 'smoke'
            event.value = isOpen ? 'detected' : 'clear'
            break
        case 'CODetector':
            event.name = 'carbonMonoxide'
            event.value = isOpen ? 'detected' : 'clear'
            break
        case 'Motion':
        case 'Panel Motion':
            event.name = 'motion'
            event.value = isOpen ? 'active' : 'inactive'
            break
        case 'Water':
            event.name = 'water'
            event.value = isOpen ? 'wet' : 'dry'
            break
        case 'KeyFob':
        case 'Auxiliary Pendant':
            event.name = 'pushed'
            event.value = isOpen ? '1' : '0'
            break
        case 'Bluetooth':
        case 'Z-Wave Siren':
            break
        default:
            LOG.warn "Unknown zone type ${zone}"
    }

    if (event.value == null) { return }

    // Update child zone device
    String dni = "${device.deviceNetworkId}-z${zone.zone_id}"
    event.descriptionText = "${event.name} is ${event.value}"
    getChildDevice(dni)?.parse([ event ])

    // Update partition zone state mapping
    SynchronousQueue q = queues.computeIfAbsent(device.id) { k -> new SynchronousQueue() }
    synchronized (q) {
        Map partitionState = state["partition${zone.partition_id}"] ?: [:]
        partitionState[zone.zone_id] = isOpen
        state["partition${zone.partition_id}"] = partitionState
    }
}

private void processPartitionState(int partition_id) {
    Map partitionState = state["partition${partition_id}"] ?: [:]
    LOG.debug "Partition ${partition_id} state is ${partitionState}"
    boolean isSecure = partitionState.every { z -> z.value == false }
    String openZoneText = 'None'
    if (isSecure) {
        LOG.info "partition ${partition_id} is secure"
    } else {
        Set openZones = partitionState.findAll { z -> z.value == true }.collect { z -> getChildDevice("${device.deviceNetworkId}-z${z.key}")?.toString() }
        openZoneText = openZones.sort().join(', ')
        LOG.info "partition ${partition_id} open zones: ${openZoneText}"
    }

    String dni = "${device.deviceNetworkId}-p${partition_id}"
    getChildDevice(dni)?.parse([
        [ name: 'isSecure', value: isSecure, descriptionText: "isSecure is ${isSecure}" ],
        [ name: 'openZones', value: openZoneText, descriptionText: "open zones ${openZoneText}" ]
    ])
}

private boolean sendCommand(Map json) {
    SynchronousQueue q = queues.computeIfAbsent(device.id) { k -> new SynchronousQueue() }
    synchronized(q) {
        unschedule('socketKeepAlive')
        for (i = 1; i <= settings.repeat; i++) {
            try {
                json.version = 1
                json.source = 'C4'
                json.token = settings.accessToken
                LOG.debug "sending ${json}"
                interfaces.rawSocket.sendMessage(JsonOutput.toJson(json))
            } catch (e) {
                LOG.exception "send exception", e
                increaseErrorCount(e.message)
                pauseExecution(500)
                increaseRetryCount()
                connect()
                continue
            }

            boolean result = q.poll(settings.timeoutSecs, TimeUnit.SECONDS)
            if (result) {
                LOG.debug "received panel ack for ${json?.action}"
                scheduleSocketKeepAlive()
                return true
            } else {
                LOG.warn "${json?.action} command timeout (${i} of ${settings.repeat})"
                pauseExecution(500)
                increaseRetryCount()
                connect()
            }
        }
    }

    increaseErrorCount("${json?.action ?: 'action'} not acknowledged")
    return false
}

@Field private final Map LOG = [
    debug: { s -> if (settings.logEnable == true) { log.debug(s) } },
    info: { s -> log.info(s) },
    warn: { s -> log.warn(s) },
    error: { s -> log.error(s); sendEvent([ name: 'state', value: 'error', descriptionText: s ]) },
    exception: { message, exception ->
        List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith('user_app') }
        Integer line = relevantEntries[0]?.lineNumber ?: 0
        String method = relevantEntries[0]?.methodName ?: ''
        log.error("${message}: ${exception} at line ${line} (${method})")
        sendEvent([ name: 'state', value: 'error', descriptionText: "${message}: ${exception?.message}" ])
        if (settings.logEnable && relevantEntries.size() > 0) {
            log.debug("App exception stack trace:\n${relevantEntries.join('\n')}")
        }
    }
]

/*
ZoneTypes = {
UNKNOWN = 0,
CONTACT = 1,
MOTION = 2,
SOUND = 3 ,
BREAKAGE = 4,
SMOKE_HEAT = 5,
CARBON_MONOXIDE = 6,
RADON = 7,
TEMPERATURE = 8,
PANIC_BUTTON = 9,
CONTROL = 10,
CAMERA = 11,
LIGHT = 12,
GPS = 13,
SIREN = 14,
WATER = 15,
TILT = 16,
FREEZE = 17,
TAKEOVER_MODULE = 18,
GLASSBREAK = 19,
TRANSLATOR = 20,
MEDICAL_PENDANT = 21,
WATER_IQ_FLOOD = 22,
WATER_OTHER_FLOOD = 23,
IMAGE_SENSOR = 30,
WIRED_SENSOR = 100,
RF_SENSOR = 101,
KEYFOB = 102,
WALLFOB = 103,
RF_KEYPAD = 104,
PANEL = 105,
WTTS_OR_SECONDARY = 106,
SHOCK = 107,
SHOCK_SENSOR_MULTI_FUNCTION = 108,
DOOR_BELL = 109,
CONTACT_MULTI_FUNCTION = 110,
SMOKE_MULTI_FUNCTION = 111,
TEMPERATURE_MULTI_FUNCTION = 112,
SHOCK_OTHERS = 113,
OCCUPANCY_SENSOR = 114,
BLUETOOTH = 115,
PANEL_GLASS_BREAK = 116,
POWERG_SIREN = 117,
BLUETOOTH_SPEAKER = 118,
PANEL_MOTION = 119,
ZWAVE_SIREN = 120,
COUNT = 121

ZONE_OPEN_VAL = ‘Open’
ZONE_CLOSED_VAL = ‘Closed’
ZONE_ACTIVE_VAL = ‘Active’
ZONE_ACTIVATED_VAL = ‘Activated’
ZONE_IDLE_VAL = ‘Idle’
ZONE_NORMAL_VAL = ‘Normal’
*/