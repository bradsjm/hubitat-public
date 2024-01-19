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

        command 'toggle'
        command 'updateFirmware'

        attribute 'healthStatus', 'enum', [ 'unknown', 'offline', 'online' ]
        attribute 'powerFactor', 'number'

        fingerprint model: '3RSP02028BZ', manufacturer: 'Third Reality, Inc', profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0006,1000,0B04,0702', outClusters: '0019'
    }

    preferences {
        input name: 'powerRestore', type: 'enum', title: '<b>Power Restore Mode</b>', options: PowerRestoreOpts.options, defaultValue: PowerRestoreOpts.defaultValue, description:\
            '<i>Changes what happens when power is restored to outlet.</i>'

        input name: 'HealthCheckInterval', type: 'enum', title: '<b>HealthCheck Interval</b>', options: HealthCheckIntervalOpts.options, defaultValue: HealthCheckIntervalOpts.defaultValue, description:\
            '<i>Changes how often the hub pings outlet to check health.</i>'

        input name: 'disableOnOff', type: 'bool', title: '<b>Disable Power Commands</b>', defaultValue: false, description:\
            '<i>Disables the driver power commands to stop accidental changes.</i>'

        input name: 'powerDelta', type: 'number', title: '<b>Power Minimum Change</b>', description:\
            '<i>The minimum Power (watts) change that will be recorded.</i>', range: '0.1..1500'

        input name: 'energyDelta', type: 'number', title: '<b>Energy Minimum Change</b>', description:\
            '<i>The minimum energy kWh change that will be recorded.</i>', range: '0.1..100'

        input name: 'amperageDelta', type: 'number', title: '<b>Amperage Minimum Change</b>', description:\
            '<i>The minimum amperage change that will be recorded.</i>', range: '0.1..15'

        input name: 'voltageDelta', type: 'number', title: '<b>Voltage Minimum Change</b>', description:\
            '<i>The minimum voltage change that will be recorded.</i>', range: '1..100'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description:\
            '<i>Enables command logging.</i>'

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: false, description:\
            '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

@Field static final String VERSION = '1.03 (2023-04-22)'

/**
 * Send configuration parameters to the device
 * Invoked when device is first installed and when the user updates the configuration
 * @return List of zigbee commands
 */
List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'
    state.attributes = [:]

    // Power Restore Behavior
    if (settings.powerRestore != null) {
        cmds += zigbee.writeAttribute(zigbee.ON_OFF_CLUSTER, POWER_RESTORE_ID, DataType.ENUM8, settings.powerRestore as Integer, [:], DELAY_MS)
    }

    // Configure reporting - This appears to be ignored by the device
    // Out of the box reporting is only ON_OFF_CLUSTER minReportingInterval: 0, maxReportingInterval: 240
    // However it appears be hardcoded to send updates every 30 seconds and any changes for all clusters
    //cmds += zigbee.configureReporting(zigbee.ON_OFF_CLUSTER, POWER_ON_OFF_ID, DataType.BOOLEAN, 0, 240, null, [:], DELAY_MS)
    //cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ACTIVE_POWER_ID, DataType.INT16, 0, 240, 10, [:], DELAY_MS)
    //cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, RMS_CURRENT_ID, DataType.UINT16, 0, 240, 10, [:], DELAY_MS)
    //cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, RMS_VOLTAGE_ID, DataType.UINT16, 0, 240, 10, [:], DELAY_MS)
    //cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, AC_FREQUENCY_ID, DataType.UINT16, 0, 240, 10, [:], DELAY_MS)
    //cmds += zigbee.configureReporting(zigbee.METERING_CLUSTER, ATTRIBUTE_READING_INFO_SET, DataType.UINT48, 0, 240, 10, [:], DELAY_MS)

    runIn(5, 'refresh')
    return cmds
}

/**
 * Send health status event upon a timeout
 */
void deviceCommandTimeout() {
    log.warn 'no response received (device offline?)'
    updateAttribute('healthStatus', 'offline')
}

/**
 * Invoked by Hubitat when driver is installed
 */
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
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'turn off' }
    scheduleCommandTimeoutCheck()
    state.isDigital = true
    return zigbee.off()
}

/**
 * On Command
 * @return List of zigbee commands
 */
List<String> on() {
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'turn on' }
    scheduleCommandTimeoutCheck()
    state.isDigital = true
    return zigbee.on()
}

