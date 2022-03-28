/**
 *  MIT License
 *  Copyright 2022 Jonathan Bradshaw (jb@nrgup.net)
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
import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import hubitat.helper.ColorUtils
import hubitat.helper.NetworkUtils
import java.math.RoundingMode

/**
 *  This driver is designed to be used with ESPHome template:
 *      https://github.com/bradsjm/esphome/blob/main/templates/lohas-rgbcw-template.yaml
 */

metadata {
    definition(name: 'ESPHome RGBWW Light', namespace: 'esphome', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Bulb'
        capability 'ColorControl'
        capability 'ColorMode'
        capability 'ColorTemperature'
        capability 'EnergyMeter'
        capability 'Flash'
        capability 'HealthCheck'
        capability 'Initialize'
        capability 'Light'
        capability 'LightEffects'
        capability 'PowerMeter'
        capability 'SignalStrength'
        capability 'Switch'
        capability 'SwitchLevel'

        attribute 'state', 'enum', [
            'connecting',
            'connected',
            'disconnecting',
            'disconnected'
        ]

        attribute 'wifiSignal', 'enum', [
            'excellent',
            'good',
            'poor',
            'weak'
        ]

        //command 'connect'
        //command 'disconnect'
        //command 'reset'
    }

    preferences {
        section {
            input name: 'ipAddress',
                  type: 'text',
                  title: 'Device IP Address',
                  required: true
        }

        section {
            input name: 'rampRateOn',
                  type: 'number',
                  title: 'Default on transition seconds',
                  required: false,
                  range: 0..20,
                  defaultValue: 2

            input name: 'rampRateOff',
                  type: 'number',
                  title: 'Default off transition seconds',
                  required: false,
                  range: 0..20,
                  defaultValue: 1

            input name: 'dimLevelMin',
                  type: 'number',
                  title: 'Minimum dim level (0-255)',
                  required: false,
                  range: 0..255,
                  defaultValue: 17

            input name: 'dimLevelMax',
                  type: 'number',
                  title: 'Maximum dim level (1-255)',
                  required: false,
                  range: 1..255,
                  defaultValue: 255

            input name: 'autoOffDelay',
                  type: 'number',
                  title: 'Auto off seconds (0 to disable)',
                  required: false,
                  defaultValue: 0

            input name: 'prestaging',
                  type: 'bool',
                  title: 'Enable level pre-staging',
                  required: true,
                  defaultValue: true

            input name: 'flashColor',
                  type: 'enum',
                  title: 'Select flash color',
                  options: PredefinedColors.keySet()
        }

        section {
            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: false

            // input name: 'logTextEnable',
            //       type: 'bool',
            //       title: 'Enable descriptionText logging',
            //       required: false,
            //       defaultValue: true
        }
    }
}

void connect() {
    LOG.info 'connecting to event stream'
    sendEvent name: 'state', value: 'connecting'
    state.connectCount++
    interfaces.eventStream.connect("http://${settings.ipAddress}/events", [
        pingInterval: 30
    ])
    runIn(15 + (3 * new Random().nextInt(3)), connect)
}

void disconnect() {
    LOG.info 'disconnecting event stream'
    sendEvent name: 'state', value: 'disconnecting'
    interfaces.eventStream.close()
    unschedule(connect)
}

// called with any status messages from the event stream
void eventStreamStatus(String message) {
    switch (message.trim()) {
        case ~/START:.*/:
            LOG.info message
            break
        case ~/STOP:.*/:
            LOG.info message
            sendEvent name: 'state', value: 'disconnected', descriptionText: message
            runIn(15 * (new Random().nextInt(3) + 1), connect)
            break
        case ~/ERROR:.*/:
            LOG.error message
            break
        default:
            LOG.debug message
            break
    }
}

