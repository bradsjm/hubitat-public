metadata {
    definition(name: 'Generic Component Audio Notification', namespace: 'component', author: 'Jonathan Bradshaw') {
        capability 'AudioNotification'
        capability 'SpeechSynthesis'
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
        if (d.descriptionText && txtEnable) { log.info d.descriptionText }
        sendEvent(d)
    }
}

// Play Text
void playText(String text, BigDecimal volume = null) {
    parent?.componentPlayText(device, text, volume)
}

// Play Text and Restore
void playTextAndRestore(String text, BigDecimal volume = null) {
    parent?.componentPlayText(device, text, volume)
}

// Play Text and Resume
void playTextAndResume(String text, BigDecimal volume = null) {
    parent?.componentPlayText(device, text, volume)
}

// Play Track
void playTrack(String uri, BigDecimal volume = null) {
    parent?.componentPlayTrack(device, uri, volume)
}

// Play Track and Restore
void playTrackAndRestore(String uri, BigDecimal volume = null) {
    parent?.componentPlayTrack(device, uri, volume)
}

// Play Track and Resume
void playTrackAndResume(String uri, BigDecimal volume = null) {
    parent?.componentPlayTrack(device, uri, volume)
}

// Speak Text
void speak(String text, BigDecimal volume = null, String voice = null) {
    parent?.componentPlayText(device, text, volume)
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
