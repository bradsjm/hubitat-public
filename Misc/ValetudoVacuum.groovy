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
import groovy.transform.Field

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
        attribute 'rooms', 'json_object'
        attribute 'status', 'enum', [ 'error', 'docked', 'idle', 'returning', 'cleaning', 'paused', 'manual_control', 'moving' ]
        attribute 'statusDetail', 'enum', [ 'none', 'zone', 'segment', 'spot', 'target', 'resumable', 'mapping' ]
        attribute 'volume', 'number'
        attribute 'waterGrade', 'enum', [ 'off', 'min', 'low', 'medium', 'high', 'turbo', 'max' ]
        attribute 'waterTank', 'enum', [ 'true', 'false' ]

        command 'cleanRooms', [
            [
                name: 'rooms*',
                description: 'comma seperated list of room numbers',
                type: 'STRING'
            ],
            [
                name: 'iterations',
                description: 'number of iterations (defaults to 1)',
                type: 'NUMBER'
            ]
        ]
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
                title: 'Valetudo namespace',
                defaultValue: 'valetudo/',
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
@Field static final Map TopicAttributeMap = [
    'AttachmentStateAttribute/dustbin': [
        name: 'dust bin',
        attribute: 'dustbin'
    ],
    'AttachmentStateAttribute/mop': [
        name: 'mop',
        attribute: 'mop'
    ],
    'AttachmentStateAttribute/watertank': [
        name: 'water tank',
        attribute: 'waterTank'
    ],
    'BatteryStateAttribute/level': [
        name: 'battery level',
        attribute: 'battery',
        unit: '%',
        conversion: { value -> value.toInteger() }
    ],
    'BatteryStateAttribute/status': [
        name: 'battery state',
        attribute: 'batteryStatus'
    ],
    'CurrentStatisticsCapability/area': [
        name: 'area covered',
        attribute: 'area',
        unit: 'm²',
        conversion: { value -> (value.toInteger() / 10000.0) as int }
    ],
    'CurrentStatisticsCapability/time': [
        name: 'elapsed minutes',
        attribute: 'elapsedMin',
        unit: 'm',
        conversion: { value -> (value.toInteger() / 60.0) as int }
    ],
    'FanSpeedControlCapability/preset': [
        name: 'fan speed',
        attribute: 'fanSpeed'
    ],
    'SpeakerVolumeControlCapability/value': [
        name: 'volume level',
        attribute: 'volume',
        unit: '%',
        conversion: { value -> value.toInteger() }
    ],
    'MapData/segments': [
        name: 'room segments',
        attribute: 'rooms'
    ],
    'StatusStateAttribute/detail': [
        name: 'status detail',
        attribute: 'statusDetail',
        conversion: { value -> value.replace('segment', 'room') }
    ],
    'StatusStateAttribute/error_description': [
        name: 'error description',
        attribute: 'errorDescription'
    ],
    'StatusStateAttribute/status': [
        name: 'status',
        attribute: 'status'
    ],
    'WaterUsageControlCapability/preset': [
        name: 'water grade',
        attribute: 'waterGrade'
    ],
    'WifiConfigurationCapability/signal': [
        name: 'WiFi rssi',
        attribute: 'rssi',
        unit: 'dBm',
        conversion: { value -> 5 * (Math.round(value.toInteger() / 5)) } // multiples of 5db
    ],
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
    String payload = message['payload'] ?: ''
    if (settings.logEnable) { log.debug "mqtt: ${topic} = ${payload}" }
    TopicAttributeMap.find { path, map ->
        if (topic.endsWith('/' + path)) {
            String value = map.conversion ? map.conversion(payload) : payload
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
void cleanRooms(String rooms, BigDecimal iterations = 1) {
    String json = JsonOutput.toJson([
        'segment_ids': rooms.split(','),
        'iterations': iterations,
        'customOrder': true
    ])
    mqttPublish(getTopic('MapSegmentationCapability/clean/set'), json)
}

void emptyDock() {
    mqttPublish(getTopic('AutoEmptyDockManualTriggerCapability/trigger/set'), 'PERFORM')
}

void home() {
    mqttPublish(getTopic('BasicControlCapability/operation/set'), 'HOME')
}

void locate() {
    mqttPublish(getTopic('LocateCapability/locate/set'), 'PERFORM')
}

void off() {
    mqttPublish(getTopic('BasicControlCapability/operation/set'), 'STOP')
}

void on() {
    mqttPublish(getTopic('BasicControlCapability/operation/set'), 'START')
}

void pause() {
    mqttPublish(getTopic('BasicControlCapability/operation/set'), 'PAUSE')
}

void setFanSpeed(String speed) {
    mqttPublish(getTopic('/FanSpeedControlCapability/preset/set'), speed)
}

void setVolume(BigDecimal volume) {
    mqttPublish(getTopic('SpeakerVolumeControlCapability/value/set'), volume as String)
}

void setWaterGrade(String grade) {
    mqttPublish(getTopic('WaterUsageControlCapability/preset/set'), grade)
}

// ========================================================
// HELPERS
// ========================================================
void mqttConnect() {
    try {
        state.clientId = state.clientId ?: new BigDecimal(119, new Random()).toString(36)
        String uri = getBrokerUri()
        log.info "Connecting to MQTT broker at ${uri}"
        interfaces.mqtt.connect(uri, state.clientId, settings?.brokerUser, settings?.brokerPassword)
    } catch (e) {
        log.error "MQTT connect error: ${e}"
        scheduleConnect()
    }
}

void mqttSubscribe() {
    if (interfaces.mqtt.connected) {
        state.remove('reconnectDelay')
        String topic = getTopic('#')
        if (logEnable) { log.debug "SUB: ${topic}" }
        interfaces.mqtt.subscribe(topic)
    }
}

private void mqttDisconnect() {
    if (interfaces.mqtt.connected) {
        log.info 'disconnecting from MQTT broker'
        interfaces.mqtt.disconnect()
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
    return "${settings?.namespace.replaceAll('/$', '')}/${topic}"
}

private void mqttPublish(String topic, String payload = '', int qos = 0) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "send: ${topic} = ${payload}" }
        interfaces.mqtt.publish(topic, payload, qos, false)
    }
}