void flash(BigDecimal rate = 1) {
    LOG.info "flash ${rate}"
    if (PredefinedColors.containsKey(settings.flashColor)) {
        def (int r, int g, int b) = ColorUtils.hexToRGB(PredefinedColors[settings.flashColor])
        int max = [ r, g, b ].max()
        if (max > 0) {
            r = (r / max) * 255
            g = (g / max) * 255
            b = (b / max) * 255
        }
        post(state.lightId, 'turn_on', [
            flash: rate as Integer,
            brightness: 255,
            r: r,
            g: g,
            b: b
        ])
    }
}

// Called when the device is started.
void initialize() {
    LOG.info "${device} driver initializing"

    disconnect()        // Disconnect any existing connection
    unschedule()        // Remove all scheduled functions
    reset()             // Reset state
    runIn(3, connect)   // Schedule device connection (which will populate state)

    // Schedule log disable for 30 minutes
    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

// Called when the device is first created.
void installed() {
    LOG.info "${device} driver installed"
}

// Called to disable logging after timeout
void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    LOG.info "${device} debug logging disabled"
}

void off() {
    LOG.info 'turn off'
    post(state.lightId, 'turn_off', [
        transition: settings.rampRateOff ?: 0
    ])
}

void on() {
    LOG.info 'turn on'
    int colorTemperature = device.currentValue('colorTemperature') ?: 2700
    int level = device.currentValue('level') ?: 100
    int hue = device.currentValue('hue') ?: 0
    int saturation = device.currentValue('saturation') ?: 0
    String colorMode = device.currentValue('colorMode') ?: 'CT'

    Map options = [ transition: settings.rampRateOn ?: 0 ]
    if (colorMode == 'CT') {
        options += [
            brightness: convertToBrightness(level),
            color_temp: (1000000f / colorTemperature).round(2)
        ]
    } else {
        def (int r, int g, int b) = ColorUtils.hsvToRGB([ hue, saturation, level ])
        int max = [ r, g, b ].max()
        if (max > 0) {
            r = (r / max) * 255
            g = (g / max) * 255
            b = (b / max) * 255
        }
        options += [
            brightness: convertToBrightness(max),
            r: r, g: g, b: b
        ]
    }

    post(state.lightId, 'turn_on', options)
    if (settings.autoOffDelay > 0) { runIn(settings.autoOffDelay as int, off) }
}

// Called with incoming messages from the event stream server
void parse(String message) {
    if (device.currentValue('state') != 'connected') {
        unschedule(connect)
        sendEvent name: 'state', value: 'connected'
        LOG.info 'connected to event stream'
    }

    if (message.startsWith('{')) {
        LOG.debug "eventstream: ${message}"
        Map data = new JsonSlurper().parseText(message.trim())
        parseEventData(data)
    } else if (message.trim().size() > 0) {
        LOG.debug message
    }
}

