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

metadata {
    definition (name: "MQTT Notification", namespace: "mqtt", author: "Jonathan Bradshaw") {
        capability "Notification"
    }

    preferences() {
        section("MQTT Device Topics") {
            input name: "fullTopic", type: "text", title: "Topic to monitor", description: "For new Tasmota devices", required: true, defaultValue: "%prefix%/%topic%/"
        }

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

// Called after MQTT successfully connects
void connected() {
    log.info "Connected to MQTT broker at ${settings.mqttBroker}"
}

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

// Called with MQTT client status messages
void mqttClientStatus(String message) {
    if (message.startsWith("Error")) {
    	log.error "MQTT: ${message}"
        runInMillis(new Random(now()).nextInt(90000), "initialize")
    } else {
    	if (logEnable) log.debug "MQTT: ${message}"
    }
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

/**
 *  Capability: Notification
 */

void deviceNotification(text) {
    mqttPublish(settings.fullTopic, text)
}

private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
}

private boolean mqttConnect() {
    try {
        def hub = device.getHub()
        def mqtt = interfaces.mqtt
        String clientId = hub.hardwareID + "-" + device.id
        log.info "Connecting to MQTT broker at ${settings.mqttBroker}"
        state.mqttConnectCount = (state?.mqttConnectCount ?: 0) + 1

        mqtt.connect(
            settings.mqttBroker,
            clientId,
            settings?.mqttUsername,
            settings?.mqttPassword
        )

        pauseExecution(1000)
        connected()
        sendEvent (name: "connection", value: "online", descriptionText: "${device.displayName} connection now online")
        return true
    } catch(e) {
        log.error "MQTT connect error: ${e}"
        runInMillis(new Random(now()).nextInt(90000), "mqttConnect")
    }

    return false
}

private void mqttDisconnect() {
    if (interfaces.mqtt.isConnected()) {
        log.info "Disconnecting from MQTT broker at ${settings?.mqttBroker}"
    }

    try {
        interfaces.mqtt.disconnect()
        sendEvent (name: "connection", value: "offline", descriptionText: "${device.displayName} connection now offline")
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
    }
}
