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
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event

definition(
    name: 'Circadian Lighting Manager',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Sync your color temperature lights to natural daylight hues',
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
            input name: 'masterEnable',
                  type: 'bool',
                  title: 'Application enabled',
                  required: false,
                  defaultValue: true

            input name: 'colorTemperatureOnDevices',
                  type: 'capability.colorTemperature',
                  title: 'Select lights to adjust color temperature when on',
                  multiple: true,
                  required: false
        }

        section('Configuration', hideable: true, hidden: true) {
            input name: 'coldCT',
                  type: 'number',
                  title: 'Cold White Temperature',
                  range: '2000..6500',
                  required: false,
                  defaultValue: 6500

            input name: 'warmCT',
                  type: 'number',
                  title: 'Warm White Temperature',
                  range: '2000..6500',
                  required: false,
                  defaultValue: 2200

            input name: 'sunriseOffset',
                  type: 'number',
                  title: 'Sunrise Offset (+/-)',
                  range: '-600..600',
                  required: false,
                  defaultValue: 0

            input name: 'sunsetOffset',
                  type: 'number',
                  title: 'Sunset Offset (+/-)',
                  range: '-600..600',
                  required: false,
                  defaultValue: 0

            input name: 'enabledModes',
                  type: 'mode',
                  title: 'Select mode(s) where lighting manager should be enabled',
                  multiple: true
        }

        section {
            input name: 'updateInterval',
                    title: 'Update interval',
                    type: 'enum',
                    defaultValue: 5,
                    options: [
                        5: 'Every 5 minutes',
                        10: 'Every 10 minutes',
                        15: 'Every 15 minutes',
                        30: 'Every 30 minutes',
                        45: 'Every 45 minutes'
                    ]

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable Debug logging',
                  required: false,
                  defaultValue: false
        }
    }
}

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
    if (settings.logEnable) { log.debug settings }
    initialize()
}

// Called when the driver is initialized.
void initialize() {
    log.info "${app.name} initializing"
    unschedule()
    unsubscribe()
    atomicState.current = [:]

    if (settings.masterEnable) {
        log.info 'Subscribing to device events'
        if (colorTemperatureOnDevices) {
            subscribe(colorTemperatureOnDevices, 'switch', 'deviceSwitchHandler', null)
        }

        // Subscribe to mode changes and trigger an update
        subscribe(location, 'mode', 'modeChangeHandler')

        modeChangeHandler()
    }
}

// Called when the switch value on device changes
void deviceSwitchHandler(Event event) {
    if (event.value == 'on' && location.mode in settings.enabledModes) {
        updateLamp(event.device)
    }
}

// Called when the mode changes
void modeChangeHandler(Event event = null) {
    if (location.mode in settings.enabledModes) {
        scheduleUpdates()
        circadianUpdate(event)
    } else {
        unschedule('circadianUpdate')
    }
}

private void circadianUpdate(Event event = null) {
    if (location.mode in settings.enabledModes) {
        atomicState.current = currentCircadianValues()
        log.info "Circadian State now: ${atomicState.current} ${event}"
        settings.colorTemperatureOnDevices?.each { device -> updateLamp(device) }
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
    int range = settings.coldCT - settings.warmCT
    int colorTemp = settings.warmCT

    if (currentTime > after.sunrise.time && currentTime < after.sunset.time) {
        if (currentTime < midDay) {
            colorTemp = settings.warmCT + ((currentTime - after.sunrise.time) / (midDay - after.sunrise.time) * range)
        } else {
            colorTemp = settings.coldCT - ((currentTime - midDay) / (after.sunset.time - midDay) * range)
        }
    }

    return [
        sunrise: after.sunrise.time,
        sunset: after.sunset.time,
        colorTemperature: colorTemp
    ]
}

private void scheduleUpdates() {
    // Update circadian calculation and update lamps on defined schedule
    int interval = settings.updateInterval as int
    log.info "Scheduling periodic updates every ${interval} minute(s)"
    schedule("30 2/${interval} * ? * * *", 'circadianUpdate')
}

private void updateLamp(DeviceWrapper device) {
    Map current = atomicState.current

    if (device.currentValue('switch', true) == 'on' &&
        device.currentValue('colorTemperature') != null &&
        Math.abs(device.currentValue('colorTemperature') - current.colorTemperature) > 100) {
        log.info "Setting ${device} color temperature from ${device.currentValue('colorTemperature')}K " +
                 "to ${current.colorTemperature}K"
        device.setColorTemperature(current.colorTemperature)
    }
}
