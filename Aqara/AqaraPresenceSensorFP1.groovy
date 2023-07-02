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
 *  LIABILITY, WHETHER IN AN activity OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 *
 *  Thanks to the Zigbee2Mqtt and Dresden-elektronik teams for
 *  their existing work in decoding the FP1's protocol.
 *
 *  Region encoding and decoding courtesy of Otnow:
 *  https://github.com/dresden-elektronik/deconz-rest-plugin/issues/5928#issuecomment-1166545226
 */

import groovy.transform.Field
import hubitat.device.HubMultiAction
import hubitat.device.Protocol
import hubitat.helper.HexUtils
import hubitat.zigbee.zcl.DataType
import java.util.concurrent.ConcurrentHashMap

@Field static final String VERSION = '2023-05-25'

metadata {
    definition(name: 'Aqara Human Presence Sensor FP1',
        importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-dev/main/Aqara/AqaraPresenceSensorFP1.groovy',
        namespace: 'aqara', author: 'Jonathan Bradshaw') {
        capability 'Configuration'
        capability 'Health Check'
        capability 'Motion Sensor'
        capability 'Sensor'

        command 'resetState'

        attribute 'distance', 'number'
        attribute 'angle', 'number'
        attribute 'healthStatus', 'enum', ['unknown', 'offline', 'online']
        attribute 'roomState', 'enum', PRESENCE_STATES.values() as List<String>
        attribute 'roomActivity', 'enum', PRESENCE_ACTIONS.values() as List<String>
        attribute 'region1', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region2', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region3', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region4', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region5', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region6', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region7', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region8', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region9', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>
        attribute 'region10', 'enum', REGION_BASIC_ACTIONS.values() + REGION_ACTIONS.values() as List<String>

        fingerprint model: 'lumi.motion.ac01', manufacturer: 'aqara', profileId: '0104', endpointId: '01',
            inClusters: '0000,0003,FCC0', outClusters: '0003,0019', application: '36'
    }

    preferences {
        input name: 'approachDistance', type: 'enum', title: getCalculatorHeader() + '<b>Approach Distance</b>',
            options: ApproachDistanceOpts.options, defaultValue: ApproachDistanceOpts.defaultValue,
            description: '<i>Maximum distance for detecting approach/away activity.</i>'

        input name: 'sensitivityLevel', type: 'enum', title: '<b>Motion Sensitivity</b>',
            options: SensitivityLevelOpts.options, defaultValue: SensitivityLevelOpts.defaultValue,
            description: '<i>Sensitivity of movement detection for determining presence.</i>'

        input name: 'directionMode', type: 'enum', title: '<b>Monitoring Direction Mode</b>',
            options: DirectionModeOpts.options, defaultValue: DirectionModeOpts.defaultValue,
            description: '<i>Select capability mode for direction detection (left and right).</i>'

        input name: 'regionDetailLevel', type: 'enum', title: '<b>Region Detail Level</b>',
            options: RegionDetailLevelOpts.options, defaultValue: RegionDetailLevelOpts.defaultValue, required: true,
            description: '<i>Select level of detail to use with the region attribute values.</i>'

        input name: 'detectionRegion1', type: 'text', title: '<b>&#9312; Detection Region</b>', description: getCalculatorGrid('region1')
        input name: 'detectionRegion2', type: 'text', title: '<b>&#9313; Detection Region</b>', description: getCalculatorGrid('region2')
        input name: 'detectionRegion3', type: 'text', title: '<b>&#9314; Detection Region</b>', description: getCalculatorGrid('region3')
        input name: 'detectionRegion4', type: 'text', title: '<b>&#9315; Detection Region</b>', description: getCalculatorGrid('region4')

        if (advancedConfig) {
            input name: 'detectionRegion5', type: 'text', title: '<b>&#9316; Detection Region</b>', description: getCalculatorGrid('region5')
            input name: 'detectionRegion6', type: 'text', title: '<b>&#9317; Detection Region</b>', description: getCalculatorGrid('region6')
            input name: 'detectionRegion7', type: 'text', title: '<b>&#9318; Detection Region</b>', description: getCalculatorGrid('region7')
            input name: 'detectionRegion8', type: 'text', title: '<b>&#9319; Detection Region</b>', description: getCalculatorGrid('region8')
            input name: 'detectionRegion9', type: 'text', title: '<b>&#9320; Detection Region</b>', description: getCalculatorGrid('region9')
            input name: 'detectionRegion10', type: 'text', title: '<b>&#9321; Detection Region</b>', description: getCalculatorGrid('region10')
            input name: 'edgesRegion', type: 'text', title: '<b>Edges Region</b>', description: getCalculatorGrid('edges', 'green')
            input name: 'exitEntrancesRegion', type: 'text', title: '<b>Exit/Entrance Region</b>',
                description: getCalculatorGrid('exitentrances', 'green')
            input name: 'interferenceRegion', type: 'text', title: '<b>Interference Region</b>',
                description: getCalculatorGrid('interference', 'red')

            input name: 'enableDistanceDirection', type: 'bool', title: '<b>Enable distance and direction</b>',
                defaultValue: false, description: '<i>Enables raw sensor data (increases Zigbee traffic).</i>'

            input name: 'stateResetInterval', type: 'enum', title: '<b>Presence Watchdog</b>',
                options: PresenceResetOpts.options, defaultValue: PresenceResetOpts.defaultValue, description: \
                '<i>Reset presence if stuck for extended period of time.</i>'
        }

        input name: 'advancedConfig', type: 'bool', title: '<b>Show advanced configuration</b>', defaultValue: false, description: \
             '<i>Shows additional configuration settings.</i>'

        input name: 'healthCheckInterval', type: 'enum', title: '<b>Healthcheck Interval</b>',
            options: HealthcheckIntervalOpts.options, defaultValue: HealthcheckIntervalOpts.defaultValue, description: \
             '<i>Changes how often the hub pings sensor to check health.</i>'

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>', defaultValue: true, description:\
            '<i>Enables event description logging.</i>'

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', defaultValue: true, description:\
            '<i>Enables debug logging for 30 minutes.</i>'
    }
}

List<String> configure() {
    List<String> cmds = []
    log.info 'configure...'

    // Aqara Voodoo
    if (state.paired != true) {
        cmds += ["he raw 0x${device.deviceNetworkId} 1 ${device.endpointId} 0xFCC0 {14 5F 11 01 02 f2 ff 41 aa 74 02 44 00 9c 03 20}  {0x0104}", "delay 50",]                                 // FP1 (seq:9) write attr 0xfff2 8 bytes
        cmds += ["he raw 0x${device.deviceNetworkId} 1 ${device.endpointId} 0xFCC0 {14 5F 11 01 02 f2 ff 41 aa 74 02 44 01 9b 01 20}  {0x0104}", "delay 50",]                                 // FP1 (seq:10) write attr 0xfff2 8 bytes
    }

    if (settings.sensitivityLevel) {
        log.info "setting sensitivity level to ${SensitivityLevelOpts.options[settings.sensitivityLevel as Integer]} (${settings.sensitivityLevel})"
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SENSITIVITY_LEVEL_ATTR_ID, DataType.UINT8, settings.sensitivityLevel as Integer, MFG_CODE, DELAY_MS)
    }

    if (settings.approachDistance) {
        log.info "setting approach distance to ${ApproachDistanceOpts.options[settings.approachDistance as Integer]} (${settings.approachDistance})"
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, TRIGGER_DISTANCE_ATTR_ID, DataType.UINT8, settings.approachDistance as Integer, MFG_CODE, DELAY_MS)
    }

    if (settings.directionMode) {
        log.info "setting direction mode to ${DirectionModeOpts.options[settings.directionMode as Integer]} (${settings.directionMode})"
        cmds += zigbee.writeAttribute(XIAOMI_CLUSTER_ID, DIRECTION_MODE_ATTR_ID, DataType.UINT8, settings.directionMode as Integer, MFG_CODE, DELAY_MS)
    }

    // Set or clear detection regions
    (1..10).each { final int id ->
        final int[] grid = (settings["detectionRegion${id}"]?.tokenize(',') as int[]) ?: []
        final String region = "region${id}"
        if (grid.sum() > 0) {
            log.info "setting detection region ${id} value to ${grid}"
            cmds += setDetectionRegionAttribute(id, grid)
            if (device.currentValue(region) == null) {
                final String value = ((settings.regionDetailLevel as Integer) == 1) ? 'inactive' : 'unoccupied'
                updateAttribute(region, value)
            }
        } else {
            log.info "clearing detection region ${id}"
            cmds += setDetectionRegionAttribute(id, 0, 0, 0, 0, 0, 0, 0)
            device.deleteCurrentState(region)
        }
    }

    if (settings.interferenceRegion && settings.interferenceRegion != '0, 0, 0, 0, 0, 0, 0') {
        log.info 'setting detection interference region value to ' + settings.interferenceRegion
        cmds += setRegionAttribute(SET_INTERFERENCE_ATTR_ID, settings.interferenceRegion.tokenize(',') as int[])
    } else {
        log.info 'clearing detection interference region'
        cmds += setRegionAttribute(SET_INTERFERENCE_ATTR_ID, 0, 0, 0, 0, 0, 0, 0)
    }

    if (settings.exitEntrancesRegion && settings.exitEntrancesRegion != '0, 0, 0, 0, 0, 0, 0') {
        log.info 'setting exits/entrances region value to ' + settings.exitEntrancesRegion
        cmds += setRegionAttribute(SET_EXIT_REGION_ATTR_ID, settings.exitEntrancesRegion.tokenize(',') as int[])
    } else {
        log.info 'clearing exits/entrances region'
        cmds += setRegionAttribute(SET_EXIT_REGION_ATTR_ID, 0, 0, 0, 0, 0, 0, 0)
    }

    // Set edges region
    if (settings.edgesRegion && settings.edgesRegion != '0, 0, 0, 0, 0, 0, 0') {
        log.info 'setting edges region value to ' + settings.edgesRegion
        cmds += setRegionAttribute(SET_EDGE_REGION_ATTR_ID, settings.edgesRegion.tokenize(',') as int[])
    } else {
        log.info 'clearing edges region'
        cmds += setRegionAttribute(SET_EDGE_REGION_ATTR_ID, 0, 0, 0, 0, 0, 0, 0)
    }

    if (settings.logEnable) {
        log.debug "zigbee configure cmds: ${cmds}"
    }

    return cmds
}

