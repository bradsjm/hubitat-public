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
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.scheduling.AsyncResponse

definition (
    name: 'Tuya Smart Life Cloud',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Control Tuya Smart Life devices through their cloud',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page(name: 'configuration', title: 'Tuya Lighting', install: true, uninstall: true) {
        section {
            label title: 'Application Label',
                required: false

            input name: 'email',
                type: 'text',
                title: 'Tuya Email',
                description: '',
                required: true

            input name: 'password',
                type: 'password',
                title: 'Tuya Password',
                required: true

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
        }
        section {
            input name: 'logEnable',
                type: 'bool',
                title: 'Enable debug logging',
                required: false,
                defaultValue: true
        }
    }
}

@Field final static Map lightEffects = [
    1: 'Effect 1',
    2: 'Effect 2',
    3: 'Effect 3',
    4: 'Effect 4',
    5: 'Effect 5',
    6: 'Effect 6',
    7: 'Effect 7',
    8: 'Effect 8',
    9: 'Effect 9'
]

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
}

// Called when the app is removed.
void uninstalled() {
    log.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    log.debug settings
    initialize()
}

// Called when the app is initialized.
void initialize() {
    unschedule()
    log.info "${app.name} initializing"
    if (!settings.email || !settings.password) {
        log.error 'Unable to connect because login and password are required'
        return
    }

    authenticate()
}

/*
 * Switch Capability
 */
void componentOn(DeviceWrapper device) {
    log.info "Turning ${device.displayName} on"
    skill(
        'control',
        'turnOnOff',
        [
            devId: device.deviceNetworkId,
            value: 1
        ],
        [ switch: newEvent('switch', 'on') ]
    )
}

void componentOff(DeviceWrapper device) {
    log.info "Turning ${device.displayName} off"
    skill(
        'control',
        'turnOnOff',
        [
            devId: device.deviceNetworkId,
            value: 0
        ],
        [ switch: newEvent('switch', 'off') ]
    )
}

/*
 * SwitchLevel Capability
 */
