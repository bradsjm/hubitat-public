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
import com.hubitat.hub.domain.Event
import com.hubitat.hub.domain.State
import groovy.json.JsonOutput

definition (
    name: 'HomeAssistant MQTT Bridge',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Intranet Connectivity',
    description: 'Publish Hubitat devices to Home Assistant via MQTT',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page(name: 'configuration', install: true, uninstall: true) {
        section {
            label title: 'Application Label',
                  required: false
        }

        section {
            input name: 'devices',
                  type: 'capability.*',
                  title: 'Devices to Publish to MQTT:',
                  multiple: true,
                  required: false
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

        section {
            input name: 'telePeriod',
                  type: 'number',
                  title: 'Periodic state refresh publish interval (minutes)',
                  description: 'Number of minutes (default 15)',
                  required: false,
                  defaultValue: 15

            input name: 'maxIdleHours',
                  type: 'number',
                  title: 'Maximum hours since last activity to publish',
                  description: 'Number of hours (default 24)',
                  required: false,
                  defaultValue: 24

            input name: 'hsmEnable',
                  type: 'bool',
                  title: 'Enable Hubitat Security Manager',
                  required: false,
                  defaultValue: true

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable Debug logging',
                  required: false,
                  defaultValue: true
        }
    }
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
}

// Called when the app is removed.
void uninstalled() {
    unsubscribe()
    removeAllChildDevices()
    log.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    log.debug settings
    initialize()
}

// Called when the driver is initialized.
void initialize() {
    unsubscribe()
    deployDriver()
    log.info 'Subscribing to device and location events'
    subscribe(devices, 'publishDeviceEvent', [ filterEvents: true ])
    subscribe(location, 'publishLocationEvent', [ filterEvents: true ])
}

// Called when MQTT is connected
void connected() {
    log.info 'MQTT is connected'
    subscribeMqtt('homeassistant/status')
    subscribeMqtt('hubitat/cmnd/#')
    runIn(5, 'publishDiscovery')
}

/**
 * MQTT topic and command parsing
 */
void parseMessage(String topic, String payload) {
    if (logEnable) { log.debug "Receive ${topic} = ${payload}" }

    if (topic == 'homeassistant/status') {
        switch (payload) {
            case 'online':
                // wait for home assistant to be ready after being online
                log.info 'Detected Home Assistant online, scheduling publish'
                runIn(5, 'publishDiscovery')
                break
        }
        return
    }

    // Parse topic path for dni and command
    if (topic.startsWith('hubitat/cmnd')) {
        List<String> topicParts = topic.tokenize('/')
        int idx = topicParts.indexOf('cmnd')
        if (!idx) { return }

        String dni = topicParts[idx + 1]
        String cmd = topicParts[idx + 2]
        if (dni == location.hub.hardwareID) {
            parseHubCommand(cmd, payload)
        } else {
            parseDeviceCommand(dni, cmd, payload)
        }
        return
    }

    log.warn "Received unknown topic: ${topic}"
}

/**
 * Utility Methods
 */

// Translate camel case name to normal text
private static String splitCamelCase(String s) {
   return s.replaceAll(
      String.format('%s|%s|%s',
         '(?<=[A-Z])(?=[A-Z][a-z])',
         '(?<=[^A-Z])(?=[A-Z])',
         '(?<=[A-Za-z])(?=[^A-Za-z])'
      ),
      ' '
   )
}

// Translate HSM states to Home Assistant values
private static String getHsmState(String value) {
    switch (value) {
        case 'armingAway': return 'arming'
        case 'armedAway': return 'armed_away'
        case 'armingHome': return 'arming'
        case 'armedHome': return 'armed_home'
        case 'armingNight': return 'arming'
        case 'armedNight': return 'armed_night'
        case 'disarmed': return 'disarmed'
        case 'allDisarmed': return'disarmed'
        case 'intrusion': return 'triggered'
        case 'intrusion-home': return 'triggered'
        case 'intrusion-night': return 'triggered'
    }
    return value
}

private void parseHubCommand(String cmd, String payload) {
    switch (cmd) {
        case 'hsmSetArm':
            if (hsmEnable) {
                log.info "Sending location event ${cmd} of ${payload}"
                sendLocationEvent(name: cmd, value: payload)
            }
            return
        default:
            log.error "Unknown command ${cmd} for hub received"
            return
    }
}

private void parseDeviceCommand(String dni, String cmd, String payload) {
    DeviceWrapper device = settings.devices.find { d -> dni == d.deviceNetworkId }
    if (!device) {
        log.error "Unknown device id ${dni} received"
        return
    }

    switch (cmd) {
        case 'switch':
            parseDeviceSwitchCommand(device, payload)
            break
        default:
            if (device.hasCommand(cmd)) {
                log.info "Executing ${device.displayName}: ${cmd} to ${payload}"
                device."$cmd"(payload)
            } else {
                log.warn "Executing ${device.displayName}: ${cmd} does not exist"
            }
            break
    }
}

private void parseDeviceSwitchCommand(DeviceWrapper device, String payload) {
    if (payload == 'on') {
        log.info "Executing ${device.displayName}: Switch on"
        device.on()
    } else {
        log.info "Executing ${device.displayName}: Switch off"
        device.off()
    }
}

/**
 * Home Assistant Device Discovery Publication
 */
/* groovylint-disable-next-line UnusedPrivateMethod */
private void publishDiscovery() {
    ChildDeviceWrapper mqtt = findBridge()
    log.info 'Publishing Hub Auto Discovery'
    publishDiscoveryHub(mqtt)

    log.info 'Publishing Device Auto Discovery'
    devices.each { device -> publishDiscoveryDevice(mqtt, device) }

    // Schedule sending device current state
    runIn(5, 'publishCurrentState')
}

private void publishDiscoveryHub(ChildDeviceWrapper mqtt) {
    String dni = location.hub.hardwareID
    Map deviceConfig = [
        'ids': [ dni, location.hub.zigbeeId ],
        'manufacturer': 'Hubitat',
        'name': location.hub.name,
        'model': 'Elevation',
        'sw_version': location.hub.firmwareVersionString
    ]

    if (hsmEnable) {
        // Publish HSM
        Map config = [ 'device': deviceConfig ]
        config['name'] = location.hub.name + ' Alarm'
        config['unique_id'] = dni + '::hsm'
        config['availability_topic'] = 'hubitat/LWT'
        config['payload_available'] = 'Online'
        config['payload_not_available'] = 'Offline'
        config['state_topic'] = "hubitat/tele/${dni}/hsmArmState"
        config['command_topic'] = "hubitat/cmnd/${dni}/hsmSetArm"
        config['payload_arm_away'] = 'armAway'
        config['payload_arm_home'] = 'armHome'
        config['payload_arm_night'] = 'armNight'
        config['payload_disarm'] = 'disarm'
        String path = "homeassistant/alarm_control_panel/${dni}_hsm/config"
        publishDeviceConfig(mqtt, path, config)
    }

    // Publish hub sensors
    [ 'mode' ].each { name ->
        Map config = [ 'device': deviceConfig ]
        config['name'] = location.hub.name + ' ' + name.capitalize()
        config['unique_id'] = dni + '::' + name
        config['state_topic'] = "hubitat/tele/${dni}/${name}"
        config['expire_after'] = telePeriod * 120
        config['availability_topic'] = 'hubitat/LWT'
        config['payload_available'] = 'Online'
        config['payload_not_available'] = 'Offline'
        switch (name) {
            case 'mode':
                config['icon'] = 'mdi:tag'
                break
        }
        String path = "homeassistant/sensor/${dni}_${name}/config"
        publishDeviceConfig(mqtt, path, config)
    }
}

private void publishDiscoveryDevice(ChildDeviceWrapper mqtt, DeviceWrapper device) {
    if (device == null) { return }
    String dni = device.deviceNetworkId
    Map deviceConfig = [
        'ids': [ dni ],
        'manufacturer': device.getDataValue('manufacturer') ?: '',
        'name': device.displayName,
        'model': device.getDataValue('model') ?: ''
    ]
    if (device.zigbeeId) { deviceConfig.ids << device.zigbeeId }

    // Thermostats are unique beasts and require special handling
    if (device.hasCapability('Thermostat')) {
        Map config = [
            'device': deviceConfig,
            'name': device.displayName
        ]
        thermostatConfig(device, config)
        String path = "homeassistant/climate/${dni}_thermostat/config"
        publishDeviceConfig(mqtt, path, config)
    } else {
        device.currentStates.each { state ->
            Map config = [
                'device': deviceConfig,
                'name': device.displayName + ' ' + splitCamelCase(state.name).capitalize()
            ]
            String type = stateConfig(device, state, config)
            String path = "homeassistant/${type}/${dni}_${state.name}/config"
            publishDeviceConfig(mqtt, path, config)
        }
    }
}

private void thermostatConfig(DeviceWrapper device, Map config) {
    String dni = device.deviceNetworkId
    String tele = "hubitat/tele/${dni}"
    String cmnd = "hubitat/cmnd/${dni}"
    config['availability_topic'] = 'hubitat/LWT'
    config['current_temperature_topic'] = tele + '/temperature'
    config['fan_mode_command_topic'] = cmnd + '/setThermostatFanMode'
    config['fan_mode_state_topic'] = tele + '/thermostatFanMode'
    config['fan_modes'] = ['auto', 'circulate', 'on']
    config['max_temp'] = temperatureScale == 'F' ? '90' : '32'
    config['min_temp'] = temperatureScale == 'F' ? '50' : '10'
    config['mode_command_topic'] = cmnd + '/setThermostatMode'
    config['mode_state_topic'] = tele + '/thermostatMode'
    config['modes'] = device.currentValue('supportedThermostatModes')[1..-2].replace('control', 'auto').split(',')
    config['payload_available'] = 'Online'
    config['payload_not_available'] = 'Offline'
    config['temp_step'] = '1'
    config['temperature_high_command_topic'] = cmnd + '/setCoolingSetpoint'
    config['temperature_high_state_topic'] = tele + '/coolingSetpoint'
    config['temperature_low_command_topic'] = cmnd + '/setHeatingSetpoint'
    config['temperature_low_state_topic'] = tele + '/heatingSetpoint'
    config['temperature_unit'] = temperatureScale
    config['unique_id'] = "${dni}::thermostat"
}

/* groovylint-disable-next-line MethodSize */
private String stateConfig(DeviceWrapper device, State state, Map config) {
    String type
    String dni = device.deviceNetworkId
    String cmndTopic = "hubitat/cmnd/${dni}/${state.name}"
    int expireAfter = settings.telePeriod * 120
    config['unique_id'] = "${dni}::${state.name}"
    config['state_topic'] = "hubitat/tele/${dni}/${state.name}"
    config['availability_topic'] = 'hubitat/LWT'
    config['payload_available'] = 'Online'
    config['payload_not_available'] = 'Offline'
    switch (state.name) {
        case 'acceleration':
            type = 'binary_sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'moving'
            config['payload_on'] = 'active'
            config['payload_off'] = 'inactive'
            break
        case 'battery':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'battery'
            config['unit_of_measurement'] = '%'
            break
        case 'carbonMonoxide':
            type = 'binary_sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'gas'
            config['payload_on'] = 'detected'
            config['payload_off'] = 'clear'
            break
        case 'contact':
            type = 'binary_sensor'
            String name = device.displayName.toLowerCase()
            if (name.contains('garage door') || name.contains('overhead')) {
                config['device_class'] = 'garage_door'
            } else if (name.contains('door')) {
                config['device_class'] = 'door'
            } else {
                config['device_class'] = 'window'
            }
            config['expire_after'] = expireAfter
            config['payload_on'] = 'open'
            config['payload_off'] = 'closed'
            break
        case 'door':
            type = 'cover'
            config['command_topic'] = cmndTopic
            config['device_class'] = 'door'
            config['payload_close'] = 'close'
            config['payload_open'] = 'open'
            config['state_closed'] = 'closed'
            config['state_open'] = 'open'
            break
        case 'humidity':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'humidity'
            config['unit_of_measurement'] = '%'
            break
        case 'motion':
            type = 'binary_sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'motion'
            config['payload_on'] = 'active'
            config['payload_off'] = 'inactive'
            break
        case 'illuminance':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'illuminance'
            config['unit_of_measurement'] = 'lx'
            break
        case 'mute':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['icon'] = 'mdi:volume-off'
            break
        case 'power':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'power'
            config['unit_of_measurement'] = 'W'
            break
        case 'presence':
            type = 'binary_sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'occupancy'
            config['payload_on'] = 'present'
            config['payload_off'] = 'not present'
            break
        case 'pressure':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'pressure'
            config['unit_of_measurement'] = 'mbar'
            break
        case 'switch':
            String name = device.displayName.toLowerCase()
            if (name.contains('light') || name.contains('lamp')) {
                type = 'light'
            } else {
                type = 'switch'
            }
            config['payload_on'] = 'on'
            config['payload_off'] = 'off'
            config['command_topic'] = cmndTopic
            break
        case 'smoke':
            type = 'binary_sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'smoke'
            config['payload_on'] = 'detected'
            config['payload_off'] = 'clear'
            break
        case 'temperature':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'temperature'
            config['unit_of_measurement'] = temperatureScale
            break
        case 'threeAxis':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['icon'] = 'mdi:axis-arrow'
            break
        case 'voltage':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'voltage'
            config['unit_of_measurement'] = 'V'
            break
        case 'volume':
            type = 'sensor'
            config['expire_after'] = expireAfter
            config['icon'] = 'mdi:volume-medium'
            break
        case 'water':
            type = 'binary_sensor'
            config['expire_after'] = expireAfter
            config['device_class'] = 'moisture'
            config['payload_on'] = 'wet'
            config['payload_off'] = 'dry'
            break
        case 'windowShade':
            type = 'cover'
            config['command_topic'] = cmndTopic
            config['device_class'] = 'shade'
            config['payload_close'] = 'close'
            config['payload_open'] = 'open'
            config['state_closed'] = 'closed'
            config['state_open'] = 'open'
            break
        default:
            type = 'sensor'
            config['expire_after'] = expireAfter
            break
    }

    return type
}

/**
 * MQTT Publishing Helpers
 */
/* groovylint-disable-next-line UnusedPrivateMethod */
private void publishCurrentState() {
    ChildDeviceWrapper mqtt = findBridge()
    publishHubState(mqtt)
    publishDeviceState(mqtt)

    if (telePeriod > 0) {
        if (logEnable) { log.debug "Scheduling publish in ${telePeriod} minutes" }
        runIn(telePeriod * 60, 'publishCurrentState')
    }
}

private void publishDeviceConfig(ChildDeviceWrapper mqtt, String path, Map config) {
    String json = JsonOutput.toJson(config)
    log.info "Publishing discovery for ${config.name} to: ${path}"
    if (logEnable) { log.debug "Publish ${json}" }
    mqtt.publish(path, json, 0, true)
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void publishDeviceEvent(Event event) {
    ChildDeviceWrapper mqtt = findBridge()
    String dni = event.device.deviceNetworkId
    String path = "hubitat/tele/${dni}/${event.name}"
    log.info "Publishing (${event.displayName}) ${path}=${event.value}"
    mqtt.publish(path, event.value)
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void publishLocationEvent(Event event) {
    ChildDeviceWrapper mqtt = findBridge()
    String dni = location.hub.hardwareID
    String path = "hubitat/tele/${dni}"
    log.info "Publishing (${location.hub.name}) ${path}/${event.name}=${event.value}"
    mqtt.publish("${path}/${event.name}", event.value)

    switch (event.name) {
        case 'hsmStatus':
        case 'hsmAlert':
            String payload = getHsmState(event.value)
            log.info "Publishing (${location.hub.name}) ${path}/hsmArmState=${payload}"
            mqtt.publish("${path}/hsmArmState", payload)
            break
    }
}

private void publishHubState(ChildDeviceWrapper mqtt) {
    log.info "Publishing ${location.hub.name} current state"
    String prefix = "hubitat/tele/${location.hub.hardwareID}"
    mqtt.publish("${prefix}/mode", location.mode)
    mqtt.publish("${prefix}/timeZone", location.timeZone.ID)
    mqtt.publish("${prefix}/zipCode", location.zipCode)
    mqtt.publish("${prefix}/sunrise", location.sunrise.toString())
    mqtt.publish("${prefix}/sunset", location.sunset.toString())
    location.with {
        mqtt.publish("${prefix}/hsmArmState", getHsmState(hsmStatus))
        mqtt.publish("${prefix}/latitude", formattedLatitude)
        mqtt.publish("${prefix}/longitude", formattedLongitude)
        mqtt.publish("${prefix}/temperatureScale", temperatureScale)
        mqtt.publish("${prefix}/name", hub.name)
    }
    location.hub.with {
        mqtt.publish("${prefix}/zigbeeId", zigbeeId)
        mqtt.publish("${prefix}/hardwareID", hardwareID)
        mqtt.publish("${prefix}/localIP", localIP)
        mqtt.publish("${prefix}/firmwareVersion", firmwareVersionString)
    }
}

private void publishDeviceState(ChildDeviceWrapper mqtt) {
    devices.each { device ->
        int idleHours = getIdleHours(device)
        if (idleHours > maxIdleHours) {
            log.warn "Skipping ${device.displayName} as last updated ${idleHours} hours ago"
        } else {
            String dni = device.deviceNetworkId
            log.info "Publishing ${device.displayName} current state"
            device.currentStates.each { state ->
                String path = "hubitat/tele/${dni}/${state.name}"
                String value = state.value
                switch (state.name) {
                    case 'thermostatMode':
                        value = value.replace('control', 'auto')
                        break
                }
                if (logEnable) { log.debug "Publishing (${device.displayName}) ${path}=${value}" }
                mqtt.publish(path, value)
            }
        }
    }
}

/**
 * MQTT Child Device
 */
private ChildDeviceWrapper findBridge() {
    return getChildDevice("mqtt-bridge-${app.id}")
}

private void deployDriver() {
    String dni = "mqtt-bridge-${app.id}"
    ChildDeviceWrapper childDev = getChildDevice(dni)

    if (childDev == null) {
        String name = 'MQTT Bridge Device'
        childDev = addChildDevice(
            'mqtt',
            'MQTT Bridge',
            dni,
            [
                name: name,
                label: name,
            ]
        )
    }

    if (childDev == null) {
        log.error ('MQTT Bridge Device was not created')
        return
    }

    childDev.updateSetting('mqttBroker', mqttBroker)
    childDev.updateSetting('mqttUsername', mqttUsername)
    childDev.updateSetting('mqttPassword', mqttPassword)
    childDev.updated()
}

// Subscribe to MQTT topic
private void subscribeMqtt(String topic) {
    if (logEnable) { log.debug "MQTT Subscribing to ${topic}" }
    ChildDeviceWrapper mqtt = findBridge()
    mqtt.subscribe(topic)
}

// Returns number of hours since activity
private int getIdleHours(DeviceWrapper device) {
    long difference = now() - device.lastActivity.time
    return Math.round( difference / 3600000 )
}
