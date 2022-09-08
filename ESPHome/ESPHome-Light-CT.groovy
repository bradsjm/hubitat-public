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
    definition(name: 'ESPHome CT Light', namespace: 'esphome', author: 'Jonathan Bradshaw') {
        singleThreaded: true

        capability 'Actuator'
        capability 'Bulb'
        capability 'ColorTemperature'
        capability 'Flash'
        capability 'LevelPreset'
        capability 'Light'
        capability 'LightEffects'
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
    espSubscribeLogsRequest(LOG_LEVEL_INFO, false) // disable device logging
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

public void updated() {
    log.info "${device} driver configuration updated"

    if (state.entities && settings.light) {
        List<String> effects = state.entities[settings.light].effects
        sendEvent(name: 'lightEffects', value: JsonOutput.toJson(effects))
    }
    initialize()
}

public void uninstalled() {
    closeSocket('uninstalling') // make sure the socket is closed when uninstalling
    log.info "${device} driver uninstalled"
}

// driver commands
public void flash(BigDecimal rate) {
    if (device.currentValue('networkStatus') == 'online') {
        if (logTextEnable) { log.info "${device} flash (${rate})" }
        espHomeLightCommand(
            key: settings.light as int,
            brightness: 1f,
            flashLength: rate * 1000,
            red: 1f,
            green: 0f,
            blue: 0f
        )
    } else {
        log.error "${device} unable to flash, device not online"
    }
}

public void on() {
    if (device.currentValue('networkStatus') == 'online') {
        if (logTextEnable) { log.info "${device} on" }
        espHomeLightCommand(key: settings.light as int, state: true)
    } else {
        log.error "${device} unable to turn on, device not online"
    }
}

public void off() {
    if (device.currentValue('networkStatus') == 'online') {
        if (logTextEnable) { log.info "${device} off" }
        // API library cover command, entity key is required
        espHomeLightCommand(key: settings.light as int, state: false)
    } else {
        log.error "${device} unable to turn off, device not online"
    }
}

public void presetLevel(BigDecimal level) {
    if (device.currentValue('networkStatus') == 'online') {
        String descriptionText = "${device} preset level ${level}%"
        if (logTextEnable) { log.info descriptionText }
        sendEvent(name: 'levelPreset', value: level, unit: '%', descriptionText: descriptionText)
        espHomeLightCommand(
            key: settings.light as int,
            masterBrightness: level / 100f
        )
    } else {
        log.error "${device} unable to set level, device not online"
    }
}

public void setColorTemperature(BigDecimal colorTemperature, BigDecimal level = null, BigDecimal duration = null) {
    if (device.currentValue('networkStatus') == 'online') {
        if (logTextEnable) { log.info "${device} set color temperature ${colorTemperature}" }
        float mireds = 1000000f / colorTemperature
        espHomeLightCommand(
            key: settings.light as int,
            colorTemperature: mireds,
            masterBrightness: level != null ? level / 100f : null,
            transitionLength: duration != null ? duration * 1000 : null
        )
    } else {
        log.error "${device} unable to set color temperature, device not online"
    }
}

public void setEffect(BigDecimal number) {
    if (state.entities && settings.light) {
        List<String> effects = state.entities[settings.light].effects
        if (number < 1) { number = effects.size() }
        if (number > effects.size()) { number = 1 }
        int index = number - 1
        if (logTextEnable) { log.info "${device} set effect ${effects[index]}" }
        espHomeLightCommand(key: settings.light as int, effect: effects[index])
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

public void setLevel(BigDecimal level, BigDecimal duration = null) {
    if (device.currentValue('networkStatus') == 'online') {
        if (logTextEnable) { log.info "${device} set level to ${level}%" }
        espHomeLightCommand(
            key: settings.light as int,
            state: level > 0,
            masterBrightness: level > 0 ? level / 100f : null,
            transitionLength: duration != null ? duration * 1000 : null
        )
    } else {
        log.error "${device} unable to set level, device not online"
    }
}

// the parse method is invoked by the API library when messages are received
public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'device':
            // Device information
            log.info message
            break

        case 'entity':
            // This will populate the cover dropdown with all the entities
            // discovered and the entity key which is required when sending commands
            if (message.platform == 'light') {
                state.entities = (state.entities ?: [:]) + [ (message.key): message ]
            }
            break

        case 'state':
            // Check if the entity key matches the message entity key received to update device state
            if (settings.light as Integer == message.key) {
                String descriptionText

                String state = message.state ? 'on' : 'off'
                if (device.currentValue("switch") != state) {
                    descriptionText = "${device} was turned ${state}"
                    sendEvent(name: 'switch', value: state, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }

                int level = message.state ? Math.round(message.masterBrightness * 100f) : 0
                if (device.currentValue("level") != level) {
                    descriptionText = "${device} level was set to ${level}"
                    sendEvent(name: 'level', value: level, unit: '%', descriptionText: descriptionText)
                    if (message.state) {
                        sendEvent(name: 'levelPreset', value: level, unit: '%', descriptionText: descriptionText)
                    }
                    if (logTextEnable) { log.info descriptionText }
                }

                int colorTemperature = Math.round(1000000f / message.colorTemperature)
                if (device.currentValue("colorTemperature") != colorTemperature) {
                    descriptionText = "${device} color temperature was set to ${colorTemperature}"
                    sendEvent(name: 'colorTemperature', value: colorTemperature, unit: '°K', descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }

                String colorName = colorTempName.find { k, v -> colorTemperature < k }.value
                if (device.currentValue("colorName") != colorName) {
                    descriptionText = "${device} color name is ${colorName}"
                    sendEvent(name: 'colorName', value: colorName, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }

                String effectName = message.effect
                if (device.currentValue("effectName") != effectName) {
                    descriptionText = "${device} effect name is ${effectName}"
                    sendEvent(name: 'effectName', value: effectName, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }
            }
            break
    }
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
