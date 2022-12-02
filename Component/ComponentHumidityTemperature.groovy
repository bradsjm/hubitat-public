metadata {
    definition(name: 'Generic Component Humidity/Temperature', namespace: 'component', author: 'Jonathan Bradshaw') {
        capability 'Battery'
        capability 'RelativeHumidityMeasurement'
        capability 'TemperatureMeasurement'
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

// Disable logging
void logsOff() {
    log.warn "debug logging disabled for ${device}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}

// parse commands from parent
void parse(List<Map> description) {
    if (logEnable) { log.debug description }
    description.each { d ->
        if (d.descriptionText && txtEnable) { log.info d.descriptionText }
        sendEvent(d)
    }
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
