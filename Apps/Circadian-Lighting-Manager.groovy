/* groovylint-disable UnnecessarySetter */
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
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import hubitat.helper.ColorUtils

definition (
    name: 'Circadian Lighting Manager',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Sync your color temperature and/or color changing lights to natural daylight hues',
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
        }

        section('Active Managed Lights (only when on)', hideable: true, hidden: true) {
            input name: 'colorTemperatureOnDevices',
                  type: 'capability.colorTemperature',
                  title: 'Circadian Color Temperature Adjustment Devices',
                  multiple: true,
                  required: false

            input name: 'colorOnDevices',
                  type: 'capability.colorControl',
                  title: 'Circadian RGB Color Adjustment Devices',
                  multiple: true,
                  required: false
        }

        section('Fully Managed Lights (even when off)', hideable: true, hidden: true) {
            input name: 'colorTemperatureDevices',
                  type: 'capability.colorTemperature',
                  title: 'Circadian Color Temperature Adjustment Devices',
                  multiple: true,
                  required: false

            input name: 'colorDevices',
                  type: 'capability.colorControl',
                  title: 'Circadian RGB Color Adjustment Devices',
                  multiple: true,
                  required: false
        }

        section('Configuration', hideable: true, hidden: true) {
            input name: 'coldCT',
                  type: 'number',
                  title: 'Cold White Temperature',
                  range: '2000..6000',
                  required: false,
                  defaultValue: 6500

            input name: 'warmCT',
                  type: 'number',
                  title: 'Warm White Temperature',
                  range: '2000..6000',
                  required: false,
                  defaultValue: 2000

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

            input name: 'reenableDelay',
                  type: 'number',
                  title: 'Automatically re-enable lights after specified minutes (0 for never)',
                  range: '0..600',
                  required: false,
                  defaultValue: 60
        }

        section('Overrides', hideable: true, hidden: true) {
            input name: 'disabledSwitch',
                  type: 'capability.switch',
                  title: 'Select switch to enable/disable manager'

            input name: 'disabledSwitchValue',
                    title: 'Disable lighting manager when switch is',
                    type: 'enum',
                    defaultValue: 'off',
                    options: [
                        on: 'on',
                        off: 'off'
                    ]

            input name: 'disabledModes',
                  type: 'mode',
                  title: 'Select mode(s) where lighting manager should be disabled',
                  multiple: true
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
    unschedule()
    unsubscribe()
    state.current = [:]
    state.disabledDevices = [:]

    if (settings.masterEnable) {
        log.info 'Subscribing to device events'
        if (colorTemperatureDevices) { subscribe(colorTemperatureDevices, 'deviceEvent', [ filtered: true ]) }
        if (colorTemperatureOnDevices) { subscribe(colorTemperatureOnDevices, 'deviceEvent', [ filtered: true ]) }
        if (colorDevices) { subscribe(colorDevices, 'deviceEvent', [ filtered: true ]) }
        if (colorOnDevices) { subscribe(colorOnDevices, 'deviceEvent', [ filtered: true ]) }

        // Update circadian calculation and update lamps every 5 minutes
        log.info 'Scheduling period updates'
        schedule('0 */5 * * * ?', 'circadianUpdate')
        circadianUpdate()
    }
}

private static List<Integer> ctToRGB(int colorTemp) {
    int ct = colorTemp
    if (ct < 1000) { ct = 1000 }
    if (ct > 40000) { ct = 40000 }
    ct /= 100

    //red
    BigDecimal r
    if (ct <= 66) { r = 255 } else { r = 329.698727446 * ((ct - 60) ** -0.1332047592) }
    if (r < 0) { r = 0 }
    if (r > 255) { r = 255 }

    //green
    BigDecimal g
    if (ct <= 66) {
        g = 99.4708025861 * Math.log(ct) - 161.1195681661
    } else {
        g = 288.1221695283 * ((ct - 60) ** -0.0755148492)
    }
    if (g < 0) { g = 0 }
    if (g > 255) { g = 255 }

    //blue
    BigDecimal b
    if (ct >= 66) {
        b = 255
    } else if (ct <= 19) {
        b = 0
    } else {
        b = 138.5177312231 * Math.log(ct - 10) - 305.0447927307
    }
    if (b < 0) { b = 0 }
    if (b > 255) { b = 255 }

    return [ r as int, g as int, b as int ]
}

private static int diff(int value1, int value2) {
    return Math.abs(value1 - value2)
}

