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
import groovy.time.*
import groovy.json.JsonBuilder

@Field final Random _random = new Random()

static final String version() { "0.1" }
definition (
    name: "HomeAssistant MQTT Bridge", 
    namespace: "nrgup", 
    author: "Jonathan Bradshaw",
    category: "Intranet Connectivity",
    description: "Publish Hubitat devices to Home Assistant via MQTT",
    iconUrl: "",
    iconX2Url: "",
    installOnOpen: true,
    iconX3Url: ""
)

preferences {
    page(name: "configuration", install: true, uninstall: true) {
        section() {
            input name: "name", type: "string", title: "Application Label", required: false, submitOnChange: true
        }
        section () {
            input "devices", "capability.*", hideWhenEmpty: true, title: "Devices to Publish:", multiple: true, required: false
        }
        section("MQTT Broker") {
            input name: "mqttBroker", type: "text", title: "MQTT Broker Host/IP", description: "ex: tcp://hostnameorip:1883", required: true, defaultValue: "tcp://mqtt:1883"
            input name: "mqttUsername", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "mqttPassword", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }
        section() {
            input name: "telePeriod", type: "number", title: "Periodic state refresh publish interval (minutes)", description: "Number of minutes (default 15)", required: false, defaultValue: 15
            input name: "maxIdleHours", type: "number", title: "Maximum hours since last activity to publish", description: "Number of hours (default 24)", required: false, defaultValue: 24
            input name: "hsmEnable", type: "bool", title: "Enable Hubitat Security Manager", required: false, defaultValue: true
            input name: "logEnable", type: "bool", title: "Enable Debug logging", required: false, defaultValue: true
        }
    }
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} v${version()} installed"
    settings.name = app.name
}

// Called when the app is removed.
void uninstalled() {
    unsubscribe()
    removeAllChildDevices()
    log.info "${app.name} v${version()} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} v${version()} configuration updated"
    log.debug settings
    app.updateLabel(settings.name)
    initialize()
}

// Called when the driver is initialized.
void initialize() {
    unsubscribe()
    createDriver()
    log.info "Subscribing to device and location events"
    subscribe(devices, "publishDeviceEvent", [ filterEvents: true ])
    subscribe(location, "publishLocationEvent", [ filterEvents: true ])
}

// Called when MQTT is connected
void connected() {
    log.info "MQTT is connected"
    subscribeMqtt("homeassistant/status")
    subscribeMqtt("hubitat/cmnd/#")
    runIn(_random.nextInt(4) + 1, "publishDiscovery")
}

/**
 * MQTT topic and command parsing
 */
void parseMessage(topic, payload) {
    if (logEnable) log.debug "Receive ${topic} = ${payload}"

    if (topic == "homeassistant/status") {
        switch (payload) {
            case "online":
                // wait for home assistant to be ready after being online
                log.info "Detected Home Assistant online, scheduling publish"
                runIn(_random.nextInt(4) + 1, "publishDiscovery")
                break
        }
        return
    }

    // Parse topic path for dni and command
    if (topic.startsWith("hubitat/cmnd")) {
        def topicParts = topic.tokenize("/")
        def idx = topicParts.indexOf("cmnd")
        if (!idx) return

        def dni = topicParts[idx+1]
        def cmd = topicParts[idx+2]
        if (dni == location.hub.hardwareID)
            parseHubCommand(cmd, payload)
        else 
            parseDeviceCommand(dni, cmd, payload)
        return
    }

    log.warn "Received unknown topic: ${topic}"
}

private void parseHubCommand(String cmd, String payload) {
    switch (cmd) {
        case "hsmSetArm":
            if (!hsmEnable) return
            log.info "Sending location event ${cmd} of ${payload}"
            sendLocationEvent(name: cmd, value: payload)
            return
        default:
            log.error "Unknown command ${cmd} for hub received"
            return
    }
}

