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
        input name: 'levelUpTransition', type: 'enum', title: '<b>Dim up transition length</b>', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true, description:\
            '<i>Changes the speed the light dims up. Increasing the value slows down the transition.</i>'
        input name: 'levelDownTransition', type: 'enum', title: '<b>Dim down transition length</b>', options: TransitionOpts.options, defaultValue: TransitionOpts.defaultValue, required: true, description:\
            '<i>Changes the speed the light dims down. Increasing the value slows down the transition.</i>'

        input name: 'levelChangeRate', type: 'enum', title: '<b>Level change rate</b>', options: LevelRateOpts.options, defaultValue: LevelRateOpts.defaultValue, required: true, description:\
            '<i>Changes the speed that the light changes when using <b>start level change</b> until <b>stop level change</b> is sent.</i>'

        input name: 'enableDimOnOffMode', type: 'bool', title: '<b>Dim to zero instead of off</b>', defaultValue: false, description:\
            '<i>Changes the <b>Off</b> command to instead dim down to zero and <b>On</b> to dim up to the previous level.</i>'
        input name: 'enableReporting', type: 'bool', title: '<b>Enable state reporting</b>', defaultValue: true, description:\
            '<i>Enables the use of reporting to push updates instead of polling bulb. Only available from Generation 3 bulbs.</i>'

        input name: 'flashEffect', type: 'enum', title: '<b>Flash effect</b>', options: IdentifyEffectNames.values(), defaultValue: 'Blink', required: true, description:\
            '<i>Changes the effect used when the <b>flash</b> command is used.</i>'
        input name: 'powerRestore', type: 'enum', title: '<b>Power restore mode</b>', options: PowerRestoreOpts.options, defaultValue: PowerRestoreOpts.defaultValue, description:\
            '<i>Changes what happens when power to the bulb is restored.</i>'

        input name: 'healthCheckInterval', type: 'enum', title: '<b>Healthcheck Interval</b>', options: HealthcheckIntervalOpts.options, defaultValue: HealthcheckIntervalOpts.defaultValue, required: true, description:\
            '<i>Changes how often the hub pings the bulb to check health.</i>'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description:\
            '<i>Enables command logging.</i>'
        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description:\
            '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

@Field static final String VERSION = '1.03'

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

    cmds += [ "zdo unbind unicast ${device.deviceNetworkId} {${device.device.zigbeeId}} ${device.endpointId} ${zigbee.ON_OFF_CLUSTER} ${location.hub.zigbeeEui}", "delay ${DELAY_MS}" ]
    cmds += [ "zdo unbind unicast ${device.deviceNetworkId} {${device.device.zigbeeId}} ${device.endpointId} ${zigbee.LEVEL_CONTROL_CLUSTER} ${location.hub.zigbeeEui}", "delay ${DELAY_MS}" ]

    if (settings.logEnable) { log.debug "zigbee configure cmds: ${cmds}" }

    runIn(2, 'refresh')
    return cmds
}

void deviceCommandTimeout() {
    log.warn 'no response received (device offline?)'
    sendHealthStatusEvent('offline')
}

List<String> flash(Object rate = null) {
    if (settings.txtEnable) { log.info "flash (${rate})" }
    return identify(settings.flashEffect)
}

List<String> identify(String name) {
    Integer effect = IdentifyEffectNames.find { k, v -> v.equalsIgnoreCase(name) }?.key
    if (effect == null) { return [] }
    if (settings.txtEnable) { log.info "identify (${name})" }
    return zigbee.command(zigbee.IDENTIFY_CLUSTER, 0x40, [:], 0, "${intToHexStr(effect)} 00")
}

void installed() {
    log.info 'installed'
    // populate some default values for attributes
    sendEvent(name: 'effectName', value: 'none')
    sendEvent(name: 'level', value: 0, unit: '%')
    sendEvent(name: 'switch', value: 'off')
    sendEvent(name: 'healthStatus', value: 'unknown')
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

List<String> off() {
    if (settings.enableDimOnOffMode == true) {
        state.previousLevel = device.currentValue('level') as Integer
        return setLevel(0)
    }
    if (settings.txtEnable) { log.info 'turn off' }
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x40, [:], 0, '00 00') +
        ifPolling { zigbee.onOffRefresh(0) }
}

List<String> on() {
    if (state.previousLevel && settings.enableDimOnOffMode == true) {
        Integer level = state.previousLevel as Integer
        state.remove('previousLevel')
        return setLevel(level)
    }
    if (settings.txtEnable) { log.info 'turn on' }
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x01, [:], 0) +
        ifPolling { zigbee.onOffRefresh(0) }
}

List<String> ping() {
    if (settings.txtEnable) { log.info 'ping...' }
    // Using attribute 0x00 as a simple ping/pong mechanism
    scheduleCommandTimeoutCheck()
    return zigbee.readAttribute(zigbee.BASIC_CLUSTER, PING_ATTR_ID, [:], 0)
}

List<String> presetLevel(Object value) {
    if (settings.txtEnable) { log.info "presetLevel (${value})" }
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer rate = isOn ? getLevelTransitionRate(value) : 0
    scheduleCommandTimeoutCheck()
    return setLevelPrivate(value, rate, 0, true)
}

List<String> refresh() {
    log.info 'refresh'
    List<String> cmds = []

    // Get Firmware Version
    cmds += zigbee.readAttribute(zigbee.BASIC_CLUSTER, FIRMWARE_VERSION_ID, [:], DELAY_MS)

    // Get Power Restore state
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, POWER_RESTORE_ID, [:], DELAY_MS)

    // Get device type and supported effects
    cmds += zigbee.readAttribute(PHILIPS_PRIVATE_CLUSTER, [ 0x01, 0x11 ], [ mfgCode: PHILIPS_VENDOR ], DELAY_MS)

    // Refresh other attributes
    cmds += hueStateRefresh(DELAY_MS)

    // Get group membership
    cmds += zigbee.command(zigbee.GROUPS_CLUSTER, 0x02, [:], DELAY_MS, '00')

    scheduleCommandTimeoutCheck()
    return cmds
}

List<String> setEffect(Object number) {
    List<String> effectNames = parseJson(device.currentValue('lightEffects') ?: '[]')
    Integer effectNumber = constrain(number, 0, effectNames.size())
    if (settings.txtEnable) { log.info "setEffect (${number})" }
    if (effectNumber == 0) {
        state.remove('effect')
        return zigbee.command(PHILIPS_PRIVATE_CLUSTER, 0x00, [ mfgCode: PHILIPS_VENDOR ], 0, '2000 00')
    }
    String effectName = effectNames[effectNumber - 1]
    state.effect = number
    int effect = HueEffectNames.find { k, v -> v == effectName }?.key
    scheduleCommandTimeoutCheck()
    return zigbee.command(PHILIPS_PRIVATE_CLUSTER, 0x00, [ mfgCode: PHILIPS_VENDOR ], 0, "2100 01 ${intToHexStr(effect)}") +
        ifPolling { hueStateRefresh(0) }
}

List<String> setLevel(Object value, Object transitionTime = null) {
    if (settings.txtEnable) { log.info "setLevel (${value}, ${transitionTime})" }
    Integer rate = getLevelTransitionRate(value, transitionTime)
    scheduleCommandTimeoutCheck()
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

List<String> startLevelChange(String direction) {
    if (settings.txtEnable) { log.info "startLevelChange (${direction})" }
    String upDown = direction == 'down' ? '01' : '00'
    String rate = getLevelTransitionRate(direction == 'down' ? 0 : 100)
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, 0x05, [:], 0, "${upDown} ${rate}")
}

List<String> stepLevelChange(String direction, Object stepSize, Object transitionTime = null) {
    if (settings.txtEnable) { log.info "stepLevelChange (${direction}, ${stepSize}, ${transitionTime})" }
    Integer rate = getLevelTransitionRate(direction == 'down' ? 0 : 100, transitionTime)
    String rateHex = intToSwapHexStr(rate)
    Integer level = constrain(stepSize, 1, 99)
    String stepHex = intToHexStr((level * 2.55).toInteger())
    String upDown = direction == 'down' ? '01' : '00'
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, 0x06, [:], 0, "${upDown} ${stepHex} ${rateHex}") +
        ifPolling { zigbee.levelRefresh(0) + zigbee.onOffRefresh(0) }
}

List<String> stopLevelChange() {
    if (settings.txtEnable) { log.info 'stopLevelChange' }
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, 0x03, [:], 0) +
        ifPolling { zigbee.levelRefresh(0) + zigbee.onOffRefresh(0) }
}

List<String> toggle() {
    if (settings.txtEnable) { log.info 'toggle' }
    scheduleCommandTimeoutCheck()
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x02, [:], 0) +
        ifPolling { zigbee.onOffRefresh(0) }
}

void updated() {
    log.info 'updated...'
    log.info "driver version ${VERSION}"
    unschedule()

    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }

    int interval = (settings.healthCheckInterval as Integer) ?: 0
    if (interval > 0) {
        log.info "scheduling health check every ${interval} minutes"
        scheduleDeviceHealthCheck(interval)
    }

    runIn(1, 'configure')
}

/*
 * ------------------------ ZIGBEE MESSAGE PARSING --------------------
 */
void parse(String description) {
    Map descMap = zigbee.parseDescriptionAsMap(description)
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
        String clusterName = clusterLookup(descMap.clusterInt) ?: "PRIVATE_CLUSTER (0x${descMap.cluster})"
        String attribute = descMap.attrId ? " attribute 0x${descMap.attrId} (value 0x${descMap.value})" : ''
        log.trace "zigbee received ${clusterName} message" + attribute
    }

    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parseBasicCluster(m) }
            break
        case zigbee.GROUPS_CLUSTER:
            parseGroupsCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parseGroupsCluster(m) }
            break
        case PHILIPS_PRIVATE_CLUSTER:
            parsePrivateCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parsePrivateCluster(m) }
            break
        case zigbee.LEVEL_CONTROL_CLUSTER:
            parseLevelCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parseLevelCluster(m) }
            break
        case zigbee.ON_OFF_CLUSTER:
            parseOnOffCluster(descMap)
            descMap.remove('additionalAttrs')?.each { m -> parseOnOffCluster(m) }
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
        case PING_ATTR_ID: // Using 0x01 read as a simple ping/pong mechanism
            if (settings.txtEnable) { log.info 'pong..' }
            break
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
            log.warn "zigbee received unknown BASIC_CLUSTER: ${descMap}"
            break
    }
}

/*
 * Zigbee Global Command Parsing
 */
