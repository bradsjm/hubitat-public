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
static final String deviceType() { "RGBCW" }

import groovy.transform.Field
import groovy.json.JsonBuilder

metadata {
    definition (name: "Tasmota ${deviceType()} (MQTT)", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Actuator"
        capability "ChangeLevel"
        capability "ColorControl"
        capability "ColorMode"
        capability "ColorTemperature"
        capability "Configuration"
        capability "Initialize"
        capability "Light"
        capability "LightEffects"
        capability "Refresh"
        capability "Switch"
        capability "SwitchLevel"

        attribute "colorName", "String"
        attribute "deviceState", "String"
        attribute "fadeMode", "String"
        attribute "fadeSpeed", "Number"
        attribute "hueName", "String"

        command "restart"
        command "nextColor"
        command "previousColor"

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
    2: "Wakeup",
    3: "Cycle Up",
    4: "Cycle Down",
    5: "Random Cycle"
]

@Field List<Map> colorNames = getColorNames()

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
void mqttClientStatus(String message) {
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
 *  Capability: ColorControl
 */

// Set the HSB color [hue:(0-100), saturation:(0-100), brightness level:(0-100)]
void setColor(colormap) {
    if (colormap.hue == null || colormap.saturation == null) return
    int hue = limit(colormap.hue * 3.6, 0, 360).toInteger()
    int saturation = limit(colormap.saturation).toInteger()
    int level = limit(colormap.level).toInteger() // brightness

    if (settings.enforceState && !settings.preStaging) state.desiredPowerState = "on"
    mqttPublish(getTopic("HsbColor"), "${hue},${saturation},${level}")
    sendEvent(newEvent("colorName", colormap.name ?: ""))
    log.info "Setting ${device.displayName} color (HSB) to ${hue},${saturation},${level}"
}

// Set the hue (0-100)
void setHue(hue) {
    // Hubitat hue is 0-100 to be converted to Tasmota 0-360
    hue = limit(Math.round(hue * 3.6), 0, 360).toInteger()

    if (settings.enforceState && !settings.preStaging) state.desiredPowerState = "on"
    mqttPublish(getTopic("HsbColor1"), hue.toString())
    sendEvent(newEvent("colorName", ""))
    log.info "Setting ${device.displayName} hue to ${hue}"
}

// Set the saturation (0-100)
void setSaturation(saturation) {
    saturation = limit(saturation).toInteger()

    if (settings.enforceState && !settings.preStaging) state.desiredPowerState = "on"
    mqttPublish(getTopic("HsbColor2"), saturation.toString())
    sendEvent(newEvent("colorName", ""))
    log.info "Setting ${device.displayName} saturation to ${saturation}"
}

// Set the color temperature (2000-6536)
void setColorTemperature(kelvin) {
    kelvin = limit(kelvin, 2000, 6536)
    int channelCount = state.channelCount

    if (settings.enforceState && !settings.preStaging) state.desiredPowerState = "on"
    if (channelCount == 5) {
        int mired = limit(Math.round(1000000f / kelvin), 153, 500).toInteger()
        if (logEnable) log.debug "Converted ${kelvin} kelvin to ${mired} mired"
        mqttPublish(getTopic("CT"), mired.toString())
    } else if (channelCount == 4) {
        mqttPublish(getTopic("White"), device.currentValue("level").toString())
    }
    log.info "Setting ${device.displayName} temperature to ${kelvin}K"
}

void nextColor() {
    state.currentColorIndex = (state.currentColorIndex ?: 0) + 1
    if (state.currentColorIndex >= colorNames.size()) state.currentColorIndex = 0
    Map color = colorNames[state.currentColorIndex]
    setColor([
        name: color.name,
        hue: color.hue / 3.6,
        saturation: color.saturation,
        level: color.level
    ])
}

void previousColor() {
    state.currentColorIndex = (state.currentColorIndex ?: 0) - 1
    if (state.currentColorIndex < 0) state.currentColorIndex = colorNames.size() - 1
    Map color = colorNames[state.currentColorIndex]
    setColor([
        hue: color.hue / 3.6,
        saturation: color.saturation,
        level: color.level
    ])
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
        case 3: 
            setEffectsScheme(2)
            break
        case 4:
            setEffectsScheme(3)
            break
        case 5:
            setEffectsScheme(4)
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

    if (json.containsKey("Channel")) {
        if (logEnable) log.debug "Parsing [ Channel: ${json.Channel} ]"
        int channelCount = json.Channel.size()
        state.channelCount = channelCount
        def value = "RGB"
        if (channelCount == 4 && json.Channel[3] > 0) {
            value = "White"
        } else if (channelCount == 5 && (json.Channel[3] > 0 || json.Channel[4] > 0)) {
            value = "CT"
        }
        events << newEvent("colorMode", value)

        if (channelCount == 4 && json.Channel[3] > 0) {
            int fakeKelvin = 6500
            events << newEvent("colorTemperature", fakeKelvin, "K")
            events << newEvent("colorName", getTemperatureName(fakeKelvin))
        }
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

    if (json.containsKey("HSBColor")) {
        if (logEnable) log.debug "Parsing [ HSBColor: ${json.HSBColor} ]"
        def hsbColor = json.HSBColor.tokenize(",")
        int hue = hsbColor[0].toInteger()
        int saturation = hsbColor[1].toInteger()

        // Hubitat hue is 0-100 to be converted from Tasmota 0-360
        events << newEvent("hue", Math.round(hue / 3.6) as int)
        events << newEvent("hueName", getHueName(hue))
        events << newEvent("saturation", saturation)
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

private List<Map> getColorNames() {
    [
        [name:"Alice Blue", rgb:"#F0F8FF", hue:208, saturation:100, level:97],
        [name:"Antique White", rgb:"#FAEBD7", hue:34, saturation:78, level:91],
        [name:"Aqua", rgb:"#00FFFF", hue:180, saturation:100, level:50],
        [name:"Aquamarine", rgb:"#7FFFD4", hue:160, saturation:100, level:75],
        [name:"Azure", rgb:"#F0FFFF", hue:180, saturation:100, level:97],
        [name:"Beige", rgb:"#F5F5DC", hue:60, saturation:56, level:91],
        [name:"Bisque", rgb:"#FFE4C4", hue:33, saturation:100, level:88],
        [name:"Blanched Almond", rgb:"#FFEBCD", hue:36, saturation:100, level:90],
        [name:"Blue", rgb:"#0000FF", hue:240, saturation:100, level:50],
        [name:"Blue Violet", rgb:"#8A2BE2", hue:271, saturation:76, level:53],
        [name:"Brown", rgb:"#A52A2A", hue:0, saturation:59, level:41],
        [name:"Burly Wood", rgb:"#DEB887", hue:34, saturation:57, level:70],
        [name:"Cadet Blue", rgb:"#5F9EA0", hue:182, saturation:25, level:50],
        [name:"Chartreuse", rgb:"#7FFF00", hue:90, saturation:100, level:50],
        [name:"Chocolate", rgb:"#D2691E", hue:25, saturation:75, level:47],
        [name:"Cool White", rgb:"#F3F6F7", hue:187, saturation:19, level:96],
        [name:"Coral", rgb:"#FF7F50", hue:16, saturation:100, level:66],
        [name:"Corn Flower Blue", rgb:"#6495ED", hue:219, saturation:79, level:66],
        [name:"Corn Silk", rgb:"#FFF8DC", hue:48, saturation:100, level:93],
        [name:"Crimson", rgb:"#DC143C", hue:348, saturation:83, level:58],
        [name:"Cyan", rgb:"#00FFFF", hue:180, saturation:100, level:50],
        [name:"Dark Blue", rgb:"#00008B", hue:240, saturation:100, level:27],
        [name:"Dark Cyan", rgb:"#008B8B", hue:180, saturation:100, level:27],
        [name:"Dark Golden Rod", rgb:"#B8860B", hue:43, saturation:89, level:38],
        [name:"Dark Gray", rgb:"#A9A9A9", hue:0, saturation:0, level:66],
        [name:"Dark Green", rgb:"#006400", hue:120, saturation:100, level:20],
        [name:"Dark Khaki", rgb:"#BDB76B", hue:56, saturation:38, level:58],
        [name:"Dark Magenta", rgb:"#8B008B", hue:300, saturation:100, level:27],
        [name:"Dark Olive Green", rgb:"#556B2F", hue:82, saturation:39, level:30],
        [name:"Dark Orange", rgb:"#FF8C00", hue:33, saturation:100, level:50],
        [name:"Dark Orchid", rgb:"#9932CC", hue:280, saturation:61, level:50],
        [name:"Dark Red", rgb:"#8B0000", hue:0, saturation:100, level:27],
        [name:"Dark Salmon", rgb:"#E9967A", hue:15, saturation:72, level:70],
        [name:"Dark Sea Green", rgb:"#8FBC8F", hue:120, saturation:25, level:65],
        [name:"Dark Slate Blue", rgb:"#483D8B", hue:248, saturation:39, level:39],
        [name:"Dark Slate Gray", rgb:"#2F4F4F", hue:180, saturation:25, level:25],
        [name:"Dark Turquoise", rgb:"#00CED1", hue:181, saturation:100, level:41],
        [name:"Dark Violet", rgb:"#9400D3", hue:282, saturation:100, level:41],
        [name:"Daylight White", rgb:"#CEF4FD", hue:191, saturation:9, level:90],
        [name:"Deep Pink", rgb:"#FF1493", hue:328, saturation:100, level:54],
        [name:"Deep Sky Blue", rgb:"#00BFFF", hue:195, saturation:100, level:50],
        [name:"Dim Gray", rgb:"#696969", hue:0, saturation:0, level:41],
        [name:"Dodger Blue", rgb:"#1E90FF", hue:210, saturation:100, level:56],
        [name:"Fire Brick", rgb:"#B22222", hue:0, saturation:68, level:42],
        [name:"Floral White", rgb:"#FFFAF0", hue:40, saturation:100, level:97],
        [name:"Forest Green", rgb:"#228B22", hue:120, saturation:61, level:34],
        [name:"Fuchsia", rgb:"#FF00FF", hue:300, saturation:100, level:50],
        [name:"Gainsboro", rgb:"#DCDCDC", hue:0, saturation:0, level:86],
        [name:"Ghost White", rgb:"#F8F8FF", hue:240, saturation:100, level:99],
        [name:"Gold", rgb:"#FFD700", hue:51, saturation:100, level:50],
        [name:"Golden Rod", rgb:"#DAA520", hue:43, saturation:74, level:49],
        [name:"Gray", rgb:"#808080", hue:0, saturation:0, level:50],
        [name:"Green", rgb:"#008000", hue:120, saturation:100, level:25],
        [name:"Green Yellow", rgb:"#ADFF2F", hue:84, saturation:100, level:59],
        [name:"Honeydew", rgb:"#F0FFF0", hue:120, saturation:100, level:97],
        [name:"Hot Pink", rgb:"#FF69B4", hue:330, saturation:100, level:71],
        [name:"Indian Red", rgb:"#CD5C5C", hue:0, saturation:53, level:58],
        [name:"Indigo", rgb:"#4B0082", hue:275, saturation:100, level:25],
        [name:"Ivory", rgb:"#FFFFF0", hue:60, saturation:100, level:97],
        [name:"Khaki", rgb:"#F0E68C", hue:54, saturation:77, level:75],
        [name:"Lavender", rgb:"#E6E6FA", hue:240, saturation:67, level:94],
        [name:"Lavender Blush", rgb:"#FFF0F5", hue:340, saturation:100, level:97],
        [name:"Lawn Green", rgb:"#7CFC00", hue:90, saturation:100, level:49],
        [name:"Lemon Chiffon", rgb:"#FFFACD", hue:54, saturation:100, level:90],
        [name:"Light Blue", rgb:"#ADD8E6", hue:195, saturation:53, level:79],
        [name:"Light Coral", rgb:"#F08080", hue:0, saturation:79, level:72],
        [name:"Light Cyan", rgb:"#E0FFFF", hue:180, saturation:100, level:94],
        [name:"Light Golden Rod Yellow", rgb:"#FAFAD2", hue:60, saturation:80, level:90],
        [name:"Light Gray", rgb:"#D3D3D3", hue:0, saturation:0, level:83],
        [name:"Light Green", rgb:"#90EE90", hue:120, saturation:73, level:75],
        [name:"Light Pink", rgb:"#FFB6C1", hue:351, saturation:100, level:86],
        [name:"Light Salmon", rgb:"#FFA07A", hue:17, saturation:100, level:74],
        [name:"Light Sea Green", rgb:"#20B2AA", hue:177, saturation:70, level:41],
        [name:"Light Sky Blue", rgb:"#87CEFA", hue:203, saturation:92, level:75],
        [name:"Light Slate Gray", rgb:"#778899", hue:210, saturation:14, level:53],
        [name:"Light Steel Blue", rgb:"#B0C4DE", hue:214, saturation:41, level:78],
        [name:"Light Yellow", rgb:"#FFFFE0", hue:60, saturation:100, level:94],
        [name:"Lime", rgb:"#00FF00", hue:120, saturation:100, level:50],
        [name:"Lime Green", rgb:"#32CD32", hue:120, saturation:61, level:50],
        [name:"Linen", rgb:"#FAF0E6", hue:30, saturation:67, level:94],
        [name:"Maroon", rgb:"#800000", hue:0, saturation:100, level:25],
        [name:"Medium Aquamarine", rgb:"#66CDAA", hue:160, saturation:51, level:60],
        [name:"Medium Blue", rgb:"#0000CD", hue:240, saturation:100, level:40],
        [name:"Medium Orchid", rgb:"#BA55D3", hue:288, saturation:59, level:58],
        [name:"Medium Purple", rgb:"#9370DB", hue:260, saturation:60, level:65],
        [name:"Medium Sea Green", rgb:"#3CB371", hue:147, saturation:50, level:47],
        [name:"Medium Slate Blue", rgb:"#7B68EE", hue:249, saturation:80, level:67],
        [name:"Medium Spring Green", rgb:"#00FA9A", hue:157, saturation:100, level:49],
        [name:"Medium Turquoise", rgb:"#48D1CC", hue:178, saturation:60, level:55],
        [name:"Medium Violet Red", rgb:"#C71585", hue:322, saturation:81, level:43],
        [name:"Midnight Blue", rgb:"#191970", hue:240, saturation:64, level:27],
        [name:"Mint Cream", rgb:"#F5FFFA", hue:150, saturation:100, level:98],
        [name:"Misty Rose", rgb:"#FFE4E1", hue:6, saturation:100, level:94],
        [name:"Moccasin", rgb:"#FFE4B5", hue:38, saturation:100, level:85],
        [name:"Navajo White", rgb:"#FFDEAD", hue:36, saturation:100, level:84],
        [name:"Navy", rgb:"#000080", hue:240, saturation:100, level:25],
        [name:"Old Lace", rgb:"#FDF5E6", hue:39, saturation:85, level:95],
        [name:"Olive", rgb:"#808000", hue:60, saturation:100, level:25],
        [name:"Olive Drab", rgb:"#6B8E23", hue:80, saturation:60, level:35],
        [name:"Orange", rgb:"#FFA500", hue:39, saturation:100, level:50],
        [name:"Orange Red", rgb:"#FF4500", hue:16, saturation:100, level:50],
        [name:"Orchid", rgb:"#DA70D6", hue:302, saturation:59, level:65],
        [name:"Pale Golden Rod", rgb:"#EEE8AA", hue:55, saturation:67, level:80],
        [name:"Pale Green", rgb:"#98FB98", hue:120, saturation:93, level:79],
        [name:"Pale Turquoise", rgb:"#AFEEEE", hue:180, saturation:65, level:81],
        [name:"Pale Violet Red", rgb:"#DB7093", hue:340, saturation:60, level:65],
        [name:"Papaya Whip", rgb:"#FFEFD5", hue:37, saturation:100, level:92],
        [name:"Peach Puff", rgb:"#FFDAB9", hue:28, saturation:100, level:86],
        [name:"Peru", rgb:"#CD853F", hue:30, saturation:59, level:53],
        [name:"Pink", rgb:"#FFC0CB", hue:350, saturation:100, level:88],
        [name:"Plum", rgb:"#DDA0DD", hue:300, saturation:47, level:75],
        [name:"Powder Blue", rgb:"#B0E0E6", hue:187, saturation:52, level:80],
        [name:"Purple", rgb:"#800080", hue:300, saturation:100, level:25],
        [name:"Red", rgb:"#FF0000", hue:0, saturation:100, level:50],
        [name:"Rosy Brown", rgb:"#BC8F8F", hue:0, saturation:25, level:65],
        [name:"Royal Blue", rgb:"#4169E1", hue:225, saturation:73, level:57],
        [name:"Saddle Brown", rgb:"#8B4513", hue:25, saturation:76, level:31],
        [name:"Salmon", rgb:"#FA8072", hue:6, saturation:93, level:71],
        [name:"Sandy Brown", rgb:"#F4A460", hue:28, saturation:87, level:67],
        [name:"Sea Green", rgb:"#2E8B57", hue:146, saturation:50, level:36],
        [name:"Sea Shell", rgb:"#FFF5EE", hue:25, saturation:100, level:97],
        [name:"Sienna", rgb:"#A0522D", hue:19, saturation:56, level:40],
        [name:"Silver", rgb:"#C0C0C0", hue:0, saturation:0, level:75],
        [name:"Sky Blue", rgb:"#87CEEB", hue:197, saturation:71, level:73],
        [name:"Slate Blue", rgb:"#6A5ACD", hue:248, saturation:53, level:58],
        [name:"Slate Gray", rgb:"#708090", hue:210, saturation:13, level:50],
        [name:"Snow", rgb:"#FFFAFA", hue:0, saturation:100, level:99],
        [name:"Soft White", rgb:"#B6DA7C", hue:83, saturation:44, level:67],
        [name:"Spring Green", rgb:"#00FF7F", hue:150, saturation:100, level:50],
        [name:"Steel Blue", rgb:"#4682B4", hue:207, saturation:44, level:49],
        [name:"Tan", rgb:"#D2B48C", hue:34, saturation:44, level:69],
        [name:"Teal", rgb:"#008080", hue:180, saturation:100, level:25],
        [name:"Thistle", rgb:"#D8BFD8", hue:300, saturation:24, level:80],
        [name:"Tomato", rgb:"#FF6347", hue:9, saturation:100, level:64],
        [name:"Turquoise", rgb:"#40E0D0", hue:174, saturation:72, level:56],
        [name:"Violet", rgb:"#EE82EE", hue:300, saturation:76, level:72],
        [name:"Warm White", rgb:"#DAF17E", hue:72, saturation:20, level:72],
        [name:"Wheat", rgb:"#F5DEB3", hue:39, saturation:77, level:83],
        [name:"White", rgb:"#FFFFFF", hue:0, saturation:0, level:100],
        [name:"White Smoke", rgb:"#F5F5F5", hue:0, saturation:0, level:96],
        [name:"Yellow", rgb:"#FFFF00", hue:60, saturation:100, level:50],
        [name:"Yellow Green", rgb:"#9ACD32", hue:80, saturation:61, level:50]
    ].asImmutable()
}