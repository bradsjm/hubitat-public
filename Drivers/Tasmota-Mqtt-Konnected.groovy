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
static final String deviceType() { "Konnected" }

metadata {
    definition (name: "Tasmota MQTT ${deviceType()}", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Configuration"
        capability "Refresh"

        attribute "connection", "String"
        attribute "wifiSignal", "String"

        command "restart"
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

        section("Misc") {
            input name: "zoneCount", type: "number", title: "Zone count", description: "Number of active zones", required: true, defaultValue: 6
            input name: "logEnable", type: "bool", title: "Enable Debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
            input name: "watchdogEnable", type: "bool", title: "Enable Watchdog logging", description: "Checks for mqtt activity every 5 minutes", required: true, defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called after MQTT successfully connects
void connected() {
    mqttSubscribeTopics()
    configure()

    if (settings.watchdogEnable) {
    	int randomSeconds = new Random(now()).nextInt(60)
        schedule("${randomSeconds} 0/5 * * * ?", "mqttCheckReceiveTime")
    }
}

// Called when the device connects to mqtt or when configure action is invoked.
void configure()
{
    // Set switches (zones) to follow mode
    1.upto(settings.zoneCount) {
        mqttPublish(getTopic("cmnd", "Switchmode${it}"), "1")
    }

    // Set rule to publish zone changes
    def rule = """
        on switch1#state do publish stat/%topic%/SWITCH1 %value% break on switch2#state do publish stat/%topic%/SWITCH2 %value% break on switch3#state do publish stat/%topic%/SWITCH3 %value% break on switch4#state do publish stat/%topic%/SWITCH4 %value% break on switch5#state do publish stat/%topic%/SWITCH5 %value% break on switch6#state do publish stat/%topic%/SWITCH6 %value% break
    """
    commandTopic = getTopic("cmnd", "Rule1")
    mqttPublish(commandTopic, rule) // send the rule content
    mqttPublish(commandTopic, "1") // enable the rule
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver v${version()} installed"
}

// Called to parse received MQTT data
void parse(data) {
    Map message = interfaces.mqtt.parseMessage(data)
    mqttReceive(message)
}

// Called when the user requests a refresh (from Refresh capability)
// Requests latest STATE and STATUS 0
void refresh() {
    log.info "Refreshing state of ${device.name}"
    state.clear()

    String commandTopic = getTopic("cmnd", "Backlog")
    mqttPublish(commandTopic, "State;Status 0")
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

    device.setName("mqtt-${settings.deviceTopic}")

    mqttDisconnect()
    unschedule()

    if (settings.mqttBroker) {
        mqttConnect()
    } else {
        log.warn "${device.displayName} requires a broker configured to connect"
    }

    if (logEnable) runIn(1800, "logsOff")
}

/**
 *  Child Device Management
 */
def getZoneDevice(zone) {
    def childDNI = "${device.deviceNetworkId}-z${zone}"
    def child = getChildDevice(childDNI)
    if (!child) {
        def label = "${device.label ?: device.name} Zone ${zone}"
        log.info "Creating ${label}"
        child = addChildDevice(
            "hubitat",
            "Virtual Contact Sensor",
            childDNI,
            [
                "name": "Konnected Contact Sensor",
                "label": label
            ]
        )
    }

    return child
}

void updateChildContact(zone, isOpen) {
    def child = getZoneDevice(zone)
    if (child) {
        if (isOpen && child.currentValue("contact") != "open") {
            if (logEnable) log.debug "Setting zone ${zone} to OPEN"
            child.open()
        } else if (child.currentValue("contact") != "closed") {
            if (logEnable) log.debug "Setting zone ${zone} to closed"
            child.close()
        }
    } else {
        log.warn "Unable to find child device for zone ${zone}"
    }
}

/**
 *  Tasmota Device Specific
 */

 void restart() {
    mqttPublish(getTopic("cmnd", "Restart"), "1")
 }

// Parses Tasmota JSON content and send driver events
void parseTasmota(String topic, Map json) {
    if (json.containsKey("statussns")) {
        if (logEnable) log.debug "Parsing [ StatusSNS: ${json.statussns} ]"
        json = json.statussns
    }

    if (json.containsKey("wifi")) {
        if (logEnable) log.debug "Parsing [ Wifi: ${json.wifi} ]"
        updateDataValue("BSSId", json.wifi.bssid)
        updateDataValue("Channel", json.wifi.channel.toString())
        updateDataValue("LinkCount", json.wifi.linkcount.toString())
        updateDataValue("RSSI", json.wifi.rssi.toString())
        updateDataValue("Signal", json.wifi.signal.toString())
        updateDataValue("SSId", json.wifi.ssid)
        sendEvent(newEvent("wifiSignal", getWifiSignalName(json.wifi.rssi)))
    }

    if (json.containsKey("statusnet")) {
        if (logEnable) log.debug "Parsing [ StatusNET: ${json.statusnet} ]"
        updateDataValue("Hostname", json.statusnet.hostname)
        updateDataValue("IPAddress", json.statusnet.ipaddress)
    }

    if (json.containsKey("uptime")) {
        if (logEnable) log.debug "Parsing [ Uptime: ${json.uptime} ]"
        state.uptime = json.uptime
    }

    if (json.containsKey("statusprm")) {
        if (logEnable) log.debug "Parsing [ StatusPRM: ${json.statusprm} ]"
        state.restartReason = json.statusprm.restartreason
    }

    if (json.containsKey("statusfwr")) {
        if (logEnable) log.debug "Parsing [ StatusFWR: ${json.statusfwr} ]"
        state.version = json.statusfwr.version
    }

    // Check each zone (this code should go last)
    1.upto(settings.zoneCount) {
        String key = "switch".plus(it)
        if (json.containsKey(key)) {
            def isOpen = json[key] == "1" || json[key] == "on"
            updateChildContact(it, isOpen)
        }
    }

    state.lastResult = json
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
    String topic = settings.deviceTopic
    settings.fullTopic
        .replaceFirst("%prefix%", prefix)
        .replaceFirst("%topic%", topic)
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

private void mqttCheckReceiveTime() {
    int timeout = 5
    if (state.mqttReceiveTime) {
        int elapsedMinutes = (now() - state.mqttReceiveTime).intdiv(60000)

        if (elapsedMinutes > timeout) {
            log.warn "No messages received from ${device.displayName} in ${elapsedMinutes} minutes"
            sendEvent (name: "connection", value: "offline", descriptionText: "${device.displayName} silent for ${elapsedMinutes} minutes")
            mqttCheckConnected()
        } else
        {
            sendEvent (name: "connection", value: "online", descriptionText: "${device.displayName} last message was ${elapsedMinutes} minutes ago")
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

private void mqttPublish(String topic, String payload = "") {
    int qos = 1 // at least once delivery
    if (logEnable) log.debug "MQTT PUBLISH ---> ${topic} = ${payload}"

    if (mqttCheckConnected()) {
        interfaces.mqtt.publish(topic, payload, qos, false)
        state.mqttTransmitCount = (state?.mqttTransmitCount ?: 0) + 1
    } else {
        log.warn "Unable to publish topic (MQTT not connected)"
    }
}

private void mqttReceive(Map message) {
    String topic = message.get("topic")
    String payload = message.get("payload")
    if (logEnable) log.debug "MQTT RECEIVE <--- ${topic} = ${payload}"
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
        parseTasmota(topic, parseJson(payload.toLowerCase()))
    } else {
        state.mqttReceiveTime = now()
        def key = topic.substring(topic.lastIndexOf("/")+1).toLowerCase()
        parseTasmota(topic, [ (key): payload.toLowerCase() ])
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
