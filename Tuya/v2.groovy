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

/*
 *  Changelog:
 */

import com.hubitat.app.exception.UnknownDeviceTypeException
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import hubitat.helper.HexUtils
import hubitat.scheduling.AsyncResponse

metadata {
    definition (name: 'Tuya Cloud IoT Platform', namespace: 'tuya', author: 'Jonathan Bradshaw') {
        capability 'Initialize'
        capability 'Refresh'

        command 'removeChildDevices'

        attribute 'deviceCount', 'number'
        attribute 'state', 'enum', [
            'not configured',
            'error',
            'authenticating',
            'authenticated',
            'connected',
            'disconnected',
            'ready'
        ]
    }

    preferences {
        section {
            input name: 'access_id',
                  type: 'text',
                  title: 'Tuya API Access/Client Id',
                  required: true

            input name: 'access_key',
                  type: 'password',
                  title: 'Tuya API Access/Client Secret',
                  required: true

            input name: 'appSchema',
                  title: 'Tuya Application',
                  type: 'enum',
                  required: true,
                  defaultValue: 'tuyaSmart',
                  options: [
                    'tuyaSmart': 'Tuya Smart Life App',
                    'smartlife': 'Smart Life App'
                ]

            input name: 'username',
                  type: 'text',
                  title: 'Tuya Application Login',
                  required: true

            input name: 'password',
                  type: 'password',
                  title: 'Tuya Application Password',
                  required: true

            input name: 'appCountry',
                  title: 'Tuya Application Country',
                  type: 'enum',
                  required: true,
                  defaultValue: 'United States',
                  options: tuyaCountries.country

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: true

            input name: 'txtEnable',
                  type: 'bool',
                  title: 'Enable descriptionText logging',
                  required: false,
                  defaultValue: true
        }
    }
}

// Version of driver
@Field static final String devVersion = '0.2.0'

// Constants
@Field static final Integer maxMireds = 500 // 2000K
@Field static final Integer minMireds = 153 // 6536K

// Device Registration
@Field static final ConcurrentHashMap<String, String> tuyaRoutes = new ConcurrentHashMap<>()

// Jason Parsing Cache
@Field static final ConcurrentHashMap<String, Map> jsonCache = new ConcurrentHashMap<>()

// Random number generator
@Field static final Random random = new Random()

// Tuya Datacenter Country Mapping
@Field static final List<Map> tuyaCountries = getTuyaCountries()

/*
 * Tuya default attributes used if missing from device details
 */
