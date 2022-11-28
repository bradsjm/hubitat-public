metadata {
    definition(name: 'Generic Component Garage Door Contact/Switch', namespace: 'component', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'GarageDoorControl'
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
    if (device.currentValue('door') != 'open') {
        parent?.componentOpen(device)
    }
}

// Component command to close device
void close() {
    if (device.currentValue('door') != 'closed') {
        parent?.componentClose(device)
    }
}

// parse commands from parent
void parse(List<Map> description) {
    if (logEnable) { log.debug description }
    description.each { d ->
        if (d.descriptionText && txtEnable) { log.info "${device} ${d.descriptionText}" }
        switch (d.name) {
            case 'contact':
                sendEvent([ name: 'door', value: d.value ])
                break
            default:
                sendEvent(d)
                break
        }
    }
}

// Component command to refresh device
void refresh() {
    parent?.componentRefresh(device)
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
