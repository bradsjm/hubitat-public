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
    definition(name: 'Modern Forms - Fan Light', namespace: 'nrgup', author: 'jb@nrgup.net') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'Light'
        capability 'SwitchLevel'
        capability 'Refresh'

        attribute 'lightSleepTimer', 'string'
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
        input name: 'autoLightOn',
            type: 'bool',
            title: 'Automatic light on',
            description: 'Turn on when light level set',
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
    log.info "${device.displayName} Turning on light"
    sendCommand([ 'lightOn': true ])
}

void off() {
    log.info "${device.displayName} Turning off light"
    sendCommand([ 'lightOn': false ])
}

void refresh() {
    log.info "${device.displayName} Querying device state"
    sendCommand([ 'queryDynamicShadowData': 1 ])
}

void setLevel(BigDecimal level) {
    log.info "${device.displayName} Set light level to ${level}"
    Map payload = settings.autoLightOn ? [ 'lightOn': true ] : [:]
    payload['lightBrightness'] = level
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
        if (json.containsKey('lightOn')) {
            sendEvent(newEvent('switch', json.lightOn ? 'on' : 'off'))
        }
        if (json.containsKey('lightBrightness')) {
            sendEvent(newEvent('level', json.lightBrightness))
        }
        if (json.containsKey('lightSleepTimer') && json.lightSleepTimer > 0) {
            String sleepUntil = new Date((json.lightSleepTimer as long) * 1000).format( 'M-d-yyyy HH:mm-ss' )
            sendEvent(newEvent('lightSleepTimer', sleepUntil))
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

