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
static final String version() { "0.2" }
static final String deviceType() { "Power Monitor" }

metadata {
    definition (name: "Tasmota MQTT ${deviceType()}", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Actuator"
        capability "Configuration"
        capability "EnergyMeter"
        capability "Initialize"
        capability "Outlet"
        capability "PowerMeter"
        capability "Refresh"
        capability "Sensor"
        capability "VoltageMeasurement"

        attribute "deviceState", "String"
        attribute "totalStartTime", "string"	//Time at which energy totals are effective from.
        attribute "todayPower", "number"		//Total power used today
        attribute "yesterdayPower", "number"	//Total power used yesterday
        attribute "apparentPower", "number"			
        attribute "reactivePower", "number"			
        attribute "powerFactor", "number"			
        attribute "current", "number"			//Present Amps

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
            input name: "relayNumber", type: "number", title: "Relay Number", description: "For Power commands", required: true, defaultValue: 1
            input name: "reportInterval", type: "number", title: "Reporting Interval", description: "Period between power reports", required: true, defaultValue: 300
            input name: "powerDelta", type: "number", title: "Power Delta Reporting", description: "Maximum delta between reports", required: true, defaultValue: 0
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called after MQTT successfully connects
void connected() {
    log.info "Connected to MQTT broker at ${settings.mqttBroker}"
    mqttSubscribeTopics()
}

void configure()
{
    // set timezone offset
    int offsetInMillis = location.timeZone.getOffset(now())
    String offset = String.format("%s%02d:%02d", 
        offsetInMillis >= 0 ? "+" : "-", 
        Math.abs(offsetInMillis).intdiv(3600000),
        (Math.abs(offsetInMillis) / 60000).remainder(60) as int
    )
    mqttPublish(getTopic("Timezone"), offset)
    // set latitude and longitude
    mqttPublish(getTopic("Latitude"), location.latitude.toString())
    mqttPublish(getTopic("Longitude"), location.longitude.toString())
    // reporting interval
    setTelePeriod(settings.reportInterval)
    setPowerDelta(settings.powerDelta)
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
void mqttClientStatus(String message) {

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
void refresh() {
    log.info "Refreshing state of ${device.name}"
    state.clear()

    mqttPublish(getTopic("Backlog"), "State;Status;Status 1;Status 2;Status 5;Status 10")
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
 *  Capability: Switch or Bulb
 */

// Turn on
void on() {
    mqttPublish(getTopic("Power${settings.relayNumber}"), "1")
}

// Turn off
void off() {
    mqttPublish(getTopic("Power${settings.relayNumber}"), "0")
}

/**
 *  Tasmota Custom Commands
 */

void restart() {
    mqttPublish(getTopic("Restart"), "1")
}

void setTelePeriod(interval) {
    mqttPublish(getTopic("TelePeriod"), interval.toString())
}

void setPowerDelta(value) {
    mqttPublish(getTopic("PowerDelta"), value.toString())
}

/**
 *  Tasmota MQTT Message Parsing
 */

private void parseTasmota(String topic, Map json) {
    List<Map> events = []

    def power = json.find { 
        it.key.equalsIgnoreCase("Power") || 
        it.key.equalsIgnoreCase("Power".plus(relayNumber))
    }

    if (power) {
        if (logEnable) log.debug "Parsing [ ${power.key}: ${power.value} ]"
        events << newEvent("switch", power.value.toLowerCase())
    }

    if (json.containsKey("StatusSNS")) {
        json = json.StatusSNS
    }

    if (json.containsKey("ENERGY")) {
        if (logEnable) log.debug "Parsing [ ENERGY: ${json.ENERGY} ]"
        events << newEvent("apparentPower", json.ENERGY.ApparentPower, "VA")
        events << newEvent("current", json.ENERGY.Current, "A")
        events << newEvent("energy", json.ENERGY.Total, "kWh")
        events << newEvent("power", json.ENERGY.Power, "W")
        events << newEvent("powerFactor", json.ENERGY.Factor)
        events << newEvent("reactivePower", json.ENERGY.ReactivePower, "VAr")
        events << newEvent("todayPower", json.ENERGY.Today, "W")
        events << newEvent("totalStartTime", json.ENERGY.TotalStartTime)
        events << newEvent("voltage", json.ENERGY.Voltage, "v")
        events << newEvent("yesterdayPower", json.ENERGY.Yesterday, "W")
    }

    if (json.containsKey("Status")) {
        if (logEnable) log.debug "Parsing [ Status: ${json.Status} ]"
        int relayNumber = settings?.relayNumber ?: 1
        String friendlyName = json.Status.FriendlyName instanceof String 
            ? json.Status.FriendlyName 
            : json.Status.FriendlyName[relayNumber-1]
        if (!device.label) device.setLabel(friendlyName)
    }

    if (json.containsKey("Uptime")) {
        if (logEnable) log.debug "Parsing [ Uptime: ${json.Uptime} ]"
        updateDataValue("uptime", json.Uptime)
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

    if (json.containsKey("StatusPRM")) { // Status 1
        if (logEnable) log.debug "Parsing [ StatusPRM: ${json.StatusPRM} ]"
        updateDataValue("restartReason", json.StatusPRM.RestartReason)
        state.groupTopic = json.StatusPRM.GroupTopic
    }

    if (json.containsKey("StatusFWR")) { // Status 2
        if (logEnable) log.debug "Parsing [ StatusFWR: ${json.StatusFWR} ]"
        updateDataValue("firmwareVersion", json.StatusFWR.Version)
    }

    if (json.containsKey("StatusNET")) { // Status 5
        if (logEnable) log.debug "Parsing [ StatusNET: ${json.StatusNET} ]"
        updateDataValue("hostname", json.StatusNET.Hostname)
        updateDataValue("ipAddress", json.StatusNET.IPAddress)
    }

    state.lastResult = json

    events.each { sendEvent(it) }
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
        runInMillis(new Random(now()).nextInt(30000), "initialize")
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