@Field static final Map defaults = [
    'battery_percentage': [ min: 0, max: 100, scale: 0, step: 1, unit: '%', type: 'Integer' ],
    'bright_value': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'bright_value_v2': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'co2': [ min: 0, max: 1000, scale: 1, step: 1, type: 'Integer' ],
    'fanSpeed': [ min: 1, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'fanSpeedPercent': [ min: 1, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'temp_value': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'temp_value_v2': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'colour_data': [
        h: [ min: 1, scale: 0, max: 360, step: 1, type: 'Integer' ],
        s: [ min: 1, scale: 0, max: 255, step: 1, type: 'Integer' ],
        v: [ min: 1, scale: 0, max: 255, step: 1, type: 'Integer' ]
    ],
    'colour_data_v2': [
        h: [ min: 1, scale: 0, max: 360, step: 1, type: 'Integer' ],
        s: [ min: 1, scale: 0, max: 1000, step: 1, type: 'Integer' ],
        v: [ min: 1, scale: 0, max: 1000, step: 1, type: 'Integer' ]
    ],
    'humidity_value': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'temp_current': [ min: -400, max: 2000, scale: 1, step: 1, unit: '°C', type: 'Integer' ],
    'va_humidity': [ min: 0, max: 1000, scale: 1, step: 1, type: 'Integer' ],
    'va_temperature': [ min: 0, max: 1000, scale: 1, step: 1, type: 'Integer' ]
]

/* ------------------------------------------------------------------
 * Tuya category code map to one or more drivers and Hubitat devices
 */
static Map<String, Map> getCategoryMap(String category) {
    switch (category) {
        // String Lights (https://developer.tuya.com/en/docs/iot/dc?id=Kaof7taxmvadu)
        // Strip Lights (https://developer.tuya.com/en/docs/iot/dd?id=Kaof804aibg2l)
        // Ambient Light (https://developer.tuya.com/en/docs/iot/ambient-light?id=Kaiuz06amhe6g)
        // Motion Sensor Light (https://developer.tuya.com/en/docs/iot/gyd?id=Kaof8a8hycfmy)
        // Solar Light (https://developer.tuya.com/en/docs/iot/tynd?id=Kaof8j02e1t98)
        // Ceiling Light (https://developer.tuya.com/en/docs/iot/ceiling-light?id=Kaiuz03xxfc4r)
        case 'dc':
        case 'dd':
        case 'fwd':
        case 'gyd':
        case 'tyndj':
        case 'xdd':
            return [
                'switch': [ name: 'Plug' ], // RGB light socket
                'switch_led': [
                    driver: 'Generic Component RGBW',
                    colorMode: [ 'work_mode' ],
                    brightness: [ 'bright_value' ],
                    colorTemp: [ 'temp_value' ],
                    colorData: [ 'colour_data' ]
                ]
            ]
        // Humidifier Light (https://developer.tuya.com/en/docs/iot/categoryjsq?id=Kaiuz1smr440b)
        case 'jsq':
            return [
                'switch_led': [
                    driver: 'Generic Component RGB',
                    colorMode: [ 'work_mode' ],
                    brightness: [ 'bright_value' ],
                    colorData: [ 'colour_data' ]
                ]
            ]
        // Remote Control (https://developer.tuya.com/en/docs/iot/ykq?id=Kaof8ljn81aov)
        case 'ykq':
            return [
                'switch_controller': [
                    driver: 'Generic Component RGB',
                    colorMode: [ 'work_mode' ],
                    brightness: [ 'bright_controller' ],
                    colorTemp: [ 'temp_controller' ]
                ]
            ]
        // Switch Backlight (https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)
        case 'kg':
            return [
                'switch_backlight': [ name: 'Backlight' ]
            ]
        // Air conditioner light (https://developer.tuya.com/en/docs/iot/categorykt?id=Kaiuz0z71ov2n)
        // Socket light (https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)
        // Power Socket light (https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)
        case 'kg':
        case 'cz':
        case 'pc':
            return [
                'light': [ name: 'Light' ]
            ]
        // Smart Camera Light (https://developer.tuya.com/en/docs/iot/categorysp?id=Kaiuz35leyo12)
        case 'sp':
            return [
                'floodlight_switch': [ name: 'Floodlight' ],
                'basic_indicator': [ name: 'Indicator' ],
            ]
        // Dimmer Switch (https://developer.tuya.com/en/docs/iot/categorytgkg?id=Kaiuz0ktx7m0o)
        case 'tgkg':
            return [
                'switch_led_1': [ name: 'Light' ],
                'switch_led_2': [ name: 'Light 2' ],
                'switch_led_3': [ name: 'Light 3' ],
            ]
        // Dimmer (https://developer.tuya.com/en/docs/iot/tgq?id=Kaof8ke9il4k4)
        case 'tgq':
            String driver = 'Generic Component Dimmer'
            return [
                'switch_led_1': [ name: 'Light', driver: driver, brightness: [ 'bright_value_1' ] ],
                'switch_led_2': [ name: 'Light 2', driver: driver, brightness: [ 'bright_value_2' ] ],
                'switch_led_3': [ name: 'Light 3', driver: driver, brightness: [ 'bright_value_3' ] ]
            ]
        // Light (https://developer.tuya.com/en/docs/iot/categorydj?id=Kaiuyzy3eheyy)
        case 'dj':
            return [
                'switch': [ name: 'Plug' ], // RGB light socket
                'switch_led': [
                    driver: 'Generic Component RGBW',
                    colorMode: [ 'work_mode' ],
                    brightness: [ 'bright_value_v2', 'bright_value' ],
                    colorTemp: [ 'temp_value_v2', 'temp_value' ],
                    colorData: [ 'colour_data_v2', 'colour_data' ]
                ]
        ]
        // Switch (https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)
        // Power Socket (https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)
        case 'kg':
        case 'pc':
        case 'sz':
            return [
                'switch': [ ],
                'switch_1': [ name: 'Socket 1' ],
                'switch_2': [ name: 'Socket 2' ],
                'switch_3': [ name: 'Socket 3' ],
                'switch_4': [ name: 'Socket 4' ],
                'switch_5': [ name: 'Socket 5' ],
                'switch_6': [ name: 'Socket 6' ],
                'switch_usb1': [ name: 'USB 1' ],
                'switch_usb2': [ name: 'USB 2' ],
                'switch_usb3': [ name: 'USB 3' ],
                'switch_usb4': [ name: 'USB 4' ],
                'switch_usb5': [ name: 'USB 5' ],
                'switch_usb6': [ name: 'USB 6' ],
            ]
        // Fan (https://developer.tuya.com/en/docs/iot/categoryfs?id=Kaiuz1xweel1c)
        // Ceiling Fan Light (https://developer.tuya.com/en/docs/iot/fsd?id=Kaof8eiei4c2v)
        // Air Purifier (https://developer.tuya.com/en/docs/iot/categorykj?id=Kaiuz1atqo5l7)
        case 'fs':
        case 'fsd':
        case 'kj':
            return [
                'switch': [
                    name: 'Fan',
                    driver: 'Generic Component Fan',
                    speed: [ 'fan_speed' ],
                    speedPercent: [ 'fan_speed_percent' ]
                ],
                'light': [
                    name: 'Light',
                    driver: 'Generic Component CT',
                    colorMode: [ 'work_mode' ],
                    brightness: [ 'bright_value' ],
                    colorTemp: [ 'temp_value' ]
                 ]
            ]
        // Curtain Motor (https://developer.tuya.com/en/docs/iot/f?id=K9gf46o5mtfyc)
        case 'cl':
        case 'clkg':
            return [
                'control': [
                    namespace: 'component',
                    driver: 'Generic Component Window Shade'
                ]
            ]
        // Residential lock (https://developer.tuya.com/en/docs/iot/f?id=Kb0o2vbzuzl81)
        // Safe box
        // Business lock
        // Residential lock pro
        // Hotel lock
        // Lock accessories
        // Smart lock
        // Access control
        // Video intercom lock
        // Audio and video lock
        case 'ms':
        case 'bxx':
        case 'gyms':
        case 'jtmspro':
        case 'hotelms':
        case 'ms_category':
        case 'jtmsbh':
        case 'mk':
        case 'videolock':
        case 'photolock':
            return [
                'alarm_lock': [
                    driver: 'Generic Component Lock',
                    unlock: [ 'unlock_fingerprint', 'unlock_password', 'unlock_temporary',
                              'unlock_dynamic', 'unlock_card', 'unlock_lock', 'unlock_remote' ],
                ]
            ]
        // CO2 Detector (https://developer.tuya.com/en/docs/iot/categoryco2bj?id=Kaiuz3wes7yuy)
        case 'co2bj':
            return [
                'co2_value': [
                    driver: 'Generic Component Carbon Dioxide Detector',
                    co2: [ 'co2_value' ],
                    battery: [ 'battery_percentage', 'va_battery' ],
                ]
            ]
        // CO Detector (https://developer.tuya.com/en/docs/iot/categorycobj?id=Kaiuz3u1j6q1v)
        case 'cobj':
            return [
                'co_value': [
                    driver: 'Generic Component Carbon Monoxide Detector',
                    co: [ 'co_value' ],
                    battery: [ 'battery_percentage', 'va_battery' ],
                ]
            ]
        // PIR Detector (https://developer.tuya.com/en/docs/iot/categorypir?id=Kaiuz3ss11b80)
        case 'pir':
            return [
                'pir': [
                    driver: 'Generic Component Motion Sensor',
                    motion: [ 'pir' ],
                    battery: [ 'battery_percentage', 'va_battery' ],
                ]
            ]
        // Smoke Detector (https://developer.tuya.com/en/docs/iot/categoryywbj?id=Kaiuz3f6sf952)
        case 'ywbj':
            return [
                'smoke_sensor_status': [
                    driver: 'Generic Component Smoke Detector',
                    smoke: [ 'smoke_sensor_status' ],
                    battery: [ 'battery_percentage', 'va_battery' ],
                ]
            ]
        // Water Detector (https://developer.tuya.com/en/docs/iot/categorysj?id=Kaiuz3iub2sli)
        case 'sj':
            return [
                'watersensor_state': [
                    driver: 'Generic Component Water Sensor',
                    water: [ 'watersensor_state' ],
                    battery: [ 'battery_percentage', 'va_battery' ],
                ]
            ]
        // Door / Window Sensor (https://developer.tuya.com/en/docs/iot/s?id=K9gf48hm02l8m)
        case 'mcs':
            return [
                'doorcontact_state': [
                    driver: 'Generic Component Contact Sensor',
                    contact: [ 'doorcontact_state' ],
                    battery: [ 'battery_percentage', 'va_battery' ],
                ]
            ]
        // Luminance Sensor (https://developer.tuya.com/en/docs/iot/categoryldcg?id=Kaiuz3n7u69l8)
        // Temperature and Humidity Sensor (https://developer.tuya.com/en/docs/iot/categoryldcg?id=Kaiuz3n7u69l8)
        case 'ldcg':
            return [
                'bright_value': [
                    driver: 'Generic Component Omni Sensor',
                    brightness: [ 'bright_value' ],
                    co2: [ 'co2_value' ],
                    humidity: [ 'humidity_value' ],
                    temperature: [ 'temp_current' ],
                    battery: [ 'battery_percentage', 'va_battery' ],
                ],
            ]
        case 'wsdcg':
            return [
                'va_temperature': [
                    driver: 'Generic Component Omni Sensor',
                    temperature: [ 'va_temperature' ],
                    brightness: [ 'bright_value' ],
                    humidity: [ 'va_humidity' ],
                    battery: [ 'battery_percentage', 'va_battery' ],
                ]
            ]
        // Scene Switch (TS004F in 'Device trigger' mode only; TS0044)
        case 'wxkg':
            return [
                'switch1_value': [
                    driver: 'Generic Component Central Scene Switch',
                    switches: [ 'switch1_value', 'switch2_value', 'switch3_value', 'switch4_value' ],
                    modes: [ 'switch_mode2', 'switch_mode3', 'switch_mode4' ],
                    battery: [ 'battery_percentage'],
                ]
            ]
    }
}

/* -----------------------------------------------------
 * Convert Tuya state updates to Device attribute events
 */
void updateDeviceState(String id, List<Map> updates) {
    // Group Hubitat devices by matched state code
    Map groups = updates.groupBy { s -> tuyaRoutes[id + s.code] }
    groups.remove(null) // remove states that were not mapped to hubitat device
    if (logEnable) { log.debug "${device} update groups: ${groups}" }

    groups.each { dni, stateList ->
        ChildDeviceWrapper dw = getChildDevice(dni)
        if (dw) {
            String category = dw.getDataValue('category')
            String code = dw.getDataValue('code')
            Map mapping = getCategoryMap(category)[code]
            Map states = stateList.collectEntries { s -> [ s.code, s.value ] }
            switch (mapping.driver) {
                case 'Generic Component RGB':
                case 'Generic Component RGBW':
                    updateRgbwState(category, code, dw, mapping, states)
                    break
                case 'Generic Component Switch':
                    updateSwitchState(category, code, dw, mapping, states)
                    break
                case 'Generic Component Dimmer':
                    updateLevelState(category, code, dw, mapping, states)
                    break
                case 'Generic Component Fan':
                    updateFanState(category, code, dw, mapping, states)
                    break
                case 'Generic Component Window Shade':
                    updateShadeState(category, code, dw, mapping, states)
                    break
                case 'Generic Component Lock':
                    updateLockState(category, code, dw, mapping, states)
                    break
                case 'Generic Component Motion Sensor':
                case 'Generic Component Carbon Monoxide Detector':
                case 'Generic Component Carbon Dioxide Detector':
                case 'Generic Component Smoke Detector':
                case 'Generic Component Water Sensor':
                case 'Generic Component Contact Sensor':
                case 'Generic Component Omni Sensor':
                    updateSensorState(category, code, dw, mapping, states)
                    break
                case 'Generic Component Central Scene Switch':
                    updateSceneSwitchState(category, code, dw, mapping, states)
                    break
            }
        } else {
            log.warn "${device} updateDeviceState: Child device ${dni} for ${id} ${state.code} is missing"
        }
    }
}

// Called by updateState to process Component Switch changes
static void updateFanState(String category, String code, DeviceWrapper device, Map mapping, Map states) {
    if (states.containsKey(code)) {
        String value = states[code] ? 'on' : 'off'
        device.parse([ [ name: 'switch', value: value, descriptionText: "switch is ${value}" ] ])
    }

    Map statusSet = getMap(device, 'statusSet')
    String fanSpeed = mapping.fanSpeed?.find { k -> k in states }
    String fanSpeedPercent = mapping.fanSpeedPercent?.find { k -> k in states }

    if (fanSpeed) {
        Map tag = statusSet[fanSpeed] ?: defaults[fanSpeed]
        Integer value
        switch (tag.type) {
            case 'Enum':
                value = remap(tag.range.indexOf(states[fanSpeed].value), 0, tag.range.size() - 1, 0, 4) as int
                break
            case 'Integer':
                value = remap(states[fanSpeed].value as int, tag.min, tag.max, 0, 4) as int
                break
        }
        String level = ['low', 'medium-low', 'medium', 'medium-high', 'high'].get(value)
        device.parse([ [ name: 'speed', value: level, descriptionText: "speed is ${level}" ] ])
    } else if (fanSpeedPercent) {
        Map tag = statusSet[fanSpeedPercent] ?: defaults[fanSpeedPercent]
        Integer value = remap(states[fanSpeedPercent].value as int, tag.min, tag.max, 0, 4) as int
        String level = ['low', 'medium-low', 'medium', 'medium-high', 'high'].get(value)
        device.parse([ [ name: 'speed', value: level, descriptionText: "speed is ${level}" ] ])
    }
}

// Called by updateState to process Component level changes
static void updateLevelState(String category, String code, DeviceWrapper device, Map mapping, Map states) {
    Map statusSet = getMap(device, 'statusSet')
    String brightness = mapping.brightness?.find { k -> k in states }
    if (brightness) {
        Map tag = statusSet[brightness] ?: defaults[brightness]
        Integer value = Math.floor(remap(states[brightness].value, tag.min, tag.max, 0, 100))
        device.parse([ [ name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%" ] ])
    }
}

// Called by updateState to process Component lock changes
static void updateLockState(String category, String code, DeviceWrapper device, Map mapping, Map states) {
    Map statusSet = getMap(device, 'statusSet')
    String unlock = mapping.unlock?.find { k -> k in states }
    if (unlock) {
        device.parse([ [ name: 'lock', value: 'unlocked', descriptionText: "lock is unlocked" ] ])
    }
}

// Called by updateState to process Component RGBW Light changes
static void updateRgbwState(String category, String code, DeviceWrapper device, Map mapping, Map states) {
    List<Map> events = []
    if (states.containsKey(code)) {
        String value = states[code] ? 'on' : 'off'
        events << [ name: 'switch', value: value, descriptionText: "switch is ${value}" ]
    }

    Map statusSet = getMap(device, 'statusSet')
    String colorMode = mapping.colorMode?.find { k -> k in states }
    String colorData = mapping.colorData?.find { k -> k in states }
    String colorTemp = mapping.colorTemp?.find { k -> k in states }
    String brightness = mapping.brightness?.find { k -> k in states }

    if (colorMode && device.hasAttribute('colorMode')) {
        switch (states[colorMode]) {
            case 'white':
            case 'light_white':
                events << [ name: 'colorMode', value: 'CT', descriptionText: 'color mode is CT' ]
                break
            case 'colour':
                events << [ name: 'colorMode', value: 'RGB', descriptionText: 'color mode is RGB' ]
                break
        }
    }

    if (colorTemp) {
        Map tag = statusSet[colorTemp] ?: defaults[colorTemp]
        Integer value = Math.floor(1000000 / remap(tag.max - states[colorTemp].value,
                                tag.min, tag.max, minMireds, maxMireds))
        events << [ name: 'colorTemperature', value: value, unit: 'K',
                    descriptionText: "color temperature is ${value}K" ]
    }

    if (brightness && (states[colorMode] == 'white' || !colorData)) {
        Map tag = statusSet[brightness] ?: defaults[brightness]
        Integer value = Math.floor(remap(states[brightness].value, tag.min, tag.max, 0, 100))
        events << [ name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%" ]
    }

    if (colorData) {
        Map tag = statusSet[colorData] ?: defaults[colorData]
        Map value = new JsonSlurper().parseText(states[colorData])
        Integer hue = Math.floor(remap(value.h, tag.h.min, tag.h.max, 0, 100))
        Integer saturation = Math.floor(remap(value.s, tag.s.min, tag.s.max, 0, 100))
        Integer level = Math.floor(remap(value.v, tag.v.min, tag.v.max, 0, 100))
        String colorName = translateColorName(hue, saturation)
        events << [ name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}" ]
        events << [ name: 'hue', value: hue, descriptionText: "hue is ${hue}" ]
        events << [ name: 'saturation', value: saturation, descriptionText: "saturation is ${saturation}" ]
        if (states[colorMode] != 'white') {
            events << [ name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%" ]
        }
    }

    device.parse(events)
}

// Called by updateState to process Component Scene Switch changes
private static void updateSceneSwitchState(String category, String code, DeviceWrapper device, Map mapping, Map states) {
    List<Map> events = []
    Map statusSet = getMap(device, 'statusSet') ?: defaults[category]
    String battery = mapping.battery?.find { k -> k in states }
    String switchNum = mapping.switches?.find { k -> k in states }
    String modes = mapping.modes?.find { k -> k in states }

    if (battery && device.hasAttribute('battery')) {
        Integer value = remap(states[battery] / Math.pow(10, statusSet[battery].scale as int),
                              statusSet[battery].min, statusSet[battery].max, 0, 100)
        String unit = statusSet[battery].unit
        events << [ name: 'battery', value: value, descriptionText: "battery is ${value}${unit}", unit: unit ]
    }

    int button = 0
    String action
    switch (switchNum) {
        case 'switch1_value': button = 4; break // '4'for TS004F and '1' for TS0044 !
        case 'switch2_value': button = 3; break // TS004F - match the key numbering as in Hubitat built-in TS0044 driver
        case 'switch3_value': button = 1; break
        case 'switch4_value': button = 2; break
    }

    if (device.getDataValue('product_id') == 'vp6clf9d' && switchNum == 'switch1_value') {
        button = 1                    // correction for TS0044 key #1
    }

    switch (modes) {
        case 'switch_mode2': button = 2; break
        case 'switch_mode3': button = 3; break
        case 'switch_mode4': button = 4; break
    }

    switch (states[switchNum ?: modes]) {
        case 'single_click'  : action = 'pushed'; break             // TS004F
        case 'double_click'  : action = 'doubleTapped'; break
        case 'long_press'    : action = 'held'; break
        case 'click'         : action = 'pushed'; break             // TS0044
        case 'double_click'  : action = 'doubleTapped'; break
        case 'press'         : action = 'held'; break
    }

    if (button && action) {
        events << [ name: action, value: button, descriptionText: "button ${button} is ${action}", isStateChange: true ]
    }

    device.parse(events)
}

// Called by updateState to process Component Switch changes
private static void updateSwitchState(String category, String code, DeviceWrapper device, Map mapping, Map states) {
    if (states.containsKey(code)) {
        String value = states[code] ? 'on' : 'off'
        device.parse([ [ name: 'switch', value: value, descriptionText: "switch is ${value}" ] ])
    }
}

// Called by updateState to process Component Sensor changes
static void updateSensorState(String category, String code, DeviceWrapper device, Map mapping, Map states) {
    List<Map> events = []
    Map statusSet = getMap(device, 'statusSet') ?: defaults[category]
    String battery = mapping.battery?.find { k -> k in states }
    String brightness = mapping.brightness?.find { k -> k in states }
    String co = mapping.co?.find { k -> k in states }
    String co2 = mapping.co2?.find { k -> k in states }
    String contact = mapping.contact?.find { k -> k in states }
    String humidity = mapping.humidity?.find { k -> k in states }
    String motion = mapping.motion?.find { k -> k in states }
    String temperature = mapping.temperature?.find { k -> k in states }
    String water = mapping.water?.find { k -> k in states }

    if (battery && device.hasAttribute('battery')) {
        Integer value = remap(states[battery] / Math.pow(10, statusSet[battery].scale as int),
                              statusSet[battery].min, statusSet[battery].max, 0, 100)
        String unit = statusSet[battery].unit
        events << [ name: 'battery', value: value, descriptionText: "battery is ${value}${unit}", unit: unit ]
    }

    if (brightness && device.hasAttribute('illuminance')) {
        BigDecimal value = states[brightness]
        events << [ name: 'illuminance', value: value, descriptionText: "illuminance is ${value} lux", unit: 'lux' ]
    }

    if (co && device.hasAttribute('carbonMonoxide')) {
        String value = states[co] == 'alarm' ? 'detected' : 'clear'
        events << [ name: 'carbonMonoxide', value: value, descriptionText: "carbonMonoxide is ${value}" ]
    }

    if (co2 && device.hasAttribute('carbonDioxide')) {
        Integer value = states[co2] / Math.pow(10, statusSet[co2].scale as int)
        events << [ name: 'carbonDioxide', value: value, unit: 'ppm', descriptionText: "carbonDioxide is ${value} ppm" ]
    }

    if (contact && device.hasAttribute('contact')) {
        String value = states[contact] ? 'open' : 'closed'
        events << [ name: 'contact', value: value, descriptionText: "contact is ${value}" ]
    }

    if (humidity && device.hasAttribute('humidity')) {
        Integer value = remap(states[humidity] / Math.pow(10, statusSet[humidity].scale as int),
                              statusSet[humidity].min, statusSet[humidity].max, 0, 100)
        String unit = statusSet[humidity].unit
        events << [ name: 'humidity', value: value, descriptionText: "humidity is ${value}${unit}", unit: unit ]
    }

    if (motion && device.hasAttribute('motion')) {
        String value = states[motion] ? 'active' : 'inactive'
        events << [ name: 'water', value: value, descriptionText: "water is ${value}" ]
    }

    if (temperature && device.hasAttribute('temperature')) {
        Integer value = states[temperature] / Math.pow(10, statusSet[temperature].scale as int)
        String unit = statusSet[temperature].unit
        events << [ name: 'temperature', value: value, descriptionText: "temperature is ${value}${unit}", unit: unit ]
    }

    if (water && device.hasAttribute('water')) {
        String value = states[water] == 'alarm' ? 'wet' : 'dry'
        events << [ name: 'water', value: value, descriptionText: "water is ${value}" ]
    }

    device.parse(events)
}

// Called by updateState to process Component Window Shade changes
static void updateShadeState(String category, String code, DeviceWrapper device, Map mapping, Map states) {
    if (states.containsKey(code)) {
        String value
        switch (states[code].value) {
            case 'open': value = 'open'; break
            case 'opening': value = 'opening'; break
            case 'close': value = 'closed'; break
            case 'closing': value = 'closing'; break
            case 'stop': value = 'unknown'; break
        }
        if (value) {
            device.parse([ [ name: 'windowShade', value: value, descriptionText: "window shade is ${value}" ] ])
        }
    }
}

/* -------------------------------------------------------
 * Implementation of component commands from child devices
 */

// Component command to open device
void componentClose(DeviceWrapper dw) {
    String code = dw.getDataValue('code')
    if (code) {
        log.info "Opening ${dw}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': 'close' ])
    }
}

// Component command to cycle fan speed
void componentCycleSpeed(DeviceWrapper dw) {
    switch (dw.currentValue('speed')) {
        case 'low':
        case 'medium-low':
            componentSetSpeed(dw, 'medium')
            break
        case 'medium':
        case 'medium-high':
            componentSetSpeed(dw, 'high')
            break
        case 'high':
            componentSetSpeed(dw, 'low')
            break
    }
}

// Component command to lock device
void componentLock(DeviceWrapper dw) {
    log.warn 'componentLock not yet supported'
}

// Component command to turn on device
void componentOn(DeviceWrapper dw) {
    String code = dw.getDataValue('code')
    if (code) {
        log.info "Turning ${dw} on"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': true ])
    }
}

// Component command to turn off device
void componentOff(DeviceWrapper dw) {
    String code = dw.getDataValue('code')
    if (code) {
        log.info "Turning ${dw} off"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': false ])
    }
}

// Component command to open device
void componentOpen(DeviceWrapper dw) {
    String code = dw.getDataValue('code')
    if (code) {
        log.info "Opening ${dw}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': 'open' ])
    }
}

// Component command to refresh device
void componentRefresh(DeviceWrapper dw) {
    String id = dw.getDataValue('id')
    if (id && dw.getDataValue('functions')) {
        log.info "Refreshing ${dw} (${id})"
        tuyaGetStateAsync(id)
    }
}

// Component command to set color
void componentSetColor(DeviceWrapper dw, Map colorMap) {
    String code = dw.getDataValue('code')
    Map mapping = getCategoryMap(dw.getDataValue('category'))
    Map<String, Map> functions = getMap(dw, 'functions')
    String colorMode = functions.keySet().find { k -> k in mapping[code].colorMode }
    String colorData = functions.keySet().find { k -> k in mapping[code].colorData }

    if (colorData) {
        Map color = functions[colorData] ?: defaults[colorData]
        Map value = [
            h: remap(colorMap.hue, 0, 100, color.h.min, color.h.max),
            s: remap(colorMap.saturation, 0, 100, color.s.min, color.s.max),
            v: remap(colorMap.level, 0, 100, color.v.min, color.v.max)
        ]
        log.info "Setting ${dw} color to ${colorMap}"
        List<Map> commands = [ [ 'code': colorData, 'value': value ] ]
        if (colorMode) { commands += [ 'code': colorMode, 'value': 'colour'] }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), commands)
    }
}

// Component command to set color temperature
void componentSetColorTemperature(DeviceWrapper dw, BigDecimal kelvin,
                                  BigDecimal level = null, BigDecimal duration = null) {
    String code = dw.getDataValue('code')
    Map mapping = getCategoryMap(dw.getDataValue('category'))
    Map<String, Map> functions = getMap(dw, 'functions')
    String colorMode = functions.keySet().find { k -> k in mapping[code].colorMode }
    String colorTemp = functions.keySet().find { k -> k in mapping[code].colorTemp }

    if (colorTemp) {
        Map temp = functions[colorTemp] ?: defaults[colorTemp]
        Integer value = temp.max - Math.ceil(remap(1000000 / kelvin, minMireds, maxMireds, temp.min, temp.max))
        log.info "Setting ${dw} color temperature to ${kelvin}K"
        List<Map> commands = [ [ 'code': colorTemp, 'value': value ] ]
        if (colorMode) { commands += [ 'code': colorMode, 'value': 'white'] }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), commands)
    }
    if (level && dw.currentValue('level') != level) {
        componentSetLevel(dw, level, duration)
    }
}

// Component command to set heating setpoint
void componentSetHeatingSetpoint(DeviceWrapper dw, BigDecimal temperature) {
    String code = dw.getDataValue('code')
    Map mapping = getCategoryMap(dw.getDataValue('category'))
    Map<String, Map> functions = getMap(dw, 'functions')
    String temperatureSet = functions.keySet().find { k -> k in mapping[code].temperatureSet }
    if (temperatureSet) {
        log.info "Setting ${dw} heating set point to ${temperature}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': temperatureSet, 'value': temperature ])
    }
}

// Component command to set hue
void componentSetHue(DeviceWrapper dw, BigDecimal hue) {
    componentSetColor(dw, [
        hue: hue,
        saturation: dw.currentValue('saturation'),
        level: dw.currentValue('level')
    ])
}

// Component command to set level
/* groovylint-disable-next-line UnusedMethodParameter */
void componentSetLevel(DeviceWrapper dw, BigDecimal level, BigDecimal duration = 0) {
    String colorMode = dw.currentValue('colorMode') ?: 'CT'

    if (colorMode == 'CT') {
        String code = dw.getDataValue('code')
        String mapping = getCategoryMap(dw.getDataValue('category'))
        Map<String, Map> functions = getMap(dw, 'functions')
        String brightness = functions.keySet().find { k -> k in mapping[code].brightness }
        if (brightness) {
            Map bright = functions[brightness] ?: defaults[brightness]
            Integer value = Math.ceil(remap(level, 0, 100, bright.min, bright.max))
            log.info "Setting ${dw} level to ${level}%"
            tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': brightness, 'value': value ])
        }
    } else {
        componentSetColor(dw, [
            hue: dw.currentValue('hue'),
            saturation: dw.currentValue('saturation'),
            level: level
        ])
    }
}

// Component command to set position
void componentSetPosition(DeviceWrapper dw, BigDecimal position) {
    String code = dw.getDataValue('code')
    Map mapping = getCategoryMap(dw.getDataValue('category'))
    Map<String, Map> functions = getMap(dw, 'functions')
    String percentControl = functions.keySet().find { k -> k in mapping[code].percentControl }
    if (percentControl) {
        log.info "Setting ${dw} position to ${position}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': percentControl, 'value': position as Integer ])
    }
}

// Component command to set saturation
void componentSetSaturation(DeviceWrapper dw, BigDecimal saturation) {
    componentSetColor(dw, [
        hue: dw.currentValue('hue'),
        saturation: saturation,
        level: dw.currentValue('level')
    ])
}

// Component command to set fan speed
void componentSetSpeed(DeviceWrapper dw, String speed) {
    String code = dw.getDataValue('code')
    Map mapping = getCategoryMap(dw.getDataValue('category'))
    Map<String, Map> functions = getMap(dw, 'functions')
    String fanSpeed = functions.keySet().find { k -> k in mapping[code].speed }
    String fanSpeedPercent = functions.keySet().find { k -> k in mapping[code].speedPercent }

    if (fanSpeed || fanSpeedPercent) {
        log.info "Setting speed to ${speed}"
        switch (speed) {
            case 'on':
                tuyaSendDeviceCommandsAsync(id, [ 'code': code, 'value': true ])
                break
            case 'off':
                tuyaSendDeviceCommandsAsync(id, [ 'code': code, 'value': false ])
                break
            case 'auto':
                log.warn 'Speed level auto is not supported'
                break
            default:
                if (fanSpeed) {
                    Map speedFunc = functions[fanSpeed] ?: defaults[fanSpeed]
                    int speedVal = ['low', 'medium-low', 'medium', 'medium-high', 'high'].indexOf(speed)
                    String value
                    switch (speedFunc.type) {
                        case 'Enum':
                            value = speedFunc.range[remap(speedVal, 0, 4, 0, speedFunc.range.size() - 1) as int]
                            break
                        case 'Integer':
                            value = remap(speedVal, 0, 4, speedFunc.min as int, speedFunc.max as int)
                            break
                        default:
                            log.warn "Unknown fan speed function type ${speedFunc}"
                            return
                    }
                    tuyaSendDeviceCommandsAsync(id, [ 'code': fanSpeed, 'value': value ])
                } else if (fanSpeedPercent) {
                    Map speedFunc = functions[fanSpeedPercent] ?: defaults[fanSpeedPercent]
                    int speedVal = ['low', 'medium-low', 'medium', 'medium-high', 'high'].indexOf(speed)
                    Integer value = remap(speedVal, 0, 4, speedFunc.min as int, speedFunc.max as int)
                    tuyaSendDeviceCommandsAsync(id, [ 'code': fanSpeedPercent, 'value': value ])
                }
                break
        }
    }
}

// Component command to start level change (up or down)
void componentStartLevelChange(DeviceWrapper dw, String direction) {
    levelChanges[dw.deviceNetworkId] = (direction == 'down') ? -10 : 10
    log.info "Starting level change ${direction} for ${dw}"
    runInMillis(1000, 'doLevelChange')
}

// Component command to stop level change
void componentStopLevelChange(DeviceWrapper dw) {
    log.info "Stopping level change for ${dw}"
    levelChanges.remove(dw.deviceNetworkId)
}

// Component command to set position direction
void componentStartPositionChange(DeviceWrapper dw, String direction) {
    switch (direction) {
        case 'open': componentOpen(dw); break
        case 'close': componentClose(dw); break
        default:
            log.warn "${device.displayName} Unknown position change direction ${direction} for ${dw}"
            break
    }
}

// Component command to stop position change
void componentStopPositionChange(DeviceWrapper dw) {
    String code = dw.getDataValue('code')
    if (code) {
        log.info "Opening ${dw}"
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), [ 'code': code, 'value': 'stop' ])
    }
}

// Component command to unlock device
void componentUnlock(DeviceWrapper dw) {
    log.warn 'componentUnlock not yet supported'
}

/* ------------------------------------------ */

// Called when the device is started
void initialize() {
    log.info "${device} driver initializing"
    interfaces.mqtt.disconnect()
    state.clear()
    unschedule()

    state.with {
        tokenInfo = [ access_token: '', expire: now() ] // initialize token
        uuid = state?.uuid ?: UUID.randomUUID().toString()
        driver_version = devVersion
        lang = 'en'
    }

    sendEvent([ name: 'deviceCount', value: 0 ])
    Map datacenter = tuyaCountries.find { c -> c.country == settings.appCountry }
    if (datacenter) {
        state.endPoint = "https://openapi.tuya${datacenter.endPoint}.com"
        state.countryCode = datacenter.countryCode
    } else {
        log.error "${device} Country not set in configuration"
        sendEvent([ name: 'state', value: 'error', descriptionText: 'Country not set in configuration'])
    }

    tuyaAuthenticateAsync()
}

// Called when the device is first created
void installed() {
    log.info "${device} driver installed"
}

// Called when the device is removed
void uninstalled() {
    log.info "${device} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    log.info "${device} driver configuration updated"
    if (logEnable) {
        log.debug settings
        runIn(1800, 'logsOff')
    }

    initialize()
}

// Called to decrypt and parse received MQTT data
void parse(String data) {
    Map payload = new JsonSlurper().parseText(interfaces.mqtt.parseMessage(data).payload)
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(state.mqttInfo.password[8..23].bytes, 'AES'))
    Map result = new JsonSlurper().parse(cipher.doFinal(payload.data.decodeBase64()), 'UTF-8')
    if (logEnable) { log.debug "${device} received ${result}" }
    if (result.status && result.devId) {
        updateDeviceState(result.devId, result.status)
    } else if (result.bizCode && result.bizData) {
        parseBizData(result.bizCode, result.bizData)
    } else {
        log.warn "${device} unsupported mqtt packet: ${result}"
    }
}

