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
        section() {
            input name: "telePeriod", type: "number", title: "Periodic state refresh publish interval (minutes)", description: "Number of minutes (default 15)", required: false, defaultValue: 15
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
    if (idx >= 0) {
        def dni = topicParts[idx+1]
        def cmd = topicParts[idx+2]
        def device = devices.find { dni == it.getDeviceNetworkId() }
        if (device) {
            // Thermostats are unique beasts and require special handling
            if (cmd == "setThermostatSetpoint") {
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
            } else if (device.hasCommand(cmd)) {
                log.info "Executing ${device.displayName}: ${cmd} to ${payload}"
                device."$cmd"(payload)
            }
        }
    }
}

/**
 * Implementation
 */
private def getDriver() {
    def dni = "mqtt-bridge-${app.id}"
    return getChildDevice(dni)
}

private void createDriver () {
    def name = "MQTT Bridge Device"
    def dni = "mqtt-bridge-${app.id}"
    def childDev = getChildDevice(dni)

	if (childDev == null) 
        addChildDevice(
            "mqtt",
            "MQTT Bridge", 
            dni,
            [
                name: name,
                label: name, 
            ]
        )

	if (getDriver() == null) 
        log.error ("MQTT Bridge Device was not created")
}

private void publishAutoDiscovery() {
    log.info "Publishing Auto Discovery"
    def mqtt = getDriver()
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
            config["min_temp"] = "60"
            config["max_temp"] = "90"
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
                config["name"] = device.getDisplayName() + " " + state.name
                config["unique_id"] = dni + "::" + state.name
                config["state_topic"] = "hubitat/tele/${dni}/${state.name}"            
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
                        break
                    case "contact":
                        path += "/binary_sensor"
                        if ( (config.name.toLowerCase().contains("garage") 
                            || config.name.toLowerCase().contains("overhead"))
                            && config.name.toLowerCase().contains("door") )
                            config["device_class"] = "garage_door"
                        else if (config.name.toLowerCase().contains("door"))
                            config["device_class"] = "door"
                        else
                            config["device_class"] = "window"
                        config["payload_on"] = "open"
                        config["payload_off"] = "closed"
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
                        break
                    case "presence":
                        path += "/binary_sensor"
                        config["device_class"] = "occupancy"
                        config["payload_on"] = "present"
                        config["payload_off"] = "not present"
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
}

private void subscribeMqtt(topic) {
    if (logEnable) log.debug "MQTT Subscribing to ${topic}"
    def mqtt = getDriver()
    mqtt.subscribe(topic)
}

private void publishEvent(event) {
    def mqtt = getDriver()
    def dni = event.getDevice().getDeviceNetworkId()
    def path = "hubitat/tele/${dni}/${event.name}"
    if (logEnable) log.debug "Publishing (${event.displayName}) ${path}=${event.value}"
    mqtt.publish(path, event.value)
}

private void publishCurrentState() {
    def mqtt = getDriver()
    devices.each({ device ->
        def dni = device.getDeviceNetworkId()
        device.getCurrentStates().each( { state ->
            def path = "hubitat/tele/${dni}/${state.name}"
            if (logEnable) log.debug "Publishing (${device.displayName}) ${path}=${state.value}"
            mqtt.publish(path, state.value)
        })
    })

    if (telePeriod > 0) {
        if (logEnable) log.debug "Scheduling publish in ${telePeriod} minutes"
        runIn(telePeriod * 60, "publishCurrentState")
    }
}

private void logsOff() {
    log.warn "debug logging disabled for ${app.name}"
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
}