void parseEventData(Map eventData) {
    if (eventData['title']) {
        device.name = eventData['title']
        return
    }

    switch (eventData['id']) {
        case ~/sensor-.+_wifi_signal/:
            int rssi = eventData['value']
            String value = translateRssi(rssi)
            sendEvent name: 'wifiSignal', value: value, descriptionText: "wifi signal is ${value}"
            sendEvent name: 'rssi', value: rssi, descriptionText: "wifi signal is ${rssi} dBm", unit: 'dBm'
            break
        case ~/sensor-.+_power_consumption/:
            BigDecimal power = (eventData['value'] as BigDecimal).setScale(1, RoundingMode.HALF_UP)
            sendEvent name: 'power', value: power, descriptionText: "power is ${power} W", unit: 'W'
            break
        case ~/sensor-.+_total_daily_energy/:
            BigDecimal energy = (eventData['value'] as BigDecimal).setScale(3, RoundingMode.HALF_UP)
            sendEvent name: 'energy', value: energy, descriptionText: "energy is ${energy} kWh", unit: 'kWh'
            break
        case ~/sensor-.+/:
            if (eventData['name']) {
                state.sensors[eventData['id']] = eventData['name']
            }
            if (eventData['state'] && state.sensors.containsKey(eventData['id'])) {
                String name = state.sensors[eventData['id']]
                device.updateDataValue(name, eventData['state'])
            }
            break

        case ~/light-.+/:
            state.lightId = eventData['id']
            device.label = eventData['name'] ?: device.label
            if (eventData['state'] in ['ON', 'OFF']) {
                String value = eventData['state'].toLowerCase()
                sendEvent name: 'switch', value: value, descriptionText: "switch is ${value}"
            }

            if (eventData['color_temp']) {
                int value = eventData['color_temp']
                sendEvent name: 'colorTemperature', value: value, descriptionText: "color temperature is ${value}°K", unit: '°K'
            }

            if (eventData['color_mode']) {
                String value = eventData['color_mode'].startsWith('rgb') ? 'RGB' : 'CT'
                sendEvent name: 'colorMode', value: value, descriptionText: "color mode is ${value}"
            }

            if (eventData['effect']) {
                String value = eventData['effect']
                sendEvent name: 'effectName', value: value, descriptionText: "effect name is ${value}"
            }

            if (eventData['effects']) {
                List<String> value = eventData['effects']
                sendEvent name: 'lightEffects', value: JsonOutput.toJson(value), descriptionText: "effects are ${value}"
            }

            if (eventData['color_mode']?.startsWith('rgb') && eventData['color']) {
                Map<String, Integer> color = eventData['color']
                def (int h, int s, int b) = ColorUtils.rgbToHSV([ color['r'], color['g'], color['b'] ])
                b = b * (convertFromBrightness((int)eventData['brightness']) / 100)
                String colorName = translateColorName(h, s)
                sendEvent name: 'hue', value: h, descriptionText: "hue is ${h}"
                sendEvent name: 'saturation', value: s, descriptionText: "saturation is ${s}"
                sendEvent name: 'level', value: b, descriptionText: "level is ${b}%", unit: '%'
                sendEvent name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}"
            } else if (eventData['brightness']) {
                int level = convertFromBrightness((int)eventData['brightness'])
                sendEvent name: 'level', value: level, descriptionText: "level is ${level}%", unit: '%'
            }
            break
        default:
            LOG.debug "Unknown event data: ${eventData}"
            break
    }
}

void ping() {
    NetworkUtils.PingData response = NetworkUtils.ping(settings.ipAddress, 3)
    state.lastPingResult = response
    if (response.packetLoss == response.packetsTransmitted) {
        LOG.error response
        sendEvent name: 'state', value: 'disconnected'
    } else if (response.packetLoss > 0) {
        LOG.warn response
    } else {
        LOG.info response
    }
}

void reset() {
    // Reset device state
    state.connectCount = 0
    state.effectNumber = 0
    state.lastPingResult = ''
    state.lightId = ''
    state.sensors = [:]

    // Reset device state
    sendEvent name: 'switch', value: 'OFF'
    sendEvent name: 'colorTemperature', value: 0
    sendEvent name: 'colorMode', value: 'CT'
    sendEvent name: 'effectName', value: ''
    sendEvent name: 'lightEffects', value: '{}'
    sendEvent name: 'hue', value: 0
    sendEvent name: 'saturation', value: 0
    sendEvent name: 'level', value: 0
    sendEvent name: 'colorName', value: ''

    // Reset data values
    Set<String> keys = device.getData()*.key
    keys.each { k -> device.removeDataValue(k) }
}

void setColor(Map colorMap) {
    LOG.info "setColor ${colorMap}"
    if (device.currentValue('switch') == 'on' || settings.prestaging == false) {
        def (int r, int g, int b) = ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level])
        int max = [ r, g, b ].max()
        if (max > 0) {
            r = (r / max) * 255
            g = (g / max) * 255
            b = (b / max) * 255
        }
        post(state.lightId, 'turn_on', [
            r: r, g: g, b: b,
            brightness: convertToBrightness(max),
            transition: settings.rampRateOn ?: 0
        ])
        if (settings.autoOffDelay > 0) { runIn(settings.autoOffDelay as int, off) }
    } else {
        String colorName = translateColorName(colorMap.hue, colorMap.saturation)
        sendEvent name: 'hue', value: colorMap.hue, descriptionText: "pre-stage hue is ${colorMap.hue}"
        sendEvent name: 'saturation', value: colorMap.saturation, descriptionText: "pre-stage saturation is ${colorMap.saturation}"
        sendEvent name: 'level', value: colorMap.level, descriptionText: "pre-stage level is ${colorMap.level}%", unit: '%'
        sendEvent name: 'colorName', value: colorName, descriptionText: "pre-stage color name is ${colorName}"
        sendEvent name: 'colorMode', value: 'RGB', descriptionText: 'pre-stage color mode is RGB'
    }
}