/**
 * Ping Command
 * @return List of zigbee commands
 */
List<String> ping() {
    if (settings.txtEnable) { log.info 'ping...' }
    // Using attribute 0x00 as a simple ping/pong mechanism
    scheduleCommandTimeoutCheck()
    return zigbee.readAttribute(zigbee.BASIC_CLUSTER, PING_ATTR_ID, [:], 0)
}

/**
 * Refresh Command
 * @return List of zigbee commands
 */
List<String> refresh() {
    log.info 'refresh'
    state.values = [:]
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

    // Get Measurement Formatting
    cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, [
        METERING_DIVISOR_ID,
        METERING_UNIT_OF_MEASURE_ID,
        METERING_SUMMATION_FORMATTING_ID
    ], [:], DELAY_MS)

    // Get Power On/Off state
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, POWER_ON_OFF_ID, [:], DELAY_MS)

    // Get Current Power Measurement
    cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [
        AC_FREQUENCY_ID,
        RMS_CURRENT_ID,
        RMS_VOLTAGE_ID,
        ACTIVE_POWER_ID
    ], [:], DELAY_MS)

    // Get Energy Measurement
    cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, ATTRIBUTE_READING_INFO_SET, [:], DELAY_MS)

    // Get Reporting Configuration (for debug)
    if (settings.logEnable) {
        cmds += zigbee.reportingConfiguration(zigbee.ON_OFF_CLUSTER, POWER_ON_OFF_ID, [:], DELAY_MS)
        cmds += zigbee.reportingConfiguration(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ACTIVE_POWER_ID, [:], DELAY_MS)
        cmds += zigbee.reportingConfiguration(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, RMS_CURRENT_ID, [:], DELAY_MS)
        cmds += zigbee.reportingConfiguration(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, RMS_VOLTAGE_ID, [:], DELAY_MS)
        cmds += zigbee.reportingConfiguration(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, AC_FREQUENCY_ID, [:], DELAY_MS)
        cmds += zigbee.reportingConfiguration(zigbee.METERING_CLUSTER, ATTRIBUTE_READING_INFO_SET, [:], DELAY_MS)

        // Management LQI (Neighbor Table) Request (response is 0x8031)
        //cmds += "he raw ${device.deviceNetworkId} 0x01 0x00 0x0031 { 01 00 } { 0000 }"
        // Management Rtg (Routing Table) Request (response is 0x8032)
        //cmds += "he raw ${device.deviceNetworkId} 0x01 0x00 0x0032 { 01 00 } { 0000 }"
    }

    scheduleCommandTimeoutCheck()
    return cmds
}

/**
 * Toggle Command (On/Off)
 * @return List of zigbee commands
 */
List<String> toggle() {
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'toggle' }
    scheduleCommandTimeoutCheck()
    state.isDigital = true
    return zigbee.command(zigbee.ON_OFF_CLUSTER, 0x02, [:], 0)
}

/**
 * Update Energy Calculation (no longer required - read from device)
 * @return List of zigbee commands
 */
void updateEnergyCalculation() {
    unschedule('updateEnergyCalculation') // legacy
}

/**
 * Invoked by Hubitat when the driver configuration is updated
 */
void updated() {
    log.info 'updated...'
    log.info "${device} driver version ${VERSION}"
    unschedule()
    state.remove('energyInKwh') // legacy
    state.remove('lastPowerUpdate') // legacy

    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }

    final int interval = (settings.HealthCheckInterval as Integer) ?: 0
    if (interval > 0) {
        log.info "${device} scheduling health check every ${interval} minutes"
        scheduleDeviceHealthCheck(interval)
    }

    runIn(1, 'configure')
}

/**
 * Update Firmware Command
 * @return List of zigbee commands
 */
List<String> updateFirmware() {
    log.info 'checking for firmware updates'
    return zigbee.updateFirmware()
}

/**
 * Parse Zigbee message
 * @param description Zigbee message in hex format
 */
void parse(final String description) {
    final Map descMap = zigbee.parseDescriptionAsMap(description)
    updateAttribute('healthStatus', 'online')
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
        final String clusterName = clusterLookup(descMap.clusterInt)
        final String attribute = descMap.attrId ? " attribute 0x${descMap.attrId} (value ${descMap.value})" : ''
        if (settings.logEnable) { log.trace "${device} zigbee received ${clusterName} message" + attribute }
    }

    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseBasicCluster(descMap + map) }
            break
        case zigbee.ELECTRICAL_MEASUREMENT_CLUSTER:
            if (state.attributes == null) { state.attributes = [:] }
            if (state.values == null) { state.values = [:] }
            parseElectricalMeasureCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseElectricalMeasureCluster(descMap + map) }
            break
        case zigbee.METERING_CLUSTER:
            if (state.values == null) { state.values = [:] }
            parseMeteringCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseMeteringCluster(descMap + map) }
            break
        case zigbee.ON_OFF_CLUSTER:
            parseOnOffCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map map -> parseOnOffCluster(descMap + map) }
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
            if (settings.txtEnable) { log.info 'pong..' }
            break
        case FIRMWARE_VERSION_ID:
            final String version = descMap.value ?: 'unknown'
            log.info "${device} device firmware version is ${version}"
            updateDataValue('softwareBuild', version)
            break
        default:
            log.warn "${device} zigbee received unknown Basic cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
    }
}

/**
 * Zigbee Electrical Measurement Cluster Parsing
 * @param descMap Zigbee message in parsed map format
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
            handleRmsCurrentValue(value)
            break
        case ACTIVE_POWER_ID:
            handleActivePowerValue(value)
            break
        case RMS_VOLTAGE_ID:
            handleRmsVoltageValue(value)
            break
        default:
            log.warn "${device} zigbee received unknown Electrical Measurement cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
    }
}

/**
 * Handle RMS Current Value updates
 * @param value The new RMS Current Value
 */
void handleRmsCurrentValue(final long value) {
    final Integer multiplier = state.attributes[AC_CURRENT_MULTIPLIER_ID as String] as Integer
    final Integer divisor = state.attributes[AC_CURRENT_DIVISOR_ID as String] as Integer
    if (multiplier != null && divisor != null) {
        final BigDecimal currentValue = state.values[RMS_CURRENT_ID as String] as BigDecimal
        BigDecimal result = value * multiplier / divisor
        result = result.setScale(1, RoundingMode.HALF_UP)
        if (isDelta(currentValue, result, settings.amperageDelta as BigDecimal)) {
            state.values[RMS_CURRENT_ID as String] = result
            updateAttribute('amperage', result, 'A', 'physical')
            runIn(1, 'updatePowerFactor')
        }
    }
}

/**
 * Handle Active Power Value updates
 * @param value The new Power Value
 */
void handleActivePowerValue(final long value) {
    final Integer multiplier = state.attributes[AC_POWER_MULTIPLIER_ID as String] as Integer
    final Integer divisor = state.attributes[AC_POWER_DIVISOR_ID as String] as Integer
    if (multiplier > 0 && divisor > 0) {
        final BigDecimal currentValue = state.values[ACTIVE_POWER_ID as String] as BigDecimal
        BigDecimal result = (int)value * multiplier / divisor
        result = result.setScale(1, RoundingMode.HALF_UP)
        if (isDelta(currentValue, result, settings.powerDelta as BigDecimal)) {
            state.values[ACTIVE_POWER_ID as String] = result
            updateAttribute('power', result, 'W', 'physical')
            runIn(1, 'updatePowerFactor')
        }
    }
}

/**
 * Handle RMS Voltage Value updates
 * @param value The new Voltage Value
 */
void handleRmsVoltageValue(final long value) {
    final Integer multiplier = state.attributes[AC_VOLTAGE_MULTIPLIER_ID as String] as Integer
    final Integer divisor = state.attributes[AC_VOLTAGE_DIVISOR_ID as String] as Integer
    if (multiplier > 0 && divisor > 0) {
        final BigDecimal currentValue = state.values[RMS_VOLTAGE_ID as String] as BigDecimal
        BigDecimal result = value * multiplier / divisor
        result = result.setScale(0, RoundingMode.HALF_UP)
        if (isDelta(currentValue, result, settings.voltageDelta as BigDecimal)) {
            state.values[RMS_VOLTAGE_ID as String] = result
            updateAttribute('voltage', result, 'V', 'physical')
            updatePowerFactor()
        }
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
                log.warn "${device} zigbee configure reporting error: ${statusName} ${descMap.data}"
            } else if (settings.logEnable) {
                log.trace "${device} zigbee configure reporting response: ${statusName} ${descMap.data}"
            }
            break
        case 0x09: // read reporting configuration response
            parseReadReportingConfigResponse(descMap)
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
                log.warn "${device} zigbee ${commandName} ${clusterName} error: ${statusName}"
            } else if (settings.logEnable) {
                log.trace "${device} zigbee ${commandName} ${clusterName}: ${descMap.data}"
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
        log.warn "${device} zigbee ${clusterLookup(descMap.clusterInt)} command 0x${commandId} error: ${status}"
    } else if (settings.logEnable) {
        log.trace "${device} zigbee ${clusterLookup(descMap.clusterInt)} command 0x${commandId} response: ${status}"
    }
}

/**
 * Zigbee Metering Cluster Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseMeteringCluster(final Map descMap) {
    if (descMap.value == null || descMap.value == 'FFFF') { return } // invalid or unknown value
    final long value = hexStrToUnsignedInt(descMap.value)
    switch (descMap.attrInt as Integer) {
        case ATTRIBUTE_READING_INFO_SET:
            final Long divisor = state.attributes[METERING_DIVISOR_ID as String] as Long
            final BigDecimal currentValue = state.values[ATTRIBUTE_READING_INFO_SET as String] as BigDecimal
            if (divisor > 0) {
                BigDecimal result = value / divisor
                result = result.setScale(1, RoundingMode.HALF_UP)
                final String unit = state.attributes[METERING_UNIT_OF_MEASURE_ID as String] == 0 ? 'kWh' : ''
                if (isDelta(currentValue, result, settings.energyDelta as BigDecimal)) {
                    state.values[ATTRIBUTE_READING_INFO_SET as String] = result
                    updateAttribute('energy', result, unit, 'physical')
                }
            }
            break
        case METERING_DIVISOR_ID:
        case METERING_UNIT_OF_MEASURE_ID:
        case METERING_SUMMATION_FORMATTING_ID:
            state.attributes[descMap.attrInt as String] = value
            break
        default:
            log.warn "${device} zigbee received unknown Metering cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
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
        log.trace "${device} zigbee read ${clusterLookup(descMap.clusterInt)} attribute 0x${attribute} response: ${status} ${data}"
    } else if (statusCode > 0x00) {
        log.warn "${device} zigbee read ${clusterLookup(descMap.clusterInt)} attribute 0x${attribute} error: ${status}"
    }
}

/**
 * Zigbee Read Reporting Configuration Response Parsing
 * @param descMap Zigbee message in parsed map format
 */
void parseReadReportingConfigResponse(final Map descMap) {
    final List<String> data = descMap.data as List<String>
    final String status = data.first()
    final int statusCode = hexStrToUnsignedInt(status)
    final String statusName = ZigbeeStatusEnum[statusCode] ?: "0x${status}"
    if (statusCode > 0x00) {
        log.warn "${device} zigbee read reporting config error: ${statusName} ${descMap.data}"
        return
    } else if (settings.logEnable) {
        log.trace "${device} zigbee read reporting config: ${statusName} ${descMap.data}"
    }
    if (data[1] != '00') {
        return
    }
    final String attribute = '0x' + data[3] + data[2]
    final int dataType = hexStrToUnsignedInt(data[4])
    final int minReportingInterval = hexStrToUnsignedInt(data[6] + data[5])
    final int maxReportingInterval = hexStrToUnsignedInt(data[8] + data[7])
    Integer reportableChange = null
    if (!DataType.isDiscrete(dataType)) {
        final int start = DataType.getLength(dataType) + 8
        reportableChange = hexStrToUnsignedInt(data[start..9].join())
    }
    log.info "${device} zigbee reporting configuration [attribute: ${attribute}, dataType: ${dataType}, minReportingInterval: ${minReportingInterval}, maxReportingInterval: ${maxReportingInterval}, reportableChange: ${reportableChange}]"
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
        log.trace "${device} zigbee response write ${clusterLookup(descMap.clusterInt)} attribute response: ${statusName}"
    } else if (statusCode > 0x00) {
        log.warn "${device} zigbee response write ${clusterLookup(descMap.clusterInt)} attribute error: ${statusName}"
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
                log.trace "${device} zigbee response add group ${groupId}: ${statusName}"
            } else if (statusCode > 0x00) {
                log.warn "${device} zigbee response add group ${groupId} error: ${statusName}"
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
            log.info "${device} zigbee group memberships: ${groups} (capacity available: ${capacity})"
            break
        default:
            log.warn "${device} zigbee received unknown GROUPS cluster: ${descMap}"
            break
    }
}

