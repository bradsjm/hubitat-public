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
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: 'Philips Hue White',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/PhilipsHue/Philips-Hue-Zigbee-Bulb-White.groovy',
            namespace: 'philips-hue', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Bulb'
        capability 'Change Level'
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
        attribute 'healthStatus', 'enum', [ 'unknown', 'offline', 'online' ]

        command 'identify', [ [ name: 'Effect type*', type: 'ENUM', description: 'Effect Type', constraints: IdentifyEffectNames.values()*.toLowerCase() ] ]
        command 'stepLevelChange', [
            [ name: 'Direction*', type: 'ENUM', description: 'Direction for step change request', constraints: [ 'up', 'down' ] ],
            [ name: 'Step Size*', type: 'NUMBER', description: 'Level change step size (1-99)' ],
            [ name: 'Duration', type: 'NUMBER', description: 'Transition duration in seconds' ]
        ]
        command 'toggle'

        // Philips Hue White
        fingerprint model: 'LWA019', profileId: '0104', inClusters: '0000,0003,0004,0005,0006,0008,1000,FC03,FC01', outClusters: '0019'
    }

    preferences {
        input name: 'levelUpTransition', type: 'enum', title: '<b>Dim up transition length</b>', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true, description: \
             '<i>Changes the speed the light dims up. Increasing the value slows down the transition.</i>'
        input name: 'levelDownTransition', type: 'enum', title: '<b>Dim down transition length</b>', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true, description: \
             '<i>Changes the speed the light dims down. Increasing the value slows down the transition.</i>'

        input name: 'levelChangeRate', type: 'enum', title: '<b>Level change rate</b>', options: LevelRateOpts.options, defaultValue: LevelRateOpts.defaultValue, required: true, description: \
             '<i>Changes the speed that the light changes when using <b>start level change</b> until <b>stop level change</b> is sent.</i>'

        input name: 'offCommandMode', type: 'enum', title: '<b>Off command mode</b>', options: OffModeOpts.options, defaultValue: OffModeOpts.defaultValue, required: true, description: \
             '<i>Changes off command. <b>Fade out</b> (default), <b>Instant</b> or <b>Dim to zero</b> (On will dim back to previous level).</i>'

        input name: 'flashEffect', type: 'enum', title: '<b>Flash effect</b>', options: IdentifyEffectNames.values(), defaultValue: 'Blink', required: true, description: \
             '<i>Changes the effect used when the <b>flash</b> command is used.</i>'
        input name: 'powerRestore', type: 'enum', title: '<b>Power restore mode</b>', options: PowerRestoreOpts.options, defaultValue: PowerRestoreOpts.defaultValue, description: \
             '<i>Changes what happens when power to the bulb is restored.</i>'

        input name: 'groupbinding1', type: 'number', title: '<b>Group Bind # 1</b>', range: '-1..65527', description: \
             '<i>Enter the Zigbee Group ID or leave blank to UNBind.</i>'
        input name: 'groupbinding2', type: 'number', title: '<b>Group Bind # 2</b>', range: '1..65527', description: \
             '<i>Enter the Zigbee Group ID or leave blank to UNBind.</i>'
        input name: 'groupbinding3', type: 'number', title: '<b>Group Bind # 3</b>', range: '1..65527', description: \
             '<i>Enter the Zigbee Group ID or leave blank to UNBind.</i>'

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

@Field static final String VERSION = '1.08 (2023-04-14)'

/**
 * Send configuration parameters to the bulb
 * Invoked when device is first installed and when the user updates the configuration
 * @return List of zigbee commands
 */
List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'

    state.clear()
    state.reportingEnabled = false

    // Power Restore Behavior
    if (settings.powerRestore != null) {
        log.info "configure: setting power restore state to 0x${intToHexStr(settings.powerRestore as Integer)}"
        cmds += zigbee.writeAttribute(zigbee.ON_OFF_CLUSTER, POWER_RESTORE_ID, DataType.ENUM8, settings.powerRestore as Integer, [:], DELAY_MS)
    }

    // Add to specified groups (if group is null then remove from previous group if any)
    cmds += setGroupMembership()

    // Attempt to enable cluster reporting, if it fails we fall back to polling after commands
    if (settings.enableReporting != false) {
        log.info 'configure: attempting to enable state reporting'
        cmds += zigbee.configureReporting(PHILIPS_PRIVATE_CLUSTER, HUE_PRIVATE_STATE_ID, DataType.STRING_OCTET, 1, REPORTING_MAX, null, [ mfgCode: PHILIPS_VENDOR ], DELAY_MS)
    } else {
        cmds += zigbee.configureReporting(PHILIPS_PRIVATE_CLUSTER, HUE_PRIVATE_STATE_ID, DataType.STRING_OCTET, 0, 0xFFFF, null, [ mfgCode: PHILIPS_VENDOR ], DELAY_MS)
        cmds += [ "zdo unbind unicast ${device.deviceNetworkId} {${device.device.zigbeeId}} ${device.endpointId} ${PHILIPS_PRIVATE_CLUSTER} ${location.hub.zigbeeEui}", "delay ${DELAY_MS}" ]
    }

    // Unbind unused cluster reporting (on/off, level are reported via private cluster)
    cmds += zigbee.configureReporting(zigbee.ON_OFF_CLUSTER, 0x00, DataType.UINT8, 0, 0xFFFF, 1, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.LEVEL_CONTROL_CLUSTER, 0x00, DataType.UINT8, 0, 0xFFFF, 1, [:], DELAY_MS)

    if (settings.logEnable) {
        log.debug "zigbee configure cmds: ${cmds}"
    }

    runIn(5, 'refresh')
    return cmds
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
    sendEvent(name: 'effectName', value: 'none')
    sendEvent(name: 'level', value: 0, unit: '%')
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
    // if off command mode is set to 'previous level' then store the current level and turn off
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
    // if off command mode is set to 'previous level' then restore the previous level
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
 * This will not turn the device on if it is off.
 * @param value level percent (0-100)
 * @return List of zigbee commands
 */
List<String> presetLevel(final BigDecimal value) {
    if (settings.txtEnable) {
        log.info "presetLevel (${value})"
    }
    final Boolean isOn = device.currentValue('switch') == 'on'
    final Integer rate = isOn ? getLevelTransitionRate(value as Integer) : 0
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

    // Get device type and supported effects
    cmds += zigbee.readAttribute(PHILIPS_PRIVATE_CLUSTER, [0x01, 0x11], [mfgCode: PHILIPS_VENDOR], DELAY_MS)

    // Refresh other attributes
    cmds += hueStateRefresh(DELAY_MS)

    // Get group membership
    cmds += zigbee.command(zigbee.GROUPS_CLUSTER, 0x02, [:], DELAY_MS, '00')

    scheduleCommandTimeoutCheck()
    return cmds
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
 * Add or remove bulb from specified group configuration
 * @return List of zigbee commands
 */
List<String> setGroupMembership() {
    List<String> cmds = []
    for (final int i = 1; i <= 3; i++) {
        final String config = "groupbinding${i}"
        // Remove from previous group if necessary
        if (state[config] && state[config] as Integer != settings[config] as Integer) {
            final Integer group = state[config] as Integer
            log.info "configure: removing from group ${group}"
            final String groupHex = DataType.pack(group, DataType.UINT16, true)
            cmds += zigbee.command(zigbee.GROUPS_CLUSTER, 0x03, [:], DELAY_MS, groupHex)
            state.remove(config)
        }
        // Add to new group if specified
        if (settings[config]) {
            final Integer group = settings[config] as Integer
            if (group >= 1 && group <= 0xFFF7) {
                log.info "configure: adding to group ${group}"
                final String groupHex = DataType.pack(group, DataType.UINT16, true)
                cmds += zigbee.command(zigbee.GROUPS_CLUSTER, 0x00, [:], DELAY_MS, "${groupHex} 00")
                state[config] = group
            }
        }
    }
    return cmds
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
    final Integer rate = getLevelTransitionRate(value as Integer, transitionTime as Integer)
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
    final Integer rate = getLevelTransitionRate(direction == 'down' ? 0 : 100, transitionTime as Integer)
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
        parseZdoClusters(descMap)
        return
    }

    if (descMap.isClusterSpecific == false) {
        parseGeneralCommandResponse(descMap)
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
 * Zigbee General Command Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseGeneralCommandResponse(final Map descMap) {
    final int commandId = hexStrToUnsignedInt(descMap.command)
    switch (commandId) {
        case 0x01: // read attribute response
            parseReadAttributeResponse(descMap)
            break
        case 0x04: // write attribute response
            parseWriteAttributeResponse(descMap)
            break
        case 0x07: // configure reporting response
            final String status = ((List)descMap.data).first()
            final int statusCode = hexStrToUnsignedInt(status)
            if (statusCode == 0x00 && settings.enableReporting != false) {
                state.reportingEnabled = true
            }
            final String statusName = ZigbeeStatusEnum[statusCode] ?: "0x${status}"
            if (statusCode > 0x00) {
                log.warn "zigbee configure reporting error: ${statusName} ${descMap.data}"
            } else if (settings.logEnable) {
                log.trace "zigbee configure reporting response: ${statusName} ${descMap.data}"
            }
            break
        case 0x0B: // default command response
            parseDefaultCommandResponse(descMap)
            break
        default:
            final String commandName = ZigbeeGeneralCommandEnum[commandId] ?: "UNKNOWN_COMMAND (0x${descMap.command})"
            final String clusterName = clusterLookup(descMap.clusterInt)
            final String status = descMap.data in List ? ((List)descMap.data).last() : descMap.data
            final int statusCode = hexStrToUnsignedInt(status)
            final String statusName = ZigbeeStatusEnum[statusCode] ?: "0x${status}"
            if (statusCode > 0x00) {
                log.warn "zigbee ${commandName} ${clusterName} error: ${statusName}"
            } else if (settings.logEnable) {
                log.trace "zigbee ${commandName} ${clusterName}: ${descMap.data}"
            }
            break
    }
}

/**
 * Zigbee Default Command Response Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseDefaultCommandResponse(final Map descMap) {
    final List<String> data = descMap.data as List<String>
    final String commandId = data[0]
    final int statusCode = hexStrToUnsignedInt(data[1])
    final String status = ZigbeeStatusEnum[statusCode] ?: "0x${data[1]}"
    if (statusCode > 0x00) {
        log.warn "zigbee ${clusterLookup(descMap.clusterInt)} command 0x${commandId} error: ${status}"
    } else if (settings.logEnable) {
        log.trace "zigbee ${clusterLookup(descMap.clusterInt)} command 0x${commandId} response: ${status}"
    }
}

/**
 * Zigbee Read Attribute Response Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseReadAttributeResponse(final Map descMap) {
    final List<String> data = descMap.data as List<String>
    final String attribute = data[1] + data[0]
    final int statusCode = hexStrToUnsignedInt(data[2])
    final String status = ZigbeeStatusEnum[statusCode] ?: "0x${data}"
    if (settings.logEnable) {
        log.trace "zigbee read ${clusterLookup(descMap.clusterInt)} attribute 0x${attribute} response: ${status} ${data}"
    } else if (statusCode > 0x00) {
        log.warn "zigbee read ${clusterLookup(descMap.clusterInt)} attribute 0x${attribute} error: ${status}"
    }
}

/**
 * Zigbee Write Attribute Response Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseWriteAttributeResponse(final Map descMap) {
    final String data = descMap.data in List ? ((List)descMap.data).first() : descMap.data
    final int statusCode = hexStrToUnsignedInt(data)
    final String statusName = ZigbeeStatusEnum[statusCode] ?: "0x${data}"
    if (settings.logEnable) {
        log.trace "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute response: ${statusName}"
    } else if (statusCode > 0x00) {
        log.warn "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute error: ${statusName}"
    }
}

/**
 * Zigbee Groups Cluster Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseGroupsCluster(final Map descMap) {
    switch (descMap.command as Integer) {
        case 0x00: // Add group response
            final List<String> data = descMap.data as List<String>
            final int statusCode = hexStrToUnsignedInt(data[0])
            final String statusName = ZigbeeStatusEnum[statusCode] ?: "0x${data[0]}"
            final int groupId = hexStrToUnsignedInt(data[2] + data[1])
            if (settings.logEnable) {
                log.trace "zigbee response add group ${groupId}: ${statusName}"
            } else if (statusCode > 0x00) {
                log.warn "zigbee response add group ${groupId} error: ${statusName}"
            }
            break
        case 0x02: // Group membership response
            final List<String> data = descMap.data as List<String>
            final int capacity = hexStrToUnsignedInt(data[0])
            final int groupCount = hexStrToUnsignedInt(data[1])
            final Set<String> groups = []
            for (int i = 0; i < groupCount; i++) {
                int pos = (i * 2) + 2
                String group = hexStrToUnsignedInt(data[pos + 1] + data[pos])
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
 * ZDO (Zigbee Data Object) Clusters Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseZdoClusters(final Map descMap) {
    final Integer clusterId = descMap.clusterInt as Integer
    final String clusterName = ZdoClusterEnum[clusterId] ?: "UNKNOWN_CLUSTER (0x${descMap.clusterId})"
    final String statusHex = ((List)descMap.data)[1]
    final Integer statusCode = hexStrToUnsignedInt(statusHex)
    final String statusName = ZigbeeStatusEnum[statusCode] ?: "0x${statusHex}"
    if (statusCode > 0x00) {
        log.warn "zigbee received device object ${clusterName} error: ${statusName}"
    } else if (settings.logEnable) {
        log.trace "zigbee received device object ${clusterName} success: ${descMap.data}"
    }
}

/*-------------------------- END OF ZIGBEE PARSING --------------------*/

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
 * Lookup the cluster name from the cluster ID
 * @param cluster cluster ID
 * @return cluster name if known, otherwise "private cluster"
 */
private String clusterLookup(final Object cluster) {
    return zigbee.clusterLookup(cluster.toInteger()) ?: "private cluster 0x${intToHexStr(cluster.toInteger())}"
}

/**
 * Get the level transition rate
 * @param level desired target level (0-100)
 * @param transitionTime transition time in seconds (optional)
 * @return transition rate in 1/10ths of a second
 */
private Integer getLevelTransitionRate(final Integer desiredLevel, final Integer transitionTime = null) {
    int rate = 0
    final Boolean isOn = device.currentValue('switch') == 'on'
    Integer currentLevel = (device.currentValue('level') as Integer) ?: 0
    if (!isOn) {
        currentLevel = 0
    }
    // Check if 'transitionTime' has a value
    if (transitionTime > 0) {
        // Calculate the rate by converting 'transitionTime' to BigDecimal, multiplying by 10, and converting to Integer
        rate = transitionTime * 10
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

// Configuration

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay in-between zigbee commands
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
    0x01: 'candle'
]

@Field static final Map<Integer, String> IdentifyEffectNames = [
    0x00: 'Blink',
    0x01: 'Pulse (15s)'
]

@Field static final Map OffModeOpts = [
    defaultValue: 0x00,
    options: [ 0x00: 'Fade Off (800ms)', 0x01: 'Instant Off', 0xFF: 'Dim to Zero' ]
]

@Field static final Map PowerRestoreOpts = [
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
        0x001E: '3s',
        0x0028: '4s',
        0x0032: '5s',
        0x0064: '10s'
    ]
]

@Field static final Map HealthcheckIntervalOpts = [
    defaultValue: 10,
    options: [ 10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', '59': 'Every Hour', '00': 'Disabled' ]
]

@Field static final Map LevelRateOpts = [
    defaultValue: 0x64,
    options: [ 0xFF: 'Device Default', 0x16: 'Very Slow', 0x32: 'Slow', 0x64: 'Medium', 0x96: 'Medium Fast', 0xC8: 'Fast' ]
]

@Field static final Map<Integer, String> ZigbeeStatusEnum = [
    0x00: 'Success',
    0x01: 'Failure',
    0x02: 'Not Authorized',
    0x80: 'Malformed Command',
    0x81: 'Unsupported COMMAND',
    0x85: 'Invalid Field',
    0x86: 'Unsupported Attribute',
    0x87: 'Invalid Value',
    0x88: 'Read Only',
    0x89: 'Insufficient Space',
    0x8A: 'Duplicate Exists',
    0x8B: 'Not Found',
    0x8C: 'Unreportable Attribute',
    0x8D: 'Invalid Data Type',
    0x8E: 'Invalid Selector',
    0x94: 'Time out',
    0x9A: 'Notification Pending',
    0xC3: 'Unsupported Cluster'
]

@Field static final Map<Integer, String> ZdoClusterEnum = [
    0x0013: 'Device announce',
    0x8004: 'Simple Descriptor Response',
    0x8005: 'Active Endpoints Response',
    0x801D: 'Extended Simple Descriptor Response',
    0x801E: 'Extended Active Endpoint Response',
    0x8021: 'Bind Response',
    0x8022: 'Unbind Response',
    0x8023: 'Bind Register Response',
]

@Field static final Map<Integer, String> ZigbeeGeneralCommandEnum = [
    0x00: 'Read Attributes',
    0x01: 'Read Attributes Response',
    0x02: 'Write Attributes',
    0x03: 'Write Attributes Undivided',
    0x04: 'Write Attributes Response',
    0x05: 'Write Attributes No Response',
    0x06: 'Configure Reporting',
    0x07: 'Configure Reporting Response',
    0x08: 'Read Reporting Configuration',
    0x09: 'Read Reporting Configuration Response',
    0x0A: 'Report Attributes',
    0x0B: 'Default Response',
    0x0C: 'Discover Attributes',
    0x0D: 'Discover Attributes Response',
    0x0E: 'Read Attributes Structured',
    0x0F: 'Write Attributes Structured',
    0x10: 'Write Attributes Structured Response',
    0x11: 'Discover Commands Received',
    0x12: 'Discover Commands Received Response',
    0x13: 'Discover Commands Generated',
    0x14: 'Discover Commands Generated Response',
    0x15: 'Discover Attributes Extended',
    0x16: 'Discover Attributes Extended Response'
]
