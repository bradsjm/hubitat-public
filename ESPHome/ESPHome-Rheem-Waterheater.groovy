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
        name: 'ESPHome Rheem Water Heater',
        namespace: 'esphome',
        author: 'Kris Linquist',
        singleThreaded: true,
        importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/ESPHome/ESPHome-Rheem-Waterheater.groovy') {

        capability 'Refresh'
        capability 'Initialize'
        capability 'SignalStrength'
        capability 'TemperatureMeasurement'
        capability 'ThermostatHeatingSetpoint'
        capability 'ThermostatOperatingState'
        
        command 'setWaterHeaterMode', [[name:'Mode*','type':'ENUM','description':'Mode','constraints':['Heat Pump', 'Energy Saver', 'High Demand', 'Normal', 'Vacation', 'Off']]]

        attribute 'networkStatus', 'enum', [ 'connecting', 'online', 'offline' ]
        attribute 'waterHeaterMode', 'enum', ['Heat Pump', 'Energy Saver', 'High Demand', 'Normal', 'Vacation', 'Off']
        attribute 'lowerTankTemperature', 'number'
        attribute 'upperTankTemperature', 'number'
        attribute 'thermostatHeatingSetpoint', 'number'

        attribute 'powerWatts', 'number'
        attribute 'hotWaterAvailabilityPercent', 'number'

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

import com.hubitat.app.ChildDeviceWrapper

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

// driver commands
public void on() {
    if (device.currentValue('switch') != 'on') {
        if (logTextEnable) { log.info "${device} on" }
        espHomeSwitchCommand(key: settings.switch as Long, state: true)
    }
}

public void off() {
    if (device.currentValue('switch') != 'off') {
        if (logTextEnable) { log.info "${device} off" }
        espHomeSwitchCommand(key: settings.switch as Long, state: false)
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
            
            if (message.platform == 'binary' && !message.disabledByDefault && message.entityCategory == 'none') {

            }

            if (message.platform == 'sensor') {
                switch (message.deviceClass) {
                    case 'signal_strength':
                        state['signalStrength'] = message.key
                        break
                }
            }
            break

        case 'state':
            
            // Signal Strength
            if (state.signalStrength as Long == message.key && message.hasState) {
                Integer rssi = Math.round(message.state as Float)
                String unit = 'dBm'
                if (device.currentValue('rssi') != rssi) {
                    descriptionText = "${device} rssi is ${rssi}"
                    sendEvent(name: 'rssi', value: rssi, unit: unit, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }
                return
            }


            

            if (message.platform == 'climate') {
                
                if (message.targetTemperature) {
                    Double temperatureF = Math.round((message.targetTemperature.toDouble() * 1.8 + 32) * 10) / 10.0
                    if (device.currentValue('thermostatHeatingSetpoint') != temperatureF) {
                        updateAttribute('thermostatHeatingSetpoint', temperatureF, 'F')
                    }
                }

                if (message.temperature) {                    
                    Double temperatureF = Math.round((message.temperature.toDouble() * 1.8 + 32) * 10) / 10.0
                    if (device.currentValue('upperTankTemperature') != temperatureF) {
                        updateAttribute('upperTankTemperature', temperatureF, 'F')
                    }
                }

                if (message.customMode) {                    
                    if (message.customMode == 'Eco Mode'){
                        message.customMode = 'Energy Saver'
                    }
                    if (device.currentValue('waterHeaterMode') != message.customMode) {
                        updateAttribute('waterHeaterMode', message.customMode)
                    }
                }

                return
            }

    }
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
    //if (device.currentValue(attribute) != value && settings.txtEnable) {
    log.info descriptionText
    //}
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}



// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper
