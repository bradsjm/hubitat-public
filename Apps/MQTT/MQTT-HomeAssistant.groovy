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

@Field final Random _random = new Random()

static final String version() { "0.1" }
definition (
    name: "Home Assistant Publisher", 
    namespace: "mqtt", 
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
            input name: "hsmEnable", type: "bool", title: "Enable Hubitat Security Manager", required: true, defaultValue: true
            input name: "logEnable", type: "bool", title: "Enable Debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
        }
    }
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} driver v${version()} installed"
    settings.name = app.name
}

// Called when the app is removed.
void uninstalled() {
    unsubscribe()
    removeAllChildDevices()
    log.info "${app.name} driver v${version()} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} driver v${version()} configuration updated"
    log.debug settings
    app.updateLabel(settings.name)
    initialize()

    if (logEnable) runIn(1800, "logsOff")
}

void initialize() {
    unsubscribe()
    createDriver()
    subscribeDevices()
    subscribeMqtt("homeassistant/status")
    subscribeMqtt("hubitat/cmnd/#")
    runIn(_random.nextInt(4) + 1, "publishAutoDiscovery")
}

void parseMessage(topic, payload) {
    if (logEnable) log.debug "Receive ${topic} = ${payload}"
    if (topic == "homeassistant/status" && payload.toLowerCase() == "online") {
        // wait for home assistant to be ready after being online
        runIn(_random.nextInt(14) + 1, "publishAutoDiscovery")
        return
    }

    def topicParts = topic.tokenize("/")
    def idx = topicParts.indexOf("cmnd")
    if (!idx) return

    def dni = topicParts[idx+1]
    def cmd = topicParts[idx+2]

    // Handle HUB commands
    if (dni == location.hub.hardwareID) {
        switch (cmd) {
            case "hsmSetArm":
                if (!hsmEnable) return
                log.info "Sending location even ${cmd} of ${payload}"
                sendLocationEvent(name: cmd, value: payload)
                return
            default:
                log.error "Unknown command ${cmd} for hub received"
                return
        }
    }

    // Handle device commands
    def device = devices.find { dni == it.getDeviceNetworkId() }
    if (!device) {
        log.error "Unknown device id ${dni} received"
        return
    }

    switch (cmd) {
        case "setThermostatSetpoint":
            def mode = device.currentValue("thermostatMode")
            switch (mode) {
                case "cool":
                    log.info "Executing setCoolingSetpoint to ${payload} on ${device.displayName}"
                    device.setCoolingSetpoint(payload as double)
                    break
                case "heat":
                    log.info "Executing setHeatingSetpoint to ${payload} on ${device.displayName}"
                    device.setHeatingSetPoint(payload as double)
                    break
                default:
                    log.error "Thermostat not set to cool or heat (mode is ${mode})"
            }
            break
        default:
            if (device.hasCommand(cmd)) {
                log.info "Executing ${device.displayName}: ${cmd} to ${payload}"
                device."$cmd"(payload)
            }
            break
    }
}

