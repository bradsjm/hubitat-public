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
static final String deviceType() { "Color Group" }

import java.security.MessageDigest
import groovy.transform.Field

metadata {
    definition (name: "Tasmota ${deviceType()} (MQTT)", namespace: "tasmota-mqtt", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/Drivers/Tasmota-Mqtt-RGBWCT.groovy") {
        capability "Actuator"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "Configuration"
        capability "Light"
        capability "Refresh"
        capability "Switch"
        capability "SwitchLevel"

        command "setFadeSpeed", [
            [
                name:"Fade Speed*",
                type: "NUMBER",
                description: "Seconds (0 to 20) where 0 is off"
            ]
        ]

        command "nextColor"
        command "previousColor"

        attribute "connection", "String"
        attribute "colorName", "String"
    }

    preferences() {
        section("MQTT Device Topics") {
            input name: "fullTopic", type: "text", title: "Topic to monitor", description: "For new Tasmota devices", required: true, defaultValue: "%prefix%/%topic%/"
            input name: "groupTopic", type: "text", title: "Group topic to use", description: "If set, will update devices", required: false
        }

        section("MQTT Broker") {
            input name: "mqttBroker", type: "text", title: "MQTT Broker Host/IP", description: "ex: tcp://hostnameorip:1883", required: true, defaultValue: "tcp://mqtt:1883"
            input name: "mqttUsername", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "mqttPassword", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }

        section("Misc") {
            input name: "schemeType", type: "enum", title: "Color Scheme Type", description: "For multiple lights", options: ["single", "monochrome", "monochrome-dark", "monochrome-light", "analogic", "complement", "analogic-complement", "triad", "quad"], required: true, defaultValue: "single"
            input name: "driverType", type: "enum", title: "MQTT Driver", description: "Driver for discovered devices", options: ["Tasmota Switch (MQTT)", "Tasmota Dimmer (MQTT)"], required: true, defaultValue: "Tasmota Switch (MQTT)"
            input name: "logEnable", type: "bool", title: "Enable Debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}

@Field List<Map> colorNames = getColorNames()

/**
 *  Hubitat Driver Event Handlers
 */

void configure() {
    // Update group topic for all devices
    if (settings.groupTopic) {
        def devices = getChildDevices().findAll { it.hasCommand("setGroupTopic") }
        devices.each {
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
    Map message = interfaces.mqtt.parseMessage(data)
    String topic = message.get("topic")
    String payload = message.get("payload")
    if (logEnable) log.debug "MQTT RECEIVE <--- ${topic} = ${payload}"
    state.mqttReceiveCount = (state?.mqttReceiveCount ?: 0) + 1
    if (payload.equalsIgnoreCase("Online"))
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

void stateChanged(child) {
    if (logEnable) log.debug "State change notification from ${device.displayName}"

    updateSwitch()
    updateSwitchLevel()
}

void updateSwitch() {
    // Update switch state (if all off then off)
    def devices = getChildDevices().findAll { it.hasCapability("Switch") }
    sendEvent(newEvent("switch", 
        devices.every { it.currentValue("switch") == "off" } ? "off" : "on"
    ))
}

void updateSwitchLevel () {
    // Update dimmer level to maximum value
    def devices = getChildDevices().findAll { it.hasCapability("SwitchLevel") }
    sendEvent(newEvent("level", 
        devices.collect { it.currentValue("level") }.max(), "%"
    ))
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
    configure()

    if (logEnable) runIn(1800, "logsOff")
}

/**
 *  Capability: Switch/Light
 */

// Turn on
void on() {
    def devices = getChildDevices().findAll { it.hasCommand("on") }
    devices.each { 
        log.info "Turning on ${it.displayName}"
        it.on()
    }
}

// Turn off
void off() {
    def devices = getChildDevices().findAll { it.hasCommand("off") }
    devices.each { 
        log.info "Turning off ${it.displayName}"
        it.off() 
    }
}

/**
 *  Capability: SwitchLevel
 */

// Set the brightness level and optional duration
void setLevel(level, duration = 0) {
    def devices = getChildDevices().findAll { it.hasCommand("setLevel") }
    devices.each { 
        log.info "Setting ${it.displayName} brightness to ${level}%"
        it.setLevel(level, duration)
    }
}

/**
 *  Capability: ColorControl
 */

// Set the HSB color [hue:(0-100), saturation:(0-100), level:(0-100)]
void setColor(colormap) {
    def devices = getChildDevices().findAll { it.hasCommand("setColor") }
    if (settings.schemeType == "single") {
        devices.each { 
            log.info "Setting ${it.displayName} color to ${colormap}"
            it.setColor(colormap)
        }
    } else {
        setColorScheme(colormap, devices.size())
    }
}

private void setColorScheme(colormap, count) {
    def rgb = hubitat.helper.ColorUtils.hsvToRGB([colormap.hue, colormap.saturation, colormap.level])
    def params = [
        uri: "http://thecolorapi.com",
        path: "/scheme",
        query: [
            "rgb": "${rgb[0]},${rgb[1]},${rgb[2]}",
            "mode": settings.schemeType,
            "count": count,
            "format": "json"            
        ],
        requestContentType: "application/json"
    ]
    if (logEnable) log.debug "Requesting color palette ${params.query}"
    asynchttpGet("colorApiHandler", params)
}

private void colorApiHandler(response, data) {
    if (response.status != 200) {
		log.error "thecolorapi.com returned HTTP status ${status}"
        return
    } else if (response?.error) {
        log.error "thecolorapi.com Json parsing error: ${json.error.code} ${json.error.message}"
        return
    }

    if (logEnable) log.debug "API returned: ${response.data}"
    def devices = getChildDevices().findAll { it.hasCommand("setColor") }
    response.json.colors.eachWithIndex { color, index ->
        Map colormap = [
            hue: color.hsv.h / 3.6,
            saturation: color.hsv.s,
            level: color.hsv.v
        ]
        log.info "Setting ${devices[index].label} to ${colormap}"
        devices[index].setColor(colormap)
    }
}

// Set the hue (0-100)
void setHue(hue) {
    def devices = getChildDevices().findAll { it.hasCommand("setHue") }
    devices.each { 
        log.info "Setting ${it.displayName} hue to ${hue}"
        it.setHue(hue)
    }
}

// Set the saturation (0-100)
void setSaturation(saturation) {
    def devices = getChildDevices().findAll { it.hasCommand("setSaturation") }
    devices.each { 
        log.info "Setting ${it.displayName} saturation to ${saturation}%"
        it.setSaturation(saturation)
    }
}

// Set the color temperature (2000-6536)
void setColorTemperature(kelvin) {
    def devices = getChildDevices().findAll { it.hasCommand("setColorTemperature") }
    devices.each { 
        log.info "Setting ${it.displayName} color temperature to ${kelvin}"
        it.setColorTemperature(kelvin)
    }
}

// Set the Tasmota fade speed
void setFadeSpeed(seconds) {
    def devices = getChildDevices().findAll { it.hasCommand("setFadeSpeed") }
    devices.each { 
        log.info "Setting ${it.displayName} fade speed to ${seconds} seconds"
        it.setFadeSpeed(seconds)
    }
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
 *  Child Device Management
 */

void createChildDevice(String topic) {
    String deviceNetworkId = generateMD5(topic)
    String deviceTopic = topic.split('/')[-2]

    def childDevice = getChildDevice(deviceNetworkId)
    if (childDevice) {
        if (logEnable) log.debug "Tasmota topic [ ${deviceTopic} ] is connected to [ ${childDevice.label ?: childDevice.name} ]"
        return
    }

    log.info "Creating new ${settings.driverType} for ${deviceTopic}"
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
    childDevice.updated()
}

String generateMD5(String s){
    String md5 = MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString()
    return [ md5[0..7], md5[8..11], md5[12..15], md5[16..19], md5[20..md5.length()-1] ].join('-')
}

/**
 *  Common Tasmota MQTT communication methods
 */

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

private boolean mqttConnect() {
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
    [name:"Yellow Green", rgb:"#9ACD32", hue:80, saturation:61, level:50],
]}
