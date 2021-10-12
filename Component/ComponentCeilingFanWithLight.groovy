metadata {
    definition (name: 'Generic Component Ceiling Fan w/Light', namespace: 'component', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'FanControl'
        capability 'Light'
        capability 'Switch'
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

// parse commands from parent
void parse(List<Map> description) {
    if (logEnable) { log.debug description }
    description.each { d ->
        if (d.descriptionText && txtEnable) { log.info "${device} ${d.descriptionText}" }
        sendEvent(d)
    }
}

// Component command to turn on device
void on() {
    parent?.componentOn(device)
}

// Component command to turn off device
void off() {
    parent?.componentOff(device)
}

// Component command to refresh device
void refresh() {
    parent?.componentRefresh(device)
}

// Component command to set fan speed
void setSpeed(String speed) {
    parent?.componentSetSpeed(device, speed)
}

void cycleSpeed() {
    parent?.componentCycleSpeed(device)
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
