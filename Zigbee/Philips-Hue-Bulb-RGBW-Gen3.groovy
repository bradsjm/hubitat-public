/**
 *  MIT License
 *  Copyright 2023 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the 'Software'), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 *
 *  Thanks to the Zigbee2Mqtt and Dresden-elektronik teams for
 *  their existing work in decoding the Hue protocol.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: 'Philips Hue RGBW Bulb (Gen3+)',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/Zigbee/Philips-Hue-RGBW-Bulb.groovy',
            namespace: 'philips-hue', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Bulb'
        capability 'Change Level'
        capability 'Color Control'
        capability 'Color Temperature'
        capability 'Color Mode'
        capability 'Configuration'
        capability 'Flash'
        capability 'Light'
        capability 'Level Preset'
        capability 'Light Effects'
        capability 'Refresh'
        capability 'Switch Level'
        capability 'Switch'

        attribute 'effectName', 'string'

        command 'identify', [ [ name: 'Effect type*', type: 'ENUM', description: 'Effect Type', constraints: IdentifyEffectNames.values()*.toLowerCase() ] ]
        command 'stepLevelChange', [
            [ name: 'Direction*', type: 'ENUM', description: 'Direction for step change request', constraints: [ 'up', 'down' ] ],
            [ name: 'Step Size*', type: 'NUMBER', description: 'Level change step size' ],
            [ name: 'Duration', type: 'NUMBER', description: 'Transition duration in seconds' ]
        ]
        command 'toggle'

        // Philips Hue White and Color Ambiance
        fingerprint model: 'LCA001', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCA002', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCA003', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCA004', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCA005', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCA006', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCA007', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCA008', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCA009', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCE001', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCE002', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCG001', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
        fingerprint model: 'LCG002', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
    }

    preferences {
        input name: 'transitionTime', type: 'enum', title: 'Level transition duration', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true
        input name: 'colorTransitionTime', type: 'enum', title: 'Color transition duration', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true
        input name: 'levelChangeRate', type: 'enum', title: 'Level change (up/down) rate', options: LevelRateOpts.options, defaultValue: LevelRateOpts.defaultValue, required: true
        input name: 'flashEffect', type: 'enum', title: 'Flash effect', options: IdentifyEffectNames.values(), defaultValue: 'Blink', required: true
        input name: 'powerRestore', type: 'enum', title: 'Power restore state', options: PowerRestoreOpts.options, defaultValue: PowerRestoreOpts.defaultValue

        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

@Field static final String Version = '0.2'

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'
    state.clear()
    state.reportingEnabled = false

    log.info 'configure: setting light effects'
    sendEvent(name: 'lightEffects', value: JsonOutput.toJson(HueEffectNames.values()))

    // Power Restore Behavior
    if (settings.powerRestore != null) {
        log.info "configure: setting power restore state to 0x${intToHexStr(settings.powerRestore as Integer)}"
        cmds += zigbee.writeAttribute(zigbee.ON_OFF_CLUSTER, 0x4003, DataType.ENUM8, settings.powerRestore as Integer, [:], DELAY_MS)
    }

    // Attempt to enable cluster reporting, if it fails we fall back to polling after commands
    log.info 'configure: attempting to enable state reporting'
    cmds += zigbee.configureReporting(HUE_PRIVATE_CLUSTER, HUE_PRIVATE_STATE_ID, DataType.STRING_OCTET, 1, 300, null, [:], DELAY_MS)

    // Private cluster only reports XY colors so enable Hue and Saturation reporting
    cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x00, DataType.UINT8, 1, 3600, 1, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x01, DataType.UINT8, 1, 3600, 1, [:], DELAY_MS)

    // Disable unused cluster reporting (switch and level are reported via private cluster)
    cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x07, DataType.UINT16, 0, 0xFFFF, 1, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x08, DataType.ENUM8, 0, 0xFFFF, null, [:], DELAY_MS)

    if (settings.logEnable) { log.debug "zigbee configure cmds: ${cmds}" }
    return cmds + refresh()
}

List<String> flash(Object rate = null) {
    if (settings.txtEnable) { log.info "flash (${rate})" }
    return identify(settings.flashEffect)
}

List<String> identify(String name) {
    Integer effect = IdentifyEffectNames.find { k, v -> v.equalsIgnoreCase(name) }?.key
    if (effect == null) { return [] }
    if (settings.txtEnable) { log.info "identify (${name})" }
    return zigbee.command(zigbee.IDENTIFY_CLUSTER, TRIGGER_EFFECT_CMD_ID, [:], 0, "${intToHexStr(effect)} 00")
}

void installed() {
    log.info 'installed'
    // populate some default values for attributes
    sendEvent(name: 'colorMode', value: 'CT')
    sendEvent(name: 'colorTemperature', value: 2700)
    sendEvent(name: 'effectName', value: 'none')
    sendEvent(name: 'hue', value: 0, unit: '%')
    sendEvent(name: 'level', value: 0, unit: '%')
    sendEvent(name: 'saturation', value: 0)
    sendEvent(name: 'switch', value: 'off')
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

List<String> off() {
    if (settings.txtEnable) { log.info 'turn off' }
    sendSwitchEvent(false) // assume success
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x40, [:], 0, '00 00') +
        ifPolling { zigbee.onOffRefresh(0) }
}

List<String> on() {
    if (settings.txtEnable) { log.info 'turn on' }
    sendSwitchEvent(true) // assume success
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x01, [:], 0) +
        ifPolling { zigbee.onOffRefresh(0) }
}

List<String> presetLevel(Object value) {
    if (settings.txtEnable) { log.info "presetLevel (${value})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer rate = isOn ? settings.transitionTime as Integer : 0
    return setLevelPrivate(value, rate, 0, true)
}

List<String> refresh() {
    log.info 'refresh'
    List<String> cmds = []

    // Get Firmware Version
    cmds += zigbee.readAttribute(zigbee.BASIC_CLUSTER, FIRMWARE_VERSION_ID, [:], DELAY_MS)

    // Get Power Restore state
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, 0x4003, [:], DELAY_MS)

    // Get Minimum/Maximum Color Temperature
    state.ct = state.ct ?: [ high: 6536, low: 2000 ] // default values
    cmds += zigbee.readAttribute(zigbee.COLOR_CONTROL_CLUSTER, [0x400B, 0x400C], [:], DELAY_MS)

    // Refresh other attributes
    //cmds += zigbee.onOffRefresh(DELAY_MS)
    //cmds += zigbee.levelRefresh(DELAY_MS)
    cmds += hueStateRefresh(DELAY_MS)
    cmds += colorRefresh(DELAY_MS)

    // Get groups
    cmds += zigbee.command(zigbee.GROUPS_CLUSTER, 0x02, [:], DELAY_MS, '00')

    return cmds
}

List<String> setColor(Map value) {
    List<String> cmds = []
    if (settings.txtEnable) { log.info "setColor (${value})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer hue = constrain(value.hue)
    Integer saturation = constrain(value.saturation)
    Integer rate = settings.colorTransitionTime.toInteger()
    String rateHex = intToSwapHexStr(rate)
    String scaledHueValue = intToHexStr(Math.round(hue * 0xfe / 100.0))
    String scaledSatValue = intToHexStr(Math.round(saturation * 0xfe / 100.0))
    cmds += zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, COLOR_CMD_ID, [:], DELAY_MS, "${scaledHueValue} ${scaledSatValue} ${isOn ? rateHex : '0000'} ${PRESTAGING_OPTION}")
    if (value.level != null) {
        // This will turn on the device if it is off and set level
        cmds += setLevelPrivate(value.level, rate)
    }
    return cmds + ifPolling(400 + (rate * 100)) { colorRefresh(0) }
}

List<String> setColorTemperature(Object colorTemperature, Object level = null, Object transitionTime = null) {
    List<String> cmds = []
    if (settings.txtEnable) { log.info "setColorTemperature (${colorTemperature}, ${level}, ${rate})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer rate = transitionTime != null ? (transitionTime.toBigDecimal() * 10).toInteger() : settings.colorTransitionTime.toInteger()
    String rateHex = isOn ? intToSwapHexStr(rate) : '0000'
    Integer ct = constrain(colorTemperature, state.ct.low, state.ct.high)
    String miredHex = ctToMiredHex(ct)
    cmds += zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, COLOR_CMD_CT_ID, [:], DELAY_MS, "${miredHex} ${rateHex} ${PRESTAGING_OPTION}")
    if (level != null) {
        // This will turn on the device if it is off and set level
        cmds += setLevelPrivate(level, rate)
    }
    return cmds + ifPolling(400 + (rate * 100)) { colorRefresh(0) }
}

List<String> setEffect(Object number) {
    Integer effectNumber = constrain(number, 0, HueEffectNames.size())
    if (settings.txtEnable) { log.info "setEffect (${number})" }
    if (effectNumber == 0) {
        state.remove('effect')
        return zigbee.command(HUE_PRIVATE_CLUSTER, 0x00, [ mfgCode: VENDOR_PHILIPS_ID ], 0, '2000 00')
    }
    state.effect = number
    int effect = HueEffectNames.keySet()[effectNumber - 1]
    return zigbee.command(HUE_PRIVATE_CLUSTER, 0x00, [ mfgCode: VENDOR_PHILIPS_ID ], 0, "2100 01 ${intToHexStr(effect)}") +
        ifPolling { hueStateRefresh(0) }
}

List<String> setHue(Object value) {
    if (settings.txtEnable) { log.info "setHue (${value})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer hue = constrain(value)
    Integer rate = isOn ? settings.colorTransitionTime.toInteger() : 0
    String rateHex = intToSwapHexStr(rate)
    String scaledHueValue = intToHexStr(Math.round(hue * 0xfe / 100.0))
    return zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, COLOR_CMD_HUE_ID, [:], 0, "${scaledHueValue} 00 ${rateHex} ${PRESTAGING_OPTION}") +
        ifPolling(400 + (rate * 100)) { colorRefresh(0) }
}

List<String> setLevel(Object value, Object transitionTime = null) {
    if (settings.txtEnable) { log.info "setLevel (${value}, ${rate})" }
    Integer rate = transitionTime != null ? (transitionTime.toBigDecimal() * 10).toInteger() : settings.transitionTime.toInteger()
    return setLevelPrivate(value, rate)
}

List<String> setNextEffect() {
    if (settings.txtEnable) { log.info 'setNextEffect' }
    Integer number = state.effect ? state.effect + 1 : 1
    if (number > HueEffectNames.size()) { number = 1 }
    return setEffect(number)
}

List<String> setPreviousEffect() {
    if (settings.txtEnable) { log.info 'setPreviousEffect' }
    Integer number = state.effect ? state.effect - 1 : HueEffectNames.size()
    if (number < 1) { number = 1 }
    return setEffect(number)
}

List<String> setSaturation(Object value) {
    if (settings.txtEnable) { log.info "setSaturation (${value})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer saturation = constrain(value)
    Integer rate = isOn ? settings.colorTransitionTime.toInteger() : 0
    String rateHex = intToSwapHexStr(rate)
    String scaledSatValue = intToHexStr(Math.round(saturation * 0xfe / 100.0))
    return zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, COLOR_CMD_SAT_ID, [:], 0, "${scaledSatValue} 00 ${rateHex} ${PRESTAGING_OPTION}") +
        ifPolling(400 + (rate * 100)) { colorRefresh(0) }
}

List<String> startLevelChange(String direction) {
    if (settings.txtEnable) { log.info "startLevelChange (${direction})" }
    String rate = intToHexStr(settings.levelChangeRate.toInteger())
    String upDown = direction == 'down' ? '01' : '00'
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, MOVE_CMD_ON_OFF_ID, [:], 0, "${upDown} ${rate}")
}

List<String> stepLevelChange(String direction, Object stepSize, Object transitionTime = null) {
    if (settings.txtEnable) { log.info "stepLevelChange (${direction}, ${stepSize}, ${transitionTime})" }
    Integer rate = transitionTime != null ? (transitionTime.toBigDecimal() * 10).toInteger() : settings.colorTransitionTime.toInteger()
    String rateHex = isOn ? intToSwapHexStr(rate) : '0000'
    Integer level = constrain(stepSize, 1, 99)
    String stepHex = intToHexStr((level * 2.55).toInteger())
    String upDown = direction == 'down' ? '01' : '00'
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, MOVE_CMD_STEP_ID, [:], 0, "${upDown} ${stepHex} ${rateHex}") +
        ifPolling { zigbee.levelRefresh(0) + zigbee.onOffRefresh(0) }
}

List<String> stopLevelChange() {
    if (settings.txtEnable) { log.info 'stopLevelChange' }
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, MOVE_CMD_STOP_ID, [:], 0) +
        ifPolling { zigbee.levelRefresh(0) + zigbee.onOffRefresh(0) }
}

List<String> toggle() {
    if (settings.txtEnable) { log.info 'toggle' }
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x02, [:], 0) +
        ifPolling { zigbee.onOffRefresh(0) }
}

void updated() {
    log.info 'updated...'
    configure()
    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }
}

/*
 * ------------------------ ZIGBEE MESSAGE PARSING --------------------
 */
