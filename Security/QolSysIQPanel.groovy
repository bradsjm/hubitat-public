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
    definition(name: 'QolSys IQ Panel 2+', namespace: 'nrgup', author: 'Jonathan Bradshaw',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/master/Security/QolSysIQPanel.groovy') {
        capability 'Initialize'
        capability 'Refresh'

        command 'removeDevices'

        attribute 'networkStatus', 'enum', [
                'initializing',
                'connecting',
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

// Buffer for received messages
@Field static ConcurrentHashMap<String, StringBuffer> buffers = new ConcurrentHashMap<>()

// Queue used for ACK tracking
@Field static ConcurrentHashMap<String, SynchronousQueue> queues = new ConcurrentHashMap<>()

// Zone cache
@Field static ConcurrentHashMap<String, Map> zoneCache = new ConcurrentHashMap<>()

/*
 * Public Methods
 */

// Called when the device is first created
void installed() {
    LOG.info "${device} driver installed"
}

// Called when the device is started
void initialize() {
    LOG.info "${device} driver initializing"
    sendEvent([ name: 'networkStatus', value: 'initializing', descriptionText: 'Initializing QolSys IQ Panel 2+ driver' ])
    state.clear()
    buffers.remove(device.id)
    queues.remove(device.id)
    interfaces.rawSocket.close()
    unschedule()

    if (!settings.ipAddress || !accessToken) {
        LOG.error 'IP Address and access token of panel must be configured'
        return
    }

    LOG.info "Connecting to panel at ${settings.ipAddress}"
    connect()
}

void parse(String message) {
    if (!message) { return }
    StringBuffer sb = buffers.computeIfAbsent(device.id) { k -> new StringBuffer() }
    sb.append(message)
    if (message[-1] != '\n') {
        LOG.debug "partial packet: ${message}"
        return
    }
    if (device.currentValue('networkStatus') != 'online') {
        sendEvent([ name: 'networkStatus', value: 'online', descriptionText: "Connected to panel at ${settings.ipAddress}" ])
    }
    try {
        if (sb.length() >= 3 && sb.substring(0, 3) == 'ACK') {
            getQ().offer(true)
        } else if (sb.length() >= 2 && sb.substring(0, 1) == '{') {
            Map json = new JsonSlurper().parseText(sb.toString())
            LOG.debug "received: ${json}"
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
    unschedule('refresh')
    LOG.info 'requesting panel summary'
    sendCommand([
            action: 'INFO',
            info_type: 'SUMMARY'
    ])
}

// Command to remove all the child devices
void removeDevices() {
    LOG.info "removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

// Socket status updates
void socketStatus(String message) {
    if (message.contains('error')) {
        buffers.remove(device.id)
        LOG.warn message
    } else {
        LOG.info message
    }
}

// Called when the device is removed
void uninstalled() {
    LOG.info "${device} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    LOG.info "${device} driver configuration updated"
    LOG.debug settings
    if (settings.logEnable == true) { runIn(1800, 'logsOff') }

    initialize()
}

/*
 * Internal Implementation Methods
 */

// Connect to panel
private void connect() {
    unschedule('connect')
    try {
        sendEvent([ name: 'networkStatus', value: 'connecting', descriptionText: "Connecting to panel at ${settings.ipAddress}" ])
        interfaces.rawSocket.connect(
                settings.ipAddress,
                12345,
                byteInterface: false,
                secureSocket: true,
                ignoreSSLIssues: true,
                convertReceivedDataToString: true
        )
        refresh()
    } catch (e) {
        LOG.exception("Error connecting to panel at ${settings.ipAddress}", e)
        runIn(60, 'connect')
    }
}

private void createPartition(String namespace, String driver, Map partition) {
    String dni = "${device.deviceNetworkId}-p${partition.partition_id}"
    String deviceName = "${device.name} P${partition.partition_id + 1}"
    String deviceLabel = "${device} ${partition.name.capitalize()}"
    LOG.info "Initializing partition #${partition.partition_id}: ${deviceLabel}"
    LOG.debug "createPartition ${partition}"
    try {
        ChildDeviceWrapper dw = getChildDevice(dni) ?: addChildDevice(namespace, driver, dni, [ name: deviceName, label: deviceLabel ])
        dw.with {
            label = label ?: deviceLabel
            updateDataValue 'partition_id', partition.partition_id as String
            updateDataValue 'secureArm', partition.secureArm
        }
    } catch (e) {
        LOG.exception("partition device creation failed", e)
    }
}

private ChildDeviceWrapper createZone(String namespace, String driver, Map zone) {
    String dni = "${device.deviceNetworkId}-z${zone.zone_id}"
    String deviceName = "${device.name} P${zone.partition_id + 1} ${zone.type.replaceAll('_', ' ')}"
    String deviceLabel = zone.name.capitalize()
    LOG.info "Initializing zone #${zone.zone_id}: ${deviceLabel} (${deviceName})"
    LOG.debug "createZone ${zone}"
    zoneCache.put(dni, zone)
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

private List getOpenZones(int partition_id, boolean includeSafety = false) {
    return zoneCache.values().findAll { z ->
        z.partition_id == partition_id && z.status == 'Open' &&
                (includeSafety ? true : z.group.startsWith('safety') == false)
    }
}

private SynchronousQueue getQ() {
    return queues.computeIfAbsent(device.id) { k -> new SynchronousQueue() }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    LOG.warn 'debug logging disabled'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

private void processAlarm(Map json) {
    String dni = "${device.deviceNetworkId}-p${json.partition_id}"
    String value = json.alarm_type ?: 'INTRUSION'
    getChildDevice(dni)?.parse([
            [ name: 'networkStatus', value: 'alarm', descriptionText: 'state is alarm' ],
            [ name: 'alarm', value: value, descriptionText: "alarm is ${value}" ]
    ])
}

private void processArming(Map json) {
    String value
    String bypass = getOpenZones(json.partition_id)*.name.sort().join(', ') ?: 'none'
    switch (json.arming_type ?: json.status) {
        case 'ARM_STAY': value = 'armed home'; break
        case 'ARM_AWAY': value = 'armed away'; break
        case 'DISARM': value = 'disarmed'; bypass = 'none'; break
        case 'EXIT_DELAY': value = 'exit delay'; break
        case 'ENTRY_DELAY': value = 'entry delay'; break
        default:
            LOG.error "Unknown arming type: ${json.arming_type ?: json.status}"
            return
    }

    int delay = json.delay ?: 0
    String dni = "${device.deviceNetworkId}-p${json.partition_id}"
    getChildDevice(dni)?.parse([
            [ name: 'networkStatus', value: value, descriptionText: "state is ${value}" ],
            [ name: 'bypass', value: bypass, descriptionText: "bypass is ${bypass}" ],
            [ name: 'alarm', value: 'none', descriptionText: 'alarm cleared' ],
            [ name: 'error', value: 'none', descriptionText: 'error cleared' ],
            [ name: 'delay', value: delay, descriptionText: "delay is ${delay} seconds"]
    ])
}

private void processError(Map json) {
    String dni = "${device.deviceNetworkId}-p${json.partition_id}"
    String value = "${json.error_type}: ${json.description}"
    getChildDevice(dni)?.parse([
            [ name: 'networkStatus', value: 'error', descriptionText: 'state is error' ],
            [ name: 'error', value: value, descriptionText: "error ${value}" ]
    ])
}

private void processSummary(Map json) {
    json.partition_list?.each { partition ->
        createPartition('nrgup', 'QolSys IQ Partition Child', partition)
        partition.zone_list?.each { zone ->
            switch (zone?.zone_type) {
                case 1: // CONTACT
                case 16: // TILT
                case 110: // CONTACT_MULTI_FUNCTION
                    createZone('hubitat', 'Generic Component Contact Sensor', zone)
                    break
                case 5: // SMOKE_HEAT
                    createZone('hubitat', 'Generic Component Smoke Detector', zone)
                    break
                case 6: // CARBON_MONOXIDE
                    createZone('hubitat', 'Generic Component Carbon Monoxide Sensor', zone)
                    break
                case 2: // MOTION
                case 114: // OCCUPANCY
                case 119: // PANEL MOTION
                    createZone('hubitat', 'Generic Component Motion Sensor', zone)
                    break
                case 15: // WATER
                    createZone('hubitat', 'Generic Component Water Sensor', zone)
                    break
                case 9: // PANIC BUTTON
                case 102: // KEYFOB
                case 103: // WALLFOB
                    //createZone('hubitat', 'Generic Component Button Controller', zone)?.
                    //    sendEvent([ name: 'numberOfButtons', value: 1 ])
                    break
                default:
                    LOG.debug "no driver mapping for zone ${zone}"
                    return
            }

            processZoneState(zone)
        }

        processPartitionState(partition.partition_id)
        processArming(partition)
    }
}

private void processZoneEvent(Map zone) {
    //Instead it looks like the panel is reporting motion with zone_event_type,
    //as ZONE_ACTIVE (motion) and ZONE_UPDATE (clear). (TODO?)
    String dni = "${device.deviceNetworkId}-z${zone.zone_id}"
    if (zoneCache.containsKey(dni) == false) {
        runIn(1, 'refresh')
        return
    }
    Map z = (zoneCache[dni] += zone)
    processZoneState(z)
    processPartitionState(z.partition_id)
}

private void processZoneState(Map zone) {
    LOG.debug "zone state: ${zone}"
    String dni = "${device.deviceNetworkId}-z${zone.zone_id}"
    ChildDeviceWrapper dw = getChildDevice(dni)
    if (dw == null) { return }

    Map event = [:]
    boolean isOpen = zone.status == 'Open'

    if (dw.hasAttribute('contact')) {
        event.name = 'contact'
        event.value = isOpen ? 'open' : 'closed'
    } else if (dw.hasAttribute('smoke')) {
        event.name = 'smoke'
        event.value = isOpen ? 'detected' : 'clear'
    } else if (dw.hasAttribute('carbonMonoxide')) {
        event.name = 'carbonMonoxide'
        event.value = isOpen ? 'detected' : 'clear'
    } else if (dw.hasAttribute('motion')) {
        event.name = 'motion'
        event.value = isOpen ? 'active' : 'inactive'
    } else if (dw.hasAttribute('water')) {
        event.name = 'water'
        event.value = isOpen ? 'wet' : 'dry'
    } else if (dw.hasAttribute('pushed')) {
        event.name = 'pushed'
        event.value = isOpen ? '1' : '0'
    } else {
        LOG.warn "unknown driver for type ${zone}"
        return
    }

    // Update child zone device
    event.descriptionText = "${event.name} is ${event.value}"
    dw.parse([ event ])
}

private void processPartitionState(int partition_id) {
    String dni = "${device.deviceNetworkId}-p${partition_id}"
    String openZoneText = 'none'
    boolean isSecure = true
    LOG.debug "zonecache: ${zoneCache}"
    List zones = getOpenZones(partition_id)
    if (zones.size() > 0) {
        isSecure = false
        openZoneText = zones*.name.sort().join(', ')
        LOG.debug "partition ${partition_id} open zones: ${openZoneText}"
    }

    getChildDevice(dni)?.parse([
            [ name: 'isSecure', value: isSecure as String, descriptionText: "partition is ${isSecure ? '' : 'not '}secure" ],
            [ name: 'openZones', value: openZoneText, descriptionText: "zone(s) open: ${openZoneText}" ]
    ])
}

private void scheduleSocketKeepAlive() {
    // Websocket expects a keepalive every 30 seconds
    runIn(25, 'socketKeepAlive')
}

private void sendCommand(Map json) {
    if (device.currentValue('networkStatus') == 'error') { return }

    unschedule('socketKeepAlive')
    try {
        json.version = 1
        json.source = 'C4'
        json.token = settings.accessToken
        LOG.debug "sending ${json}"
        interfaces.rawSocket.sendMessage(JsonOutput.toJson(json))
    } catch (e) {
        LOG.exception "send exception", e
    }

    if (getQ().poll(3, TimeUnit.SECONDS)) {
        LOG.debug "received panel ack for ${json?.action}"
        scheduleSocketKeepAlive()
    } else {
        LOG.error "no panel ack for ${json?.action}"
        runIn(5, 'connect')
    }
}

private void socketKeepAlive() {
    interfaces.rawSocket.sendMessage('\n')
    if (getQ().poll(3, TimeUnit.SECONDS)) {
        scheduleSocketKeepAlive()
    } else {
        LOG.error 'socket keepalive timeout'
        runIn(5, 'connect')
    }
}

@Field private final Map LOG = [
        debug: { s -> if (settings.logEnable == true) { log.debug(s) } },
        info: { s -> log.info(s) },
        warn: { s -> log.warn(s) },
        error: { s -> log.error(s); sendEvent([ name: 'networkStatus', value: 'error', descriptionText: s ]) },
        exception: { message, exception ->
            List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith('user_app') }
            Integer line = relevantEntries[0]?.lineNumber ?: 0
            String method = relevantEntries[0]?.methodName ?: ''
            log.error("${message}: ${exception}" + (line ? " at line ${line} (${method})" : ''))
            sendEvent([ name: 'networkStatus', value: 'error', descriptionText: "${message}: ${exception}" ])
            if (settings.logEnable && relevantEntries) {
                log.debug("App exception stack trace:\n${relevantEntries.join('\n')}")
            }
        }
]

/* Reference:
    Zone Physical Types:
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

    Zone States:
        ZONE_OPEN_VAL = ‘Open’
        ZONE_CLOSED_VAL = ‘Closed’
        ZONE_ACTIVE_VAL = ‘Active’
        ZONE_ACTIVATED_VAL = ‘Activated’
        ZONE_IDLE_VAL = ‘Idle’
        ZONE_NORMAL_VAL = ‘Normal’
*/