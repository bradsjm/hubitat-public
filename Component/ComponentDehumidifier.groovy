metadata {
    definition(name: 'Generic Component DeHumidifer Device', namespace: 'component', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Switch'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'Refresh'

        attribute 'humiditySetpoint', 'number'
        attribute 'temperature', 'number'
        attribute 'swing', 'string'
        attribute 'child_lock', 'string'
        attribute 'speed', 'enum'
        attribute 'humidity', 'number'
        attribute 'switch', 'string'
        attribute 'mode', 'string'
        attribute 'anion', 'string'
        attribute 'waterPump', 'string'
        attribute 'insideDrying', 'string'
        attribute 'countdown', 'number'
        attribute 'countdown_left', 'number'
        attribute 'fault', 'string'
    }

    command 'setHumidity', [[name:'humidityNeeded', type: 'ENUM', constraints: ['35', '40', '45', '50', '55', '60', '65', '70', '75', '80'], description: 'Set Humidity']]
    command 'setFanSpeed', [[name:'speedNeeded', type: 'ENUM', constraints: ['low', 'high'], description: 'Fan Speed']]
}

preferences {
    section {
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

// Called when the device is first created
void installed() {
    log.info "${device} driver installed"
}

// Component command to turn on device
void on() {
    parent?.componentOn(device)
    runInMillis(500, 'refresh')
}

// Component command to turn off device
void off() {
    parent?.componentOff(device)
    runInMillis(500, 'refresh')
}

// Component command to refresh device
void refresh() {
    parent?.componentRefresh(device)
}

// parse commands from parent
void parse(List<Map> description) {
    if (logEnable) { log.debug description }
    description.each { d ->
        if (d.descriptionText && txtEnable) { log.info "${device} ${d.descriptionText}" }
        sendEvent(d)
    }
}

// Component command to set humidity
void setHumidity(BigDecimal humidityNeeded) {
    parent?.componentSetHumiditySetpoint(device, humidityNeeded)
    runInMillis(500, 'refresh')
}

// Component command to set fan speed
void setFanSpeed(BigDecimal speedNeeded) {
    parent?.componentSetHumidifierSpeed(device, speedNeeded)
    runInMillis(500, 'refresh')
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
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}
