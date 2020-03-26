/*
 *  Centralite Pearl Thermostat
 */
metadata {
    definition (name: "CentraLite Pearl Thermostat", namespace: "dagrider", author: "dagrider") {
        capability "Actuator"
        capability "Configuration"
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "Refresh"
        capability "Sensor"
        capability "Battery"

        // Custom commands
        command "holdOn"
        command "holdOff"

        attribute "thermostatHoldMode", "string"
        attribute "powerSource", "string"
        attribute "thermostatRunMode", "string"

        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0020,0201,0202,0204,0B05", outClusters: "000A, 0019"
    }
}

def installed() {
    log.info "${device.displayName} driver installed"
    configure()
}

def updated() {
    log.info "${device.displayName} driver updated"
    state.clear()
    configure()
}

def configure() {
    log.info "${device.displayName} driver configure"
    def cmds = [
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0001 {${device.zigbeeId}} {}", "delay 500",
        "zcl global send-me-a-report 0x0001 0x20 0x20 3600 86400 {}", "delay 500", //battery report request
        "send 0x${device.deviceNetworkId} 1 1",
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0201 {${device.zigbeeId}} {}", "delay 500",
        "zcl global send-me-a-report 0x0201 0x0000 0x29 5 300 {3200}", "delay 500", // report temperature changes over 0.5°C (0x3200 in little endian)
        "send 0x${device.deviceNetworkId} 1 1",
        "zcl global send-me-a-report 0x0201 0x0011 0x29 5 300 {3200}", "delay 500", // report cooling setpoint delta: 0.5°C
        "send 0x${device.deviceNetworkId} 1 1",
        "zcl global send-me-a-report 0x0201 0x0012 0x29 5 300 {3200}", "delay 500", // report heating setpoint delta: 0.5°C
        "send 0x${device.deviceNetworkId} 1 1",
        "zcl global send-me-a-report 0x0201 0x001C 0x30 5 300 {}", "delay 500",     // report system mode
        "send 0x${device.deviceNetworkId} 1 1",
        "zcl global send-me-a-report 0x0201 0x0029 0x19 5 300 {}", "delay 500",    // report running state
        "send 0x${device.deviceNetworkId} 1 1",
        "zcl global send-me-a-report 0x0201 0x0023 0x30 5 300 {}", "delay 500",     // report hold mode
        "send 0x${device.deviceNetworkId} 1 1",
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0202 {${device.zigbeeId}} {}", "delay 500",
        "zcl global send-me-a-report 0x0202 0 0x30 5 300 {}","delay 500",          // report fan mode
        "send 0x${device.deviceNetworkId} 1 1",
    ]

    return cmds
}

def refresh() {
    log.info "${device.displayName} driver refresh"
    // 0000 07 power source
    // 0201 00 temperature
    // 0201 11 cooling setpoint
    // 0201 12 heating setpoint
    // 0201 1C thermostat mode
    // 0201 1E run mode
    // 0201 23 hold mode
    // 0001 20 battery
    // 0202 00 fan mode

    def cmds = zigbee.readAttribute(0x0000, 0x0007) +
               zigbee.readAttribute(0x0201, 0x0000) +
               zigbee.readAttribute(0x0201, 0x0011) +
               zigbee.readAttribute(0x0201, 0x0012) +
               zigbee.readAttribute(0x0201, 0x001C) +
               zigbee.readAttribute(0x0201, 0x001E) +
               zigbee.readAttribute(0x0201, 0x0023) +
               zigbee.readAttribute(0x0201, 0x0029) +
               zigbee.readAttribute(0x0001, 0x0020) +
               zigbee.readAttribute(0x0202, 0x0000)

    return cmds
}

