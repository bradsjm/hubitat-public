/* groovylint-disable MethodCount, CompileStatic, DuplicateNumberLiteral, DuplicateStringLiteral */
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
import groovy.transform.Field
import groovy.json.JsonOutput
import hubitat.helper.ColorUtils
import com.hubitat.app.ChildDeviceWrapper

@Field static Map mappings = [
    'POWER': [
        [ event: 'switch', func: { c -> c.value.toLowerCase() } ]
    ],
    'Dimmer': [
        [ event: 'level', unit: '%' ]
    ],
    'CT': [
        [ event: 'colorTemperature', unit: 'K', func: { c -> toKelvin(c.value) } ],
    ],
    'Color': [
        [ event: 'hue', func: { c -> rgbToHSV(c.value)[0] as int } ],
        [ event: 'saturation', func: { c -> rgbToHSV(c.value)[1] as int } ],
        [ event: 'color', func: { c -> rgbToHEX(c.value) } ],
        [ event: 'colorMode', func: { c -> c.value.startsWith('0,0,0') ? 'CT' : 'RGB' } ],
        [ event: 'colorName', func: { c -> getGenericName(rgbToHSV(c.value)) } ],
    ]
]

@Field static Map lightEffects = [
    0: 'None',
    1: 'Blink',
    2: 'Wakeup',
    3: 'Cycle Up',
    4: 'Cycle Down',
    5: 'Random Cycle'
]

