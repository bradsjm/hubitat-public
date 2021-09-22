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
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.ColorUtils
import hubitat.helper.HexUtils
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.regex.Matcher
import java.util.Random

metadata {
    definition (name: 'Tuya Generic RGBW Light', namespace: 'tuya', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'ChangeLevel'
        capability 'ColorControl'
        capability 'ColorMode'
        capability 'ColorTemperature'
        capability 'Initialize'
        capability 'Light'
        capability 'Switch'
        capability 'SwitchLevel'
        capability 'Refresh'

        command 'sendCustomDps', [
            [
                name: 'Dps',
                type: 'NUMBER'
            ],
            [
                name: 'Value',
                type: 'STRING'
            ]
        ]
    }
}

preferences {
    section {
        input name: 'ipAddress',
              type: 'text',
              title: 'Device IP',
              required: false

        input name: 'sendMode',
              title: 'Communications Mode',
              type: 'enum',
              required: true,
              defaultValue: 'both',
              options: [
                'cloud': 'Cloud (requires internet)',
                'local': 'Local (requires ip)',
                'both':  'Optimized (local with cloud)'
              ]

        input name: 'enableHeartbeat',
              title: 'Enable Heartbeat ping',
              type: 'bool',
              required: true,
              defaultValue: false

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

// Tuya Function Categories
@Field static final Map<String, List<String>> tuyaFunctions = [
    'brightness': [ 'bright_value', 'bright_value_v2', 'bright_value_1' ],
    'colour': [ 'colour_data', 'colour_data_v2' ],
    'switch': [ 'switch_led', 'switch_led_1', 'light' ],
    'temperature': [ 'temp_value', 'temp_value_v2' ],
    'workMode': [ 'work_mode' ]
]

// Constants
@Field static final Integer minMireds = 153
@Field static final Integer maxMireds = 500 // should this be 370?

// Random number generator
@Field static final Random random = new Random()

/**
 *  Hubitat Driver Event Handlers
 */
// Called every 15 - 20 seconds to keep device connection open
void heartbeat() {
    runIn(15 + random.nextInt(5), 'heartbeat')
    tuyaSendCommand(getDataValue('id'), 'HEART_BEAT')
}

// Called when the device is first created
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called to initialize
void initialize() {
    unschedule('heartbeat')
    if (ipAddress && getDataValue('local_key') && enableHeartbeat) {
        heartbeat()
    }
}

// Component command to turn on device
void on() {
    //tuyaSendCommand(getDataValue('id'), [ '1': true ])
    //runIn(1, 'parentCommand', [data: [ name: 'componentOn', args: [] ]])
    if (!tuyaSendCommand(getDataValue('id'), [ '1': true ])) {
        parent?.componentOn(device)
    } else {
        log.info "Turning ${device} on"
    }
}

// Component command to turn off device
void off() {
    if (!tuyaSendCommand(getDataValue('id'), [ '1': false ])) {
        parent?.componentOff(device)
    } else {
        log.info "Turning ${device} off"
    }
}

// parse responses from device
void parse(String message) {
    if (!message) { return }
    String localKey = getDataValue('local_key')
    Map result = tuyaDecode(HexUtils.hexStringToByteArray(message), localKey)
    if (logEnable) { log.debug "${device.displayName} received ${result}" }
    if (result.error) {
        log.error "${device.displayName} received error ${result.error}"
    } else if (result.commandByte == 7 && sendMode == 'local') { // COMMAND ACK
        tuyaSendCommand(getDataValue('id'), 'DP_QUERY')
    } else if (result.commandByte == 8 || result.commandByte == 10 ) { // STATUS or QUERY RESULTS
        Map json = new JsonSlurper().parseText(result.text)
        parseDeviceState(json.dps)
    }
}

// parse commands from parent (cloud)
void parse(List<Map> description) {
    if (ipAddress && sendMode == 'local') { return }
    if (logEnable) { log.debug description }
    description.each { d ->
        if (device.currentValue(d.name) != d.value) {
            if (d.descriptionText && txtEnable) { log.info "${device} ${d.descriptionText}" }
            sendEvent(d)
        }
    }
}

// Component command to refresh device
void refresh() {
    if (!tuyaSendCommand(getDataValue('id'), 'DP_QUERY')) {
        parent?.componentRefresh(device)
    } else {
        log.info "Refreshing ${device}"
    }

}

// Component command to set color
void setColor(Map colorMap) {
    Map functions = getFunctions(device)
    String code = getFunctionByCode(functions, tuyaFunctions.colour)
    Map color = functions[code]
    Map bright = functions['bright_value'] ?: functions['bright_value_v2'] ?: color.v
    int h = remap(colorMap.hue, 0, 100, color.h.min, color.h.max)
    int s = remap(colorMap.saturation, 0, 100, color.s.min, color.s.max)
    int v = remap(colorMap.level, 0, 100, bright.min, bright.max)
    def (int r, int g, int b) = ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level])
    String rgb = Integer.toHexString(r).padLeft(2, '0') +
                 Integer.toHexString(g).padLeft(2, '0') +
                 Integer.toHexString(b).padLeft(2, '0')
    String hsv = Integer.toHexString(h).padLeft(4, '0') +
                 Integer.toHexString(s).padLeft(2, '0') +
                 Integer.toHexString(v).padLeft(2, '0')
    if (!tuyaSendCommand(getDataValue('id'), [ '5': rgb + hsv, '2': 'colour' ])) {
        parent?.setColor(device, colorMap)
    }else {
        log.info "Setting ${device} color to ${colorMap}"
    }
}