void deviceCommandTimeout() {
    log.warn 'no response received (device offline?)'
    updateAttribute('healthStatus', 'offline')
    resetState()
}

void enableSensorData() {
    if (device.currentValue('healthStatus') != 'online') { return }
    if (settings.logEnable) {
        log.debug 'requesting sensor data'
    }
    sendHubCommand(new HubMultiAction(zigbee.writeAttribute(XIAOMI_CLUSTER_ID, 0x0155, DataType.UINT8, 0x01, MFG_CODE, 0), Protocol.ZIGBEE))
}

void installed() {
    log.info 'installed'
    // populate some default values for attributes
    sendEvent(name: 'healthStatus', value: 'unknown')
    sendEvent(name: 'roomActivity', value: 'leave')
    sendEvent(name: 'motion', value: 'inactive')
    sendEvent(name: 'roomState', value: PRESENCE_STATES[0])
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

void parse(final String description) {
    final Map descMap = zigbee.parseDescriptionAsMap(description)
    updateAttribute('healthStatus', 'online')
    unschedule('deviceCommandTimeout')

    if (descMap.profileId == '0000') {
        parseZdo(descMap)
        return
    } else if (descMap.isClusterSpecific == false) {
        parseGlobalCommands(descMap)
        return
    }

    switch (descMap.clusterInt as Integer) {
        case zigbee.BASIC_CLUSTER:
            parseBasicCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map m -> parseBasicCluster(descMap + m) }
            break
        case XIAOMI_CLUSTER_ID:
            parseXiaomiCluster(descMap)
            descMap.remove('additionalAttrs')?.each { final Map m -> parseXiaomiCluster(descMap + m) }
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
    if (settings.logEnable) {
        log.trace "zigbee received Basic cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
    }

    switch (descMap.attrInt as Integer) {
        case PING_ATTR_ID: // Using 0x01 read as a simple ping/pong mechanism
            if (settings.txtEnable) {
                log.info 'pong..'
            }
            break
        case MODEL_ATTR_ID:
            log.info "device model identifier: ${descMap.value}"
            break
        default:
            log.warn "zigbee received unknown Basic cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
    }
}

/*
 * Zigbee Global Command Parsing
 */

void parseGlobalCommands(final Map descMap) {
    switch (hexStrToUnsignedInt(descMap.command)) {
        case 0x00:
            if (settings.logEnable) {
                log.trace "zigbee ${clusterLookup(descMap.clusterInt)} response: ${descMap.data}"
            }
            break
        case 0x04: // write attribute response
            final int statusCode = hexStrToUnsignedInt(descMap.data in List ? descMap.data[0] : descMap.data)
            final String status = "0x${intToHexStr(statusCode)}"
            if (settings.logEnable) {
                log.trace "zigbee write ${clusterLookup(descMap.clusterInt)} attribute response: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee write ${clusterLookup(descMap.clusterInt)} attribute error: ${status}"
            }
            break
        case 0x07: // configure reporting response
            log.info "reporting for ${clusterLookup(descMap.clusterInt)} enabled sucessfully"
            break
        case 0x0B: // default command response
            final String commandId = descMap.data[0]
            final int statusCode = hexStrToUnsignedInt(descMap.data[1])
            final String status = "0x${descMap.data[1]}"
            if (settings.logEnable) {
                log.trace "zigbee command status ${clusterLookup(descMap.clusterInt)} command 0x${commandId}: ${status}"
            } else if (statusCode != 0x00) {
                log.warn "zigbee command error (${clusterLookup(descMap.clusterInt)}, command: 0x${commandId}) ${status}"
            }
            break
        default:
            if (settings.logEnable) {
                log.trace "zigbee received global command message ${descMap}"
            }
            break
    }
}

/*
 * Zigbee Xiaomi Cluster Parsing
 */

void parseXiaomiCluster(final Map descMap) {
    if (settings.logEnable) {
        log.trace "zigbee received xiaomi cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
    }

    switch (descMap.attrInt as Integer) {
        case 0x00FC:
            log.info 'unknown attribute - resetting?'
            break
        case PRESENCE_ATTR_ID:
            final Integer value = hexStrToUnsignedInt(descMap.value)
            parseXiaomiClusterPresence(value)
            break
        case PRESENCE_ACTIONS_ATTR_ID:
            final Integer value = hexStrToUnsignedInt(descMap.value)
            parseXiaomiClusterPresenceAction(value)
            break
        case REGION_EVENT_ATTR_ID:
            // Region events can be sent fast and furious so buffer them
            final Integer regionId = HexUtils.hexStringToInt(descMap.value[0..1])
            final Integer value = HexUtils.hexStringToInt(descMap.value[2..3])
            if (settings.logEnable) {
                log.debug "xiaomi: region ${regionId} action is ${value}"
            }
            if (device.currentValue("region${regionId}") != null) {
                RegionUpdateBuffer[device.id].put(regionId, value)
                runInMillis(REGION_UPDATE_DELAY_MS, 'updateRegions')
            }
            break
        case SENSITIVITY_LEVEL_ATTR_ID:
            final Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "sensitivity level is '${SensitivityLevelOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('sensitivityLevel', [value: value.toString(), type: 'enum'])
            break
        case TRIGGER_DISTANCE_ATTR_ID:
            final Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "approach distance is '${ApproachDistanceOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('approachDistance', [value: value.toString(), type: 'enum'])
            break
        case DIRECTION_MODE_ATTR_ID:
            final Integer value = hexStrToUnsignedInt(descMap.value)
            log.info "monitoring direction mode is '${DirectionModeOpts.options[value]}' (0x${descMap.value})"
            device.updateSetting('directionMode', [value: value.toString(), type: 'enum'])
            break
        case XIAOMI_SPECIAL_REPORT_ID: // sent every 5 minutes
            final Map<Integer, Integer> tags = decodeXiaomiTags(descMap.value)
            parseXiaomiClusterTags(tags)
            break
        case XIAOMI_RAW_ATTR_ID:
            final byte[] rawData = HexUtils.hexStringToByteArray(descMap.value)
            if (rawData.size() == 24 && settings.enableDistanceDirection) {
                final int degrees = rawData[19]
                final int distanceCm = (rawData[17] << 8) | (rawData[18] & 0x00ff)
                if (settings.logEnable) {
                    log.debug "location ${degrees}&deg;, ${distanceCm}cm"
                }
                runIn(1, 'updateLocation', [ data: [ degrees: degrees, distanceCm: distanceCm ] ])
            }
            break
        case 0x00FF:
            // unknown
            break
        default:
            log.warn "zigbee received unknown xiaomi cluster attribute 0x${descMap.attrId} (value ${descMap.value})"
            break
    }
}

void parseXiaomiClusterPresence(final Integer value) {
    if (settings.logEnable) {
        log.debug "xiaomi: presence state attribute is ${value}"
    }
    if (value > 1) { return } // 255 is used when presence is not yet known
    updateAttribute('roomState', PRESENCE_STATES[value])
    switch (value) {
        case 0:
            resetRegions()
            unschedule('resetState')
            updateAttribute('motion', 'inactive')
            if (settings.enableDistanceDirection) {
                updateAttribute('distance', 0, 'cm')
                updateAttribute('angle', 0, '°')
            }
            break
        case 1:
            if (device.currentValue('motion') != 'active') {
                setWatchdogTimer()
            }
            updateAttribute('motion', 'active')
            break
    }
}

void parseXiaomiClusterPresenceAction(final Integer value) {
    if (settings.logEnable) {
        log.debug "xiaomi: action attribute is ${value}"
    }
    if (value <= 7) {
        final String activity = PRESENCE_ACTIONS[value]
        updateAttribute('roomActivity', activity)
        updateAttribute('motion', value in [0, 2, 4, 6, 7] ? 'active' : 'inactive')
    } else {
        log.warn "unknown presence value ${value}"
    }
}

void parseXiaomiClusterTags(final Map<Integer, Object> tags) {
    tags.each { final Integer tag, final Object value ->
        switch (tag) {
            case 0x03:
                if (settings.logEnable) {
                    log.debug "device temperature ${value}&deg;"
                }
                break
            case DIRECTION_MODE_TAG_ID:
                if ((value as Integer) in DirectionModeOpts.options) {
                    if (settings.logEnable) { log.debug "xiaomi tag: directionMode (value ${value})" }
                    device.updateSetting('directionMode', [value: value.toString(), type: 'enum'])
                } else {
                    log.warn "xiaomi tag: directionMode (value ${value}) out of range"
                }
                break
            case SENSITIVITY_LEVEL_TAG_ID:
                if ((value as Integer) in SensitivityLevelOpts.options) {
                    if (settings.logEnable) { log.debug "xiaomi tag: sensitivityLevel (value ${value})" }
                    device.updateSetting('sensitivityLevel', [value: value.toString(), type: 'enum'])
                } else {
                    log.warn "xiaomi tag: sensitivityLevel (value ${value}) out of range"
                }
                break
            case PRESENCE_ACTIONS_TAG_ID:
                parseXiaomiClusterPresenceAction(value)
                break
            case PRESENCE_TAG_ID:
                parseXiaomiClusterPresence(value)
                break
            case SWBUILD_TAG_ID:
                final String swBuild = '0.0.0_' + (value & 0xFF).toString().padLeft(4, '0')
                if (settings.logEnable) { log.debug "xiaomi tag: swBuild (value ${swBuild})" }
                device.updateDataValue('softwareBuild', swBuild)
                break
            case TRIGGER_DISTANCE_TAG_ID:
                if ((value as Integer) in ApproachDistanceOpts.options) {
                    if (settings.logEnable) { log.debug "xiaomi tag: approachDistance (value ${value})" }
                    device.updateSetting('approachDistance', [value: value.toString(), type: 'enum'])
                } else {
                    log.warn "xiaomi tag: approachDistance (value ${value}) out of range"
                }
                break
            default:
                if (settings.logEnable) {
                    log.debug "xiaomi decode unknown tag: 0x${intToHexStr(tag, 1)}=${value}"
                }
                break
        }
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
            state.remove('paired')
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
            state.paired = true
            if (settings.logEnable) {
                log.debug "zdo command: cluster: ${descMap.clusterId} (device announce)"
            }
            break
    }
}

List<String> ping() {
    if (settings.txtEnable) {
        log.info 'ping...'
    }
    // Using attribute 0x00 as a simple ping/pong mechanism
    scheduleCommandTimeoutCheck()
    return zigbee.readAttribute(zigbee.BASIC_CLUSTER, PING_ATTR_ID, [:], 0)
}

void resetRegions() {
    final String value = ((settings.regionDetailLevel as Integer) == 1) ? 'inactive' : 'unoccupied'
    (1..10).each { final regionId ->
        if (device.currentValue("region${regionId}") != null) {
            updateAttribute("region${regionId}", value)
        }
    }
}

List<String> resetState() {
    log.info 'reset state'
    state.remove('paired')
    updateAttribute('motion', 'inactive')
    updateAttribute('roomState', PRESENCE_STATES[0])
    updateAttribute('roomActivity', 'leave')
    resetRegions()
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, RESET_PRESENCE_ATTR_ID, DataType.UINT8, 0x01, MFG_CODE, 0)
}

