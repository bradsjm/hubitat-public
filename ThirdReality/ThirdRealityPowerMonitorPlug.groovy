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
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import java.math.RoundingMode

metadata {
    definition(name: 'Third Reality Power Meter Plug',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-public/main/ThirdReality/ThirdRealityPowerMonitorPlug.groovy',
            namespace: 'thirdreality', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Configuration'
        capability 'Current Meter'
        capability 'Energy Meter'
        capability 'Health Check'
        capability 'Outlet'
        capability 'Power Meter'
        capability 'Refresh'
        capability 'Sensor'
        capability 'Switch'
        capability 'Voltage Measurement'

        command 'resetEnergy'
        command 'toggle'
        command 'updateFirmware'

        attribute 'healthStatus', 'enum', [ 'unknown', 'offline', 'online' ]
        attribute 'powerFactor', 'number'

        fingerprint model: '3RSP02028BZ', manufacturer: 'Third Reality, Inc', profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0006,1000,0B04', outClusters: '0019', application: '1E'
    }

    preferences {
        input name: 'powerRestore', type: 'enum', title: '<b>Power Restore Mode</b>', options: PowerRestoreOpts.options, defaultValue: PowerRestoreOpts.defaultValue, description:\
            '<i>Changes what happens when power is restored to outlet.</i>'

        input name: 'healthCheckInterval', type: 'enum', title: '<b>Healthcheck Interval</b>', options: HealthcheckIntervalOpts.options, defaultValue: HealthcheckIntervalOpts.defaultValue, description:\
            '<i>Changes how often the hub pings outlet to check health.</i>'

        input name: 'disableOnOff', type: 'bool', title: '<b>Disable Commands</b>', defaultValue: false, description:\
            '<i>Disables all power commands.</i>'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description:\
            '<i>Enables command logging.</i>'

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description:\
            '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

@Field static final String VERSION = '1.01'

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'
    state.attributes = [:]

    // Power Restore Behavior
    if (settings.powerRestore != null) {
        cmds += zigbee.writeAttribute(zigbee.ON_OFF_CLUSTER, POWER_RESTORE_ID, DataType.ENUM8, settings.powerRestore as Integer, [:], DELAY_MS)
    }

    cmds += zigbee.configureReporting(zigbee.ON_OFF_CLUSTER, POWER_ON_OFF_ID, DataType.BOOLEAN, 0, 3600, 1, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ACTIVE_POWER_ID, DataType.INT16, 5, 3600, 10, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, RMS_CURRENT_ID, DataType.UINT16, 5, 3600, 50, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, RMS_VOLTAGE_ID, DataType.UINT16, 5, 3600, 5, [:], DELAY_MS)
    cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, AC_FREQUENCY_ID, DataType.UINT16, 5, 3600, 1, [:], DELAY_MS)

    if (settings.logEnable) { log.debug "zigbee configure cmds: ${cmds}" }

    runIn(2, 'refresh')
    return cmds
}

void deviceCommandTimeout() {
    log.warn 'no response received (device offline?)'
    updateAttribute('healthStatus', 'offline')
}

void installed() {
    log.info 'installed'
    // populate some default values for attributes
    sendEvent(name: 'amperage', value: 0, unit: 'A')
    sendEvent(name: 'energy', value: 0, unit: 'kWh')
    sendEvent(name: 'frequency', value: 0, unit: 'Hz')
    sendEvent(name: 'healthStatus', value: 'unknown')
    sendEvent(name: 'power', value: 0, unit: 'W')
    sendEvent(name: 'switch', value: 'off')
    sendEvent(name: 'voltage', value: 0, unit: 'V')
    sendEvent(name: 'powerFactor', value: 0)
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

List<String> off() {
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'turn off' }
    scheduleCommandTimeoutCheck()
    state.isDigital = true
    return zigbee.off()
}

List<String> on() {
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'turn on' }
    scheduleCommandTimeoutCheck()
    state.isDigital = true
    return zigbee.on()
}

List<String> ping() {
    if (settings.txtEnable) { log.info 'ping...' }
    // Using attribute 0x00 as a simple ping/pong mechanism
    scheduleCommandTimeoutCheck()
    return zigbee.readAttribute(zigbee.BASIC_CLUSTER, PING_ATTR_ID, [:], 0)
}

List<String> refresh() {
    log.info 'refresh'
    List<String> cmds = []

    // Get Firmware Version
    cmds += zigbee.readAttribute(zigbee.BASIC_CLUSTER, FIRMWARE_VERSION_ID, [:], DELAY_MS)

    // Get Power Restore state
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, POWER_RESTORE_ID, [:], DELAY_MS)

    // Get Power Measurement Formatting
    cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [
        AC_CURRENT_MULTIPLIER_ID,
        AC_CURRENT_DIVISOR_ID,
        AC_VOLTAGE_MULTIPLIER_ID,
        AC_VOLTAGE_DIVISOR_ID,
        AC_POWER_MULTIPLIER_ID,
        AC_POWER_DIVISOR_ID
    ], [:], DELAY_MS)

    // Get Current Power Measurement
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, POWER_ON_OFF_ID, [:], DELAY_MS)

    cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [
        AC_FREQUENCY_ID,
        RMS_CURRENT_ID,
        RMS_VOLTAGE_ID,
        ACTIVE_POWER_ID
    ], [:], DELAY_MS)

    // Active Endpoint request
    //cmds += "he raw ${device.deviceNetworkId} 0 0 0x0005 {00 ${zigbee.swapOctets(device.deviceNetworkId)}} {0x0000}"
    // Simple discovery request
    //cmds += "he raw ${device.deviceNetworkId} 0 0 0x0004 {00 ${zigbee.swapOctets(device.deviceNetworkId)}} {0x0000}"

    scheduleCommandTimeoutCheck()
    return cmds
}

void resetEnergy() {
    log.info 'reset energy value'
    unschedule('updateEnergyCalculation')
    state.energyInKwh = 0
    state.lastPowerUpdate = now()
    updateEnergyCalculation()
}

List<String> toggle() {
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'toggle' }
    scheduleCommandTimeoutCheck()
    state.isDigital = true
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x02, [:], 0)
}

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

List<String> updateFirmware() {
    log.info 'checking for firmware updates'
    return zigbee.updateFirmware()
}

void parse(final String description) {
    final Map descMap = zigbee.parseDescriptionAsMap(description)
    updateAttribute('healthStatus', 'online')
    unschedule('deviceCommandTimeout')

    if (descMap.profileId == '0000') {
        parseZdo(descMap)
        return
    }

    if (descMap.isClusterSpecific == false) {
        if (settings.logEnable) { log.trace "zigbee received global message ${descMap}" }
        parseGlobalCommands(descMap)
        return
    }

    if (settings.logEnable) {
        final String clusterName = clusterLookup(descMap.clusterInt)
        final String attribute = descMap.attrId ? " attribute 0x${descMap.attrId} (value ${descMap.value})" : ''
        if (settings.logEnable) { log.trace "zigbee received ${clusterName} message" + attribute }
    }

    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseBasicCluster(descMap + map) }
            break
        case zigbee.ON_OFF_CLUSTER:
            parseOnOffCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseOnOffCluster(descMap + map) }
            break
        case zigbee.ELECTRICAL_MEASUREMENT_CLUSTER:
            if (state.attributes == null) { state.attributes = [:] }
            parseElectricalMeasureCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseElectricalMeasureCluster(descMap + map) }
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
void parseBasicCluster(final Map descMap) {
    switch (descMap.attrInt as Integer) {
        case PING_ATTR_ID: // Using 0x01 read as a simple ping/pong mechanism
            if (settings.txtEnable) { log.info 'pong..' }
            break
        case FIRMWARE_VERSION_ID:
            final String version = descMap.value ?: 'unknown'
            log.info "device firmware version is ${version}"
            updateDataValue('softwareBuild', version)
            break
        default:
            log.warn "zigbee received unknown Basic cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
    }
}

/*
 * Zigbee Electrical Measurement Cluster Parsing
 */
void parseElectricalMeasureCluster(final Map descMap) {
    if (descMap.value == null || descMap.value == 'FFFF') { return } // invalid or unknown value
    final long value = hexStrToUnsignedInt(descMap.value)
    switch (descMap.attrInt as Integer) {
        case AC_CURRENT_DIVISOR_ID:
        case AC_CURRENT_MULTIPLIER_ID:
        case AC_POWER_DIVISOR_ID:
        case AC_POWER_MULTIPLIER_ID:
        case AC_VOLTAGE_DIVISOR_ID:
        case AC_VOLTAGE_MULTIPLIER_ID:
            state.attributes[descMap.attrInt as String] = value
            break
        case AC_FREQUENCY_ID:
            updateAttribute('frequency', value, 'Hz', 'physical')
            break
        case RMS_CURRENT_ID:
            final Integer multiplier = state.attributes[AC_CURRENT_MULTIPLIER_ID as String] as Integer
            final Integer divisor = state.attributes[AC_CURRENT_DIVISOR_ID as String] as Integer
            if (multiplier > 0 && divisor > 0) {
                final BigDecimal result = value * multiplier / divisor
                updateAttribute('amperage', result.setScale(1, RoundingMode.HALF_UP), 'A', 'physical')
                updatePowerFactor()
            }
            break
        case ACTIVE_POWER_ID:
            final Integer multiplier = state.attributes[AC_POWER_MULTIPLIER_ID as String] as Integer
            final Integer divisor = state.attributes[AC_POWER_DIVISOR_ID as String] as Integer
            if (multiplier > 0 && divisor > 0) {
                unschedule('updateEnergyCalculation')
                updateEnergyCalculation()
                final BigDecimal result = (int)value * multiplier / divisor
                updateAttribute('power', result.setScale(1, RoundingMode.HALF_UP), 'W', 'physical')
                updatePowerFactor()
            }
            break
        case RMS_VOLTAGE_ID:
            final Integer multiplier = state.attributes[AC_VOLTAGE_MULTIPLIER_ID as String] as Integer
            final Integer divisor = state.attributes[AC_VOLTAGE_DIVISOR_ID as String] as Integer
            if (multiplier > 0 && divisor > 0) {
                final BigDecimal result = value * multiplier / divisor
                updateAttribute('voltage', result.setScale(0, RoundingMode.HALF_UP), 'V', 'physical')
                updatePowerFactor()
            }
            break
        default:
            log.warn "zigbee received unknown Electrical Measurement cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
    }
}

/*
 * Zigbee Global Command Parsing
 */
void parseGlobalCommands(final Map descMap) {
    switch (hexStrToUnsignedInt(descMap.command)) {
        case 0x04: // write attribute response
            final String data = descMap.data in List ? (descMap.data as List)[0] : descMap.data
            final int statusCode = hexStrToUnsignedInt(data)
            final String status = "0x${intToHexStr(statusCode)}"
            if (settings.logEnable) {
                log.trace "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute response: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee response write ${clusterLookup(descMap.clusterInt)} attribute error: ${status}"
            }
            break
        case 0x07: // configure reporting response
            log.info "reporting for ${clusterLookup(descMap.clusterInt)} enabled sucessfully"
            break
        case 0x0B: // default command response
            final List<String> data = descMap.data as List<String>
            final String commandId = data[0]
            final int statusCode = hexStrToUnsignedInt(data[1])
            final String status = "0x${data[1]}"
            if (settings.logEnable) {
                log.trace "zigbee command status ${clusterLookup(descMap.clusterInt)} command 0x${commandId}: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee command error (${clusterLookup(descMap.clusterInt)}, command: 0x${commandId}) ${status}"
            }
            break
    }
}

/*
 * Zigbee On Off Cluster Parsing
 */
void parseOnOffCluster(final Map descMap) {
    switch (descMap.attrInt as Integer) {
        case POWER_ON_OFF_ID:
            final String type = state.isDigital == true ? 'digital' : 'physical'
            state.remove('isDigital')
            updateAttribute('switch', descMap.value == '01' ? 'on' : 'off', null, type)
            break
        case POWER_RESTORE_ID:
            final Map<Integer, String> options = PowerRestoreOpts.options as Map<Integer, String>
            final Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "power restore mode is '${options[value]}' (0x${descMap.value})"
            device.updateSetting('powerRestore', [value: value.toString(), type: 'enum' ])
            break
        default:
            log.warn "zigbee received unknown ${clusterLookup(descMap.clusterInt)}: ${descMap}"
            break
    }
}

void parseZdo(final Map descMap) {
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

private static BigDecimal calculateEnergyInKWh(final BigDecimal currentPower, final Long durationMs) {
    final BigDecimal powerInKw = currentPower / 1000
    final BigDecimal timeInHours = durationMs / (1000 * 60 * 60)
    final BigDecimal energyInKwh = powerInKw * timeInHours
    return energyInKwh
}

private static BigDecimal calculatePowerFactor(final BigDecimal rmsVoltage, final BigDecimal rmsCurrent, final BigDecimal activePower) {
    final BigDecimal apparentPower = rmsVoltage * rmsCurrent
    final BigDecimal powerFactor = activePower / apparentPower
    return powerFactor
}

private String clusterLookup(final Object cluster) {
    final int clusterInt = cluster in String ? hexStrToUnsignedInt(cluster) : cluster.toInteger()
    final String label = zigbee.clusterLookup(clusterInt)?.clusterLabel
    final String hex = "0x${intToHexStr(clusterInt, 2)}"
    return label ? "${label} (${hex}) cluster" : "cluster ${hex}"
}

private void scheduleCommandTimeoutCheck(final int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

private void scheduleDeviceHealthCheck(final int intervalMins) {
    final Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

private void updateAttribute(final String attribute, final Object value, final String unit = null, final String type = null) {
    final String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}

private void updateEnergyCalculation() {
    final Long now = now()
    final Long lastUpdate = state.lastPowerUpdate as Long ?: now
    final Long elapsedMs = now - lastUpdate
    state.lastPowerUpdate = now
    final BigDecimal powerInWatts = device.currentValue('power') as BigDecimal ?: 0
    final BigDecimal energyInKwh = state.energyInKwh as BigDecimal ?: 0
    final BigDecimal result = energyInKwh + calculateEnergyInKWh(powerInWatts, elapsedMs)
    state.energyInKwh = result
    updateAttribute('energy', result.setScale(2, RoundingMode.HALF_UP), 'kWh', 'digital')
    if (powerInWatts > 0) {
        runIn(60, 'updateEnergyCalculation')
    }
    if (settings.logEnable) {
        log.debug "updateEnergyCalculation { power=${powerInWatts}W, elapsedMs=${elapsedMs}, total kWh=${result} }"
    }
}

private void updatePowerFactor() {
    final BigDecimal rmsVoltage = device.currentValue('voltage') as BigDecimal
    final BigDecimal rmsCurrent = device.currentValue('amperage') as BigDecimal
    final BigDecimal activePower = device.currentValue('power') as BigDecimal
    if (rmsVoltage && rmsCurrent && activePower) {
        final BigDecimal powerFactor = calculatePowerFactor(rmsVoltage, rmsCurrent, activePower)
        updateAttribute('powerFactor', powerFactor.setScale(1, RoundingMode.HALF_UP), null, 'digital')
    }
}

// Zigbee Attribute IDs
@Field static final int AC_CURRENT_DIVISOR_ID = 0x0603
@Field static final int AC_CURRENT_MULTIPLIER_ID = 0x0602
@Field static final int AC_FREQUENCY_ID = 0x0300
@Field static final int AC_POWER_DIVISOR_ID = 0x0605
@Field static final int AC_POWER_MULTIPLIER_ID = 0x0604
@Field static final int AC_VOLTAGE_DIVISOR_ID = 0x0601
@Field static final int AC_VOLTAGE_MULTIPLIER_ID = 0x0600
@Field static final int ACTIVE_POWER_ID = 0x050B
@Field static final int FIRMWARE_VERSION_ID = 0x4000
@Field static final int PING_ATTR_ID = 0x01
@Field static final int POWER_ON_OFF_ID = 0x0000
@Field static final int POWER_RESTORE_ID = 0x4003
@Field static final int RMS_CURRENT_ID = 0x0508
@Field static final int RMS_VOLTAGE_ID = 0x0505

@Field static final Map PowerRestoreOpts = [
    defaultValue: 0xFF,
    options: [ 0x00: 'Off', 0x01: 'On', 0xFF: 'Last State' ]
]

@Field static final Map HealthcheckIntervalOpts = [
    defaultValue: 10,
    options: [ 10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', 59: 'Every Hour', 00: 'Disabled' ]
]

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay in between zigbee commands
@Field static final int DELAY_MS = 200
