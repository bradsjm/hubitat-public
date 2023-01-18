/**
 *  MIT License
 *  Copyright 2022 Jonathan Bradshaw (jb@nrgup.net)
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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Field
import hubitat.helper.HexUtils
import java.util.zip.Inflater

metadata {
    definition(name: 'Valetudo Robot Vacuum', namespace: 'nrgup', author: 'Jonathan Bradshaw',
        importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/Misc/ValetudoVacuum.groovy') {
        capability 'Initialize'
        capability 'Battery'
        capability 'SignalStrength'
        capability 'Switch'

        attribute 'area', 'number'
        attribute 'batteryStatus', 'enum', [ 'none', 'charging', 'discharging', 'charged' ]
        attribute 'dustbin', 'enum', [ 'true', 'false' ]
        attribute 'elapsedMin', 'number'
        attribute 'errorDescription', 'string'
        attribute 'fanSpeed', 'enum', [ 'off', 'min', 'low', 'medium', 'high', 'turbo', 'max' ]
        attribute 'mop', 'enum', [ 'true', 'false' ]
        attribute 'networkStatus', 'enum', [ 'connecting', 'online', 'offline' ]
        attribute 'rooms', 'json_object'
        attribute 'status', 'enum', [ 'error', 'docked', 'idle', 'returning', 'cleaning', 'paused', 'manual_control', 'moving' ]
        attribute 'statusDetail', 'enum', [ 'none', 'zone', 'segment', 'spot', 'target', 'resumable', 'mapping' ]
        attribute 'volume', 'number'
        attribute 'waterGrade', 'enum', [ 'off', 'min', 'low', 'medium', 'high', 'turbo', 'max' ]
        attribute 'waterTank', 'enum', [ 'true', 'false' ]

        command 'cleanRooms', [
            [
                name: 'rooms',
                description: 'comma seperated room ids or blank for switched on child rooms',
                type: 'STRING'
            ],
            [
                name: 'iterations',
                description: 'number of iterations (defaults to 1)',
                type: 'NUMBER'
            ]
        ]
        command 'createChildRooms'
        command 'emptyDock'
        command 'setFanSpeed', [
            [
                name: 'speed*',
                description: 'speed values depend on your firmware',
                type: 'ENUM',
                constraints: [ 'off', 'min', 'low', 'medium', 'high', 'turbo', 'max' ]
            ]
        ]
        command 'setWaterGrade', [
            [
                name: 'grade*',
                description: 'grade values depend on your firmware',
                type: 'ENUM',
                constraints: [ 'off', 'min', 'low', 'medium', 'high', 'turbo', 'max' ]
            ]
        ]
        command 'home'
        command 'locate'
        command 'pause'
        command 'removeChildRooms'
        command 'setVolume', [
            [
                name: 'level*',
                description: 'Percent from 1 to 100',
                type: 'NUMBER'
            ]
        ]
    }

    preferences {
        section('MQTT Configuration') {
            input name: 'brokerIp',
                type: 'string',
                title: 'MQTT Broker IP Address',
                required: true

            input name: 'brokerPort',
                type: 'string',
                title: 'MQTT Broker Port',
                defaultValue: '1883',
                required: true

            input name: 'brokerUser',
                type: 'string',
                title: 'MQTT Broker Username',
                required: false

            input name: 'brokerPassword',
                type: 'password',
                title: 'MQTT Broker Password',
                required: false

            input name: 'namespace',
                type: 'string',
                title: 'Valetudo name space',
                defaultValue: 'valetudo',
                required: true

            input name: 'identifier',
                type: 'string',
                title: 'Vacuum machine name',
                defaultValue: 'robot',
                required: true
        }

        section {
            input name: 'logEnable',
                type: 'bool',
                title: 'Enable debug logging',
                required: false,
                defaultValue: false

            input name: 'txtEnable',
                type: 'bool',
                title: 'Enable descriptionText logging',
                required: false,
                defaultValue: true
        }
    }
}

/**
 * MQTT Topic to Hubitat Attribute Mapping & Conversion
 */