void setWatchdogTimer() {
    final boolean watchdogEnabled = (settings.stateResetInterval as Integer) > 0
    if (watchdogEnabled) {
        final int seconds = (settings.stateResetInterval as int) * 60 * 60
        runIn(seconds, 'resetState')
    }
}

void updateLocation(final Map data) {
    final int distanceCm = (int) ((Math.round(data.distanceCm / 5f)) * 5)
    final int degrees = (int) ((Math.round(data.degrees / 5f)) * 5)
    updateAttribute('distance', distanceCm, 'cm')
    updateAttribute('angle', degrees, '°')
}

void updated() {
    log.info 'updated...'
    log.info "driver version ${VERSION}"
    unschedule()
    state.clear()

    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }

    final int interval = (settings.healthCheckInterval as Integer) ?: 0
    if (interval > 0) {
        log.info "scheduling health check every ${interval} minutes"
        scheduleDeviceHealthCheck(interval)
    }

    final boolean watchdogEnabled = (settings.stateResetInterval as Integer) > 0
    if (watchdogEnabled && device.currentValue('roomState') == 'occupied') {
        final int seconds = (settings.stateResetInterval as int) * 60 * 60
        log.info "setting presence reset watchdog timer for ${seconds} seconds"
        runIn(seconds, 'resetState')
    }

    if (settings.enableDistanceDirection) {
        updateAttribute('distance', 0, 'cm')
        updateAttribute('angle', 0, '°')
        runEvery1Minute('enableSensorData')
        enableSensorData()
    } else {
        device.deleteCurrentState('distance')
        device.deleteCurrentState('angle')
    }

    runIn(1, 'configure')
}

