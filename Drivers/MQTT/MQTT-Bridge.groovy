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
static final String version() { "1.0" }

metadata {
    definition (name: "MQTT Bridge", namespace: "mqtt", author: "Jonathan Bradshaw") {  
        capability "Initialize"
        capability "PresenceSensor"
         
        command "publish", [
            [ name:"topic", type: "STRING", description: "Topic"],
            [ name:"message", type: "STRING", description: "Message"
        ]]
		command "subscribe", [[ name:"topic", type: "STRING", description: "Topic"]]
		command "unsubscribe", [[ name:"topic", type: "STRING", description: "Topic"]]
    }

    preferences() {
        section("MQTT Broker") {
            input name: "mqttBroker", type: "text", title: "MQTT Broker Host/IP", description: "ex: tcp://hostnameorip:1883", required: true, defaultValue: "tcp://mqtt:1883"
            input name: "mqttUsername", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "mqttPassword", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }

        section("Misc") {
            input name: "logEnable", type: "bool", title: "Enable Debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

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
    initialize()

    if (logEnable) runIn(1800, "logsOff")
}

// Publish mqtt payload to specified topic
void publish(String topic, String payload = "", int qos = 0, boolean retain = false) {
    if (interfaces.mqtt.isConnected()) {
        if (logEnable) log.debug "PUB: ${topic} = ${payload}"
        interfaces.mqtt.publish(topic, payload, qos, retain)
        state.TransmitCount = (state?.TransmitCount ?: 0) + 1
    } else {
        log.warn "MQTT not connected, unable to publish ${topic} = ${payload}"
        runInMillis(new Random(now()).nextInt(30000), "initialize")
    }
}

void subscribe(String topic, int qos = 0) {
    if (interfaces.mqtt.isConnected()) {
        if (logEnable) log.debug "SUB: ${topic}"
        interfaces.mqtt.subscribe(topic, qos)
    } else {
        log.warn "MQTT not connected, unable to subscribe ${topic}"
        runInMillis(new Random(now()).nextInt(30000), "initialize")
    }
}

void unsubscribe(String topic) {
    if (interfaces.mqtt.isConnected()) {
        if (logEnable) log.debug "UNSUB: ${topic}"
        interfaces.mqtt.unsubscribe(topic)
    } else {
        log.warn "MQTT not connected, unable to unsubscribe ${topic}"
    }
}

// Called to parse received MQTT data
def parse(data) {
    def message = interfaces.mqtt.parseMessage(data)
    String topic = message.get("topic")
    String payload = message.get("payload")
    if (logEnable) log.debug "RCV: ${topic} = ${payload}"
    state.ReceiveCount = (state?.mqttReceiveCount ?: 0) + 1

    // Ignore empty payloads
    if (!payload) return

    parent.parseMessage(topic, payload)
}

private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
}

/**
 *  Common Tasmota MQTT communication methods
 */

private void mqttConnect() {
    unschedule("mqttConnect")
    try {
        def hub = device.getHub()
        def mqtt = interfaces.mqtt
        String clientId = hub.hardwareID + "-" + device.id
        log.info "Connecting to MQTT broker at ${settings.mqttBroker}"
        state.ConnectCount = (state?.ConnectCount ?: 0) + 1
        mqtt.connect(
            settings.mqttBroker,
            clientId,
            settings?.mqttUsername,
            settings?.mqttPassword,
            lastWillTopic: "hubitat/LWT",
            lastWillQos: 0, 
            lastWillMessage: "offline", 
            lastWillRetain: true
        )
    } catch(e) {
        log.error "MQTT connect error: ${e}"
        runInMillis(new Random(now()).nextInt(30000), "mqttConnect")
    }
}

private void mqttDisconnect() {
    if (interfaces.mqtt.isConnected()) {
        log.info "Disconnecting from MQTT broker at ${settings?.mqttBroker}"
    }

    try {
        interfaces.mqtt.disconnect()
    }
    catch (any)
    {
        // Ignore any exceptions since we are disconnecting
    }
}

void connected() {
    log.info "Connected to MQTT broker at ${settings.mqttBroker}"
    sendEvent(name: "presence", value: "present", descriptionText: "${device.displayName} is connected")
    publish("hubitat/LWT", "online", 0, true)
    parent.connected()
}

// Called with MQTT client status messages
void mqttClientStatus(String status) {
    // The string that is passed to this method with start with "Error" if an error occurred or "Status" if this is just a status message.
    def parts = status.split(": ")
    switch (parts[0]) {
        case "Error":
            log.warn "MQTT ${status}"
            switch (parts[1]) {
                case "Connection lost":
                case "send error":
                    sendEvent(name: "presence", value: "not present", descriptionText: "${device.displayName} {$parts[1]}")
                    runInMillis(new Random(now()).nextInt(30000), "initialize")
                    break
            }
            break
        case "Status":
            log.info "MQTT ${status}"
            switch (parts[1]) {
                case "Connection succeeded":
                    // without this delay the `parse` method is never called
                    // (it seems that there needs to be some delay after connection to subscribe)
                    runInMillis(1000, "connected")
                    break
            }
            break
        default:
        	if (logEnable) log.debug "MQTT ${status}"
            break
    }
}
