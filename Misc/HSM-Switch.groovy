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

import com.hubitat.hub.domain.Event

metadata {
    definition (name: 'HSM Switch', namespace: 'nrgup', author: 'Jonathan Bradshaw') {
        capability 'Switch'
    }

    preferences {
        section {
            input name: 'armValue',
                  type: 'text',
                  title: 'Arming Mode',
                  defaultValue: 'armAway'
                  required: true
        }

        section {
            input name: 'logTextEnable',
                  type: 'bool',
                  title: 'Enable descriptionText logging',
                  required: false,
                  defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings

    if (logEnable) { runIn(1800, 'logsOff') }
}

/**
 *  Capability: Switch
 */

// Turn on
void on() {
    log.info "Switching ${device.displayName} on"
    sendLocationEvent (name: 'hsmSetArm', value: armValue)
    sendEvent(newEvent('switch', 'on'))
}

// Turn off
void off() {
    log.info "Switching ${device.displayName} off"
    sendLocationEvent (name: 'hsmSetArm', value: 'disarm')
    sendEvent(newEvent('switch', 'off'))
}

/**
 *  Common utility methods
 */

private Map newEvent(String name, Object value, Map params = [:]) {
    String splitName = splitCamelCase(name).toLowerCase()
    String description = "${device.displayName} ${splitName} is ${value}${params.unit ?: ''}"
    log.info description
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: settings.logTextEnable ? description : ''
    ] + params
}

private String splitCamelCase(String s) {
   return s.replaceAll(
      String.format('%s|%s|%s',
         '(?<=[A-Z])(?=[A-Z][a-z])',
         '(?<=[^A-Z])(?=[A-Z])',
         '(?<=[A-Za-z])(?=[^A-Za-z])'
      ),
      ' '
   )
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}