void updateRegions() {
    if (settings.logEnable) { log.debug 'processing region cache' }
    final Map<Integer, Integer> regions = RegionUpdateBuffer[device.id]
    final boolean simpleMode = (settings.regionDetailLevel as Integer) == 1
    for (iter = regions.entrySet().iterator(); iter.hasNext();) {
        Map.Entry<Integer, Integer> entry = iter.next()
        iter.remove()
        String value = simpleMode ? REGION_BASIC_ACTIONS[entry.value] : REGION_ACTIONS[entry.value]
        updateAttribute("region${entry.key}", value)
        if (entry.value in [1, 4]) {
            updateAttribute('motion', 'active')
        }
    }
}

/**
 * User interface region calculator
 */
private static String getCalculatorGrid(final String id, final String color = 'blue') {
    return "<div style=\"text-align: center;\">&dArr;</div><table id=\"${id}\" class=\"fp1-grid\"></table>" +
        "<script>\$('document').ready(() => fp1CreateTable(\"${id}\", \"${color}\"))</script>"
}

private static String getCalculatorHeader() {
    return '''<style>
        .fp1-grid {
            margin: 5px auto 5px auto;
        }
        .fp1-grid-box {
            width: 20px;
            height: 20px;
            padding: 5px 20px 5px 20px;
            border: 1px dotted black;
        }
        .fp1-grid-box-red {
            background-color: red;
        }
        .fp1-grid-box-blue {
            background-color: blue;
        }
        .fp1-grid-box-green {
            background-color: green;
        }
        .fp1-grid-box-purple {
            border: 2px solid #ff00ff;
        }
    </style>
    <script>
        const gridBoxClass = 'fp1-grid-box';
        const numRows = 7;
        const numCols = 4;
        var oldRow = 0;
        var oldCol = 0;
        var tables = [];

        const observer = new MutationObserver((mutations) => {
            const distance = parseInt(document.querySelector("#cstate-distance > strong").textContent);
            const angle = parseInt(document.querySelector("#cstate-angle > strong").textContent);
            if (distance > 0 && distance <= 700 && angle != 0 && angle >= -60 && angle <= 60) {
                const row = Math.ceil(distance / 100) - 1;
                const col = Math.round((60 - angle) / 40);
                console.log('row ' + row + ', column ' + col);
                tables.forEach(table => {
                    table.rows[oldRow].cells[oldCol].classList.remove(gridBoxClass + '-purple');
                    table.rows[row].cells[col].classList.add(gridBoxClass + '-purple');
                });
                oldRow = row;
                oldCol = col;
            } else {
                tables.forEach(table => {
                    table.rows[oldRow].cells[oldCol].classList.remove(gridBoxClass + '-purple');
                });
            }
        });

        const angleElement = document.querySelector('#cstate-angle');
        if (angleElement != null) {
            observer.observe(angleElement, { childList: true, characterData: true });
        }

        function fp1CreateTable(tableId, color) {
            const tableElement = document.getElementById(tableId);
            const fragment = document.createDocumentFragment();
            for (let i = 0; i < numRows; i++) {
                const row = document.createElement('tr');
                for (let j = 0; j < numCols; j++) {
                    const cell = document.createElement('td');
                    cell.classList.add(gridBoxClass);
                    row.appendChild(cell);
                }
                fragment.appendChild(row);
            }
            tableElement.appendChild(fragment);

            tableElement.addEventListener('click', (event) => {
                if (event.target.classList.contains(gridBoxClass)) {
                    event.target.classList.toggle(gridBoxClass + '-' + color);
                    fp1UpdateRowSums(tableElement, color);
                }
            });

            fp1PopulateTable(tableElement, color);
            tables.push(tableElement);
        }

        function fp1PopulateTable(tableElement, color) {
            const inputElem = tableElement.parentElement.parentElement.querySelector("input[type='text']");
            const cells = tableElement.querySelectorAll('.' + gridBoxClass);
            const sums = inputElem.value.split(',');
            const hasBitSet = (x, y) => ((x >> y) & 1) === 1;
            inputElem.style.display = 'none';
            for (let i = 0; i < cells.length; i++) {
                const row = Math.floor(i / numCols);
                const col = i % numCols;
                if (hasBitSet(sums[row], ((numCols - 1) - col))) {
                    cells[i].classList.add(gridBoxClass + '-' + color);
                }
            }
        }

        function fp1UpdateRowSums(tableElement, color) {
            const cells = tableElement.querySelectorAll('.' + gridBoxClass);
            const sums = Array(numRows).fill(0);
            for (let i = 0; i < cells.length; i++) {
                if (cells[i].classList.contains(gridBoxClass + '-' + color)) {
                    const row = Math.floor(i / numCols);
                    const col = i % numCols;
                    sums[row] += 1 << ((numCols - 1) - col);
                }
            }
            const inputElement = tableElement.parentElement.parentElement.querySelector("input[type='text']");
            inputElement.value = sums.join(', ');
        }
    </script>'''
}

