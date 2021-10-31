 /**
  *  WeatherFlow MQTT Hubitat Driver
  *
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

metadata {
    definition (
        name: 'WeatherFlow (MQTT)',
        namespace: 'nrgup',
        author: 'Jonathan Bradshaw'
    ) {
        capability 'Initialize'
        capability 'Battery'
        capability 'Relative Humidity Measurement'
        capability 'Illuminance Measurement'
        capability 'Pressure Measurement'
        capability 'Liquid Flow Rate'
        capability 'Sensor'
        capability 'Temperature Measurement'
        capability 'Ultraviolet Index'
        capability 'Voltage Measurement'
        capability 'Water Sensor'

        preferences {
            input name: 'mqttBroker',
                  type: 'text',
                  title: 'MQTT Broker Host/IP',
                  description: 'ex: tcp://hostnameorip:1883',
                  required: true,
                  defaultValue: 'tcp://mqtt:1883'

            input name: 'mqttUsername',
                  type: 'text',
                  title: 'MQTT Username',
                  description: '(blank if none)',
                  required: false

            input name: 'mqttPassword',
                  type: 'password',
                  title: 'MQTT Password',
                  description: '(blank if none)',
                  required: false

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: true

            input name: 'logTextEnable',
                  type: 'bool',
                  title: 'Enable descriptionText logging',
                  required: false,
                  defaultValue: true
        }
    }
}

import groovy.json.JsonSlurper
import groovy.transform.Field

// Random number generator
@Field static final Random random = new Random()

@Field static final List<String> topics = [
    'homeassistant/sensor/weatherflow2mqtt/obs_air/state',
    'homeassistant/sensor/weatherflow2mqtt/obs_sky/state'
]

@Field static final Map mapping = [
    'relative_humidity': [ name: 'humidity', unit: '%' ],
    'station_pressure': [ name: 'pressure', unit: 'mb' ],
    'air_temperature': [ name: 'temperature', unit: '&deg;F', f: { c -> (c * 1.8) + 32 } ],
    'uv': [ name: 'ultravioletIndex' ],
    'precipitation_type': [ name: 'water', f: { t -> t == 'None' ? 'dry' : 'wet' } ],
    'battery_level_tempest': [ name: 'battery', unit: '%' ],
    'illuminance': [ name: 'illuminance', unit: 'lx' ],
    'rain_rate': [ name: 'rate' ],
    'battery': [ name: 'voltage', unit: 'V' ]
]

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"
    unschedule()

    if (!settings.mqttBroker) {
        log.error 'Unable to connect because Broker setting not configured'
        return
    }

    mqttConnect()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called when MQTT client state changes
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            runIn(1, 'subscribe')
            break
        case 'Error: Connection lost':
        case 'Error: send error':
            log.error "${device.displayName} MQTT connection error: " + status
            runIn(15 + random.nextInt(45), 'initialize')
            break
    }
}

// Called to parse received MQTT data
void parse(String data) {
    Map message = interfaces.mqtt.parseMessage(data)
    if (logEnable) { log.debug "RCV: ${message}" }
    Map json = new JsonSlurper().parseText(message.payload)
    json.each { e ->
        Map map = mapping[e.key]
        if (map) {
            String value = map.f ? map.f(e.value) : e.value
            Map event = [ name: map.name, value: value, unit: map.unit ?: '',
                          descriptionText: "${map.name} is ${value}${map.unit ?: ''}" ]
            if (logTextEnable) { log.info "${device} ${event.descriptionText}" }
            sendEvent(event)
        }
    }
}

// Called when MQTT is connected to subscribe to topics
void subscribe() {
    topics.each { t -> mqttSubscribe(t) }
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

/**
 *  Common Tasmota MQTT communication methods
 */

private void mqttConnect() {
    unschedule('mqttConnect')
    String clientId = device.hub.hardwareID + '-' + device.id
    try {
        log.info "Connecting to MQTT broker at ${settings.mqttBroker}"
        interfaces.mqtt.connect(
            settings.mqttBroker,
            clientId,
            settings?.mqttUsername,
            settings?.mqttPassword
        )
    } catch (e) {
        if (e != 'Client is disconnected (32101)') {
            log.error "MQTT connect error: ${e}"
            runIn(15 + random.nextInt(45), 'mqttConnect')
        }
        runIn(1, 'mqttConnect')
    }
}

private void mqttDisconnect() {
    if (interfaces.mqtt.connected) {
        log.info "Disconnecting from MQTT broker at ${settings?.mqttBroker}"
        interfaces.mqtt.disconnect()
    }
}

private void mqttSubscribe(String topic) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "SUB: ${topic}" }
        interfaces.mqtt.subscribe(topic)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}
