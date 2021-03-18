/**
 *  MIT License
 *  Copyright 2019 Jonathan Bradshaw (jb@nrgup.net)
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
    definition(
        name: 'Tuya Cloud - RGB/CT Light',
        namespace: 'tuya',
        author: 'Jonathan Bradshaw'
    ) {
        capability 'Actuator'
        capability 'Color Control'
        capability 'ColorMode'
        capability 'ColorTemperature'
        capability 'Initialize'
        capability 'Light'
        capability 'SwitchLevel'
        capability 'Polling'

        attribute 'effect', 'number'
    }
}

preferences {
    section {
        input name: 'email',
              type: 'text',
              title: 'Tuya Email',
              description: '',
              required: true

        input name: 'password',
              type: 'password',
              title: 'Tuya Password',
              required: true

        input name: 'region',
              title: 'Region',
              type: 'enum',
              required: true,
              defaultValue: us,
              options: [
                us: 'US',
                eu: 'Europe',
                cn: 'China'
              ]

        input name: 'countryCode',
              title: 'Country Code',
              type: 'text',
              required: true,
              defaultValue: '1'

        input name: 'bizType',
              title: 'Platform',
              type: 'enum',
              required: true,
              defaultValue: tuya,
              options: [
                tuya: 'Tuya',
                smart_life: 'Smart Life',
                jinvoo_smart: 'Jinvoo Smart'
              ]

        input name: 'devId',
              type: 'text',
              title: 'Device ID',
              required: true

        input name: 'warmColorTemp',
              type: 'number',
              title: 'Warm Color Temperature',
              required: true,
              range: 2700..6500,
              defaultValue: 2700

        input name: 'coldColorTemp',
              type: 'number',
              title: 'Cold Color Temperature',
              required: true,
              range: 2700..6500,
              defaultValue: 4700
    }

    section {
        input name: 'pollInterval',
                title: 'Polling Interval',
                type: 'enum',
                required: true,
                defaultValue: 120,
                options: [
                    0: 'None',
                    125: '2 Minutes',
                    300: '5 Minutes',
                    600: '10 Minutes',
                    905: '15 Minutes',
                    1800: '30 Minutes',
                    3600: '60 Minutes'
                ]

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

// Called when the device is started.
void initialize() {
    unschedule()
    log.info "${device.displayName} driver initializing"
    if (!settings.email || !settings.password) {
        log.error 'Unable to connect because login and password are required'
        return
    }

    authenticate()
    if ((settings.pollInterval as int) > 0) {
        runIn(settings.pollInterval as int, 'poll')
    }
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

void poll() {
    log.info "Polling ${device.displayName} status"
    skill(
        'query',
        'QueryDevice'
    )

    if ((settings.pollInterval as int) > 0) {
        runIn(settings.pollInterval as int, 'poll')
    }
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

/*
 * Switch Capability
 */
void on() {
    log.info "Turning ${device.displayName} on"
    skill(
        'control',
        'turnOnOff',
        [ value: 1 ],
        [ switch: newEvent('switch', 'on') ]
    )
}

void off() {
    log.info "Turning ${device.displayName} off"
    skill(
        'control',
        'turnOnOff',
        [ value: 0 ],
        [ switch: newEvent('switch', 'off') ]
    )
}

/*
 * SwitchLevel Capability
 */
/* groovylint-disable-next-line UnusedMethodParameter */
void setLevel(BigDecimal level, BigDecimal duration = 0) {
    log.info "Setting ${device.displayName} brightness to ${level}%"
    String colorMode = device.currentValue('colorMode')
    if (colorMode == 'CT') {
        skill(
            'control',
            'brightnessSet',
            [ value: level ],
            [ level: newEvent('level', level) ]
        )
    } else {
        setColor([
            hue: device.currentValue('hue') ?: 0,
            saturation: device.currentValue('saturation') ?: 0,
            level: level
        ])
    }
}

/*
 * Color Temperature Capability
 */
void setColorTemperature(BigDecimal kelvin) {
    int value = kelvin >= settings.coldColorTemp ? settings.coldColorTemp :
                kelvin <= settings.warmColorTemp ? settings.warmColorTemp : kelvin
    log.info "Setting ${device.displayName} temperature to ${value}K"
    skill(
        'control',
        'colorTemperatureSet',
        [ value: value ],
        [
            colorTemperature: newEvent('colorTemperature', value),
            colorMode: newEvent('colorMode', 'CT')
        ]
    )
}

/*
 * Color Control Capability
 */

// Set the HSB color [hue:(0-100), saturation:(0-100), brightness level:(0-100)]
void setColor(Map colormap) {
    log.info "Setting ${device.displayName} color to ${colormap}"
    Map value = [
        hue: Math.round(colormap.hue * 3.60),
        saturation: Math.round(colormap.saturation * 2.55),
        brightness: Math.round(colormap.level * 2.55)
    ]
    skill(
        'control',
        'colorSet',
        [ color: value ],
        [
            hue: newEvent('hue', colormap.hue),
            saturation: newEvent('saturation', colormap.saturation),
            level: newEvent('level', colormap.level),
            colorName: newEvent('colorName', getGenericName([colormap.hue, colormap.saturation, colormap.level])),
            colorMode: newEvent('colorMode', 'RGB')
        ]
    )
}

void setHue(BigDecimal hue) {
    log.info "Setting ${device.displayName} hue to ${hue}"
    /* groovylint-disable-next-line UnnecessarySetter */
    setColor([
        hue: hue,
        saturation: device.currentValue('saturation'),
        level: device.currentValue('level') ?: 100
    ])
}