void parse(String description) {
    Map descMap = zigbee.parseDescriptionAsMap(description)

    if (descMap.isClusterSpecific == false) {
        parseGlobalCommands(descMap)
        return
    }

    if (settings.logEnable) {
        String clusterName = clusterLookup(descMap.clusterInt) ?: "private cluster 0x${descMap.cluster}"
        log.trace "zigbee received ${clusterName} message"
    }
    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseBasicCluster(m) }
            break
        case zigbee.COLOR_CONTROL_CLUSTER:
            parseColorCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseColorCluster(m) }
            break
        case zigbee.GROUPS_CLUSTER:
            parseGroupsCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseGroupsCluster(m) }
            break
        case HUE_PRIVATE_CLUSTER:
            parsePhilipsHueCluster(descMap)
            descMap.additionalAttrs?.each { m -> parsePhilipsHueCluster(m) }
            break
        case zigbee.LEVEL_CONTROL_CLUSTER:
            parseLevelCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseLevelCluster(m) }
            break
        case zigbee.ON_OFF_CLUSTER:
            parseOnOffCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseOnOffCluster(m) }
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown message cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Basic Cluster Parsing
 */
void parseBasicCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case FIRMWARE_VERSION_ID:
            String version = descMap.value ?: 'unknown'
            log.info "device firmware version is ${version}"
            updateDataValue('softwareBuild', version)
            break
        case PRODUCT_ID:
            String name = descMap.value ?: 'unknown'
            log.info "device product identifier is ${name}"
            updateDataValue('productIdentifier', name)
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown basic cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Color Cluster Parsing
 */
void parseColorCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case 0x00: // hue
            sendHueEvent(descMap.value)
            break
        case 0x01: // saturation
            sendSaturationEvent(descMap.value)
            break
        case 0x03: // current X
            if (settings.logEnable) { log.debug 'ignoring X color attribute' }
            break
        case 0x04: // current Y
            if (settings.logEnable) { log.debug 'ignoring Y color attribute' }
            break
        case 0x07: // ct
            sendColorTempEvent(descMap.value)
            break
        case 0x08: // color mode
            String mode = descMap.value == '02' ? 'CT' : 'RGB'
            sendColorModeEvent(mode)
            break
        case 0x400B:
            state.ct = state.ct ?: [:]
            state.ct.high = Math.round(1000000 / hexStrToUnsignedInt(descMap.value))
            log.info "color temperature high set to ${state.ct.high}K"
            break
        case 0x400C:
            state.ct = state.ct ?: [:]
            state.ct.low = Math.round(1000000 / hexStrToUnsignedInt(descMap.value))
            log.info "color temperature low set to ${state.ct.low}K"
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received color cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Global Command Parsing
 */
void parseGlobalCommands(Map descMap) {
    switch (hexStrToUnsignedInt(descMap.command)) {
        case 0x00: // read attribute
            if (settings.logEnable) { log.trace "zigbee read attribute request ${clusterLookup(descMap.clusterInt)}: ${descMap}" }
            break
        case 0x01: // read attribute response
            if (settings.logEnable) { log.trace "zigbee read attribute response ${clusterLookup(descMap.clusterInt)}: ${descMap}" }
            break
        case 0x02: // write attribute (with response)
            if (settings.logEnable) { log.trace "zigbee write attribute request ${clusterLookup(descMap.clusterInt)}: ${descMap}" }
            break
        case 0x04: // write attribute response
            int statusCode = hexStrToUnsignedInt(descMap.data)
            String status = ZigbeeStatusEnum[statusCode] ?: "0x${descMap.data}"
            if (settings.logEnable) {
                log.trace "zigbee write ${clusterLookup(descMap.clusterInt)} attribute response: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee write ${clusterLookup(descMap.clusterInt)} attribute error: ${status}"
            }
            break
        case 0x07: // configure reporting response
            state.reportingEnabled = true
            if (settings.logEnable) {
                log.trace "zigbee attribute reporting enabled for ${clusterLookup(descMap.clusterInt)}"
            }
            break
        case 0x0B: // default command response
            String commandId = descMap.data[0]
            int statusCode = hexStrToUnsignedInt(descMap.data[1])
            String status = ZigbeeStatusEnum[statusCode] ?: "0x${descMap.data[1]}"
            if (settings.logEnable) {
                log.trace "zigbee command status ${clusterLookup(descMap.clusterInt)} command 0x${commandId}: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee command error (${clusterLookup(descMap.clusterInt)}, command: 0x${commandId}) ${status}"
            }
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown global command: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Groups Cluster Parsing
 */
void parseGroupsCluster(Map descMap) {
    switch (descMap.command as Integer) {
        case 0x02: // Group membership response
            int capacity = hexStrToUnsignedInt(descMap.data[0])
            int groupCount = hexStrToUnsignedInt(descMap.data[1])
            Set<String> groups = []
            for (int i = 0; i < groupCount; i++) {
                int pos = (i * 2) + 2
                String group = descMap.data[pos + 1] + descMap.data[pos]
                groups.add(group)
            }
            state.groups = groups
            log.info "zigbee group memberships: ${groups} (capacity available: ${capacity})"
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown groups cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Hue Specific Cluster Parsing
 */
void parsePhilipsHueCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case HUE_PRIVATE_STATE_ID:
            parsePhilipsHueClusterState(descMap)
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown philips hue cluster: ${descMap}"
            }
            break
    }
}

/*
 * Attribute 0x0002 seems to encode the light state, where the first
 * two bytes indicate the mode, the next byte OnOff, the next byte
 * Current Level, and the following bytes the mode-specific state.
 * from https://github.com/dresden-elektronik/deconz-rest-plugin/issues/5891
 */
void parsePhilipsHueClusterState(Map descMap) {
    int mode = hexStrToUnsignedInt(zigbee.swapOctets(descMap.value[0..3]))
    boolean isOn = hexStrToUnsignedInt(descMap.value[4..5]) == 1
    int level = hexStrToUnsignedInt(descMap.value[6..7])
    if (settings.logEnable) {
        log.debug "zigbee received hue cluster report [power: ${isOn}, level: ${level}, mode: 0x${intToHexStr(mode, 2)}]"
    }

    sendSwitchEvent(isOn)
    switch (mode) {
        case 0x000F: // CT mode
            sendColorModeEvent('CT')
            sendColorTempEvent(descMap.value[8..11])
            sendLevelEvent(level)
            sendEffectNameEvent()
            break
        case 0x000B: // XY mode
            sendColorModeEvent('RGB')
            sendLevelEvent(level)
            sendEffectNameEvent()
            break
        case 0x00AB: // XY mode with effect
            sendColorModeEvent('RGB')
            sendLevelEvent(level)
            sendEffectNameEvent(descMap.value[16..17])
            break
        default:
            if (settings.logEnable) {
                log.debug "Unknown mode received: ${intToHexStr(mode)}"
            }
            break
    }
}

/*
 * Zigbee Level Cluster Parsing
 */
void parseLevelCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case 0x00:
            sendLevelEvent(hexStrToUnsignedInt(descMap.value))
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received level cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee On Off Cluster Parsing
 */
void parseOnOffCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case 0x00:
            sendSwitchEvent(descMap.value == '01')
            break
        case 0x4003:
            Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "power restore mode is '${PowerRestoreOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('powerRestore', [value: value, type: 'number' ])
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown on/off cluster: ${descMap}"
            }
            break
    }
}
/*-------------------------- END OF ZIGBEE PARSING --------------------*/

/*
 * Utility Methods
 */
private List<String> colorRefresh(int delayMs = 2000) {
    return zigbee.readAttribute(zigbee.COLOR_CONTROL_CLUSTER, [0x00, 0x01, 0x07, 0x08], [:], delayMs)
}

private String clusterLookup(Object cluster) {
    return zigbee.clusterLookup(cluster.toInteger()) ?: "private cluster 0x${intToHexStr(cluster.toInteger())}"
}

private Integer constrain(Object value, Integer min = 0, Integer max = 100, Integer nullValue = 0) {
    if (min == null || max == null) { return value }
    return value != null ? Math.min(Math.max(value.toInteger(), min), max) : nullValue
}

private String ctToMiredHex(int ct) {
    return zigbee.swapOctets(intToHexStr((1000000 / ct).toInteger(), 2))
}

private List<String> hueStateRefresh(int delayMs = 2000) {
    return zigbee.readAttribute(HUE_PRIVATE_CLUSTER, HUE_PRIVATE_STATE_ID, [:], delayMs)
}

private List<String> ifPolling(int delayMs = POLL_DELAY_MS, Closure commands) {
    if (state.reportingEnabled == false) {
        return [ "delay ${delayMs}" ] + (commands() as List<String>)
    }
    return []
}

private String intToSwapHexStr(Integer i, String nullValue = '0000') {
    return i != null ? zigbee.swapOctets(intToHexStr(i, 2)) : nullValue
}

private int miredHexToCt(String mired) {
    return (1000000 / hexStrToUnsignedInt(zigbee.swapOctets(mired))) as int
}

private void sendHueEvent(String rawValue) {
    Integer hue = Math.round((hexStrToUnsignedInt(rawValue) / 255.0) * 100)
    String descriptionText = "hue was set to ${hue}"
    sendEvent(name: 'hue', value: hue, descriptionText: descriptionText)
    if (device.currentValue('hue') as Integer != hue && settings.txtEnable) {
        log.info descriptionText
    }
    sendColorNameEvent(hue)
}

private void sendSaturationEvent(String rawValue) {
    Integer saturation = Math.round((hexStrToUnsignedInt(rawValue) / 255.0) * 100)
    String descriptionText = "saturation was set to ${saturation}"
    sendEvent(name: 'saturation', value: saturation, descriptionText: descriptionText)
    if (device.currentValue('saturation') as Integer != saturation && settings.txtEnable) {
        log.info descriptionText
    }
}

private void sendColorModeEvent(String mode) {
    String descriptionText = "color mode was set to ${mode}"
    sendEvent(name: 'colorMode', value: mode, descriptionText: descriptionText)
    if (device.currentValue('colorMode') != mode && settings.txtEnable) {
        log.info descriptionText
    }
}

private void sendColorNameEvent(Integer hue) {
    String colorName = ColorNameMap.find { k, v -> hue * 3.6 <= k }?.value
    if (!colorName) { return }
    descriptionText = "color name was set to ${colorName}"
    sendEvent name: 'colorName', value: colorName, descriptionText: descriptionText
    if (device.currentValue('colorName') != colorName && settings.txtEnable) {
        log.info descriptionText
    }
}

private void sendColorTempEvent(String rawValue) {
    Integer value = miredHexToCt(rawValue)
    if (state.ct?.high && value > state.ct.high) { return }
    if (state.ct?.low && value < state.ct.low) { return }
    String descriptionText = "color temperature was set to ${value}°K"
    sendEvent(name: 'colorTemperature', value: value, descriptionText: descriptionText, unit: '°K')
    if (device.currentValue('colorTemperature') as Integer != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendColorTempNameEvent(value)
}

private void sendColorTempNameEvent(Integer ct) {
    String genericName = ColorTempName.find { k , v -> ct < k }?.value
    if (!genericName) { return }
    String descriptionText = "color is ${genericName}"
    sendEvent(name: 'colorName', value: genericName, descriptionText: descriptionText)
    if (device.currentValue('colorName') != genericName && settings.txtEnable) {
        log.info descriptionText
    }
}

private void sendEffectNameEvent(String rawValue = null) {
    String effectName = 'none'
    if (rawValue != null) {
        int effect = hexStrToUnsignedInt(rawValue)
        effectName = HueEffectNames[effect] ?: 'unknown'
    }
    String descriptionText = "effect was set to ${effectName}"
    sendEvent(name: 'effectName', value: effectName, descriptionText: descriptionText)
    if (device.currentValue('effectName') != effectName && settings.txtEnable) {
        log.info descriptionText
    }
}

private void sendLevelEvent(Object rawValue) {
    Integer value = Math.round(rawValue.toInteger() / 2.55)
    String descriptionText = "level was set to ${value}%"
    sendEvent(name: 'level', value: value, descriptionText: descriptionText, unit: '%')
    if (device.currentValue('level') as Integer != value && settings.txtEnable) {
        log.info descriptionText
    }
}

private void sendSwitchEvent(Boolean isOn) {
    String value = isOn ? 'on' : 'off'
    String descriptionText = "light was turned ${value}"
    sendEvent(name: 'switch', value: value, descriptionText: descriptionText)
    if (device.currentValue('switch') != value && settings.txtEnable) {
        log.info descriptionText
    }
}

private List<String> setLevelPrivate(Object value, Integer rate = 0, Integer delay = 0, Boolean levelPreset = false) {
    Integer level = constrain(value)
    if (level > 0 && levelPreset == false) { sendSwitchEvent(true) } // assume success
    String hexLevel = intToHexStr(Math.round(level * 0xfe / 100.0).toInteger())
    String hexRate = intToSwapHexStr(rate)
    int levelCommand = levelPreset ? LEVEL_CMD_ID : LEVEL_CMD_ON_OFF_ID
    // Payload: Level | Transition Time | Options Mask | Options Override
    // Options: Bit 0x01 enables prestaging level
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, levelCommand, [:], delay, "${hexLevel} ${hexRate} ${PRESTAGING_OPTION}") +
        ifPolling(400 + (rate * 100)) { zigbee.levelRefresh(0) }
}

