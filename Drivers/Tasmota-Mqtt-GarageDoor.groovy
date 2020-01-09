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

static final String deviceType() { "GarageDoor" }

metadata {
    definition (name: "Tasmota ${deviceType()} v${version()}", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Actuator"
        capability "Refresh"
        capability "Sensor"
        capability "GarageDoorControl"
        capability "PresenceSensor"

        command "soundBuzzer", [
            [
                name: "Duration*",
                type: "number",
                description: "Seconds (0 to 20)"
            ]
        ]

        attribute "connection", "string"
        attribute "wifiSignal", "string"
        attribute "distance", "number"
    }

    preferences() {
        section("MQTT Device Topics") {
            input name: "deviceTopic", type: "text", title: "Device Topic (Name)", description: "Topic value from Tasmota", required: true, defaultValue: "tasmota"
            input name: "fullTopic", type: "text", title: "Full Topic Template", description: "Full Topic value from Tasmota", required: true, defaultValue: "%prefix%/%topic%/"
        }

        section("MQTT Broker") {
            input name: "mqttBroker", type: "text", title: "MQTT Broker Host/IP", description: "ex: tcp://hostnameorip:1883", required: true, defaultValue: "tcp://mqtt:1883"
            input name: "mqttUsername", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "mqttPassword", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
            input name: "mqttQOS", type: "text", title: "MQTT QOS setting", description: "0 = Only Once, 1 = At Least Once*, 2 = Exactly Once", required: true, defaultValue: "1"
        }

        section("Opener") {
            input name: "doorThreshold", type: "number", title: "Door Threshold", description: "Distance at which door is regarded as open (cm)", required: true, defaultValue: 150
            input name: "vehicleThreshold", type: "number", title: "Vehicle Threshold", description: "Distance at which car is detected (cm)", required: true, defaultValue: 50
            input name: "pulseTime", type: "number", title: "Relay Pulse Time", description: "In tenths of a second (ms)", required: true, defaultValue: 7
            input name: "travelTime", type: "number", title: "Door Travel Time", description: "Time to fully open/close (seconds)", required: true, defaultValue: 30
            input name: "telePeriod", type: "number", title: "Sensor Read Interval", description: "Time between distance readings (seconds)", required: true, defaultValue: 5
            input name: "useMetric", type: "bool", title: "Use metric", description: "Displays distance using centimeters", required: true, defaultValue: true
            input name: "warnOnClose", type: "number", title: "Warning Buzzer on Closing", description: "Alarm beeps before closing (0 for none)", required: true, defaultValue: 3
        }

        section("Misc") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
            input name: "watchdogEnable", type: "bool", title: "Enable watchdog logging", description: "Checks for activity every 5 minutes", required: true, defaultValue: true
        }
    }
}


/**
 *  Hubitat Driver Event Handlers
 */

// Called after MQTT successfully connects
void connected() {
    mqttSubscribeTopics()

    if (settings.watchdogEnable) {
    	int randomSeconds = new Random(now()).nextInt(60)
        schedule("${randomSeconds} 0/5 * * * ?", "mqttCheckReceiveTime")
    }

    // Set options
    def commandTopic = getTopic("cmnd", "Backlog")
    mqttPublish(commandTopic, "SetOption0 0;PowerOnState 0;TelePeriod ${settings.telePeriod}")
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver v${version()} installed"

    def item = [
        name = "door"
        value = "unknown"
    ]
    item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
    sendEvent(item)
}

// Called to parse received MQTT data
void parse(data) {
    Map message = interfaces.mqtt.parseMessage(data)
    mqttReceive(message)
}

// Called when the user requests a refresh (from Refresh capability)
// Requests latest STATE and STATUS 5 (Network)
void refresh() {
    log.info "Refreshing state of ${device.name}"
    state.clear()
    state.sensorHistory = []

    String commandTopic = getTopic("cmnd", "Backlog")
    mqttPublish(commandTopic, "State;Status 5;Status 10")
}