// Called to parse MQTT client status changes
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            sendEvent([ name: 'state', value: 'connected', descriptionText: 'Connected to Tuya MQTT hub'])
            runInMillis(1000, 'tuyaHubSubscribeAsync')
            break
        default:
            log.error "${device} MQTT connection error: " + status
            sendEvent([ name: 'state', value: 'disconnected', descriptionText: 'Disconnected from Tuya MQTT hub'])
            runIn(15 + random.nextInt(45), 'tuyaGetHubConfigAsync')
            break
    }
}

// Command to refresh all devices
void refresh() {
    log.info "${device} refreshing devices"
    tuyaGetDevicesAsync()
}

// Command to remove all the child devices
void removeChildDevices() {
    log.info "${device} removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

// Create country map entry from parameters
private static Map country(String country, String countryCode, String endPoint = 'us') {
    return [ country: country, countryCode: countryCode, endPoint: endPoint ]
}

// Returns map from json data using cache for peformance
private static Map getMap(DeviceWrapper dw, String name) {
    return jsonCache.computeIfAbsent(dw.id + name) {
        k -> new JsonSlurper().parseText(dw.getDataValue(name) ?: '{}')
    }
}

// Tuya Countries (https://developer.tuya.com/en/docs/iot/oem-app-data-center-distributed?id=Kafi0ku9l07qb)
private static List<Map> getTuyaCountries() { [
    country('Afghanistan', '93', 'eu'),
    country('Albania', '355', 'eu'),
    country('Algeria', '213', 'eu'),
    country('American Samoa', '1-684', 'eu'),
    country('Andorra', '376', 'eu'),
    country('Angola', '244', 'eu'),
    country('Anguilla', '1-264', 'eu'),
    country('Antarctica', '672', 'us'),
    country('Antigua and Barbuda', '1-268', 'eu'),
    country('Argentina', '54', 'us'),
    country('Armenia', '374', 'eu'),
    country('Aruba', '297', 'eu'),
    country('Australia', '61', 'eu'),
    country('Austria', '43', 'eu'),
    country('Azerbaijan', '994', 'eu'),
    country('Bahamas', '1-242', 'eu'),
    country('Bahrain', '973', 'eu'),
    country('Bangladesh', '880', 'eu'),
    country('Barbados', '1-246', 'eu'),
    country('Belarus', '375', 'eu'),
    country('Belgium', '32', 'eu'),
    country('Belize', '501', 'eu'),
    country('Benin', '229', 'eu'),
    country('Bermuda', '1-441', 'eu'),
    country('Bhutan', '975', 'eu'),
    country('Bolivia', '591', 'us'),
    country('Bosnia and Herzegovina', '387', 'eu'),
    country('Botswana', '267', 'eu'),
    country('Brazil', '55', 'us'),
    country('British Indian Ocean Territory', '246', 'us'),
    country('British Virgin Islands', '1-284', 'eu'),
    country('Brunei', '673', 'eu'),
    country('Bulgaria', '359', 'eu'),
    country('Burkina Faso', '226', 'eu'),
    country('Burundi', '257', 'eu'),
    country('Cambodia', '855', 'eu'),
    country('Cameroon', '237', 'eu'),
    country('Canada', '1', 'us'),
    country('Capo Verde', '238', 'eu'),
    country('Cayman Islands', '1-345', 'eu'),
    country('Central African Republic', '236', 'eu'),
    country('Chad', '235', 'eu'),
    country('Chile', '56', 'us'),
    country('China', '86', 'cn'),
    country('Christmas Island', '61'),
    country('Cocos Islands', '61'),
    country('Colombia', '57', 'us'),
    country('Comoros', '269', 'eu'),
    country('Cook Islands', '682', 'us'),
    country('Costa Rica', '506', 'eu'),
    country('Croatia', '385', 'eu'),
    country('Cuba', '53'),
    country('Curacao', '599', 'us'),
    country('Cyprus', '357', 'eu'),
    country('Czech Republic', '420', 'eu'),
    country('Democratic Republic of the Congo', '243', 'eu'),
    country('Denmark', '45', 'eu'),
    country('Djibouti', '253', 'eu'),
    country('Dominica', '1-767', 'eu'),
    country('Dominican Republic', '1-809', 'us'),
    country('East Timor', '670', 'us'),
    country('Ecuador', '593', 'us'),
    country('Egypt', '20', 'eu'),
    country('El Salvador', '503', 'eu'),
    country('Equatorial Guinea', '240', 'eu'),
    country('Eritrea', '291', 'eu'),
    country('Estonia', '372', 'eu'),
    country('Ethiopia', '251', 'eu'),
    country('Falkland Islands', '500', 'us'),
    country('Faroe Islands', '298', 'eu'),
    country('Fiji', '679', 'eu'),
    country('Finland', '358', 'eu'),
    country('France', '33', 'eu'),
    country('French Polynesia', '689', 'eu'),
    country('Gabon', '241', 'eu'),
    country('Gambia', '220', 'eu'),
    country('Georgia', '995', 'eu'),
    country('Germany', '49', 'eu'),
    country('Ghana', '233', 'eu'),
    country('Gibraltar', '350', 'eu'),
    country('Greece', '30', 'eu'),
    country('Greenland', '299', 'eu'),
    country('Grenada', '1-473', 'eu'),
    country('Guam', '1-671', 'eu'),
    country('Guatemala', '502', 'us'),
    country('Guernsey', '44-1481'),
    country('Guinea', '224'),
    country('Guinea-Bissau', '245', 'us'),
    country('Guyana', '592', 'eu'),
    country('Haiti', '509', 'eu'),
    country('Honduras', '504', 'eu'),
    country('Hong Kong', '852', 'us'),
    country('Hungary', '36', 'eu'),
    country('Iceland', '354', 'eu'),
    country('India', '91', 'in'),
    country('Indonesia', '62', 'us'),
    country('Iran', '98'),
    country('Iraq', '964', 'eu'),
    country('Ireland', '353', 'eu'),
    country('Isle of Man', '44-1624'),
    country('Israel', '972', 'eu'),
    country('Italy', '39', 'eu'),
    country('Ivory Coast', '225', 'eu'),
    country('Jamaica', '1-876', 'eu'),
    country('Japan', '81', 'us'),
    country('Jersey', '44-1534'),
    country('Jordan', '962', 'eu'),
    country('Kazakhstan', '7', 'eu'),
    country('Kenya', '254', 'eu'),
    country('Kiribati', '686', 'us'),
    country('Kosovo', '383'),
    country('Kuwait', '965', 'eu'),
    country('Kyrgyzstan', '996', 'eu'),
    country('Laos', '856', 'eu'),
    country('Latvia', '371', 'eu'),
    country('Lebanon', '961', 'eu'),
    country('Lesotho', '266', 'eu'),
    country('Liberia', '231', 'eu'),
    country('Libya', '218', 'eu'),
    country('Liechtenstein', '423', 'eu'),
    country('Lithuania', '370', 'eu'),
    country('Luxembourg', '352', 'eu'),
    country('Macao', '853', 'us'),
    country('Macedonia', '389', 'eu'),
    country('Madagascar', '261', 'eu'),
    country('Malawi', '265', 'eu'),
    country('Malaysia', '60', 'us'),
    country('Maldives', '960', 'eu'),
    country('Mali', '223', 'eu'),
    country('Malta', '356', 'eu'),
    country('Marshall Islands', '692', 'eu'),
    country('Mauritania', '222', 'eu'),
    country('Mauritius', '230', 'eu'),
    country('Mayotte', '262', 'eu'),
    country('Mexico', '52', 'us'),
    country('Micronesia', '691', 'eu'),
    country('Moldova', '373', 'eu'),
    country('Monaco', '377', 'eu'),
    country('Mongolia', '976', 'eu'),
    country('Montenegro', '382', 'eu'),
    country('Montserrat', '1-664', 'eu'),
    country('Morocco', '212', 'eu'),
    country('Mozambique', '258', 'eu'),
    country('Myanmar', '95', 'us'),
    country('Namibia', '264', 'eu'),
    country('Nauru', '674', 'us'),
    country('Nepal', '977', 'eu'),
    country('Netherlands', '31', 'eu'),
    country('Netherlands Antilles', '599'),
    country('New Caledonia', '687', 'eu'),
    country('New Zealand', '64', 'us'),
    country('Nicaragua', '505', 'eu'),
    country('Niger', '227', 'eu'),
    country('Nigeria', '234', 'eu'),
    country('Niue', '683', 'us'),
    country('North Korea', '850'),
    country('Northern Mariana Islands', '1-670', 'eu'),
    country('Norway', '47', 'eu'),
    country('Oman', '968', 'eu'),
    country('Pakistan', '92', 'eu'),
    country('Palau', '680', 'eu'),
    country('Palestine', '970', 'us'),
    country('Panama', '507', 'eu'),
    country('Papua New Guinea', '675', 'us'),
    country('Paraguay', '595', 'us'),
    country('Peru', '51', 'us'),
    country('Philippines', '63', 'us'),
    country('Pitcairn', '64'),
    country('Poland', '48', 'eu'),
    country('Portugal', '351', 'eu'),
    country('Puerto Rico', '1-787, 1-939', 'us'),
    country('Qatar', '974', 'eu'),
    country('Republic of the Congo', '242', 'eu'),
    country('Reunion', '262', 'eu'),
    country('Romania', '40', 'eu'),
    country('Russia', '7', 'eu'),
    country('Rwanda', '250', 'eu'),
    country('Saint Barthelemy', '590', 'eu'),
    country('Saint Helena', '290'),
    country('Saint Kitts and Nevis', '1-869', 'eu'),
    country('Saint Lucia', '1-758', 'eu'),
    country('Saint Martin', '590', 'eu'),
    country('Saint Pierre and Miquelon', '508', 'eu'),
    country('Saint Vincent and the Grenadines', '1-784', 'eu'),
    country('Samoa', '685', 'eu'),
    country('San Marino', '378', 'eu'),
    country('Sao Tome and Principe', '239', 'us'),
    country('Saudi Arabia', '966', 'eu'),
    country('Senegal', '221', 'eu'),
    country('Serbia', '381', 'eu'),
    country('Seychelles', '248', 'eu'),
    country('Sierra Leone', '232', 'eu'),
    country('Singapore', '65', 'eu'),
    country('Sint Maarten', '1-721', 'us'),
    country('Slovakia', '421', 'eu'),
    country('Slovenia', '386', 'eu'),
    country('Solomon Islands', '677', 'us'),
    country('Somalia', '252', 'eu'),
    country('South Africa', '27', 'eu'),
    country('South Korea', '82', 'us'),
    country('South Sudan', '211'),
    country('Spain', '34', 'eu'),
    country('Sri Lanka', '94', 'eu'),
    country('Sudan', '249'),
    country('Suriname', '597', 'us'),
    country('Svalbard and Jan Mayen', '4779', 'us'),
    country('Swaziland', '268', 'eu'),
    country('Sweden', '46', 'eu'),
    country('Switzerland', '41', 'eu'),
    country('Syria', '963'),
    country('Taiwan', '886', 'us'),
    country('Tajikistan', '992', 'eu'),
    country('Tanzania', '255', 'eu'),
    country('Thailand', '66', 'us'),
    country('Togo', '228', 'eu'),
    country('Tokelau', '690', 'us'),
    country('Tonga', '676', 'eu'),
    country('Trinidad and Tobago', '1-868', 'eu'),
    country('Tunisia', '216', 'eu'),
    country('Turkey', '90', 'eu'),
    country('Turkmenistan', '993', 'eu'),
    country('Turks and Caicos Islands', '1-649', 'eu'),
    country('Tuvalu', '688', 'eu'),
    country('U.S. Virgin Islands', '1-340', 'eu'),
    country('Uganda', '256', 'eu'),
    country('Ukraine', '380', 'eu'),
    country('United Arab Emirates', '971', 'eu'),
    country('United Kingdom', '44', 'eu'),
    country('United States', '1', 'us'),
    country('Uruguay', '598', 'us'),
    country('Uzbekistan', '998', 'eu'),
    country('Vanuatu', '678', 'us'),
    country('Vatican', '379', 'eu'),
    country('Venezuela', '58', 'us'),
    country('Vietnam', '84', 'us'),
    country('Wallis and Futuna', '681', 'eu'),
    country('Western Sahara', '212', 'eu'),
    country('Yemen', '967', 'eu'),
    country('Zambia', '260', 'eu'),
    country('Zimbabwe', '263', 'eu')
]}

// Remap a value from old range to a new range
private static BigDecimal remap(BigDecimal fromValue, BigDecimal fromMin, BigDecimal fromMax,
                                BigDecimal toMin, BigDecimal toMax, Integer decimals = 1) {
    BigDecimal value = fromValue
    if (fromMin == fromMax || toMin == toMax) { return fromValue }
    if (value < fromMin) { value = fromMin }
    if (value > fromMax) { value = fromMax }
    BigDecimal newValue = ( (value - fromMin) / (fromMax - fromMin) ) * (toMax - toMin) + toMin
    return newValue.setScale(decimals, BigDecimal.ROUND_HALF_UP)
}

private static String translateColorName(Integer hue, Integer saturation) {
    if (saturation < 1) {
        return 'White'
    }

    switch (hue * 3.6 as int) {
        case 0..15: return 'Red'
        case 16..45: return 'Orange'
        case 46..75: return 'Yellow'
        case 76..105: return 'Chartreuse'
        case 106..135: return 'Green'
        case 136..165: return 'Spring'
        case 166..195: return 'Cyan'
        case 196..225: return 'Azure'
        case 226..255: return 'Blue'
        case 256..285: return 'Violet'
        case 286..315: return 'Magenta'
        case 316..345: return 'Rose'
        case 346..360: return 'Red'
    }

    return ''
}

// Utility function to handle multiple level changes
private void doLevelChange() {
    List active = levelChanges.collect() // copy list locally
    active.each { kv ->
        ChildDeviceWrapper dw = getChildDevice(kv.key)
        if (dw) {
            int newLevel = (dw.currentValue('level') as int) + kv.value
            if (newLevel < 0) { newLevel = 0 }
            if (newLevel > 100) { newLevel = 100 }
            componentSetLevel(dw, newLevel)
            if (newLevel <= 0 && newLevel >= 100) {
                componentStopLevelChange(device)
            }
        } else {
            levelChanges.remove(kv.key)
        }
    }

    if (!levelChanges.isEmpty()) {
        runInMillis(1000, 'doLevelChange')
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "${device} debug logging disabled"
}

private void parseBizData(String bizCode, Map bizData) {
    if (logEnable) { log.debug "${device} ${bizCode} ${bizData}" }
    switch (bizCode) {
        case 'nameUpdate':
        case 'online':
        case 'offline':
        case 'bindUser':
            refresh()
            break
    }
}

// Create one or multiple Hubitat Devices from Tuya Device
private void tuyaCreateDevices(Map tuyaDevice) {
    Map categoryMap = getCategoryMap(tuyaDevice.category) ?: [:]
    Set deviceCodes = (tuyaDevice.functions + tuyaDevice.statusSet).keySet()
    Map subdevices = categoryMap.findAll { entry -> entry.key in deviceCodes }

    // Iterate through each device from mapping
    subdevices.each { code, mapping ->
        String dni = "${device.id}-${tuyaDevice.id}"
        if (mapping.name) { dni += code }
        ChildDeviceWrapper dw = getChildDevice(dni)
        String name = tuyaDevice.product_name
        String label = tuyaDevice.name + (mapping.name ? " ${mapping.name}" : '')
        String driver = mapping.driver ?: 'Generic Component Switch'
        String namespace = mapping.namespace ?: 'hubitat'
        if (!dw) {
            log.info "${device} creating ${tuyaDevice.name} ${mapping.name ?: 'device'} with ${driver} (${dni})"
            try {
                dw = addChildDevice(namespace, driver, dni, [ name: name, label: label ])
            } catch (UnknownDeviceTypeException e) {
                log.warn "${device} ${e.message} - you may need to install the driver"
            }
        } else {
            dw.label = dw.label ?: label
        }

        // Set data values on Hubitat device
        if (dw) {
            dw.with {
                updateDataValue 'id', tuyaDevice.id
                updateDataValue 'local_key', tuyaDevice.local_key
                updateDataValue 'product_id', tuyaDevice.product_id
                updateDataValue 'category', tuyaDevice.category
                updateDataValue 'code', code
                updateDataValue 'functions', JsonOutput.toJson(tuyaDevice.functions)
                updateDataValue 'statusSet', JsonOutput.toJson(tuyaDevice.statusSet)
                updateDataValue 'online', tuyaDevice.online as String
            }

            // Add routes to in-memory table used to match state updates to the right device
            List<String> registrations = [code] + mapping.values().findAll { v -> v instanceof List }.flatten()
            registrations.each { c -> tuyaRoutes[tuyaDevice.id + c] = dni }
        }
    }
}

/**
 *  Tuya Open API Authentication
 *  https://developer.tuya.com/en/docs/cloud/
*/
private void tuyaAuthenticateAsync() {
    unschedule('tuyaAuthenticateAsync')
    if (settings.username && settings.password && settings.appSchema && settings.countryCode) {
        log.info "${device} starting Tuya cloud authentication for ${settings.username}"
        MessageDigest digest = MessageDigest.getInstance('MD5')
        String md5pwd = HexUtils.byteArrayToHexString(digest.digest(settings.password.bytes)).toLowerCase()
        Map body = [
            'country_code': settings.countryCode,
            'username': settings.username,
            'password': md5pwd,
            'schema': settings.appSchema
        ]
        state.tokenInfo.access_token = ''
        sendEvent([ name: 'state', value: 'authenticating', descriptionText: 'Authenticating to Tuya'])
        tuyaPostAsync('/v1.0/iot-01/associated-users/actions/authorized-login', body, 'tuyaAuthenticateResponse')
    } else {
        sendEvent([ name: 'state', value: 'not configured', descriptionText: 'Driver not configured'])
        log.error "${device} must be configured before authentication is possible"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaAuthenticateResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) {
        sendEvent([ name: 'state', value: 'error', descriptionText: 'Error authenticating to Tuya (check credentials and country)'])
        runIn(60 + random.nextInt(300), 'tuyaAuthenticateAsync')
        return
    }

    Map result = response.json.result
    state.endPoint = result.platform_url
    state.tokenInfo = [
        access_token: result.access_token,
        refresh_token: result.refresh_token,
        uid: result.uid,
        expire: result.expire_time * 1000 + now(),
    ]
    log.info "${device} received Tuya access token (valid for ${result.expire_time}s)"
    sendEvent([ name: 'state', value: 'authenticated', descriptionText: 'Authenticated to Tuya'])

    // Schedule next authentication
    runIn(result.expire_time - 60, 'tuyaAuthenticateAsync')

    // Get MQTT details
    tuyaGetHubConfigAsync()
}

/**
 *  Tuya Open API Device Management
 *  https://developer.tuya.com/en/docs/cloud/
 *
 *  Attributes:
 *      id: Device id
 *      name: Device name
 *      local_key: Key
 *      category: Product category
 *      product_id: Product ID
 *      product_name: Product name
 *      sub: Determine whether it is a sub-device, true-> yes; false-> no
 *      uuid: The unique device identifier
 *      asset_id: asset id of the device
 *      online: Online status of the device
 *      icon: Device icon
 *      ip: Device external IP
 *      time_zone: device time zone
 *      active_time: The last pairing time of the device
 *      create_time: The first network pairing time of the device
 *      update_time: The update time of device status
 *      status: Status set of the device
 */
private void tuyaGetDevicesAsync(String last_row_key = '', Map data = [:]) {
    if (!jsonCache.isEmpty()) {
        log.info "${device} clearing json cache"
        jsonCache.clear()
    }

    log.info "${device} requesting cloud devices batch"
    tuyaGetAsync('/v1.0/iot-01/associated-users/devices', [
        'last_row_key': last_row_key
    ], 'tuyaGetDevicesResponse', data)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDevicesResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response)) {
        Map result = response.json.result
        data.devices = (data.devices ?: []) + result.devices
        log.info "${device} received ${result.devices.size()} cloud devices (has_more: ${result.has_more})"
        if (result.has_more) {
            pauseExecution(1000)
            tuyaGetDevicesAsync(result.last_row_key, data)
            return
        }
    }

    sendEvent([ name: 'deviceCount', value: data.devices?.size() ])
    data.devices.each { d ->
        tuyaGetDeviceSpecificationsAsync(d.id, d)
    }
}

