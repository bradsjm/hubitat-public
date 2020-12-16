metadata {
    definition (name: 'Sense Energy Monitor Child Device',
                namespace: 'nrgup',
                author: 'Jonathan Bradshaw'
    ) {
        capability 'Sensor'
        capability 'EnergyMeter'

        attribute 'icon', 'text'
        attribute 'attrs', 'text'
    }
    preferences {
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
    }
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [ value: 'false', type: 'bool' ])
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    if (logEnable) { runIn(1800, 'logsOff') }
}

void parse(String description) {
    log.warn "parse(${description}) not implemented"
}

void parse(List<Map> events) {
    events.each { e ->
        if (e.descriptionText) { log.info e.descriptionText }
        if (device.currentValue(e.name) != e.value) {
            sendEvent(e)
        }
    }
}