private void parseDeviceCommand(dni, cmd, payload) {
    // Handle device commands
    def device = devices.find { dni == it.getDeviceNetworkId() }
    if (!device) {
        log.error "Unknown device id ${dni} received"
        return
    }

    switch (cmd) {
        case "switch":
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

private void parseDeviceSwitchCommand(device, String payload) {
    if (payload == "on") {
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
private void publishDiscovery() {
    def mqtt = getDriver()
    log.info "Publishing Hub Auto Discovery"
    publishDiscoveryHub(mqtt, location.hub)

    log.info "Publishing Device Auto Discovery"
    devices.each({ device -> publishDiscoveryDevice(mqtt, device)})

    // Schedule sending device current state
    runIn(_random.nextInt(4) + 1, "publishCurrentState")
}

private void publishDiscoveryHub(def mqtt, def hub) {
    def dni = hub.hardwareID
    def deviceConfig = [
        "ids": [ dni, hub.zigbeeId ],
        "manufacturer": "Hubitat",
        "name": hub.name,
        "model": "Elevation",
        "sw_version": hub.firmwareVersionString
    ]

    if (hsmEnable) {
        // Publish HSM
        def config = [ "device": deviceConfig ]
        config["name"] = hub.name + " Alarm"
        config["unique_id"] = dni + "::hsm"
        config["availability_topic"] = "hubitat/LWT"
        config["payload_available"] = "Online"
        config["payload_not_available"] = "Offline"
        config["state_topic"]= "hubitat/tele/${dni}/hsmArmState"
        config["command_topic"] = "hubitat/cmnd/${dni}/hsmSetArm"
        config["payload_arm_away"] = "armAway"
        config["payload_arm_home"] = "armHome"
        config["payload_arm_night"] = "armNight"
        config["payload_disarm"] = "disarm"
        def path = "homeassistant/alarm_control_panel/${dni}_hsm/config"
        publishDeviceConfig(mqtt, path, config)
    }

    // Publish hub sensors
    [ "mode" ].each({ name ->
        def config = [ "device": deviceConfig ]
        config["name"] = hub.name + " " + name.capitalize()
        config["unique_id"] = dni + "::" + name
        config["state_topic"] = "hubitat/tele/${dni}/${name}"
        config["expire_after"] = telePeriod * 120
        config["availability_topic"] = "hubitat/LWT"
        config["payload_available"] = "Online"
        config["payload_not_available"] = "Offline"
        switch (name) {
            case "mode":
                config["icon"] = "mdi:tag"
                break
        }
        def path = "homeassistant/sensor/${dni}_${name}/config"
        publishDeviceConfig(mqtt, path, config)
    })
}

private void publishDiscoveryDevice(def mqtt, def device) {
    if (device == null) return
    def dni = device.getDeviceNetworkId()
    def deviceConfig = [
        "ids": [ dni ],
        "manufacturer": device.getDataValue("manufacturer") ?: "",
        "name": device.getDisplayName(),
        "model": device.getDataValue("model") ?: ""
    ]
    if (device.getZigbeeId()) deviceConfig.ids << device.getZigbeeId()

    // Thermostats are unique beasts and require special handling
    if (device.hasCapability("Thermostat")) {
        Map config = [
            "device": deviceConfig,
            "name": device.getDisplayName()
        ]
        buildThermostatConfig(device, config)
        def path = "homeassistant/climate/${dni}_thermostat/config"
        publishDeviceConfig(mqtt, path, config)
    } else {
        device.getCurrentStates().each({ state ->
            Map config = [
                "device": deviceConfig,
                "name": device.getDisplayName() + " " + splitCamelCase(state.name).capitalize()
            ]
            def type = buildStateConfig(device, state, config)
            def path = "homeassistant/${type}/${dni}_${state.name}/config"
            publishDeviceConfig(mqtt, path, config)
        })
    }
}

private void buildThermostatConfig(device, config) {
    def dni = device.getDeviceNetworkId()
    def tele = "hubitat/tele/${dni}"
    def cmnd = "hubitat/cmnd/${dni}"
    config["availability_topic"] = "hubitat/LWT"
    config["current_temperature_topic"] = tele + "/temperature"
    config["fan_mode_command_topic"] = cmnd + "/setThermostatFanMode"
    config["fan_mode_state_topic"] = tele + "/thermostatFanMode"
    config["fan_modes"] = ["auto", "circulate", "on"]
    config["max_temp"] = getTemperatureScale() == "F" ? "90" : "32"
    config["min_temp"] = getTemperatureScale() == "F" ? "50" : "10"
    config["mode_command_topic"] = cmnd + "/setThermostatMode"
    config["mode_state_topic"] = tele + "/thermostatMode"
    config["modes"] = device.currentValue("supportedThermostatModes")[1..-2].replace("control","auto").split(",")
    config["payload_available"] = "Online"
    config["payload_not_available"] = "Offline"
    config["temp_step"] = "1"
    config["temperature_high_command_topic"] = cmnd + "/setCoolingSetpoint"
    config["temperature_high_state_topic"] = tele + "/coolingSetpoint"
    config["temperature_low_command_topic"] = cmnd + "/setHeatingSetpoint"
    config["temperature_low_state_topic"] = tele + "/heatingSetpoint"
    config["temperature_unit"] = getTemperatureScale()
    config["unique_id"] = "${dni}::thermostat"
}

private String buildStateConfig(def device, def state, Map config) {
    String type
    def dni = device.getDeviceNetworkId()
    def cmndTopic = "hubitat/cmnd/${dni}/${state.name}"
    def expireAfter = settings.telePeriod * 120
    config["unique_id"] = "${dni}::${state.name}"
    config["state_topic"] = "hubitat/tele/${dni}/${state.name}"
    config["availability_topic"] = "hubitat/LWT"
    config["payload_available"] = "Online"
    config["payload_not_available"] = "Offline"
    switch (state.name) {
        case "acceleration":
            type = "binary_sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "moving"
            config["payload_on"] = "active"
            config["payload_off"] = "inactive"
            break
        case "battery":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "battery"
            config["unit_of_measurement"] = "%"
            break
        case "carbonMonoxide":
            type = "binary_sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "gas"
            config["payload_on"] = "detected"
            config["payload_off"] = "clear"
            break
        case "contact":
            type = "binary_sensor"
            def name = device.getDisplayName().toLowerCase()
            if (name.contains("garage door") || name.contains("overhead"))
                config["device_class"] = "garage_door"
            else if (name.contains("door"))
                config["device_class"] = "door"
            else
                config["device_class"] = "window"
            config["expire_after"] = expireAfter
            config["payload_on"] = "open"
            config["payload_off"] = "closed"
            break
        case "door":
            type = "cover"
            config["command_topic"] = cmndTopic
            config["device_class"] = "door"
            config["payload_close"] = "close"
            config["payload_open"] = "open"
            config["state_closed"] = "closed"
            config["state_open"] = "open"
            break
        case "humidity":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "humidity"
            config["unit_of_measurement"] = "%"
            break
        case "motion":
            type = "binary_sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "motion"
            config["payload_on"] = "active"
            config["payload_off"] = "inactive"
            break
        case "illuminance":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "illuminance"
            config["unit_of_measurement"] = "lx"
            break
        case "mute":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["icon"] = "mdi:volume-off"
            break
        case "power":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "power"
            config["unit_of_measurement"] = "W"
            break
        case "presence":
            type = "binary_sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "occupancy"
            config["payload_on"] = "present"
            config["payload_off"] = "not present"
            break
        case "pressure":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "pressure"
            config["unit_of_measurement"] = "mbar"
            break
        case "switch":
            def name = device.getDisplayName().toLowerCase()
            if (name.contains("light") || name.contains("lamp"))
                type = "light"
            else
                type = "switch"
            config["payload_on"] = "on"
            config["payload_off"] = "off"
            config["command_topic"] = cmndTopic
            break
        case "smoke":
            type = "binary_sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "smoke"
            config["payload_on"] = "detected"
            config["payload_off"] = "clear"
            break
        case "temperature":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "temperature"
            config["unit_of_measurement"] = getTemperatureScale()
            break
        case "threeAxis":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["icon"] = "mdi:axis-arrow"
            break
        case "voltage":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "voltage"
            config["unit_of_measurement"] = "V"
            break
        case "volume":
            type = "sensor"
            config["expire_after"] = expireAfter
            config["icon"] = "mdi:volume-medium"
            break
        case "water":
            type = "binary_sensor"
            config["expire_after"] = expireAfter
            config["device_class"] = "moisture"
            config["payload_on"] = "wet"
            config["payload_off"] = "dry"
            break
        case "windowShade":
            type = "cover"
            config["command_topic"] = cmndTopic
            config["device_class"] = "shade"
            config["payload_close"] = "close"
            config["payload_open"] = "open"
            config["state_closed"] = "closed"
            config["state_open"] = "open"
            break
        default:
            type = "sensor"
            config["expire_after"] = expireAfter
            break
    }

    return type
}

/**
 * MQTT Publishing Helpers
 */
private void publishCurrentState() {
    def mqtt = getDriver()
    publishHubState(mqtt)
    publishDeviceState(mqtt)

    if (telePeriod > 0) {
        if (logEnable) log.debug "Scheduling publish in ${telePeriod} minutes"
        runIn(telePeriod * 60, "publishCurrentState")
    }
}

private void publishDeviceConfig(def mqtt, String path, Map config) {
    def json = new JsonBuilder(config).toString()
    log.info "Publishing discovery for ${config.name} to: ${path}"
    if (logEnable) log.debug "Publish ${json}"
    mqtt.publish(path, json, 0, true)
}

private void publishDeviceEvent(event) {
    def mqtt = getDriver()
    def dni = event.getDevice().getDeviceNetworkId()
    def path = "hubitat/tele/${dni}/${event.name}"
    log.info "Publishing (${event.displayName}) ${path}=${event.value}"
    mqtt.publish(path, event.value)
}

private void publishLocationEvent(event) {
    def mqtt = getDriver()
    def dni = location.hub.hardwareID
    def path = "hubitat/tele/${dni}"
    log.info "Publishing (${location.hub.name}) ${path}/${event.name}=${event.value}"
    mqtt.publish("${path}/${event.name}", event.value)

    switch (event.name) {
        case "hsmStatus":
        case "hsmAlert":
            def payload = getHsmState(event.value)
            log.info "Publishing (${location.hub.name}) ${path}/hsmArmState=${payload}"
            mqtt.publish("${path}/hsmArmState", payload)
            break
    }
}

private void publishHubState(def mqtt) {
    log.info "Publishing ${location.hub.name} current state"
    def prefix = "hubitat/tele/${location.hub.hardwareID}"
    mqtt.publish("${prefix}/mode", location.getMode())
    mqtt.publish("${prefix}/timeZone", location.timeZone.ID)
    mqtt.publish("${prefix}/zipCode", location.zipCode)
    mqtt.publish("${prefix}/sunrise", location.sunrise.toString())
    mqtt.publish("${prefix}/sunset", location.sunset.toString())
    mqtt.publish("${prefix}/hsmArmState", getHsmState(location.hsmStatus))
    mqtt.publish("${prefix}/latitude", location.getFormattedLatitude())
    mqtt.publish("${prefix}/longitude", location.getFormattedLongitude())
    mqtt.publish("${prefix}/temperatureScale", location.temperatureScale)

    mqtt.publish("${prefix}/name", location.hub.name)
    mqtt.publish("${prefix}/zigbeeId", location.hub.zigbeeId)
    mqtt.publish("${prefix}/hardwareID", location.hub.hardwareID)
    mqtt.publish("${prefix}/localIP", location.hub.localIP)
    mqtt.publish("${prefix}/firmwareVersion", location.hub.firmwareVersionString)
}

private void publishDeviceState(def mqtt) {
    devices.each({ device ->
        int idleHours = getIdleHours(device)
        if (idleHours > maxIdleHours) {
            log.warn "Skipping ${device.displayName} as last updated ${idleHours} hours ago"
        } else {
            def dni = device.getDeviceNetworkId()
            log.info "Publishing ${device.displayName} current state"
            device.getCurrentStates().each( { state ->
                def path = "hubitat/tele/${dni}/${state.name}"
                def value = state.value
                switch (state.name) {
                    case "thermostatMode":
                        value = value.replace("control", "auto")
                        break
                }
                if (logEnable) log.debug "Publishing (${device.displayName}) ${path}=${value}"
                mqtt.publish(path, value)
            })
        }
    })
}

/**
 * MQTT Child Device
 */
private def getDriver() {
    return getChildDevice("mqtt-bridge-${app.id}")
}

private void createDriver () {
    def dni = "mqtt-bridge-${app.id}"
    def childDev = getChildDevice(dni)

    if (childDev == null) {
        def name = "MQTT Bridge Device"
        childDev = addChildDevice(
            "mqtt",
            "MQTT Bridge", 
            dni,
            [
                name: name,
                label: name, 
            ]
        )
    }
    
    if (childDev == null) {
        log.error ("MQTT Bridge Device was not created")
        return
    }

    childDev.updateSetting("mqttBroker", mqttBroker)
    childDev.updateSetting("mqttUsername", mqttUsername)
    childDev.updateSetting("mqttPassword", mqttPassword)
    childDev.updated()
}

// Subscribe to MQTT topic
private void subscribeMqtt(topic) {
    if (logEnable) log.debug "MQTT Subscribing to ${topic}"
    def mqtt = getDriver()
    mqtt.subscribe(topic)
}

/**
 * Utility Methods
 */

// Returns number of hours since activity
private int getIdleHours(device) {
    long difference = new Date().getTime() - device.getLastActivity().getTime()
    return Math.round( difference / 3600000 )
}

// Translate HSM states to Home Assistant values
private String getHsmState(String value) {
    switch (value) {
        case "armingAway": return "arming"
        case "armedAway": return "armed_away"
        case "armingHome": return "arming"
        case "armedHome": return "armed_home"
        case "armingNight": return "arming"
        case "armedNight": return "armed_night"
        case "disarmed": return "disarmed"
        case "allDisarmed": return"disarmed"
        case "intrusion": return "triggered"
        case "intrusion-home": return "triggered"
        case "intrusion-night": return "triggered"
    }
    return value
}

// Translate camel case name to normal text
private String splitCamelCase(String s) {
   return s.replaceAll(
      String.format("%s|%s|%s",
         "(?<=[A-Z])(?=[A-Z][a-z])",
         "(?<=[^A-Z])(?=[A-Z])",
         "(?<=[A-Za-z])(?=[^A-Za-z])"
      ),
      " "
   );
}
