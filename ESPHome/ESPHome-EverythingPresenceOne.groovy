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
    definition(
        name: 'ESPHome Everything Presence One',
        namespace: 'esphome',
        author: 'Jonathan Bradshaw',
        singleThreaded: true,
        importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/ESPHome/ESPHome-EverythingPresenceOnce.groovy') {

        capability 'IlluminanceMeasurement'
        capability 'MotionSensor'
        capability 'Sensor'
        capability 'Refresh'
        capability 'TemperatureMeasurement'
        capability 'Initialize'

        attribute 'occupancy', 'enum', [ 'occupied', 'not occupied' ]

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

        input name: 'temperature', // allows the user to select which sensor entity to use
            type: 'enum',
            title: 'ESPHome Temperature Entity',
            required: state.sensors?.size() > 0,
            options: state.sensors?.collectEntries { k, v -> [ k, v.name ] }

        input name: 'illuminance', // allows the user to select which sensor entity to use
            type: 'enum',
            title: 'ESPHome Illuminance Entity',
            required: state.sensors?.size() > 0,
            options: state.sensors?.collectEntries { k, v -> [ k, v.name ] }

        input name: 'motion', // allows the user to select which sensor entity to use
            type: 'enum',
            title: 'ESPHome Motion Entity',
            required: state.sensors?.size() > 0,
            options: state.sensors?.collectEntries { k, v -> [ k, v.name ] }

        input name: 'occupancy', // allows the user to select which sensor entity to use
            type: 'enum',
            title: 'ESPHome Occupancy Entity',
            required: state.sensors?.size() > 0,
            options: state.sensors?.collectEntries { k, v -> [ k, v.name ] }

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

public void initialize() {
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

public void refresh() {
    log.info "${device} refresh"
    state.clear()
    state.requireRefresh = true
    espHomeDeviceInfoRequest()
}

public void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

public void uninstalled() {
    closeSocket('driver uninstalled') // make sure the socket is closed when uninstalling
    log.info "${device} driver uninstalled"
}

// the parse method is invoked by the API library when messages are received
public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'device':
            // Device information
            break

        case 'entity':
            if (message.platform in ['sensor', 'binary']) {
                state.sensors = (state.sensors ?: [:]) + [ (message.key): message ]
            }
            break

        case 'state':
            // Check if the entity key matches the message entity key received to update device state
            if (settings.temperature as Long == message.key && message.hasState) {
                String value = message.state
                if (device.currentValue('temperature') != value) {
                    sendEvent([
                        name: 'temperature',
                        value: value,
                        descriptionText: "Temperature is ${value}"
                    ])
                }
                return
            }

            if (settings.illuminance as Long == message.key && message.hasState) {
                String value = message.state
                if (device.currentValue('illuminance') != value) {
                    sendEvent([
                        name: 'illuminance',
                        value: value,
                        unit: 'lx',
                        descriptionText: "Illuminance is ${value}"
                    ])
                }
                return
            }

            if (settings.occupancy as Long == message.key && message.hasState) {
                String value = message.state ? 'occupied' : 'not occupied'
                if (device.currentValue('occupancy') != value) {
                    sendEvent([
                        name: 'occupancy',
                        value: value,
                        descriptionText: "Occupancy is ${value}"
                    ])
                }
                return
            }

            if (settings.motion as Long == message.key && message.hasState) {
                String value = message.state ? 'active' : 'inactive'
                if (device.currentValue('motion') != value) {
                    sendEvent([
                        name: 'motion',
                        value: value,
                        descriptionText: "Motion is ${value}"
                    ])
                }
                return
            }
            break
    }
}

// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper
