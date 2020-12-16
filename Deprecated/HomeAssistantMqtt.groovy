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
import groovy.transform.Field
import java.util.regex.Matcher
import hubitat.helper.ColorUtils

static final String version() { "0.1" }

@Field final Map Mappings = getMappings()

metadata {
    definition (name: "Tasmota MQTT Devices", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw") {
        capability "Initialize"
        capability "PresenceSensor"

        attribute "foundDevices", "Number"
        attribute "offlineDevices", "Number"
    }

    preferences() {
        section("MQTT Device Topics") {
            input name: "discoveryPrefix", type: "text", title: "Discovery Prefix", description: "Discovery Topic Prefix", required: true, defaultValue: "homeassistant"
        }

        section("MQTT Broker") {
            input name: "mqttBroker", type: "text", title: "MQTT Broker Host/IP", description: "ex: tcp://hostnameorip:1883", required: true, defaultValue: "tcp://mqtt:1883"
            input name: "mqttUsername", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "mqttPassword", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }

        section("Misc") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver v${version()} initializing"
    unschedule()
    state.clear()

    state.Subscriptions = [:]
    state.DeviceState = [:]

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

// Called to parse received MQTT data
def parse(data) {
    mqttReceive(interfaces.mqtt.parseMessage(data))
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
 * Discovery logic
 */

private String getDeviceDriver(String component, Map config) {
    final Map driverMap = [
        "light": [
            [ "bri_stat_t", "clr_temp_stat_t", "rgb_stat_t" ]: "Generic Component RGBW",
            [ "brightness_state_topic", "color_temp_state_topic", "rgb_state_topic" ]: "Generic Component RGBW",

            [ "bri_stat_t", "whit_val_stat_t", "rgb_stat_t" ]: "Generic Component RGBW",
            [ "brightness_state_topic", "white_value_state_topic", "rgb_state_topic" ]: "Generic Component RGBW",

            [ "bri_stat_t", "rgb_stat_t" ]: "Generic Component RGB",
            [ "brightness_state_topic", "rgb_state_topic" ]: "Generic Component RGB",

            [ "bri_stat_t", "clr_temp_stat_t" ]: "Generic Component CT",
            [ "brightness_state_topic", "color_temp_state_topic" ]: "Generic Component CT",

            [ "bri_stat_t" ]: "Generic Component Dimmer",
            [ "brightness_state_topic" ]: "Generic Component Dimmer",

            [ "stat_t", "cmd_t" ]: "Generic Component Switch",
            [ "status_topic", "command_topic" ]: "Generic Component Switch"
        ],
        "switch": [
            [ "stat_t", "cmd_t" ]: "Generic Component Switch",
            [ "status_topic", "command_topic" ]: "Generic Component Switch"
        ],
        "binary_sensor": [
            [ "stat_t" ]: "Generic Component Switch",
            [ "status_topic" ]: "Generic Component Switch"
        ]
    ]

    if (logEnable) log.trace "Autodiscovery: Identifying driver for ${component}"

    if (driverMap[component]) {
        // Return first driver that has all the required attributes
        return driverMap[component].find({ attributes, driver ->
            attributes.every({ config.containsKey(it) })
        })?.value
    }
}

private void parseAutoDiscovery(String topic, Map config) {
    String component = topic.tokenize("/")[1]

    if (!config) {
        log.warn "Autodiscovery: Config empty or missing ${topic}"
        return
    }

    switch (component) {
        case "light":
        case "switch":
        case "binary_sensor":
            parseAutoDiscoveryDevice(component, topic, config)
            break

        default:
            log.info "Autodiscovery: ${component} component not supported ${topic}"
    }
}

private void parseAutoDiscoveryDevice(String component, String topic, Map config) {
    String name = config.name
    String dni = config.uniq_id

    if (!dni) {
        log.warn "Autodiscovery: Config missing unique id ${topic}"
        return
    }

    String driver = getDeviceDriver(component, config)
    if (!driver) {
        log.info "Autodiscovery: Missing driver for ${name} ${topic}"
        return
    }

    def device = getOrCreateDevice(driver, dni, name)
    if (!device) {
        log.warn "Autodiscovery: Unable to create driver ${driver} for ${name}"
        return
    }

    // Update device name
    if (device.getName() != name) device.setName(name)

    // Persist configuration to device data fields
    config.each {
        device.updateDataValue(it.key, it.value.toString())
    }

    log.info "Autodiscovery: ${device} (${dni}) using ${driver} driver"

    // Iterate to find state topics to subscribe to
    Set subscriptions = config.findResults({
        if (Mappings.containsKey(it.key)) {
            if (!state.Subscriptions.containsKey(it.value)) 
                state.Subscriptions[it.value] = [] as Set
            state.Subscriptions[it.value].add(config.uniq_id)
            return it.value
        }
    }) as Set

    // Add topic subscriptions
    subscriptions.each {
        if (logEnable) log.trace "Autodiscovery: Subscribing to topic ${it} for ${device}"
        interfaces.mqtt.subscribe(it, config.qos ?: 0)
    }

    // Force refresh of state
    componentRefresh(device)
}

private def getOrCreateDevice(String driverName, String deviceNetworkId, String name) {
    def childDevice = getChildDevice(deviceNetworkId)
    if (!childDevice) {
        log.info "Autodiscovery: Creating child device ${name} [${deviceNetworkId}] (${driverName})"
        childDevice = addChildDevice(
            "hubitat",
            driverName,
            deviceNetworkId,
            [
                name: name
            ]
        )
    } else {
        if (logEnable) log.debug "Autodiscovery: Found child device ${name} [${deviceNetworkId}] (${driverName})"
    }

    return childDevice
}

/**
 *  Message Parsing logic
 */

private void parseTopicPayload(def device, String topic, String payload) {
    List<Map> events = []
    
    // Get the configuration from the device
    def config = device.getData()

    // Detect and parse json payload
    Map json = (payload.startsWith("{") && payload.endsWith("}")) ? parseJson(payload) : null

    // Find all the variables matching the topic we received (could be multiple)
    config.findAll({ it.value == topic }).each({
        // Get the action mappings for this variable
        Mappings[it.key].each({ action ->
            String template = config[action.template]
            def value = template ? parseTemplate(template, payload, json) : payload
            if (logEnable) log.trace "${device} ${action.event ?: ""}: Parsed ${payload} using template ${template} to ${value}"

            if (action.func) {
                value = action.func.call([ device: device, config: config, value: value ])
                if (logEnable) log.trace "${device} ${action.event ?: ""}: Translated value to ${value}"
            }

            if (value != null && action.event && device.currentValue(action.event) != value) {
                if (logEnable) log.trace "${device} ${action.event ?: ""}: Pushing event value ${value}"
                events << newEvent(device, action.event, value, action?.unit)
            }
        })
    })

    if (events) { 
        // Publish events to device
        device.parse(events)

        // count unique ids of devices
        sendEvent(name: "foundDevices", value: state.DeviceState.size())

        // Count offline devices
        sendEvent(name: "offlineDevices", value: state.DeviceState.findAll{
            it.value == config["payload_not_available"] || it.value == config["pl_not_avail"]
        }.size())
    }
}

private def parseTemplate(String template, String value, Map value_json) {
    def result

    switch (template.replaceAll(/[{}\s]/, "")) {
        case "value":
            result = value
            break
        
        case ~/value_json\.(\w+).*/:
            String prop = Matcher.lastMatcher[0][1]
            result = value_json[prop]
            break

        case ~/value_json\[\'(\w+)\'\].*/:
            String prop = Matcher.lastMatcher[0][1]
            result = value_json[prop]
            break

        default:
            log.warn "Unknown template token: ${it}"
            break
    }

    return result
}

/**
 *  Component child device callbacks
 */

void componentOn(device) {
    String topic = device.getDataValue("cmd_t") ?: device.getDataValue("command_topic")
    int qos = device.getDataValue("qos")?.toInteger() ?: 0
    if (topic) {
        String payload = device.getDataValue("pl_on") ?: device.getDataValue("payload_on")
        log.info "Turning ${device} on"
        mqttPublish(topic, payload, qos)
    }
}

void componentOff(device) {
    String topic = device.getDataValue("cmd_t") ?: device.getDataValue("command_topic")
    int qos = device.getDataValue("qos")?.toInteger() ?: 0
    if (topic) {
        String payload = device.getDataValue("pl_off") ?: device.getDataValue("payload_off")
        log.info "Turning ${device} off"
        mqttPublish(topic, payload, qos)
    }
}

void componentSetLevel(device, level) {
    String topic = device.getDataValue("bri_cmd_t") ?: device.getDataValue("brightness_command_topic")
    int qos = device.getDataValue("qos")?.toInteger() ?: 0
    if (topic) {
        String payload = level.toString()
        log.info "Setting ${device} level to ${level}%"
        mqttPublish(topic, payload, qos)
    }
}

// void componentStartLevelChange(device, direction) {
//     if (settings.changeLevelStep && settings.changeLevelEvery) {
//         int delta = (direction == "down") ? -settings.changeLevelStep : settings.changeLevelStep
//         doLevelChange(limit(delta, -10, 10))
//         log.info "${device.displayName} Starting level change ${direction}"
//     }
// }

// void componentStopLevelChange(device) {
//     unschedule("doLevelChange")
//     log.info "${device.displayName} Stopping level change"
// }

// private void doLevelChange(device, delta) {
//     int newLevel = limit(device.currentValue("level").toInteger() + delta)
//     componentSetLevel(device, newLevel)
//     if (newLevel > 0 && newLevel < 99) {
//         int delay = limit(settings.changeLevelEvery, 100, 1000)
//         runInMillis(delay, "doLevelChange", [ data: [device, delta] ])
//     }
// }

void componentSetColorTemperature(device, value) {
    String topic = device.getDataValue("clr_temp_cmd_t") ?: device.getDataValue("color_temp_command_topic")
    int qos = device.getDataValue("qos")?.toInteger() ?: 0
    if (topic) {
        String payload = Math.round(1000000f / value.toInteger()).toString()
        log.info "Setting ${device} color temperature to ${value}K"
        mqttPublish(topic, payload, qos)
        return
    }

    topic = device.getDataValue("whit_val_cmd_t") ?: device.getDataValue("white_value_command_topic")
    if (topic) {
        // ignore value of color temperature as there is only one white for this device
        String payload = device.currentValue("level").toString()
        log.info "Setting ${device} white channel to ${value}K"
        mqttPublish(topic, payload, qos)
        return
    }
}

void componentSetColor(device, colormap) {
    def (int r, int g, int b) = ColorUtils.hsvToRGB([colormap.hue, colormap.saturation, colormap.level])
    String topic = device.getDataValue("rgb_cmd_t") ?: device.getDataValue("rgb_command_topic")
    int qos = device.getDataValue("qos")?.toInteger() ?: 0
    if (topic) {
        String payload = "${r},${g},${b}"
        log.info "Setting ${device} color (RGB) to ${payload}"
        mqttPublish(topic, payload, qos)
    }
}

void componentSetHue(device, hue) {
    componentSetColor(device, [
        hue: hue, 
        saturation: 100, 
        level: device.currentValue("level") ?: 100
    ])
}

void componentSetSaturation(device, percent) {
    componentSetColor(device, [
        hue: device.currentValue("hue") ?: 100, 
        saturation: percent, 
        level: device.currentValue("level") ?: 100
    ])
}

void componentRefresh(device) {
    String topic = device.getDataValue("cmd_t") ?: device.getDataValue("command_topic")
    int qos = device.getDataValue("qos")?.toInteger() ?: 0
    if (topic) {
        topic = topic.tokenize("/")[0..-2].plus("STATE").join("/")
        log.info "Refreshing ${device}"
        mqttPublish(topic, "", qos)
    }
}

/**
 *  Common Tasmota MQTT communication methods
 */

private boolean mqttConnect() {
    unschedule("mqttConnect")
    try {
        def hub = device.getHub()
        def mqtt = interfaces.mqtt
        String clientId = hub.hardwareID + "-" + device.id
        log.info "Connecting to MQTT broker at ${settings.mqttBroker}"
        state.ConnectCount = (state?.ConnectCount ?: 0) + 1
        mqtt.connect(
            settings.mqttBroker,
            clientId,
            settings?.mqttUsername,
            settings?.mqttPassword
        )
        return true
    } catch(e) {
        log.error "MQTT connect error: ${e}"
        runInMillis(new Random(now()).nextInt(30000), "mqttConnect")
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

private void mqttPublish(String topic, String payload = "", int qos = 0) {
    if (interfaces.mqtt.isConnected()) {
        if (logEnable) log.debug "PUB: ${topic} = ${payload}"
        interfaces.mqtt.publish(topic, payload, qos, false)
        state.TransmitCount = (state?.TransmitCount ?: 0) + 1
    } else {
        log.warn "MQTT not connected, unable to publish ${topic} = ${payload}"
        runInMillis(new Random(now()).nextInt(30000), "initialize")
    }
}

private void mqttReceive(Map message) {
    String topic = message.get("topic")
    String payload = message.get("payload")
    if (logEnable) log.debug "RCV: ${topic} = ${payload}"
    state.ReceiveCount = (state?.mqttReceiveCount ?: 0) + 1

    // Ignore empty payloads
    if (!payload) return

    if (topic.startsWith(settings.discoveryPrefix) && topic.endsWith("config")) {
        // Parse Home Assistant Discovery topic
        parseAutoDiscovery(topic, parseJson(payload))
    } else if (state.Subscriptions.containsKey(topic)) {
        // Parse one of our subscription notifications
        state.Subscriptions[topic].each({ dni ->
            def childDevice = getChildDevice(dni)
            if (childDevice) {
                parseTopicPayload(childDevice, topic, payload)
            } else {
                log.warn "Unable to find child device id ${dni} for topic ${topic}"
            }
        })
    } else {
        if (logEnable) log.debug "Unhandled topic ${topic}, ignoring payload"
    }
}

// Called with MQTT client status messages
void mqttClientStatus(String status) {
    // The string that is passed to this method with start with "Error" if an error occurred or "Status" if this is just a status message.
    def parts = status.split(": ")
    switch (parts[0]) {
        case "Error":
            log.warn "MQTT ${status}"
            switch (parts[1]) {
                case "Connection lost":
                case "send error":
                    sendEvent(name: "presence", value: "not present", descriptionText: "${device.displayName} {$parts[1]}")
                    runInMillis(new Random(now()).nextInt(30000), "initialize")
                    break
            }
            break
        case "Status":
            log.info "MQTT ${status}"
            switch (parts[1]) {
                case "Connection succeeded":
                    sendEvent(name: "presence", value: "present", descriptionText: "${device.displayName} is connected")
                    // without this delay the `parse` method is never called
                    // (it seems that there needs to be some delay after connection to subscribe)
                    runInMillis(1000, "mqttSubscribeDiscovery")
                    break
            }
            break
        default:
        	if (logEnable) log.debug "MQTT ${status}"
            break
    }
}

private void mqttSubscribeDiscovery() {
    String[] discoveryTopics = [ "light", "switch", "binary_sensor" ]

    discoveryTopics.each {
        String haDiscoveryTopic = "${settings.discoveryPrefix}/${it}/#"
        if (logEnable) log.trace "Subscribing to Homeassistant discovery topic: ${haDiscoveryTopic}"
        interfaces.mqtt.subscribe(haDiscoveryTopic)
    }
}

/**
 *  Utility methods
 */

private String getGenericName(def hsv) {
    String colorName

    if (!hsv[0] && !hsv[1]) {
        colorName = "White"
    } else {
        switch (hsv[0] * 3.6 as int) {
            case 0..15: colorName = "Red"
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
    }

    if (logEnable) log.debug "Converting ${hsv} to ${colorName}"

    return colorName
}

private String getGenericTempName(int value) {
    String genericName
    if (!value) return

    if (value <= 2000) genericName = "Sodium"
    else if (value <= 2100) genericName = "Starlight"
    else if (value < 2400) genericName = "Sunrise"
    else if (value < 2800) genericName = "Incandescent"
    else if (value < 3300) genericName = "Soft White"
    else if (value < 3500) genericName = "Warm White"
    else if (value < 4150) genericName = "Moonlight"
    else if (value <= 5000) genericName = "Horizon"
    else if (value < 5500) genericName = "Daylight"
    else if (value < 6000) genericName = "Electronic"
    else if (value <= 6500) genericName = "Skylight"
    else if (value < 20000) genericName = "Polar"

    if (logEnable) log.debug "Converting ${value} to ${genericName}"

    return genericName
}

private Map getMappings() {
    [
        "avty_t": [
            [ func: { state.DeviceState[it.config.uniq_id] = it.value } ]
        ],
        "stat_t": [
            [ event: "switch", template: "val_tpl", func: { it.value == (it.config["stat_off"] ?: it.config["pl_off"] ?: "OFF") ? "off" : "on" } ]
        ],
        "status_topic": [
            [ event: "switch", template: "value_template", func: { it.value == (it.config["state_off"] ?: it.config["payload_off"] ?: "OFF") ? "off" : "on" } ]
        ],
        "bri_stat_t": [
            [ event: "level", template: "bri_val_tpl", unit: "%", func: { Math.round(it.value.toFloat() / it.config["bri_scl"].toFloat() * 100) } ]
        ],
        "brightness_state_topic": [
            [ event: "level", template: "brightness_value_template", unit: "%", func: { Math.round(it.value.toFloat() / it.config["brightness_scale"].toFloat() * 100) } ]
        ],
        "clr_temp_stat_t": [
            [ event: "colorTemperature", template: "clr_temp_val_tpl", unit: "K", func: { toKelvin(it.value) } ],
        ],
        "color_temp_state_topic": [
            [ event: "colorTemperature", template: "color_temp_value_template", unit: "K", func: { toKelvin(it.value) } ],
        ],
        "rgb_stat_t": [
            [ event: "hue", template: "rgb_val_tpl", func: { rgbToHSV(it.value)[0] as int } ],
            [ event: "saturation", template: "rgb_val_tpl", func: { rgbToHSV(it.value)[1] as int } ],
            [ event: "color", template: "rgb_val_tpl", func: { rgbToHEX(it.value) } ],
            [ event: "colorMode", template: "rgb_val_tpl", func: { it.value.startsWith("0,0,0") ? "CT" : "RGB" } ],
            [ event: "colorName", template: "rgb_val_tpl", func: { getGenericName(rgbToHSV(it.value)) } ],
        ],
        "rgb_status_topic": [
            [ event: "hue", template: "rgb_value_template", func: { rgbToHSV(it.value)[0] as int } ],
            [ event: "saturation", template: "rgb_value_template", func: { rgbToHSV(it.value)[1] as int } ],
            [ event: "color", template: "rgb_value_template", func: { rgbToHEX(it.value) } ],
            [ event: "colorMode", template: "rgb_value_template", func: { it.value.startsWith("0,0,0") ? "CT" : "RGB" } ],
            [ event: "colorName", template: "rgb_value_template", func: { getGenericName(rgbToHSV(it.value)) } ],
        ]
    ]
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
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
    log.info "debug logging disabled for ${device.displayName}"
}

private Map newEvent(def device, String name, def value, unit = null) {
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: "${device.displayName} ${name} is ${value}${unit ?: ''}"
    ]
}

private def rgbToHSV(String rgb) {
    return ColorUtils.rgbToHSV(rgb.tokenize(",")*.asType(int).take(3))
}

private def rgbToHEX(String rgb) {
    return ColorUtils.rgbToHEX(rgb.tokenize(",")*.asType(int).take(3))
}

private int toKelvin(value) {
    return Math.round(1000000f / value) as int
}