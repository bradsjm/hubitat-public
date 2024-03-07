/**
 *  MIT License
 *  Copyright 2024 Kris Linquist (kris@linquist.net)
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
        capability 'Switch'
        capability 'TemperatureMeasurement'
        capability 'ThermostatHeatingSetpoint'
        capability 'ThermostatOperatingState'
        
        command 'setWaterHeaterMode', [[name:'Mode*','type':'ENUM','description':'Mode','constraints':['Heat Pump', 'Energy Saver', 'High Demand', 'Electric/Gas', 'Off']]]
        command 'setVacationMode', [[name:'VacationMode*','type':'ENUM','description':'VacationMode','constraints':['Off', 'Permanent']]]

        attribute 'networkStatus', 'enum', [ 'connecting', 'online', 'offline' ]
        attribute 'waterHeaterMode', 'enum', ['Heat Pump', 'Energy Saver', 'High Demand', 'Electric/Gas', 'Off']
        attribute 'lowerTankTemperature', 'number'
        attribute 'upperTankTemperature', 'number'
        attribute 'powerWatts', 'number'
        attribute 'lowerHeatingElementRuntime', 'number'
        attribute 'upperHeatingElementRuntime', 'number'
        attribute 'ambientTemperature', 'number'
        attribute 'vacationMode', 'enum', ['Off', 'Timed', 'Permanent']
        attribute 'heatingElementState', 'enum', ['Off', 'On']
        attribute 'compressorState', 'string'
        attribute 'thermostatHeatingSetpoint', 'number'

        
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
    setWaterHeaterMode('Eco Mode')
}

public void off() {
    setWaterHeaterMode('Off')
}

public void setWaterHeaterMode(String value) {
    if (value == 'Energy Saver'){
        value = 'Eco Mode'
    }
    if (logTextEnable) { log.info "${device} setWaterHeaterMode to ${value}" }
    espHomeClimateCommand(key: state.climate as Long, customPreset: value)
}

public void setVacationMode(String value) {
    if (logTextEnable) { log.info "${device} setVacationMode to ${value} using key ${state.vacation}" }
    espHomeSelectCommand(key: state.vacation as Long, state: value)
}

public void setHeatingSetpoint(float value) {

    if (value < 110 || value > 140){
        log.error "${device} setThermostatHeatingSetpoint to ${value} is out of range (110-140)"
        return
    }

    valueC = ((value - 32) / 1.8) as float
    if (logTextEnable) { log.info "${device} setThermostatHeatingSetpoint to ${value} (${valueC} celsius)" }
    //ESPHome expects Celsius
    espHomeClimateCommand(key: state.climate as Long, targetTemperature: valueC)
}


// the parse method is invoked by the API library when messages are received
public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'device':
            // Device information
            break

        case 'entity':
            
            //Each sensor has a unique key that is used to send commands to the device (also used to interpret received state messages)
            //These are received as a flood of messages when the device is first connected and are used to populate the settings

            switch (message.objectId) {
                case 'power':
                    state['power'] = message.key
                    break
                case  'lower_tank_temperature':
                    state['lowerTankTemperature'] = message.key
                    break
                case 'upper_tank_temperature':
                    state['upperTankTemperature'] = message.key
                    break
                case 'lower_heating_element_runtime':
                    state['lowerHeatingElementRuntime'] = message.key
                    break
                case 'upper_heating_element_runtime':
                    state['upperHeatingElementRuntime'] = message.key
                    break
                case 'hot_water':
                    state['hotWater'] = message.key
                    break
                case 'ambient_temperature':
                    state['ambientTemperature'] = message.key
                    break
                case 'vacation':
                    state['vacation'] = message.key
                    break
                case 'mode':
                    state['mode'] = message.key
                    break
                case 'heating_element_state':
                    state['heatingElementState'] = message.key
                    break
                case 'compressor':
                    state['compressorState'] = message.key
                    break                    
                default:
                    log.debug "Skipping storing key ID for : ${message.objectId} (${message.name})"
            }

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


            //All the other sensors

            if (state.power as Long == message.key && message.hasState) {
                Integer power = Math.round(message.state as Float)
                if (device.currentValue('powerWatts') != power) {
                    updateAttribute('powerWatts', power, 'W')
                }
                return
            }

            if (state.lowerTankTemperature as Long == message.key && message.hasState) {
                Double temperatureF = Math.round(message.state * 10) / 10.0
                if (device.currentValue('lowerTankTemperature') != temperatureF) {
                    updateAttribute('lowerTankTemperature', temperatureF, 'F')
                }
                return
            }

            if (state.upperTankTemperature as Long == message.key && message.hasState) {
                Double temperatureF = Math.round(message.state * 10) / 10.0
                if (device.currentValue('upperTankTemperature') != temperatureF) {
                    updateAttribute('upperTankTemperature', temperatureF, 'F')
                    updateAttribute('temperature', temperatureF, 'F') //Set this attribute so the "TemperatureMeasurement" capability works
                }
                return
            }

            if (state.upperHeatingElementRuntime as Long == message.key && message.hasState) {
                Integer runtime = Math.round(message.state as Float)
                if (device.currentValue('upperHeatingElementRuntime') != runtime) {
                    updateAttribute('upperHeatingElementRuntime', runtime, 'hours')
                }
                return
            }

            if (state.lowerHeatingElementRuntime as Long == message.key && message.hasState) {
                Integer runtime = Math.round(message.state as Float)
                if (device.currentValue('lowerHeatingElementRuntime') != runtime) {
                    updateAttribute('lowerHeatingElementRuntime', runtime, 'hours')
                }
                return
            }

            if (state.hotWater as Long == message.key && message.hasState) {
                Integer percent = Math.round(message.state as Float)
                if (device.currentValue('hotWaterAvailabilityPercent') != percent) {
                    updateAttribute('hotWaterAvailabilityPercent', percent, '%')
                }
                return
            }

            if (state.ambientTemperature as Long == message.key && message.hasState) {
                Double temperatureF = Math.round(message.state * 10) / 10.0
                if (device.currentValue('ambientTemperature') != temperatureF) {
                    updateAttribute('ambientTemperature', temperatureF, 'F')
                }
                return
            }

            if (state.vacation as Long == message.key && message.hasState) {
                if (device.currentValue('vacationMode') != message.state) {
                    updateAttribute('vacationMode', message.state)
                }
                return
            }

            if (state.heatingElementState as Long == message.key && message.hasState) {
                if (device.currentValue('heatingElementState') != message.state) {
                    updateAttribute('heatingElementState', message.state)
                }
                return
            }

            if (state.compressorState as Long == message.key && message.hasState) {
                if (message.state == false){
                    message.state = 'Not Running'
                } else {
                    message.state = 'Running'
                }
                if (device.currentValue('compressorState') != message.state) {
                    updateAttribute('compressorState', message.state)
                    if (message.state == 'Running'){
                        updateAttribute('thermostatOperatingState', 'heating')
                    } else {
                        updateAttribute('thermostatOperatingState', 'idle')
                    }
                    updateAttribute('thermostatOperatingState', message.state)
                }
                return
            }            


            if (message.platform == 'climate') {
                state['climate'] = message.key
                if (message.targetTemperature) {
                    Double temperatureF = Math.round((message.targetTemperature.toDouble() * 1.8 + 32) * 10) / 10.0
                    if (device.currentValue('thermostatHeatingSetpoint') != temperatureF) {
                        updateAttribute('thermostatHeatingSetpoint', temperatureF, 'F')
                        updateAttribute('heatingSetpoint', temperatureF, 'F') //Set this attribute so the "ThermostatHeatingSetpoint" capability works
                    }
                }

                if (message.customPreset) {                    
                    if (message.customPreset == 'Eco Mode'){
                        message.customPreset = 'Energy Saver'
                    }
                    if (device.currentValue('waterHeaterMode') != message.customPreset) {
                        if (message.customPreset == 'Off'){
                            updateAttribute('switch', 'off')
                        } else {
                            updateAttribute('switch', 'on')
                        }
                        updateAttribute('waterHeaterMode', message.customPreset)
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