void setColorTemperature(BigDecimal kelvin, BigDecimal level = null, BigDecimal duration = null) {
    LOG.info "setColorTemperature ${kelvin}"
    if (device.currentValue('switch') == 'on' || settings.prestaging == false) {
        post(state.lightId, 'turn_on', [
            brightness: convertToBrightness(level ?: device.currentValue('level')),
            color_temp: (1000000f / kelvin).round(2),
            transition: duration != null ? duration : (settings.rampRateOn ?: 0)
        ])
        if (settings.autoOffDelay > 0) { runIn(settings.autoOffDelay as int, off) }
    }

    sendEvent name: 'colorTemperature', value: kelvin, descriptionText: "color temperature is ${kelvin}°K", unit: '°K'
    sendEvent name: 'colorMode', value: 'CT', descriptionText: 'color mode is CT'
}

void setEffect(BigDecimal effectNumber) {
    LOG.info "setEffect ${kelvin}"
    int value = effectNumber
    String[] lightEffects = new JsonSlurper().parseText(device.currentValue('lightEffects') ?: '[]')
    if (value < 1) { value = 1 }
    if (value > lightEffects.size()) { value = lightEffects.size() }
    state.effectNumber = value
    post(state.lightId, 'turn_on', [
        effect: lightEffects[value - 1]
    ])
    if (settings.autoOffDelay > 0) { runIn(settings.autoOffDelay as int, off) }
}

void setNextEffect() {
    setEffect(state.effectNumber + 1)
}

void setPreviousEffect() {
    setEffect(state.effectNumber - 1)
}

void setHue(BigDecimal hue) {
    setColor([
        hue: hue,
        saturation: device.currentValue('saturation'),
        level: device.currentValue('level')
    ])
}

void setLevel(BigDecimal level, BigDecimal duration = null) {
    LOG.info "setLevel ${level}"
    if (level == 0) {
        off()
    } else if (device.currentValue('switch') == 'on' || settings.prestaging == false) {
        post(state.lightId, 'turn_on', [
            brightness: convertToBrightness(level),
            transition: duration != null ? duration : (settings.rampRateOn ?: 0)
        ])
        if (settings.autoOffDelay > 0) { runIn(settings.autoOffDelay as int, off) }
    } else {
        sendEvent name: 'level', value: level, descriptionText: "pre-stage level is ${level}"
    }
}

void setSaturation(BigDecimal saturation) {
    setColor([
        hue: device.currentValue('hue') ?: 100,
        saturation: saturation,
        level: device.currentValue('level') ?: 100
    ])
}

// Called when the device is removed.
void uninstalled() {
    disconnect()
    LOG.info "${device} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    LOG.info "${device} driver configuration updated"
    LOG.debug settings
    initialize()
}

@Field private final Map LOG = [
    debug: { s -> if (settings.logEnable) { log.debug(s) } },
    trace: { s -> if (settings.logEnable) { log.trace(s) } },
    info: { s -> log.info(s) },
    warn: { s -> log.warn(s) },
    error: { s -> log.error(s) },
    exception: { message, exception ->
        List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith('user_app') }
        Integer line = relevantEntries[0]?.lineNumber
        String method = relevantEntries[0]?.methodName
        log.error("${message}: ${exception} at line ${line} (${method})")
        if (settings.logEnable) {
            log.debug("App exception stack trace:\n${relevantEntries.join('\n')}")
        }
    }
].asImmutable()

