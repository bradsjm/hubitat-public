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
static final String deviceType() { "RGBW/CT" }

import groovy.json.JsonSlurper
import groovy.transform.Field

metadata {
    definition (name: "Tasmota MQTT ${deviceType()}", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Actuator"
        capability "ChangeLevel"
        capability "ColorControl"
        capability "ColorMode"
        capability "ColorTemperature"
        capability "Configuration"
        capability "Light"
        capability "LightEffects"
        capability "Refresh"
        capability "Switch"
        capability "SwitchLevel"

        attribute "colorTemperatureName", "String"
        attribute "connection", "String"
        attribute "fadeMode", "String"
        attribute "fadeSpeed", "Number"
        attribute "groupMode", "String"
        attribute "hueName", "String"
        attribute "wifiSignal", "String"

        command "restart"

        command "setFadeSpeed", [
            [
                name:"Fade Speed*",
                type: "NUMBER",
                description: "Seconds (0 to 20) where 0 is off"
            ]
        ]

        command "setGroupTopicMode", [
            [
                name:"Group Mode*",
                type: "ENUM",
                description: "Select if changes are applied to the group",
                constraints: [
                    "single",
                    "grouped",
                ]
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
            input name: "groupTopic", type: "text", title: "Group Topic (Name)", description: "Group Topic value from Tasmota", required: true, defaultValue: "tasmotas"
            input name: "fullTopic", type: "text", title: "Full Topic Template", description: "Full Topic value from Tasmota", required: true, defaultValue: "%prefix%/%topic%/"
        }

        section("MQTT Broker") {
            input name: "mqttBroker", type: "text", title: "MQTT Broker Host/IP", description: "ex: tcp://hostnameorip:1883", required: true, defaultValue: "tcp://mqtt:1883"
            input name: "mqttUsername", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "mqttPassword", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }

        section("Misc") {
            input name: "relayNumber", type: "number", title: "Relay Number", description: "Used for Power commands", required: true, defaultValue: 1
            input name: "changeLevelStep", type: "decimal", title: "Change level step size", description: "Between 1 and 10", required: true, defaultValue: 2
            input name: "changeLevelEvery", type: "number", title: "Change level every x milliseconds", description: "Between 100ms and 1000ms", required: true, defaultValue: 100
            input name: "preStaging", type: "bool", title: "Enable color and level pre-staging", description: "Update of Dimmer/Color/CT without turning power on", required: true, defaultValue: false
            input name: "initialGroupMode", type: "enum", title: "Initial group mode", description: "Grouped uses the group topic", options: ["single", "grouped"], required: true, defaultValue: "single"
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
            input name: "watchdogEnable", type: "bool", title: "Enable watchdog logging", description: "Checks for mqtt activity every 5 minutes", required: true, defaultValue: true
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

void configure()
{
    // Set option 20 (Update of Dimmer/Color/CT without turning power on)
    mqttPublish(getTopic("cmnd", "SetOption20"), preStaging ? "1" : "0")
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver v${version()} installed"
    sendEvent(name: "lightEffects", value: new groovy.json.JsonBuilder(lightEffects))
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

    setGroupTopicMode(settings.initialGroupMode)
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

// Set the driver group topic mode
void setGroupTopicMode(mode) {
    sendEvent(newEvent("groupMode", mode))
}

/**
 *  Capability: Switch
 */

// Turn on
void on() {
    mqttPublish(getTopic("cmnd", "Power${settings.relayNumber}"), "1")
}

// Turn off
void off() {
    mqttPublish(getTopic("cmnd", "Power${settings.relayNumber}"), "0")
}

/**
 *  Capability: ChangeLevel
 */

// Start level change (up or down)
void startLevelChange(direction) {
    if (settings.changeLevelStep && settings.changeLevelEvery) {
        int delta = (direction == "down") ? -settings.changeLevelStep : settings.changeLevelStep
        doLevelChange(limit(delta, 1, 10))
    }
}

// Stop level change (up or down)
void stopLevelChange() {
    unschedule("doLevelChange")
}

private void doLevelChange(delta) {
    int newLevel = limit(device.currentValue("level").toInteger() + delta)
    setLevel(newLevel)
    if (newLevel > 0 && newLevel < 100) {
        int delay = limit(settings.changeLevelEvery, 100, 1000)
        runInMillis(delay, "doLevelChange", [ data: delta ])
    }
}

/**
 *  Capability: SwitchLevel
 */

// Set the brightness level and optional duration
void setLevel(level, duration = 0) {
    level = limit(level, 0, 100).toInteger()

    int oldSpeed = device.currentValue("fadeSpeed").toInteger() * 2
    int oldFade = device.currentValue("fadeMode") == "on" ? 1 : 0
    int speed = Math.min(40f, duration * 2).toInteger()
    if (speed > 0) {
        mqttPublish(getTopic("cmnd", "Backlog"), "Speed ${speed};Fade 1;Dimmer${settings.relayNumber} ${level};Delay ${duration * 10};Speed ${oldSpeed};Fade ${oldFade}")
    } else {
        mqttPublish(getTopic("cmnd", "Dimmer${settings.relayNumber}"), level.toString())
    }
}

/**
 *  Capability: ColorControl
 */

// Set the HSB color [hue:(0-100), saturation:(0-100), level:(0-100)]
void setColor(colormap) {
    if (colormap.hue == null || colormap.saturation == null) return
    int hue = limit(colormap.hue * 3.6, 0, 360).toInteger()
    int saturation = limit(colormap.saturation).toInteger()
    int level = limit(colormap.level).toInteger()

    mqttPublish(getTopic("cmnd", "HsbColor"), "${hue},${saturation},${level}")
}

// Set the hue (0-100)
void setHue(hue) {
    // Hubitat hue is 0-100 to be converted to Tasmota 0-360
    hue = limit(Math.round(hue * 3.6), 0, 360).toInteger()
    mqttPublish(getTopic("cmnd", "HsbColor1"), hue.toString())
}

// Set the saturation (0-100)
void setSaturation(saturation) {
    saturation = limit(saturation).toInteger()
    mqttPublish(getTopic("cmnd", "HsbColor2"), saturation.toString())
}

// Set the color temperature (2000-6536)
void setColorTemperature(kelvin) {
    kelvin = limit(kelvin, 2000, 6536)
    int channelCount = state.channelCount
    if (channelCount == 5) {
        int mired = limit(Math.round(1000000f / kelvin), 153, 500).toInteger()
        if (logEnable) log.debug "Converted ${kelvin} kelvin to ${mired} mired"
        mqttPublish(getTopic("cmnd", "CT"), mired.toString())
    } else if (channelCount == 4) {
        mqttPublish(getTopic("cmnd", "White"), device.currentValue("level").toString())
    }
}

/**
 *  Capability: Light Effects
 */

void setEffect(String effect) {
    def id = lightEffects.find { it.value == effect }
    if (id != null) setEffect(id.key)
}

def setEffect(id) {
    if (logEnable) log.debug "Setting effect $id"
    switch (id) {
        case 0:
            setEffectsScheme(0)
            blinkOff()
            break
        case 1: blinkOn()
            break
        case 2: setEffectsScheme(1)
            break
        case 3: setEffectsScheme(2)
            break
        case 4: setEffectsScheme(3)
            break
        case 5: setEffectsScheme(4)
            break
    }
}

def setNextEffect() {
    def currentEffect = state.crntEffectId ?: 0
    currentEffect++
    if (currentEffect > lightEffects.size() - 1) currentEffect = 0
    setEffect(currentEffect)
}

def setPreviousEffect() {
    def currentEffect = state.crntEffectId ?: 0
    currentEffect--
    if (currentEffect < 0) currentEffect = lightEffects.size() - 1
    setEffect(currentEffect)
}

void blinkOn() {
    mqttPublish(getTopic("cmnd", "Power${settings.relayNumber}"), "blink")
}

void blinkOff() {
    mqttPublish(getTopic("cmnd", "Power${settings.relayNumber}"), "blinkoff")
}

void setEffectsScheme(scheme) {
    mqttPublish(getTopic("cmnd", "Scheme"), scheme.toString())
}

/**
 *
 * Tasmota Custom Commands
 */

// Set the Tasmota fade speed
void setFadeSpeed(seconds) {
    int speed = Math.min(40f, seconds * 2).toInteger()
    if (speed > 0) {
        mqttPublish(getTopic("cmnd", "Backlog"), "Speed ${speed};Fade 1")
    } else {
        mqttPublish(getTopic("cmnd", "Fade"), "0")
    }
}

// Perform Tasmota wakeup function
void startWakeup(level, duration) {
    level = limit(level).toInteger()
    duration = limit(duration, 1, 3000).toInteger()
    mqttPublish(getTopic("cmnd", "Backlog"), "WakeupDuration ${duration};Wakeup ${level}")
}

/**
 *  Tasmota Device Specific
 */

 void restart() {
    mqttPublish(getTopic("cmnd", "Restart"), "1")
 }

// Parses Tasmota JSON content and send driver events
void parseTasmota(String topic, Map json) {
    if (json.containsKey("POWER")) {
        if (logEnable) log.debug "Parsing [ POWER: ${json.POWER} ]"
        sendEvent(newEvent("switch", json.POWER.toLowerCase()))
    }

    if (json.containsKey("Fade")) {
        if (logEnable) log.debug "Parsing [ Fade: ${json.Fade} ]"
        if (json.Fade == "OFF") json.Speed = 0
        sendEvent(newEvent("fadeMode", json.Fade.toLowerCase()))
    }

    if (json.containsKey("Speed")) {
        if (logEnable) log.debug "Parsing [ Speed: ${json.Speed} ]"
        def value = sprintf("%.1f", json.Speed.toInteger().div(2))
        sendEvent(newEvent("fadeSpeed", value, "s"))
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
        sendEvent(newEvent("colorMode", value))

        if (channelCount == 4 && json.Channel[3] > 0) {
            int fakeKelvin = 6500
            sendEvent(newEvent("colorTemperature", fakeKelvin, "K"))
            sendEvent(newEvent("colorTemperatureName", getTemperatureName(fakeKelvin)))
        }
    }

    if (json.containsKey("CT")) {
        if (logEnable) log.debug "Parsing [ CT: ${json.CT} ]"
        int kelvin = Math.round(1000000f / json.CT).toInteger()
        if (logEnable) log.debug "Converted ${json.CT} CT to ${kelvin} kelvin"
        sendEvent(newEvent("colorTemperature", kelvin, "K"))
        sendEvent(newEvent("colorTemperatureName", getTemperatureName(kelvin)))
    }

    if (json.containsKey("Dimmer")) {
        if (logEnable) log.debug "Parsing [ Dimmer: ${json.Dimmer} ]"
        sendEvent(newEvent("level", json.Dimmer, "%"))
    }

    if (json.containsKey("HSBColor")) {
        if (logEnable) log.debug "Parsing [ HSBColor: ${json.HSBColor} ]"
        def hsbColor = json.HSBColor.tokenize(",")
        int hue = hsbColor[0].toInteger()
        int saturation = hsbColor[1].toInteger()

        // Hubitat hue is 0-100 to be converted from Tasmota 0-360
        sendEvent(newEvent("hue", Math.round(hue / 3.6) as int))
        sendEvent(newEvent("hueName", getHueName(hue)))
        sendEvent(newEvent("saturation", saturation))
    }

    if (json.containsKey("Wifi")) {
        if (logEnable) log.debug "Parsing [ Wifi: ${json.Wifi} ]"
        updateDataValue("BSSId", json.Wifi.BSSId)
        updateDataValue("Channel", json.Wifi.Channel.toString())
        updateDataValue("LinkCount", json.Wifi.LinkCount.toString())
        updateDataValue("RSSI", json.Wifi.RSSI.toString())
        updateDataValue("Signal", json.Wifi.Signal.toString())
        updateDataValue("SSId", json.Wifi.SSId)
        sendEvent(newEvent("wifiSignal", getWifiSignalName(json.Wifi.RSSI)))
    }

    if (json.containsKey("StatusNET")) {
        if (logEnable) log.debug "Parsing [ StatusNET: ${json.StatusNET} ]"
        updateDataValue("Hostname", json.StatusNET.Hostname)
        updateDataValue("IPAddress", json.StatusNET.IPAddress)
    }

    if (json.containsKey("Uptime")) {
        if (logEnable) log.debug "Parsing [ Uptime: ${json.Uptime} ]"
        state.uptime = json.Uptime
    }

    if (json.containsKey("StatusPRM")) {
        if (logEnable) log.debug "Parsing [ StatusPRM: ${json.StatusPRM} ]"
        state.restartReason = json.StatusPRM.RestartReason
    }

    if (json.containsKey("StatusFWR")) {
        if (logEnable) log.debug "Parsing [ StatusFWR: ${json.StatusFWR} ]"
        state.version = json.StatusFWR.Version
    }

    state.lastResult = json
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
        case 3200..3350: temperatureName = "Studio White"
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

private int getRetrySeconds() {
    final minimumRetrySec = 20
    final maximumRetrySec = minimumRetrySec * 6
    int count = state.mqttRetryCount ?: 0
    int jitter = new Random().nextInt(minimumRetrySec.intdiv(2))
    state.mqttRetryCount = count + 1
    return Math.min(minimumRetrySec * Math.pow(2, count) + jitter, maximumRetrySec)
}

private String getTopic(String prefix, String postfix = "", boolean forceSingle = false)
{
    String topic = settings.deviceTopic
    if (!forceSingle && device.currentValue("groupMode") == "grouped" && settings.groupTopic) {
        topic = settings.groupTopic
    }

    settings.fullTopic
        .replaceFirst("%prefix%", prefix)
        .replaceFirst("%topic%", topic)
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
        parseTasmota(topic, parseJson(payload))
    } else {
        state.mqttReceiveTime = now()
        def key = topic.substring(topic.lastIndexOf("/")+1)
        parseTasmota(topic, [ (key): payload ])
    }
}

private void mqttSubscribeTopics() {
    int qos = 1 // at least once delivery
    String teleTopic = getTopic("tele", "+", true)
    if (logEnable) log.debug "Subscribing to Tasmota telemetry topic: ${teleTopic}"
    interfaces.mqtt.subscribe(teleTopic, qos)

    String statTopic = getTopic("stat", "+", true)
    if (logEnable) log.debug "Subscribing to Tasmota stat topic: ${statTopic}"
    interfaces.mqtt.subscribe(statTopic, qos)
}