def parse(String description) {
    log.debug "Parse description $description"
    def map = [:]

    if (description?.startsWith("read attr -")) {
        def descMap = zigbee.parseDescriptionAsMap(description)

        if (descMap.cluster == "0201" && descMap.attrId == "0000") {
            log.debug "TEMPERATURE"
            map.name = "temperature"
            map.unit = getTemperatureScale()
            map.value = getTemperature(descMap.value)
            def offset = 4
            if (device.currentValue("thermostatMode") == "heat" && device.currentValue("temperature") > device.currentValue("heatingSetpoint") + offset)
                cool()
            else if (device.currentValue("thermostatMode") == "cool" && device.currentValue("temperature") < device.currentValue("coolingSetpoint") - offset)
                heat()
        } else if (descMap.cluster == "0201" && descMap.attrId == "0011") {
            log.debug "COOLING SETPOINT"
            map.name = "coolingSetpoint"
            map.unit = getTemperatureScale()
            map.value = getTemperature(descMap.value)
            if (device.currentValue("thermostatMode") == "cool") {
                sendEvent(
                    name: "thermostatSetpoint",
                    value: map.value
                )
            }
        } else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
            log.debug "HEATING SETPOINT"
            map.name = "heatingSetpoint"
            map.unit = getTemperatureScale()
            map.value = getTemperature(descMap.value)
            if (device.currentValue("thermostatMode") == "heat" || device.currentValue("thermostatMode") == "emergencyHeat") {
                sendEvent(
                    name: "thermostatSetpoint",
                    value: map.value
                )
            }
        } else if (descMap.cluster == "0201" && descMap.attrId == "001C") {
            log.debug "MODE"
            map.name = "thermostatMode"
            map.value = getModeMap()[descMap.value]
            switch (map.value) {
                case "heat":
                case "emergencyHeat":
                    sendEvent(
                        name: "thermostatSetpoint",
                        value: device.currentValue("heatingSetpoint")
                    )
                break
                case "cool":
                    sendEvent(
                        name: "thermostatSetpoint",
                        value: device.currentValue("coolingSetpoint")
                    )
                break
            }
        } else if (descMap.cluster == "0202" && descMap.attrId == "0000") {
            log.debug "FAN MODE"
            map.name = "thermostatFanMode"
            map.value = getFanModeMap()[descMap.value]
        } else if (descMap.cluster == "0001" && descMap.attrId == "0020") {
            log.debug "BATTERY"
            map.name = "battery"
            map.value = getBatteryLevel(descMap.value)
        } else if (descMap.cluster == "0201" && descMap.attrId == "001E") {
            log.debug "RUN MODE"
            map.name = "thermostatRunMode"
            map.value = getModeMap()[descMap.value]
        } else if (descMap.cluster == "0201" && descMap.attrId == "0023") {
            log.debug "HOLD MODE"
            map.name = "thermostatHoldMode"
            map.value = getHoldModeMap()[descMap.value]
        } else if (descMap.cluster == "0201" && descMap.attrId == "0029") {
            log.debug "OPERATING MODE"
            map.name = "thermostatOperatingState"
            map.value = getThermostatOperatingStateMap()[descMap.value]
            sendEvent(
                name: "thermostatFanMode",
                value: map.value == "idle" ? "auto" : "on"
            )
        } else if (descMap.cluster == "0000" && descMap.attrId == "0007") {
            log.debug "POWER SOURCE"
            map.name = "powerSource"
            map.value = getPowerSource()[descMap.value]
        }
    }

    def result = null

    if (map) {
        result = createEvent(map)
    }

    log.debug "Parse returned $map"
    return result
}

def getModeMap() {
    [
        "00":"off",
        "01":"auto",
        "03":"cool",
        "04":"heat",
        "05":"emergency heat",
        "06":"precooling",
        "07":"fan only",
        "08":"dry",
        "09":"sleep"
    ]
}

def modes() {
    ["off", "cool", "heat", "emergencyHeat"]
}

def getHoldModeMap() {
    [
        "00":"off",
        "01":"on",
    ]
}

def getPowerSource() {
    [
        "01":"24V AC",
        "03":"Battery",
        "81":"24V AC"
    ]
}

def getFanModeMap() {
    [
        "04":"on",
        "05":"auto"
    ]
}

def getThermostatOperatingStateMap() {
    /**  Bit Number
    //  0 Heat State
    //  1 Cool State
    //  2 Fan State
    //  3 Heat 2nd Stage State
    //  4 Cool 2nd Stage State
    //  5 Fan 2nd Stage State
    //  6 Fan 3rd Stage Stage
    **/
    [
        "0000":"idle",
        "0001":"heating",
        "0002":"cooling",
        "0004":"fan only",
        "0005":"heating",
        "0006":"cooling",
        "0008":"heating",
        "0009":"heating",
        "000A":"heating",
        "000D":"heating",
        "0010":"cooling",
        "0012":"cooling",
        "0014":"cooling",
        "0015":"cooling"
    ]
}