metadata {
    definition (name: 'MQTT - Tasmota', namespace: 'bradsjm', author: 'Jonathan Bradshaw') {
        capability 'Initialize'
        capability 'PresenceSensor'
        capability 'Refresh'
        capability 'Switch'

        command 'removeDevices'
    }

    preferences {
        section('MQTT Device Topics') {
            input name: 'discoveryPrefix',
                  type: 'text',
                  title: 'Discovery Prefix',
                  description: 'Discovery Topic Prefix',
                  required: true,
                  defaultValue: 'tasmota/discovery'
        }

        section('MQTT Broker') {
            input name: 'mqttBroker',
                  type: 'text',
                  title: 'MQTT Broker Host/IP',
                  description: 'ex: tcp://hostnameorip:1883',
                  required: true,
                  defaultValue: 'tcp://mqtt:1883'
            input name: 'mqttUsername',
                  type: 'text',
                  title: 'MQTT Username',
                  description: '(blank if none)',
                  required: false
            input name: 'mqttPassword',
                  type: 'password',
                  title: 'MQTT Password',
                  description: '(blank if none)',
                  required: false
        }

        section('Misc') {
            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: true
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"
    unschedule()
    state.clear()

    state.Subscriptions = [:]

    if (!settings.mqttBroker) {
        log.error 'Unable to connect because Broker setting not configured'
        return
    }

    mqttDisconnect()
    mqttConnect()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called to parse received MQTT data
void parse(String data) {
    mqttReceive(interfaces.mqtt.parseMessage(data))
}

// Called when refresh command is used
void refresh() {
    log.info "${device.displayName} refreshing devices"
    childDevices.each { device -> componentRefresh(device) }
}

// Called when the device is removed.
void uninstalled() {
    mqttDisconnect()
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

// command to remove all the child devices
void removeDevices() {
    log.info "${device.displayName} removing all child devices"
    state.Subscriptions = [:]
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

/**
 *  Switch Capability
 */

// Turn on all devices
void on() {
    childDevices.findAll { device -> device.hasCommand('on') }.each { device ->
        componentOn(device)
    }
}

// Turn off all devices
void off() {
    childDevices.findAll { device -> device.hasCommand('off') }.each { device ->
        componentOff(device)
    }
}

/**
 *  Component child device callbacks
 */

void componentOn(ChildDeviceWrapper device) {
    Map config = parseJson(device.getDataValue('config'))
    String topic = getCommandTopic(config) + 'POWER'
    int index = device.getDataValue('index') as int
    if (index > 1) { topic += index.toString() }
    log.info "Turning ${device} on"
    mqttPublish(topic, '1')
}

void componentOff(ChildDeviceWrapper device) {
    Map config = parseJson(device.getDataValue('config'))
    String topic = getCommandTopic(config) + 'POWER'
    int index = device.getDataValue('index') as int
    if (index > 1) { topic += index.toString() }
    log.info "Turning ${device} off"
    mqttPublish(topic, '0')
}

void componentSetLevel(ChildDeviceWrapper device, Integer level) {
    Map config = parseJson(device.getDataValue('config'))
    String topic = getCommandTopic(config) + 'Dimmer'
    String payload = level
    log.info "Setting ${device} level to ${level}%"
    mqttPublish(topic, payload)
}

// void componentStartLevelChange(device, direction) {
//     if (settings.changeLevelStep && settings.changeLevelEvery) {
//         int delta = (direction == "down") ? -settings.changeLevelStep : settings.changeLevelStep
//         doLevelChange(limit(delta, -10, 10))
//         log.info "${device.displayName} Starting level change ${direction}"
//     }
// }

// void componentStopLevelChange(device) {
//     unschedule("doLevelChange")
//     log.info "${device.displayName} Stopping level change"
// }

// private void doLevelChange(device, delta) {
//     int newLevel = limit(device.currentValue("level").toInteger() + delta)
//     componentSetLevel(device, newLevel)
//     if (newLevel > 0 && newLevel < 99) {
//         int delay = limit(settings.changeLevelEvery, 100, 1000)
//         runInMillis(delay, "doLevelChange", [ data: [device, delta] ])
//     }
// }

void componentSetColorTemperature(ChildDeviceWrapper device, Integer value) {
    Map config = parseJson(device.getDataValue('config'))

    if (config['lt_st'] == 2 || config['lt_st'] == 5) {
        String topic = getCommandTopic(config) + 'CT'
        String payload = Math.round(1000000f / value)
        log.info "Setting ${device} color temperature to ${value}K"
        mqttPublish(topic, payload)
        return
    }

    String topic = getCommandTopic(config) + 'WHITE'
    // ignore value of color temperature as there is only one white for this device
    String payload = device.currentValue('level')
    log.info "Setting ${device} white channel to ${value}K"
    mqttPublish(topic, payload)
}

void componentSetColor(ChildDeviceWrapper device, Map colormap) {
    def (int r, int g, int b) = ColorUtils.hsvToRGB([colormap.hue, colormap.saturation, colormap.level])
    Map config = parseJson(device.getDataValue('config'))
    String topic = getCommandTopic(config) + 'Color'
    String payload = "${r},${g},${b}"
    log.info "Setting ${device} color (RGB) to ${payload}"
    mqttPublish(topic, payload)
}

void componentSetHue(ChildDeviceWrapper device, Integer hue) {
    componentSetColor(device, [
        hue: hue,
        saturation: 100,
        level: device.currentValue('level') ?: 100
    ])
}

void componentSetSaturation(ChildDeviceWrapper device, Integer percent) {
    componentSetColor(device, [
        hue: device.currentValue('hue') ?: 100,
        saturation: percent,
        level: device.currentValue('level') ?: 100
    ])
}

void componentRefresh(ChildDeviceWrapper device) {
    Map config = parseJson(device.getDataValue('config'))
    String topic = getCommandTopic(config) + 'STATE'
    log.info "Refreshing ${device}"
    mqttPublish(topic, '')
}

// Called with MQTT client status messages
void mqttClientStatus(String status) {
    // The string that is passed to this method with start with "Error" if an error occurred
    // or "Status" if this is just a status message.
    List<String> parts = status.split(': ')
    switch (parts[0]) {
        case 'Error':
            log.warn "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection lost':
                case 'send error':
                    runIn(30, 'initialize')
                    break
            }
            break
        case 'Status':
            log.info "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection succeeded':
                    sendEvent(name: 'presence', value: 'present', descriptionText: "${device.displayName} is connected")
                    // without this delay the `parse` method is never called
                    // (it seems that there needs to be some delay after connection to subscribe)
                    runIn(1, 'mqttSubscribeDiscovery')
                    break
            }
            break
        default:
            log.warn "MQTT ${status}"
            break
    }
}

/**
 *  Static Utility methods
 */

private static Map newEvent(ChildDeviceWrapper device, String name, Object value, String unit = null) {
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: "${device.displayName} ${name} is ${value}${unit ?: ''}"
    ]
}

private static String getGenericName(List<Integer> hsv) {
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

private static List<Integer> rgbToHSV(String rgb) {
    return ColorUtils.rgbToHSV(rgb.tokenize(',')*.asType(int).take(3))
}

private static String rgbToHEX(String rgb) {
    return ColorUtils.rgbToHEX(rgb.tokenize(',')*.asType(int).take(3))
}

private static int toKelvin(BigDecimal value) {
    return Math.round(1000000f / value) as int
}

/**
 * Discovery logic
 */
private void parseAutoDiscovery(String topic, Map config) {
    if (logEnable) { log.debug "Autodiscovery: ${topic}=${config}" }
    if (!config) {
        log.warn "Autodiscovery: Config empty or missing ${topic}"
        return
    }

    String hostname = config['hn']
    List<Integer> relay = config['rl']
    relay.eachWithIndex { relaytype, idx ->
        if (relaytype > 0 && relaytype <= 3) {
            parseAutoDiscoveryDevice(idx as int, relaytype as int, config)
        } else if (relaytype) {
            log.warn "Autodiscovery ${hostname}: Relay ${idx + 1} has unknown type ${relaytype}"
        }
    }
}

private void parseAutoDiscoveryDevice(int idx, int relaytype, Map config) {
    String friendlyname = config['fn'][idx]
    String devicename = config['dn']
    String dni = config['mac']
    if (idx > 0) { dni += "-${idx}" }

    String driver = getDeviceDriver(relaytype, config)
    if (!driver) {
        log.error "Autodiscovery ${dni}: Missing driver for ${friendlyname}"
        return
    }

    ChildDeviceWrapper device = getOrCreateDevice(driver, dni, devicename, friendlyname)
    log.info device
    if (!device) {
        log.error "Autodiscovery: Unable to create driver ${driver} for ${friendlyname}"
        return
    }

    // Persist configuration to device data fields
    device.updateDataValue('config', JsonOutput.toJson(config))
    device.updateDataValue('index', (idx + 1).toString())

    if (device.hasCapability('LightEffects')) {
        device.sendEvent(name: 'lightEffects', value: JsonOutput.toJson(lightEffects))
    }

    log.info "Autodiscovery: ${device} (${dni}) using ${driver} driver"

    List<String> subscriptions = [
        getStatTopic(config) + 'RESULT',
        getTeleTopic(config) + 'STATE'
    ]

    // Add topic subscriptions
    subscriptions.each { topic ->
        if (logEnable) { log.trace "Autodiscovery: Subscribing to topic ${topic} for ${device}" }
        interfaces.mqtt.subscribe(topic, config.qos ?: 0)
        state.Subscriptions[topic] = dni
    }

    // Force refresh of state
    componentRefresh(device)
}

/**
 *  Message Parsing logic
 */
private void parseTopicPayload(ChildDeviceWrapper device, String topic, String payload) {
    List<Map> events = []

    // Detect json payload
    if (!payload.startsWith('{') || !payload.endsWith('}')) {
        log.warn "${device} ${topic}: Ignoring non JSON payload (${payload})"
        return
    }

    // Get the configuration from the device
    Map config = device.data
    Map json = parseJson(payload)

    // Iterate the json payload content
    json.each { kv ->
        // Get the action mappings for this variable
        mappings[kv.key].each { action ->
            Object value = kv.value
            if (action.func) {
                value = action.func.call([ device: device, config: config, value: kv.value ])
                if (logEnable && value != it.value) {
                    log.debug "${device} ${action.event ?: ''}: Converted from Tasmota ${kv.value} to Hubitat ${value}"
                }
            }

            if (value != null && action.event && device.currentValue(action.event) != value) {
                events << newEvent(device, action.event, value, action?.unit)
            }
        }
    }

    if (events) { device.parse(events) }
}

/**
 *  Common Tasmota MQTT communication methods
 */

private void mqttConnect() {
    unschedule('mqttConnect')
    try {
        String clientId = device.hub.hardwareID + '-' + device.id
        log.info "Connecting to MQTT broker at ${settings.mqttBroker}"
        interfaces.mqtt.connect(
            settings.mqttBroker,
            clientId,
            settings?.mqttUsername,
            settings?.mqttPassword
        )
    } catch (e) {
        log.error "MQTT connect error: ${e}"
        runIn(30, 'mqttConnect')
    }
}

private void mqttDisconnect() {
    if (interfaces.mqtt.connected) {
        log.info "Disconnecting from MQTT broker at ${settings?.mqttBroker}"
        interfaces.mqtt.disconnect()
    }

    sendEvent(name: 'presence', value: 'not present', descriptionText: "${device.displayName} is not connected")
}

private void mqttPublish(String topic, String payload = '', int qos = 0) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "PUB: ${topic} = ${payload}" }
        interfaces.mqtt.publish(topic, payload, qos, false)
    }
}