// Called with MQTT client status messages
void mqttClientStatus(String message) {
	if (logEnable) log.debug "MQTT ${message}"

    if (message.startsWith("Error")) {
        mqttDisconnect()
        mqttCheckConnected()
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

    mqttDisconnect()
    unschedule()

    if (settings.mqttBroker) {
        mqttConnect()
        refresh()
    } else {
        log.warn "${device.displayName} requires a broker configured to connect"
    }

    if (logEnable) runIn(1800, "logsOff")
}

/**
 *  Capability: Door Control
 */

// Open door
void open() {
    String doorState = device.currentValue("door")
    if (doorState != "closed") {
		log.info "${device.displayName} ignoring open request as door is ${doorState}"
        return
	}

    def item = [
        name = "door"
        value = "opening"
    ]
    item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
    sendEvent(item)

    String commandTopic = getTopic("cmnd", "Backlog")
    mqttPublish(commandTopic, "PulseTime ${settings.pulseTime};Power 1")

    runIn(settings.travelTime, "setOpen")
}

// Close door
void close() {
    String doorState = device.currentValue("door")
    if (doorState != "open") {
		log.info "${device.displayName} ignoring close request as door is ${doorState}"
        return
	}

    def item = [
        name = "door"
        value = "closing"
    ]
    item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
    sendEvent(item)

    if (settings.warnOnClose) {
        soundBuzzer(settings.warnOnClose)
        pauseExecution(settings.warnOnClose * 1000)
    }

    String commandTopic = getTopic("cmnd", "Backlog")
    mqttPublish(commandTopic, "PulseTime ${settings.pulseTime};Power 1")

    runIn(settings.travelTime, "setClosed")
}

// Sound the buzzer
void soundBuzzer(count = 1, beep = 1, silence = 1) {
    String commandTopic = getTopic("cmnd", "Buzzer")
    mqttPublish(commandTopic, "${count},${beep},${silence}")
}

private void setClosed() {
    String doorState = device.currentValue("door")
    if (doorState == "closed")
        return

    if (doorState != "closing") {
		log.warn "${device.displayName} should be closing but is ${doorState}"
        return
	}

    def item = [
        name = "door"
        value = "closed"
    ]
    item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
    sendEvent(item)
}

private void setOpen() {
    String doorState = device.currentValue("door")
    if (doorState == "open")
        return

    if (doorState != "opening") {
		log.warn "${device.displayName} should be opening but is ${doorState}"
        return
	}

    def item = [
        name = "door"
        value = "open"
    ]
    item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
    sendEvent(item)
}

/**
 *  Tasmota Device Specific
 */

// Parses Tasmota JSON content and send driver events
void parseTasmota(String topic, Map json) {
    if (json.containsKey("StatusSNS")) {
        json = json.StatusSNS
    }

    if (json.containsKey("SR04")) {
        if (logEnable) log.debug "Parsing [ SR04: ${json.SR04} ]"
        int historySize = 4
        int value = limit(Math.round(json.SR04.Distance), 0, 400)

        if (value) {
            // Track distance sensorHistory values
            state.sensorHistory = (state?.sensorHistory ?: []).takeRight(historySize).plus(value)
            state.medianDistance = median(state.sensorHistory)
            int distance = state.medianDistance

            // Publish distance value (optionally converted to inches)
            def item = [
                name: "distance",
                value: distance,
                unit: settings.useMetric ? "cm" : "in"
            ]
            item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
            sendEvent(item)

            // Publish door status based on distance to door threshold
            item = [
                name: "door",
                value: distance < settings.doorThreshold ? "open" : "closed"
            ]
            if (logEnable) log.debug "${item.name} is ${item.value} (distance = ${distance}, doorThreshold = ${settings.doorThreshold}"
            item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
            sendEvent(item)

            def presence = distance >= settings.doorThreshold && distance < settings.vehicleThreshold
            item = [
                name: "presence",
                value: presence ? "present" : "not present"
            ]
            if (logEnable) log.debug "Vehicle is considered ${item.value} (${distance} between ${settings.vehicleThreshold} and ${settings.vehicleThreshold})"
            item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
            sendEvent(item)
        }
    }

    if (json.containsKey("Wifi")) {
        if (logEnable) log.debug "Parsing [ Wifi: ${json.Wifi} ]"
        updateDataValue("BSSId", json.Wifi.BSSId)
        updateDataValue("Channel", json.Wifi.Channel.toString())
        updateDataValue("LinkCount", json.Wifi.LinkCount.toString())
        updateDataValue("RSSI", json.Wifi.RSSI.toString())
        updateDataValue("Signal", json.Wifi.Signal.toString())
        updateDataValue("SSId", json.Wifi.SSId)

        def item = [
            name: "wifiSignal",
            value: getWifiSignalName(json.Wifi.RSSI)
        ]
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)
    }

    if (json.containsKey("StatusNET")) {
        if (logEnable) log.debug "Parsing [ StatusNET: ${json.StatusNET} ]"
        updateDataValue("Hostname", json.StatusNET.Hostname)
        updateDataValue("IPAddress", json.StatusNET.IPAddress)
        device.deviceNetworkId = json.StatusNET.Mac.toLowerCase()
    }

    state.lastResult = json
}

