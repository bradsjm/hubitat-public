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
import groovy.transform.CompileStatic
import java.math.RoundingMode

metadata {
    definition(
        name: 'ESPHome Bluetooth Proxy',
        namespace: 'esphome',
        author: 'Jonathan Bradshaw',
        singleThreaded: true,
        importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/ESPHome/ESPHome-BluetoothLE.groovy') {
        capability 'Initialize'

        command 'startScan'

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

void initialize() {
    // API library command to open socket to device, it will automatically reconnect if needed
    openSocket()
    state.clear()

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

void installed() {
    log.info "${device} driver installed"
}

void logsOff() {
    espHomeSubscribeLogs(LOG_LEVEL_INFO, false) // disable device logging
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

void uninstalled() {
    closeSocket('driver uninstalled') // make sure the socket is closed when uninstalling
    log.info "${device} driver uninstalled"
}

// the parse method is invoked by the API library when messages are received
@CompileStatic
void parse(Map message) {
    switch (message.type) {
        case 'device':
            // Device information
            startScan()
            break

        case 'entity':
            break

        case 'state':
            // Bluetooth LE
            if (message.platform == 'bluetoothle') {
                parseBluetoothLE(message)
            }
            break
    }
}

@CompileStatic
void parseBluetoothLE(Map message) {
    switch (message.name as String) {
        case ~/^GVH5.+/:
            parseGovee(message)
            break
    }
}

void parseGovee(Map message) {
    List<Integer> data = message.manufacturerData['0xec88'] as List<Integer>
    if (data?.size() != 6) {
        return
    }

    int basenum = (data[1] << 16) + (data[2] << 8) + data[3]
    //int batteryLevel = data[4]
    BigDecimal humidity = (basenum % 1000) / 10.0f
    BigDecimal temperature = fromCelcius(basenum / 10000.0f)
    humidity = humidity.setScale(1, RoundingMode.HALF_UP)
    temperature = temperature.setScale(1, RoundingMode.HALF_UP)

    String driver = 'Generic Component Omni Sensor'
    getChildDevice(message.name as String, message.address as String, driver).parse([
        //[ name: 'battery', value: batteryLevel, descriptionText: "battery level is ${batteryLevel}", unit: '%' ],
        [ name: 'temperature', value: temperature, descriptionText: "temperature is ${temperature}", unit: location.temperatureScale ],
        [ name: 'humidity', value: humidity, descriptionText: "humidity level is ${humidity}", unit: '%rh' ],
    ])
}

void startScan() {
    log.info 'Subscribing to Bluetooth LE advertisements'
    espHomeSubscribeBtleRequest()
}

// Create (if required) and return the child device wrapper
private ChildDeviceWrapper getChildDevice(String name, String address, String componentDriver) {
    String dni = "${device.id}-${address.replace(':', '')}"
    ChildDeviceWrapper dw = getChildDevice(dni) ?:
        addChildDevice(
            'hubitat',
            componentDriver,
            dni
        )
    dw.name = name
    return dw
}

// Convert value from celcius if Hubitat is using F scale
private BigDecimal fromCelcius(double temperature) {
    return location.temperatureScale == 'F' ? celsiusToFahrenheit(temperature) : temperature
}

// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper
