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
 *  FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 *
 *  Thanks to the Zigbee2Mqtt and Dresden-elektronik teams for
 *  their existing work in decoding the Hue protocol.
 */

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.transform.Field
import hubitat.helper.ColorUtils
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: 'Philips Hue White and Color Ambiance',
        importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/PhilipsHue/Philips-Hue-Zigbee-Bulb-RGBW.groovy',
        namespace: 'philips-hue', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Bulb'
        capability 'Change Level'
        capability 'Color Control'
        capability 'Color Temperature'
        capability 'Color Mode'
        capability 'Configuration'
        capability 'Flash'
        capability 'Health Check'
        capability 'Light'
        capability 'Level Preset'
        capability 'Light Effects'
        capability 'Refresh'
        capability 'Switch Level'
        capability 'Switch'

        attribute 'effectName', 'string'
        attribute 'healthStatus', 'enum', ['unknown', 'offline', 'online']

        command 'identify', [[name: 'Effect type*', type: 'ENUM', description: 'Effect Type', constraints: IdentifyEffectNames.values()*.toLowerCase()]]

        command 'setColorXy', [
            [name: 'X*', type: 'NUMBER', description: 'X value'],
            [name: 'Y*', type: 'NUMBER', description: 'Y value'],
            [name: 'Level', type: 'NUMBER', description: 'Level to set'],
            [name: 'Transition time', type: 'NUMBER', description: 'Transition duration in seconds']
        ]
        command 'setEnhancedHue', [[name: 'Hue*', type: 'NUMBER', description: 'Color Hue (0-360)']]
        command 'setScene', [[name: 'Scene name*', type: 'ENUM', description: 'Philips Hue defined scene', constraints: HueColorScenes.keySet().sort()]]

        command 'stepColorTemperature', [
            [name: 'Direction*', type: 'ENUM', description: 'Direction for step change request', constraints: ['up', 'down']],
            [name: 'Step Size (Mireds)*', type: 'NUMBER', description: 'Mireds step size (1-300)'],
            [name: 'Transition time', type: 'NUMBER', description: 'Transition duration in seconds']
        ]
        command 'stepHueChange', [
            [name: 'Direction*', type: 'ENUM', description: 'Direction for step change request', constraints: ['up', 'down']],
            [name: 'Step Size*', type: 'NUMBER', description: 'Hue change step size (1-99)'],
            [name: 'Transition time', type: 'NUMBER', description: 'Transition duration in seconds']
        ]
        command 'stepLevelChange', [
            [name: 'Direction*', type: 'ENUM', description: 'Direction for step change request', constraints: ['up', 'down']],
            [name: 'Step Size*', type: 'NUMBER', description: 'Level change step size (1-99)'],
            [name: 'Transition time', type: 'NUMBER', description: 'Transition duration in seconds']
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
        fingerprint model: 'LCD007', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,0300,FC01', outClusters: '0019'
    }

    preferences {
        input name: 'levelUpTransition', type: 'enum', title: '<b>Dim up transition length</b>', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true, description: \
             '<i>Changes the speed the light dims up. Increasing the value slows down the transition.</i>'
        input name: 'levelDownTransition', type: 'enum', title: '<b>Dim down transition length</b>', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true, description: \
             '<i>Changes the speed the light dims down. Increasing the value slows down the transition.</i>'
        input name: 'colorTransitionTime', type: 'enum', title: '<b>Color transition length</b>', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true, description: \
             '<i>Changes the speed the light changes color/temperature. Increasing the value slows down the transition.</i>'

        input name: 'levelChangeRate', type: 'enum', title: '<b>Level change rate</b>', options: LevelRateOpts.options, defaultValue: LevelRateOpts.defaultValue, required: true, description: \
             '<i>Changes the speed that the light changes when using <b>start level change</b> until <b>stop level change</b> is sent.</i>'

        input name: 'offCommandMode', type: 'enum', title: '<b>Off command mode</b>', options: OffModeOpts.options, defaultValue: OffModeOpts.defaultValue, required: true, description: \
             '<i>Changes off command. <b>Fade out</b> (default), <b>Instant</b> or <b>Dim to zero</b> (On will dim back to previous level).</i>'

        input name: 'flashEffect', type: 'enum', title: '<b>Flash effect</b>', options: IdentifyEffectNames.values(), defaultValue: 'Blink', required: true, description: \
             '<i>Changes the effect used when the <b>flash</b> command is used.</i>'
        input name: 'powerRestore', type: 'enum', title: '<b>Power restore mode</b>', options: PowerRestoreOpts.options, defaultValue: PowerRestoreOpts.defaultValue, description: \
             '<i>Changes what happens when power to the bulb is restored.</i>'

        input name: 'enableReporting', type: 'bool', title: '<b>Enable state reporting</b>', defaultValue: true, description: \
             '<i>Enables the use of reporting to push updates instead of polling bulb. Only available from Generation 3 bulbs.</i>'

        input name: 'healthCheckInterval', type: 'enum', title: '<b>Healthcheck Interval</b>', options: HealthcheckIntervalOpts.options, defaultValue: HealthcheckIntervalOpts.defaultValue, required: true, description: \
             '<i>Changes how often the hub pings the bulb to check health.</i>'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description: \
             '<i>Enables command logging.</i>'
        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description: \
             '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

@Field static final String VERSION = '1.06'

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'

    state.clear()
    state.ct = [high: 6536, low: 2000] // default values
    state.reportingEnabled = false
    device.deleteCurrentState('color') // attribute not used

    // Power Restore Behavior
    if (settings.powerRestore != null) {
        log.info "configure: setting power restore state to 0x${intToHexStr(settings.powerRestore as Integer)}"
        cmds += zigbee.writeAttribute(zigbee.ON_OFF_CLUSTER, POWER_RESTORE_ID, DataType.ENUM8, settings.powerRestore as Integer, [:], DELAY_MS)
        cmds += zigbee.writeAttribute(zigbee.COLOR_CONTROL_CLUSTER, 0x4010, DataType.UINT16, 0xFFFF, [:], DELAY_MS)
    }

    // Attempt to enable cluster reporting, if it fails we fall back to polling after commands
    if (settings.enableReporting == false) {
        cmds += zigbee.configureReporting(PHILIPS_PRIVATE_CLUSTER, HUE_PRIVATE_STATE_ID, DataType.STRING_OCTET, 0, 0xFFFF, null, [mfgCode: PHILIPS_VENDOR], DELAY_MS)
        cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x00, DataType.UINT8, 0, 0xFFFF, 0, [:], DELAY_MS)
        cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x01, DataType.UINT8, 0, 0xFFFF, 1, [:], DELAY_MS)
    } else {
        log.info 'configure: attempting to enable state reporting'
        cmds += zigbee.configureReporting(PHILIPS_PRIVATE_CLUSTER, HUE_PRIVATE_STATE_ID, DataType.STRING_OCTET, 1, REPORTING_MAX, null, [mfgCode: PHILIPS_VENDOR], DELAY_MS)
        cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x00, DataType.UINT8, 1, REPORTING_MAX, 1, [:], DELAY_MS)
        cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x01, DataType.UINT8, 1, REPORTING_MAX, 1, [:], DELAY_MS)
    }

    // Unbind unused cluster reporting (on/off, level, XY, CT & mode are reported via private cluster)
    cmds += zigbee.configureReporting(zigbee.ON_OFF_CLUSTER, 0x00, DataType.BOOLEAN, 0, 0xFFFF, null, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.LEVEL_CONTROL_CLUSTER, 0x00, DataType.UINT8, 0, 0xFFFF, 1, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x03, DataType.UINT16, 0, 0xFFFF, 1, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x04, DataType.UINT16, 0, 0xFFFF, 1, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x07, DataType.UINT16, 0, 0xFFFF, 1, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.COLOR_CONTROL_CLUSTER, 0x08, DataType.ENUM8, 0, 0xFFFF, null, [:], DELAY_MS)

    if (settings.logEnable) {
        log.debug "zigbee configure cmds: ${cmds}"
    }

    runIn(2, 'refresh')
    return cmds as List<String>
}

/**
 * Send health status event upon a timeout
 */
void deviceCommandTimeout() {
    log.warn 'no response received (device offline?)'
    sendHealthStatusEvent('offline')
}

/**
 * Flash Command
 * @param rate not used
 * @return List of zigbee commands
 */
List<String> flash(final Object rate = null) {
    if (settings.txtEnable) {
        log.info "flash (${rate})"
    }
    return identify(settings.flashEffect as String)
}

/**
 * Identify Command
 * @param name effect name
 * @return List of zigbee commands
 */
List<String> identify(final String name) {
    final Integer effect = IdentifyEffectNames.find { final key, final value -> value.equalsIgnoreCase(name) }?.key
    if (effect == null) {
        return []
    }
    if (settings.txtEnable) {
        log.info "identify (${name})"
    }
    final String effectStr = DataType.pack(effect, DataType.UINT8)
    return zigbee.command(zigbee.IDENTIFY_CLUSTER, 0x40, [:], 0, "${effectStr} 00")
}

/**
 * Invoked by Hubitat when driver is installed
 */
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
    sendEvent(name: 'healthStatus', value: 'unknown')
}

/**
 * Disable logging (for debugging)
 */
void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

/**
 * Off Command
 * @return List of zigbee commands
 */
List<String> off() {
    final Integer mode = settings.offCommandMode as Integer
    if (mode == 0xFF) {
        state.previousLevel = device.currentValue('level') as Integer
        return setLevel(0)
    }
    if (settings.txtEnable) {
        log.info 'turn off'
    }
    scheduleCommandTimeoutCheck()
    final String variant = DataType.pack(mode, DataType.UINT8)
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x40, [:], 0, "00 ${variant}") +
        ifPolling { zigbee.onOffRefresh(0) }
}

/**
 * On Command
 * @return List of zigbee commands
 */
List<String> on() {
    final Integer mode = settings.offCommandMode as Integer
    if (state.previousLevel && mode == 0xFF) {
        final Integer level = state.previousLevel as Integer
        state.remove('previousLevel')
        return setLevel(level)
    }
    if (settings.txtEnable) {
        log.info 'turn on'
    }
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x01, [:], 0) +
        ifPolling { zigbee.onOffRefresh(0) }
}

/**
 * Ping Command
 * @return List of zigbee commands
 */
List<String> ping() {
    if (settings.txtEnable) {
        log.info 'ping...'
    }
    // Using attribute 0x00 as a simple ping/pong mechanism
    scheduleCommandTimeoutCheck()
    return zigbee.readAttribute(zigbee.BASIC_CLUSTER, PING_ATTR_ID, [:], 0)
}

/**
 * Preset Level Command
 * @param value level percent (0-100)
 * @return List of zigbee commands
 */
List<String> presetLevel(final BigDecimal value) {
    if (settings.txtEnable) {
        log.info "presetLevel (${value})"
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer rate = isOn ? getLevelTransitionRate(value) : 0
    scheduleCommandTimeoutCheck()
    return setLevelPrivate(value, rate, 0, true)
}

/**
 * Refresh Command
 * @return List of zigbee commands
 */
List<String> refresh() {
    log.info 'refresh'
    List<String> cmds = []

    // Get Firmware Version
    cmds += zigbee.readAttribute(zigbee.BASIC_CLUSTER, FIRMWARE_VERSION_ID, [:], DELAY_MS)

    // Get Power Restore state
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, POWER_RESTORE_ID, [:], DELAY_MS)

    // Get Minimum/Maximum Color Temperature
    state.ct = state.ct ?: [high: 6536, low: 2000] // default values
    cmds += zigbee.readAttribute(zigbee.COLOR_CONTROL_CLUSTER, [0x400B, 0x400C], [:], DELAY_MS)

    // Get device type and supported effects
    cmds += zigbee.readAttribute(PHILIPS_PRIVATE_CLUSTER, [0x01, 0x11], [mfgCode: PHILIPS_VENDOR], DELAY_MS)

    // Refresh other attributes
    cmds += hueStateRefresh(DELAY_MS)
    cmds += colorRefresh(DELAY_MS)

    // Get group membership
    cmds += zigbee.command(zigbee.GROUPS_CLUSTER, 0x02, [:], DELAY_MS, '00')

    scheduleCommandTimeoutCheck()
    return cmds
}

/**
 * Set Color Command
 * @param value color map (hue, saturation, level, rate)
 * @return List of zigbee commands
 */
List<String> setColor(final Map value) {
    List<String> cmds = []
    if (settings.txtEnable) {
        log.info "setColor (${value})"
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer hue = constrain(value.hue)
    final Integer saturation = constrain(value.saturation)
    final Integer rate = isOn ? getColorTransitionRate(value.rate) : 0
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final String scaledHueValue = DataType.pack(Math.round(hue * 2.54), DataType.UINT8)
    final String scaledSatValue = DataType.pack(Math.round(saturation * 2.54), DataType.UINT8)
    if (value.level != null) {
        // This will turn on the device if it is off and set level
        cmds += setLevelPrivate(value.level, getLevelTransitionRate(value.level))
    }
    cmds += zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x06, [:], DELAY_MS, "${scaledHueValue} ${scaledSatValue} ${isOn ? rateHex : '0000'} ${PRE_STAGING_OPTION}")
    scheduleCommandTimeoutCheck()
    return cmds + ifPolling(DELAY_MS + (rate * 100)) { colorRefresh(0) }
}

/**
 * Set Color Temperature Command
 * @param colorTemperature color temperature (mireds)
 * @param level level percent (0-100)
 * @param transitionTime transition time (seconds)
 * @return List of zigbee commands
 */
List<String> setColorTemperature(final BigDecimal colorTemperature, final BigDecimal level = null, final BigDecimal transitionTime = null) {
    List<String> cmds = []
    if (settings.txtEnable) {
        log.info "setColorTemperature (${colorTemperature}, ${level}, ${transitionTime})"
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer rate = isOn ? getColorTransitionRate(transitionTime) : 0
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final Integer ct = constrain(colorTemperature, state.ct.low as Integer, state.ct.high as Integer)
    final String miredHex = DataType.pack(ctToMired(ct), DataType.UINT16, true)
    cmds += zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x0A, [:], DELAY_MS, "${miredHex} ${rateHex} ${PRE_STAGING_OPTION}")
    if (level != null) {
        // This will turn on the device if it is off and set level
        cmds += setLevelPrivate(level, getLevelTransitionRate(level, transitionTime))
    }
    scheduleCommandTimeoutCheck()
    return cmds + ifPolling(DELAY_MS + (rate * 100)) { colorRefresh(0) }
}

/**
 * Set Color XY Command
 * @param colorX color X
 * @param colorY color Y
 * @param level level percent (0-100)
 * @param transitionTime transition time (seconds)
 * @return List of zigbee commands
 */
List<String> setColorXy(final BigDecimal colorX, final BigDecimal colorY, final BigDecimal level = null, final BigDecimal transitionTime = null) {
    List<String> cmds = []
    if (settings.txtEnable) {
        log.info "setColorXy (${colorX}, ${colorY}, ${level})"
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final int intX = Math.round(constrain(colorX) * 0xFFFF).intValue() // 0..65279
    final int intY = Math.round(constrain(colorY) * 0xFFFF).intValue() // 0..65279
    final Integer rate = isOn ? getColorTransitionRate(transitionTime) : 0
    final String hexX = DataType.pack(intX, DataType.UINT16, true)
    final String hexY = DataType.pack(intY, DataType.UINT16, true)
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    if (level != null) {
        // This will turn on the device if it is off and set level
        cmds += setLevelPrivate(level, getLevelTransitionRate(level))
    }
    cmds += zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x07, [:], DELAY_MS, "${hexX} ${hexY} ${isOn ? rateHex : '0000'} ${PRE_STAGING_OPTION}")
    scheduleCommandTimeoutCheck()
    return cmds + ifPolling(DELAY_MS + (rate * 100)) { colorRefresh(0) }
}

/**
 * Set Effect Command
 * @param number effect number
 * @return List of zigbee commands
 */
List<String> setEffect(final BigDecimal number) {
    final List<String> effectNames = parseJson(device.currentValue('lightEffects') ?: '[]')
    final Integer effectNumber = constrain(number, 0, effectNames.size())
    if (settings.txtEnable) {
        log.info "setEffect (${number})"
    }
    if (effectNumber == 0) {
        state.remove('effect')
        return zigbee.command(PHILIPS_PRIVATE_CLUSTER, 0x00, [mfgCode: PHILIPS_VENDOR], 0, '2000 00')
    }
    final String effectName = effectNames[effectNumber - 1]
    state.effect = number
    final int effect = HueEffectNames.find { final key, final value -> value == effectName }?.key
    final String effectHex = DataType.pack(effect, DataType.UINT8)
    scheduleCommandTimeoutCheck()
    return zigbee.command(PHILIPS_PRIVATE_CLUSTER, 0x00, [mfgCode: PHILIPS_VENDOR], 0, "2100 01 ${effectHex}") +
        ifPolling { hueStateRefresh(0) }
}

/**
 * Set Enhanced Hue Command
 * @param value hue value (0-360)
 * @return List of zigbee commands
 */
List<String> setEnhancedHue(final BigDecimal value) {
    if (settings.txtEnable) {
        log.info "setEnhancedHue (${value})"
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer hue = constrain(value, 0, 360)
    final Integer rate = isOn ? getColorTransitionRate() : 0
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final String scaledHueHex = DataType.pack(Math.round(hue * 182.04444).intValue(), DataType.UINT16, true)
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x40, [:], 0, "${scaledHueHex} 00 ${rateHex} ${PRE_STAGING_OPTION}") +
        ifPolling(DELAY_MS + (rate * 100)) { colorRefresh(0) }
}

/**
 * Set Hue Command
 * @param value hue value (0-100)
 * @return List of zigbee commands
 */
List<String> setHue(final BigDecimal value) {
    if (settings.txtEnable) {
        log.info "setHue (${value})"
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer hue = (Integer) constrain(value)
    final Integer rate = isOn ? getColorTransitionRate() : 0
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final String scaledHueHex = DataType.pack(Math.round(hue * 2.54).intValue(), DataType.UINT8)
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x00, [:], 0, "${scaledHueHex} 00 ${rateHex} ${PRE_STAGING_OPTION}") +
        ifPolling(DELAY_MS + (rate * 100)) { colorRefresh(0) }
}

/**
 * Set Scene Command
 * @param name scene name from HueColorScenes
 * @return List of zigbee commands
 */
List<String> setScene(final String name) {
    List<String> cmds = []
    final Map formula = HueColorScenes[name] as Map
    if (!formula) {
        return []
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer rate = isOn ? getColorTransitionRate() : 0
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final String scaledHueHex = DataType.pack(Math.round((int)formula.hue * 182.04444).intValue(), DataType.UINT8)
    final String scaledSatHex = DataType.pack(Math.round((int)formula.saturation * 2.54).intValue(), DataType.UINT8)
    scheduleCommandTimeoutCheck()
    cmds += setLevelPrivate(formula.brightness, getLevelTransitionRate(formula.brightness))
    cmds += zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x43, [:], 0, "${scaledHueHex} ${scaledSatHex} ${rateHex} 00 ${PRE_STAGING_OPTION}")
    cmds += ifPolling(DELAY_MS + (rate * 100)) { colorRefresh(0) }
    return cmds as List<String>
}

/**
 * Set Level Command
 * @param value level percent (0-100)
 * @param transitionTime transition time in seconds
 * @return List of zigbee commands
 */
List<String> setLevel(final Object value, final Object transitionTime = null) {
    if (settings.txtEnable) {
        log.info "setLevel (${value}, ${transitionTime})"
    }
    final Integer rate = getLevelTransitionRate(value, transitionTime)
    scheduleCommandTimeoutCheck()
    return setLevelPrivate(value, rate)
}

/**
 * Set Next Effect Command
 * @return List of zigbee commands
 */
List<String> setNextEffect() {
    if (settings.txtEnable) {
        log.info 'setNextEffect'
    }
    BigDecimal number = state.effect ? state.effect + 1 : 1
    if (number > HueEffectNames.size()) {
        number = 1
    }
    return setEffect(number)
}

/**
 * Set Previous Effect Command
 * @return List of zigbee commands
 */
List<String> setPreviousEffect() {
    if (settings.txtEnable) {
        log.info 'setPreviousEffect'
    }
    BigDecimal number = state.effect ? state.effect - 1 : HueEffectNames.size()
    if (number < 1) {
        number = 1
    }
    return setEffect(number)
}

/**
 * Set Saturation Command
 * @param value saturation value (0-100)
 * @return List of zigbee commands
 */
List<String> setSaturation(final BigDecimal value) {
    if (settings.txtEnable) {
        log.info "setSaturation (${value})"
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer saturation = (Integer) constrain(value)
    final Integer rate = isOn ? getColorTransitionRate() : 0
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final String scaledSatHex = DataType.pack(Math.round(saturation * 2.54), DataType.UINT8)
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x03, [:], 0, "${scaledSatHex} 00 ${rateHex} ${PRE_STAGING_OPTION}") +
        ifPolling(DELAY_MS + (rate * 100)) { colorRefresh(0) }
}

/**
 * Start Level Change Command
 * @param direction direction to change level (up/down)
 * @return List of zigbee commands
 */
List<String> startLevelChange(final String direction) {
    if (settings.txtEnable) {
        log.info "startLevelChange (${direction})"
    }
    final String upDown = direction == 'down' ? '01' : '00'
    final String rateHex = DataType.pack(settings.levelChangeRate as Integer, DataType.UINT8)
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, 0x05, [:], 0, "${upDown} ${rateHex}")
}

/**
 * Step Color Temperature Command
 * @param direction direction to change color temperature (up/down)
 * @param stepSize step size in mireds
 * @param transitionTime transition time in seconds
 * @return List of zigbee commands
 */
List<String> stepColorTemperature(final String direction, final BigDecimal stepSize, final BigDecimal transitionTime = null) {
    if (settings.txtEnable) {
        log.info "stepColorTemperatureChange (${direction}, ${stepSize}, ${transitionTime})"
    }
    final Integer rate = getColorTransitionRate(transitionTime)
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final String stepHex = DataType.pack(constrain(stepSize.toInteger(), 1, 300), DataType.UINT16, true)
    final String upDownHex = direction == 'down' ? '01' : '03'
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x4C, [:], 0, "${upDownHex} ${stepHex} ${rateHex} 0000 0000") +
        ifPolling { zigbee.colorRefresh(0) }
}

/**
 * Step Hue Command
 * @param direction direction to change hue (up/down)
 * @param stepSize step size in degrees
 * @param transitionTime transition time in seconds
 * @return List of zigbee commands
 */
List<String> stepHueChange(final String direction, final BigDecimal stepSize, final BigDecimal transitionTime = null) {
    if (settings.txtEnable) {
        log.info "stepHueChange (${direction}, ${stepSize}, ${transitionTime})"
    }
    final Integer rate = getColorTransitionRate(transitionTime)
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final Integer level = constrain(stepSize, 1, 99)
    final String stepHex = DataType.pack((level * 2.55).toInteger(), DataType.UINT8)
    final String upDownHex = direction == 'down' ? '03' : '01'
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.COLOR_CONTROL_CLUSTER, 0x02, [:], 0, "${upDownHex} ${stepHex} ${rateHex}") +
        ifPolling { zigbee.colorRefresh(0) }
}

/**
 * Step Level Command
 * @param direction direction to change level (up/down)
 * @param stepSize step size in percent
 * @param transitionTime transition time in seconds
 * @return List of zigbee commands
 */
List<String> stepLevelChange(final String direction, final BigDecimal stepSize, final BigDecimal transitionTime = null) {
    if (settings.txtEnable) {
        log.info "stepLevelChange (${direction}, ${stepSize}, ${transitionTime})"
    }
    final Integer rate = getLevelTransitionRate(direction == 'down' ? 0 : 100, transitionTime)
    final String rateHex = DataType.pack(rate, DataType.UINT16, true)
    final Integer level = constrain(stepSize, 1, 99)
    final String stepHex = DataType.pack((level * 2.55).toInteger(), DataType.UINT8)
    final String upDownHex = direction == 'down' ? '01' : '00'
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, 0x06, [:], 0, "${upDownHex} ${stepHex} ${rateHex}") +
        ifPolling { zigbee.levelRefresh(0) + zigbee.onOffRefresh(0) }
}

/**
 * Stop Level Change Command
 * @return List of zigbee commands
 */
List<String> stopLevelChange() {
    if (settings.txtEnable) {
        log.info 'stopLevelChange'
    }
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, 0x03, [:], 0) +
        ifPolling { zigbee.levelRefresh(0) + zigbee.onOffRefresh(0) }
}

/**
 * Toggle Command (On/Off)
 * @return List of zigbee commands
 */
List<String> toggle() {
    if (settings.txtEnable) {
        log.info 'toggle'
    }
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x02, [:], 0) +
        ifPolling { zigbee.onOffRefresh(0) }
}

/**
 * Invoked by Hubitat when the driver configuration is updated
 */
void updated() {
    log.info 'updated...'
    log.info "driver version ${VERSION}"
    unschedule()

    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }

    final int interval = (settings.healthCheckInterval as Integer) ?: 0
    if (interval > 0) {
        log.info "scheduling health check every ${interval} minutes"
        scheduleDeviceHealthCheck(interval)
    }

    runIn(1, 'configure')
}

/**
 * Parse Zigbee message
 * @param description Zigbee message in hex format
 */
void parse(final String description) {
    final Map descMap = zigbee.parseDescriptionAsMap(description)
    sendHealthStatusEvent('online')
    unschedule('deviceCommandTimeout')

    if (descMap.profileId == '0000') {
        parseZdo(descMap)
        return
    }

    if (descMap.isClusterSpecific == false) {
        parseGlobalCommands(descMap)
        return
    }

    if (settings.logEnable) {
        final String clusterName = clusterLookup(descMap.clusterInt) ?: "PRIVATE_CLUSTER (0x${descMap.cluster})"
        final String attribute = descMap.attrId ? " attribute 0x${descMap.attrId} (value 0x${descMap.value})" : ''
        log.trace "zigbee received ${clusterName} message" + attribute
    }

    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseBasicCluster(map) }
            break
        case zigbee.COLOR_CONTROL_CLUSTER:
            parseColorCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseColorCluster(map) }
            break
        case zigbee.GROUPS_CLUSTER:
            parseGroupsCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseGroupsCluster(map) }
            break
        case PHILIPS_PRIVATE_CLUSTER:
            parsePrivateCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parsePrivateCluster(map) }
            break
        case zigbee.LEVEL_CONTROL_CLUSTER:
            parseLevelCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseLevelCluster(map) }
            break
        case zigbee.ON_OFF_CLUSTER:
            parseOnOffCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseOnOffCluster(map) }
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown message cluster: ${descMap}"
            }
            break
    }
}

/**
 * Zigbee Basic Cluster Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseBasicCluster(final Map descMap) {
    switch (descMap.attrInt as Integer) {
        case PING_ATTR_ID: // Using 0x01 read as a simple ping/pong mechanism
            if (settings.txtEnable) {
                log.info 'pong..'
            }
            break
        case FIRMWARE_VERSION_ID:
            final String version = descMap.value ?: 'unknown'
            log.info "device firmware version is ${version}"
            updateDataValue('softwareBuild', version)
            break
        case PRODUCT_ID:
            final String name = descMap.value ?: 'unknown'
            log.info "device product identifier is ${name}"
            updateDataValue('productIdentifier', name)
            break
        default:
            log.warn "zigbee received unknown BASIC_CLUSTER: ${descMap}"
            break
    }
}

/**
 * Zigbee Color Cluster Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseColorCluster(final Map descMap) {
    switch (descMap.attrInt as Integer) {
        case 0x00: // hue
            sendHueEvent(descMap.value as String)
            break
        case 0x01: // saturation
            sendSaturationEvent(descMap.value as String)
            break
        case 0x03: // current X
            if (settings.logEnable) {
                log.debug 'ignoring X color attribute'
            }
            break
        case 0x04: // current Y
            if (settings.logEnable) {
                log.debug 'ignoring Y color attribute'
            }
            break
        case 0x07: // ct
            sendColorTempEvent(descMap.value as String)
            break
        case 0x08: // color mode
            final String mode = descMap.value == '02' ? 'CT' : 'RGB'
            sendColorModeEvent(mode)
            break
        case 0x400B:
            state.ct = state.ct ?: [:]
            state.ct.high = Math.round(1000000 / hexStrToUnsignedInt(descMap.value) as Long)
            log.info "color temperature high set to ${state.ct.high}K"
            break
        case 0x400C:
            state.ct = state.ct ?: [:]
            state.ct.low = Math.round(1000000 / hexStrToUnsignedInt(descMap.value) as Long)
            log.info "color temperature low set to ${state.ct.low}K"
            break
        default:
            log.debug "zigbee received COLOR_CLUSTER: ${descMap}"
            break
    }
}

/**
 * Zigbee Global Command Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseGlobalCommands(final Map descMap) {
    switch (hexStrToUnsignedInt(descMap.command)) {
        case 0x00: // read attribute
            if (settings.logEnable) {
                log.trace "zigbee read attribute request ${clusterLookup(descMap.clusterInt)}: ${descMap.data}"
            }
            break
        case 0x01: // read attribute response
            if (settings.logEnable) {
                log.trace "zigbee read attribute response ${clusterLookup(descMap.clusterInt)}: ${descMap.data}"
            }
            final List<String> data = descMap.data as List<String>
            final String attribute = data[1] + data[0]
            final int statusCode = hexStrToUnsignedInt(data[2])
            final String status = ZigbeeStatusEnum[statusCode] ?: "0x${data}"
            if (settings.logEnable) {
                log.trace "zigbee read ${clusterLookup(descMap.clusterInt)} attribute 0x${attribute} response: ${status} ${data}"
            } else if (statusCode > 0x00) {
                log.warn "zigbee read ${clusterLookup(descMap.clusterInt)} attribute 0x${attribute} error: ${status}"
            }
            break
        case 0x02: // write attribute (with response)
            if (settings.logEnable) {
                log.trace "zigbee response write attribute request ${clusterLookup(descMap.clusterInt)}: ${descMap.data}"
            }
            break
        case 0x04: // write attribute response
            final String data = descMap.data in List ? (descMap.data as List)[0] : descMap.data
            final int statusCode = hexStrToUnsignedInt(data)
            final String status = ZigbeeStatusEnum[statusCode] ?: "0x${intToHexStr(statusCode)}"
            if (settings.logEnable) {
                log.trace "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute response: ${status}"
            } else if (statusCode > 0x00) {
                log.warn "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute error: ${status}"
            }
            break
        case 0x07: // configure reporting response
            if (settings.enableReporting != false) {
                state.reportingEnabled = true
                if (settings.logEnable) {
                    log.trace "zigbee attribute reporting enabled for ${clusterLookup(descMap.clusterInt)}"
                }
            }
            break
        case 0x0B: // default command response
            final List<String> data = descMap.data as List<String>
            final String commandId = data[0]
            final int statusCode = hexStrToUnsignedInt(data[1])
            final String status = ZigbeeStatusEnum[statusCode] ?: "0x${data[1]}"
            if (settings.logEnable) {
                log.trace "zigbee response status ${clusterLookup(descMap.clusterInt)} command 0x${commandId}: ${status}"
            } else if (statusCode > 0x00) {
                log.warn "zigbee response error (${clusterLookup(descMap.clusterInt)}, command: 0x${commandId}) ${status}"
            }
            break
        default:
            if (settings.logEnable) {
                log.debug "zigbee received unknown global command: ${descMap}"
            }
            break
    }
}

/**
 * Zigbee Groups Cluster Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseGroupsCluster(final Map descMap) {
    switch (descMap.command as Integer) {
        case 0x02: // Group membership response
            final List<String> data = descMap.data as List<String>
            final int capacity = hexStrToUnsignedInt(data[0])
            final int groupCount = hexStrToUnsignedInt(data[1])
            final Set<String> groups = []
            for (int i = 0; i < groupCount; i++) {
                int pos = (i * 2) + 2
                String group = data[pos + 1] + data[pos]
                groups.add(group)
            }
            state.groups = groups
            log.info "zigbee group memberships: ${groups} (capacity available: ${capacity})"
            break
        default:
            log.warn "zigbee received unknown GROUPS cluster: ${descMap}"
            break
    }
}

/**
 * Zigbee Hue Specific Cluster Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parsePrivateCluster(final Map descMap) {
    switch (descMap.attrInt as Integer) {
        case 0x01: // device type
            parsePrivateClusterDeviceType(descMap)
            break
        case HUE_PRIVATE_STATE_ID: // current state
            parsePrivateClusterState(descMap)
            break
        case 0x11: // available effects
            parsePrivateClusterEffects(descMap)
            break
        default:
            log.warn "zigbee received unknown PRIVATE_CLUSTER: ${descMap}"
            break
    }
}

/*
 * Known Device type values
 *  0x03 - ZLL
 *  0x05 - White Only ZB3
 *  0x07 - White and Color ZB3
 */

/**
 * Parse the private cluster device type attribute
 * @param descMap Zigbee message in parsed map format
 */
void parsePrivateClusterDeviceType(final Map descMap) {
    final BigInteger deviceTypeValue = new BigInteger(descMap.value as String, 16)
    final String deviceType = deviceTypeValue.toString(16).toUpperCase()
    log.info "detected light type: ${deviceType}"
    updateDataValue('type', deviceType)
}

/**
 * Parse the private cluster effects attribute
 * ENUM64 bitmap position corresponds to the effect number
 * @param descMap Zigbee message in parsed map format
 */
void parsePrivateClusterEffects(final Map descMap) {
    final BigInteger effectsMap = new BigInteger(descMap.value as String, 16)
    final Map<Integer, String> effects = HueEffectNames.findAll { final key, final value -> effectsMap.testBit(key) }
    log.info "supported light effects: ${effects.values()}"
    sendEvent(name: 'lightEffects', value: JsonOutput.toJson(effects.values()))
}

/**
 * Parse the private cluster state attribute
 * Attribute 0x0002 encodes the light state, where the first
 * two bytes indicate the mode, the next byte OnOff, the next byte
 * Current Level, and the following bytes the mode-specific state.
 * from https://github.com/dresden-elektronik/deconz-rest-plugin/issues/5891
 * @param descMap Zigbee message in parsed map format
 */
void parsePrivateClusterState(final Map descMap) {
    final String value = descMap.value
    final int mode = hexStrToUnsignedInt(zigbee.swapOctets(value[0..3]))
    final boolean isOn = hexStrToUnsignedInt(value[4..5]) == 1
    final int level = hexStrToUnsignedInt(value[6..7])
    if (settings.logEnable) {
        log.debug "zigbee received private cluster report (length ${value.size()}) [power: ${isOn}, level: ${level}, mode: 0x${intToHexStr(mode, 2)}]"
    }

    sendSwitchEvent(isOn)
    switch (mode) {
        case 0x0003: // Brightness mode
            sendLevelEvent(level)
            sendEffectNameEvent()
            break
        case 0x00A3: // Brightness with Effect
            sendLevelEvent(level)
            sendEffectNameEvent(value[8..9])
            break
        case 0x0007: // Color Temperature mode
        case 0x000F:
            sendColorTempEvent(value[8..11])
            sendColorModeEvent('CT')
            sendLevelEvent(level)
            sendEffectNameEvent()
            break
        case 0x000B: // XY mode
            sendColorModeEvent('RGB')
            sendLevelEvent(level)
            sendColorXyEvent(level, value[8..15])
            sendEffectNameEvent()
            break
        case 0x00AB: // XY mode with effect
            sendColorModeEvent('RGB')
            sendLevelEvent(level)
            sendColorXyEvent(level, value[8..15])
            sendEffectNameEvent(value[16..17])
            break
        default:
            log.warn "Unknown mode received: 0x${intToHexStr(mode)}"
            break
    }
}

/**
 * Parse the Level Control cluster attribute
 * @param descMap Zigbee message in parsed map format
 */
void parseLevelCluster(final Map descMap) {
    switch (descMap.attrInt as Integer) {
        case 0x00:
            sendLevelEvent(hexStrToUnsignedInt(descMap.value))
            break
        default:
            log.warn "zigbee received unknown LEVEL_CONTROL cluster: ${descMap}"
            break
    }
}

/**
 * Parse the On Off cluster attribute
 * @param descMap Zigbee message in parsed map format
 */
void parseOnOffCluster(final Map descMap) {
    switch (descMap.attrInt as Integer) {
        case 0x00:
            sendSwitchEvent(descMap.value == '01')
            break
        case POWER_RESTORE_ID:
            final Integer value = hexStrToUnsignedInt(descMap.value)
            final Map<Integer, String> options = PowerRestoreOpts.options as Map<Integer, String>
            log.info "power restore mode is '${options[value]}' (0x${descMap.value})"
            device.updateSetting('powerRestore', [value: value.toString(), type: 'enum'])
            break
        default:
            log.warn "zigbee received unknown ON_OFF_CLUSTER: ${descMap}"
            break
    }
}

/**
 * ZDO (Zigbee Data Object) Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseZdo(final Map descMap) {
    switch (descMap.clusterInt as Integer) {
        case 0x8005: // endpoint response
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (endpoint response) ${descMap.data}"
            }
            break
        case 0x8004: // simple descriptor response
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (simple descriptor response)"
            }
            break
        case 0x8034: // leave response
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (leave response)"
            }
            break
        case 0x8021: // bind response
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (bind response) ${descMap}"
            }
            break
        case 0x8022: // unbind request
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (unbind request)"
            }
            break
        case 0x0013: //"device announce"
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (device announce)"
            }
            break
    }
}
/*-------------------------- END OF ZIGBEE PARSING --------------------*/

/**
 * Read the color attributes from the Color Control cluster
 * @param delayMs delay in milliseconds between each attribute read
 * @return list of commands to be sent to the device
 */
private List<String> colorRefresh(final int delayMs = 2000) {
    return zigbee.readAttribute(zigbee.COLOR_CONTROL_CLUSTER, [0x00, 0x01, 0x07, 0x08], [:], delayMs)
}

/**
 * Lookup the cluster name from the cluster ID
 * @param cluster cluster ID
 * @return cluster name if known, otherwise "private cluster"
 */
private String clusterLookup(final Object cluster) {
    return zigbee.clusterLookup(cluster.toInteger()) ?: "private cluster 0x${intToHexStr(cluster.toInteger())}"
}

/**
 * Constrain a value to a range
 * @param value value to constrain
 * @param min minimum value (default 0)
 * @param max maximum value (default 100)
 * @param nullValue value to return if value is null (default 0)
 */
private static BigDecimal constrain(final BigDecimal value, final BigDecimal min = 0, final BigDecimal max = 100, final BigDecimal nullValue = 0) {
    if (min == null || max == null) {
        return value
    }
    return value != null ? max.min(value.max(min)) : nullValue
}

/**
 * Constrain a value to a range
 * @param value value to constrain
 * @param min minimum value (default 0)
 * @param max maximum value (default 100)
 * @param nullValue value to return if value is null (default 0)
 */
private static Integer constrain(final Object value, final Integer min = 0, final Integer max = 100, final Integer nullValue = 0) {
    if (min == null || max == null) {
        return value as Integer
    }
    return value != null ? Math.min(Math.max(value as Integer, min) as Integer, max) : nullValue
}

/**
 * Convert a color temperature in Kelvin to a mired value
 * @param kelvin color temperature in Kelvin
 * @return mired value
 */
private static Integer ctToMired(final int kelvin) {
    return (1000000 / kelvin).toInteger()
}

/**
 * Get color transition rate
 * @param transitionTime transition time in seconds (optional)
 * @return transition rate in 1/10ths of a second
 */
private Integer getColorTransitionRate(final Object transitionTime = null) {
    Integer rate = 0
    if (transitionTime != null) {
        rate = (transitionTime.toBigDecimal() * 10).toInteger()
    } else if (settings.colorTransitionTime != null) {
        rate = settings.colorTransitionTime.toInteger()
    }
    if (settings.logEnable) {
        log.debug "using color transition rate ${rate}"
    }
    return rate
}

/**
 * Get the level transition rate
 * @param level desired target level (0-100)
 * @param transitionTime transition time in seconds (optional)
 * @return transition rate in 1/10ths of a second
 */
private Integer getLevelTransitionRate(final Object level, final Object transitionTime = null) {
    int rate = 0
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer desiredLevel = level.toInteger()
    Integer currentLevel = (device.currentValue('level') as Integer) ?: 0
    if (!isOn) {
        currentLevel = 0
    }
    // Check if 'transitionTime' has a value
    if (transitionTime > 0) {
        // Calculate the rate by converting 'transitionTime' to BigDecimal, multiplying by 10, and converting to Integer
        rate = (transitionTime.toBigDecimal() * 10).toInteger()
    } else {
        // Check if the 'levelUpTransition' setting has a value and the current level is less than the desired level
        if ((settings.levelUpTransition as Integer) > 0 && currentLevel < desiredLevel) {
            // Set the rate to the value of the 'levelUpTransition' setting converted to Integer
            rate = settings.levelUpTransition.toInteger()
        }
        // Check if the 'levelDownTransition' setting has a value and the current level is greater than the desired level
        else if ((settings.levelDownTransition as Integer) > 0 && currentLevel > desiredLevel) {
            // Set the rate to the value of the 'levelDownTransition' setting converted to Integer
            rate = settings.levelDownTransition.toInteger()
        }
    }

    if (settings.logEnable) {
        log.debug "using level transition rate ${rate}"
    }
    return rate
}

/**
 * Refresh the Philips Hue private cluster state attribute
 * @param delayMs delay in milliseconds between each attribute read
 * @return list of commands to be sent to the device
 */
private List<String> hueStateRefresh(final int delayMs = 2000) {
    return zigbee.readAttribute(PHILIPS_PRIVATE_CLUSTER, HUE_PRIVATE_STATE_ID, [mfgCode: PHILIPS_VENDOR], delayMs)
}

/**
 * If the device is polling, delay the execution of the provided commands
 * @param delayMs delay in milliseconds
 * @param commands commands to execute
 * @return list of commands to be sent to the device
 */
private List<String> ifPolling(final int delayMs = 0, final Closure commands) {
    if (state.reportingEnabled == false) {
        final int value = Math.max(delayMs, POLL_DELAY_MS)
        return ["delay ${value}"] + (commands() as List<String>) as List<String>
    }
    return []
}

/**
 * Mired to Kelvin conversion
 * @param mired mired value in hex
 * @return color temperature in Kelvin
 */
private int miredHexToCt(final String mired) {
    return (1000000 / hexStrToUnsignedInt(zigbee.swapOctets(mired))) as int
}

/**
 * Schedule a command timeout check
 * @param delay delay in seconds (default COMMAND_TIMEOUT)
 */
private void scheduleCommandTimeoutCheck(final int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

/**
 * Schedule a device health check
 * @param intervalMins interval in minutes
 */
private void scheduleDeviceHealthCheck(final int intervalMins) {
    final Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

/**
 * Send 'hue' attribute event
 * @param rawValue raw hue attribute value
 */
private void sendHueEvent(final String rawValue) {
    final long value = hexStrToUnsignedInt(rawValue)
    final int hue = Math.round(value / 2.54).intValue()
    final String descriptionText = "hue was set to ${hue}"
    if (device.currentValue('hue') as Integer != hue && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'hue', value: hue, descriptionText: descriptionText)
}

/**
 * Send 'saturation' attribute event
 * @param rawValue raw saturation attribute value
 */
private void sendSaturationEvent(final String rawValue) {
    final long value = hexStrToUnsignedInt(rawValue)
    final int saturation = Math.round(value / 2.54).intValue()
    final String descriptionText = "saturation was set to ${saturation}"
    if (device.currentValue('saturation') as Integer != saturation && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'saturation', value: saturation, descriptionText: descriptionText)
}

/**
 * Send 'colorMode' attribute event
 * @param rawValue raw color mode attribute value
 */
private void sendColorModeEvent(final String mode) {
    final String descriptionText = "color mode was set to ${mode}"
    if (device.currentValue('colorMode') != mode && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'colorMode', value: mode, descriptionText: descriptionText)
    if (mode == 'CT') {
        sendColorTempNameEvent(device.currentValue('colorTemperature') as Integer)
    } else {
        sendColorNameEvent(device.currentValue('hue') as Integer, device.currentValue('saturation') as Integer)
    }
}

/**
 * Send 'colorName' attribute event
 * @param hue hue value
 * @param saturation saturation value
 */
private void sendColorNameEvent(final Integer hue, final Integer saturation) {
    final String colorName = convertHueToGenericColorName(hue, saturation)
    if (!colorName) {
        return
    }
    descriptionText = "color name was set to ${colorName}"
    if (device.currentValue('colorName') != colorName && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent name: 'colorName', value: colorName, descriptionText: descriptionText
}

/**
 * Send 'colorXy' attribute event
 * @param level brightness level (0-254)
 * @param rawValue raw color xy attribute value
 */
private void sendColorXyEvent(final int level, final String rawValue) {
    final BigDecimal colorX = hexStrToUnsignedInt(zigbee.swapOctets(rawValue[0..3])) / 0xFFFF
    final BigDecimal colorY = hexStrToUnsignedInt(zigbee.swapOctets(rawValue[4..7])) / 0xFFFF
    log.debug "colorX: ${colorX}, colorY: ${colorY}"
    final List<Integer> rgb = xyBrightnessToRgb(colorX, colorY, level)
    log.debug "rgb: ${rgb}"
    final List<Integer> hsv = ColorUtils.rgbToHSV(rgb)
    log.debug "hsv: ${hsv}"
    //final int hue = Math.round(hsv[0] as BigDecimal).intValue()
    //final int saturation = Math.round(hsv[1] as BigDecimal).intValue()
    // String descriptionText = "hue was set to ${hue}"
    // if (device.currentValue('hue') as Integer != hue && settings.txtEnable) {
    //     log.info descriptionText
    // }
    // sendEvent(name: 'hue', value: hue, descriptionText: descriptionText)
    //
    // descriptionText = "saturation was set to ${saturation}"
    // if (device.currentValue('saturation') as Integer != saturation && settings.txtEnable) {
    //     log.info descriptionText
    // }
    // sendEvent(name: 'saturation', value: saturation, descriptionText: descriptionText)
}

/**
 * Send 'colorTemperature' attribute event
 * @param rawValue raw color temperature attribute value
 */
private void sendColorTempEvent(final String rawValue) {
    final Integer value = miredHexToCt(rawValue)
    if (state.ct.high && value > state.ct.high) {
        return
    }
    if (state.ct.low && value < state.ct.low) {
        return
    }
    final String descriptionText = "color temperature was set to ${value}°K"
    if (device.currentValue('colorTemperature') as Integer != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'colorTemperature', value: value, descriptionText: descriptionText, unit: '°K')
}

/**
 * Send 'colorTempName' attribute event
 * @param kelvin color temperature in Kelvin
 */
private void sendColorTempNameEvent(final Integer kelvin) {
    final String genericName = convertTemperatureToGenericColorName(kelvin)
    if (!genericName) {
        return
    }
    final String descriptionText = "color is ${genericName}"
    if (device.currentValue('colorName') != genericName && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'colorName', value: genericName, descriptionText: descriptionText)
}

/**
 * Send 'effectName' attribute event
 * @param rawValue raw effect attribute value (optional, if not provided, 'none' will be used)
 */
private void sendEffectNameEvent(final String rawValue = null) {
    String effectName = 'none'
    if (rawValue != null) {
        final int effect = hexStrToUnsignedInt(rawValue)
        effectName = HueEffectNames[effect] ?: 'unknown'
    }
    final String descriptionText = "effect was set to ${effectName}"
    if (device.currentValue('effectName') != effectName && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'effectName', value: effectName, descriptionText: descriptionText)
}

/**
 * Send 'healthStatus' attribute event
 * @param status health status
 */
private void sendHealthStatusEvent(final String status) {
    if (device.currentValue('healthStatus') != status) {
        final String descriptionText = "healthStatus was set to ${status}"
        sendEvent(name: 'healthStatus', value: status, descriptionText: descriptionText)
        if (settings.txtEnable) {
            log.info descriptionText
        }
    }
}

/**
 * Send 'level' attribute event
 * @param rawValue raw level attribute value
 */
private void sendLevelEvent(final Object rawValue) {
    final BigDecimal value = rawValue as BigDecimal
    final int level = Math.round(value / 2.54).intValue()
    final String descriptionText = "level was set to ${level}%"
    if (device.currentValue('level') as Integer != level && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'level', value: level, descriptionText: descriptionText, unit: '%')
}

/**
 * Send 'switch' attribute event
 * @param isOn true if light is on, false otherwise
 */
private void sendSwitchEvent(final Boolean isOn) {
    final String value = isOn ? 'on' : 'off'
    final String descriptionText = "light was turned ${value}"
    if (device.currentValue('switch') != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'switch', value: value, descriptionText: descriptionText)
}

/**
 * Send 'switchLevel' attribute event
 * @param isOn true if light is on, false otherwise
 * @param level brightness level (0-254)
 */
private List<String> setLevelPrivate(final Object value, final Integer rate = 0, final Integer delay = 0, final Boolean levelPreset = false) {
    List<String> cmds = []
    final Integer level = constrain(value)
    final String hexLevel = DataType.pack(Math.round(level * 2.54).intValue(), DataType.UINT8)
    final String hexRate = DataType.pack(rate, DataType.UINT16, true)
    final int levelCommand = levelPreset ? 0x00 : 0x04
    if (device.currentValue('switch') == 'off' && level > 0 && levelPreset == false) {
        // If light is off, first go to level 0 then to desired level
        cmds += zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, 0x00, [:], delay, "00 0000 ${PRE_STAGING_OPTION}")
    }
    // Payload: Level | Transition Time | Options Mask | Options Override
    // Options: Bit 0x01 enables pre-staging level
    cmds += zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, levelCommand, [:], delay, "${hexLevel} ${hexRate} ${PRE_STAGING_OPTION}") +
        ifPolling(DELAY_MS + (rate * 100)) { zigbee.levelRefresh(0) }
    return cmds
}

/**
 * This method takes in a set of X, Y, and brightness values and converts them to an RGB
 * color value. The X and Y values represent a point in the CIE 1931 color space, while the
 * brightness value determines the overall brightness of the resulting color.
 * https://developers.meethue.com/develop/application-design-guidance/color-conversion-formulas-rgb-to-xy-and-back/
 *
 * @param colorX a BigDecimal representing the X value in the CIE 1931 color space
 * @param colorY a BigDecimal representing the Y value in the CIE 1931 color space
 * @param brightness an integer representing the overall brightness of the resulting color (0-254)
 * @return an integer list containing the resulting RGB color values (0-255)
 */
@CompileStatic
private static List<Integer> xyBrightnessToRgb(final BigDecimal colorX, final BigDecimal colorY, final int brightness) {
    if (colorX == null || colorY == null) {
        return [0, 0, 0]
    }
    // Calculate XYZ values
    final BigDecimal z = 1.0 - colorX - colorY
    final BigDecimal valueY = brightness / 254f
    final BigDecimal valueX = (valueY / colorY) * colorX
    final BigDecimal valueZ = (valueY / colorY) * z
    // Convert to RGB using Wide RGB D65 conversion
    BigDecimal red = valueX * 1.656492f - valueY * 0.354851f - valueZ * 0.255038f
    BigDecimal green = -valueX * 0.707196f + valueY * 1.655397f + valueZ * 0.036152f
    BigDecimal blue = valueX * 0.051713f - valueY * 0.121364f + valueZ * 1.011530f
    // Apply reverse gamma correction
    red = red <= 0.0031308f ? 12.92f * red : (1.0f + 0.055f) * Math.pow(red as double, (1.0f / 2.4f)) - 0.055f
    green = green <= 0.0031308f ? 12.92f * green : (1.0f + 0.055f) * Math.pow(green as double, (1.0f / 2.4f)) - 0.055f
    blue = blue <= 0.0031308f ? 12.92f * blue : (1.0f + 0.055f) * Math.pow(blue as double, (1.0f / 2.4f)) - 0.055f
    // Constrain RGB values
    final BigDecimal max = red.max(green).max(blue)
    if (max > 1.0) {
        red /= max
        green /= max
        blue /= max
    }
    if (red < 0) {
        red = 0
    }
    if (green < 0) {
        green = 0
    }
    if (blue < 0) {
        blue = 0
    }
    return [(int) (red * 255), (int) (green * 255), (int) (blue * 255)]
}

// Configuration

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay in between zigbee commands
@Field static final int DELAY_MS = 200

// Hue hub uses five minute attribute reporting
@Field static final int REPORTING_MAX = 600

// Delay before reading attribute (when using polling)
@Field static final int POLL_DELAY_MS = 1000

// Command option that enable changes when off
@Field static final String PRE_STAGING_OPTION = '01 01'

// Philips Hue private cluster vendor code
@Field static final int PHILIPS_VENDOR = 0x100B

// Philips Hue private cluster
@Field static final int PHILIPS_PRIVATE_CLUSTER = 0xFC03

// Zigbee Attribute IDs
@Field static final int FIRMWARE_VERSION_ID = 0x4000
@Field static final int HUE_PRIVATE_STATE_ID = 0x02
@Field static final int PING_ATTR_ID = 0x01
@Field static final int POWER_RESTORE_ID = 0x4003

@Field static final Map<Integer, String> HueEffectNames = [
    0x01: 'candle',
    0x02: 'fireplace',
    0x03: 'color loop',
    0x09: 'sunrise',
    0x0a: 'sparkle'
]

@Field static final Map<Integer, String> IdentifyEffectNames = [
    0x00: 'Blink',
    0x02: 'Green (1s)',
    0x0b: 'Orange (8s)',
    0x01: 'Pulse (15s)'
]

@Field static final Map OffModeOpts = [
    defaultValue: 0x00,
    options     : [0x00: 'Fade Off (800ms)', 0x01: 'Instant Off', 0xFF: 'Dim to Zero']
]

@Field static final Map PowerRestoreOpts = [
    defaultValue: 0xFF,
    options     : [0x00: 'Off', 0x01: 'On', 0xFF: 'Last State']
]

@Field static final Map TransitionOpts = [
    defaultValue: 0x0004,
    options     : [
        0x0000: 'No Delay',
        0x0002: '200ms',
        0x0004: '400ms',
        0x000A: '1s',
        0x000F: '1.5s',
        0x0014: '2s',
        0x001E: '3s',
        0x0028: '4s',
        0x0032: '5s',
        0x0064: '10s'
    ]
]

@Field static final Map HealthcheckIntervalOpts = [
    defaultValue: 10,
    options     : [10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', '59': 'Every Hour', '00': 'Disabled']
]

@Field static final Map LevelRateOpts = [
    defaultValue: 0x64,
    options     : [0xFF: 'Device Default', 0x16: 'Very Slow', 0x32: 'Slow', 0x64: 'Medium', 0x96: 'Medium Fast', 0xC8: 'Fast']
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

@Field static final Map<String, Object> HueColorScenes = [
    'Savanna Sunset'   : [
        'brightness': 200,
        'hue'       : 14.717,
        'saturation': 83.137
    ],
    'Tropical Twilight': [
        'brightness': 123,
        'hue'       : 263.182,
        'saturation': 34.51
    ],
    'Arctic Aurora'    : [
        'brightness': 138,
        'hue'       : 201.308,
        'saturation': 83.922
    ],
    'Spring Blossom'   : [
        'brightness': 215,
        'hue'       : 339.718,
        'saturation': 27.843
    ],
    'Relax'            : [
        'brightness': 145,
        'hue'       : 36.568,
        'saturation': 66.275
    ],
    'Read'             : [
        'brightness': 255,
        'hue'       : 38.88,
        'saturation': 49.02
    ],
    'Concentrate'      : [
        'brightness': 255,
        'hue'       : 45,
        'saturation': 21.961
    ],
    'Energize'         : [
        'brightness': 255,
        'hue'       : 173.333,
        'saturation': 3.529
    ],
    'Bright'           : [
        'brightness': 255,
        'hue'       : 38.667,
        'saturation': 52.941
    ],
    'Dimmed'           : [
        'brightness': 77,
        'hue'       : 38.222,
        'saturation': 52.941
    ],
    'Nightlight'       : [
        'brightness': 1,
        'hue'       : 33.767,
        'saturation': 84.314
    ]
]
