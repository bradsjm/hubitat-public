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
        capability 'Lock'
        capability 'Refresh'

        command 'connect'
        command 'disconnect'
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

/**
 * Hubitat Driver Implementation
 */
public void connect() {
    log.info "connecting to ESPHome native API at ${ipAddress}:${portNumber}"
    closeSocket()
    openSocket()
}

// Close door
public void close() {
    if (device.currentValue('lock') != 'locked') {
        String doorState = device.currentValue('door')
        if (logEnable) { log.debug "Current door state is ${doorState}" }
        if (doorState != 'open' || doorState == 'closing') {
            log.info "${device.displayName} ignoring close request (door is ${doorState})"
            return
        }
        espCoverCommandRequest(state.key, 0.0)
        publishState('closing')
    } else {
        log.warn "${device.displayName} is locked, cannot close"
    }
}

public void disconnect() {
    espDisconnectRequest()
    runIn(1, 'closeSocket')
}

// Called when the device is started.
public void initialize() {
    log.info "${device} driver initializing"

    unschedule()        // Remove all scheduled functions
    disconnect()        // Disconnect any existing connection
    unlock()            // set state to enabled

    // Schedule log disable for 30 minutes
    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

// Called when the device is first created.
public void installed() {
    log.info "${device} driver installed"
}

// Called to disable logging after timeout
public void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    log.info "${device} debug logging disabled"
}

// Open door
public void open() {
    if (device.currentValue('lock') != 'locked') {
        String doorState = device.currentValue('door')
        if (logEnable) { log.debug "Current door state is ${doorState}" }
        if (doorState != 'closed' || doorState == 'opening') {
            log.info "${device.displayName} ignoring open request (door is ${doorState})"
            return
        }
        espCoverCommandRequest(state.key, 1.0)
        publishState('opening')
    } else {
        log.warn "${device.displayName} is locked, cannot open"
    }
}

public void lock() {
    sendEvent([ name: 'lock', value: 'locked' ])
}

// parse the JSON message from the ESPHome API
public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }
    if (message.key && message.deviceClass == 'garage') {
        state.key = message.key
    } else if (state.key && message.key == state.key) {
        if (message.position == 0.0) {
            transitionState('closed')
        } else if (message.position == 1.0) {
            transitionState('open')
        }
    }
}

public void refresh() {
    log.info 'refreshing device entities'
    espListEntitiesRequest()
}

// Called when the device is removed.
public void uninstalled() {
    disconnect()
    log.info "${device} driver uninstalled"
}

public void unlock() {
    sendEvent([ name: 'lock', value: 'unlocked' ])
}

// Called when the settings are updated.
public void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}


private void transitionState(String newState) {
    String doorState = device.currentValue('door')
    if (newState != doorState) {
        if (logEnable) { log.debug "Current door state is ${doorState}" }
        switch (doorState) {
            case 'opening':
                if (newState == 'open') {
                    publishState(newState)
                } else {
                    runIn(20, 'publishState', [ data: newState ])
                }
                break
            case 'closing':
                if (newState == 'closed') {
                    publishState(newState)
                } else {
                    runIn(20, 'publishState', [ data: newState ])
                }
                break
            default:
                publishState(newState)
        }
    }
}

private void publishState(String value) {
    unschedule('publishState')
    String doorState = device.currentValue('door')
    if (logEnable) { log.debug "Update door state from ${doorState} to ${value}" }
    sendEvent([name: 'door', value: value, descriptionText: settings.logTextEnable ? "Door is ${value}" : ''])
}

#include esphome.espHomeApi
