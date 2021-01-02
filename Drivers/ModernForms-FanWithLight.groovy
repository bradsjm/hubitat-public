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
    definition(name: 'Modern Forms Fan', namespace: 'nrgup', author: 'jb@nrgup.net') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'FanControl'
        capability 'SwitchLevel'
        capability 'Polling'

        attribute 'direction', 'string'
        attribute 'fanSleepTimer', 'string'
        attribute 'lightSleepTimer', 'string'
        attribute 'windSpeed', 'number'

        command 'setDirection', [
            [
                name: 'Fan Direction',
                type: 'ENUM',
                description: 'Pick an option',
                constraints: [ 'forward', 'reverse' ]
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
            title: 'Automatic turn on',
            description: 'Turn on when speed set',
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
        poll()
    }
}

void poll() {
    log.info "${device.displayName} Polling device"
    sendCommand([ 'queryDynamicShadowData': 1 ])
}

void setDirection(String direction) {
    log.info "${device.displayName} Setting fan direction to ${direction}"
    sendCommand([ 'fanDirection': direction])
}

void setLevel(BigDecimal level) {
    log.info "${device.displayName} Set light level to ${level}"
    sendCommand([ 'lightBrightness': level ])
}

void setSpeed(String speed) {
    log.info "${device.displayName} Setting fan speed to ${speed}"

    Map payload = settings.autoOn ? [ 'fanOn': true ] : [:]

    switch (speed) {
        case 'auto':
        case 'on':
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
        } else if (json.containsKey('fanSpeed')) {
            sendEvent(newEvent('speed', speedName(json.fanSpeed)))
        }
        if (json.containsKey('fanDirection')) {
            sendEvent(newEvent('direction', json.fanDirection))
        }
        if (json.containsKey('lightOn')) {
            sendEvent(newEvent('switch', json.lightOn ? 'on' : 'off'))
        }
        if (json.containsKey('lightBrightness')) {
            sendEvent(newEvent('level', json.lightBrightness))
        }
        if (json.containsKey('fanSleepTimer') && json.fanSleepTimer > 0) {
            String sleepUntil = new Date((json.fanSleepTimer as long) * 1000).format( 'M-d-yyyy HH:mm-ss' )
            sendEvent(newEvent('fanSleepTimer', sleepUntil))
        }
        if (json.containsKey('lightSleepTimer') && json.lightSleepTimer > 0) {
            String sleepUntil = new Date((json.lightSleepTimer as long) * 1000).format( 'M-d-yyyy HH:mm-ss' )
            sendEvent(newEvent('lightSleepTimer', sleepUntil))
        }
        if (json.containsKey('wind') && json.wind == false) {
            sendEvent(newEvent('windSpeed', 'off'))
        } else if (json.containsKey('windSpeed')) {
            sendEvent(newEvent('windSpeed', speedName(json.windSpeed)))
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