// https://developer.tuya.com/en/docs/cloud/device-control?id=K95zu01ksols7#title-29-API%20address
private void tuyaGetDeviceSpecificationsAsync(String deviceID, Map data = [:]) {
    log.info "${device} requesting cloud device specifications for ${deviceID}"
    tuyaGetAsync("/v1.0/devices/${deviceID}/specifications", null, 'tuyaGetDeviceSpecificationsResponse', data)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDeviceSpecificationsResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    JsonSlurper parser = new JsonSlurper()
    Map result = response.json.result
    data.category = result.category
    if (result.functions) {
        data.functions = result.functions.collectEntries { f ->
            Map values = parser.parseText(f.values ?: '{}')
            values.type = f.type
            return [ (f.code): values ]
        }
    } else {
        data.functions = [:]
    }

    if (result.status) {
        data.statusSet = result.status.collectEntries { f ->
            Map values = parser.parseText(f.values ?: '{}')
            values.type = f.type
            return [ (f.code): values ]
        }
    } else {
        data.statusSet = [:]
    }

    tuyaCreateDevices(data)
    updateDeviceState(data.id, data.status)

    if (device.currentValue('state') != 'ready') {
        sendEvent([ name: 'state', value: 'ready', descriptionText: 'Received device data from Tuya'])
    }
}

