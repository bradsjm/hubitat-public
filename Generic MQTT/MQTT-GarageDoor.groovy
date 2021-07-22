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

metadata {
    definition (name: 'MQTT - Garage Door', namespace: 'nrgup', author: 'Jonathan Bradshaw') {
        capability 'GarageDoorControl'
        capability 'Initialize'

        attribute 'status', 'string'
    }

    preferences {
        section {
            input name: 'stateTopic',
                  type: 'text',
                  title: 'MQTT State Topic',
                  description: 'ex: /garagedoor_control/state',
                  required: true

            input name: 'commandTopic',
                  type: 'text',
                  title: 'MQTT Command Topic',
                  description: 'ex: /garagedoor_control/command',
                  required: true

            input name: 'availabilityTopic',
                  type: 'text',
                  title: 'MQTT Availability Topic',
                  description: 'ex: /garagedoor/status',
                  required: false

            input name: 'travelTime',
                  type: 'enum',
                  title: 'Door Travel Time (seconds)',
                  description: 'Maximum time for door to cycle',
                  defaultValue: 60,
                  required: true,
                  options: [
                    15: '15 seconds',
                    30: '30 seconds',
                    45: '45 seconds',
                    60: '60 seconds',
                    90: '90 seconds'
                  ]
        }

        section {
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
        }

        section {
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

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"
    unschedule()
    state.clear()

    if (!settings.mqttBroker) {
        log.error 'Unable to connect because Broker setting not configured'
        return
    }

    mqttDisconnect()
    mqttConnect()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called with MQTT client status messages
void mqttClientStatus(String status) {
    mqttParseStatus(status)
}

// Called to parse received MQTT data
void parse(String data) {
    Map message = interfaces.mqtt.parseMessage(data)
    if (logEnable) { log.debug "RCV: ${message}" }

    if (message.topic == settings.availabilityTopic) {
        boolean isOnline = message.payload.toLowerCase() in ['online', 'on', '1', 'true']
        sendEvent(newEvent('status', isOnline ? 'online' : 'offline'))
        return
    }

    if (message.topic == settings.stateTopic) {
        String doorState = device.currentValue('door')
        if (logEnable) { log.debug "Current door state is ${doorState}" }
        switch (doorState) {
            case 'opening':
                if (message.payload == 'open') {
                    updateState(message.payload)
                } else {
                    runIn(settings.travelTime as int, 'updateState', [ data: message.payload ])
                }
                break
            case 'closing':
                if (message.payload == 'closed') {
                    updateState(message.payload)
                } else {
                    runIn(settings.travelTime as int, 'updateState', [ data: message.payload ])
                }
                break
            default:
                updateState(message.payload)
        }
    }
}

// Called when the device is removed.
void uninstalled() {
    mqttDisconnect()
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

/**
 *  Capability: Door Control
 */

// Open door
void open() {
    String doorState = device.currentValue('door')
    if (logEnable) { log.debug "Current door state is ${doorState}" }
    if (doorState != 'closed' || doorState == 'opening') {
        log.info "${device.displayName} ignoring open request (door is ${doorState})"
        return
    }

    mqttPublish(settings.commandTopic, 'open')
    sendEvent(newEvent('door', 'opening'))
}

// Close door
void close() {
    String doorState = device.currentValue('door')
    if (logEnable) { log.debug "Current door state is ${doorState}" }
    if (doorState != 'open' || doorState == 'closing') {
        log.info "${device.displayName} ignoring close request (door is ${doorState})"
        return
    }

    mqttPublish(settings.commandTopic, 'close')
    sendEvent(newEvent('door', 'closing'))
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void updateState(String value) {
    unschedule('updateState')
    String doorState = device.currentValue('door')
    if (logEnable) { log.debug "Update door state from ${doorState} to ${value}" }
    sendEvent(newEvent('door', value))
}

/**
 *  Implementation methods
 */

/* groovylint-disable-next-line UnusedPrivateMethod */
private void subscribe() {
    mqttSubscribe(settings.stateTopic)
    if (settings.availabilityTopic) {
        mqttSubscribe(settings.availabilityTopic)
    }
}

/**
 *  Common utility methods
 */

private Map newEvent(String name, Object value, Map params = [:]) {
    String splitName = splitCamelCase(name).toLowerCase()
    String description = "${device.displayName} ${splitName} is ${value}${params.unit ?: ''}"
    log.info description
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: settings.logTextEnable ? description : ''
    ] + params
}

private String splitCamelCase(String s) {
   return s.replaceAll(
      String.format('%s|%s|%s',
         '(?<=[A-Z])(?=[A-Z][a-z])',
         '(?<=[^A-Z])(?=[A-Z])',
         '(?<=[A-Za-z])(?=[^A-Za-z])'
      ),
      ' '
   )
}

/**
 *  Common Tasmota MQTT communication methods
 */

private void mqttConnect() {
    unschedule('mqttConnect')
    try {
        String clientId = device.hub.hardwareID + '-' + device.id
        log.info "Connecting to MQTT broker at ${settings.mqttBroker}"
        interfaces.mqtt.connect(
            settings.mqttBroker,
            clientId,
            settings?.mqttUsername,
            settings?.mqttPassword
        )
    } catch (e) {
        log.error "MQTT connect error: ${e}"
        runIn(30, 'mqttConnect')
    }
}

private void mqttDisconnect() {
    if (interfaces.mqtt.connected) {
        log.info "Disconnecting from MQTT broker at ${settings?.mqttBroker}"
        interfaces.mqtt.disconnect()
    }

    sendEvent(newEvent('status', 'offline'))
}

private void mqttPublish(String topic, String payload = '', int qos = 0) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "PUB: ${topic} = ${payload}" }
        interfaces.mqtt.publish(topic, payload, qos, false)
    }
}

private void mqttSubscribe(String topic) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "SUB: ${topic}" }
        interfaces.mqtt.subscribe(topic)
    }
}

private void mqttParseStatus(String status) {
    // The string that is passed to this method with start with "Error" if an error occurred
    // or "Status" if this is just a status message.
    List<String> parts = status.split(': ')
    switch (parts[0]) {
        case 'Error':
            log.warn "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection lost':
                case 'send error':
                    runIn(30, 'initialize')
                    break
            }
            break
        case 'Status':
            log.info "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection succeeded':
                    sendEvent(newEvent('status', 'connected'))
                    // without this delay the `parse` method is never called
                    // (it seems that there needs to be some delay after connection to subscribe)
                    runIn(1, 'subscribe')
                    break
            }
            break
        default:
            log.warn "MQTT ${status}"
            break
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}