private void mqttReceive(Map message) {
    String topic = message.get('topic')
    String payload = message.get('payload')
    if (logEnable) { log.debug "RCV: ${topic} = ${payload}" }

    if (topic.startsWith(settings.discoveryPrefix) && topic.endsWith('config')) {
        // Parse Home Assistant Discovery topic
        parseAutoDiscovery(topic, parseJson(payload))
    } else if (state.Subscriptions.containsKey(topic)) {
        // Parse one of our subscription notifications
        String dni = state.Subscriptions[topic]
        ChildDeviceWrapper childDevice = getChildDevice(dni)
        if (childDevice) {
            parseTopicPayload(childDevice, topic, payload)
        } else {
            log.warn "Unable to find child device id ${dni} for topic ${topic}"
        }
    } else if (logEnable) {
        log.debug "Unhandled topic ${topic}, ignoring payload"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void mqttSubscribeDiscovery() {
    if (logEnable) { log.trace "Subscribing to Tasmota discovery topic at ${settings.discoveryPrefix}" }
    interfaces.mqtt.subscribe("${settings.discoveryPrefix}/#")
}

/**
 *  Utility methods
 */

private ChildDeviceWrapper getOrCreateDevice(String driverName, String deviceNetworkId, String name, String label) {
    ChildDeviceWrapper childDevice = getChildDevice(deviceNetworkId)
    if (!childDevice) {
        log.info "Autodiscovery: Creating child device ${name} [${deviceNetworkId}] (${driverName})"
        childDevice = addChildDevice(
            'hubitat',
            driverName,
            deviceNetworkId,
            [
                name: name
            ]
        )
    } else if (logEnable) {
        log.debug "Autodiscovery: Found child device ${name} [${deviceNetworkId}] (${driverName})"
    }

    if (childDevice.name != name) { childDevice.name = name }
    if (childDevice.label != label) { childDevice.label = label }

    return childDevice
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}

private String getCommandTopic(Map config) {
    return getTopic(config, config['tp'][0])
}

private String getStatTopic(Map config) {
    return getTopic(config, config['tp'][1])
}

private String getTeleTopic(Map config) {
    return getTopic(config, config['tp'][2])
}

private String getTopic(Map config, String prefix) {
    topic = config['ft']
    topic = topic.replace('%hostname%', config['hn'])
    topic = topic.replace('%id%', config['mac'][-6..-1])
    topic = topic.replace('%prefix%', prefix)
    topic = topic.replace('%topic%', config['t'])
    return topic
}

private String getDeviceDriver(int relaytype, Map config) {
    switch (relaytype) {
        case 1:
            return 'Generic Component Switch'
        case 2: // light or light fan
            if (config['if']) {
                log.warn 'Light Fan not implemented'
            } else {
                switch (config['lt_st']) {
                    case 1: return 'Generic Component Dimmer'
                    case 2: return 'Generic Component CT'
                    case 3: return 'Generic Component RGB'
                    case 4:
                    case 5: return 'Generic Component RGBW Light Effects'
                }
            }
            break
    }
}
