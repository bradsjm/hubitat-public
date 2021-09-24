/**
 *  MIT License
 *  Copyright 2021 Jonathan Bradshaw (jb@nrgup.net)
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
import groovy.json.JsonOutput
import hubitat.scheduling.AsyncResponse

metadata {
    definition(name: 'Modern Forms - Fan', namespace: 'nrgup', author: 'jb@nrgup.net') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'FanControl'
        capability 'Refresh'
        capability 'Switch'

        attribute 'direction', 'string'
        attribute 'fanSleepTimer', 'string'
        attribute 'breeze', 'string'

        command 'reboot'

        command 'setDirection', [
            [
                name: 'Fan direction',
                type: 'ENUM',
                constraints: [ 'forward', 'reverse' ]
            ]
        ]

        command 'setBreeze', [
            [
                name: 'Breeze speed',
                type: 'ENUM',
                constraints: [ 'off', 'on', 'low', 'medium', 'high' ]
            ]
        ]
    }
}

preferences {
    section {
        input name: 'networkHost',
                type: 'text',
                title: 'IP Address',
                description: 'Modern Forms Device IP',
                required: true,
                defaultValue: ''
    }

    section {
        input name: 'autoOn',
            type: 'bool',
            title: 'Automatic fan on',
            description: 'Turn on when fan speed set',
            required: true,
            defaultValue: true

        input name: 'logEnable',
            type: 'bool',
            title: 'Enable debug logging',
            required: false,
            defaultValue: true

        input name: 'logTextEnable',
            type: 'bool',
            title: 'Enable descriptionText logging',
            required: false,
            defaultValue: true
    }
}

/*
 * Command Section
 */

void initialize() {
    if (settings.networkHost) {
        refresh()
    }
}

void on() {
    log.info "${device.displayName} Turning on"
    setSpeed('on')
}

void off() {
    log.info "${device.displayName} Turning off"
    setSpeed('off')
}

void refresh() {
    log.info "${device.displayName} Querying device state"
    sendCommand([ 'queryDynamicShadowData': 1 ])
}

void setBreeze(String mode) {
    log.info "${device.displayName} Setting breeze mode to ${speed}"
    Map payload = settings.autoOn ? [ 'wind': true ] : [:]
    switch (mode) {
        case 'on':
            payload['wind'] = true
            break
        case 'off':
            payload['wind'] = false
            break
        case 'low':
            payload['windSpeed'] = 1
            break
        case 'medium':
            payload['windSpeed'] = 2
            break
        case 'high':
            payload['windSpeed'] = 3
            break
    }

    sendCommand(payload)
}

void reboot() {
    if (logEnable) { log.trace "sending reboot to ${settings.networkHost}" }

    Map params = [
        uri: 'http://' + settings.networkHost,
        path: '/mf',
        contentType: 'application/json',
        body: JsonOutput.toJson(['reboot', true]),
        timeout: 1
    ]

    asynchttpPost('nullHandler', params)
}

void setDirection(String direction) {
    log.info "${device.displayName} Setting fan direction to ${direction}"
    sendCommand([ 'fanDirection': direction])
}

void setSpeed(String speed) {
    log.info "${device.displayName} Setting fan speed to ${speed}"

    Map payload = settings.autoOn ? [ 'fanOn': true ] : [:]

    switch (speed) {
        case 'auto':
        case 'on':
            // If fan is on low, increase to avoid it having issues
            if (device.currentValue('speed') == 'low') {
                payload['fanSpeed'] = 3 // 50% speed
            }
            payload['fanOn'] = true
            break
        case 'off':
            payload['fanOn'] = false
            break
        case 'low':
            payload['fanSpeed'] = 2
            break
        case 'medium-low':
            payload['fanSpeed'] = 3
            break
        case 'medium':
            payload['fanSpeed'] = 4
            break
        case 'medium-high':
            payload['fanSpeed'] = 5
            break
        case 'high':
            payload['fanSpeed'] = 6
            break
    }

    sendCommand(payload)
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

private String breezeName(int speed) {
    switch (speed) {
        case 1: return 'low'
        case 2: return 'medium'
        case 3: return 'high'
    }

    return 'unknown'
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void handler(AsyncResponse response, Object data) {
    int status = response.status
    if (status == 200) {
        if (logEnable) { log.trace "Modern Fans returned: ${response.data}" }
        Map json = response.json
        if (json.containsKey('clientId')) {
            device.updateDataValue('model', json.clientId)
        }
        if (json.containsKey('fanOn') && json.fanOn == false) {
            sendEvent(newEvent('speed', 'off'))
            sendEvent(newEvent('switch', 'off'))
        } else if (json.containsKey('fanSpeed')) {
            sendEvent(newEvent('speed', speedName(json.fanSpeed)))
            sendEvent(newEvent('switch', 'on'))
        }
        if (json.containsKey('fanDirection')) {
            sendEvent(newEvent('direction', json.fanDirection))
        }
        if (json.containsKey('fanSleepTimer') && json.fanSleepTimer > 0) {
            String sleepUntil = new Date((json.fanSleepTimer as long) * 1000).format( 'M-d-yyyy HH:mm-ss' )
            sendEvent(newEvent('fanSleepTimer', sleepUntil))
        }
        if (json.containsKey('wind') && json.wind == false) {
            sendEvent(newEvent('breeze', 'off'))
        } else if (json.containsKey('windSpeed')) {
            sendEvent(newEvent('breeze', breezeName(json.windSpeed)))
        }
    } else {
        log.error "Modern Fans returned HTTP status ${status}"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}

private Map newEvent(String name, Object value, String unit = null) {
    String splitName = splitCamelCase(name)
    String description = "${device.displayName} ${splitName} is ${value}${unit ?: ''}"
    if (logEnable) { log.info description }
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: settings.logTextEnable ? description : ''
    ]
}

/* groovylint-disable-next-line EmptyMethod, UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void nullHandler(AsyncResponse response, Object data) {
    // Do nothing
}

private void sendCommand(Map payload) {
    if (logEnable) { log.trace "sending ${payload} to ${settings.networkHost}" }

    Map params = [
        uri: 'http://' + settings.networkHost,
        path: '/mf',
        contentType: 'application/json',
        body: JsonOutput.toJson(payload),
        timeout: 5
    ]

    asynchttpPost('handler', params)
}

private String speedName(int speed) {
    switch (speed) {
        case 1:
        case 2: return 'low'
        case 3: return 'medium-low'
        case 4: return 'medium'
        case 5: return 'medium-high'
        case 6: return 'high'
    }

    return 'unknown'
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