/**
 * Implementation
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

private void publishAutoDiscovery() {
    def mqtt = getDriver()
    log.info "Publishing Auto Discovery"
    publishHubDiscovery(mqtt)
    publishDeviceDiscovery(mqtt)
}

private void publishHubDiscovery(def mqtt) {
    def dni = location.hub.hardwareID
    def deviceConfig = [
        "ids": [ dni, location.hub.zigbeeId ],
        "manufacturer": "Hubitat",
        "name": location.hub.name,
        "model": "Elevation",
        "sw_version": location.hub.firmwareVersionString
    ]

    if (hsmEnable) {
        // Publish HSM
        def config = [ "device": deviceConfig ]
        config["name"] = location.hub.name + " Alarm"
        config["unique_id"] = dni + "::hsm"
        config["state_topic"]= "hubitat/tele/${dni}/hsmStatus"
        config["command_topic"] = "hubitat/cmnd/${dni}/hsmSetArm"
        config["payload_arm_away"] = "armAway"
        config["payload_arm_home"] = "armHome"
        config["payload_arm_night"] = "armNight"
        config["payload_disarm"] = "disarm"
        def json = new groovy.json.JsonBuilder(config).toString()
        def path = "homeassistant/alarm_control_panel/${dni}_hsm/config"
        log.info "Publishing discovery for ${config.name} to: ${path}"
        if (logEnable) log.debug json
        mqtt.publish(path, json, 0, true)
    }

    // Publish sensors
    [ "mode" ].each({ name ->
        def config = [ "device": deviceConfig ]
        config["name"] = location.hub.name + " " + name.capitalize()
        config["unique_id"] = dni + "::" + name
        config["state_topic"] = "hubitat/tele/${dni}/${name}"
        config["expire_after"] = telePeriod * 120
        switch (name) {
            case "mode":
                config["icon"] = "mdi:tag"
                break
        }
        def json = new groovy.json.JsonBuilder(config).toString()
        def path = "homeassistant/sensor/${dni}_${name}/config"
        log.info "Publishing discovery for ${config.name} to: ${path}"
        if (logEnable) log.debug json
        mqtt.publish(path, json, 0, true)
    })
}

private void publishDeviceDiscovery(def mqtt) {
    devices.each({ device ->
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
            def config = [ "device": deviceConfig ]
            def tele = "hubitat/tele/${dni}"
            def cmnd = "hubitat/cmnd/${dni}"
            config["name"] = device.getDisplayName()
            config["unique_id"] = dni + "::thermostat"
            config["current_temperature_topic"] = tele + "/temperature"
            config["fan_mode_command_topic"] = cmnd + "/setThermostatFanMode"
            config["fan_mode_state_topic"] = tele + "/thermostatFanMode"
            config["fan_modes"] = ["auto", "circulate", "on"]
            config["mode_command_topic"] = cmnd + "/setThermostatMode"
            config["mode_state_topic"] = tele + "/thermostatMode"
            config["modes"] = ["auto", "off", "heat", "emergency heat", "cool"]
            config["min_temp"] = getTemperatureScale() == "F" ? "60" : "15"
            config["max_temp"] = getTemperatureScale() == "F" ? "90" : "32"
            config["temperature_command_topic"] = cmnd + "/setThermostatSetpoint"
            config["temperature_state_topic"] = tele + "/thermostatSetpoint"
            config["temperature_unit"] = getTemperatureScale()
            config["temp_step"] = "1"
            def path = "homeassistant/climate/${dni}_thermostat/config"
            def json = new groovy.json.JsonBuilder(config).toString()
            if (logEnable) log.debug json
            log.info "Publishing discovery for ${config.name} to: ${path}"
            mqtt.publish(path, json, 0, true)
        } else {
            device.getCurrentStates().each({ state ->
                def config = [ "device": deviceConfig ]
                def path = "homeassistant"
                def stateName = splitCamelCase(state.name)
                config["name"] = device.getDisplayName() + " " + stateName.capitalize()
                config["unique_id"] = dni + "::" + state.name
                config["state_topic"] = "hubitat/tele/${dni}/${state.name}"
                config["expire_after"] = telePeriod * 120
                switch (state.name) {
                    case "acceleration":
                        path += "/binary_sensor"
                        config["device_class"] = "moving"
                        config["payload_on"] = "active"
                        config["payload_off"] = "inactive"
                        break
                    case "battery":
                        path += "/sensor"
                        config["device_class"] = "battery"
                        config["unit_of_measurement"] = "%"
                        break
                    case "carbonMonoxide":
                        path += "/binary_sensor"
                        config["device_class"] = "gas"
                        config["payload_on"] = "detected"
                        config["payload_off"] = "clear"
                        break
                    case "contact":
                        path += "/binary_sensor"
                        def name = config.name.toLowerCase()
                        if (name.contains("garage door") || name.contains("overhead"))
                            config["device_class"] = "garage_door"
                        else if (name.contains("door"))
                            config["device_class"] = "door"
                        else
                            config["device_class"] = "window"
                        config["payload_on"] = "open"
                        config["payload_off"] = "closed"
                        break
                    case "door":
                        path += "/cover"
                        config["command_topic"] = "hubitat/cmnd/${dni}/${state.name}"
                        config["device_class"] = "door"
                        config["payload_close"] = "close"
                        config["payload_open"] = "open"
                        config["state_closed"] = "closed"
                        config["state_open"] = "open"
                        break
                    case "humidity":
                        path += "/sensor"
                        config["device_class"] = "humidity"
                        config["unit_of_measurement"] = "%"
                        break
                    case "motion":
                        path += "/binary_sensor"
                        config["device_class"] = "motion"
                        config["payload_on"] = "active"
                        config["payload_off"] = "inactive"
                        break
                    case "illuminance":
                        path += "/sensor"
                        config["device_class"] = "illuminance"
                        config["unit_of_measurement"] = "lx"
                        break
                    case "power":
                        path += "/sensor"
                        config["device_class"] = "power"
                        config["unit_of_measurement"] = "W"
                        break
                    case "presence":
                        path += "/binary_sensor"
                        config["device_class"] = "occupancy"
                        config["payload_on"] = "present"
                        config["payload_off"] = "not present"
                        break
                    case "pressure":
                        path += "/sensor"
                        config["device_class"] = "pressure"
                        config["unit_of_measurement"] = "mbar"
                        break
                    case "switch":
                        path += "/binary_sensor"
                        def name = config.name.toLowerCase()
                        if (name.contains("light") || name.contains("lamp"))
                            config["device_class"] = "light"
                        else
                            config["device_class"] = "power"
                        config["payload_on"] = "on"
                        config["payload_off"] = "off"
                        break
                    case "smoke":
                        path += "/binary_sensor"
                        config["device_class"] = "smoke"
                        config["payload_on"] = "detected"
                        config["payload_off"] = "clear"
                        break
                    case "temperature":
                        path += "/sensor"
                        config["device_class"] = "temperature"
                        config["unit_of_measurement"] = getTemperatureScale()
                        break
                    case "threeAxis":
                        path += "/sensor"
                        config["icon"] = "mdi:axis-arrow"
                        break
                    case "voltage":
                        path += "/sensor"
                        config["device_class"] = "voltage"
                        config["unit_of_measurement"] = "V"
                        break
                    case "water":
                        path += "/binary_sensor"
                        config["device_class"] = "moisture"
                        config["payload_on"] = "wet"
                        config["payload_off"] = "dry"
                        break
                    case "windowShade":
                        path += "/cover"
                        config["command_topic"] = "hubitat/cmnd/${dni}/${state.name}"
                        config["device_class"] = "shade"
                        config["payload_close"] = "close"
                        config["payload_open"] = "open"
                        config["state_closed"] = "closed"
                        config["state_open"] = "open"
                        break
                    default:
                        path += "/sensor"
                        break
                }
                path += "/${dni}_${state.name}/config"
                def json = new groovy.json.JsonBuilder(config).toString()
                log.info "Publishing discovery for ${config.name} to: ${path}"
                if (logEnable) log.debug json
                mqtt.publish(path, json, 0, true)
            })
        }
    })

    // Schedule sending device current state
    runIn(_random.nextInt(4) + 1, "publishCurrentState")
}

private void subscribeDevices() {
    if (logEnable) log.debug "Subscribing to device events"
    subscribe(devices, "publishEvent", [ filterEvents: true ])
    subscribe(location, "locationEvent", [ filterEvents: true ])
}

private void subscribeMqtt(topic) {
    if (logEnable) log.debug "MQTT Subscribing to ${topic}"
    def mqtt = getDriver()
    mqtt.subscribe(topic)
}

private void locationEvent(event) {
    def mqtt = getDriver()
    def dni = location.hub.hardwareID
    def path = "hubitat/tele/${dni}/"
    def payload
    switch (event.name) {
        case "mode":
            path += "mode"
            payload = event.value
            break
        case "hsmStatus":
            path += "hsmStatus"
            payload = getHsmState(event.value)
            break
        case "hsmAlert":
            path += "hsmStatus"
            payload = getHsmState(event.value)
            break
        default:
            log.info "Unknown location event ${event.name} of ${event.value}"
            return
    }
    log.info "Publishing (${location.hub.name}) ${path}=${payload}"
    mqtt.publish(path, payload)
}

private void publishEvent(event) {
    def mqtt = getDriver()
    def dni = event.getDevice().getDeviceNetworkId()
    def path = "hubitat/tele/${dni}/${event.name}"
    log.info "Publishing (${event.displayName}) ${path}=${event.value}"
    mqtt.publish(path, event.value)
}

private void publishCurrentState() {
    def mqtt = getDriver()
    publishHubState(mqtt)
    publishDeviceState(mqtt)

    if (telePeriod > 0) {
        if (logEnable) log.debug "Scheduling publish in ${telePeriod} minutes"
        runIn(telePeriod * 60, "publishCurrentState")
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
    mqtt.publish("${prefix}/hsmStatus", getHsmState(location.hsmStatus))
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
        def dni = device.getDeviceNetworkId()
        log.info "Publishing ${device.displayName} current state"
        device.getCurrentStates().each( { state ->
            def path = "hubitat/tele/${dni}/${state.name}"
            if (logEnable) log.debug "Publishing (${device.displayName}) ${path}=${state.value}"
            mqtt.publish(path, state.value)
        })
    })
}

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
}

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

private void logsOff() {
    log.warn "debug logging disabled for ${app.name}"
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
}
