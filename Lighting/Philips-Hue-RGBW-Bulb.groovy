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
import hubitat.helper.ColorUtils
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: 'Philips Hue RGBW Bulb (Gen3)', importUrl: '',
        namespace: 'philips-hue', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'ChangeLevel'
        capability 'Color Control'
        capability 'Color Temperature'
        capability 'ColorMode'
        capability 'Configuration'
        capability 'Flash'
        capability 'Light'
        capability 'LightEffects'
        capability 'Refresh'
        capability 'Switch Level'
        capability 'Switch'

        command 'identify', [ [ name: 'effectType*', type: 'ENUM', description: 'Effect Type', constraints: IdentifyEffectNames.values() ] ]
        command 'resetToFactoryDefaults'
        command 'toggle'
        command 'updateFirmware'

        fingerprint profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019', manufacturer: 'Signify Netherlands B.V.', model: 'LCA005', deviceJoinName: 'Philips Hue White and Color 60W'
        fingerprint profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019', manufacturer: 'Signify Netherlands B.V.', model: 'LCA007', deviceJoinName: 'Philips Hue White and Color 75W'
        fingerprint profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019', manufacturer: 'Signify Netherlands B.V.', model: 'LCA009', deviceJoinName: 'Philips Hue White and Color 100W'
    }

    preferences {
        input name: 'transitionTime', type: 'enum', title: 'Level transition time', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue
        input name: 'powerRestore', type: 'enum', title: 'Power restore state', options: PowerRestoreOpts.options, defaultValue: PowerRestoreOpts.defaultValue

        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

// Zigbee Cluster IDs
@Field static final int BASIC_CLUSTER_ID = 0x0000
@Field static final int COLOR_CLUSTER_ID = 0x0300
@Field static final int ON_OFF_CLUSTER_ID = 0x0006
@Field static final int COLOR_TEMP_CLUSTER = 0x0300
@Field static final int HUE_EFFECTS_CLUSTER_ID = 0xFC03
@Field static final int IDENTIFY_CLUSTER_ID = 0x0003
@Field static final int LEVEL_CLUSTER_ID = 0x0008

// Zigbee Command IDs
@Field static final int COLOR_CMD_ID = 0x06
@Field static final int COLOR_CMD_CT_ID = 0x0A
@Field static final int COLOR_CMD_HUE_ID = 0x00
@Field static final int COLOR_CMD_SAT_ID = 0x03
@Field static final int LEVEL_CMD_ID = 0x04
@Field static final int TRIGGER_EFFECT_CMD_ID = 0x40

// Zigbee Attribute IDs
@Field static final int HUE_STATE_ID = 0x02
@Field static final int FIRMWARE_VERSION_ID = 0x4000
@Field static final int VENDOR_PHILIPS_ID = 0x100B // Philips vendor code
@Field static final int DELAY_MS = 200 // Delay between zigbee commands

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
    0x01: 'Candle',
    0x02: 'Fireplace',
    0x03: 'Color Loop'
]

@Field static final Map<Integer, String> IdentifyEffectNames = [
    0x00: 'Blink',
    0x01: 'Breathe',
    0x02: 'Okay'
]

@Field static Map PowerRestoreOpts = [
    defaultValue: 'on',
    defaultText: 'On',
    options: [ '01': 'On', '00': 'Off', 'ff': 'Last state' ]
]

@Field static final Map TransitionOpts = [
    defaultText: '1s',
    defaultValue: '0A00',
    options: [ '0000': 'Immediately', '0500': '500ms', '0A00': '1s', '0F00': '1.5s', '1400': '2s', '3200': '5s' ]
]

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'
    state.clear()

    // Todo: Generate dynamic effect based on model
    sendEvent(name: 'lightEffects', value: HueEffectNames.values())

    // Power Restore Behavior
    if (settings.powerRestore != null) {
        cmds += zigbee.writeAttribute(ON_OFF_CLUSTER_ID, 0x4003, DataType.ENUM8, settings.powerRestore)
    }

    // Enable Hue specific cluster reporting
    cmds += zigbee.configureReporting(HUE_EFFECTS_CLUSTER_ID, HUE_STATE_ID, DataType.STRING_OCTET, 1, 300)

    return cmds
}

List<String> identify(String name) {
    Integer effect = IdentifyEffectNames.find { k, v -> v == name }?.key
    if (effect == null) { return [] }
    if (settings.txtEnable) { log.info "identify (${name})" }
    return zigbee.command(IDENTIFY_CLUSTER_ID, TRIGGER_EFFECT_CMD_ID, [:], 0, "${intToHexStr(effect)} 00")
}

void installed() {
    log.info 'installed'
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

List<String> off() {
    if (settings.txtEnable) { log.info 'turn off' }
    sendSwitchEvent(false) // assume success
    return zigbee.off(0)
}

List<String> on() {
    if (settings.txtEnable) { log.info 'turn on' }
    sendSwitchEvent(true) // assume success
    return zigbee.on(0)
}

/**
 * Zigbee Message Parser
 */
void parse(String description) {
    if (description.startsWith('catchall')) { return }
    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (settings.logEnable) {
        log.debug "zigbee map: ${descMap}"
    }

    switch (descMap.clusterInt as Integer) {
        case BASIC_CLUSTER_ID:
            parseBasicCluster(descMap)
            break
        case COLOR_CLUSTER_ID:
            parseColorCluster(descMap)
            break
        case COLOR_TEMP_CLUSTER:
            parseColorTempCluster(descMap)
            break
        case HUE_EFFECTS_CLUSTER_ID:
            parseHueCluster(descMap)
            break
        case LEVEL_CLUSTER_ID:
            parseLevelCluster(descMap)
            break
        case ON_OFF_CLUSTER_ID:
            parseOnOffCluster(descMap)
            break
        default:
            log.debug "DID NOT PARSE MESSAGE: ${descMap}"
            break
    }
}

/*
 * Zigbee Basic Cluster Parsing
 */
void parseBasicCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case FIRMWARE_VERSION_ID:
            updateDataValue('softwareBuild', descMap.value ?: 'unknown')
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
        case 0x07: // ct
            sendColorTempEvent(descMap.value)
            break
        case 0x08: // color mode
            String mode = descMap.value == '02' ? 'CT' : 'RGB'
            sendColorModeEvent(mode)
            break
    }
}

/*
 * Zigbee Color Temperature Cluster Parsing
 */
void parseColorTempCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case 0x400B:
            state.ct = state.ct ?: [:]
            state.ct.high = Math.round(1000000 / hexStrToUnsignedInt(descMap.value))
            break
        case 0x400C:
            state.ct = state.ct ?: [:]
            state.ct.low = Math.round(1000000 / hexStrToUnsignedInt(descMap.value))
            break
    }
}

/*
 * Zigbee Hue Specific Cluster Parsing
 */
void parseHueCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case HUE_STATE_ID:
            runInMillis(200, parseHueClusterStateAttr, [ data: descMap ])
    }
}

/*
 * Attribute 0x0002 seems to encode the light state, where the first
 * two bytes indicate the mode, the next byte OnOff, the next byte
 * Current Level, and the following bytes the mode-specific state.
 * from https://github.com/dresden-elektronik/deconz-rest-plugin/issues/5891
 */
void parseHueClusterStateAttr(Map descMap) {
    int mode = hexStrToUnsignedInt(zigbee.swapOctets(descMap.value[0..3]))
    boolean isOn = hexStrToUnsignedInt(descMap.value[4..5]) == 1
    int level = hexStrToUnsignedInt(descMap.value[6..7])
    if (settings.logEnable) {
        log.debug "hue [power: ${isOn}, level: ${level}, mode: 0x${intToHexStr(mode, 2)}]"
    }

    sendSwitchEvent(isOn)
    if (isOn) { sendLevelEvent(level) }

    switch (mode) {
        case 0x000F: // CT mode
            sendColorTempEvent(descMap.value[8..11])
            sendColorModeEvent('CT')
            break
        case 0x000B: // XY mode
            sendHueColorEvent(descMap.value[6..15])
            sendColorModeEvent('RGB')
            break
        default:
            log.warn "unknown mode ${mode}"
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
    }
}

List<String> refresh() {
    log.info 'refresh'
    List<String> cmds = []

    // Get Firmware Version
    cmds += zigbee.readAttribute(BASIC_CLUSTER_ID, FIRMWARE_VERSION_ID, [:], DELAY_MS)

    // Get Minimum/Maximum Color Temperature
    cmds += zigbee.readAttribute(COLOR_TEMP_CLUSTER, 0x400B, [:], DELAY_MS)
    cmds += zigbee.readAttribute(COLOR_TEMP_CLUSTER, 0x400C, [:], DELAY_MS)

    cmds += zigbee.onOffRefresh(DELAY_MS)
    cmds += zigbee.levelRefresh(DELAY_MS)
    cmds += zigbee.colorTemperatureRefresh(DELAY_MS)
    cmds += zigbee.readAttribute(COLOR_CLUSTER_ID, 0, [:], DELAY_MS)
    cmds += zigbee.readAttribute(COLOR_CLUSTER_ID, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(COLOR_CLUSTER_ID, 7, [:], DELAY_MS)
    cmds += zigbee.readAttribute(COLOR_CLUSTER_ID, 8, [:])

    return cmds
}

List<String> resetToFactoryDefaults() {
    log.warn 'resetToFactoryDefaults'
    return zigbee.command(BASIC_CLUSTER_ID, 0x00)
}

List<String> setColor(Map value) {
    List<String> cmds = []
    String rateHex = settings.transitionTime
    if (device.currentValue('colorMode') != 'RGB') { rateHex = '0000' }
    if (value.level != null) {
        cmds += setLevelPrivate(value.level.toInteger(), rateHex, DELAY_MS)
    }
    String scaledHueValue = intToHexStr(Math.round(value.hue * 0xfe / 100.0))
    String scaledSatValue = intToHexStr(Math.round(value.saturation * 0xfe / 100.0))
    cmds += zigbee.command(COLOR_CLUSTER_ID, COLOR_CMD_ID, [:], 0, "${scaledHueValue} ${scaledSatValue} ${rateHex}")
    return cmds
}

List<String> setColorTemperature(Object colorTemperature, Object level = null, Object transitionTime = null) {
    List<String> cmds = []
    if (settings.txtEnable) { log.info "setColorTemperature(${colorTemperature}, ${level}, ${rate})" }
    String rateHex = transitionTime != null ? zigbee.swapOctets(intToHexStr((transitionTime.toBigDecimal() * 10).toInteger(), 2)) : settings.transitionTime
    if (device.currentValue('colorMode') != 'CT') { rateHex = '0000' }
    if (level != null) {
        cmds += setLevelPrivate(level.toInteger(), rateHex, DELAY_MS)
    }
    Integer ct = colorTemperature.toInteger()
    if (state.ct.high && ct > state.ct.high) { ct = state.ct.high as Integer }
    if (state.ct.low && ct < state.ct.low) { ct = state.ct.low as Integer }
    String miredHex = ctToMiredHex(ct)
    cmds += zigbee.command(COLOR_TEMP_CLUSTER, COLOR_CMD_CT_ID, [:], 0, "${miredHex} ${rateHex}")
    return cmds
}

List<String> setEffect(Object number) {
    Integer effectNumber = number.toInteger()
    if (effectNumber < 0 || effectNumber > HueEffectNames.size()) {
        log.warn "setEffect ${number} not in valid range"
        return []
    }
    if (settings.txtEnable) { log.info "startEffect (${number})" }
    if (effectNumber == 0) {
        state.remove('effect')
        return zigbee.command(HUE_EFFECTS_CLUSTER_ID, 0x00, [ mfgCode: VENDOR_PHILIPS_ID ], 0, '2000 00')
    }
    state.effect = number
    int effect = HueEffectNames.keySet()[effectNumber - 1]
    return zigbee.command(HUE_EFFECTS_CLUSTER_ID, 0x00, [ mfgCode: VENDOR_PHILIPS_ID ], 0, "2100 01 ${intToHexStr(effect)}")
}

List<String> setHue(Object value) {
    String rateHex = settings.transitionTime
    if (device.currentValue('colorMode') != 'RGB') { rateHex = '0000' }
    String scaledHueValue = intToHexStr(Math.round(value.hue * 0xfe / 100.0), 2)
    return zigbee.command(COLOR_CLUSTER_ID, COLOR_CMD_HUE_ID, [:], 0, "${scaledHueValue} ${rateHex}")
}

List<String> setLevel(Object value, Object transitionTime = null) {
    if (settings.txtEnable) { log.info "setLevel(${value}, ${rate})" }
    String rateHex = transitionTime != null ? zigbee.swapOctets(intToHexStr((transitionTime.toBigDecimal() * 10).toInteger(), 2)) : settings.transitionTime
    return setLevelPrivate(value.toInteger(), rateHex, 0)
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
    if (settings.txtEnable) { log.info "setSaturation(${value})" }
    String rateHex = settings.transitionTime
    if (device.currentValue('colorMode') != 'RGB') { rateHex = '0000' }
    String scaledSatValue = intToHexStr(Math.round(value.saturation * 0xfe / 100.0))
    return zigbee.command(COLOR_CLUSTER_ID, COLOR_CMD_SAT_ID, [:], 0, "${scaledSatValue} ${rateHex}")
}

List<String> toggle() {
    if (settings.txtEnable) { log.info 'toggle' }
    return zigbee.command(ON_OFF_CLUSTER_ID, 0x02, [:], 0)
}

void updated() {
    log.info 'updated...'
    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }
}

List<String> updateFirmware() {
    log.info 'updateFirmware...'
    return zigbee.updateFirmware()
}

/*
 * Implementation Methods
 */
private static List<Integer> convertXYBtoRGB(BigDecimal x, BigDecimal y, BigDecimal brightness) {
    if (x < 0 || x > 1 || y < 0 || y > 1 || brightness < 0 || brightness > 1) {
        return [ 0, 0, 0 ]
    }
    BigDecimal z = 1.0 - x - y
    BigDecimal vY = brightness
    BigDecimal vX = (vY / y) * x
    BigDecimal vZ = (vY / y) * z
    BigDecimal r = vX * 1.656492 - vY * 0.354851 - vZ * 0.255038
    BigDecimal g = -vX * 0.707196 + vY * 1.655397 + vZ * 0.036152
    BigDecimal b = vX * 0.051713 - vY * 0.121364 + vZ * 1.011530
    int red = (int) Math.round(r * 255)
    int green = (int) Math.round(g * 255)
    int blue = (int) Math.round(b * 255)
    red = red < 0 ? 0 : red > 255 ? 255 : red
    green = green < 0 ? 0 : green > 255 ? 255 : green
    blue = blue < 0 ? 0 : blue > 255 ? 255 : blue
    return [ red, green, blue ]
}

private String ctToMiredHex(int ct) {
    return zigbee.swapOctets(intToHexStr((1000000 / ct).toInteger(), 2))
}

private int miredHexToCt(String mired) {
    return (1000000 / hexStrToUnsignedInt(zigbee.swapOctets(mired))) as int
}

private void sendHueEvent(String rawValue) {
    Integer hue = hexStrToUnsignedInt(rawValue)
    if (device.currentValue('hue') as Integer != hue) {
        String descriptionText = "${device.displayName} hue was set to ${hue}"
        sendEvent(name: 'hue', value: hue, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendSaturationEvent(String rawValue) {
    Integer saturation = hexStrToUnsignedInt(rawValue)
    if (device.currentValue('saturation') as Integer != saturation) {
        String descriptionText = "${device.displayName} saturation was set to ${saturation}"
        sendEvent(name: 'saturation', value: saturation, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendHueColorEvent(String rawValue) {
    BigDecimal bri = hexStrToUnsignedInt(rawValue[0..1]) / 256
    BigDecimal x = hexStrToUnsignedInt(zigbee.swapOctets(rawValue[2..5])) / 65536
    BigDecimal y = hexStrToUnsignedInt(zigbee.swapOctets(rawValue[6..9])) / 65536
    List<BigDecimal> hsv = ColorUtils.rgbToHSV(convertXYBtoRGB(x, y, bri))
    int hue = Math.round(hsv[0])
    int saturation = Math.round(hsv[1])
    int level = Math.round(hsv[2])
    if (device.currentValue('hue') as Integer != hue) {
        String descriptionText = "${device.displayName} hue was set to ${hue}"
        sendEvent(name: 'hue', value: hue, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
    if (device.currentValue('saturation') as Integer != saturation) {
        String descriptionText = "${device.displayName} saturation was set to ${saturation}"
        sendEvent(name: 'saturation', value: hue, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
    if (device.currentValue('level') as Integer != level) {
        String descriptionText = "${device.displayName} level was set to ${level}%"
        sendEvent(name: 'level', value: level, descriptionText: descriptionText, unit: '%')
        if (settings.txtEnable) { log.info descriptionText }
    }
    sendColorNameEvent(hue, saturation, level)
}

private void sendColorModeEvent(String mode) {
    if (device.currentValue('colorMode') != mode) {
        String descriptionText = "${device.displayName} color mode was set to ${mode}"
        sendEvent(name: 'colorMode', value: mode, descriptionText: descriptionText)
    }
}

private void sendColorNameEvent(int hue, int saturation, int level) {
    String colorName = ColorNameMap.find { k, v -> hue * 3.6 <= k }?.value
    if (device.currentValue('colorName') != colorName) {
        descriptionText = "${device.displayName} color name was set to ${colorName}"
        sendEvent name: 'colorName', value: colorName, descriptionText: descriptionText
        if (settings.txtEnable) { log.info descriptionText }
    }
    String color = "{hue=${hue}, saturation=${saturation}, level=${level}}"
    if (device.currentValue('color') != colorName) {
        descriptionText = "${device.displayName} color set to ${color}"
        sendEvent name: 'color', value: color, descriptionText: descriptionText
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendColorTempEvent(String rawValue) {
    Integer value = miredHexToCt(rawValue)
    if (device.currentValue('colorTemperature') as Integer != value) {
        sendColorTempNameEvent(value)
        String descriptionText = "${device.displayName} color temperature was set to ${value}°K"
        sendEvent(name: 'colorTemperature', value: value, descriptionText: descriptionText, unit: '°K')
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendColorTempNameEvent(Integer ct) {
    String genericName = ColorTempName.find { k , v -> ct < k }?.value
    if (genericName && device.currentValue('colorName') != genericName) {
        String descriptionText = "${device.displayName} color is ${genericName}"
        sendEvent(name: 'colorName', value: genericName, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendLevelEvent(Object rawValue) {
    Integer value = Math.round(rawValue.toInteger() / 2.55)
    if (device.currentValue('level') as Integer != value) {
        String descriptionText = "${device.displayName} level was set to ${value}%"
        sendEvent(name: 'level', value: value, descriptionText: descriptionText, unit: '%')
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendSwitchEvent(Boolean isOn) {
    String value = isOn ? 'on' : 'off'
    if (device.currentValue('switch') != value) {
        String descriptionText = "${device.displayName} was turned ${value}"
        sendEvent(name: 'switch', value: value, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private List<String> setLevelPrivate(Object value, String hexRate, Integer delay = 0) {
    Integer level = value.toInteger()
    if (level < 0) { level = 0 }
    if (level > 100) { level = 100 }
    String hexLevel = intToHexStr((level * 2.55).toInteger())
    if (hexLevel == 'FF') { hexLevel = 'FE' }
    return zigbee.command(LEVEL_CLUSTER_ID, LEVEL_CMD_ID, [:], delay, "${hexLevel} ${hexRate}")
}