private void tuyaGetStateAsync(String deviceID) {
    if (logEnable) { log.debug "${device} requesting device ${deviceID} state" }
    tuyaGetAsync("/v1.0/devices/${deviceID}/status", null, 'tuyaGetStateResponse', [ id: deviceID ])
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetStateResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    data.status = response.json.result
    updateDeviceState(data.id, data.status)
}

private void tuyaSendDeviceCommandsAsync(String deviceID, Map...params) {
    if (logEnable) { log.debug "${device} device ${deviceID} command ${params}" }
    if (!state?.tokenInfo?.access_token) {
        log.error "${device} Unable to send Tuya device command (Access token not valid, failed login?)"
        sendEvent([ name: 'state', value: 'error', descriptionText: 'Access token not set (failed login?)'])
        return
    }

    tuyaPostAsync("/v1.0/devices/${deviceID}/commands", [ 'commands': params ], 'tuyaSendDeviceCommandsResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaSendDeviceCommandsResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) {
        sendEvent([ name: 'state', value: 'error', descriptionText: 'Error sending device command'])
        runIn(5, 'tuyaHubConnectAsync')
    }
}

/**
 *  Tuya Open API MQTT Hub
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetHubConfigAsync() {
    log.info "${device} requesting Tuya MQTT configuration"
    Map body = [
        'uid': state.tokenInfo.uid,
        'link_id': state.uuid,
        'link_type': 'mqtt',
        'topics': 'device',
        'msg_encrypted_version': '1.0'
    ]

    tuyaPostAsync('/v1.0/iot-03/open-hub/access-config', body, 'tuyaGetHubConfigResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetHubConfigResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    state.mqttInfo = result
    tuyaHubConnectAsync()
}

private void tuyaHubConnectAsync() {
    log.info "${device} connecting to Tuya MQTT hub at ${state.mqttInfo.url}"
    try {
        interfaces.mqtt.connect(
            state.mqttInfo.url,
            state.mqttInfo.client_id,
            state.mqttInfo.username,
            state.mqttInfo.password)
    } catch (e) {
        log.error "${device} MQTT connection error: " + e
        runIn(15 + random.nextInt(45), 'tuyaHubConnectAsync')
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void tuyaHubSubscribeAsync() {
    state.mqttInfo.source_topic.each { t ->
        log.info "${device} subscribing to Tuya MQTT hub ${t.key} topic"
        interfaces.mqtt.subscribe(t.value)
    }

    tuyaGetDevicesAsync()
}

/**
 *  Tuya Open API HTTP REST Implementation
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetAsync(String path, Map query, String callback, Map data = [:]) {
    tuyaRequestAsync('get', path, callback, query, null, data)
}

private void tuyaPostAsync(String path, Map body, String callback, Map data = [:]) {
    tuyaRequestAsync('post', path, callback, null, body, data)
}

/* groovylint-disable-next-line ParameterCount */
private void tuyaRequestAsync(String method, String path, String callback, Map query, Map body, Map data) {
    String accessToken = state?.tokenInfo?.access_token ?: ''
    String stringToSign = tuyaGetStringToSign(method, path, query, body)
    long now = now()
    Map headers = [
      't': now,
      'nonce': state.uuid,
      'client_id': access_id,
      'Signature-Headers': 'client_id',
      'sign': tuyaCalculateSignature(accessToken, now, stringToSign),
      'sign_method': 'HMAC-SHA256',
      'access_token': accessToken,
      'lang': state.lang, // use zh for china
      'dev_lang': 'groovy',
      'dev_channel': 'hubitat',
      'devVersion': devVersion
    ]

    Map request = [
        uri: state.endPoint,
        path: path,
        query: query,
        contentType: 'application/json',
        headers: headers,
        body: JsonOutput.toJson(body),
        timeout: 5
    ]

    if (logEnable) {
        log.debug("${device} API ${method.toUpperCase()} ${request}")
    }

    switch (method) {
        case 'get': asynchttpGet(callback, request, data); break
        case 'post': asynchttpPost(callback, request, data); break
    }
}