/* groovylint-disable-next-line UnusedMethodParameter */
void componentSetLevel(DeviceWrapper device, BigDecimal level, BigDecimal duration = 0) {
    log.info "Setting ${device.displayName} brightness to ${level}%"
    String colorMode = device.currentValue('colorMode')
    if (colorMode == 'CT') {
        skill(
            'control',
            'brightnessSet',
            [
                devId: device.deviceNetworkId,
                value: level
            ],
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
void componentSetColorTemperature(DeviceWrapper device, BigDecimal kelvin) {
    int value = kelvin
    log.info "Setting ${device.displayName} temperature to ${value}K"
    skill(
        'control',
        'colorTemperatureSet',
        [
            devId: device.deviceNetworkId,
            value: value
        ],
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
void componentSetColor(DeviceWrapper device, Map colormap) {
    log.info "Setting ${device.displayName} color to ${colormap}"
    Map value = [
        hue: Math.round(colormap.hue * 3.60),
        saturation: Math.round(colormap.saturation * 2.55),
        brightness: Math.round(colormap.level * 2.55)
    ]
    skill(
        'control',
        'colorSet',
        [
            devId: device.deviceNetworkId,
            color: value
        ],
        [
            hue: newEvent('hue', colormap.hue),
            saturation: newEvent('saturation', colormap.saturation),
            level: newEvent('level', colormap.level),
            colorName: newEvent('colorName', getGenericName([colormap.hue, colormap.saturation, colormap.level])),
            colorMode: newEvent('colorMode', 'RGB')
        ]
    )
}

void componentSetHue(DeviceWrapper device, BigDecimal hue) {
    log.info "Setting ${device.displayName} hue to ${hue}"
    /* groovylint-disable-next-line UnnecessarySetter */
    componentSetColor([
        hue: hue,
        saturation: device.currentValue('saturation'),
        level: device.currentValue('level') ?: 100
    ])
}

void componentSetSaturation(DeviceWrapper device, BigDecimal saturation) {
    log.info "Setting ${device.displayName} saturation to ${saturation}"
    /* groovylint-disable-next-line UnnecessarySetter */
    componentSetColor([
        hue: device.currentValue('hue') ?: 100,
        saturation: saturation,
        level: device.currentValue('level') ?: 100
    ])
}

/**
 *  Light Effects Capability
 */

void setEffect(DeviceWrapper device, BigDecimal id) {
    if (logEnable) { log.debug "Setting effect ${id}" }
    skill(
        'control',
        'colorMode',
        [
            devId: device.deviceNetworkId,
            value: "scene${id}"
        ],
        [ level: newEvent('effect', id) ]
    )
}

void componentSetNextEffect(DeviceWrapper device) {
    int currentEffect = device.currentValue('effect') ?: 0
    currentEffect++
    if (currentEffect > lightEffects.size()) { currentEffect = 1 }
    componentSetEffect(device, currentEffect)
}

void componentSetPreviousEffect(DeviceWrapper device) {
    int currentEffect = device.currentValue('effect') ?: 0
    currentEffect--
    if (currentEffect < 1) { currentEffect = lightEffects.size() }
    componentSetEffect(device, currentEffect)
}

void componentRefresh(DeviceWrapper device) {
    log.debug "${device.displayName} refresh (not implemented)"
    device.sendEvent(name: 'lightEffects', value: JsonOutput.toJson(lightEffects))
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
            runIn((state.token.expires_in as int) / 2, 'authenticate')
            discovery()
        } else if (response.json && response.json.errorMsg) {
            log.error response.json.errorMsg
            runIn(90, 'authenticate')
        }
    } else if (response.status == 401 || response.status == 400) {
        log.error 'Authentication failed! Check email/password and try again.'
        state.token = null
    } else {
        log.error "Tuya returned HTTP status ${response.status}"
        runIn(90, 'authenticate')
    }
}

private void discovery() {
    unschedule('discovery')
    skill('discovery', 'Discovery')
    runIn(630, 'discovery')
}

private void discoveryHandler(List devices) {
    devices.each { deviceData ->
        String driver = getDeviceDriver(deviceData.ha_type)
        String dni = deviceData.id
        String devicename = deviceData.name
        ChildDeviceWrapper device = getOrCreateDevice(driver, dni, devicename)
        if (device) {
            parse(dni, deviceData.data)
        } else {
            log.error "Discovery: Unable to create driver ${driver} for ${devicename}"
        }
    }
}

private String getDeviceDriver(String type) {
    switch (type) {
        case 'light':
            return 'Generic Component RGBW'
    }
}

private ChildDeviceWrapper getOrCreateDevice(String driverName, String deviceNetworkId, String name) {
    ChildDeviceWrapper childDevice = getChildDevice(deviceNetworkId)
    if (!childDevice) {
        log.info "Discovery: Creating child device ${name} [${deviceNetworkId}] (${driverName})"
        childDevice = addChildDevice(
            'hubitat',
            driverName,
            deviceNetworkId,
            [
                name: name,
                //isComponent: true
            ]
        )
    } else if (logEnable) {
        log.debug "Discovery: Found child device ${name} [${deviceNetworkId}] (${driverName})"
    }

    if (childDevice.name != name) { childDevice.name = name }
    if (childDevice.label == null) { childDevice.label = name }

    return childDevice
}

private Map newEvent(String name, Object value, String unit = '') {
    String splitName = splitCamelCase(name).toLowerCase()
    String description = "${splitName} is ${value}${unit}"
    return [
        name: name,
        value: value,
        descriptionText: description,
        unit: unit
    ]
}

private void parse(String dni, Map dps) {
    ChildDeviceWrapper device = getChildDevice(dni)
    String colorMode = device.currentValue('colorMode')
    List<Map> events = []

    if (dps.containsKey('state')) {
        events << newEvent('switch', dps['state'] == 'true' ? 'on' : 'off')
    }

    if (dps.containsKey('online')) {
        setOnline(device, dps['online'])
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
        if (device.currentValue(e.name) != e.value) {
            device.parse(e)
        }
    }
}

private void setOnline(ChildDeviceWrapper device, Boolean online) {
    String indicator = ' (Offline)'
    if (online && device.label && device.label.endsWith(indicator)) {
        device.label -= indicator
        log.info "${device} being marked as online"
        return
    }

    if (!online && device.label && !device.label.endsWith(indicator)) {
        device.label += indicator
        log.info "${device} being marked as offline"
        device.parse([ newEvent(device, 'switch', 'off') ])
    }
}

private void skill(String namespace, String command, Map payload = [:], Map events = null) {
    payload['accessToken'] = state.token.access_token

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
    asynchttpPost('skillHandler', params, [ payload: payload, events: events ])
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void skillHandler(AsyncResponse response, Map data) {
    if (response.status == 200) {
        if (logEnable) { log.debug "Tuya API returned: ${response.data}" }
        if (response.json && response.json['header']['code'] == 'SUCCESS') {
            if (data.events && data.payload['devId']) {
                ChildDeviceWrapper device = getChildDevice(data.payload['devId'])
                data.events.each { k, v -> device.sendEvent(v) }
            }
            if (response.json['payload']['devices']) {
                discoveryHandler(response.json['payload']['devices'])
            }
        } else if (response.json && response.json['header']['msg']) {
            log.error 'Tuya API: ' + response.json['header']['msg']
        }
    } else {
        log.error "Tuya returned HTTP status ${response.status}"
    }
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

