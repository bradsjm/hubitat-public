/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
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
import groovy.transform.Field

definition (
    name: 'Circadian Manager',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Sync your color temperature, color changing, and dimmable lights to natural daylight hues',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page(name: 'configuration', install: true, uninstall: true) {
        section {
            label title: 'Application Label',
                  required: false
        }
        section {
            input name: 'colorTemperatureDevices',
                  type: 'capability.colorTemperature',
                  hideWhenEmpty: true,
                  title: 'Devices to manage Circadian Color Temperature',
                  multiple: true,
                  required: false

            input name: 'dimmableDevices',
                  type: 'capability.switchLevel',
                  hideWhenEmpty: true,
                  title: 'Devices to manage Circadian Brightness Level',
                  multiple: true,
                  required: false
        }
        section('Options') {
            input name: 'coldCT',
                  type: 'number',
                  title: 'Cold White Temperature',
                  required: false,
                  defaultValue: 6500

            input name: 'warmCT',
                  type: 'number',
                  title: 'Warm White Temperature',
                  required: false,
                  defaultValue: 2000

            input name: 'sunriseOffset',
                  type: 'number',
                  title: 'Sunrise Offset (+/-)',
                  required: false,
                  defaultValue: 0

            input name: 'sunsetOffset',
                  type: 'number',
                  title: 'Sunset Offset (+/-)',
                  required: false,
                  defaultValue: 0

            input name: 'minBrightness',
                  type: 'number',
                  title: 'Minimum brightness (1-100)',
                  required: false,
                  defaultValue: 30

            input name: 'maxBrightness',
                  type: 'number',
                  title: 'Maximum brightness (1-100)',
                  required: false,
                  defaultValue: 100
        }
        section {
            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable Debug logging',
                  required: false,
                  defaultValue: true
        }
    }
}

@Field final String cXYZ = 'constant'

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
}

// Called when the app is removed.
void uninstalled() {
    unsubscribe()
    log.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    log.debug settings
    initialize()
}

// Called when the driver is initialized.
void initialize() {
    log.info "${app.name} initializing"

    if (colorTemperatureDevices) {
    //subscribe(colorTemperatureDevices, 'switch.on', modeHandler)
    //subscribe(colorTemperatureDevices, 'level', modeHandler)
    }

    if (dimmableDevices) {
    //subscribe(dimmableDevices, 'switch.on', modeHandler)
    //subscribe(dimmableDevices, 'level', modeHandler)
    }

    scheduleUpdate()
    schedule('0 */5 * * * ?', 'scheduleUpdate')
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void scheduleUpdate() {
    Map current = currentCircadianValues()

    dimmableDevices.each { device ->
        if (logEnable) { log.debug "Setting ${device} level to ${current.brightness}%" }
        device.setLevel(current.brightness)
    }

    colorTemperatureDevices.each { device ->
        if (logEnable) { log.debug "Setting ${device} color temperature to ${current.colorTemperature}K" }
        device.setColorTemperature(current.colorTemperature)
    }
}

/*
 * Track the state of the sun
 */
private Map currentCircadianValues() {
    Map after = getSunriseAndSunset(
        sunriseOffset: settings.sunriseOffset ?: 0,
        sunsetOffset: settings.sunsetOffset ?: 0
    )
    long midDay = after.sunrise.time + ((after.sunset.time - after.sunrise.time) / 2)
    long currentTime = now()
    BigDecimal brightness = settings.minBrightness
    int range = settings.coldCT - settings.warmCT
    int colorTemp = settings.warmCT

    if (currentTime > after.sunrise.time && currentTime < after.sunset.time) {
        if (currentTime < midDay) {
            colorTemp = settings.warmCT + ((currentTime - after.sunrise.time) / (midDay - after.sunrise.time) * range)
            brightness = ((currentTime - after.sunrise.time) / (midDay - after.sunrise.time))
        } else {
            colorTemp = settings.coldCT - ((currentTime - midDay) / (after.sunset.time - midDay) * range)
            brightness = 1 - ((currentTime - midDay) / (after.sunset.time - midDay))
        }

        brightness = settings.minBrightness + Math.round(brightness * (settings.maxBrightness - settings.minBrightness))
    }

    if (logEnable) { log.debug "Calculation: ColorTemperature is ${colorTemp}K, Brightness ${brightness}" }

    return [
        sunrise: after.sunrise.time,
        sunset: after.sunset.time,
        colorTemperature: colorTemp,
        brightness: brightness as int
    ]
}