def getTemperature(value) {
    if (value != null) {
        def temp = new BigInteger(value, 16) & 0xFFFF
        def celsius = temp / 100

        if (getTemperatureScale() == "C") {
            return Math.round(celsius)
        } else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

def off() {
    log.debug "off"
    sendEvent("name":"thermostatMode", "value":"off")
    zigbee.writeAttribute(0x0201, 0x1C, 0x30, 0)
}

def setThermostatFanMode(String value) {
    log.debug "setThermostatFanMode ${value}"
    switch (value) {
        case "on": fanOn()
            break
        case "circulate": fanCirculate()
            break
        case "auto": fanAuto()
            break
    }
}

def setThermostatMode(String value) {
    log.debug "setThermostatMode ${value}"
    switch (value) {
        case "heat": heat()
            break
        case "cool": cool()
            break
        case "off": off()
            break
        case "emergency heat": emergencyHeat()
            break
        case "auto": auto()
            break
    }
}

def auto() {
    if (device.currentValue("temperature") > device.currentValue("heatingSetpoint"))
        cool()
    else if (device.currentValue("temperature") < device.currentValue("coolingSetpoint"))
        heat()
}

def cool() {
    log.debug "cool"
    sendEvent("name":"thermostatMode", "value":"cool")
    zigbee.writeAttribute(0x0201, 0x1C, 0x30, 3)
}

def heat() {
    log.debug "heat"
    sendEvent("name":"thermostatMode", "value":"heat")
    zigbee.writeAttribute(0x0201, 0x1C, 0x30, 4)
}

def emergencyHeat() {
    log.debug "emergencyHeat"
    sendEvent("name":"thermostatMode", "value":"emergencyHeat")
    zigbee.writeAttribute(0x0201, 0x1C, 0x30, 5)
}

def fanCirculate() {
    fanOn()
}

def fanOn() {
    log.debug "fanOn"
    sendEvent("name":"thermostatFanMode", "value":"fanOn")
    zigbee.writeAttribute(0x0202, 0x00, 0x30, 4)
}

def fanAuto() {
    log.debug "fanAuto"
    sendEvent("name":"thermostatFanMode", "value":"fanAuto")
    zigbee.writeAttribute(0x0202, 0x00, 0x30, 5)
}

def holdOn() {
    log.debug "holdOn"
    sendEvent("name":"thermostatHoldMode", "value":"holdOn")
    zigbee.writeAttribute(0x0201, 0x23, 0x30, 1)
}

def holdOff() {
    log.debug "Set Hold Off for thermostat"
    sendEvent("name":"thermostatHoldMode", "value":"holdOff")
    zigbee.writeAttribute(0x0201, 0x23, 0x30, 0)
}

private getBatteryLevel(rawValue) {
    def intValue = Integer.parseInt(rawValue,16)
    def min = 2.1
    def max = 3.0
    def vBatt = intValue / 10
    return ((vBatt - min) / (max - min) * 100) as int
}

private cvtTemp(value) {
    new BigInteger(Math.round(value))
}

private isHoldOn() {
    return (device.currentState("thermostatHoldMode")?.value == "holdOn")
}

def setHeatingSetpoint(degrees) {
    log.debug "setHeatingSetpoint to $degrees"

    if (isHoldOn()) return

    if (degrees != null) {
        def temperatureScale = getTemperatureScale()
        def degreesInteger = Math.round(degrees)
        def maxTemp
        def minTemp

        if (temperatureScale == "C") {
            maxTemp = 44
            minTemp = 7
            log.debug "Location is in Celsius, maxTemp: $maxTemp, minTemp: $minTemp"
        } else {
            maxTemp = 86
            minTemp = 30
            log.debug "Location is in Farenheit, maxTemp: $maxTemp, minTemp: $minTemp"
                }

        if (degreesInteger > maxTemp) degreesInteger = maxTemp
        if (degreesInteger < minTemp) degreesInteger = minTemp

        log.debug "setHeatingSetpoint degrees $degreesInteger $temperatureScale"
        def celsius = (temperatureScale == "C") ? degreesInteger : (fahrenheitToCelsius(degreesInteger) as Double).round(2)
        zigbee.writeAttribute(0x0201, 0x12, 0x29, cvtTemp(celsius * 100))
    }
}

def setCoolingSetpoint(degrees) {
    log.debug "setCoolingSetpoint to $degrees"

    if (isHoldOn()) return

    if (degrees != null) {
        def temperatureScale = getTemperatureScale()
        def degreesInteger = Math.round(degrees)
        def maxTemp
        def minTemp

        if (temperatureScale == "C") {
            maxTemp = 44
            minTemp = 7
            log.debug "Location is in Celsius, maxTemp: $maxTemp, minTemp: $minTemp"
        } else {
            maxTemp = 86
            minTemp = 30
            log.debug "Location is in Farenheit, maxTemp: $maxTemp, minTemp: $minTemp"
        }

        if (degreesInteger > maxTemp) degreesInteger = maxTemp
        if (degreesInteger < minTemp) degreesInteger = minTemp

        log.debug "setCoolingSetpoint degrees $degreesInteger $temperatureScale"
        def celsius = (temperatureScale == "C") ? degreesInteger : (fahrenheitToCelsius(degreesInteger) as Double).round(2)
        zigbee.writeAttribute(0x0201, 0x11, 0x29, cvtTemp(celsius * 100))
    }
}