// Send custom Dps command
void sendCustomDps(BigDecimal dps, String value) {
    tuyaSendCommand(getDataValue('id'), [ (dps): value ])
    log.info "Sending DPS ${dps} command ${value}"
}

// Component command to set color temperature
void setColorTemperature(BigDecimal kelvin, BigDecimal level = null, BigDecimal duration = null) {
    Map functions = getFunctions(device)
    String code = getFunctionByCode(functions, tuyaFunctions.temperature)
    Map temp = functions[code]
    Integer value = temp.max - Math.ceil(remap(1000000 / kelvin, minMireds, maxMireds, temp.min, temp.max))
    if (!tuyaSendCommand(getDataValue('id'), [ '4': value ])) {
        parent?.componentSetColorTemperature(device, kelvin, level, duration)
    } else {
        log.info "Setting ${device} color temperature to ${kelvin}K"
        if (level && device.currentValue('level') != level) {
            setLevel(level, duration)
        }
    }
}

// Component command to set hue
void setHue(BigDecimal hue) {
    setColor([
        hue: hue,
        saturation: device.currentValue('saturation'),
        level: device.currentValue('level') ?: 100
    ])
}

// Component command to set level
void setLevel(BigDecimal level, BigDecimal duration = 0) {
    String colorMode = device.currentValue('colorMode')
    if (colorMode == 'CT') {
        Map functions = getFunctions(device)
        String code = getFunctionByCode(functions, tuyaFunctions.brightness)
        Map bright = functions[code]
        Integer value = Math.ceil(remap(level, 0, 100, bright.min, bright.max))
        if (!tuyaSendCommand(getDataValue('id'), [ '3': value ])) {
            parent?.componentSetLevel(device, level, duration)
        } else {
            log.info "Setting ${device} level to ${level}%"
        }
    } else {
        setColor([
            hue: device.currentValue('hue'),
            saturation: device.currentValue('saturation'),
            level: level
        ])
    }
}

// Component command to set saturation
void setSaturation(BigDecimal saturation) {
    setColor([
        hue: device.currentValue('hue') ?: 100,
        saturation: saturation,
        level: device.currentValue('level') ?: 100
    ])
}

// Socket status updates
void socketStatus(String message) {
    if (message.contains('error')) {
        log.error "${device.displayName} socket ${message}"
    } else {
        log.info "${device.displayName} socket ${message}"
    }
}

// Start gradual level change
void startLevelChange(String direction) {
    log.info "Starting level change ${direction} for ${device}"
    doLevelChange(delta: (direction == 'down') ? -10 : 10)
}

// Stop gradual level change
void stopLevelChange() {
    log.info "Stopping level change for ${device}"
    unschedule('doLevelChange')
}