def median(data) {
    def copy = data.toSorted()
    def middle = data.size().intdiv(2)
    return data.size() % 2 ? copy[middle] : (copy[middle - 1] + copy[middle]) / 2
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

private String getTopic(String prefix, String postfix = "")
{
    settings.fullTopic
        .replaceFirst("%prefix%", prefix)
        .replaceFirst("%topic%", settings.deviceTopic)
        .plus(postfix)
}

private String getWifiSignalName(int rssi) {
    String signalName
    switch(rssi) {
        case 75..100: signalName = "High"
            break

        case 45..74: signalName = "Medium"
            break

        case 1..44: signalName = "Low"
            break

        case 0: signalName = "None"
            break;
    }

    return signalName
}

private int conversion(cm) {
    if (settings.useMetric)
        return cm
    else
        return Math.round(cm * 0.393700787)
}

private def limit(value, lowerBound = 0, upperBound = 100) {
    value == null ? value = upperBound : null

    if (lowerBound < upperBound){
        if (value < lowerBound) value = lowerBound
        if (value > upperBound) value = upperBound
    }
    else if (upperBound < lowerBound) {
        if (value < upperBound) value = upperBound
        if (value > lowerBound) value = lowerBound
    }

    return value
}

private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
}

private void mqttCheckReceiveTime() {
    int timeout = 5 * 60
    if (state.mqttReceiveTime) {
        int elapsedSeconds = (now() - state.mqttReceiveTime).intdiv(1000)

        if (elapsedSeconds > timeout) {
            log.warn "No messages received from ${device.displayName} in ${elapsedSeconds} seconds"
            sendEvent (name: "connection", value: "offline", descriptionText: "${device.displayName} silent for ${elapsedSeconds} seconds")
        } else
        {
            sendEvent (name: "connection", value: "online", descriptionText: "${device.displayName} last message was ${elapsedSeconds} seconds ago")
        }
    }
}

private boolean mqttCheckConnected() {
    if (interfaces.mqtt.isConnected() == false) {
        log.warn "MQTT is not connected"
        sendEvent (name: "connection", value: "offline", descriptionText: "${device.displayName} connection now offline")
        if (!mqttConnect()) {
            int waitSeconds = getRetrySeconds()
            log.info "Retrying MQTT connection in ${waitSeconds} seconds"
            runIn(waitSeconds, "mqttCheckConnected")
            return false
        }
    }

    state.remove("mqttRetryCount")
    return true
}

private boolean mqttConnect() {
    try {
        def hub = device.getHub()
        def mqtt = interfaces.mqtt
        def clientId = device.getDeviceNetworkId()
        log.info "Connecting to MQTT broker at ${settings.mqttBroker}"
        state.mqttConnectCount = (state?.mqttConnectCount ?: 0) + 1

        mqtt.connect(
            settings.mqttBroker,
            clientId,
            settings?.username,
            settings?.password
        )

        pauseExecution(1000)
        connected()
        refresh()
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

private void mqttPublish(String topic, String message = "") {
    int qos = settings.mqttQOS.toInteger()
    if (logEnable) log.debug "MQTT Publish: ${topic} = ${message} (qos: ${qos})"

    if (mqttCheckConnected()) {
        interfaces.mqtt.publish(topic, message, qos, false)
        state.mqttTransmitCount = (state?.mqttTransmitCount ?: 0) + 1
    } else {
        log.warn "Unable to publish topic (MQTT not connected)"
    }
}

private void mqttReceive(Map message) {
    if (logEnable) log.debug "MQTT Receive: ${message}"
    String topic = message.get("topic")
    String payload = message.get("payload")
    state.mqttReceiveCount = (state?.mqttReceiveCount ?: 0) + 1

    String availabilityTopic = getTopic("tele", "LWT")
    if (topic == availabilityTopic) {
        def event = [
            name: "connection",
            value: payload.toLowerCase()
        ]
        event.descriptionText = "${device.displayName} ${event.name} now ${event.value}"
        sendEvent (event)
        log.info event.descriptionText
    } else if (payload[0] == "{") {
        state.mqttReceiveTime = now()
        parseTasmota(topic, parseJson(payload))
    } else {
        if (logEnable) log.debug "Unknown Tasmota message: ${topic} = ${payload}"
    }
}

private void mqttSubscribeTopics() {
    int qos = settings.mqttQOS.toInteger()
    String teleTopic = getTopic("tele", "+")
    if (logEnable) log.debug "Subscribing to Tasmota telemetry topic: ${teleTopic}"
    interfaces.mqtt.subscribe(teleTopic, qos)

    String statTopic = getTopic("stat", "+")
    if (logEnable) log.debug "Subscribing to Tasmota stat topic: ${statTopic}"
    interfaces.mqtt.subscribe(statTopic, qos)
}
