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
    name: 'Illuminance Manager',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Sync your light illuminance to outside lighting conditions',
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

        section('Managed Lights', hideable: true, hidden: true) {
            input name: 'dimmableOnDevices',
                  type: 'capability.switchLevel',
                  title: 'Circadian Brightness Level Adjustment Devices',
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

        section('Overrides', hideable: true, hidden: true) {
            input name: 'disabledSwitch',
                  type: 'capability.switch',
                  title: 'Select switch to enable/disable manager'

            input name: 'disabledSwitchValue',
                    title: 'Disable lighting manager when switch is',
                    type: 'enum',
                    required: true,
                    defaultValue: 10,
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
    state.luxHistory = state.luxHistory ?: [:]

    if (masterEnable) {
        subscribe(dimmableOnDevices, 'switch', 'updateLamp')
        subscribe(dimmableDevices, 'switch', 'updateLamp')
        subscribe(dimmableOnDevices, 'level', 'levelCheck')
        subscribe(dimmableDevices, 'level', 'levelCheck')

        // Update lamps every 5 minutes
        schedule('0 */5 * * * ?', 'levelUpdate')
        levelUpdate()

        // Every hour take a lux reading
        schedule('0 0 * * * ?', 'luxHistoryUpdate')
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void levelUpdate() {
    //state.current = currentCircadianValues()
    //log.info "Circadian State now: ${state.current}"
    updateLamps()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void luxHistoryUpdate() {
    int historySize = 5
    String key = Calendar.instance.get(Calendar.HOUR_OF_DAY)
    int lux = currentLuxValue()
    log.info "Lux (hour ${key}) history: ${state.luxHistory[key]}"
    state.luxHistory[key] = state.luxHistory.getOrDefault(key, []).takeRight(historySize - 1) + lux
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

    if (disabledSwitch && disabledSwitch.currentValue('switch') == disabledSwitchValue) {
        log.info "Manager is disabled due to switch ${disabledSwitch} set to ${disabledSwitchValue}"
        return
    }

    if (device.id in settings.dimmableOnDevices*.id && device.currentValue('level') != current.brightness) {
        log.info "Setting ${device} level to ${current.brightness}%"
        device.setLevel(current.brightness)
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

    settings.dimmableDevices?.each { device ->
        if (!disabled.containsKey(device.id) &&
            device.currentValue('level') != current.brightness) {
            if (logEnable) { log.debug "Setting ${device} level to ${current.brightness}%" }
            device.setLevel(current.brightness)
        }
    }
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