/**
 *  Reads a specified number of little-endian bytes from a given
 *  ByteArrayInputStream and returns a BigInteger.
 */
private static BigInteger readBigIntegerBytes(final ByteArrayInputStream stream, final int length) {
    final byte[] byteArr = new byte[length]
    stream.read(byteArr, 0, length)
    BigInteger bigInt = BigInteger.ZERO
    for (int i = byteArr.length - 1; i >= 0; i--) {
        bigInt |= (BigInteger.valueOf((byteArr[i] & 0xFF) << (8 * i)))
    }
    return bigInt
}

/**
 *  Decodes a Xiaomi Zigbee cluster attribute payload in hexadecimal format and
 *  returns a map of decoded tag number and value pairs where the value is either a
 *  BigInteger for fixed values or a String for variable length.
 */
private static Map<Integer, Object> decodeXiaomiTags(final String hexString) {
    final Map<Integer, Object> results = [:]
    final byte[] bytes = HexUtils.hexStringToByteArray(hexString)
    new ByteArrayInputStream(bytes).withCloseable { final stream ->
        while (stream.available() > 2) {
            int tag = stream.read()
            int dataType = stream.read()
            Object value
            if (DataType.isDiscrete(dataType)) {
                int length = stream.read()
                byte[] byteArr = new byte[length]
                stream.read(byteArr, 0, length)
                value = new String(byteArr)
            } else {
                int length = DataType.getLength(dataType)
                value = readBigIntegerBytes(stream, length)
            }
            results[tag] = value
        }
    }
    return results
}

