/**
 *  MIT License
 *  Copyright 2019 Jonathan Bradshaw (jb@nrgup.net)
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
static final String version() { "0.1" }
static final String deviceType() { "Sensor" }

metadata {
    definition (name: "ESPHome ${deviceType()} (MQTT)", namespace: "esphome-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Initialize"
        capability "Sensor"

        attribute "deviceState", "String"
        attribute "sensor", "string"

        command "restart"
    }

    preferences() {
        section("MQTT Broker") {
            input name: "mqttBroker", type: "text", title: "MQTT Broker Host/IP", description: "ex: tcp://hostnameorip:1883", required: true, defaultValue: "tcp://mqtt:1883"
            input name: "mqttUsername", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "mqttPassword", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }

        section("MQTT Device Topics") {
            input name: "topicPrefix", type: "text", title: "Topic Prefix", description: "", required: true, defaultValue: ""
            input name: "debugTopic", type: "text", title: "Debug Topic", description: "", required: true, defaultValue: "%prefix%/debug"
            input name: "statusTopic", type: "text", title: "Status Topic", description: "", required: true, defaultValue: "%prefix%/status"
            input name: "sensorTopic", type: "text", title: "Sensor Topic State", description: "", required: true, defaultValue: "%prefix%/sensor/wifi_signal_sensor/state"
            input name: "restartTopic", type: "text", title: "Sensor Topic State", description: "", required: true, defaultValue: "%prefix%/switch/restart/command"
        }

        section("Misc") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called after MQTT successfully connects
void connected() {
    log.trace "Connected to MQTT broker at ${settings.mqttBroker}"
    mqttSubscribeTopics()
}

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver v${version()} initializing"
    unschedule()

    if (!settings.mqttBroker) {
        log.error "Unable to connect because Broker setting not configured"
        return
    }

    mqttDisconnect()    
    mqttConnect()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver v${version()} installed"
}

// Called when the device is removed.
void uninstalled() {
    mqttDisconnect()
    log.info "${device.displayName} driver v${version()} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver v${version()} configuration updated"
    log.debug settings
    state.clear()
    initialize()

    if (logEnable) runIn(1800, "logsOff")
}

/**
 *  Custom Commands
 */

void restart() {
    log.info "Restarting ${device.displayName}"
    mqttPublish(getTopic(settings.restartTopic), "ON")
}

/**
 *  ESPHome MQTT Message Parsing
 */

private void parsePayload(String topic, String payload) {
    List<Map> events = []

    switch (topic) {
        case getTopic(settings.sensorTopic):
            events << newEvent("sensor", payload)
            break
    }

    events.each { sendEvent(it) }
}

/**
 *  Common MQTT communication methods
 */

private String getTopic(String topic)
{
    return topic.replaceFirst("%prefix%", settings.topicPrefix)
}

private Map newEvent(String name, value, unit = null) {
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: "${device.displayName} ${name} is ${value}${unit ?: ''}"
    ]
}

private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
}

private boolean mqttConnect() {
    unschedule("mqttConnect")
    try {
        def hub = device.getHub()
        def mqtt = interfaces.mqtt
        String clientId = hub.hardwareID + "-" + device.id
        log.trace "Connecting to MQTT broker at ${settings.mqttBroker}"
        state.mqttConnectCount = (state?.mqttConnectCount ?: 0) + 1
        mqtt.connect(
            settings.mqttBroker,
            clientId,
            settings?.mqttUsername,
            settings?.mqttPassword
        )
        return true
    } catch(e) {
        log.error "MQTT connect error: ${e}"
        runInMillis(new Random(now()).nextInt(90000), "mqttConnect")
    }

    return false
}

private void mqttDisconnect() {
    if (interfaces.mqtt.isConnected()) {
        log.trace "Disconnecting from MQTT broker at ${settings?.mqttBroker}"
    }

    sendEvent(name: "deviceState", value: "offline", descriptionText: "${device.displayName} connection closed by driver")
    try {
        interfaces.mqtt.disconnect()
    }
    catch (any)
    {
        // Ignore any exceptions since we are disconnecting
    }
}

private void mqttPublish(String topic, String payload = "") {
    final int qos = 1 // at least once delivery

    if (interfaces.mqtt.isConnected()) {
        if (logEnable) log.debug "MQTT Publish > ${topic} = ${payload}"
        interfaces.mqtt.publish(topic, payload, qos, false)
        state.mqttTransmitCount = (state?.mqttTransmitCount ?: 0) + 1
    } else {
        log.warn "MQTT not connected, unable to publish ${topic} = ${payload}"
        runInMillis(new Random(now()).nextInt(30000), "initialize")
    }
}

// Called with MQTT client status messages
void mqttClientStatus(String status) {
    // The string that is passed to this method with start with "Error" if an error occurred or "Status" if this is just a status message.
    def parts = status.split(': ')
    switch (parts[0]) {
        case 'Error':
            log.warn "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection lost':
                case 'send error':
                    runInMillis(new Random(now()).nextInt(90000), "initialize")
                    break
            }
            break
        case 'Status':
            log.info "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection succeeded':
                    // without this delay the `parse` method is never called
                    // (it seems that there needs to be some delay after connection to subscribe)
                    runInMillis(1000, connected)
                    break
            }
            break
        default:
        	if (logEnable) log.debug "MQTT: ${message}"
            break
    }
}

// Called to parse received MQTT data
def parse(data) {
    Map message = interfaces.mqtt.parseMessage(data)
    String topic = message.get("topic")
    String payload = message.get("payload")
    if (logEnable) log.debug "MQTT Receive < ${topic} = ${payload}"
    state.mqttReceiveCount = (state?.mqttReceiveCount ?: 0) + 1
    state.mqttReceiveTime = now()
    state.mqttLastTopic = topic
    state.mqttLastPayload = payload

    if (topic == getTopic(settings.statusTopic)) {
        def event = [
            name: "deviceState",
            value: payload.toLowerCase()
        ]
        event.descriptionText = "${device.displayName} ${event.name} is ${event.value}"
        sendEvent(event)
        log.info event.descriptionText
    } else if (topic == getTopic(settings.debugTopic)) {
        if (logEnable) log.debug payload
    } else {
        parsePayload(topic, payload)
    }
}

private void mqttSubscribeTopics() {
    int qos = 1 // at least once delivery
    String topic = settings.topicPrefix.plus("/#")
    if (logEnable) log.trace "Subscribing to topic: ${topic}"
    interfaces.mqtt.subscribe(topic, qos)
}