/**
 * Zigbee On Off Cluster Parsing
 * @param descMap Zigbee message in parsed map format
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
            log.info "${device} power restore mode is '${options[value]}' (0x${descMap.value})"
            device.updateSetting('powerRestore', [value: value.toString(), type: 'enum' ])
            break
        default:
            log.warn "${device} zigbee received unknown ${clusterLookup(descMap.clusterInt)}: ${descMap}"
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
        log.warn "${device} zigbee received device object ${clusterName} error: ${statusName}"
    } else if (settings.logEnable) {
        log.trace "${device} zigbee received device object ${clusterName} success: ${descMap.data}"
    }
}

/**
 * Calculates the power factor from the RMS voltage, RMS current and active power
 * @param rmsVoltage in V
 * @param rmsCurrent in A
 * @param activePower in W
 * @return power factor
 */
private static BigDecimal calculatePowerFactor(final BigDecimal rmsVoltage, final BigDecimal rmsCurrent, final BigDecimal activePower) {
    final BigDecimal apparentPower = rmsVoltage * rmsCurrent
    final BigDecimal powerFactor = activePower / apparentPower
    return powerFactor
}

/**
 * Checks if the specified value is at or above the minimum change from the previous value
 * @param value value to check
 * @param previousValue previous value
 * @param minimumChange minimum change
 * @return true if the value is over the minimum change, otherwise false
 */
private boolean isDelta(final BigDecimal value, final BigDecimal previousValue, final BigDecimal minimumChange) {
    boolean result = true
    if (value > 0 && previousValue != null && minimumChange > 0) {
        result = (value - previousValue).abs() >= minimumChange
    }

    if (settings.logEnable) {
        log.debug "isDelta(value: ${value}, previousValue: ${previousValue}, minimumChange: ${minimumChange}) = ${result}"
    }
    return result
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
 * Schedule a command timeout check
 * @param delay delay in seconds (default COMMAND_TIMEOUT)
 */
private void scheduleCommandTimeoutCheck(final int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

/**
 * Schedule a device health check
 * @param intervalMin interval in minutes
 */
private void scheduleDeviceHealthCheck(final int intervalMin) {
    final Random rnd = new Random()
    schedule("${rnd.nextInt(59)} */${intervalMin} * ? * * *", 'ping')
}

/**
 * Update the specified device attribute with the specified value and log if changed
 * @param attribute name of the attribute
 * @param value value of the attribute
 * @param unit unit of the attribute
 * @param type type of the attribute
 */
private void updateAttribute(final String attribute, final Object value, final String unit = null, final String type = null) {
    final String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}

/**
 * Update the power factor calculation
 */
private void updatePowerFactor() {
    final BigDecimal rmsVoltage = device.currentValue('voltage') as BigDecimal
    final BigDecimal rmsCurrent = device.currentValue('amperage') as BigDecimal
    final BigDecimal activePower = device.currentValue('power') as BigDecimal
    if (rmsVoltage && rmsCurrent && activePower) {
        BigDecimal powerFactor = calculatePowerFactor(rmsVoltage, rmsCurrent, activePower)
        if (powerFactor < -1) { powerFactor = -1 } // power factor can't be less than -1
        if (powerFactor > 1) { powerFactor = 1 } // power factor can't be greater than 1
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
@Field static final int ATTRIBUTE_READING_INFO_SET = 0x0000
@Field static final int FIRMWARE_VERSION_ID = 0x4000
@Field static final int PING_ATTR_ID = 0x01
@Field static final int POWER_ON_OFF_ID = 0x0000
@Field static final int POWER_RESTORE_ID = 0x4003
@Field static final int RMS_CURRENT_ID = 0x0508
@Field static final int RMS_VOLTAGE_ID = 0x0505
@Field static final int METERING_UNIT_OF_MEASURE_ID = 0x0300
@Field static final int METERING_DIVISOR_ID = 0x0302
@Field static final int METERING_SUMMATION_FORMATTING_ID = 0x0303

@Field static final Map PowerRestoreOpts = [
    defaultValue: 0xFF,
    options: [ 0x00: 'Off', 0x01: 'On', 0xFF: 'Last State' ]
]

@Field static final Map HealthCheckIntervalOpts = [
    defaultValue: 10,
    options: [ 10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', 59: 'Every Hour', 00: 'Disabled' ]
]

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay in between zigbee commands
@Field static final int DELAY_MS = 200

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