private boolean tuyaCheckResponse(AsyncResponse response) {
    if (response.hasError()) {
        log.error "${device} cloud request error ${response.getErrorMessage()}"
        sendEvent([ name: 'state', value: 'error', descriptionText: response.getErrorMessage() ])
        return false
    }

    if (response.status != 200) {
        log.error "${device} cloud request returned HTTP status ${response.status}"
        sendEvent([ name: 'state', value: 'error', descriptionText: "Cloud HTTP response ${response.status}" ])
        return false
    }

    if (response.json?.success != true) {
        log.error "${device} cloud API request failed: ${response.data}"
        sendEvent([ name: 'state', value: 'error', descriptionText: response.data ])
        return false
    }

    if (logEnable) {
        log.debug "${device} API response ${response.json ?: response.data}"
    }

    return true
}

private String tuyaCalculateSignature(String accessToken, long timestamp, String stringToSign) {
    String message = access_id + accessToken + timestamp.toString() + state.uuid + stringToSign
    Mac sha256HMAC = Mac.getInstance('HmacSHA256')
    sha256HMAC.init(new SecretKeySpec(access_key.bytes, 'HmacSHA256'))
    return HexUtils.byteArrayToHexString(sha256HMAC.doFinal(message.bytes))
}

private String tuyaGetStringToSign(String method, String path, Map query, Map body) {
    String url = query ? path + '?' + query.sort().collect { key, value -> "${key}=${value}" }.join('&') : path
    String headers = 'client_id:' + access_id + '\n'
    String bodyStream = (body == null) ? '' : JsonOutput.toJson(body)
    MessageDigest sha256 = MessageDigest.getInstance('SHA-256')
    String contentSHA256 = HexUtils.byteArrayToHexString(sha256.digest(bodyStream.bytes)).toLowerCase()
    return method.toUpperCase() + '\n' + contentSHA256 + '\n' + headers + '\n' + url
}