/**
 *  Returns the name of the cluster provided if known or returns hex if unknown.
 */
private String clusterLookup(final Object cluster) {
    if (cluster) {
        final int clusterInt = cluster in String ? hexStrToUnsignedInt(cluster) : cluster.toInteger()
        final String label = zigbee.clusterLookup(clusterInt)?.clusterLabel
        final String hex = "0x${intToHexStr(clusterInt, 2)}"
        return label ? "${label} (${hex}) cluster" : "cluster ${hex}"
    }
    return 'unknown cluster'
}

private void scheduleCommandTimeoutCheck(final int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

private void scheduleDeviceHealthCheck(final int intervalMins) {
    final Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

/**
 *  Set a detection region on an Aqara FP1 device based on the provided grid coordinates.
 *
 *  This function takes in a region ID and a grid array containing seven values representing the
 *  rows of the grid. It checks if the region ID and grid values are valid, and returns an
 *  empty list if they are not.
 */
private List<String> setDetectionRegionAttribute(final int regionId, final int ... grid) {
    if (regionId < 1 || regionId > 10) {
        log.error 'region must be between 1 and 10'
        return []
    }
    if (grid.size() != 7) {
        log.error 'grid must contain 7 row values'
        return []
    }

    final String octetStr
    if (grid.sum() > 0) {
        // upsert region
        octetStr = new StringBuilder()
            .append('07010')
            .append(HEX_CHARS[(int) regionId])
            .append(HEX_CHARS[grid[1]])
            .append(HEX_CHARS[grid[0]])
            .append(HEX_CHARS[grid[3]])
            .append(HEX_CHARS[grid[2]])
            .append(HEX_CHARS[grid[5]])
            .append(HEX_CHARS[grid[4]])
            .append('0')
            .append(HEX_CHARS[grid[6]])
            .append('FF')
    } else {
        // delete region
        octetStr = '07030' + HEX_CHARS[(int) regionId] + '0000000000'
    }
    if (settings.logEnable) {
        log.debug "set region ${regionId} to ${octetStr}"
    }
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, SET_REGION_ATTR_ID, DataType.STRING_OCTET, octetStr, MFG_CODE, DELAY_MS)
}

private List<String> setRegionAttribute(final int attribute, final int ... grid) {
    if (grid.size() != 7) {
        log.error 'grid must contain exactly 7 row values'
        return []
    }
    final StringBuilder hexString = new StringBuilder('0')
    for (int i = 6; i >= 0; i--) {
        hexString.append HEX_CHARS[grid[i]]
    }
    return zigbee.writeAttribute(XIAOMI_CLUSTER_ID, attribute, DataType.UINT32, hexString.toString(), MFG_CODE, DELAY_MS)
}

/**
 * Updates an attribute of a device with the given value and optional unit and type.
 * It also logs the update if the new value is different from the current value and if the
 * txtEnable setting is enabled. It then sends an event with the updated attribute information.
 */
private void updateAttribute(final String attribute, final Object value, final String unit = null, final String type = 'digital') {
    final String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (device.currentValue(attribute) != value && settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
}

// Buffer for region updates to reduce event overhead with high rates of change over 10 regions
@Field static final Map<Integer, Map> RegionUpdateBuffer = new ConcurrentHashMap<>().withDefault {
    new ConcurrentHashMap<Integer, Integer>()
}

// Hex characters used for conversion
@Field static final char[] HEX_CHARS = '0123456789ABCDEF'.toCharArray()

// Set of Presence Actions
@Field static final Map<Integer, String> PRESENCE_ACTIONS = [
    0: 'enter',
    1: 'leave',
    2: 'enter (right)',
    3: 'leave (left)',
    4: 'enter (left)',
    5: 'leave (right)',
    6: 'towards',
    7: 'away'
]

// Set of Presence States
@Field static final Map<Integer, String> PRESENCE_STATES = [
    0: 'unoccupied',
    1: 'occupied',
]

// Set of Region Actions
@Field static final Map<Integer, String> REGION_BASIC_ACTIONS = [
    1: 'active',
    2: 'inactive',
    4: 'active',
    8: 'inactive'
]

@Field static final Map<Integer, String> REGION_ACTIONS = [
    1: 'enter',
    2: 'leave',
    4: 'occupied',
    8: 'unoccupied'
]

// Zigbee Cluster
@Field static final int XIAOMI_CLUSTER_ID = 0xFCC0

// Zigbee Attributes
@Field static final int DIRECTION_MODE_ATTR_ID = 0x0144
@Field static final int MODEL_ATTR_ID = 0x05
@Field static final int PING_ATTR_ID = 0x01
@Field static final int PRESENCE_ACTIONS_ATTR_ID = 0x0143
@Field static final int PRESENCE_ATTR_ID = 0x0142
@Field static final int REGION_EVENT_ATTR_ID = 0x0151
@Field static final int RESET_PRESENCE_ATTR_ID = 0x0157
@Field static final int SENSITIVITY_LEVEL_ATTR_ID = 0x010C
@Field static final int SET_EDGE_REGION_ATTR_ID = 0x0156
@Field static final int SET_EXIT_REGION_ATTR_ID = 0x0153
@Field static final int SET_INTERFERENCE_ATTR_ID = 0x0154
@Field static final int SET_REGION_ATTR_ID = 0x0150
@Field static final int TRIGGER_DISTANCE_ATTR_ID = 0x0146
@Field static final int XIAOMI_RAW_ATTR_ID = 0xFFF2
@Field static final int XIAOMI_SPECIAL_REPORT_ID = 0x00F7
@Field static final Map MFG_CODE = [ mfgCode: 0x115F ]

// Xiaomi Tags
@Field static final int DIRECTION_MODE_TAG_ID = 0x67
@Field static final int SENSITIVITY_LEVEL_TAG_ID = 0x66
@Field static final int SWBUILD_TAG_ID = 0x08
@Field static final int TRIGGER_DISTANCE_TAG_ID = 0x69
@Field static final int PRESENCE_ACTIONS_TAG_ID = 0x66
@Field static final int PRESENCE_TAG_ID = 0x65

// Configuration options
@Field static final Map ApproachDistanceOpts = [
    defaultValue: 0x00,
    options     : [0x00: 'Far (~5m)', 0x01: 'Medium (~3m)', 0x02: 'Near (~1m)']
]

@Field static final Map SensitivityLevelOpts = [
    defaultValue: 0x03,
    options     : [0x01: 'Low', 0x02: 'Medium', 0x03: 'High']
]

@Field static final Map DirectionModeOpts = [
    defaultValue: 0x00,
    options     : [0x00: 'Undirected Enter/Leave', 0x01: 'Left & Right Enter/Leave']
]

@Field static final Map HealthcheckIntervalOpts = [
    defaultValue: 10,
    options     : [10: 'Every 10 Mins', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 45: 'Every 45 Mins', 59: 'Every Hour', 00: 'Disabled']
]

@Field static final Map PresenceResetOpts = [
    defaultValue: 0,
    options     : [0: 'Disabled', 1: 'After 1 Hour', 2: 'After 2 Hours', 4: 'After 4 Hours', 8: 'After 8 Hours', 12: 'After 12 Hours']
]

@Field static final Map RegionDetailLevelOpts = [
    defaultValue: 1,
    options     : [1: 'Motion Active/Inactive', 2: 'Enter/Leave/Occupied/Unoccupied' ]
]

// Command timeout before setting healthState to offline
@Field static final int COMMAND_TIMEOUT = 10

// Delay between zigbee commands
@Field static final int DELAY_MS = 200

// Delay between region updates to avoid bounces
@Field static final int REGION_UPDATE_DELAY_MS = 500
