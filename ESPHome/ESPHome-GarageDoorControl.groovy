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
    definition(name: 'ESPHome Garage Door Control', namespace: 'esphome', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'GarageDoorControl'
        capability 'Initialize'
    }

    preferences {
        input name: 'ipAddress',
                type: 'text',
                title: 'Device IP Address',
                required: true

        input name: 'password',
                type: 'text',
                title: 'Device Password (if required)',
                required: false

        input name: 'logEnable',
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

public void close() {
    String doorState = device.currentValue('door')
    if (doorState != 'open' || doorState == 'closing') {
        log.info "${device.displayName} ignoring close request (door is ${doorState})"
        return
    }
    espCoverCommandRequest(state.key, 0.0)
    publishState('closing')
}

public void initialize() {
    openSocket()

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

public void installed() {
    log.info "${device} driver installed"
}

public void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    log.info "${device} debug logging disabled"
}

public void open() {
    String doorState = device.currentValue('door')
    if (doorState != 'closed' || doorState == 'opening') {
        log.info "${device.displayName} ignoring open request (door is ${doorState})"
        return
    }
    espCoverCommandRequest(state.key, 1.0)
    publishState('opening')
}

public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }
    if (message.key && message.deviceClass == 'garage') {
        state.key = message.key
    } else if (state.key && message.key == state.key) {
        if (message.position == 0.0) {
            sendEvent([name: 'door', value: 'closed', descriptionText: settings.logTextEnable ? "Garage Door is closed" : ''])
        } else if (message.position == 1.0) {
            sendEvent([name: 'door', value: 'open', descriptionText: settings.logTextEnable ? "Garage Door is open" : ''])
        }
    }
}

public void uninstalled() {
    closeSocket()
    log.info "${device} driver uninstalled"
}

public void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

#include esphome.espHomeApi