private static BigDecimal remap(BigDecimal oldValue, BigDecimal oldMin, BigDecimal oldMax,
                                BigDecimal newMin, BigDecimal newMax) {
    BigDecimal value = oldValue
    if (value < oldMin) { value = oldMin }
    if (value > oldMax) { value = oldMax }
    BigDecimal newValue = ((value - oldMin) / (oldMax - oldMin)) * (newMax - newMin) + newMin
    return newValue.setScale(1, BigDecimal.ROUND_HALF_UP)
}

@Field static final Map<String, String> PredefinedColors = [
    'Alice Blue': '#F0F8FF',
    'Antique White': '#FAEBD7',
    'Aqua': '#00FFFF',
    'Aquamarine': '#7FFFD4',
    'Azure': '#F0FFFF',
    'Beige': '#F5F5DC',
    'Bisque': '#FFE4C4',
    'Blanched Almond': '#FFEBCD',
    'Blue': '#0000FF',
    'Blue Violet': '#8A2BE2',
    'Brown': '#A52A2A',
    'Burly Wood': '#DEB887',
    'Cadet Blue': '#5F9EA0',
    'Chartreuse': '#7FFF00',
    'Chocolate': '#D2691E',
    'Cool White': '#F3F6F7',
    'Coral': '#FF7F50',
    'Corn Flower Blue': '#6495ED',
    'Corn Silk': '#FFF8DC',
    'Crimson': '#DC143C',
    'Cyan': '#00FFFF',
    'Dark Blue': '#00008B',
    'Dark Cyan': '#008B8B',
    'Dark Golden Rod': '#B8860B',
    'Dark Gray': '#A9A9A9',
    'Dark Green': '#006400',
    'Dark Khaki': '#BDB76B',
    'Dark Magenta': '#8B008B',
    'Dark Olive Green': '#556B2F',
    'Dark Orange': '#FF8C00',
    'Dark Orchid': '#9932CC',
    'Dark Red': '#8B0000',
    'Dark Salmon': '#E9967A',
    'Dark Sea Green': '#8FBC8F',
    'Dark Slate Blue': '#483D8B',
    'Dark Slate Gray': '#2F4F4F',
    'Dark Turquoise': '#00CED1',
    'Dark Violet': '#9400D3',
    'Daylight White': '#CEF4FD',
    'Deep Pink': '#FF1493',
    'Deep Sky Blue': '#00BFFF',
    'Dim Gray': '#696969',
    'Dodger Blue': '#1E90FF',
    'Fire Brick': '#B22222',
    'Floral White': '#FFFAF0',
    'Forest Green': '#228B22',
    'Fuchsia': '#FF00FF',
    'Gainsboro': '#DCDCDC',
    'Ghost White': '#F8F8FF',
    'Gold': '#FFD700',
    'Golden Rod': '#DAA520',
    'Gray': '#808080',
    'Green': '#008000',
    'Green Yellow': '#ADFF2F',
    'Honeydew': '#F0FFF0',
    'Hot Pink': '#FF69B4',
    'Indian Red': '#CD5C5C',
    'Indigo': '#4B0082',
    'Ivory': '#FFFFF0',
    'Khaki': '#F0E68C',
    'Lavender': '#E6E6FA',
    'Lavender Blush': '#FFF0F5',
    'Lawn Green': '#7CFC00',
    'Lemon Chiffon': '#FFFACD',
    'Light Blue': '#ADD8E6',
    'Light Coral': '#F08080',
    'Light Cyan': '#E0FFFF',
    'Light Golden Rod Yellow': '#FAFAD2',
    'Light Gray': '#D3D3D3',
    'Light Green': '#90EE90',
    'Light Pink': '#FFB6C1',
    'Light Salmon': '#FFA07A',
    'Light Sea Green': '#20B2AA',
    'Light Sky Blue': '#87CEFA',
    'Light Slate Gray': '#778899',
    'Light Steel Blue': '#B0C4DE',
    'Light Yellow': '#FFFFE0',
    'Lime': '#00FF00',
    'Lime Green': '#32CD32',
    'Linen': '#FAF0E6',
    'Maroon': '#800000',
    'Medium Aquamarine': '#66CDAA',
    'Medium Blue': '#0000CD',
    'Medium Orchid': '#BA55D3',
    'Medium Purple': '#9370DB',
    'Medium Sea Green': '#3CB371',
    'Medium Slate Blue': '#7B68EE',
    'Medium Spring Green': '#00FA9A',
    'Medium Turquoise': '#48D1CC',
    'Medium Violet Red': '#C71585',
    'Midnight Blue': '#191970',
    'Mint Cream': '#F5FFFA',
    'Misty Rose': '#FFE4E1',
    'Moccasin': '#FFE4B5',
    'Navajo White': '#FFDEAD',
    'Navy': '#000080',
    'Old Lace': '#FDF5E6',
    'Olive': '#808000',
    'Olive Drab': '#6B8E23',
    'Orange': '#FFA500',
    'Orange Red': '#FF4500',
    'Orchid': '#DA70D6',
    'Pale Golden Rod': '#EEE8AA',
    'Pale Green': '#98FB98',
    'Pale Turquoise': '#AFEEEE',
    'Pale Violet Red': '#DB7093',
    'Papaya Whip': '#FFEFD5',
    'Peach Puff': '#FFDAB9',
    'Peru': '#CD853F',
    'Pink': '#FFC0CB',
    'Plum': '#DDA0DD',
    'Powder Blue': '#B0E0E6',
    'Purple': '#800080',
    'Red': '#FF0000',
    'Rosy Brown': '#BC8F8F',
    'Royal Blue': '#4169E1',
    'Saddle Brown': '#8B4513',
    'Salmon': '#FA8072',
    'Sandy Brown': '#F4A460',
    'Sea Green': '#2E8B57',
    'Sea Shell': '#FFF5EE',
    'Sienna': '#A0522D',
    'Silver': '#C0C0C0',
    'Sky Blue': '#87CEEB',
    'Slate Blue': '#6A5ACD',
    'Slate Gray': '#708090',
    'Snow': '#FFFAFA',
    'Soft White': '#B6DA7C',
    'Spring Green': '#00FF7F',
    'Steel Blue': '#4682B4',
    'Tan': '#D2B48C',
    'Teal': '#008080',
    'Thistle': '#D8BFD8',
    'Tomato': '#FF6347',
    'Turquoise': '#40E0D0',
    'Violet': '#EE82EE',
    'Warm White': '#DAF17E',
    'Wheat': '#F5DEB3',
    'White': '#FFFFFF',
    'White Smoke': '#F5F5F5',
    'Yellow': '#FFFF00',
    'Yellow Green': '#9ACD32'
].asImmutable()

