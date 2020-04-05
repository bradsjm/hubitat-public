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

static final String deviceType() { "OpenGarage" }

metadata {
    definition (name: "Tasmota MQTT ${deviceType()}", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Initialize"
        capability "Sensor"
        capability "GarageDoorControl"
        capability "Lock"

        command "soundWarning"
        command "restart"

        attribute "deviceState", "string"
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
        }

        section("Opener") {
            input name: "doorThreshold", type: "number", title: "Max Door Distance", description: "Measured to top of garage door (cm)", required: true, defaultValue: 50
            input name: "debounceTime", type: "number", title: "Sensor Debounce", description: "Time to wait for measurement to settle (seconds)", required: true, defaultValue: 2
            input name: "pulseTime", type: "number", title: "Relay Pulse", description: "In tenths of a second (ms)", required: true, defaultValue: 7
            input name: "travelTime", type: "number", title: "Door Travel", description: "Time to fully open/close (seconds)", required: true, defaultValue: 30
            input name: "warnOnClose", type: "bool", title: "Warning Beeper on Closing", description: "Beeps 3 times before closing", required: true, defaultValue: true
        }

        section("Misc") {
            input name: "useMetric", type: "bool", title: "Display Metric", description: "Displays distance using centimeters", required: true, defaultValue: true
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}


/**
 *  Hubitat Driver Event Handlers
 */

// Called after MQTT successfully connects
void connected() {
    mqttSubscribeTopics()
}

void configure() {
    // Set options
    def commandTopic = getTopic("Backlog")
    mqttPublish(commandTopic, "SetOption0 0;PowerOnState 0")

    // Create rule to publish distance on change of +/- 12cm/1in
    def rule = """
        on SR04#Distance>%var1% do backlog publish stat/%topic%/Distance %value%;var1 %value%;var2 %value%;add1 12;sub2 12 break on SR04#Distance<%var2% do backlog publish stat/%topic%/Distance %value%;var1 %value%;var2 %value%;add1 12;sub2 12 endon
    """
    commandTopic = getTopic("Rule1")
    mqttPublish(commandTopic, rule) // send the rule content
    mqttPublish(commandTopic, "1") // enable the rule
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

// Called with MQTT client status messages
void mqttClientStatus(String message) 
{
    if (message.startsWith("Error")) {
    	log.error "MQTT: ${message}"
        runInMillis(new Random(now()).nextInt(90000), "initialize")
    } else {
    	if (logEnable) log.debug "MQTT: ${message}"
    }
}

// Called to parse received MQTT data
void parse(data) {
    mqttReceive(interfaces.mqtt.parseMessage(data))
}

// Called when the user requests a refresh (from Refresh capability)
// Requests latest STATE and full STATUS
void refresh() {
    log.info "Refreshing state of ${device.name}"
    state.clear()

    String commandTopic = getTopic("Backlog")
    mqttPublish(commandTopic, "State;Status 0")
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
    initialize()

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

    if (isLocked()) {
		log.info "${device.displayName} ignoring open request as door is soft-locked"
        return
    }

    sendEvent(newEvent("door", "opening"))

    String commandTopic = getTopic("Backlog")
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

    sendEvent(newEvent("door", "closing"))

    if (settings.warnOnClose) {
        soundWarning()
        pauseExecution(3000)
    }

    String commandTopic = getTopic("Backlog")
    mqttPublish(commandTopic, "PulseTime ${settings.pulseTime};Power 1")

    runIn(settings.travelTime, "setClosed")
}

void soundWarning(count = 3, freq = 750) {
    String commandTopic = getTopic("Backlog")
    String beep = "Pwm2 512;Delay 2;Pwm2 0;Delay 3;"
    mqttPublish(commandTopic, "PwmFrequency ${freq};" + beep * count)
}

private void setClosed() {
    String doorState = device.currentValue("door")
    if (doorState == "closed") {
		log.info "${device.displayName} is already ${doorState} within ${settings.travelTime} seconds"
        return
    }

    if (doorState != "closing") {
		log.warn "${device.displayName} door state should be 'closing' but is ${doorState}"
        return
	}

    log.info "${device.displayName} setting door state to 'closed' (${settings.travelTime} second timer)"
    sendEvent(newEvent("door", "closed"))
}

private void setOpen() {
    String doorState = device.currentValue("door")
    if (doorState == "open") {
		log.info "${device.displayName} is already ${doorState} within ${settings.travelTime} seconds"
        return
    }

    if (doorState != "opening") {
		log.warn "${device.displayName} door state should be 'opening' but is ${doorState}"
        return
	}

    log.info "${device.displayName} setting door state to 'open' (${settings.travelTime} second timer)"
    sendEvent(newEvent("door", "open"))
}

/**
 *  Capability: Lock
 */

// soft lock door
void lock() {
    sendEvent(newEvent("lock", "locked"))
}

void unlock() {
    sendEvent(newEvent("lock", "unlocked"))
}

boolean isLocked() {
    return device.currentValue("lock") == "locked"
}

/**
 *  Tasmota Device Specific
 */

void restart() {
    mqttPublish(getTopic("Restart"), "1")
}

// Parses Tasmota JSON content and send driver events
void parseTasmota(String topic, Map json) {
    if (json.containsKey("StatusSNS")) {
        json = json.StatusSNS
    }

    // Realtime updates
    if (json.containsKey("Distance")) {
        if (logEnable) log.debug "Parsing [ Distance: ${json.Distance} ]"
        int distance = limit(Math.round(json.Distance as float), 0, 400)
        state.minDistance = Math.min(distance, state.minDistance ?: 400)
        state.maxDistance = Math.max(distance, state.maxDistance ?: 0)
        runIn(settings.debounceTime, "updateDistance", [ data: distance ])
    }

    // Telemetry updates
    if (json.containsKey("SR04")) {
        if (logEnable) log.debug "Parsing [ SR04: ${json.SR04} ]"
        int distance = limit(Math.round(json.SR04.Distance as float), 0, 400)
        state.minDistance = Math.min(distance, state.minDistance ?: 400)
        state.maxDistance = Math.max(distance, state.maxDistance ?: 0)
        runIn(settings.debounceTime, "updateDistance", [ data: distance ])
    }

    if (json.containsKey("Status")) {
        if (logEnable) log.debug "Parsing [ Status: ${json.Status} ]"
        int relayNumber = settings?.relayNumber ?: 1
        String friendlyName = json.Status.FriendlyName instanceof String 
            ? json.Status.FriendlyName 
            : json.Status.FriendlyName[relayNumber-1]
        if (!device.label) device.setLabel(friendlyName)
    }

    if (json.containsKey("Wifi")) {
        if (logEnable) log.debug "Parsing [ Wifi: ${json.Wifi} ]"
        updateDataValue("bssId", json.Wifi.BSSId)
        updateDataValue("channel", json.Wifi.Channel.toString())
        updateDataValue("linkCount", json.Wifi.LinkCount.toString())
        updateDataValue("rssi", json.Wifi.RSSI.toString())
        updateDataValue("signal", json.Wifi.Signal.toString())
        updateDataValue("ssId", json.Wifi.SSId)
    }

    if (json.containsKey("StatusNET")) {
        if (logEnable) log.debug "Parsing [ StatusNET: ${json.StatusNET} ]"
        updateDataValue("hostname", json.StatusNET.Hostname)
        updateDataValue("ipAddress", json.StatusNET.IPAddress)
    }

    if (json.containsKey("Uptime")) {
        if (logEnable) log.debug "Parsing [ Uptime: ${json.Uptime} ]"
        updateDataValue("uptime", json.Uptime)
    }

    if (json.containsKey("StatusPRM")) {
        if (logEnable) log.debug "Parsing [ StatusPRM: ${json.StatusPRM} ]"
        updateDataValue("restartReason", json.StatusPRM.RestartReason)
        state.groupTopic = json.StatusPRM.GroupTopic
    }

    if (json.containsKey("StatusFWR")) {
        if (logEnable) log.debug "Parsing [ StatusFWR: ${json.StatusFWR} ]"
        updateDataValue("firmwareVersion", json.StatusFWR.Version)
    }

    state.lastResult = json
}

private void updateDistance(int distance) {
    if (!distance) return

    if (logEnable) log.debug "updating distance value (${distance})"

    // Publish distance value (optionally converted to inches)
    sendEvent(newEvent("distance", conversion(distance), settings.useMetric ? "cm" : "in"))

    // Check if distance is less than the open door threshold
    def newState = distance <= settings.doorThreshold ? "open" : "closed"

    // Publish door status based on distance to door threshold
    def oldState = device.currentValue("door")
    switch (oldState) {
        case "open":
        case "opening":
        case "closed":
        case "unknown":
            if (logEnable && newState != oldState) log.debug "setting door state to ${newState} from ${oldState} (distance ${distance}, threshold ${settings.doorThreshold})"
            sendEvent(newEvent("door", newState))
            break
    }
}

private Map newEvent(String name, value, unit = null) {
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: "${device.displayName} ${name} is ${value}${unit ?: ''}"
    ]
}

private def median(data) {
    def copy = data.toSorted()
    def middle = data.size().intdiv(2)
    return data.size() % 2 ? copy[middle] : (copy[middle - 1] + copy[middle]) / 2
}

/**
 *  Common Tasmota MQTT communication methods
 */

private String getTopic(String postfix)
{
    getTopic("cmnd", postfix)
}

private String getTopic(String prefix, String postfix)
{
    String deviceTopic = settings.deviceTopic
    String fullTopic = settings.fullTopic
    if (!fullTopic.endsWith("/")) fullTopic += "/"

    fullTopic
        .replaceFirst("%prefix%", prefix)
        .replaceFirst("%topic%", deviceTopic)
        .plus(postfix)
}

private String getWifiSignalName(int rssi) {
    String signalName
    switch(rssi) {
        case 75..100: signalName = "high"
            break

        case 45..74: signalName = "medium"
            break

        case 1..44: signalName = "low"
            break

        case 0: signalName = "none"
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

private boolean mqttConnect() {
    unschedule("mqttConnect")
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

    sendEvent(name: "deviceState", value: "offline", descriptionText: "${device.displayName} broker connection closed by driver")
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
    }
}

private void mqttReceive(Map message) {
    String topic = message.get("topic")
    String payload = message.get("payload")
    if (logEnable) log.debug "MQTT Receive < ${topic} = ${payload}"
    state.mqttReceiveCount = (state?.mqttReceiveCount ?: 0) + 1
    state.mqttReceiveTime = now()

    String availabilityTopic = getTopic("tele", "LWT")
    if (topic == availabilityTopic) {
        def event = [
            name: "deviceState",
            value: payload.toLowerCase()
        ]
        event.descriptionText = "${device.displayName} ${event.name} LWT now ${event.value}"
        sendEvent(event)
        log.info event.descriptionText
        if (payload.equalsIgnoreCase("Online")) {
            configure()
            refresh()
        }
    } else if (payload[0] == "{") {
        parseTasmota(topic, parseJson(payload))
    } else {
        String key = topic.split('/')[-1]
        parseTasmota(topic, [ (key): payload ])
    }
}

private void mqttSubscribeTopics() {
    int qos = 1 // at least once delivery
    String teleTopic = getTopic("tele", "+")
    if (logEnable) log.debug "Subscribing to Tasmota telemetry topic: ${teleTopic}"
    interfaces.mqtt.subscribe(teleTopic, qos)

    String statTopic = getTopic("stat", "+")
    if (logEnable) log.debug "Subscribing to Tasmota stat topic: ${statTopic}"
    interfaces.mqtt.subscribe(statTopic, qos)
}
