metadata {
    definition (name: 'Generic Component Window Shade', namespace: 'component', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'WindowShade'
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

// Component command to open device
void open() {
    parent?.componentOpen(device)
}

// Component command to close device
void close() {
    parent?.componentClose(device)
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
void setPosition(BigDecimal position) {
    parent?.componentSetPosition(device, position)
}

// Component command to start position change of device
void startPositionChange(String change) {
    parent?.componentStartPositionChange(device, change)
}

// Component command to start position change of device
void stopPositionChange() {
    parent?.componentStopPositionChange(device)
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