private boolean checkEnabled() {
    if (!settings.masterEnable) {
        log.info 'Manager is disabled'
        return false
    }

    if (location.mode in disabledModes) {
        log.info "Manager is disabled due to mode ${location.mode}"
        return false
    }

    if (disabledSwitch && disabledSwitch.currentValue('switch') == disabledSwitchValue) {
        log.info "Manager is disabled due to switch ${disabledSwitch} set to ${disabledSwitchValue}"
        return false
    }

    return true
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void circadianUpdate() {
    state.current = currentCircadianValues()
    log.info "Circadian State now: ${state.current}"
    updateLamps()
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

    List<Integer> rgb = ctToRGB(colorTemp)
    List hsv = ColorUtils.rgbToHSV(rgb)

    return [
        sunrise: after.sunrise.time,
        sunset: after.sunset.time,
        colorTemperature: colorTemp,
        hsv: hsv
    ]
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void deviceEvent(Event evt) {
    DeviceWrapper device = evt.device

    switch (evt.name) {
        case 'switch':
            if (evt.value == 'off' && device.id in state.disabledDevices) {
                log.info "Re-enabling ${device} for circadian management (light turned off)"
                state.disabledDevices.remove(device.id)
                if (checkEnabled()) { updateLamp(device) }
                return
            }
            break
        case 'colorTemperature':
            int value = evt.value as int
            int ct = state.current.colorTemperature as int
            if (diff(value, ct) > 100 && !state.disabledDevices.containsKey(device.id)) {
                log.info "Disabling ${device} for circadian management due to manual CT change"
                state.disabledDevices.put(device.id, now())
            } else if (device.id in state.disabledDevices) {
                log.info "Re-enabling ${device} for circadian management"
                state.disabledDevices.remove(device.id)
                if (checkEnabled()) { updateLamp(device) }
            }
            break
        case 'hue':
            int value = evt.value as int
            int hue = state.current.hsv[0] as int
            if (diff(value, hue) > 10 && !state.disabledDevices.containsKey(device.id)) {
                log.info "Disabling ${device} for circadian management due to manual hue change"
                state.disabledDevices.put(device.id, now())
            } else if (device.id in state.disabledDevices) {
                log.info "Re-enabling ${device} for circadian management"
                state.disabledDevices.remove(device.id)
                if (checkEnabled()) { updateLamp(device) }
            }
            break
    }
}

private void updateLamp(DeviceWrapper device) {
    Map current = state.current

    if (device.id in settings.colorOnDevices*.id &&
        device.currentValue('switch') == 'on' && (
        device.currentValue('hue') != current.hsv[0] ||
        device.currentValue('saturation') != current.hsv[1] ||
        device.currentValue('level') != current.hsv[2]) ) {
        log.info "Setting ${device} color to ${current.hsv}"
        device.setColor(current.hsv)
    }

    if (device.id in settings.colorDevices*.id && (
        device.currentValue('hue') != current.hsv[0] ||
        device.currentValue('saturation') != current.hsv[1] ||
        device.currentValue('level') != current.hsv[2]) ) {
        log.info "Setting ${device} color to ${current.hsv}"
        device.setColor(current.hsv)
    }

    if (device.id in settings.colorTemperatureOnDevices*.id &&
        device.currentValue('switch') == 'on' &&
        device.currentValue('colorTemperature') != current.colorTemperature) {
        log.info "Setting ${device} color temperature to ${current.colorTemperature}K"
        device.setColorTemperature(current.colorTemperature)
    }

    if (device.id in settings.colorTemperatureDevices*.id &&
        device.currentValue('colorTemperature') != current.colorTemperature) {
        log.info "Setting ${device} color temperature to ${current.colorTemperature}K"
        device.setColorTemperature(current.colorTemperature)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void updateLamps() {
    Map disabled = state.disabledDevices

    // Remove disabled devices that have timed out
    if (settings.reenableDelay) {
        long expire = now() - (settings.reenableDelay * 60000)
        disabled.values().removeIf { v -> v <= expire }
    }

    if (!checkEnabled()) { return }

    log.info 'Starting circadian updates to lights'

    settings.colorOnDevices?.each { device -> updateLamp(device) }
    settings.colorTemperatureOnDevices?.each { device -> updateLamp(device) }
    settings.colorDevices?.each { device -> updateLamp(device) }
    settings.colorTemperatureDevices?.each { device -> updateLamp(device) }
}
