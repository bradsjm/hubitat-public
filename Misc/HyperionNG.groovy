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
static final String deviceType() { "Hyperion-NG" }

import groovy.json.JsonBuilder
import groovy.transform.Field

metadata {
    definition (name: "${deviceType()}", namespace: "bradsjm", author: "Jonathan Bradshaw", importUrl: "") {
        capability "Actuator"
        capability "ColorControl"
        capability "Initialize"
        capability "Light"
        capability "LightEffects"
        capability "Switch"
        capability "SwitchLevel"

        attribute "deviceState", "String"
        
        preferences() {
            section("Connection") {
                input name: "hyperionHost", type: "text", title: "Hostname/IP", description: "", required: true, defaultValue: ""
                input name: "hyperionPort", type: "number", title: "Port", description: "", required: true, defaultValue: 19444
            }

            section("Misc") {
                input name: "priority", type: "number", title: "Priority", description: "", required: true, defaultValue: 50
                input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
            }
        }
    }
}

@Field static Map lightEffects = [
    0: "None",
    1: "Atomic swirl",
    2: "Blue mood blobs",
    3: "Breath",
    4: "Candle",
    5: "Cinema brighten lights",
    6: "Cinema dim lights",
    7: "Cold mood blobs",
    8: "Collision",
    9: "Color traces",
    10: "Double swirl",
    11: "Fire",
    12: "Flags Germany/Sweden",
    13: "Full color mood blobs",
    14: "Knight rider",
    15: "Led Test",
    16: "Light clock",
    17: "Lights",
    18: "Notify blue",
    19: "Pac-Man",
    20: "Police Lights Single",
    21: "Police Lights Solid",
    22: "Rainbow mood",
    23: "Rainbow swirl",
    24: "Rainbow swirl fast",
    25: "Random",
    26: "Red mood blobs",
    27: "Sea waves",
    28: "Snake",
    29: "Sparks",
    30: "Strobe red",
    31: "Strobe white",
    32: "System Shutdown",
    33: "Trails",
    34: "Trails color",
    35: "Warm mood blobs",
    36: "Waves with Color",
    37: "X-Mas"
]

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    sendEvent(name: "lightEffects", value: new JsonBuilder(lightEffects))
    log.info "${device.displayName} driver v${version()} initializing"
    unschedule()

    if (!settings.hyperionHost) {
        log.error "Unable to connect because Hyperion host setting not configured"
        return
    }

    connect()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver v${version()} installed"
}

// Called with socket status messages
void socketStatus(String status) {
    if (logEnable) log.debug status
}

void connected() {
    sendEvent(name: "deviceState", value: "online", descriptionText: "${device.displayName} connection opened by driver")
    log.info "Connected to Hyperion server at ${settings.hyperionHost}:${settings.hyperionPort}"
}

// Called to parse received socket data
def parse(data) {
    // rawSocket and socket interfaces return Hex encoded string data
    state.response += new String(hubitat.helper.HexUtils.hexStringToByteArray(data))
    if (state.response[-1] != "\n") return
    Map json = parseJson(state.response)
    if (logEnable) log.debug json
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver v${version()} uninstalled"
    disconnect()
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
	sendEvent(newEvent("switch", "on"))
    setColor([
        hue: device.currentValue("hue"), 
        saturation: device.currentValue("saturation"),
        level: device.currentValue("level")
    ])

    send([ 
        command: "sourceselect",
        priority: settings.priority
    ])
}

// Turn off
void off() {
    log.info "Switching ${device.displayName} off"
	sendEvent(newEvent("switch", "off"))
    send([ 
        command: "clear",
        priority: settings.priority
    ])
}

/**
 *  Capability: SwitchLevel
 */

// Set the brightness level and optional duration
void setLevel(level, duration = 0) {
    setColor([
        hue: device.currentValue("hue"), 
        saturation: device.currentValue("saturation"),
        level: level
    ])
}

/**
 *  Capability: ColorControl
 */

// Set the HSB color [hue:(0-100), saturation:(0-100), brightness level:(0-100)]
void setColor(colormap) {
    if (colormap.hue == null || colormap.saturation == null) return
    sendEvent(newEvent("hue", colormap.hue))
    sendEvent(newEvent("saturation", colormap.saturation))
    sendEvent(newEvent("level", colormap.level))

    def rgb = hubitat.helper.ColorUtils.hsvToRGB([colormap.hue, colormap.saturation, colormap.level])
    log.info "Setting ${device.displayName} color (RGB) to ${rgb}"
    send([
        origin: "Hubitat",
        priority: settings.priority,
        command: "color",
        //duration: 2,
        color: rgb
    ])
}

// Set the hue (0-100)
void setHue(hue) {
    setColor([
        hue: hue, 
        saturation: device.currentValue("saturation"),
        level: device.currentValue("level")
    ])
}

// Set the saturation (0-100)
void setSaturation(saturation) {
    setColor([
        hue: device.currentValue("hue"), 
        saturation: saturation,
        level: device.currentValue("level")
    ])
}

/**
 *  Capability: Light Effects
 */
void setEffect(id) {
    String effectName = lightEffects[id as int]
    log.info "Setting ${device.displayName} to effects scheme ${id} ${effectName}"
    sendEvent(newEvent("effectName", effectName))

    if (id == 0) {
        send([ 
            command: "clear",
            priority: settings.priority
        ])
    } else {
        send([
            origin: "Hubitat",
            priority: settings.priority,
            command: "effect",
            effect: [
                name: effectName
            ]
        ])
    }
    
    state.crntEffectId = id as int
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

private boolean connect() {
    unschedule("connect")
    try {
        def hub = device.getHub()
        def socket = interfaces.rawSocket
        log.info "Connecting to Hyperion server at ${settings.hyperionHost}:${settings.hyperionPort}"
        state.connectCount = (state?.connectCount ?: 0) + 1
        socket.connect(
            settings.hyperionHost,
            settings.hyperionPort as int,
        )
        connected()
        return true
    } catch(e) {
        log.error "connect error: ${e}"
        runInMillis(new Random(now()).nextInt(90000), "connect")
    }

    return false
}

private void disconnect() {
    log.info "Disconnecting from ${settings?.hyperionHost}"
    sendEvent(name: "deviceState", value: "offline", descriptionText: "${device.displayName} connection closed by driver")

    try {
        interfaces.rawSocket.close()
    }
    catch (any)
    {
        // Ignore any exceptions since we are disconnecting
    }
}

private void send(def output) {
    try {
        String json = new JsonBuilder(output)
        if (logEnable) log.debug json
        state.response = ""
        interfaces.rawSocket.sendMessage(json.plus("\n"))    
    } catch(e) {
        log.error "send error: ${e}"
        runInMillis(new Random(now()).nextInt(90000), "connect")
    }
}
