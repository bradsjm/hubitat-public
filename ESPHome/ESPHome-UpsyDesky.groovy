/**
 *  MIT License
 *  Copyright 2023 Jonathan Bradshaw (jb@nrgup.net)
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
    definition(
        name: 'Upsy Desky',
        namespace: 'esphome',
        author: 'Jonathan Bradshaw',
        singleThreaded: true,
        importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/ESPHome/ESPHome-UpsyDesky.groovy') {

        capability 'Actuator'
        capability 'Sensor'
        capability 'Refresh'
        capability 'Initialize'
        capability 'PushableButton'
        capability 'Switch'
        capability 'HoldableButton'

        command 'redetectDecoder'
        command 'restart'
        command 'setPosition', [ [ name: 'Height*', type: 'NUMBER' ] ]

        attribute 'position', 'number'

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

import java.math.RoundingMode

public void initialize() {
    // API library command to open socket to device, it will automatically reconnect if needed
    openSocket()

    // set the number of preset buttons
    updateAttribute('numberOfButtons', 4)

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
public void refresh() {
    log.info "${device} refresh"
    state.clear()
    state.requireRefresh = true
    espHomeDeviceInfoRequest()
}

public void redetectDecoder() {
    log.info "${device} redetectEncoder"
    Long key = state.entities['re-detect_decoder']
    if (!key) {
        log.warn "${device} redetectEncoder not found"
        return
    }
    espHomeButtonCommand([key: key])
}

public void restart() {
    log.info "${device} restart"
    Long key = state.entities['restart']
    if (!key) {
        log.warn "${device} restart not found"
        return
    }
    espHomeButtonCommand([key: key])
}

// the parse method is invoked by the API library when messages are received
public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'device':
            // Device information
            break

        case 'entity':
            // Persist Entity objectId mapping to keys
            if (state.entities == null) {
                state.entities = [:]
            }
            state.entities[message.objectId] = message.key
            break

        case 'state':
            if (message.key) {
                String objectId = state.entities.find { e -> e.value as Long == message.key as Long }?.key
                if (!objectId) {
                    log.warn "ESPHome: Unknown entity key: ${message}"
                    return
                }
                switch (objectId) {
                    case 'desk_height':
                        if (message.hasState) {
                            BigDecimal value = (message.state as BigDecimal).setScale(1, RoundingMode.HALF_UP)
                            updateAttribute('position', value)
                        }
                        break   
                    case 'status_led':
                        updateAttribute('switch', message.state ? 'on' : 'off')
                        break
                }
            }
            break
    }
}

// set preset position
public void hold(BigDecimal number) {
    log.info "${device} hold: ${number}"
    Long key = state.entities["set_preset_${number}"]
    if (!key) {
        log.warn "${device} invalid preset number: ${number}"
        return
    }
    espHomeButtonCommand([key: key])
}

// activate preset position
public void push(BigDecimal number) {
    log.info "${device} push: ${number}"
    Long key = state.entities["preset_${number}"]
    if (!key) {
        log.warn "${device} invalid preset number: ${number}"
        return
    }
    espHomeButtonCommand([key: key])
}

public void setPosition(BigDecimal position) {
    log.info "${device} setPosition: ${position}"
    Long key = state.entities["target_desk_height"]
    if (!key) {
        return
    }
    espHomeNumberCommand([key: key, state: position])
}

public void on() {
    log.info "${device} LED on"
    Long key = state.entities['status_led']
    if (!key) {
        return
    }
    espHomeLightCommand([key: key, state: true])
}

public void off() {
    log.info "${device} LED off"
    Long key = state.entities['status_led']
    if (!key) {
        return
    }
    espHomeLightCommand([key: key, state: false])
}

/**
 * Update the specified device attribute with the specified value and log if changed
 * @param attribute name of the attribute
 * @param value value of the attribute
 * @param unit unit of the attribute
 * @param type type of the attribute
 */
private void updateAttribute(final String attribute, final Object value, final String unit = null, final String type = null) {
    final String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}

// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper
