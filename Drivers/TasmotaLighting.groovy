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
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.helper.ColorUtils

metadata {
    definition (name: 'MQTT - Tasmota Lighting', namespace: 'nrgup', author: 'Jonathan Bradshaw') {
        capability 'Initialize'
        capability 'PresenceSensor'
        capability 'Refresh'
        capability 'Switch'

        command 'disconnect'
        command 'sendCommand', [
            [
                name: 'command*',
                type: 'STRING',
                description: 'Sent to all devices'
            ]
        ]
        command 'removeDevices'
    }

    preferences {
        section {
            input name: 'discoveryPrefix',
                  type: 'text',
                  title: 'Discovery Prefix',
                  description: 'Discovery Topic Prefix',
                  required: true,
                  defaultValue: 'tasmota/discovery'

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

            input name: 'so20',
                  type: 'bool',
                  title: 'Update of Dimmer/Color/CT without turning power on',
                  required: false,
                  defaultValue: false

            input name: 'restoreState',
                  type: 'bool',
                  title: 'Attempt to restore state when device comes online',
                  required: false,
                  defaultValue: false

            input name: 'telePeriod',
                  type: 'number',
                  title: 'Interval for telemetry updates in seconds (0 to disable)',
                  required: false,
                  defaultValue: 300

            input name: 'fadeTime',
                  type: 'number',
                  title: 'Default fade seconds (0 to disable)',
                  required: false,
                  defaultValue: 0

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: true
        }
    }
}

// List of topic to child device mappings for lookup for received messages
@Field static final Map<String, Set> subscriptions = [:]
// Cache of device configuration data for performance
@Field static final Map<Integer, Map> configCache = [:]
// Track of last heard from time for each device
@Field static final Map<Integer, Long> lastHeard = [:]
// Track for dimming operations
@Field static final Map<String, Integer> levelChanges = [:]

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"
    unschedule()
    subscriptions.clear()

    if (!settings.mqttBroker) {
        log.error 'Unable to connect because Broker setting not configured'
        return
    }

    if (settings.telePeriod) {
        runIn(settings.telePeriod + 60, 'healthcheck')
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

/**
 *  Custom Commands
 */

// Called when refresh command is used
void refresh() {
    log.info "${device.displayName} refreshing devices"
    childDevices.each { device -> componentRefresh(device) }
}

// command to remove all the child devices
void removeDevices() {
    log.info "${device.displayName} removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

// disconnect from broker
void disconnect() {
    log.info "${device.displayName} disconnecting from broker"
    mqttDisconnect()
}

// send command to all child devices
void sendCommand(String command) {
    log.info "${device.displayName} sending ${command} to all devices"
    childDevices.each { device ->
        Map config = getDeviceConfig(device)
        String topic = getCommandTopic(config) + 'Backlog'
        mqttPublish(topic, command)
    }
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

void componentOn(DeviceWrapper device) {
    Map config = getDeviceConfig(device)
    String topic = getCommandTopic(config) + 'Backlog'
    boolean isTuyaMcu = config['ty']
    String fadeCommand = fadeCommand(isTuyaMcu ? 0 : settings.fadeTime)
    log.info "Turning ${device} on"
    mqttPublish(topic, fadeCommand + "POWER${config.index ?: 1} 1")
}

void componentOff(DeviceWrapper device) {
    Map config = getDeviceConfig(device)
    String topic = getCommandTopic(config) + 'Backlog'
    boolean isTuyaMcu = config['ty']
    String fadeCommand = fadeCommand(isTuyaMcu ? 0 : settings.fadeTime)
    log.info "Turning ${device} off"
    mqttPublish(topic, fadeCommand + "POWER${config.index ?: 1} 0")
}

void componentSetLevel(DeviceWrapper device, BigDecimal level, BigDecimal duration = -1) {
    Map config = getDeviceConfig(device)
    String topic = getCommandTopic(config) + 'Backlog'
    int seconds = duration >= 0 ? duration : settings.fadeTime
    boolean isTuyaMcu = config['ty']
    String fadeCommand = fadeCommand(isTuyaMcu ? 0 : settings.fadeTime)
    log.info "Setting ${device} level to ${level}%" + (isTuyaMcu ? '' : " over ${seconds}s")
    mqttPublish(topic, fadeCommand + "Dimmer${config.index ?: 1} ${level}")
}

void componentStartLevelChange(DeviceWrapper device, String direction) {
    if (settings.changeLevelStep && settings.changeLevelEvery) {
        int delta = limit((direction == 'down') ? -settings.changeLevelStep : settings.changeLevelStep, -10, 10)
        log.info "${device.displayName} Starting level change ${direction}"
        levelChanges[device.deviceNetworkId] = delta
        int delay = limit(settings.changeLevelEvery, 100, 1000)
        runInMillis(delay, 'doLevelChange')
    }
}

void componentStopLevelChange(DeviceWrapper device) {
    log.info "${device.displayName} Stopping level change"
    levelChanges.remove(device.deviceNetworkId)
}

void componentSetColorTemperature(DeviceWrapper device, BigDecimal value) {
    Map config = getDeviceConfig(device)
    String topic = getCommandTopic(config) + 'Backlog'
    String fadeCommand = fadeCommand(duration ?: settings.fadeTime)

    if (config['lt_st'] == 2 || config['lt_st'] == 5) {
        int level = 1000000f / value
        log.info "Setting ${device} color temperature to ${value}K (${level} mireds)"
        mqttPublish(topic, fadeCommand + "CT ${level}")
        return
    }

    // ignore value of color temperature as there is only one white for this device
    String level = device.currentValue('level')
    log.info "Setting ${device} white channel to WHITE"
    mqttPublish(topic, fadeCommand + "WHITE ${level}")
}

void componentSetColor(DeviceWrapper device, Map colormap) {
    def (int r, int g, int b) = ColorUtils.hsvToRGB([colormap.hue, colormap.saturation, colormap.level])
    Map config = getDeviceConfig(device)
    String topic = getCommandTopic(config) + 'Backlog'
    String fadeCommand = fadeCommand(duration ?: settings.fadeTime)
    String color = "${r},${g},${b}"
    log.info "Setting ${device} color (RGB) to ${color}"
    mqttPublish(topic, fadeCommand + "Color ${color}")
}

void componentSetHue(DeviceWrapper device, BigDecimal hue) {
    componentSetColor(device, [
        hue: hue,
        saturation: device.currentValue('saturation') ?: 100,
        level: device.currentValue('level') ?: 100
    ])
}

void componentSetSaturation(DeviceWrapper device, BigDecimal saturation) {
    componentSetColor(device, [
        hue: device.currentValue('hue') ?: 100,
        saturation: saturation,
        level: device.currentValue('level') ?: 100
    ])
}

void componentRefresh(DeviceWrapper device) {
    Map config = getDeviceConfig(device)
    if (config) {
        subscribeDeviceTopics(device)
        log.info "Refreshing ${device}"
        mqttPublish(getCommandTopic(config) + 'STATE', '')
    }
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
                    mqttDisconnect()
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

// Get the Tasmota fade speed command from seconds
private static String fadeCommand(BigDecimal seconds) {
    int speed = Math.min(40f, seconds * 2).toInteger()
    return speed ? "NoDelay;Fade 1;NoDelay;Speed ${speed};NoDelay;" : 'NoDelay;Fade 0;NoDelay;'
}

// Convert hsv to a generic color name
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

// Convert rgb to HSV value
private static List<Integer> rgbToHSV(String rgb) {
    return ColorUtils.rgbToHSV(rgb.tokenize(',')*.asType(int).take(3))
}

// Convert mireds to kelvin
private static int toKelvin(BigDecimal value) {
    return 1000000f / value as int
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void doLevelChange() {
    deviceNetworkId.each { kv ->
        ChildDeviceWrapper device = getChildDevice(kv.key)
        int newLevel = limit(device.currentValue('level').toInteger() + kv.value)
        componentSetLevel(device, newLevel)
        if (newLevel <= 0 && newLevel >= 100) {
            componentStopLevelChange(device)
        }
    }

    if (!levelChanges.isEmpty()) {
        int delay = limit(settings.changeLevelEvery, 100, 1000)
        runInMillis(delay, 'doLevelChange')
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void healthcheck() {
    String indicator = ' (Offline)'
    childDevices.each { device ->
        if (lastHeard.containsKey(device.id)) {
            int elapsed = (now() - lastHeard[device.id]) / 1000
            if (elapsed > settings.telePeriod) {
                log.warn "${device} has not been heard from in ${elapsed} seconds"
                if (!device.label.endsWith(indicator)) {
                    device.label += indicator
                }
            } else if (device.label.endsWith(indicator)) {
                device.label -= indicator
                log.info "${device} is now reporting data again"
            }
        }
    }

    runIn(settings.telePeriod + 60, 'healthcheck')
}

private Map newEvent(ChildDeviceWrapper device, String name, Object value, Map params = [:]) {
    String splitName = splitCamelCase(name).toLowerCase()
    String oldValue = device.currentValue(name)
    String unit = params.unit ?: ''
    String description = "${device.displayName} ${splitName} is ${value}${unit} (was ${oldValue}${unit})"
    return [
        name: name,
        value: value,
        descriptionText: description
    ] + params
}

private void subscribeDeviceTopics(DeviceWrapper device) {
    Map config = getDeviceConfig(device)
    String dni = device.deviceNetworkId

    List<String> topics = [
        getStatTopic(config) + 'RESULT',
        getTeleTopic(config) + 'STATE',
        getTeleTopic(config) + 'SENSOR',
        getTeleTopic(config) + 'LWT'
    ]
    topics.each { topic ->
        mqttSubscribe(topic)
        subscriptions.computeIfAbsent(topic) { k -> [] as Set }.add(dni)
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

/**
 * Discovery logic
 */
private void parseAutoDiscovery(String topic, Map config) {
    if (logEnable) { log.debug "Autodiscovery: ${topic}=${config}" }
    if (!config) {
        log.warn "Autodiscovery: Config empty or missing ${topic}"
        return
    }

    if (!topic.endsWith('/config')) {
        return
    }

    String hostname = config['hn']
    List<Integer> relay = config['rl']

    // Send device configuration options
    configureDeviceSettings(config)

    // Iterate through the defined relays/outputs
    relay.eachWithIndex { relaytype, idx ->
        if (relaytype > 0 && relaytype <= 3) {
            parseAutoDiscoveryRelay(1 + idx as int, relaytype as int, config)
        } else if (relaytype) {
            log.warn "Autodiscovery ${hostname}: Relay ${idx + 1} has unknown type ${relaytype}"
        }
    }

    // Check for pushable buttons
    if (config['btn']) {
        parseAutoDiscoveryButton(config)
    }
}

private void parseAutoDiscoveryButton(Map config) {
    String devicename = config['dn']
    String mac = config['mac']
    List<Integer> button = config['btn']
    String dni = mac + '-btn'

    int count = button.sum()
    if (!count) { return }

    // boolean mqttButtons = config['so']['73'] == 1
    // boolean singleButtons = config['so']['13'] == 1
    // boolean swapButtons = config['so']['11'] == 1
    String friendlyname = devicename + (count > 1 ? ' Buttons' : ' Button')
    String driver = 'Generic Component Button Controller'
    ChildDeviceWrapper device = getOrCreateDevice(driver, dni, devicename, friendlyname)
    if (!device) {
        log.error "Autodiscovery: Unable to create driver ${driver} for ${devicename}"
        return
    }

    log.info "Autodiscovery: ${device} (${dni}) using ${driver} driver"

    device.with {
        updateDataValue 'config', JsonOutput.toJson(config)
        updateDataValue 'model', config['md']
        updateDataValue 'ip', "http://${config.ip}/"
        updateDataValue 'mac', config['mac']
        updateDataValue 'hostname', "http://${config.hn}/"
        updateDataValue 'software', config['sw']
        updateDataValue 'topic', config['t']
    }

    device.sendEvent(name: 'numberOfButtons', value: count)
    subscribeDeviceTopics(device)
}

private void parseAutoDiscoveryRelay(int idx, int relaytype, Map config) {
    String friendlyname = config['fn'][idx - 1]
    String devicename = config['dn']
    String mac = config['mac']
    String dni = mac
    if (idx > 1) {
        dni += "-${idx}"
        devicename += " #${idx}"
    }

    String driver = getDeviceDriver(relaytype, config)
    if (!driver) {
        log.error "Autodiscovery ${dni}: Missing driver for ${friendlyname}"
        return
    }

    ChildDeviceWrapper device = getOrCreateDevice(driver, dni, devicename, friendlyname)
    if (!device) {
        log.error "Autodiscovery: Unable to create driver ${driver} for ${friendlyname}"
        return
    }

    log.info "Autodiscovery: ${device} (${dni}) using ${driver} driver"

    // Persist required configuration to device data fields
    config['index'] = idx
    device.with {
        updateDataValue 'config', JsonOutput.toJson(config)
        updateDataValue 'model', config['md']
        updateDataValue 'ip', "http://${config.ip}/"
        updateDataValue 'mac', config['mac']
        updateDataValue 'hostname', "http://${config.hn}/"
        updateDataValue 'software', config['sw']
        updateDataValue 'topic', config['t']
    }

    if (device.hasCapability('LightEffects')) {
        device.sendEvent(name: 'lightEffects', value: JsonOutput.toJson([
            0: 'None',
            1: 'Blink',
            2: 'Wakeup',
            3: 'Cycle Up',
            4: 'Cycle Down',
            5: 'Random Cycle'
        ]))
    }

    subscribeDeviceTopics(device)
}

// Configure Tasmota device settings
private void configureDeviceSettings(Map config) {
    String topic = getCommandTopic(config) + 'BACKLOG'
    String payload = ''
    boolean isTuyaMcu = config['ty']

    payload += 'Latitude ' + location.latitude + ';'
    payload += 'Longitude ' + location.longitude + ';'
    payload += 'so17 1;' // use RGB not HEX results
    payload += 'so20 ' + (!isTuyaMcu && settings['so20'] ? '1' : '0') + ';'
    payload += 'so59 0;' // do not send state for power command
    payload += 'teleperiod ' + settings['telePeriod'] + ';'

    mqttPublish(topic, payload)
}

private void restoreState(ChildDeviceWrapper device) {
    log.info "Attempting to restore ${device} previous state"
    String sw = device.currentValue('switch')
    BigDecimal level = device.currentValue('level')
    BigDecimal colorTemperature = device.currentValue('colorTemperature')
    BigDecimal hue = device.currentValue('hue')
    BigDecimal saturation = device.currentValue('saturation')
    String colorMode = device.currentValue('colorMode')

    if (sw == 'on') { componentOn(device) }
    if (sw == 'off') { componentOff(device) }
    if (colorMode == 'CT' && colorTemperature != null) {
        componentSetColorTemperature(device, colorTemperature)
        if (level != null) { componentSetLevel(device, level) }
    } else if (colorMode == 'RGB' && hue != null && saturation != null) {
        componentSetColor(device, [ hue: hue, saturation: saturation, level: level])
    } else if (level != null) {
        componentSetLevel(device, level)
    }
}

private void scanChildDevices() {
    log.info 'Scanning child devices to build subscription topic cache'
    subscriptions.clear()
    childDevices.each { device -> componentRefresh(device) }
    log.info "Completed caching ${childDevices.size()} devices with ${subscriptions.size()} topics"
}

/**
 *  Message Parsing logic
 */
private void parseLWT(ChildDeviceWrapper device, String payload) {
    String indicator = ' (Offline)'
    if (device.label.endsWith(indicator)) {
        device.label -= indicator
    }

    if (payload == 'Offline') {
        device.label += indicator
        device.parse([ newEvent(device, 'switch', 'off') ])
    } else if (settings.restoreState) {
        restoreState(device)
    }
}

private void parseTopicPayload(ChildDeviceWrapper device, String topic, String payload) {
    List<Map> events = []

    if (topic.endsWith('LWT')) {
        parseLWT(device, payload)     // Process online/offline notifications
        return
    } else if (!payload.startsWith('{') || !payload.endsWith('}')) {
        if (logEnable) { log.debug "${device} ${topic}: Ignoring non JSON payload (${payload})" }
        return
    }

    // Get the configuration from the device
    String index = getDeviceConfig(device)['index']
    Map json = parseJson(payload)
    if (index == 1) { lastHeard[device.id] = now() }

    // Iterate the json payload content
    json.each { kv ->
        switch (kv.key) {
            case 'POWER':
            case "POWER${index}":
                String value = kv.value.toLowerCase()
                if (device.hasAttribute('switch') && device.currentValue('switch') != value) {
                    events << newEvent(device, 'switch', value)
                }
                break
            case 'Dimmer':
                if (device.hasAttribute('level') && device.currentValue('level') != kv.value) {
                    events << newEvent(device, 'level', kv.value, [unit: '%'])
                }
                break
            case 'CT':
                BigDecimal value = toKelvin(kv.value)
                if (device.hasAttribute('colorTemperature') &&
                    Math.abs((device.currentValue('colorTemperature') ?: 0) - value) > 10) {
                    events << newEvent(device, 'colorTemperature', value, [unit: 'K'])
                }
                break
            case 'Color':
                String colorMode = kv.value.startsWith('0,0,0') ? 'CT' : 'RGB'
                String colorName = getGenericName(rgbToHSV(kv.value))
                List<Integer> hsv = rgbToHSV(kv.value)
                if (device.hasAttribute('hue') && device.currentValue('hue') != hsv[0]) {
                    events << newEvent(device, 'hue', hsv[0])
                }
                if (device.hasAttribute('saturation') && device.currentValue('saturation') != hsv[1]) {
                    events << newEvent(device, 'saturation', hsv[1])
                }
                if (device.hasAttribute('colorMode') && device.currentValue('colorMode') != colorMode) {
                    events << newEvent(device, 'colorMode', colorMode)
                }
                if (device.hasAttribute('colorName') && device.currentValue('colorName') != colorName) {
                    events << newEvent(device, 'colorName', colorName)
                }
                break
            case 'ENERGY':
            int power = kv.value['Power']
                if (device.hasAttribute('power') && device.currentValue('power') != power) {
                    events << newEvent(device, 'power', power, [unit: 'W'])
                }
                break
            case 'Uptime':
            case 'LoadAvg':
            case 'MqttCount':
                device.updateDataValue(kv.key.toLowerCase(), kv.value.toString())
                break
            case 'Wifi':
                kv.value.each { wkv ->
                    device.updateDataValue('wifi.' + wkv.key.toLowerCase(), wkv.value.toString())
                }
                break
            case ~/^Button[1-8]$/:
                String action = kv.value['Action']
                int number = kv.key[-1] as int
                if (logEnable) { log.debug "${device} button number ${number} ${action}" }
                switch (action) {
                    case 'SINGLE':
                        if (device.hasAttribute('pushed')) {
                            events << newEvent(device, 'pushed', number, [type: physical, isStateChange: true])
                        }
                        break
                    case 'DOUBLE':
                        if (device.hasAttribute('doubleTapped')) {
                            events << newEvent(device, 'doubleTapped', number, [type: physical, isStateChange: true])
                        }
                        break
                    // case 'TRIPLE':
                    // case 'QUAD':
                    // case 'PENTA':
                    case 'HOLD':
                        if (device.hasAttribute('held')) {
                            events << newEvent(device, 'held', number, [type: physical, isStateChange: true])
                        }
                        break
                }
                break
            // default:
            //     if (logEnable) { log.debug "${device} ${topic}: No mapping for [ ${kv.key}: ${kv.value} ]" }
            //     break
        }
    }

    if (events) {
        if (logEnable) { log.debug "Sending ${events} to ${device}" }
        device.parse(events)
    }
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

    if (topic.startsWith(settings.discoveryPrefix) && payload.startsWith('{')) {
        // Parse Home Assistant Discovery topic
        parseAutoDiscovery(topic, parseJson(payload))
        return
    }

    if (!subscriptions.containsKey(topic)) {
        scanChildDevices()
    }

    if (subscriptions.containsKey(topic)) {
        // Parse subscription notifications
        subscriptions[topic].each { dni ->
            ChildDeviceWrapper childDevice = getChildDevice(dni)
            if (childDevice) {
                parseTopicPayload(childDevice, topic, payload)
            } else {
                log.warn "Unable to find child device id ${dni} for topic ${topic}, removing subscription"
                subscriptions.remove(topic)
                mqttUnsubscribe(topic)
            }
        }
    } else {
        log.warn "Unhandled topic ${topic} received, rebuilding subscriptions"
        mqttUnsubscribe(topic)
    }
}

private void mqttSubscribe(String topic) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "SUB: ${topic}" }
        interfaces.mqtt.subscribe(topic)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void mqttSubscribeDiscovery() {
    log.info "Subscribing to discovery topic at ${settings.discoveryPrefix}"
    mqttSubscribe("${settings.discoveryPrefix}/#")
    scanChildDevices()
}

private void mqttUnsubscribe(String topic) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "UNSUB: ${topic}" }
        interfaces.mqtt.unsubscribe(topic)
    }
}

/**
 *  Utility methods
 */

private String getCommandTopic(Map config) {
    return getTopic(config, config['tp'][0])
}

private Map getDeviceConfig(DeviceWrapper device) {
    return configCache.computeIfAbsent(device.id) { k ->
        String config = device.getDataValue('config')
        return config ? parseJson(config) : null
    }
}

private String getDeviceDriver(int relaytype, Map config) {
    switch (relaytype) {
        case 1:
            return 'Generic Component Metering Switch'
        case 2: // light or light fan
            if (config['if']) {
                log.warn 'Light Fan not implemented'
            } else {
                switch (config['lt_st']) {
                    case 1: return 'Generic Component Dimmer'
                    case 2: return 'Generic Component CT'
                    case 3: return 'Generic Component RGB'
                    case 4:
                    case 5: return 'Generic Component RGBW'
                }
            }
            break
    }
}

private ChildDeviceWrapper getOrCreateDevice(String driverName, String deviceNetworkId, String name, String label) {
    ChildDeviceWrapper childDevice = getChildDevice(deviceNetworkId)
    if (!childDevice) {
        log.info "Autodiscovery: Creating child device ${name} [${deviceNetworkId}] (${driverName})"
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
        log.debug "Autodiscovery: Found child device ${name} [${deviceNetworkId}] (${driverName})"
    }

    if (childDevice.name != name) { childDevice.name = name }
    if (childDevice.label != label) { childDevice.label = label }

    return childDevice
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

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}
