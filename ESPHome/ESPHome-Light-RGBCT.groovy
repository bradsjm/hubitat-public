/**
 *  MIT License
 *  Copyright 2022 Jonathan Bradshaw (jb@nrgup.net)
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
metadata {
    definition(name: 'ESPHome RGBCT Light', namespace: 'esphome', author: 'Jonathan Bradshaw') {
        singleThreaded: true

        capability 'Actuator'
        capability 'Bulb'
        capability 'ColorControl'
        capability 'ColorMode'
        capability 'ColorTemperature'
        capability 'Flash'
        capability 'LevelPreset'
        capability 'Light'
        capability 'LightEffects'
        capability 'SignalStrength'
        capability 'Switch'
        capability 'SwitchLevel'
        capability 'Initialize'

        // attribute populated by ESPHome API Library automatically
        attribute 'networkStatus', 'enum', [ 'connecting', 'online', 'offline' ]
    }

    preferences {
        input name: 'ipAddress',    // required setting for API library
                type: 'text',
                title: 'Device IP Address',
                required: true

        input name: 'password',     // optional setting for API library
                type: 'text',
                title: 'Device Password <i>(if required)</i>',
                required: false

        input name: 'light',       // allows the user to select which entity to use
            type: 'enum',
            title: 'ESPHome Entity',
            required: state.entities?.size() > 0,
            options: state.entities?.collectEntries { k, v -> [ k, v.name ] }
            defaultValue: state.entities ? state.entities.keySet()[0] : '' // default to first

        input name: 'logEnable',    // if enabled the library will log debug details
                type: 'bool',
                title: 'Enable Debug Logging',
                required: false,
                defaultValue: false

        input name: 'logTextEnable',
              type: 'bool',
              title: 'Enable descriptionText logging',
              required: false,
              defaultValue: true
    }
}

import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.helper.ColorUtils

public void initialize() {
    state.clear()

    // API library command to open socket to device, it will automatically reconnect if needed 
    openSocket()

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

public void installed() {
    log.info "${device} driver installed"
}

public void logsOff() {
    espHomeSubscribeLogs(LOG_LEVEL_INFO, false) // disable device logging
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

public void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

public void uninstalled() {
    closeSocket('driver uninstalled') // make sure the socket is closed when uninstalling
    log.info "${device} driver uninstalled"
}

// driver commands
public void flash(BigDecimal rate = 1) {
    if (logTextEnable) { log.info "${device} flash (${rate})" }
    espHomeLightCommand(
        key: settings.light as Long,
        brightness: 1f,
        flashLength: rate * 1000,
        red: 1f,
        green: 0f,
        blue: 0f
    )
}

public void on() {
    if (logTextEnable) { log.info "${device} on" }
    espHomeLightCommand(key: settings.light as Long, state: true)
}

public void off() {
    if (logTextEnable) { log.info "${device} off" }
    espHomeLightCommand(key: settings.light as Long, state: false)
}

public void presetLevel(BigDecimal level) {
    String descriptionText = "${device} preset level ${level}%"
    if (logTextEnable) { log.info descriptionText }
    sendEvent(name: 'levelPreset', value: level, unit: '%', descriptionText: descriptionText)
    espHomeLightCommand(
        key: settings.light as Long,
        masterBrightness: level / 100f
    )
}

public void setColor(Map colorMap) {
    if (logTextEnable) { log.info "${device} set color ${colorMap}" }
    def (int r, int g, int b) = ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level])
    espHomeLightCommand(
        key: settings.light as Long,
        red: r / 255f,
        green: g / 255f,
        blue: b / 255f,
        masterBrightness: colorMap.level / 100f,
        colorBrightness: 1f // use the master brightness
    )
}

public void setColorTemperature(BigDecimal colorTemperature, BigDecimal level = null, BigDecimal duration = null) {
    if (logTextEnable) { log.info "${device} set color temperature ${colorTemperature}" }
    float mireds = 1000000f / colorTemperature
    espHomeLightCommand(
        key: settings.light as Long,
        colorTemperature: mireds,
        masterBrightness: level != null ? level / 100f : null,
        transitionLength: duration != null ? duration * 1000 : null
    )
}

public void setHue(BigDecimal hue) {
    BigDecimal saturation = device.currentValue('saturation')
    BigDecimal level = device.currentValue('level')
    if (hue != null && saturation != null && level != null) {
        setColor([ hue: hue, saturation: saturation, level: level ])
    }
}

public void setLevel(BigDecimal level, BigDecimal duration = null) {
    if (logTextEnable) { log.info "${device} set level to ${level}%" }
    espHomeLightCommand(
        key: settings.light as Long,
        state: level > 0,
        masterBrightness: level > 0 ? level / 100f : null,
        transitionLength: duration != null ? duration * 1000 : null
    )
}

public void setSaturation(BigDecimal saturation) {
    BigDecimal hue = device.currentValue('hue')
    BigDecimal level = device.currentValue('level')
    if (hue != null && saturation != null && level != null) {
        setColor([ hue: hue, saturation: saturation, level: level ])
    }
}

public void setEffect(BigDecimal number) {
    if (state.entities && settings.light) {
        List<String> effects = state.entities[settings.light].effects
        if (number < 1) { number = effects.size() }
        if (number > effects.size()) { number = 1 }
        int index = number - 1
        if (logTextEnable) { log.info "${device} set effect ${effects[index]}" }
        espHomeLightCommand(key: settings.light as Long, effect: effects[index])
    }
}

public void setNextEffect() {
    if (state.entities && settings.light) {
        String current = device.currentValue('effectName')
        int index = state.entities[settings.light].effects.indexOf(current) + 1
        setEffect(index + 1)
    }
}

public void setPreviousEffect() {
    if (state.entities && settings.light) {
        String current = device.currentValue('effectName')
        int index = state.entities[settings.light].effects.indexOf(current) + 1
        setEffect(index - 1)
    }
}

// the parse method is invoked by the API library when messages are received
public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'device':
            // Device information
            break

        case 'entity':
            // This will populate the cover dropdown with all the entities
            // discovered and the entity key which is required when sending commands
            if (message.platform == 'light') {
                state.entities = (state.entities ?: [:]) + [ (message.key): message ]
            }

            if (message.platform == 'sensor' && message.deviceClass == 'signal_strength') {
                state['signalStrength'] = message.key
            }

            if (message.key == settings.light as Integer) {
                String effects = JsonOutput.toJson(message.effects ?: [])
                if (device.currentValue('lightEffects') != effects) {
                    sendEvent(name: 'lightEffects', value: effects)
                }
            }
            break

        case 'state':
            // Check if the entity key matches the message entity key received to update device state
            if (settings.light as Long == message.key) {
                String descriptionText

                String state = message.state ? 'on' : 'off'
                if (device.currentValue('switch') != state) {
                    descriptionText = "${device} was turned ${state}"
                    sendEvent(name: 'switch', value: state, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }

                int level = message.state ? Math.round(message.masterBrightness * 100f) : 0
                if (device.currentValue('level') != level) {
                    descriptionText = "${device} level was set to ${level}"
                    sendEvent(name: 'level', value: level, unit: '%', descriptionText: descriptionText)
                    if (message.state) {
                        sendEvent(name: 'levelPreset', value: level, unit: '%', descriptionText: descriptionText)
                    }
                    if (logTextEnable) { log.info descriptionText }
                }

                def (int h, int s, int b) = ColorUtils.rgbToHSV([message.red * 255f, message.green * 255f, message.blue * 255f])
                String colorName = hsToColorName(h, s)
                if (device.currentValue('colorName') != colorName) {
                    sendEvent name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}"
                }
                if (device.currentValue('hue') != h) {
                    sendEvent name: 'hue', value: h, descriptionText: "hue is ${h}"
                }
                if (device.currentValue('saturation') != s) {
                    sendEvent name: 'saturation', value: s, descriptionText: "saturation is ${s}"
                }

                int colorTemperature = Math.round(1000000f / message.colorTemperature)
                if (device.currentValue('colorTemperature') != colorTemperature) {
                    descriptionText = "${device} color temperature was set to ${colorTemperature}"
                    sendEvent(name: 'colorTemperature', value: colorTemperature, unit: '°K', descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }

                String effectName = message.effect
                if (device.currentValue('effectName') != effectName) {
                    descriptionText = "${device} effect name is ${effectName}"
                    sendEvent(name: 'effectName', value: effectName, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }

                String colorMode = (message.colorMode & COLOR_CAP_RGB) == COLOR_CAP_RGB ? 'RGB' : 'CT'
                if (message.effect && message.effect != 'None') { colorMode = 'EFFECTS' }
                if (device.currentValue('colorMode') != colorMode) {
                    descriptionText = "${device} color mode is ${colorMode}"
                    sendEvent(name: 'colorMode', value: colorMode, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }
            }

            if (state.signalStrength as Long == message.key && message.hasState) {
                Integer rssi = Math.round(message.state as Float)
                String unit = 'dBm'
                if (device.currentValue('rssi') != rssi) {
                    descriptionText = "${device} rssi is ${rssi}"
                    sendEvent(name: 'rssi', value: rssi, unit: unit, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }
            }
            break
    }
}

private static String hsToColorName(BigDecimal hue, BigDecimal saturation) {
    switch (hue * 3.6 as Integer) {
        case 0..15: return 'Red'
        case 16..45: return 'Orange'
        case 46..75: return 'Yellow'
        case 76..105: return 'Chartreuse'
        case 106..135: return 'Green'
        case 136..165: return 'Spring'
        case 166..195: return 'Cyan'
        case 196..225: return 'Azure'
        case 226..255: return 'Blue'
        case 256..285: return 'Violet'
        case 286..315: return 'Magenta'
        case 316..345: return 'Rose'
        case 346..360: return 'Red'
    }

    return ''
}

@Field private static Map colorTempName = [
    2001: "Sodium",
    2101: "Starlight",
    2400: "Sunrise",
    2800: "Incandescent",
    3300: "Soft White",
    3500: "Warm White",
    4150: "Moonlight",
    5001: "Horizon",
    5500: "Daylight",
    6000: "Electronic",
    6501: "Skylight",
    20000: "Polar"
]

// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper
