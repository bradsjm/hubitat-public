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

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: 'Philips Hue RGBW Bulb (Gen4)', importUrl: '',
        namespace: 'philips-hue', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
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

        command 'bind', ['string']
        command 'identify', [ [ name: 'Effect type*', type: 'ENUM', description: 'Effect Type', constraints: IdentifyEffectNames.values()*.toLowerCase() ] ]
        command 'stepLevelChange', [
            [ name: 'Direction*', type: 'ENUM', description: 'Direction for step change request', constraints: [ 'up', 'down' ] ],
            [ name: 'Step Size*', type: 'NUMBER', description: 'Level change step size' ],
            [ name: 'Duration', type: 'NUMBER', description: 'Transition duration in seconds' ]
        ]
        command 'toggle'

        fingerprint profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019', manufacturer: 'Signify Netherlands B.V.', model: 'LCA005', deviceJoinName: 'Philips Hue White and Color 60W'
        fingerprint profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019', manufacturer: 'Signify Netherlands B.V.', model: 'LCA007', deviceJoinName: 'Philips Hue White and Color 75W'
        fingerprint profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019', manufacturer: 'Signify Netherlands B.V.', model: 'LCA009', deviceJoinName: 'Philips Hue White and Color 100W'
    }

    preferences {
        input name: 'transitionTime', type: 'enum', title: 'Level transition duration', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true
        input name: 'levelChangeRate', type: 'enum', title: 'Level change (up/down) rate', options: LevelRateOpts.options, defaultValue: LevelRateOpts.defaultValue, required: true
        input name: 'flashEffect', type: 'enum', title: 'Flash effect', options: IdentifyEffectNames.values(), defaultValue: 'Blink', required: true
        input name: 'powerRestore', type: 'enum', title: 'Power restore state', options: PowerRestoreOpts.options, defaultValue: PowerRestoreOpts.defaultValue

        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

// Zigbee Cluster IDs
@Field static final int BASIC_CLUSTER_ID = 0x0000
@Field static final int COLOR_CLUSTER_ID = 0x0300
@Field static final int GROUPS_CLUSTER_ID = 0x0004
@Field static final int PHILIPS_HUE_CLUSTER_ID = 0xFC03
@Field static final int IDENTIFY_CLUSTER_ID = 0x0003
@Field static final int LEVEL_CLUSTER_ID = 0x0008
@Field static final int ON_OFF_CLUSTER_ID = 0x0006

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
@Field static final int HUE_STATE_ID = 0x02
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
    defaultText: 'On',
    defaultValue: '01',
    options: [ 0x00: 'Off', 0x01: 'On', 0xFF: 'Last State' ]
]

@Field static final Map TransitionOpts = [
    defaultText: 'Device Default',
    defaultValue: 'FFFF',
    options: [ 'FFFF': 'Device Default', '0000': 'No Delay', '0400': '400ms', '0A00': '1s', '0F00': '1.5s', '1400': '2s', '3200': '5s' ]
]

@Field static final Map LevelRateOpts = [
    defaultText: 'Device Default',
    defaultValue: 'FF',
    options: [ 'FF': 'Device Default', '16': 'Very Slow', '32': 'Slow', '64': 'Medium', '96': 'Medium Fast', 'C8': 'Fast' ]
]

@Field static final Map<Integer, String> ZigbeeStatusEnum = [
    0x00: 'SUCCESS',
    0x01: 'FAILURE',
    0x02: 'NOT_AUTHORIZED',
    0x80: 'MALFORMED_COMMAND',
    0x81: 'UNSUP_COMMAND',
    0x85: 'INVALID_FIELD',
    0x86: 'UNSUPPORTED_ATTRIBUTE',
    0x87: 'INVALID_VALUE',
    0x88: 'READ_ONLY',
    0x89: 'INSUFFICIENT_SPACE',
    0x8B: 'NOT_FOUND',
    0x8C: 'UNREPORTABLE_ATTRIBUTE',
    0x8D: 'INVALID_DATA_TYPE',
    0x8E: 'INVALID_SELECTOR',
    0x94: 'TIMEOUT',
    0x9A: 'NOTIFICATION_PENDING',
    0xC3: 'UNSUPPORTED_CLUSTER'
]

List<String> bind(List<String> cmds=[]) {
    if (settings.txtEnable) { log.info "${device.label}: bind(${cmds})" }
    return cmds
}

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'
    state.clear()
    sendEvent(name: 'lightEffects', value: HueEffectNames.values())

    // Power Restore Behavior
    if (settings.powerRestore != null) {
        log.info "setting power restore state to ${settings.powerRestore}"
        cmds += zigbee.writeAttribute(ON_OFF_CLUSTER_ID, 0x4003, DataType.ENUM8, settings.powerRestore as Integer)
        //cmds += zigbee.writeAttribute(COLOR_CLUSTER_ID, 0x4010, DataType.UINT16, 0xFFFF)
    }

    // Unbind clusters
    log.info 'unbinding clusters'
    cmds += [
        "zdo unbind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0006 {${device.zigbeeId}} {}", 'delay 2000',
        "zdo unbind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0008 {${device.zigbeeId}} {}", 'delay 2000',
        "zdo unbind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0300 {${device.zigbeeId}} {}"
    ]

    // Enable cluster reporting
    log.info 'configuring attribute reporting'
    cmds += zigbee.configureReporting(PHILIPS_HUE_CLUSTER_ID, HUE_STATE_ID, DataType.STRING_OCTET, 1, 300)
    cmds += zigbee.configureReporting(COLOR_CLUSTER_ID, 0x00, DataType.UINT8, 1, 3600) // Hue sends 0, 1, 3 and 4

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
    return zigbee.command(IDENTIFY_CLUSTER_ID, TRIGGER_EFFECT_CMD_ID, [:], 0, "${intToHexStr(effect)} 00")
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
    return zigbee.command(ON_OFF_CLUSTER_ID, 0x40, [:], 0, '00 00')
}

List<String> on() {
    if (settings.txtEnable) { log.info 'turn on' }
    sendSwitchEvent(true) // assume success
    return zigbee.command(ON_OFF_CLUSTER_ID, 0x01)
}

/*------------------------ ZIGBEE MESSAGE PARSING --------------------*/
void parse(String description) {
    state.lastCheckinTime = new Date().toLocaleString()
    Map descMap = zigbee.parseDescriptionAsMap(description)
    // if (settings.logEnable) {
    //     log.trace "zigbee: ${descMap}"
    // }

    if (descMap.isClusterSpecific == false) {
        parseGlobalCommands(descMap)
        return
    }

    switch (descMap.clusterInt as Integer) {
        case BASIC_CLUSTER_ID:
            parseBasicCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseBasicCluster(m) }
            break
        case COLOR_CLUSTER_ID:
            parseColorCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseColorCluster(m) }
            break
        case GROUPS_CLUSTER_ID:
            parseGroupsCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseGroupsCluster(m) }
            break
        case PHILIPS_HUE_CLUSTER_ID:
            parsePhilipsHueCluster(descMap)
            descMap.additionalAttrs?.each { m -> parsePhilipsHueCluster(m) }
            break
        case LEVEL_CLUSTER_ID:
            parseLevelCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseLevelCluster(m) }
            break
        case ON_OFF_CLUSTER_ID:
            parseOnOffCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseOnOffCluster(m) }
            break
        default:
            if (settings.logEnable) {
                log.debug "Unknown message cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Basic Cluster Parsing
 */
void parseBasicCluster(Map descMap) {
    if (settings.logEnable) { log.trace 'parsing basic cluster' }
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
                log.debug "Unknown basic cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Color Cluster Parsing
 */
void parseColorCluster(Map descMap) {
    if (settings.logEnable) { log.trace 'parsing color cluster' }
    switch (descMap.attrInt as Integer) {
        case 0x00: // hue
            sendHueEvent(descMap.value)
            break
        case 0x01: // saturation
            sendSaturationEvent(descMap.value)
            break
        case 0x03: // current X
            if (settings.logEnable) { log.debug 'ignoring current X attribute' }
            break
        case 0x04: // current Y
            if (settings.logEnable) { log.debug 'ignoring current Y attribute' }
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
                log.debug "Unknown color cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Global Command Parsing
 */
void parseGlobalCommands(Map descMap) {
    if (settings.logEnable) { log.trace 'zigbee parsing global command' }
    switch (hexStrToUnsignedInt(descMap.command)) {
        case 0x01: // read attribute response
            if (settings.logEnable) { log.debug "readAttributeResponse: ${descMap.data[0]}" }
            break
        case 0x04: // write attribute response
            if (settings.logEnable) { log.debug "writeAttributeResponse: ${descMap.data}" }
            break
        case 0x05: // write attribute no response
            if (settings.logEnable) { log.debug "writeAttributeNoResponse: ${descMap.data}" }
            break
        case 0x07: // configure reporting response
            if (settings.logEnable) { log.debug "configureReportingResponse: ${descMap.data}" }
            break
        case 0x09: // read reporting configuration response
            if (settings.logEnable) { log.debug "readReportingResponse: ${descMap.data}" }
            // TODO
            break
        case 0x0B: // default command response
            String clusterId = descMap.clusterId
            String commandId = descMap.data[0]
            int statusCode = hexStrToUnsignedInt(descMap.data[1])
            String status = ZigbeeStatusEnum[statusCode] ?: "0x${descMap.data[1]}"
            if (settings.logEnable) {
                log.trace "zigbee command status (clusterId 0x${clusterId}, commandId 0x${commandId}) ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee command error (clusterId 0x${clusterId}, commandId 0x${commandId}) ${status}"
            }
            break
        default:
            if (settings.logEnable) {
                log.debug "Unknown zigbee global command: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Groups Cluster Parsing
 */
void parseGroupsCluster(Map descMap) {
    if (settings.logEnable) { log.trace 'zigbee parsing groups cluster' }
    switch (descMap.command as Integer) {
        case 0x02: // Group membership response
            int groupCount = hexStrToUnsignedInt(descMap.data[1])
            log.info "zigbee group memberships: ${groupCount}"
            Set<String> groups = []
            for (int i = 0; i < groupCount; i++) {
                int pos = (i * 2) + 2
                String group = descMap.data[pos] + descMap.data[pos + 1]
                groups.add(group)
            }
            state.groups = groups
            break
        default:
            if (settings.logEnable) {
                log.debug "Unknown zigbee groups cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Hue Specific Cluster Parsing
 */
void parsePhilipsHueCluster(Map descMap) {
    if (settings.logEnable) { log.trace 'zigbee parsing philips hue custom cluster' }
    switch (descMap.attrInt as Integer) {
        case HUE_STATE_ID:
            parsePhilipsHueClusterState(descMap)
            break
        default:
            if (settings.logEnable) {
                log.debug "Unknown zigbee philips hue cluster: ${descMap}"
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
        log.debug "hue cluster report [power: ${isOn}, level: ${level}, mode: 0x${intToHexStr(mode, 2)}]"
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
                log.debug "Unknown mode: ${intToHexStr(mode)}"
            }
            break
    }
}

/*
 * Zigbee Level Cluster Parsing
 */
void parseLevelCluster(Map descMap) {
    if (settings.logEnable) { log.trace 'zigbee parsing level cluster' }
    switch (descMap.attrInt as Integer) {
        case 0x00:
            sendLevelEvent(hexStrToUnsignedInt(descMap.value))
            break
        default:
            if (settings.logEnable) {
                log.debug "Unknown zigbee level cluster: ${descMap}"
            }
            break
    }
}

/*
 * Zigbee On Off Cluster Parsing
 */
void parseOnOffCluster(Map descMap) {
    if (settings.logEnable) { log.trace 'zigbee parsing on/off cluster' }
    switch (descMap.attrInt as Integer) {
        case 0x00:
            sendSwitchEvent(descMap.value == '01')
            break
        default:
            if (settings.logEnable) {
                log.debug "Unknown zigbee on/off cluster: ${descMap}"
            }
            break
    }
}
/*-------------------------- END OF ZIGBEE PARSING --------------------*/

List<String> presetLevel(Object value) {
    Integer level = value.toInteger()
    if (level == 0) { return [] }
    if (settings.txtEnable) { log.info "presetLevel (${value})" }
    String rate = device.currentValue('switch') == 'off' ? '0000' : settings.transitionTime
    return setLevelPrivate(level, rate, 0, true)
}

List<String> refresh() {
    log.info 'refresh'
    List<String> cmds = []

    // Get Firmware Version
    cmds += zigbee.readAttribute(BASIC_CLUSTER_ID, FIRMWARE_VERSION_ID, [:], DELAY_MS)

    // Get Minimum/Maximum Color Temperature
    state.ct = state.ct ?: [ high: 6536, low: 2000 ] // default values
    cmds += zigbee.readAttribute(COLOR_CLUSTER_ID, [0x400B, 0x400C], [:], DELAY_MS)

    // Refresh other attributes
    cmds += zigbee.onOffRefresh(DELAY_MS)
    cmds += zigbee.levelRefresh(DELAY_MS)
    cmds += zigbee.readAttribute(COLOR_CLUSTER_ID, [0x00, 0x01, 0x07, 0x08], [:], 0)

    // Get groups
    cmds += zigbee.command(GROUPS_CLUSTER_ID, 0x02, [:], DELAY_MS, '00')

    return cmds
}

List<String> setColor(Map value) {
    List<String> cmds = []
    if (settings.txtEnable) { log.info "setColor (${value})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer hue = value.hue.toInteger()
    Integer saturation = value.saturation.toInteger()
    String rateHex = settings.transitionTime
    if (hue < 0) { hue = 0 }
    if (hue > 100) { hue = 100 }
    if (saturation < 0) { saturation = 0 }
    if (saturation > 100) { saturation = 100 }
    String scaledHueValue = intToHexStr(Math.round(hue * 0xfe / 100.0))
    String scaledSatValue = intToHexStr(Math.round(saturation * 0xfe / 100.0))
    cmds += zigbee.command(COLOR_CLUSTER_ID, COLOR_CMD_ID, [:], DELAY_MS, "${scaledHueValue} ${scaledSatValue} ${isOn ? rateHex : '0000'} ${PRESTAGING_OPTION}")
    if (value.level != null) {
        // This will turn on the device if it is off and set level
        cmds += setLevelPrivate(value.level, rateHex)
    }
    return cmds
}

List<String> setColorTemperature(Object colorTemperature, Object level = null, Object transitionTime = null) {
    List<String> cmds = []
    if (settings.txtEnable) { log.info "setColorTemperature (${colorTemperature}, ${level}, ${rate})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    String rateHex = transitionTime != null ? zigbee.swapOctets(intToHexStr((transitionTime.toBigDecimal() * 10).toInteger(), 2)) : settings.transitionTime
    Integer ct = colorTemperature.toInteger()
    if (state.ct?.high && ct > state.ct.high) { ct = state.ct.high as Integer }
    if (state.ct?.low && ct < state.ct.low) { ct = state.ct.low as Integer }
    String miredHex = ctToMiredHex(ct)
    cmds += zigbee.command(COLOR_CLUSTER_ID, COLOR_CMD_CT_ID, [:], DELAY_MS, "${miredHex} ${isOn ? rateHex : '0000'} ${PRESTAGING_OPTION}")
    if (level != null) {
        // This will turn on the device if it is off and set level
        cmds += setLevelPrivate(level.toInteger(), rateHex)
    }
    return cmds
}

List<String> setEffect(Object number) {
    Integer effectNumber = number.toInteger()
    if (effectNumber < 0 || effectNumber > HueEffectNames.size()) {
        log.warn "setEffect ${number} not in valid range"
        return []
    }
    if (settings.txtEnable) { log.info "setEffect (${number})" }
    if (effectNumber == 0) {
        state.remove('effect')
        return zigbee.command(PHILIPS_HUE_CLUSTER_ID, 0x00, [ mfgCode: VENDOR_PHILIPS_ID ], 0, '2000 00')
    }
    state.effect = number
    int effect = HueEffectNames.keySet()[effectNumber - 1]
    return zigbee.command(PHILIPS_HUE_CLUSTER_ID, 0x00, [ mfgCode: VENDOR_PHILIPS_ID ], 0, "2100 01 ${intToHexStr(effect)}")
}

List<String> setHue(Object value) {
    if (settings.txtEnable) { log.info "setHue (${value})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer hue = value.toInteger()
    String rateHex = isOn ? settings.transitionTime : '0000'
    if (hue == null || hue < 0) { hue = 0 }
    if (hue > 100) { hue = 100 }
    String scaledHueValue = intToHexStr(Math.round(hue * 0xfe / 100.0))
    return zigbee.command(COLOR_CLUSTER_ID, COLOR_CMD_HUE_ID, [:], 0, "${scaledHueValue} 00 ${rateHex} ${PRESTAGING_OPTION}")
}

List<String> setLevel(Object value, Object transitionTime = null) {
    if (settings.txtEnable) { log.info "setLevel (${value}, ${rate})" }
    String rateHex = transitionTime != null ? zigbee.swapOctets(intToHexStr((transitionTime.toBigDecimal() * 10).toInteger(), 2)) : settings.transitionTime
    return setLevelPrivate(value.toInteger(), rateHex)
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
    Integer saturation = value.toInteger()
    String rateHex = isOn ? settings.transitionTime : '0000'
    if (saturation == null || saturation < 0) { saturation = 0 }
    if (saturation > 100) { saturation = 100 }
    String scaledSatValue = intToHexStr(Math.round(saturation * 0xfe / 100.0))
    return zigbee.command(COLOR_CLUSTER_ID, COLOR_CMD_SAT_ID, [:], 0, "${scaledSatValue} 00 ${rateHex} ${PRESTAGING_OPTION}")
}

List<String> startLevelChange(String direction) {
    if (settings.txtEnable) { log.info "startLevelChange (${direction})" }
    String rate = settings.levelChangeRate
    String upDown = direction == 'down' ? '01' : '00'
    return zigbee.command(LEVEL_CLUSTER_ID, MOVE_CMD_ON_OFF_ID, [:], 0, "${upDown} ${rate}")
}

List<String> stepLevelChange(String direction, Object stepSize, Object transitionTime = null) {
    if (settings.txtEnable) { log.info "stepLevelChange (${direction}, ${stepSize}, ${transitionTime})" }
    String rateHex = transitionTime != null ? zigbee.swapOctets(intToHexStr((transitionTime.toBigDecimal() * 10).toInteger(), 2)) : settings.transitionTime
    Integer level = stepSize.toInteger()
    if (level < 1) { level = 1 }
    if (level > 99) { level = 99 }
    String stepHex = intToHexStr((level * 2.55).toInteger())
    String upDown = direction == 'down' ? '01' : '00'
    return zigbee.command(LEVEL_CLUSTER_ID, MOVE_CMD_STEP_ID, [:], 0, "${upDown} ${stepHex} ${rateHex}")
}

List<String> stopLevelChange() {
    if (settings.txtEnable) { log.info 'stopLevelChange' }
    return zigbee.command(LEVEL_CLUSTER_ID, MOVE_CMD_STOP_ID, [:], 0)
}

List<String> toggle() {
    if (settings.txtEnable) { log.info 'toggle' }
    return zigbee.command(ON_OFF_CLUSTER_ID, 0x02, [:], 0)
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
 * Implementation Methods
 */
private String ctToMiredHex(int ct) {
    return zigbee.swapOctets(intToHexStr((1000000 / ct).toInteger(), 2))
}

private int miredHexToCt(String mired) {
    return (1000000 / hexStrToUnsignedInt(zigbee.swapOctets(mired))) as int
}

private void sendHueEvent(String rawValue) {
    Integer hue = Math.round((hexStrToUnsignedInt(rawValue) / 255.0) * 100)
    if (device.currentValue('hue') as Integer != hue) {
        String descriptionText = "hue was set to ${hue}"
        sendEvent(name: 'hue', value: hue, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
    sendColorNameEvent(hue)
}

private void sendSaturationEvent(String rawValue) {
    Integer saturation = Math.round((hexStrToUnsignedInt(rawValue) / 255.0) * 100)
    if (device.currentValue('saturation') as Integer != saturation) {
        String descriptionText = "saturation was set to ${saturation}"
        sendEvent(name: 'saturation', value: saturation, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendColorModeEvent(String mode) {
    if (device.currentValue('colorMode') != mode) {
        String descriptionText = "color mode was set to ${mode}"
        sendEvent(name: 'colorMode', value: mode, descriptionText: descriptionText)
    }
}

private void sendColorNameEvent(Integer hue) {
    String colorName = ColorNameMap.find { k, v -> hue * 3.6 <= k }?.value
    if (colorName && device.currentValue('colorName') != colorName) {
        descriptionText = "color name was set to ${colorName}"
        sendEvent name: 'colorName', value: colorName, descriptionText: descriptionText
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendColorTempEvent(String rawValue) {
    Integer value = miredHexToCt(rawValue)
    if (state.ct?.high && value > state.ct.high) { return }
    if (state.ct?.low && value < state.ct.low) { return }
    if (device.currentValue('colorTemperature') as Integer != value) {
        String descriptionText = "color temperature was set to ${value}°K"
        sendEvent(name: 'colorTemperature', value: value, descriptionText: descriptionText, unit: '°K')
        if (settings.txtEnable) { log.info descriptionText }
    }
    sendColorTempNameEvent(value)
}

private void sendColorTempNameEvent(Integer ct) {
    String genericName = ColorTempName.find { k , v -> ct < k }?.value
    if (genericName && device.currentValue('colorName') != genericName) {
        String descriptionText = "color is ${genericName}"
        sendEvent(name: 'colorName', value: genericName, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendEffectNameEvent(String rawValue = null) {
    String effectName = 'none'
    if (rawValue != null) {
        int effect = hexStrToUnsignedInt(rawValue)
        effectName = HueEffectNames[effect] ?: 'unknown'
    }
    if (device.currentValue('effectName') != effectName) {
        String descriptionText = "effect was set to ${effectName}"
        sendEvent(name: 'effectName', value: effectName, descriptionText: descriptionText)
    }
}

private void sendLevelEvent(Object rawValue) {
    Integer value = Math.round(rawValue.toInteger() / 2.55)
    if (device.currentValue('level') as Integer != value) {
        String descriptionText = "level was set to ${value}%"
        sendEvent(name: 'level', value: value, descriptionText: descriptionText, unit: '%')
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendSwitchEvent(Boolean isOn) {
    String value = isOn ? 'on' : 'off'
    if (device.currentValue('switch') != value) {
        String descriptionText = "light was turned ${value}"
        sendEvent(name: 'switch', value: value, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private List<String> setLevelPrivate(Object value, String hexTime = '0000', Integer delay = 0, Boolean levelPreset = false) {
    Integer level = value.toInteger()
    if (level < 0) { level = 0 }
    if (level > 100) { level = 100 }
    if (level > 0 && levelPreset == false) { sendSwitchEvent(true) } // assume success
    String hexLevel = intToHexStr((level * 2.55).toInteger())
    if (hexLevel == 'FF') { hexLevel = 'FE' }
    int levelCommand = levelPreset ? LEVEL_CMD_ID : LEVEL_CMD_ON_OFF_ID
    // Payload: Level | Transition Time | Options Mask | Options Override
    // Options: Bit 0x01 enables prestaging level
    return zigbee.command(LEVEL_CLUSTER_ID, levelCommand, [:], delay, "${hexLevel} ${hexTime} ${PRESTAGING_OPTION}")
}
