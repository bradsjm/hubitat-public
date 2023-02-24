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
import java.math.RoundingMode

metadata {
    definition(name: 'Third Reality Power Meter Plug',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/Zigbee/ThirdRealityPowerMonitorPlug.groovy',
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

        command 'updateFirmware'

        attribute 'healthStatus', 'enum', [ 'unknown', 'offline', 'online' ]

        command 'toggle'

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

@Field static final String VERSION = '0.1'

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'
    state.clear()
    state.attributes = [:]

    // Power Restore Behavior
    if (settings.powerRestore != null) {
        cmds += zigbee.writeAttribute(zigbee.ON_OFF_CLUSTER, POWER_RESTORE_ID, DataType.ENUM8, settings.powerRestore as Integer, [:], DELAY_MS)
    }

    // Enables or Disables commands (e.g. on/off)
    cmds += zigbee.writeAttribute(zigbee.BASIC_CLUSTER, DEVICE_ENABLED_ID, DataType.BOOLEAN, settings.disableOnOff ? 0 : 1, [:], DELAY_MS)

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
    sendEvent(name: 'switch', value: 'off')
    sendEvent(name: 'amperage', value: 0, unit: 'A')
    sendEvent(name: 'voltage', value: 0, unit: 'V')
    sendEvent(name: 'frequency', value: 0, unit: 'Hz')
    sendEvent(name: 'power', value: 0, unit: 'W')
    sendEvent(name: 'healthStatus', value: 'unknown')
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

List<String> off() {
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'turn off' }
    scheduleCommandTimeoutCheck()
    return zigbee.off()
}

List<String> on() {
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'turn on' }
    scheduleCommandTimeoutCheck()
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
    cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [
        AC_FREQUENCY_ID,
        RMS_CURRENT_ID,
        RMS_VOLTAGE_ID,
        ACTIVE_POWER_ID,
    ], [:], DELAY_MS)

    scheduleCommandTimeoutCheck()
    return cmds
}

List<String> toggle() {
    if (settings.disableOnOff) { return [] }
    if (settings.txtEnable) { log.info 'toggle' }
    scheduleCommandTimeoutCheck()
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

    int interval = (settings.healthCheckInterval as Integer) ?: 0
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

void parse(String description) {
    Map descMap = zigbee.parseDescriptionAsMap(description)
    updateAttribute('healthStatus', 'online')
    unschedule('deviceCommandTimeout')

    if (descMap.isClusterSpecific == false) {
        log.trace "zigbee received message ${descMap}"
        parseGlobalCommands(descMap)
        return
    }

    if (settings.logEnable) {
        String clusterName = zigbee.clusterLookup(descMap.clusterInt)
        String attribute = descMap.attrId ? " attribute 0x${descMap.attrId} (value ${descMap.value})" : ''
        log.trace "zigbee received ${clusterName} message" + attribute
    }

    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseBasicCluster(m) }
            break
        case zigbee.ON_OFF_CLUSTER:
            parseOnOffCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseOnOffCluster(m) }
            break
        case zigbee.ELECTRICAL_MEASUREMENT_CLUSTER:
            if (state.attributes == null) { state.attributes = [:] }
            parseElectricalMeasurementCluster(descMap)
            descMap.additionalAttrs?.each { m -> parseElectricalMeasurementCluster(m) }
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
        default:
            log.warn "zigbee received unknown BASIC_CLUSTER: ${descMap}"
            break
    }
}

/*
 * Zigbee Electrical Measurement Cluster Parsing
 */