void doLevelChange(Integer delta) {
    int newLevel = (device.currentValue('level') as int) + delta
    if (newLevel < 0) { newLevel = 0 }
    if (newLevel > 100) { newLevel = 100 }
    setLevel(newLevel)

    if (newLevel > 0 && newLevel < 100) {
        runInMillis(1000, 'doLevelChange', delta)
    }
}

// Called when the device is removed
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    initialize()
    if (logEnable) { runIn(1800, 'logsOff') }
}

#include tuya.tuyaProtocols

private static Map getFunctions(DeviceWrapper device) {
    return new JsonSlurper().parseText(device.getDataValue('functions'))
}

private static String getFunctionByCode(Map functions, List codes) {
    return codes.find { c -> tuyaFunctions.containsKey(c) } ?: codes.first()
    // TODO: Include default function values
}

private static BigDecimal remap(BigDecimal oldValue, BigDecimal oldMin, BigDecimal oldMax,
                                BigDecimal newMin, BigDecimal newMax) {
    BigDecimal value = oldValue
    if (value < oldMin) { value = oldMin }
    if (value > oldMax) { value = oldMax }
    BigDecimal newValue = ( (value - oldMin) / (oldMax - oldMin) ) * (newMax - newMin) + newMin
    return newValue.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private static String translateColor(Integer hue, Integer saturation) {
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

private void parseDeviceState(Map dps) {
    if (logEnable) { log.debug "${device.displayName} parsing dps ${dps}" }
    Map functions = getFunctions(device)

    String colorMode = device.currentValue('colorMode')
    List<Map> events = []

    if (dps.containsKey('1')) {
        String value = dps['1'] ? 'on' : 'off'
        events << [ name: 'switch', value: value, descriptionText: "switch is ${value}" ]
    }

    // Determine if we are in RGB or CT mode either explicitly or implicitly
    if (dps.containsKey('2')) {
        colorMode = dps['2'] == 'colour' ? 'RGB' : 'CT'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
    } else if (dps.containsKey('4')) {
        colorMode = 'CT'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
    } else if (dps.containsKey('5')) {
        colorMode = 'RGB'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
    }

    if (dps.containsKey('3') && colorMode == 'CT') {
        Map code = functions[getFunctionByCode(functions, tuyaFunctions.brightness)]
        Integer value = Math.floor(remap(dps['3'] as Integer, code.min, code.max, 0, 100))
        events << [ name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%" ]
    }

    if (dps.containsKey('4')) {
        Map code = functions[getFunctionByCode(functions, tuyaFunctions.temperature)]
        Integer value = Math.floor(1000000 / remap(code.max - dps['4'] as Integer,
                                   code.min, code.max, minMireds, maxMireds))
        events << [ name: 'colorTemperature', value: value, unit: 'K',
                    descriptionText: "color temperature is ${value}K" ]
    }

    if (dps.containsKey('5') && colorMode == 'RGB') {
        String value = dps['5']
        // first six characters are RGB values which we ignore and use HSV instead
        Matcher match = value =~ /^.{6}([0-9a-f]{4})([0-9a-f]{2})([0-9a-f]{2})$/
        if (match.find()) {
            Map code = functions[getFunctionByCode(functions, tuyaFunctions.colour)]
            Map bright = functions[getFunctionByCode(functions, tuyaFunctions.brightness)]
            int h = Integer.parseInt(match.group(1), 16)
            int s = Integer.parseInt(match.group(2), 16)
            int v = Integer.parseInt(match.group(3), 16)
            Integer hue = Math.floor(remap(h, code.h.min, code.h.max, 0, 100))
            Integer saturation = Math.floor(remap(s, code.s.min, code.s.max, 0, 100))
            Integer level = Math.floor(remap(v, bright.min, bright.max, 0, 100))
            String colorName = translateColor(hue, saturation)
            events << [ name: 'hue', value: hue, descriptionText: "hue is ${hue}%" ]
            events << [ name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}%" ]
            events << [ name: 'saturation', value: saturation, descriptionText: "saturation is ${saturation}%" ]
            events << [ name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%" ]
        }
    }

    events.each { e ->
        if (device.currentValue(e.name) != e.value) {
            if (e.descriptionText && txtEnable) { log.info e.descriptionText }
            sendEvent(e)
        }
    }
}