void setSaturation(BigDecimal saturation) {
    log.info "Setting ${device.displayName} saturation to ${saturation}"
    /* groovylint-disable-next-line UnnecessarySetter */
    setColor([
        hue: device.currentValue('hue') ?: 100,
        saturation: saturation,
        level: device.currentValue('level') ?: 100
    ])
}

/*
 * Tuya Protocol Functions
 */
private void authenticate() {
    unschedule('authenticate')
    Map params = [
        uri: "https://px1.tuya${settings.region}.com/homeassistant/",
        path: 'auth.do',
        contentType: 'application/json',
        requestContentType: 'application/x-www-form-urlencoded',
        body: "userName=${settings.email}&password=${settings.password}&" +
              "countryCode=${settings.countryCode}&bizType=${settings.bizType}&from=tuya",
        timeout: 5
    ]
    log.info "Authenticating to Tuya API as ${settings.email}"
    asynchttpPost('authHandler', params)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void authHandler(AsyncResponse response, Object data) {
    if (response.status == 200) {
        if (logEnable) { log.debug "Tuya API returned: ${response.data}" }
        if (response.json && response.json.expires_in) {
            state.token = response.json
            runIn((state.token.expires_in as int) - 600, 'authenticate')
        } else if (response.json && response.json.errorMsg) {
            log.error response.json.errorMsg
            runIn(90, 'authenticate')
        }
    } else if (response.status == 401 || response.status == 400) {
        log.error 'Authentication failed! Check email/password and try again.'
    } else {
        log.error "Tuya returned HTTP status ${response.status}"
        runIn(90, 'authenticate')
    }
}

private void parse(Map dps) {
    String colorMode = device.currentValue('colorMode')
    List<Map> events = []

    if (dps.containsKey('online')) {
        events << newEvent('status', dps['online'] ? 'online' : 'offline')
    }

    if (dps.containsKey('state')) {
        events << newEvent('switch', dps['state'] ? 'on' : 'off')
    }

    // Determine if we are in RGB or CT mode either explicitly or implicitly
    if (dps.containsKey('color_mode')) {
        colorMode = dps['color_mode'] == 'colour' ? 'RGB' : 'CT'
        events << newEvent('colorMode', colorMode)
    } else if (dps.containsKey('color_temp')) {
        colorMode = 'CT'
        events << newEvent('colorMode', colorMode)
    } else if (dps.containsKey('5')) {
        colorMode = 'RGB'
        events << newEvent('colorMode', colorMode)
    }

    if (dps.containsKey('brightness') && colorMode == 'CT') {
        int value = Math.round(((dps['brightness'] as int) / 255) * 100)
        events << newEvent('level', value, '%')
    }

    if (dps.containsKey('color_temp')) {
        int value = dps['color_temp'] as int
        events << newEvent('colorTemperature', value, 'K')
    }

    events.each { e ->
        if (e.descriptionText) { log.info e.descriptionText }
        if (device.currentValue(e.name) != e.value) {
            sendEvent(e)
        }
    }
}

private void skill(String namespace, String command, Map payload = [:], Map events = null) {
    payload['accessToken'] = state.token.access_token
    payload['devId'] = settings.devId

    Map params = [
        uri: "https://px1.tuya${settings.region}.com/homeassistant/",
        path: 'skill',
        contentType: 'application/json',
        body: JsonOutput.toJson([
            header: [
                namespace: namespace,
                name: command,
                payloadVersion: 1
            ],
            payload: payload
        ]),
        timeout: 5
    ]
    if (logEnable) { log.debug "Sending ${params}" }
    asynchttpPost('skillHandler', params, events)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void skillHandler(AsyncResponse response, Map events) {
    if (response.status == 200) {
        if (logEnable) { log.debug "Tuya API returned: ${response.data}" }
        if (response.json && response.json['header']['code'] == 'SUCCESS') {
            if (events) {
                events.each { k, v -> sendEvent(v) }
            }
            if (response.json['payload']['data']) {
                parse(response.json['payload']['data'])
            }
        } else if (response.json && response.json['header']['msg']) {
            log.error 'Tuya API: ' + response.json['header']['msg']
        }
    } else {
        log.error "Tuya returned HTTP status ${response.status}"
    }
}

/*
 * Utility Functions
 */

private String getGenericName(List<Integer> hsv) {
    String colorName

    if (!hsv[0] && !hsv[1]) {
        colorName = 'White'
    } else {
        switch (hsv[0] * 3.6 as int) {
            case 0..15: colorName = 'Red'
                break
            case 16..45: colorName = 'Orange'
                break
            case 46..75: colorName = 'Yellow'
                break
            case 76..105: colorName = 'Chartreuse'
                break
            case 106..135: colorName = 'Green'
                break
            case 136..165: colorName = 'Spring'
                break
            case 166..195: colorName = 'Cyan'
                break
            case 196..225: colorName = 'Azure'
                break
            case 226..255: colorName = 'Blue'
                break
            case 256..285: colorName = 'Violet'
                break
            case 286..315: colorName = 'Magenta'
                break
            case 316..345: colorName = 'Rose'
                break
            case 346..360: colorName = 'Red'
                break
        }
    }

    return colorName
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}

private Map newEvent(String name, Object value, String unit = '') {
    String splitName = splitCamelCase(name).toLowerCase()
    String description = "${device.displayName} ${splitName} is ${value}${unit}"
    return [
        name: name,
        value: value,
        descriptionText: description
    ]
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
