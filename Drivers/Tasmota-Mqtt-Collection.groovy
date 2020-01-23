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
static final String deviceType() { "Collection" }

import java.security.MessageDigest;

metadata {
    definition (name: "Tasmota MQTT ${deviceType()}", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Refresh"
        capability "Configure"
        attribute "connection", "String"
    }

    preferences() {
        section("MQTT Device Topics") {
            input name: "fullTopic", type: "text", title: "Topic to monitor", description: "For new Tasmota devices", required: true, defaultValue: "%prefix%/%topic%/"
            input name: "groupTopic", type: "text", title: "Group Topic to use", description: "If set, will update devices", required: false
        }

        section("MQTT Broker") {
            input name: "mqttBroker", type: "text", title: "MQTT Broker Host/IP", description: "ex: tcp://hostnameorip:1883", required: true, defaultValue: "tcp://mqtt:1883"
            input name: "mqttUsername", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "mqttPassword", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }

        section("Misc") {
            input name: "driverType", type: "enum", title: "MQTT Driver", description: "Driver for discovered devices", options: ["Tasmota MQTT Sensor", "Tasmota MQTT Switch", "Tasmota MQTT RGBW/CT"], required: true, defaultValue: "Tasmota MQTT Sensor"
            input name: "logEnable", type: "bool", title: "Enable Debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

void configure() {
    // Update group topic for all devices
    if (settings.groupTopic) {
        getChildDevices().each {
            log.info "Setting group topic for ${it.name} to ${settings.groupTopic}"
            it.setGroupTopic(settings.groupTopic)
        }
    }
}

// Called after MQTT successfully connects
void connected() {
    mqttSubscribeTopics()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver v${version()} installed"
}

// Called with MQTT client status messages
void mqttClientStatus(String message) {
	if (logEnable) log.debug "MQTT ${message}"

    if (message.startsWith("Error")) {
        mqttDisconnect()
        mqttCheckConnected()
    }
}

// Called to parse received MQTT data
void parse(data) {
    Map message = interfaces.mqtt.parseMessage(data)
    String topic = message.get("topic")
    String payload = message.get("payload")
    if (logEnable) log.debug "MQTT RECEIVE <--- ${topic} = ${payload}"
    state.mqttReceiveCount = (state?.mqttReceiveCount ?: 0) + 1
    if (payload == "Online")
        createChildDevice(topic.toLowerCase())
}

void refresh() {
    log.info "Refreshing state of ${device.name}"
    state.clear()
    mqttDisconnect()
    unschedule()

    if (settings.mqttBroker) {
        mqttConnect()
    } else {
        log.warn "${device.displayName} requires a broker configured to connect"
    }
}

// Called when the device is removed.
void uninstalled() {
    mqttDisconnect()
    log.info "${device.displayName} driver v${version()} uninstalled"
}

// Called when the preferences of a device are updated.
void updated() {
    log.info "${device.displayName} driver v${version()} configuration updated"
    log.debug settings
    refresh()

    if (logEnable) runIn(1800, "logsOff")
}

/**
 *  Child Device Management
 */

void createChildDevice(String topic) {
    String deviceNetworkId = generateMD5(topic)
    String deviceTopic = topic.split('/')[-2]

    def childDevice = getChildDevice(deviceNetworkId)
    if (childDevice) {
        if (logEnable) log.debug "Tasmota device: ${deviceTopic} is connected to ${childDevice.label ?: childDevice.name}"
        return
    }

    log.info "Creating new Tasmota device: ${deviceTopic}"
    childDevice = addChildDevice(
        "tasmota-mqtt",
        settings.driverType,
        deviceNetworkId,
        [
            "name": "mqtt-".plus(deviceTopic)
        ]
    )

    childDevice.updateSetting("deviceTopic", deviceTopic)
    childDevice.updateSetting("fullTopic", settings.fullTopic)
    childDevice.updateSetting("mqttBroker", settings.mqttBroker)
    if (settings?.mqttUsername)
        childDevice.updateSetting("mqttUsername", settings.mqttUsername)
    if (settings?.mqttPassword)
    childDevice.updateSetting("mqttPassword", settings.mqttPassword)
    childDevice.refresh()
}

String generateMD5(String s){
    String md5 = MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString()
    return [ md5[0..7], md5[8..11], md5[12..15], md5[16..19], md5[20..md5.length()-1] ].join('-')
}

/**
 *  Common Tasmota MQTT communication methods
 */

private int getRetrySeconds() {
    final minimumRetrySec = 20
    final maximumRetrySec = minimumRetrySec * 6
    int count = state.mqttRetryCount ?: 0
    int jitter = new Random().nextInt(minimumRetrySec.intdiv(2))
    state.mqttRetryCount = count + 1
    return Math.min(minimumRetrySec * Math.pow(2, count) + jitter, maximumRetrySec)
}

private String getTopic(String postfix)
{
    getTopic("cmnd", postfix)
}

private String getTopic(String topic, String prefix, String postfix = "")
{
    if (!settings.fullTopic.endsWith("/")) settings.fullTopic += "/"
    settings.fullTopic
        .replaceFirst("%prefix%", prefix)
        .replaceFirst("%topic%", topic)
        .plus(postfix)
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

private boolean mqttCheckConnected() {
    if (interfaces.mqtt.isConnected() == false) {
        log.warn "MQTT is not connected"
        sendEvent (name: "connection", value: "offline", descriptionText: "${device.displayName} connection now offline")
        if (!mqttConnect()) {
            int waitSeconds = getRetrySeconds()
            log.info "Retrying MQTT connection in ${waitSeconds} seconds"
            unschedule("mqttCheckConnected")
            runIn(waitSeconds, "mqttCheckConnected")
            return false
        }
    }

    unschedule("mqttCheckConnected")
    state.remove("mqttRetryCount")
    return true
}

private boolean mqttConnect() {
    try {
        def hub = device.getHub()
        def mqtt = interfaces.mqtt
        String clientId = device.getDeviceNetworkId()
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

private void mqttSubscribeTopics() {
    int qos = 1 // at least once delivery
    if (!settings.fullTopic.endsWith("/")) settings.fullTopic += "/"
    def topic = settings.fullTopic
        .replaceFirst("%prefix%", "tele")
        .replaceFirst("%topic%", "+")
        .plus("LWT")

    if (logEnable) log.debug "Subscribing to Tasmota telemetry topic: ${topic}"
    interfaces.mqtt.subscribe(topic, qos)
}