// Zigbee Cluster IDs
@Field static final int HUE_PRIVATE_CLUSTER = 0xFC03

// Zigbee Command IDs
@Field static final int COLOR_CMD_CT_ID = 0x0A
@Field static final int COLOR_CMD_HUE_ID = 0x00
@Field static final int COLOR_CMD_ID = 0x06
@Field static final int COLOR_CMD_SAT_ID = 0x03
@Field static final int LEVEL_CMD_ID = 0x00
@Field static final int LEVEL_CMD_ON_OFF_ID = 0x04
@Field static final int MOVE_CMD_ON_OFF_ID = 0x05
@Field static final int MOVE_CMD_STEP_ID = 0x06
@Field static final int MOVE_CMD_STOP_ID = 0x03
@Field static final int TRIGGER_EFFECT_CMD_ID = 0x40

// Zigbee Attribute IDs
@Field static final int DELAY_MS = 200 // Delay between zigbee commands
@Field static final int FIRMWARE_VERSION_ID = 0x4000
@Field static final int HUE_PRIVATE_STATE_ID = 0x02
@Field static final int POLL_DELAY_MS = 1000 // Delay when polling results
@Field static final String PRESTAGING_OPTION = '01 01' // Enable change when off
@Field static final int VENDOR_PHILIPS_ID = 0x100B // Philips vendor code

