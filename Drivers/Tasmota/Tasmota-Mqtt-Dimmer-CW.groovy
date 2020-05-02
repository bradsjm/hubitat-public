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
static final String version() { "1.0" }
static final String deviceType() { "Dimmer-CW" }

import groovy.transform.Field
import groovy.json.JsonBuilder

metadata {
    definition (name: "Tasmota ${deviceType()} (MQTT)", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Actuator"
        capability "ChangeLevel"
        capability "ColorTemperature"
        capability "Configuration"
        capability "Initialize"
        capability "Light"
        capability "LightEffects"
        capability "Refresh"
        capability "Switch"
        capability "SwitchLevel"

        attribute "deviceState", "String"
        attribute "fadeMode", "String"
        attribute "fadeSpeed", "Number"

        command "restart"

        command "setFadeSpeed", [
            [
                name:"Fade Speed*",
                type: "NUMBER",
                description: "Seconds (0 to 20) where 0 is off"
            ]
        ]

        command "startWakeup", [
            [
                name:"Dimmer Level*",
                type: "NUMBER",
                description: "Target dimmer level (0-100)"
            ],
            [
                name:"Duration*",
                type: "NUMBER",
                description: "Duration in seconds (1-3000)"
            ]
        ]
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
            input name: "changeLevelStep", type: "decimal", title: "Change level step %", description: "1% to 10%", required: true, defaultValue: 2
            input name: "changeLevelEvery", type: "number", title: "Change level interval", description: "100ms to 1000ms", required: true, defaultValue: 100
            input name: "preStaging", type: "bool", title: "Enable pre-staging", description: "Color and level changes while off", required: true, defaultValue: false
            input name: "enforceState", type: "bool", title: "Enforce State", description: "Force device power state from Hubitat", required: true, defaultValue: false
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}

@Field static Map lightEffects = [
    0: "None",
    1: "Blink",
    2: "Wakeup"
]

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

    // Set option 20 (Update of Dimmer/Color/CT without turning power on)
    mqttPublish(getTopic("SetOption20"), preStaging ? "1" : "0")
    sendEvent(name: "lightEffects", value: new JsonBuilder(lightEffects))
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
    mqttReceive(interfaces.mqtt.parseMessage(data))
}

