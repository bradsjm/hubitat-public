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
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.exception.UnknownDeviceTypeException

metadata {
    definition(name: 'ESPHome Garage Door Opener', namespace: 'esphome', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'Refresh'

        command 'connect'
        command 'disconnect'
    }

    preferences {
        input name: 'ipAddress',
                type: 'text',
                title: 'Device IP Address',
                required: true

        input name: 'portNumber',
                type: 'number',
                title: 'Port Number',
                range: 1..65535,
                defaultValue: '6053',
                required: true

        input name: 'password',
                type: 'text',
                title: 'Device Password (if required)',
                required: false

        input name: 'pingInterval',
                type: 'enum',
                title: 'Device Ping Interval',
                required: true,
                defaultValue: 30,
                options: [
                    15: '15 Seconds',
                    30: '30 Seconds',
                    60: 'Every minute',
                    120: 'Every 2 minutes'
                ]

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

public void disconnect() {
    espDisconnectRequest()
    runIn(1, 'closeSocket')
}

// Called when the device is started.
public void initialize() {
    log.info "${device} driver initializing"

    unschedule()        // Remove all scheduled functions
    disconnect()        // Disconnect any existing connection

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

public void parse(Map message) {
    log.info "ESPHome received: ${message}"
}

public void refresh() {
    log.info 'refreshing device entities'
    espListEntitiesRequest()
}

// Called when the device is removed.
void uninstalled() {
    disconnect()
    log.info "${device} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

#include esphome.espHomeApi
