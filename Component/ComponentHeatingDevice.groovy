metadata {
    definition (name: 'Generic Component Heating Device', namespace: 'component', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Switch'
        capability 'TemperatureMeasurement'
        capability 'ThermostatHeatingSetpoint'
        capability 'ThermostatMode'
        capability 'Refresh'
    }
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
}

// Component command to turn off device
void off() {
    parent?.componentOff(device)
}

// parse commands from parent
void parse(List<Map> description) {
    if (logEnable) { log.debug description }
    description.each { d ->
        if (d.descriptionText && txtEnable) { log.info "${device} ${d.descriptionText}" }
        sendEvent(d)
    }
}

// Component command to set position of device
void setHeatingSetpoint(BigDecimal temperature) {
    parent?.componentSetHeatingSetpoint(device, temperature)
}

// Set the thermostat mode, maps everything to on and off
void setThermostatMode(String thermostatMode) {
    switch (thermostatMode) {
        case 'heat':
        case 'emergency heat':
        case 'cool':
            break
        case 'auto':
            on()
            break
        case 'off':
            off()
            break
    }
}

void auto() {
    on()
}

void cool() {
// do nothing
}

void emergencyHeat() {
    on()
}

void heat() {
    on()
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