void parseElectricalMeasurementCluster(Map descMap) {
    if (descMap.value == null || descMap.value == 'FFFF') { return } // invalid or unknown value
    long value = hexStrToUnsignedInt(descMap.value)
    switch (descMap.attrInt as Integer) {
        case AC_CURRENT_DIVISOR_ID:
            state.attributes[(String)AC_CURRENT_DIVISOR_ID] = value
            break
        case AC_CURRENT_MULTIPLIER_ID:
            state.attributes[(String)AC_CURRENT_MULTIPLIER_ID] = value
            break
        case AC_POWER_DIVISOR_ID:
            state.attributes[(String)AC_POWER_DIVISOR_ID] = value
            break
        case AC_POWER_MULTIPLIER_ID:
            state.attributes[(String)AC_POWER_MULTIPLIER_ID] = value
            break
        case AC_VOLTAGE_DIVISOR_ID:
            state.attributes[(String)AC_VOLTAGE_DIVISOR_ID] = value
            break
        case AC_VOLTAGE_MULTIPLIER_ID:
            state.attributes[(String)AC_VOLTAGE_MULTIPLIER_ID] = value
            break
        case AC_POWER_MULTIPLIER_ID:
            state.attributes[(String)AC_POWER_MULTIPLIER_ID] = value
            break
        case AC_FREQUENCY_ID:
            updateAttribute('frequency', value, 'Hz')
            break
        case RMS_CURRENT_ID:
            Integer multiplier = state.attributes[(String)AC_CURRENT_MULTIPLIER_ID]
            Integer divisor = state.attributes[(String)AC_CURRENT_DIVISOR_ID]
            if (multiplier > 0 && divisor > 0) {
                BigDecimal result = value * multiplier / divisor
                updateAttribute('amperage', result.setScale(1, RoundingMode.HALF_UP), 'A')
            }
            break
        case ACTIVE_POWER_ID:
            Integer multiplier = state.attributes[(String)AC_POWER_MULTIPLIER_ID]
            Integer divisor = state.attributes[(String)AC_POWER_DIVISOR_ID]
            if (multiplier > 0 && divisor > 0) {
                BigDecimal result = value * multiplier / divisor
                updateAttribute('power', result.setScale(1, RoundingMode.HALF_UP), 'W')
            }
            break
        case RMS_VOLTAGE_ID:
            Integer multiplier = state.attributes[(String)AC_VOLTAGE_MULTIPLIER_ID]
            Integer divisor = state.attributes[(String)AC_VOLTAGE_DIVISOR_ID]
            if (multiplier > 0 && divisor > 0) {
                BigDecimal result = value * multiplier / divisor
                updateAttribute('voltage', result.setScale(1, RoundingMode.HALF_UP), 'V')
            }
            break
        default:
            log.warn "zigbee received unknown ELECTRICAL_MEASUREMENT_CLUSTER: ${descMap}"
            break
    }
}

/*
 * Zigbee Global Command Parsing
 */
void parseGlobalCommands(Map descMap) {
    switch (hexStrToUnsignedInt(descMap.command)) {
        case 0x04: // write attribute response
            int statusCode = hexStrToUnsignedInt(descMap.data in List ? descMap.data[0] : descMap.data)
            String status = "0x${intToHexStr(statusCode)}"
            if (settings.logEnable) {
                log.trace "zigbee response write ${zigbee.clusterLookup(descMap.clusterInt)} attribute response: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee response write ${zigbee.clusterLookup(descMap.clusterInt)} attribute error: ${status}"
            }
            break
        case 0x0B: // default command response
            String commandId = descMap.data[0]
            int statusCode = hexStrToUnsignedInt(descMap.data[1])
            String status = "0x${descMap.data[1]}"
            if (settings.logEnable) {
                log.trace "zigbee command status ${zigbee.clusterLookup(descMap.clusterInt)} command 0x${commandId}: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee command error (${zigbee.clusterLookup(descMap.clusterInt)}, command: 0x${commandId}) ${status}"
            }
            break
    }
}

/*
 * Zigbee On Off Cluster Parsing
 */
void parseOnOffCluster(Map descMap) {
    switch (descMap.attrInt as Integer) {
        case POWER_ON_OFF_ID:
            updateAttribute('switch', descMap.value == '01' ? 'on' : 'off')
            break
        case POWER_RESTORE_ID:
            Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "power restore mode is '${PowerRestoreOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('powerRestore', [value: value, type: 'number' ])
            break
        default:
            log.warn "zigbee received unknown ON_OFF_CLUSTER: ${descMap}"
            break
    }
}

private void scheduleCommandTimeoutCheck(int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

private void scheduleDeviceHealthCheck(int intervalMins) {
    Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

private void updateAttribute(String attribute, Object value, String unit = null) {
    String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, descriptionText: descriptionText)
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
@Field static final int DEVICE_ENABLED_ID = 0x0012
@Field static final int FIRMWARE_VERSION_ID = 0x4000
@Field static final int PING_ATTR_ID = 0x01
@Field static final int POWER_ON_OFF_ID = 0x0000
@Field static final int POWER_RESTORE_ID = 0x4003
@Field static final int RMS_CURRENT_ID = 0x0508
@Field static final int RMS_VOLTAGE_ID = 0x0505

@Field static Map PowerRestoreOpts = [
    defaultValue: 0xFF,
    options: [ 0x00: 'Off', 0x01: 'On', 0xFF: 'Last State' ]
]

@Field static Map HealthcheckIntervalOpts = [
    defaultValue: 10,
    options: [ 10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', 59: 'Every Hour', 00: 'Disabled' ]
]

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay inbetween zigbee commands
@Field static final int DELAY_MS = 200
