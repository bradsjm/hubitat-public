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

static final String version() { return "0.1" }

metadata {
    definition (name: "Tasmota RGBW Driver", namespace: "tasmota", author: "Jonathan Bradshaw") {
        capability "ChangeLevel"
        capability "ColorControl"
        capability "ColorMode"
        capability "ColorTemperature"
        capability "Light"
        capability "PresenceSensor"
        capability "Refresh"
        capability "SignalStrength"
        capability "SwitchLevel"

        attribute "fadeMode", "String"
        attribute "fadeSpeed", "Number"
        attribute "fxScheme", "String"
        attribute "groupMode", "String"

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

        command "setFadeSpeed", [
            [
                name:"Fade Speed*",
                type: "NUMBER",
                description: "Seconds (0 to 20) where 0 is off"
            ]
        ]
        command "setEffectsScheme", [
            [
                name:"Effects Scheme*",
                type: "ENUM",
                description: "Select light effect scheme",
                constraints: [
                    "None",
                    "Wakeup",
                    "Cycle Up",
                    "Cycle Down",
                    "Random Cycle"
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
            input name: "mqttQOS", type: "text", title: "MQTT QOS setting", description: "0 = Only Once, 1 = At Least Once*, 2 = Exactly Once", required: true, defaultValue: "1"
        }

        section("Misc") {
            input name: "changeLevelStep", type: "decimal", title: "Change level step size", description: "Between 1 and 10", required: true, defaultValue: 2
            input name: "changeLevelEvery", type: "number", title: "Change level every x milliseconds", description: "Between 100ms and 1000ms", required: true, defaultValue: 100
            input name: "initialGroupMode", type: "enum", title: "Initial group mode", description: "Grouped uses the group topic", options: ["single", "grouped"], required: true, defaultValue: "single"
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is first created.
void installed() {
    log.debug "${device.displayName} driver v${version()} installed"
}

// Called to parse received MQTT data
void parse(String data) {
    def message = interfaces.mqtt.parseMessage(data)
    mqttReceive(message)
}

// Called when the user requests a refresh (from Refresh capability)
// Requests latest STATE and STATUS 5 (Network)
void refresh() {
    if (logEnable) log.debug "Refreshing state of ${device.name}"

    def commandTopic = getTopic("cmnd", "Backlog")
    mqttPublish(commandTopic, "State;Status 5")
    state.clear()
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
    log.debug "${device.displayName} driver v${version()} uninstalled"
}

// Called when the preferences of a device are updated.
void updated() {
    log.debug "${device.displayName} driver v${version()} configuration updated"
    setGroupTopicMode(options.initialGroupMode)

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
 *  Capability: Light
 */

// Turn on
void on() {
    def commandTopic = getTopic("cmnd", "POWER")
    mqttPublish(commandTopic, "1")
}

// Turn off
void off() {
    def commandTopic = getTopic("cmnd", "POWER")
    mqttPublish(commandTopic, "0")
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
    def newLevel = limit(device.currentValue("level").toInteger() + delta)
    setLevel(newLevel)
    if (newLevel > 0 && newLevel < 100) {
        def delay = limit(settings.changeLevelEvery, 100, 1000)
        runInMillis(delay, "doLevelChange", [ data: delta ])
    }
}

/**
 *  Capability: SwitchLevel
 */

// Set the brightness level and optional duration
void setLevel(level, duration = 0) {
    level = limit(level, 0, 100).toInteger()

    def oldSpeed = device.currentValue("fadeSpeed") * 2
    def oldFade = device.currentValue("fadeMode")
    def speed = Math.min(40f, duration * 2).toInteger()
    if (speed > 0) {
        def commandTopic = getTopic("cmnd", "Backlog")
        mqttPublish(commandTopic, "Speed ${speed};Fade 1;Dimmer ${level};Delay ${duration * 10};Speed ${oldSpeed};Fade ${oldFade}")
    } else {
        def commandTopic = getTopic("cmnd", "Dimmer")
        mqttPublish(commandTopic, level.toString())
    }
}

/**
 *  Capability: ColorControl
 */

// Set the HSB color [hue:(0-100), saturation:(0-100), level:(0-100)]
void setColor(colormap) {
    if (colormap.hue == null || colormap.saturation == null) return
    def hue = limit(colormap.hue * 3.6, 0, 360).toInteger()
    def saturation = limit(colormap.saturation).toInteger()
    def level = limit(colormap.level).toInteger()

    def commandTopic = getTopic("cmnd", "HsbColor")
    mqttPublish(commandTopic, "${hue},${saturation},${level}")
}

// Set the hue (0-100)
void setHue(hue) {
    hue = limit(colormap.hue * 3.6, 0, 360).toInteger()
    def commandTopic = getTopic("cmnd", "HsbColor1")
    mqttPublish(commandTopic, "${hue}")
}

// Set the saturation (0-100)
void setSaturation(saturation) {
    saturation = limit(saturation).toInteger()
    def commandTopic = getTopic("cmnd", "HsbColor2")
    mqttPublish(commandTopic, "${saturation}")
}

// Set the color temperature (0-100)
// Value ignored as CT not supported, only White
void setColorTemperature(kelvin) {
    def commandTopic = getTopic("cmnd", "White")
    mqttPublish(commandTopic, device.currentValue("level"))
}

/**
 *
 * Tasmota Custom Commands
 */

void setEffectsScheme(scheme) {
    def choices = [
        "None",
        "Wakeup",
        "Cycle Up",
        "Cycle Down",
        "Random Cycle"
    ]
    def value = choices.findIndexOf{ it == scheme }
    if (value >= 0) {
        def commandTopic = getTopic("cmnd", "Scheme")
        mqttPublish(commandTopic, value.toString())
    }
}

// Set the Tasmota fade speed
void setFadeSpeed(seconds) {
    def speed = Math.min(40f, seconds * 2).toInteger()
    if (speed > 0) {
        def commandTopic = getTopic("cmnd", "Backlog")
        mqttPublish(commandTopic, "Speed ${speed};Fade 1")
    } else {
        def commandTopic = getTopic("cmnd", "Fade")
        mqttPublish(commandTopic, "0")
    }
}

// Perform Tasmota wakeup function
void startWakeup(level, duration) {
    level = limit(level).toInteger()
    duration = limit(duration, 1, 3000).toInteger()
    def commandTopic = getTopic("cmnd", "Backlog")
    mqttPublish(commandTopic, "WakeupDuration ${duration};Wakeup ${level}")
}

// Set the driver group topic mode
void setGroupTopicMode(mode) {
    def item = [
        name: "groupMode",
        value: mode
    ]
    item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
    sendEvent(item)
}

/**
 *  Tasmota Device Specific
 */

// Parses Tasmota JSON content and send driver events
void parseTasmota(String topic, Map json) {
    def item = [name:"", value:"", descriptionText:""]

    if (json.containsKey("POWER")) {
        if (logEnable) log.debug "Parsing [ POWER: ${json.POWER} ]"
        item.with {
            name = "switch"
            value = json.POWER.toLowerCase()
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)
    }

    if (json.containsKey("Fade")) {
        if (logEnable) log.debug "Parsing [ Fade: ${json.Fade} ]"
        if (json.Fade == "OFF") json.Speed = 0
        item.with {
            name = "fadeMode"
            value = json.Fade.toLowerCase()
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)
    }

    if (json.containsKey("Speed")) {
        if (logEnable) log.debug "Parsing [ Speed: ${json.Speed} ]"
        item.with {
            name = "fadeSpeed"
            value = sprintf("%.1f", json.Speed.toInteger().div(2)) // seconds
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)
    }

    if (json.containsKey("Color")) {
        if (logEnable) log.debug "Parsing [ Color: ${json.Color} ]"
        // Check for active white channels to set ColorMode
        def color = json.Color.tokenize(",")
        def white = (color.size() > 3 && color.drop(3).any{e -> e.toInteger() > 0})
        item.with {
            name = "colorMode"
            value = white ? "CT" : "RGB"
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)

        if (white) {
            item.with {
                name = "colorTemperature"
                value = "6500" // Bogus max value for non-CT bulb
            }
            item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
            sendEvent(item)
        }
    }

    if (json.containsKey("Dimmer")) {
        if (logEnable) log.debug "Parsing [ Dimmer: ${json.Dimmer} ]"
        item.with {
            name = "level"
            value = json.Dimmer
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)
    }

    if (json.containsKey("HSBColor")) {
        if (logEnable) log.debug "Parsing [ HSBColor: ${json.HSBColor} ]"
        def hsbColor = json.HSBColor.tokenize(",")
        def hue = Math.round((hsbColor[0] as int) / 3.6)
        def saturation = hsbColor[1] as int
        def level = hsbColor[2] = hsbColor[2] as int

        item.with {
            name = "hue"
            value = hue
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)

        item.with {
            name = "saturation"
            value = saturation
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)
    }

    if (json.containsKey("Wifi")) {
        if (logEnable) log.debug "Parsing [ Wifi: ${json.Wifi} ]"
        item.with {
            name = "rssi"
            value = json.Wifi.RSSI
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)

        item.with {
            name = "lqi"
            value = Math.max(0, 256 - json.Wifi.LinkCount)
        }
        item.descriptionText = "${device.displayName} ${item.name} is ${item.value}"
        sendEvent(item)
    }

    if (json.containsKey("StatusNET")) {
        if (logEnable) log.debug "Parsing [ StatusNET: ${json.StatusNET} ]"
        def mac = json.StatusNET.Mac.toLowerCase()
        if (device.deviceNetworkId != mac) {
            log.info "Updating Device Network Id to ${mac} from ${device.deviceNetworkId}"
            device.deviceNetworkId = mac
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
    def count = state.mqttRetryCount ?: 0
    def jitter = new Random().nextInt(minimumRetrySec.intdiv(2))
    state.mqttRetryCount = count + 1
    return Math.min(minimumRetrySec * Math.pow(2, count) + jitter, maximumRetrySec)
}

private String getTopic(String prefix, String postfix = "", boolean forceSingle = false)
{
    def topic = settings.deviceTopic
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

private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
}

private boolean mqttCheckConnected() {
    if (interfaces.mqtt.isConnected() == false) {
        log.warn "MQTT is not connected"
        sendEvent (name: "presence", value: "not present")
        if (!mqttConnect()) {
            def waitSeconds = getRetrySeconds()
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
        mqttSubscribeTopics()
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
    def topic = message.get("topic")
    def payload = message.get("payload")
    state.mqttReceiveCount = (state?.mqttReceiveCount ?: 0) + 1

    def availabilityTopic = getTopic("tele", "LWT")
    if (topic == availabilityTopic) {
        switch(payload.toLowerCase()) {
            case "online":
            sendEvent (name: "presence", value: "present")
            log.info "${device.displayName} is online"
            break

            case "offline":
            log.warn "${device.displayName} has gone offline"
            sendEvent (name: "presence", value: "not present")
            break

            default:
            if (logEnable) log.debug "Unknown availability: ${topic} = ${payload}"
        }
    } else if (payload[0] == "{") {
        payload = parseJson(payload)
        parseTasmota(topic, payload)
    } else {
        if (logEnable) log.debug "Unknown Tasmota message: ${topic} = ${payload}"
    }
}

private void mqttSubscribeTopics() {
    int qos = settings.mqttQOS.toInteger()
    def teleTopic = getTopic("tele", "+", true)
    if (logEnable) log.debug "Subscribing to Tasmota telemetry topic: ${teleTopic}"
    interfaces.mqtt.subscribe(teleTopic, qos)

    def statTopic = getTopic("stat", "+", true)
    if (logEnable) log.debug "Subscribing to Tasmota stat topic: ${statTopic}"
    interfaces.mqtt.subscribe(statTopic, qos)
}