private static String translateRssi(BigDecimal signal) {
    if (signal <= 0 && signal >= -70) {
        return 'excellent'
    } else if (signal < -70 && signal >= -80) {
        return 'good'
    } else if (signal < -80 && signal >= -90) {
        return 'poor'
    } else if (signal < -90 && signal >= -100) {
        return 'weak'
    }
}

private static String translateColorName(BigDecimal hue, BigDecimal saturation) {
    if (saturation < 1) {
        return 'White'
    }

    switch (hue * 3.6 as Integer) {
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

private int convertToBrightness(BigDecimal level) {
    int min = settings.dimLevelMin ?: 0
    int max = settings.dimLevelMax ?: 255
    return Math.ceil(remap(level, 0, 100, min, max)) as int
}

private int convertFromBrightness(BigDecimal brightness) {
    int min = settings.dimLevelMin ?: 0
    int max = settings.dimLevelMax ?: 255
    return Math.floor(remap(brightness, min, max, 0, 100))
}

private void post(String id, String path, Map query = null) {
    if (id && path) {
        LOG.debug "post: ${id} ${path} ${query}"
        asynchttpPost(null,
            [
                uri: "http://${settings.ipAddress}/${id.replaceAll('-', '/')}/",
                path: path,
                query: query,
                timeout: 5
            ]
        )

        if (device.currentValue('state') == 'disconnected') {
            LOG.info 'attempt reconnect to event stream'
            runIn(1, connect)
        }
    }
}