@Field static final List<Map> TopicAttributeMap = [
    [
        path: 'AttachmentStateAttribute/dustbin',
        name: 'dust bin',
        attribute: 'dustbin'
    ],
    [
        path: 'AttachmentStateAttribute/mop',
        name: 'mop',
        attribute: 'mop'
    ],
    [
        path: 'AttachmentStateAttribute/watertank',
        name: 'water tank',
        attribute: 'waterTank'
    ],
    [
        path: 'BatteryStateAttribute/level',
        name: 'battery level',
        attribute: 'battery',
        unit: '%',
        conversion: { payload -> new String(payload, 'UTF-8').toInteger() }
    ],
    [
        path: 'BatteryStateAttribute/status',
        name: 'battery state',
        attribute: 'batteryStatus'
    ],
    [
        path: 'CurrentStatisticsCapability/area',
        name: 'area covered',
        attribute: 'area',
        unit: 'm²',
        conversion: { payload -> (new String(payload, 'UTF-8').toInteger() / 10000.0) as int }
    ],
    [
        path: 'CurrentStatisticsCapability/time',
        name: 'elapsed minutes',
        attribute: 'elapsedMin',
        unit: 'm',
        conversion: { payload -> (new String(payload, 'UTF-8').toInteger() / 60.0) as int }
    ],
    [
        path: 'FanSpeedControlCapability/preset',
        name: 'fan speed',
        attribute: 'fanSpeed'
    ],
    [
        path: 'SpeakerVolumeControlCapability/value',
        name: 'volume level',
        attribute: 'volume',
        unit: '%',
        conversion: { payload -> new String(payload, 'UTF-8').toInteger() }
    ],
    [
        path: 'MapData/segments',
        name: 'room segments',
        attribute: 'rooms'
    ],
    [
        path: 'StatusStateAttribute/detail',
        name: 'status detail',
        attribute: 'statusDetail',
        conversion: { payload -> new String(payload, 'UTF-8').replace('segment', 'room') }
    ],
    [
        path: 'StatusStateAttribute/error_description',
        name: 'error description',
        attribute: 'errorDescription'
    ],
    [
        path: 'StatusStateAttribute/status',
        name: 'status',
        attribute: 'status'
    ],
    [
        path: 'StatusStateAttribute/status',
        name: 'switch',
        attribute: 'switch',
        conversion: { payload ->
            switch (new String(payload, 'UTF-8')) {
                case 'cleaning':
                case 'moving':
                case 'manual_control':
                    return 'on'
                default:
                    return 'off'
            }
        }
    ],
    [
        path: 'WaterUsageControlCapability/preset',
        name: 'water grade',
        attribute: 'waterGrade'
    ],
    [
        path: 'WifiConfigurationCapability/signal',
        name: 'WiFi rssi',
        attribute: 'rssi',
        unit: 'dBm',
        conversion: { payload -> 5 * (Math.round(new String(payload, 'UTF-8').toInteger() / 5)) } // multiples of 5db
    ],
    // [
    //     path: 'MapData/map-data',
    //     name: 'map',
    //     conversion: { payload -> parseMap(payload) }
    // ]
].asImmutable()

@Field static final Random random = new Random()
@Field static final int MAX_RECONNECT_SECONDS = 60

void initialize() {
    log.info "${device} driver initializing"
    unschedule()

    if (!settings.brokerIp || !settings.brokerPort) {
        log.error 'Unable to connect because Broker setting not configured'
        return
    }

    state.remove('reconnectDelay')
    scheduleConnect()
}

// Called when the device is first created.
void installed() {
    log.info "${device} driver installed"
}

void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device}"
}

// Called with MQTT client status messages
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            runIn(1, 'mqttSubscribe')
            break
        default:
            log.error 'mqtt connection error: ' + status
            mqttDisconnect()
            scheduleConnect()
            break
    }
}

// Called to parse received MQTT data
void parse(String data) {
    Map message = interfaces.mqtt.parseMessage(data)
    String topic = message['topic'] ?: ''
    byte[] payload = HexUtils.hexStringToByteArray(message['payload'])
    if (settings.logEnable) { log.debug "mqtt: ${topic} = ${payload}" }
    TopicAttributeMap.each { map ->
        if (topic.endsWith('/' + map.path)) {
            String value = map.conversion ? map.conversion(payload) : new String(payload, 'UTF-8')
            if ((device.currentValue(map.attribute) as String) != (value as String)) {
                String descriptionText = "${map.name} is ${value}${map.unit ?: ''}"
                sendEvent(name: map.attribute, value: value, descriptionText: descriptionText)
                if (settings.txtEnable) { log.info descriptionText }
            }
        }
    }
}

