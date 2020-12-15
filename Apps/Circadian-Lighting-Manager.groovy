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

            input name: 'dimmableOnDevices',
                  type: 'capability.switchLevel',
                  title: 'Circadian Brightness Level Adjustment Devices',
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

            input name: 'dimmableDevices',
                  type: 'capability.switchLevel',
                  title: 'Circadian Brightness Level Adjustment Devices',
                  multiple: true,
                  required: false
        }

        section('Illuminance Configuration', hideable: true, hidden: true) {
            input name: 'luxDevices',
                  type: 'capability.illuminanceMeasurement',
                  title: 'Lux Sensor Devices',
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

            input name: 'minBrightness',
                  type: 'number',
                  title: 'Minimum brightness (1-100)',
                  range: '1..100',
                  required: false,
                  defaultValue: 30

            input name: 'maxBrightness',
                  type: 'number',
                  title: 'Maximum brightness (1-100)',
                  range: '1..100',
                  required: false,
                  defaultValue: 100

            input name: 'reenableDelay',
                  type: 'number',
                  title: 'Automatically re-enable lights after specified minutes (0 for never)',
                  range: '0..600',
                  required: false,
                  defaultValue: 60
        }

        section {
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
    state.luxHistory = state.luxHistory ?: [:]

    if (masterEnable) {
        List activated = []
        if (colorTemperatureOnDevices) { activated.addAll(colorTemperatureOnDevices) }
        if (colorOnDevices) { activated.addAll(colorOnDevices) }
        if (dimmableOnDevices) { activated.addAll(dimmableOnDevices) }

        List managed = []
        if (colorTemperatureDevices) { managed.addAll(colorTemperatureDevices) }
        if (colorDevices) { managed.addAll(colorDevices) }
        if (dimmableDevices) { managed.addAll(dimmableDevices) }

        subscribe(colorTemperatureOnDevices, 'switch', 'updateLamp')
        subscribe(colorOnDevices, 'switch', 'updateLamp')
        subscribe(dimmableOnDevices, 'switch', 'updateLamp')

        subscribe(colorTemperatureDevices, 'switch', 'updateLamp')
        subscribe(colorDevices, 'switch', 'updateLamp')
        subscribe(dimmableDevices, 'switch', 'updateLamp')

        subscribe(colorTemperatureOnDevices, 'level', 'levelCheck')
        subscribe(colorOnDevices, 'level', 'levelCheck')
        subscribe(dimmableOnDevices, 'level', 'levelCheck')
        subscribe(colorTemperatureDevices, 'level', 'levelCheck')
        subscribe(colorDevices, 'level', 'levelCheck')
        subscribe(dimmableDevices, 'level', 'levelCheck')

        // Update circadian calculation and update lamps every 5 minutes
        schedule('0 */5 * * * ?', 'circadianUpdate')
        circadianUpdate()

        // Every hour take a lux reading
        schedule('0 0 * * * ?', 'luxHistoryUpdate')
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

/* groovylint-disable-next-line UnusedPrivateMethod */
private void circadianUpdate() {
    state.current = currentCircadianValues()
    log.info "Circadian State now: ${state.current}"
    updateLamps()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void luxHistoryUpdate() {
    int historySize = 5
    String key = Calendar.instance.get(Calendar.HOUR_OF_DAY)
    int lux = currentLuxValue()
    log.info "Lux (hour ${key}) history: ${state.luxHistory[key]}"
    state.luxHistory[key] = (state.luxHistory[key] ?: []).takeRight(historySize - 1) + lux
    log.info "Lux (hour ${key}) history: ${state.luxHistory[key]}"
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void levelCheck(Event evt) {
    DeviceWrapper device = evt.device
    int value = evt.value as int
    int brightness = state.current.brightness

    if ((value > brightness + 5 || value < brightness - 5) &&
        !state.disabledDevices.containsKey(device.id)) {
        log.info "Disabling ${device} for circadian management due to manual brightness change"
        state.disabledDevices.put(device.id, now())
    } else if (device.id in state.disabledDevices) {
        log.info "Re-enabling ${device} for circadian management (light now at circadian brightness)"
        state.disabledDevices.remove(device.id)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void updateLamp(Event evt) {
    Map current = state.current
    DeviceWrapper device = evt.device

    if (evt.value == 'off' && device.id in state.disabledDevices) {
        log.info "Re-enabling ${device} for circadian management (light turned off)"
        state.disabledDevices.remove(device.id)
        return
    }

    if (location.mode in disabledModes) {
        log.info "Manager is disabled due to mode ${location.mode}"
        return
    }

    if (device.id in settings.dimmableOnDevices*.id && device.currentValue('level') != current.brightness) {
        log.info "Setting ${device} level to ${current.brightness}%"
        device.setLevel(current.brightness)
    }

    if (device.id in settings.colorOnDevices*.id && (
        device.currentValue('hue') != current.hsv[0] ||
        device.currentValue('saturation') != current.hsv[1] ||
        device.currentValue('level') != current.hsv[2])) {
        log.info "Setting ${device} color to ${current.hsv}"
        device.setColor(current.hsv)
    }

    if (device.id in settings.colorTemperatureOnDevices*.id &&
        device.currentValue('colorTemperature') != current.colorTemperature) {
        log.info "Setting ${device} color temperature to ${current.colorTemperature}K"
        device.setColorTemperature(current.colorTemperature)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void updateLamps() {
    Map current = state.current
    Map disabled = state.disabledDevices

    if (location.mode in disabledModes) {
        log.info "Manager is disabled due to mode ${location.mode}"
        return
    }

    // Remove disabled devices that have timed out
    if (settings.reenableDelay) {
        long expire = now() - (settings.reenableDelay * 60000)
        disabled.values().removeIf { v -> v <= expire }
    }

    log.info 'Starting circadian updates to lights'

    settings.dimmableOnDevices?.each { device ->
        if (!disabled.containsKey(device.id) &&
            device.currentValue('switch') == 'on' &&
            device.currentValue('level') != current.brightness) {
            if (logEnable) { log.debug "Setting ${device} level to ${current.brightness}%" }
            device.setLevel(current.brightness)
        }
    }

    settings.colorOnDevices?.each { device ->
        if (!disabled.containsKey(device.id) &&
            device.currentValue('switch') == 'on' && (
            device.currentValue('hue') != current.hsv[0] ||
            device.currentValue('saturation') != current.hsv[1] ||
            device.currentValue('level') != current.hsv[2])) {
            if (logEnable) { log.debug "Setting ${device} color to ${current.hsv}" }
            device.setColor(current.hsv)
        }
    }

    settings.colorTemperatureOnDevices?.each { device ->
        if (!disabled.containsKey(device.id) &&
            device.currentValue('switch') == 'on' &&
            device.currentValue('colorMode') != 'RGB' &&
            device.currentValue('colorTemperature') != current.colorTemperature) {
            if (logEnable) { log.debug "Setting ${device} color temperature to ${current.colorTemperature}K" }
            device.setColorTemperature(current.colorTemperature)
        }
    }

    settings.dimmableDevices?.each { device ->
        if (!disabled.containsKey(device.id) &&
            device.currentValue('level') != current.brightness) {
            if (logEnable) { log.debug "Setting ${device} level to ${current.brightness}%" }
            device.setLevel(current.brightness)
        }
    }

    settings.colorDevices?.each { device ->
        if (!disabled.containsKey(device.id) &&
            device.currentValue('color') != current.hsv) {
            if (logEnable) { log.debug "Setting ${device} color to ${current.hsv}" }
            device.setColor(current.hsv)
        }
    }

    settings.colorTemperatureDevices?.each { device ->
        if (!disabled.containsKey(device.id) &&
            device.currentValue('colorMode') != 'RGB' &&
            device.currentValue('colorTemperature') != current.colorTemperature) {
            if (logEnable) { log.debug "Setting ${device} color temperature to ${current.colorTemperature}K" }
            device.setColorTemperature(current.colorTemperature)
        }
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

    List<Integer> rgb = ctToRGB(colorTemp)
    List hsv = ColorUtils.rgbToHSV(rgb)

    return [
        sunrise: after.sunrise.time,
        sunset: after.sunset.time,
        colorTemperature: colorTemp,
        brightness: brightness as int,
        hsv: hsv
    ]
}

/*
 * Average of lux sensors
 */
private int currentLuxValue() {
    int total = 0
    int count = 0
    luxDevices.each { d ->
        int value = d.currentValue('illuminance')
        if (value) { count++ }
        total += value
    }
    return Math.round(total / count)
}