// Called when the user requests a refresh (from Refresh capability)
void refresh() {
    log.info "Refreshing state of ${device.name}"

    mqttPublish(getTopic("Backlog"), "State;Status;Status 1;Status 2;Status 5")
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
 *  Capability: Switch or Bulb
 */

// Turn on
void on() {
    log.info "Switching ${device.displayName} on"
    if (settings.enforceState) state.desiredPowerState = "on"
    mqttPublish(getTopic("Power${settings.relayNumber}"), "1")
}

// Turn off
void off() {
    log.info "Switching ${device.displayName} off"
    if (settings.enforceState) state.desiredPowerState = "off"
    mqttPublish(getTopic("Power${settings.relayNumber}"), "0")
}

/**
 *  Capability: ChangeLevel
 */

// Start level change (up or down)
void startLevelChange(direction) {
    if (settings.changeLevelStep && settings.changeLevelEvery) {
        int delta = (direction == "down") ? -settings.changeLevelStep : settings.changeLevelStep
        doLevelChange(limit(delta, -10, 10))
        log.info "${device.displayName} Starting level change ${direction}"
    }
}

// Stop level change (up or down)
void stopLevelChange() {
    unschedule("doLevelChange")
    log.info "${device.displayName} Stopping level change"
}

private void doLevelChange(delta) {
    int newLevel = limit(device.currentValue("level").toInteger() + delta)
    setLevel(newLevel)
    if (newLevel > 0 && newLevel < 99) {
        int delay = limit(settings.changeLevelEvery, 100, 1000)
        runInMillis(delay, "doLevelChange", [ data: delta ])
    }
}

/**
 *  Capability: SwitchLevel
 */

// Set the brightness level and optional duration
void setLevel(level, duration = 0) {
    level = limit(level).toInteger()
    int oldSpeed = device.currentValue("fadeSpeed").toInteger() * 2
    int oldFade = device.currentValue("fadeMode") == "on" ? 1 : 0
    int speed = Math.min(40f, duration * 2).toInteger()

    if (settings.enforceState && !settings.preStaging) state.desiredPowerState = "on"
    if (speed > 0) {
        mqttPublish(getTopic("Backlog"), "Speed ${speed};Fade 1;Dimmer${settings.relayNumber} ${level};Delay ${duration * 10};Speed ${oldSpeed};Fade ${oldFade}")
    } else {
        mqttPublish(getTopic("Dimmer${settings.relayNumber}"), level.toString())
    }
    log.info "Setting ${device.displayName} brightness to ${level}"
}

/**
 *  Capability: Color Temperature
 */
void setColorTemperature(kelvin) {
    kelvin = limit(kelvin, 2000, 6536)
    int mired = limit(Math.round(1000000f / kelvin), 153, 500).toInteger()
    if (logEnable) log.debug "Converted ${kelvin} kelvin to ${mired} mired"
    mqttPublish(getTopic("CT"), mired.toString())
    log.info "Setting ${device.displayName} temperature to ${kelvin}K"
}

/**
 *  Capability: Light Effects
 */
void setEffect(String effect) {
    def id = lightEffects.find { it.value == effect }
    if (id != null) setEffect(id.key)
}

void setEffect(id) {
    if (logEnable) log.debug "Setting effect $id"
    switch (id) {
        case 0:
            blinkOff()
            setEffectsScheme(0)
            break
        case 1: 
            blinkOn()
            break
        case 2: 
            setEffectsScheme(1)
            break
    }
}

void setNextEffect() {
    def currentEffect = state.crntEffectId ?: 0
    currentEffect++
    if (currentEffect > lightEffects.size() - 1) currentEffect = 0
    setEffect(currentEffect)
}

void setPreviousEffect() {
    def currentEffect = state.crntEffectId ?: 0
    currentEffect--
    if (currentEffect < 0) currentEffect = lightEffects.size() - 1
    setEffect(currentEffect)
}

void blinkOn() {
    if (settings.enforceState && !settings.preStaging) state.desiredPowerState = "blink"
    mqttPublish(getTopic("Power${settings.relayNumber}"), "blink")
    log.info "Start ${device.displayName} blinking"
}

void blinkOff() {
    if (settings.enforceState && !settings.preStaging) state.desiredPowerState = "off"
    mqttPublish(getTopic("Power${settings.relayNumber}"), "blinkoff")
    log.info "Stop ${device.displayName} blinking"
}

void setEffectsScheme(scheme) {
    if (settings.enforceState && !settings.preStaging) state.desiredPowerState = "on"
    mqttPublish(getTopic("Scheme"), scheme.toString())
    log.info "Setting ${device.displayName} to effects scheme ${scheme}"
}

/**
 *  Tasmota Custom Commands
 */

// Set the Tasmota fade speed
void setFadeSpeed(seconds) {
    int speed = Math.min(40f, seconds * 2).toInteger()
    if (speed > 0) {
        mqttPublish(getTopic("Backlog"), "Speed ${speed};Fade 1")
    } else {
        mqttPublish(getTopic("Fade"), "0")
    }
    log.info "Setting ${device.displayName} fade speed to ${speed}"
}

// Perform Tasmota wakeup function
void startWakeup(level, duration) {
    level = limit(level).toInteger()
    duration = limit(duration, 1, 3000).toInteger()
    mqttPublish(getTopic("Backlog"), "WakeupDuration ${duration};Wakeup ${level}")
    log.info "Starting ${device.displayName} wake up to ${level} (duration ${duration})"
}

void restart() {
    mqttPublish(getTopic("Restart"), "1")
    log.info "Restarting ${device.displayName}"
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
        def value = power.value.toLowerCase()
        if (logEnable) log.debug "Parsing [ ${power.key}: ${value} ]"
        events << newEvent("switch", value)

        if (settings.enforceState && state.desiredPowerState && value != state.desiredPowerState) {
            log.warn "Enforce State is enabled: Setting to ${state.desiredPowerState} (from ${value})"
            if (state.desiredPowerState == "on") on() else off()
        }
    }

    if (json.containsKey("Fade")) {
        if (logEnable) log.debug "Parsing [ Fade: ${json.Fade} ]"
        if (json.Fade.equalsIgnoreCase("OFF")) json.Speed = 0
        events << newEvent("fadeMode", json.Fade.toLowerCase())
    }

    if (json.containsKey("Speed")) {
        if (logEnable) log.debug "Parsing [ Speed: ${json.Speed} ]"
        def value = sprintf("%.1f", json.Speed.toInteger().div(2))
        events << newEvent("fadeSpeed", value, "s")
    }

    if (json.containsKey("CT")) {
        if (logEnable) log.debug "Parsing [ CT: ${json.CT} ]"
        int kelvin = Math.round(1000000f / json.CT).toInteger()
        if (logEnable) log.debug "Converted ${json.CT} CT to ${kelvin} kelvin"
        events << newEvent("colorTemperature", kelvin, "K")
        events << newEvent("colorName", getTemperatureName(kelvin))
    }

    if (json.containsKey("Dimmer")) {
        if (logEnable) log.debug "Parsing [ Dimmer: ${json.Dimmer} ]"
        events << newEvent("level", limit(json.Dimmer), "%")
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

    if (events) {
        events.each { sendEvent(it) }
        parent?.stateChanged(device)
    }

}

private String getTemperatureName(int kelvin) {
    if (!kelvin) return ""
    String temperatureName
    switch (limit(kelvin, 1000, 6500)) {
        case 1000..1999: temperatureName = "Candlelight"
            break
        case 2000..2399: temperatureName = "Sunrise"
            break
        case 2400..2999: temperatureName = "Soft White"
            break
        case 3000..3199: temperatureName = "Warm White"
            break
        case 3200..3999: temperatureName = "Studio White"
            break
        case 4000..4300: temperatureName = "Cool White"
            break
        case 5000..5765: temperatureName = "Full Spectrum"
            break
        case 5766..6500: temperatureName = "Daylight"
            break
    }

    return temperatureName
}

private String getHueName(int hue) {
    if (!hue) return ""
    String colorName
    switch (limit(hue, 1, 360)){
        case 1..15: colorName = "Red"
            break
        case 16..45: colorName = "Orange"
            break
        case 46..75: colorName = "Yellow"
            break
        case 76..105: colorName = "Chartreuse"
            break
        case 106..135: colorName = "Green"
            break
        case 136..165: colorName = "Spring"
            break
        case 166..195: colorName = "Cyan"
            break
        case 196..225: colorName = "Azure"
            break
        case 226..255: colorName = "Blue"
            break
        case 256..285: colorName = "Violet"
            break
        case 286..315: colorName = "Magenta"
            break
        case 316..345: colorName = "Rose"
            break
        case 346..360: colorName = "Red"
            break
    }

    return colorName
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
        event.descriptionText = "${device.displayName} ${event.name} is ${event.value}"
        sendEvent(event)
        log.info event.descriptionText
        if (payload.equalsIgnoreCase("Online") && device.currentValue("deviceState") != "online") {
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