// Called when the device is removed.
void uninstalled() {
    mqttDisconnect()
    log.info "${device} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device} driver configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

// Commands
void cleanRooms() { cleanRooms(null, null) }

void cleanRooms(String rooms, BigDecimal iterations) {
    List<String> roomList = []
    if (rooms) {
        roomList = rooms.split(',')
    } else {
        roomList = getChildDevices().
            findAll { d -> d.currentValue('switch') == 'on' }.
            collect { d ->
                String dni = d.getDeviceNetworkId()
                return dni.substring(dni.indexOf('-') + 1)
            }
    }
    if (roomList) {
        log.info "cleaning rooms ${roomList}"
        String json = JsonOutput.toJson([
            'segment_ids': roomList,
            'iterations': iterations ?: 1,
            'customOrder': true
        ])
        mqttPublish(getTopic('MapSegmentationCapability/clean/set'), json)
    } else {
        log.info 'starting vacuum'
        mqttPublish(getTopic('BasicControlCapability/operation/set'), 'START')
    }
}

void createChildRooms() {
    String deviceName = device.name
    Map<String, String> rooms = parseJson(device.currentValue('rooms')) ?: [:]
    log.info "creating child device rooms for ${rooms}"
    rooms.each { String id, String roomName ->
        String dni = "${device.id}-${id}"
        (getChildDevice(dni) ?: addChildDevice('hubitat', 'Virtual Switch', dni)).with {
            name = "${deviceName} segment #${id}"
            label = "${roomName} Cleaning"
        }
    }
}

void emptyDock() {
    log.info 'emptying vacuum into dock'
    mqttPublish(getTopic('AutoEmptyDockManualTriggerCapability/trigger/set'), 'PERFORM')
}

void home() {
    log.info 'vacuum returning to dock'
    mqttPublish(getTopic('BasicControlCapability/operation/set'), 'HOME')
}

void locate() {
    log.info 'locating vacuum'
    mqttPublish(getTopic('LocateCapability/locate/set'), 'PERFORM')
}

void off() {
    home()
}

void on() {
    cleanRooms()
}

void pause() {
    log.info 'pausing vacuum'
    mqttPublish(getTopic('BasicControlCapability/operation/set'), 'PAUSE')
}

void removeChildRooms() {
    log.info 'removing all child devices'
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

void setFanSpeed(String speed) {
    log.info "setting fan speed to ${speed}"
    mqttPublish(getTopic('/FanSpeedControlCapability/preset/set'), speed)
}

void setVolume(BigDecimal volume) {
    log.info "setting volume to ${volume}"
    mqttPublish(getTopic('SpeakerVolumeControlCapability/value/set'), volume as String)
}

void setWaterGrade(String grade) {
    log.info "setting water grade to ${grade}"
    mqttPublish(getTopic('WaterUsageControlCapability/preset/set'), grade)
}

// ========================================================
// HELPERS
// ========================================================
void mqttConnect() {
    try {
        state.clientId = state.clientId ?: new BigInteger(119, random).toString(36)
        String uri = getBrokerUri()
        log.info "Connecting to MQTT broker at ${uri}"
        sendEvent([ name: 'networkStatus', value: 'connecting', descriptionText: "connecting to ${uri}" ])
        interfaces.mqtt.connect(uri, state.clientId, settings?.brokerUser, settings?.brokerPassword, byteInterface: true)
    } catch (e) {
        log.error "MQTT connect error: ${e}"
        sendEvent([ name: 'networkStatus', value: 'disconnected', descriptionText: "error ${e}" ])
        scheduleConnect()
    }
}

void mqttSubscribe() {
    if (interfaces.mqtt.connected) {
        state.remove('reconnectDelay')
        String topic = getTopic('#')
        if (logEnable) { log.debug "SUB: ${topic}" }
        sendEvent([ name: 'networkStatus', value: 'connected', descriptionText: "subscriig to ${topic}" ])
        interfaces.mqtt.subscribe(topic)
    }
}

@CompileStatic
private static String parseMap(byte[] payload) {
    //Map json = new JsonSlurper().parseText(inflateJson(payload))
    return inflateString(payload)
}

@CompileStatic
private static String inflateString(byte[] data) {
    ByteArrayOutputStream outputstream = new ByteArrayOutputStream(data.size())
    Inflater inflater = new Inflater()
    inflater.setInput(data)
    byte[] buffer = new byte[data.size()]
    while (inflater.finished() != true) {
        inflater.inflate(buffer)
        outputstream.write(buffer)
    }
    inflater.end()
    outputstream.close()
    return outputstream.toString('UTF-8')
}

private void mqttDisconnect() {
    if (interfaces.mqtt.connected) {
        log.info 'disconnecting from MQTT broker'
        interfaces.mqtt.disconnect()
        sendEvent([ name: 'networkStatus', value: 'disconnected' ])
        pauseExecution(1000)
    }
}

private void scheduleConnect() {
    int reconnectDelay = (state.reconnectDelay ?: 1)
    if (reconnectDelay > MAX_RECONNECT_SECONDS) { reconnectDelay = MAX_RECONNECT_SECONDS }
    int jitter = (int) Math.ceil(reconnectDelay * 0.25)
    reconnectDelay += random.nextInt(jitter)
    log.info "reconnecting in ${reconnectDelay} seconds"
    state.reconnectDelay = reconnectDelay * 2
    runIn(reconnectDelay, 'mqttConnect')
}

private String getBrokerUri() {
    return "tcp://${settings?.brokerIp}:${settings?.brokerPort}"
}

private String getTopic(String topic) {
    return "${settings?.namespace.replaceAll('/$', '')}/${identifier.replaceAll('/$', '')}/${topic}"
}

private void mqttPublish(String topic, String payload = '', int qos = 0) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "send: ${topic} = ${payload}" }
        interfaces.mqtt.publish(topic, HexUtils.byteArrayToHexString(payload.bytes), qos, false)
    }
}
