/**
 *  MIT License
 *  Copyright 2021 Jonathan Bradshaw (jb@nrgup.net)
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
    // WF30 - Eva Logik 3-Way Switch
    // https://templates.blakadder.com/evalogik_WF30.html
    definition (name: 'Eva Logik Switch (WF30)', namespace: 'esphome', author: 'Jonathan Bradshaw') {
        capability 'Initialize'
        capability 'DoubleTapableButton'
        capability 'HoldableButton'
        capability 'PushableButton'
        capability 'ReleasableButton'
        capability 'SignalStrength'
        capability 'Switch'

        attribute 'status', 'string'

        command 'restart'
    }

    preferences {
        section {
            input name: 'deviceName',
                  type: 'text',
                  title: 'ESPHome Device Name',
                  required: true

            input name: 'topicPrefix',
                  type: 'text',
                  title: 'ESPHome MQTT Topic Prefix',
                  defaultValue: 'esphome'
                  required: false
        }

        section {
            input name: 'mqttBroker',
                  type: 'text',
                  title: 'MQTT Broker Host/IP',
                  required: true,
                  defaultValue: 'tcp://10.10.10.10:1883'

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

    sendEvent(newEvent('numberOfButtons', '2'))

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

void restart() {
    log.info "${device.displayName} Restarting"
    mqttPublish("${topicPrefix}/${deviceName}/switch/restart/command", 'ON')
}

void off() {
    log.info "${device.displayName} Turning relay off"
    mqttPublish("${topicPrefix}/${deviceName}/switch/relay/command", 'OFF')
}

void on() {
    log.info "${device.displayName} Turning relay on"
    mqttPublish("${topicPrefix}/${deviceName}/switch/relay/command", 'ON')
}

// Called to parse received MQTT data
void parse(String data) {
    String topicPrefix = settings.topicPrefix
    String deviceName = settings.deviceName
    Map message = interfaces.mqtt.parseMessage(data)
    if (logEnable) { log.debug "RCV: ${message}" }

    switch (message.topic) {
        case "${topicPrefix}/${deviceName}/status":
            sendEvent(newEvent('status', message.payload))
            break
        case "${topicPrefix}/${deviceName}/debug":
            if (logTextEnable) { log.trace message.payload[7..-4] as String }
            break
        case "${topicPrefix}/${deviceName}/switch/relay/state":
            sendEvent(newEvent('switch', message.payload.toLowerCase()))
            break
        case "${topicPrefix}/${deviceName}/sensor/wifi_signal/state":
            sendEvent(newEvent('rssi', message.payload))
            break
        case "${topicPrefix}/${deviceName}/sensor/button1/state":
            parseButton(1, message.payload as String)
            break
        case "${topicPrefix}/${deviceName}/sensor/button2/state":
            parseButton(2, message.payload as String)
            break
    }
}

void parseButton(Integer button, String value) {
    switch (value) {
        case 'SINGLE':
            sendEvent(newEvent('pushed', button, [ isStateChange: true ]))
            break
        case 'DOUBLE':
            sendEvent(newEvent('doubleTapped', button, [ isStateChange: true ]))
            break
        case 'HOLD':
            sendEvent(newEvent('held', button, [ isStateChange: true ]))
            break
        case 'RELEASE':
            sendEvent(newEvent('released', button, [ isStateChange: true ]))
            break
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
 *  Implementation methods
 */

/* groovylint-disable-next-line UnusedPrivateMethod */
private void subscribe() {
    mqttSubscribe("${topicPrefix}/${deviceName}/#")
}

/**
 *  Common utility methods
 */

private Map newEvent(String name, Object value, Map params = [:]) {
    String splitName = splitCamelCase(name).toLowerCase()
    String description = "${device.displayName} ${splitName} is ${value}${params.unit ?: ''}"
    if (settings.logTextEnable) { log.info description }
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
        sendEvent(newEvent('status', 'connecting'))
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

    sendEvent(newEvent('status', 'disconnected'))
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
            runIn(30, 'initialize')
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

private void mqttPublish(String topic, String payload = '', int qos = 0) {
    if (interfaces.mqtt.connected) {
        if (settings.logEnable) { log.debug "PUB: ${topic} = ${payload}" }
        interfaces.mqtt.publish(topic, payload, qos, false)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}
