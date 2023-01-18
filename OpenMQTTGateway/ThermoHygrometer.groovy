/**
 *  MIT License
 *  Copyright 2023 Jonathan Bradshaw (jb@nrgup.net)
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

//{"id":"A4:C1:38:12:F8:74","mac_type":0,"name":"GVH5075_F874","rssi":-85,"brand":"Govee","model":"Smart Thermo Hygrometer","model_id":"H5075","cidc":false,"tempc":20.1,"tempf":68.18,"hum":42.1,"batt":99}

import groovy.json.JsonSlurper
import groovy.transform.Field

metadata {
    definition(name: 'Smart Thermo Hygrometer', namespace: 'govee', author: 'Jonathan Bradshaw',
        importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/OpenMQTTGateway/ThermoHygrometer.groovy') {
        capability 'Initialize'
        capability 'Battery'
        capability 'RelativeHumidityMeasurement'
        capability 'SignalStrength'
        capability 'TemperatureMeasurement'

        attribute 'dewPoint', 'number'
        attribute 'networkStatus', 'enum', [ 'connecting', 'online', 'offline' ]
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

            input name: 'mqttTopic',
                type: 'string',
                title: 'MQTT Topic',
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
    if (payload.startsWith('{')) {
        Map json = new JsonSlurper().parseText(payload)
        BigDecimal dewPoint = dewPointC(json['tempc'] as BigDecimal, json['hum'] as BigDecimal)
        updateValue('dewPoint', celsiusToFahrenheit(dewPoint).toDouble().round(1), 'F')
        updateValue('battery', json['batt'], '%')
        updateValue('humidity', json['hum'], 'F')
        updateValue('rssi', json['rssi'], 'dBm')
        updateValue('temperature', json['tempf'], 'F')
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

// ========================================================
// HELPERS
// ========================================================
void mqttConnect() {
    try {
        state.clientId = state.clientId ?: new BigInteger(119, random).toString(36)
        String uri = getBrokerUri()
        log.info "Connecting to MQTT broker at ${uri}"
        sendEvent([ name: 'networkStatus', value: 'connecting', descriptionText: "connecting to ${uri}" ])
        interfaces.mqtt.connect(uri, state.clientId, settings?.brokerUser, settings?.brokerPassword, byteInterface: false)
    } catch (e) {
        log.error "MQTT connect error: ${e}"
        sendEvent([ name: 'networkStatus', value: 'disconnected', descriptionText: "error ${e}" ])
        scheduleConnect()
    }
}

void mqttSubscribe() {
    if (interfaces.mqtt.connected) {
        state.remove('reconnectDelay')
        String topic = settings.mqttTopic
        if (logEnable) { log.debug "SUB: ${topic}" }
        sendEvent([ name: 'networkStatus', value: 'connected', descriptionText: "subscribing to ${topic}" ])
        interfaces.mqtt.subscribe(topic)
    }
}

private static BigDecimal dewPointC(BigDecimal t, BigDecimal rh) {
    return 243.04 * (Math.log(rh / 100) + ((17.625 * t) / (243.04 + t))) / (17.625 - Math.log(rh / 100) - ((17.625 * t) / (243.04 + t)))
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

private void updateValue(String attribute, Object value, String unit = '') {
    if (value != null && (device.currentValue(attribute) as String) != (value as String)) {
        String descriptionText = "${attribute} is ${value}${unit}"
        sendEvent(name: attribute, value: value, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}