@Field static final Map<Integer, String> ColorNameMap = [
    15: 'Red',
    45: 'Orange',
    75: 'Yellow',
    105: 'Chartreuse',
    135: 'Green',
    165: 'Spring',
    195: 'Cyan',
    225: 'Azure',
    255: 'Blue',
    285: 'Violet',
    315: 'Magenta',
    345: 'Rose',
    360: 'Red'
]

@Field static final Map<Integer, String> ColorTempName = [
    2001: 'Sodium',
    2101: 'Starlight',
    2400: 'Sunrise',
    2800: 'Incandescent',
    3300: 'Soft White',
    3500: 'Warm White',
    4150: 'Moonlight',
    5001: 'Horizon',
    5500: 'Daylight',
    6000: 'Electronic',
    6501: 'Skylight',
    20000: 'Polar'
]

@Field static final Map<Integer, String> HueEffectNames = [
    0x01: 'candle',
    0x02: 'fireplace',
    0x03: 'color loop'
]

@Field static final Map<Integer, String> IdentifyEffectNames = [
    0x00: 'Blink',
    0x02: 'Green (1s)',
    0x0b: 'Orange (8s)',
    0x01: 'Pulse (15s)'
]

@Field static Map PowerRestoreOpts = [
    defaultValue: 0xFF,
    options: [ 0x00: 'Off', 0x01: 'On', 0xFF: 'Last State' ]
]

@Field static final Map TransitionOpts = [
    defaultValue: 0x0004,
    options: [
        0x0000: 'No Delay',
        0x0002: '200ms',
        0x0004: '400ms',
        0x000A: '1s',
        0x000F: '1.5s',
        0x0014: '2s',
        0x0032: '5s',
        0x0064: '10s',
        0xFFFF: 'Device Default'
    ]
]

@Field static final Map LevelRateOpts = [
    defaultValue: 0x64,
    options: [ 0xFF: 'Device Default', 0x16: 'Very Slow', 0x32: 'Slow', 0x64: 'Medium', 0x96: 'Medium Fast', 0xC8: 'Fast' ]
]

@Field static final Map<Integer, String> ZigbeeStatusEnum = [
    0x00: 'SUCCESS',
    0x01: 'FAILURE',
    0x02: 'NOT AUTHORIZED',
    0x80: 'MALFORMED COMMAND',
    0x81: 'UNSUPPORTED COMMAND',
    0x85: 'INVALID FIELD',
    0x86: 'UNSUPPORTED ATTRIBUTE',
    0x87: 'INVALID VALUE',
    0x88: 'READ ONLY',
    0x89: 'INSUFFICIENT SPACE',
    0x8B: 'NOT FOUND',
    0x8C: 'UNREPORTABLE ATTRIBUTE',
    0x8D: 'INVALID DATA TYPE',
    0x8E: 'INVALID SELECTOR',
    0x94: 'TIMEOUT',
    0x9A: 'NOTIFICATION PENDING',
    0xC3: 'UNSUPPORTED CLUSTER'
]