void parseGlobalCommands(Map descMap) {
    switch (hexStrToUnsignedInt(descMap.command)) {
        case 0x00: // read attribute
            if (settings.logEnable) { log.trace "zigbee read attribute request ${clusterLookup(descMap.clusterInt)}: ${descMap.data}" }
            break
        case 0x01: // read attribute response
            if (settings.logEnable) { log.trace "zigbee read attribute response ${clusterLookup(descMap.clusterInt)}: ${descMap.data}" }
            String attribute = descMap.data[1] + descMap.data[0]
            int statusCode = hexStrToUnsignedInt(descMap.data[2])
            String status = ZigbeeStatusEnum[statusCode] ?: "0x${descMap.data}"
            if (settings.logEnable) {
                log.trace "zigbee read ${clusterLookup(descMap.clusterInt)} attribute 0x${attribute} response: ${status} ${descMap.data}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee read ${clusterLookup(descMap.clusterInt)} attribute 0x${attribute} error: ${status}"
            }
            break
        case 0x02: // write attribute (with response)
            if (settings.logEnable) { log.trace "zigbee response write attribute request ${clusterLookup(descMap.clusterInt)}: ${descMap.data}" }
            break
        case 0x04: // write attribute response
            int statusCode = hexStrToUnsignedInt(descMap.data in List ? descMap.data[0] : descMap.data)
            String status = ZigbeeStatusEnum[statusCode] ?: "0x${intToHexStr(statusCode)}"
            if (settings.logEnable) {
                log.trace "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute response: ${status}"
            } else if (statusCode != 0x00) {
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
            String commandId = descMap.data[0]
            int statusCode = hexStrToUnsignedInt(descMap.data[1])
            String status = ZigbeeStatusEnum[statusCode] ?: "0x${descMap.data[1]}"
            if (settings.logEnable) {
                log.trace "zigbee response status ${clusterLookup(descMap.clusterInt)} command 0x${commandId}: ${status}"
            } else if (statusCode != 0x00) {
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
            log.warn "zigbee received unknown GROUPS cluster: ${descMap}"
            break
    }
}

/*
 * Zigbee Hue Specific Cluster Parsing
 */
void parsePrivateCluster(Map descMap) {
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
void parsePrivateClusterDeviceType(Map descMap) {
    BigInteger deviceTypeValue = new BigInteger(descMap.value, 16)
    String deviceType = deviceTypeValue.toString(16).toUpperCase()
    log.info "detected light type: ${deviceType}"
    updateDataValue('type', deviceType)
}

/*
 * ENUM64 bitmap position corresponds to the effect number
 */
void parsePrivateClusterEffects(Map descMap) {
    BigInteger effectsMap = new BigInteger(descMap.value, 16)
    Map<Integer, String> effects = HueEffectNames.findAll { k, v -> effectsMap.testBit(k) }
    log.info "supported light effects: ${effects.values()}"
    sendEvent(name: 'lightEffects', value: JsonOutput.toJson(effects.values()))
}

/*
 * Attribute 0x0002 encodes the light state, where the first
 * two bytes indicate the mode, the next byte OnOff, the next byte
 * Current Level, and the following bytes the mode-specific state.
 * from https://github.com/dresden-elektronik/deconz-rest-plugin/issues/5891
 */
void parsePrivateClusterState(Map descMap) {
    int mode = hexStrToUnsignedInt(zigbee.swapOctets(descMap.value[0..3]))
    boolean isOn = hexStrToUnsignedInt(descMap.value[4..5]) == 1
    int level = hexStrToUnsignedInt(descMap.value[6..7])
    if (settings.logEnable) {
        log.debug "zigbee received private cluster report (length ${descMap.value.size()}) [power: ${isOn}, level: ${level}, mode: 0x${intToHexStr(mode, 2)}]"
    }

    sendSwitchEvent(isOn)
    switch (mode) {
        case 0x0003: // Brightness mode
            sendLevelEvent(level)
            sendEffectNameEvent()
            break
        case 0x00A3: // Brightness with Effect
            sendLevelEvent(level)
            sendEffectNameEvent(descMap.value[8..9])
            break
        default:
            log.warn "Unknown mode received: 0x${intToHexStr(mode)}"
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
            log.warn "zigbee received unknown LEVEL_CONTROL cluster: ${descMap}"
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
        case POWER_RESTORE_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "power restore mode is '${PowerRestoreOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('powerRestore', [value: value.toString(), type: 'enum' ])
            break
        default:
            log.warn "zigbee received unknown ON_OFF_CLUSTER: ${descMap}"
            break
    }
}

/*
 * ZDO Parsing
 */
void parseZdo(Map descMap) {
    switch (descMap.clusterInt as Integer) {
        case 0x8005: //endpoint response
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (endpoint response) ${descMap.data}"
            }
            break
        case 0x8004: //simple descriptor response
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (simple descriptor response)"
            }
            break
        case 0x8034: //leave response
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (leave response)"
            }
            break
        case 0x8021: //bind response
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (bind response) ${descMap}"
            }
            break
        case 0x8022: //unbind request
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

/*
 * Utility Methods
 */
private String clusterLookup(Object cluster) {
    return zigbee.clusterLookup(cluster.toInteger()) ?: "private cluster 0x${intToHexStr(cluster.toInteger())}"
}

private Integer constrain(Object value, Integer min = 0, Integer max = 100, Integer nullValue = 0) {
    if (min == null || max == null) { return value }
    return value != null ? Math.min(Math.max(value.toInteger(), min), max) : nullValue
}

private Integer getLevelTransitionRate(Object level, Object transitionTime = null) {
    Boolean isOn = device.currentValue('switch') == 'on'
    Integer rate = 0
    Integer desiredLevel = level.toInteger()
    Integer currentLevel = isOn ? ((device.currentValue('level') as Integer) ?: 0) : 0
    if (transitionTime != null) {
        rate = (transitionTime.toBigDecimal() * 10).toInteger()
    } else if (settings.levelUpTransition != null && currentLevel < desiredLevel) {
        rate = settings.levelUpTransition.toInteger()
    } else if (settings.levelDownTransition != null && currentLevel > desiredLevel) {
        rate = settings.levelDownTransition.toInteger()
    }
    if (settings.logEnable) { log.debug "using level transition rate ${rate}" }
    return rate
}

private List<String> hueStateRefresh(int delayMs = 2000) {
    return zigbee.readAttribute(PHILIPS_PRIVATE_CLUSTER, HUE_PRIVATE_STATE_ID, [ mfgCode: PHILIPS_VENDOR ], delayMs)
}

private List<String> ifPolling(int delayMs = 0, Closure commands) {
    if (state.reportingEnabled == false) {
        int value = Math.max(delayMs, POLL_DELAY_MS)
        return [ "delay ${value}" ] + (commands() as List<String>)
    }
    return []
}

private String intToSwapHexStr(Integer i, String nullValue = '0000') {
    return i != null ? zigbee.swapOctets(intToHexStr(i, 2)) : nullValue
}

private void scheduleCommandTimeoutCheck(int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

private void scheduleDeviceHealthCheck(int intervalMins) {
    Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

private void sendEffectNameEvent(String rawValue = null) {
    String effectName = 'none'
    if (rawValue != null) {
        int effect = hexStrToUnsignedInt(rawValue)
        effectName = HueEffectNames[effect] ?: 'unknown'
    }
    String descriptionText = "effect was set to ${effectName}"
    if (device.currentValue('effectName') != effectName && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'effectName', value: effectName, descriptionText: descriptionText)
}

private void sendHealthStatusEvent(String status) {
    if (device.currentValue('healthStatus') != status) {
        String descriptionText = "healthStatus was set to ${status}"
        sendEvent(name: 'healthStatus', value: status, descriptionText: descriptionText)
        if (settings.txtEnable) { log.info descriptionText }
    }
}

private void sendLevelEvent(Object rawValue) {
    Integer value = Math.round(rawValue.toInteger() / 2.55)
    String descriptionText = "level was set to ${value}%"
    if (device.currentValue('level') as Integer != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'level', value: value, descriptionText: descriptionText, unit: '%')
}

private void sendSwitchEvent(Boolean isOn) {
    String value = isOn ? 'on' : 'off'
    String descriptionText = "light was turned ${value}"
    if (device.currentValue('switch') != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: 'switch', value: value, descriptionText: descriptionText)
}

private List<String> setLevelPrivate(Object value, Integer rate = 0, Integer delay = 0, Boolean levelPreset = false) {
    List<String> cmds = []
    Integer level = constrain(value)
    if (level > 0 && levelPreset == false) { sendSwitchEvent(true) } // assume success
    String hexLevel = intToHexStr(Math.round(level * 0xfe / 100.0).toInteger())
    String hexRate = intToSwapHexStr(rate)
    int levelCommand = levelPreset ? 0x00 : 0x04
    if (device.currentValue('switch') == 'off' && level > 0 && levelPreset == false) {
        // If light is off, first go to level 0 then to desired level
        cmds += zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, 0x04, [:], delay, "00 0000 ${PRESTAGING_OPTION}")
    }
    // Payload: Level | Transition Time | Options Mask | Options Override
    // Options: Bit 0x01 enables prestaging level
    cmds += zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, levelCommand, [:], delay, "${hexLevel} ${hexRate} ${PRESTAGING_OPTION}") +
        ifPolling(DELAY_MS + (rate * 100)) { zigbee.levelRefresh(0) }
    return cmds
}

// Configuration

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay inbetween zigbee commands
@Field static final int DELAY_MS = 200

// Hue hub uses five minute attribute reporting
@Field static final int REPORTING_MAX = 600

// Delay before reading attribute (when using polling)
@Field static final int POLL_DELAY_MS = 1000

// Command option that enable changes when off
@Field static final String PRESTAGING_OPTION = '01 01'

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
        0x001E: '3s',
        0x0028: '4s',
        0x0032: '5s',
        0x0064: '10s'
    ]
]

@Field static Map HealthcheckIntervalOpts = [
    defaultValue: 10,
    options: [ 10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', '59': 'Every Hour', '00': 'Disabled' ]
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
