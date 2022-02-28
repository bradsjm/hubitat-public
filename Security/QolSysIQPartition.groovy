metadata {
    definition(name: 'QolSys IQ Partition Child', namespace: 'nrgup', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'Sensor'
        capability 'PushableButton'
        capability 'Switch'

        attribute 'isSecure', 'enum', ['true', 'false' ]
        attribute 'alarm', 'string'
        attribute 'error', 'string'
        attribute 'openZones', 'string'
        attribute 'delay', 'number'
        attribute 'bypass', 'string'

        attribute 'state', 'enum', [
            'disarmed',
            'armed away',
            'armed home',
            'exit delay',
            'entry delay',
            'alarm',
            'error'
        ]

        command 'alarm', [
            [
                name: 'alarmType',
                type: 'ENUM',
                description: 'Alarm type',
                constraints: ['POLICE', 'FIRE', 'AUXILIARY']
            ]
        ]
        command 'armAway'
        command 'armHome'
        command 'disarm'
    }
}

preferences {
    section {
        input name: 'enableArming',
              type: 'bool',
              title: 'Enable arming/disarming commands',
              required: false,
              defaultValue: false

        input name: 'enableTrigger',
              type: 'bool',
              title: 'Enable alarm trigger command',
              required: false,
              defaultValue: false

        input name: 'userCode',
              type: 'password',
              title: 'User pin for arming/disarming',
              required: false,
              defaultValue: '1234'

        input name: 'switchMapping',
              title: 'Map switch to mode',
              type: 'enum',
              defaultValue: 'none',
              options: [
                none: 'Nothing',
                home: 'Arm Home',
                away: 'Arm Away'
              ]

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

void alarm(String alarmType) {
    if (!settings.enableTrigger) {
        log.warn 'Unable to trigger alarm (driver alarming disabled)'
        return
    }

    Map command = [
        action: 'ALARM',
        alarm_type: alarmType,
        partition_id: getDataValue('partition_id') as int,
    ]

    log.info "Alarming ${command}"
    parent.sendCommand(command)
}

void armHome() {
    arm('ARM_STAY')
}

void armAway() {
    arm('ARM_AWAY')
}

void disarm() {
    arm('DISARM')
}

// Called when the device is first created
void installed() {
    log.info "${device} driver installed"
}

void initialize() {
    sendEvent(name: 'numberOfButtons', value: 6)
    state.Buttons = '1 - Disarm, 2 - Arm Away, 3 - Arm Home, 4 - Police, 5 - Fire, 6 - Aux'
}

void on() {
    switch (settings.switchMapping) {
        case 'home':
            armHome()
            break
        case 'away':
            armAway()
            break
    }
}

void off() {
    disarm()
}

// parse commands from parent
void parse(List<Map> description) {
    if (logEnable) { log.debug description }
    description.each { d ->
        if (device.currentValue(d.name) != d.value) {
            if (d.descriptionText && txtEnable) { log.info "${device} ${d.descriptionText}" }
            sendEvent(d)
        }

        if (d.name == 'state') {
            sendEvent(name: 'switch', value: d.value == 'disarmed' ? 'off' : 'on')
        }
    }
}

void push(BigDecimal buttonNumber) {
    log.info "Button ${buttonNumber} pushed"
    switch (buttonNumber) {
        case 1: disarm()
            break
        case 2: armAway()
            break
        case 3: armHome()
            break
        case 4: alarm('POLICE')
            break
        case 5: alarm('FIRE')
            break
        case 6: alarm('AUXILIARY')
    }
}

// Called when the device is removed
void uninstalled() {
    log.info "${device} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    log.info "${device} driver configuration updated"
    initialize()
    if (logEnable) {
        log.debug settings
        runIn(1800, 'logsOff')
    }
}

private void arm(String armingType) {
    if (!settings.enableArming) {
        log.warn 'Unable to arm (driver arming disabled)'
        return
    }

    Map command = [
        action: 'ARMING',
        partition_id: getDataValue('partition_id') as int,
        arming_type: armingType,
    //instant: false
    ]

    if (settings.userCode.length() >= 4) {
        command.usercode = settings.userCode
    }

    log.info "Arming ${command}"
    parent.sendCommand(command)
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}
