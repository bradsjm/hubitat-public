/**
 *  MIT License
 *  Copyright 2022 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

library(
    name: 'espHomeApiHelper',
    namespace: 'esphome',
    author: 'jb@nrgup.net',
    description: 'ESPHome Native Protobuf API'
)

import groovy.transform.Field
import hubitat.helper.HexUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ESPHome API Message Implementation
 * https://github.com/esphome/aioesphomeapi/blob/main/aioesphomeapi/api.proto
 */

@Field static final int PING_INTERVAL_SECONDS = 30
@Field static final int PORT_NUMBER = 6053
@Field static final int SEND_RETRY_COUNT = 3
@Field static final int SEND_RETRY_MILLIS = 5000
@Field static final String NETWORK_ATTRIBUTE = 'networkStatus'

@Field static final ConcurrentHashMap<String, String> espReceiveBuffer = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, ConcurrentLinkedQueue> espSentQueue = new ConcurrentHashMap<>()

private void parseMessage(ByteArrayInputStream stream, long length) {
    int msgType = (int) readVarInt(stream, true)
    if (msgType < 1) {
        log.warn "ESPHome message type ${msgType} out of range, skipping"
        return
    }

    Map tags = protobufDecode(stream, length)
    boolean handled = supervisionCheck(msgType, tags)

    switch (msgType) {
        case MSG_DISCONNECT_REQUEST:
            closeSocket('requested by device')
            state.reconnectDelay = 10
            scheduleConnect()
            break
        case MSG_PING_REQUEST:
            sendMessage(MSG_PING_RESPONSE)
            espHomeSchedulePing()
            break
        case MSG_LIST_BINARYSENSOR_RESPONSE:
            parse espHomeListEntitiesBinarySensorResponse(tags)
            break
        case MSG_LIST_COVER_RESPONSE:
            parse espHomeListEntitiesCoverResponse(tags)
            break
        case MSG_LIST_FAN_RESPONSE:
            parse espHomeListEntitiesFanResponse(tags)
            break
        case MSG_LIST_LIGHT_RESPONSE:
            parse espHomeListEntitiesLightResponse(tags)
            break
        case MSG_LIST_SENSOR_RESPONSE:
            parse espHomeListEntitiesSensorResponse(tags)
            break
        case MSG_LIST_SWITCH_RESPONSE:
            parse espHomeListEntitiesSwitchResponse(tags)
            break
        case MSG_LIST_TEXT_SENSOR_RESPONSE:
            parse espHomeListEntitiesTextSensorResponse(tags)
            break
        case MSG_LIST_ENTITIES_RESPONSE:
            espHomeListEntitiesDoneResponse()
            break
        case MSG_BINARY_SENSOR_STATE_RESPONSE:
            parse espHomeBinarySensorState(tags)
            break
        case MSG_COVER_STATE_RESPONSE:
            parse espHomeCoverState(tags)
            break
        case MSG_FAN_STATE_RESPONSE:
            parse espHomeFanState(tags)
            break
        case MSG_LIGHT_STATE_RESPONSE:
            parse espHomeLightState(tags)
            break
        case MSG_SENSOR_STATE_RESPONSE:
            parse espHomeSensorState(tags)
            break
        case MSG_SWITCH_STATE_RESPONSE:
            parse espHomeSwitchState(tags)
            break
        case MSG_TEXT_SENSOR_STATE_RESPONSE:
            parse espHomeTextSensorState(tags)
            break
        case MSG_SUBSCRIBE_LOGS_RESPONSE:
            espHomeSubscribeLogsResponse(tags)
            break
        case MSG_GET_TIME_REQUEST:
            espHomeGetTimeRequest()
            break
        case MSG_LIST_NUMBER_RESPONSE:
            espHomeListEntitiesNumberResponse(tags)
            break
        case MSG_LIST_CAMERA_RESPONSE:
            espHomeListEntitiesCameraResponse(tags)
            break
        case MSG_CAMERA_IMAGE_RESPONSE:
            espHomeCameraImageResponse(tags)
            break
        case MSG_NUMBER_STATE_RESPONSE:
            parse espHomeNumberState(tags)
            break
        case MSG_LIST_SIREN_RESPONSE:
            espHomeListEntitiesSirenResponse(tags)
            break
        case MSG_SIREN_STATE_RESPONSE:
            parse espHomeSirenState(tags)
            break
        case MSG_LIST_LOCK_RESPONSE:
            parse espHomeListEntitiesLockResponse(tags)
            break
        case MSG_LOCK_STATE_RESPONSE:
            parse espHomeLockState(tags)
            break
        case MSG_LIST_BUTTON_RESPONSE:
            parse espHomeListEntitiesButtonResponse(tags)
            break
        case MSG_LIST_MEDIA_RESPONSE:
            parse espHomeListEntitiesMediaPlayerResponse(tags)
            break
        case MSG_MEDIA_STATE_RESPONSE:
            parse espHomeMediaPlayerState(tags)
            break
        default:
            if (!handled) {
                log.warn "ESPHome received unhandled message type ${msgType} with ${tags}"
            }
    }
}

private static Map espHomeBinarySensorState(Map tags) {
    return [
            type: 'state',
            platform: 'binary',
            key: getLongTag(tags, 1),
            state: getBooleanTag(tags, 2),
            hasState: getBooleanTag(tags, 3, true)
    ]
}

private void espHomeButtonCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_BUTTON_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ]
        ])
    }
}

private void espHomeCameraImageRequest(Map tags) {
    sendMessage(MSG_CAMERA_IMAGE_REQUEST, [
            1: [ tags.single ? 1 : 0, WIRETYPE_VARINT ],
            2: [ tags.stream ? 1 : 0, WIRETYPE_VARINT ]
    ])
}

private static Map espHomeCameraImageResponse(Map tags) {
    return [
            type: 'state',
            platform: 'camera',
            key: getLongTag(tags, 1),
            image: tags[2][0],
            done: getBooleanTag(tags, 3)
    ]
}

private void espHomeConnectRequest(String password = null) {
    // Message sent after the hello response to authenticate the client
    // Can only be sent by the client and only at the beginning of the connection
    sendMessage(MSG_CONNECT_REQUEST, [
            1: [ password as String, WIRETYPE_LENGTH_DELIMITED ]
    ], MSG_CONNECT_RESPONSE, 'espHomeConnectResponse'
    )
}

private void espHomeConnectResponse(Map tags) {
    Boolean invalidPassword = getBooleanTag(tags, 1)
    if (invalidPassword) {
        log.error "ESPHome invalid password (update configuration setting)"
        closeSocket('invalid password')
        return
    }

    setNetworkStatus('online')
    state.remove('reconnectDelay')
    espHomeSchedulePing()

    // Step 3: Send Device Info Request
    espHomeDeviceInfoRequest()
}

public void espHomeCoverCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_COVER_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ],
                4: [ tags.position != null ? 1 : 0, WIRETYPE_VARINT ],
                5: [ tags.position as Float, WIRETYPE_FIXED32 ],
                6: [ tags.tilt != null ? 1 : 0, WIRETYPE_VARINT ],
                7: [ tags.tilt as Float, WIRETYPE_FIXED32 ],
                8: [ tags.stop ? 1 : 0, WIRETYPE_VARINT ]
        ], MSG_COVER_STATE_RESPONSE)
    }
}

private static Map espHomeCoverState(Map tags) {
    return [
            type: 'state',
            platform: 'cover',
            key: getLongTag(tags, 1),
            legacyState: getIntTag(tags, 2), // legacy: state has been removed in 1.13
            position: getFloatTag(tags, 3),
            tilt: getFloatTag(tags, 4),
            currentOperation: getIntTag(tags, 5)
    ]
}

private void espHomeDeviceInfoRequest() {
    sendMessage(
            MSG_DEVICEINFO_REQUEST, [:],
            MSG_DEVICEINFO_RESPONSE, 'espHomeDeviceInfoResponse'
    )
}

private void espHomeDeviceInfoResponse(Map tags) {
    Map deviceInfo = [
            type: 'device',
            name: getStringTag(tags, 2),
            macAddress: getStringTag(tags, 3),
            espHomeVersion: getStringTag(tags, 4),
            compileTime: getStringTag(tags, 5),
            boardModel:  getStringTag(tags, 6),
            hasDeepSleep: getBooleanTag(tags, 7),
            projectName: getStringTag(tags, 8),
            projectVersion: getStringTag(tags, 9),
            portNumber: getIntTag(tags, 10)
    ]

    device.name = deviceInfo.name
    if (deviceInfo.macAddress) {
        device.deviceNetworkId = deviceInfo.macAddress.replaceAll(':', '-').toLowerCase()
    }
    device.updateDataValue 'Board Model', deviceInfo.boardModel
    device.updateDataValue 'Compile Time', deviceInfo.compileTime
    device.updateDataValue 'ESPHome Version', deviceInfo.espHomeVersion
    device.updateDataValue 'Has Deep Sleep', deviceInfo.hasDeepSleep ? 'yes' : 'no'
    device.updateDataValue 'MAC Address', deviceInfo.macAddress
    device.updateDataValue 'Project Name', deviceInfo.projectName
    device.updateDataValue 'Project Version', deviceInfo.projectVersion
    device.updateDataValue 'Web Server', "http://${ipAddress}:${deviceInfo.portNumber}"

    parse(deviceInfo)

    // Step 4: Get device entities
    espHomeListEntitiesRequest()
}

private void espHomeFanCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_FAN_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ],
                2: [ tags.state != null ? 1 : 0, WIRETYPE_VARINT ],
                3: [ tags.state ? 1 : 0, WIRETYPE_VARINT ],
                6: [ tags.oscillating != null ? 1 : 0, WIRETYPE_VARINT ],
                7: [ tags.oscillating ? 1 : 0, WIRETYPE_VARINT ],
                8: [ tags.direction != null ? 1 : 0, WIRETYPE_VARINT ],
                9: [ tags.direction as Integer, WIRETYPE_VARINT ],
                10: [ tags.speedLevel != null ? 1 : 0, WIRETYPE_VARINT ],
                11: [ tags.speedLevel as Integer, WIRETYPE_VARINT ]
        ], MSG_FAN_STATE_RESPONSE)
    }
}

private static Map espHomeFanState(Map tags) {
    return [
            type: 'state',
            platform: 'fan',
            key: getLongTag(tags, 1),
            state: getBooleanTag(tags, 2),
            oscillating: getBooleanTag(tags, 3),
            speed: getIntTag(tags, 4), // deprecated
            direction: getIntTag(tags, 5),
            speedLevel: getIntTag(tags, 6)
    ]
}

private void espHomeGetTimeRequest() {
    long value = new Date().getTime() / 1000
    sendMessage(MSG_GET_TIME_RESPONSE, [
            1: [ value as Long, WIRETYPE_VARINT ]
    ])
}

private void espHomeHelloRequest() {
    // Can only be sent by the client and only at the beginning of the connection
    String client = "Hubitat ${location.hub.name}"
    sendMessage(MSG_HELLO_REQUEST, [
            1: [ client as String, WIRETYPE_LENGTH_DELIMITED ]
    ], MSG_HELLO_RESPONSE, 'espHomeHelloResponse'
    )
}

private void espHomeHelloResponse(Map tags) {
    // Confirmation of successful connection request.
    // Can only be sent by the server and only at the beginning of the connection
    String version = getIntTag(tags, 1) + '.' + getIntTag(tags, 2)
    log.info "ESPHome API version: ${version}"
    device.updateDataValue 'API Version', version
    if (getIntTag(tags, 1) > 1) {
        log.error 'ESPHome API version > 1 not supported - disconnecting'
        closeSocket('API version not supported')
        return
    }

    String info = getStringTag(tags, 3)
    if (info) {
        log.info "ESPHome server info: ${info}"
        device.updateDataValue 'Server Info', info
    }

    String name = getStringTag(tags, 4)
    if (name) {
        log.info "ESPHome device name: ${name}"
        device.name = name
    }

    // Step 2: Send the ConnectRequest message
    espHomeConnectRequest(settings.password as String)
}

private void espHomeLightCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_LIGHT_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ],
                2: [ tags.state != null ? 1 : 0, WIRETYPE_VARINT ],
                3: [ tags.state ? 1 : 0, WIRETYPE_VARINT ],
                4: [ tags.masterBrightness != null ? 1 : 0, WIRETYPE_VARINT ],
                5: [ tags.masterBrightness as Float, WIRETYPE_FIXED32 ],
                6: [ (tags.red != null && tags.green != null && tags.blue != null) ? 1 : 0, WIRETYPE_VARINT ],
                7: [ tags.red as Float, WIRETYPE_FIXED32 ],
                8: [ tags.green as Float, WIRETYPE_FIXED32 ],
                9: [ tags.blue as Float, WIRETYPE_FIXED32 ],
                10: [ tags.white != null ? 1 : 0, WIRETYPE_VARINT ],
                11: [ tags.white as Float, WIRETYPE_FIXED32 ],
                12: [ tags.colorTemperature != null ? 1 : 0, WIRETYPE_VARINT ],
                13: [ tags.colorTemperature as Float, WIRETYPE_FIXED32 ],
                14: [ tags.transitionLength != null ? 1 : 0, WIRETYPE_VARINT ],
                15: [ tags.transitionLength as Integer, WIRETYPE_VARINT ],
                16: [ tags.flashLength != null ? 1 : 0, WIRETYPE_VARINT ],
                17: [ tags.flashLength as Integer, WIRETYPE_VARINT ],
                18: [ tags.effect != null ? 1 : 0, WIRETYPE_VARINT ],
                19: [ tags.effect as String, WIRETYPE_LENGTH_DELIMITED ],
                20: [ tags.colorBrightness != null ? 1 : 0, WIRETYPE_VARINT ],
                21: [ tags.colorBrightness as Float, WIRETYPE_FIXED32 ],
                22: [ tags.colorMode != null ? 1 : 0, WIRETYPE_VARINT ],
                23: [ tags.colorMode as Integer, WIRETYPE_VARINT ]
        ], MSG_LIGHT_STATE_RESPONSE)
    }
}

private static Map espHomeLightState(Map tags) {
    return [
            type: 'state',
            platform: 'light',
            key: getLongTag(tags, 1),
            state: getBooleanTag(tags, 2),
            masterBrightness: getFloatTag(tags, 3),
            colorMode: getIntTag(tags, 11),
            colorModeCapabilities: toCapabilities(getIntTag(tags, 11)),
            colorBrightness: getFloatTag(tags, 10),
            red: getFloatTag(tags, 4),
            green: getFloatTag(tags, 5),
            blue: getFloatTag(tags, 6),
            white: getFloatTag(tags, 7),
            colorTemperature: getFloatTag(tags, 8),
            coldWhite: getFloatTag(tags, 12),
            warmWhite: getFloatTag(tags, 13),
            effect: getStringTag(tags, 9)
    ]
}

private void espHomeListEntitiesRequest() {
    if (logEnable) { log.trace 'ESPHome requesting entities list' }
    sendMessage(MSG_LIST_ENTITIES_REQUEST)
}

private static Map espHomeListEntitiesBinarySensorResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'binary',
            isStatusBinarySensor: getBooleanTag(tags, 6),
            disabledByDefault: getBooleanTag(tags, 7),
            icon: getStringTag(tags, 8),
            entityCategory: toEntityCategory(getIntTag(tags, 9))
    ]
}

private static Map espHomeListEntitiesButtonResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'button',
            icon: getStringTag(tags, 5),
            disabledByDefault: getBooleanTag(tags, 6),
            entityCategory: toEntityCategory(getIntTag(tags, 7)),
            deviceClass: getStringTag(tags, 8)
    ]
}

private static Map espHomeListEntitiesCameraResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'camera',
            disabledByDefault: getBooleanTag(tags, 5),
            icon: getStringTag(tags, 6),
            entityCategory: toEntityCategory(getIntTag(tags, 7))
    ]
}

private static Map espHomeListEntitiesCoverResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'cover',
            assumedState: getBooleanTag(tags, 5),
            supportsPosition: getBooleanTag(tags, 6),
            supportsTilt: getBooleanTag(tags, 7),
            deviceClass: getStringTag(tags, 8),
            disabledByDefault: getBooleanTag(tags, 9),
            icon: getStringTag(tags, 10),
            entityCategory: toEntityCategory(getIntTag(tags, 11))
    ]
}

private static Map espHomeListEntitiesLockResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'lock',
            icon: getStringTag(tags, 5),
            disabledByDefault: getBooleanTag(tags, 6),
            entityCategory: toEntityCategory(getIntTag(tags, 7)),
            assumedState: getBooleanTag(tags, 8),
            supportsOpen: getBooleanTag(tags, 9),
            requiresCode: getBooleanTag(tags, 10),
            codeFormat: getStringTag(tags, 11)
    ]
}

private static Map espHomeListEntitiesFanResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'fan',
            supportsOscillation: getBooleanTag(tags, 5),
            supportsSpeed: getBooleanTag(tags, 6),
            supportsDirection: getBooleanTag(tags, 7),
            supportedSpeedLevels: getIntTag(tags, 8),
            disabledByDefault: getBooleanTag(tags, 9),
            icon: getStringTag(tags, 10),
            entityCategory: toEntityCategory(getIntTag(tags, 11))
    ]
}

private static Map espHomeListEntitiesLightResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'light',
            minMireds: getFloatTag(tags, 9),
            maxMireds: getFloatTag(tags, 10),
            effects: getStringTagList(tags, 11),
            supportedColorModes: getIntTagList(tags, 12).collectEntries { e -> [ e, toCapabilities(e) ] },
            disabledByDefault: getBooleanTag(tags, 13),
            icon: getStringTag(tags, 14),
            entityCategory: toEntityCategory(getIntTag(tags, 15))
    ]
}

private static Map espHomeListEntitiesMediaPlayerResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'media_player',
            icon: getStringTag(tags, 5),
            disabledByDefault: getBooleanTag(tags, 6),
            entityCategory: toEntityCategory(getIntTag(tags, 7))
    ]
}

private static Map espHomeListEntitiesNumberResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'number',
            icon: getStringTag(tags, 5),
            minValue: getFloatTag(tags, 6),
            maxValue: getFloatTag(tags, 7),
            step: getFloatTag(tags, 8),
            disabledByDefault: getBooleanTag(tags, 9),
            entityCategory: toEntityCategory(getIntTag(tags, 10)),
            unitOfMeasurement: getStringTag(tags, 11),
            numberMode: getIntTag(tags, 12)
    ]
}

private static Map espHomeListEntitiesSensorResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'sensor',
            icon: getStringTag(tags, 5),
            unitOfMeasurement: getStringTag(tags, 6),
            accuracyDecimals: getIntTag(tags, 7),
            forceUpdate: getBooleanTag(tags, 8),
            deviceClass: getStringTag(tags, 9),
            sensorStateClass: getIntTag(tags, 10),
            lastResetType: getIntTag(tags, 11),
            disabledByDefault: getBooleanTag(tags, 12),
            entityCategory: toEntityCategory(getIntTag(tags, 13))
    ]
}

private static Map espHomeListEntitiesSirenResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'siren',
            icon: getStringTag(tags, 5),
            disabledByDefault: getBooleanTag(tags, 6),
            tones: getStringTagList(tags, 7),
            supportsDuration: getBooleanTag(tags, 8),
            supportsVolume: getBooleanTag(tags, 9),
            entityCategory: toEntityCategory(getIntTag(tags, 10))
    ]
}

private static Map espHomeListEntitiesSwitchResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'switch',
            icon: getStringTag(tags, 5),
            assumedState: getBooleanTag(tags, 6),
            disabledByDefault: getBooleanTag(tags, 7),
            entityCategory: toEntityCategory(getIntTag(tags, 8)),
            deviceClass: getStringTag(tags, 9)
    ]
}

private static Map espHomeListEntitiesTextSensorResponse(Map tags) {
    return parseEntity(tags) + [
            type: 'entity',
            platform: 'text',
            icon: getStringTag(tags, 5),
            disabledByDefault: getBooleanTag(tags, 6),
            entityCategory: toEntityCategory(getIntTag(tags, 7))
    ]
}

private void espHomeListEntitiesDoneResponse() {
    espHomeSubscribeStatesRequest()
    espHomeSubscribeLogs(settings.logEnable ? LOG_LEVEL_DEBUG : LOG_LEVEL_INFO)
    sendMessageRetry()
}

private void espHomeLockCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_LOCK_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ],
                2: [ tags.lockCommand as Integer, WIRETYPE_VARINT ],
                3: [ tags.code != null ? 1 : 0, WIRETYPE_VARINT ],
                4: [ tags.code as String, WIRETYPE_LENGTH_DELIMITED ]
        ], MSG_LOCK_STATE_RESPONSE)
    }
}

private static Map espHomeLockState(Map tags) {
    return [
            type: 'state',
            platform: 'lock',
            key: getLongTag(tags, 1),
            state: getIntTag(tags, 2),
    ]
}

private void espHomeMediaPlayerCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_MEDIA_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ],
                2: [ tags.mediaPlayerCommand != null ? 1 : 0, WIRETYPE_VARINT ],
                3: [ tags.mediaPlayerCommand as Integer, WIRETYPE_VARINT ],
                4: [ tags.volume != null ? 1 : 0, WIRETYPE_VARINT ],
                5: [ tags.volume as Float, WIRETYPE_FIXED32 ],
                6: [ tags.mediaUrl != null ? 1 : 0, WIRETYPE_VARINT ],
                7: [ tags.mediaUrl as String, WIRETYPE_LENGTH_DELIMITED ]
        ], MSG_MEDIA_STATE_RESPONSE)
    }
}

private static Map espHomeMediaPlayerState(Map tags) {
    return [
            type: 'state',
            platform: 'media_player',
            key: getLongTag(tags, 1),
            state: getIntTag(tags, 2),
            volume: getFloatTag(tags, 3),
            muted: getBooleanTag(tags, 4)
    ]
}

private void espHomeNumberCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_NUMBER_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ],
                2: [ tags.state as Float, WIRETYPE_FIXED32 ]
        ])
    }
}

private static Map espHomeNumberState(Map tags) {
    return [
            type: 'state',
            platform: 'number',
            key: getLongTag(tags, 1),
            state: getFloatTag(tags, 2),
            hasState: getBooleanTag(tags, 3, true)
    ]
}

private void espHomePingRequest() {
    if (logEnable) { log.trace 'ESPHome ping request sent to device' }
    sendMessage(
            MSG_PING_REQUEST, [:],
            MSG_PING_RESPONSE, 'espHomePingResponse'
    )
}

private void espHomePingResponse(Map tags) {
    device.updateDataValue 'Last Ping Response', new Date().toString()
    if (logEnable) { log.trace 'ESPHome ping response received from device' }
    espHomeSchedulePing()
}

private void espHomeSchedulePing() {
    int jitter = (int) Math.ceil(PING_INTERVAL_SECONDS * 0.2)
    int interval = PING_INTERVAL_SECONDS + new Random().nextInt(jitter)
    runIn(interval, 'espHomePingRequest')
}

private static Map espHomeSensorState(Map tags) {
    return [
            type: 'state',
            platform: 'sensor',
            key: getLongTag(tags, 1),
            state: getFloatTag(tags, 2),
            hasState: getBooleanTag(tags, 3, true)
    ]
}

private void espHomeSirenCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_SIREN_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ],
                2: [ tags.state != null ? 1 : 0, WIRETYPE_VARINT ],
                3: [ tags.state ? 1 : 0, WIRETYPE_VARINT ],
                4: [ tags.tone != null ? 1 : 0, WIRETYPE_VARINT ],
                5: [ tags.tone as String, WIRETYPE_LENGTH_DELIMITED ],
                6: [ tags.duration != null ? 1 : 0, WIRETYPE_VARINT ],
                7: [ tags.duration as Integer, WIRETYPE_VARINT ],
                8: [ tags.volume != null ? 1 : 0, WIRETYPE_VARINT ],
                9: [ tags.volume as Float, WIRETYPE_FIXED32 ]
        ], MSG_SIREN_STATE_RESPONSE)
    }
}

private static Map espHomeSirenState(Map tags) {
    return [
            type: 'state',
            platform: 'siren',
            key: getLongTag(tags, 1),
            state: getIntTag(tags, 2)
    ]
}

private void espHomeSwitchCommand(Map tags) {
    if (tags.key) {
        sendMessage(MSG_SWITCH_COMMAND_REQUEST, [
                1: [ tags.key as Integer, WIRETYPE_FIXED32 ],
                2: [ tags.state != null ? 1 : 0, WIRETYPE_VARINT ],
        ], MSG_SWITCH_STATE_RESPONSE)
    }
}

private static Map espHomeSwitchState(Map tags) {
    return [
            type: 'state',
            platform: 'switch',
            key: getLongTag(tags, 1),
            state: getBooleanTag(tags, 2)
    ]
}

private void espHomeSubscribeLogs(Integer logLevel, Boolean dumpConfig = true) {
    sendMessage(MSG_SUBSCRIBE_LOGS_REQUEST, [
            1: [ logLevel as Integer, WIRETYPE_VARINT ],
            2: [ dumpConfig ? 1 : 0, WIRETYPE_VARINT ]
    ])
}

private void espHomeSubscribeLogsResponse(Map tags) {
    String message = getStringTag(tags, 3).replaceAll(/\x1b\[[0-9;]*m/, '')
    switch (getIntTag(tags, 1)) {
        case LOG_LEVEL_ERROR:
            log.error message
            break
        case LOG_LEVEL_WARN:
            log.warn message
            break
        case LOG_LEVEL_INFO:
            log.info message
            break
        case LOG_LEVEL_VERY_VERBOSE:
            log.trace message
        default:
            log.debug message
            break
    }
}

private void espHomeSubscribeStatesRequest() {
    sendMessage(MSG_SUBSCRIBE_STATES_REQUEST)
}

private static Map espHomeTextSensorState(Map tags) {
    return [
            type: 'state',
            platform: 'text',
            key: getLongTag(tags, 1),
            state: getStringTag(tags, 2),
            hasState: getBooleanTag(tags, 3, true)
    ]
}

private static Map parseEntity(Map tags) {
    return [
            objectId: getStringTag(tags, 1),
            key: getLongTag(tags, 2),
            name: getStringTag(tags, 3),
            uniqueId: getStringTag(tags, 4)
    ]
}

private static String toEntityCategory(int value) {
    switch (value) {
        case ENTITY_CATEGORY_NONE: return 'none'
        case ENTITY_CATEGORY_CONFIG: return 'config'
        case ENTITY_CATEGORY_DIAGNOSTIC: return 'diagnostic'
        default: return value
    }
}

private static List<String> toCapabilities(int capability) {
    List<String> capabilities = []
    if (capability & COLOR_CAP_ON_OFF) { capabilities.add('ON/OFF') }
    if (capability & COLOR_CAP_BRIGHTNESS) { capabilities.add('BRIGHTNESS') }
    if (capability & COLOR_CAP_RGB) { capabilities.add('RGB') }
    if (capability & COLOR_CAP_WHITE) { capabilities.add('WHITE') }
    if (capability & COLOR_CAP_COLD_WARM_WHITE) { capabilities.add('COLD WARM WHITE') }
    if (capability & COLOR_CAP_COLOR_TEMPERATURE) { capabilities.add('COLOR TEMPERATURE') }
    return capabilities
}


/**
 * ESPHome Native API Plaintext Socket IO Implementation
 */
private void openSocket() {
    log.info "ESPHome opening socket to ${ipAddress}:${PORT_NUMBER}"
    setNetworkStatus('connecting')
    try {
        interfaces.rawSocket.connect(settings.ipAddress, PORT_NUMBER, byteInterface: true)
        runInMillis(200, 'espHomeHelloRequest')
    } catch (e) {
        log.error "ESPHome error opening socket: " + e
        scheduleConnect()
    }
}

private void closeSocket(String reason) {
    unschedule('espHomePingRequest')
    unschedule('sendMessageRetry')
    espReceiveBuffer.remove(device.id)
    log.info "ESPHome closing socket to ${ipAddress}:${PORT_NUMBER} (${reason})"
    if (!isOffline()) {
        sendMessage(MSG_DISCONNECT_REQUEST)
    }
    interfaces.rawSocket.disconnect()
    setNetworkStatus('offline', reason)
}

private byte[] decodeMessage(String hexString) {
    byte[] bytes
    String buffer = espReceiveBuffer.get(device.id)
    if (buffer) {
        bytes = HexUtils.hexStringToByteArray(buffer + hexString)
        espReceiveBuffer.remove(device.id)
    } else {
        bytes = HexUtils.hexStringToByteArray(hexString)
    }
    return bytes
}

private String encodeMessage(int type, Map tags = [:]) {
    // creates hex string payload from message type and tags
    ByteArrayOutputStream payload = new ByteArrayOutputStream()
    int length = tags ? protobufEncode(payload, tags) : 0
    ByteArrayOutputStream stream = new ByteArrayOutputStream()
    stream.write(0x00)
    writeVarInt(stream, length)
    writeVarInt(stream, type)
    payload.writeTo(stream)
    return HexUtils.byteArrayToHexString(stream.toByteArray())
}

private boolean isOffline() {
    return device.currentValue(NETWORK_ATTRIBUTE) == 'offline'
}

// parse received protobuf messages - do not change this function name or driver will break
public void parse(String hexString) {
    ByteArrayInputStream stream = new ByteArrayInputStream(decodeMessage(hexString))
    int b
    while ((b = stream.read()) != -1) {
        if (b == 0x00) {
            stream.mark(0)
            long length = readVarInt(stream, true)
            int available = stream.available()
            if (length > available) {
                stream.reset()
                available = stream.available()
                byte[] bufferArray = new byte[available + 1]
                stream.read(bufferArray, 1, available)
                espReceiveBuffer.put(device.id, HexUtils.byteArrayToHexString(bufferArray))
                return
            }
            parseMessage(stream, length)
        } else if (b == 0x01) {
            log.error 'Driver does not support ESPHome native API encryption'
            return
        } else {
            log.warn "ESPHome expecting delimiter 0x00 but got 0x${Integer.toHexString(b)} instead"
            return
        }
    }
}

private void scheduleConnect() {
    state.reconnectDelay = (state.reconnectDelay ?: 1) * 2
    if (state.reconnectDelay > 60) { state.reconnectDelay = 60 }
    int jitter = (int) Math.ceil(state.reconnectDelay * 0.2)
    int interval = state.reconnectDelay + new Random().nextInt(jitter)
    log.info "ESPHome reconnecting in ${interval} seconds"
    runIn(interval, 'openSocket')
}

private void sendMessage(int msgType, Map tags = [:]) {
    interfaces.rawSocket.sendMessage(encodeMessage(msgType, tags))
}

private void sendMessage(int msgType, Map tags, int expectedMsgType, String onSuccess = '') {
    espSentQueue.computeIfAbsent(device.id) { k -> new ConcurrentLinkedQueue<Map>() }.add([
            msgType: msgType,
            tags: tags,
            expectedMsgType: expectedMsgType,
            onSuccess: onSuccess,
            retries: SEND_RETRY_COUNT
    ])
    if (!isOffline()) {
        sendMessage(msgType, tags)
        runInMillis(SEND_RETRY_MILLIS, 'sendMessageRetry')
    }
}

private void setNetworkStatus(String state, String reason = '') {
    sendEvent([ name: NETWORK_ATTRIBUTE, value: state, descriptionText: reason ?: "${device} is ${state}" ])
}

// parse received socket status - do not change this function name or driver will break
public void socketStatus(String message) {
    if (message.contains('error')) {
        log.error "ESPHome socket error: ${message}"
        closeSocket(message)
        scheduleConnect()
    } else {
        log.info "ESPHome socket status: ${message}"
    }
}

private void sendMessageRetry() {
    ConcurrentLinkedQueue<Map> sentQueue = espSentQueue.get(device.id)
    if (sentQueue) {
        // retry outstanding messages and decrement retry counter
        sentQueue.removeIf { entry ->
            if (entry.retries > 0) {
                entry.retries--
                log.info "ESPHome retrying message type #${entry.msgType} (retries left ${entry.retries})"
                sendMessage(entry.msgType, entry.tags)
                return false
            } else {
                log.info "ESPHome message type #${entry.msgType} retries exceeded"
                closeSocket('message retries exceeded')
                scheduleConnect()
                return true
            }
        }

        // reschedule check if there are outstanding messages
        if (!sentQueue.isEmpty()) {
            runInMillis(SEND_RETRY_MILLIS, 'sendMessageRetry')
        }
    }
}

private boolean supervisionCheck(int msgType, Map tags = [:]) {
    ConcurrentLinkedQueue<Map> sentQueue = espSentQueue.get(device.id)
    // check for successful responses and remove from queue
    boolean result
    if (sentQueue) {
        result = sentQueue.removeIf { entry ->
            if (entry.expectedMsgType == msgType) {
                if (entry.onSuccess) {
                    if (logEnable) { log.trace "ESPHome executing ${entry.onSuccess}" }
                    "${entry.onSuccess}"(tags)
                }
                return true
            } else {
                return false
            }
        }
        if (sentQueue.isEmpty()) {
            unschedule('sendMessageRetry')
        }
    }
    return result
}

/**
 * Minimal Protobuf Implementation for use with ESPHome
 */
@Field static final int WIRETYPE_VARINT = 0
@Field static final int WIRETYPE_FIXED64 = 1
@Field static final int WIRETYPE_LENGTH_DELIMITED = 2
@Field static final int WIRETYPE_FIXED32 = 5
@Field static final int VARINT_MAX_BYTES = 10

private Map protobufDecode(ByteArrayInputStream stream, long available) {
    Map tags = [:]
    while (available > 0) {
        long tagAndType = readVarInt(stream, true)
        if (tagAndType == -1) {
            log.warn 'ESPHome unexpected EOF decoding protobuf message'
            break
        }
        available -= getVarIntSize(tagAndType)
        int wireType = ((int) tagAndType) & 0x07
        Integer tag = (int) (tagAndType >>> 3)
        switch (wireType) {
            case WIRETYPE_VARINT:
                Long val = readVarInt(stream, false)
                available -= getVarIntSize(val)
                tags.computeIfAbsent(tag) { k -> [] }.add(val)
                break
            case WIRETYPE_FIXED32:
            case WIRETYPE_FIXED64:
                Long val = 0
                int shift = 0
                int count = (wireType == WIRETYPE_FIXED32) ? 4 : 8
                available -= count
                while (count-- > 0) {
                    long l = stream.read()
                    val |= l << shift
                    shift += 8
                }
                tags.computeIfAbsent(tag) { k -> [] }.add(val)
                break
            case WIRETYPE_LENGTH_DELIMITED:
                int total = (int) readVarInt(stream, false)
                available -= getVarIntSize(total)
                available -= total
                byte[] val = new byte[total]
                int pos = 0
                while (pos < total) {
                    int count = stream.read(val, pos, total - pos)
                    if (count < (total - pos)) {
                        log.warn 'ESPHome unexpected EOF decoding protobuf message'
                        break
                    }
                    pos += count
                }
                tags.computeIfAbsent(tag) { k -> [] }.add(val)
                break
            default:
                log.warn("Protobuf unknown wire type ${wireType}")
                break
        }
    }
    return tags
}

private int protobufEncode(ByteArrayOutputStream stream, Map tags) {
    int bytes = 0
    for (entry in new TreeMap(tags).findAll { k, v -> v instanceof List && v[0] }) {
        int fieldNumber = entry.key as int
        int wireType = entry.value[1] as int ?: WIRETYPE_VARINT
        switch (entry.value[0]) {
            case Float:
                entry.value[0] = Float.floatToRawIntBits(entry.value[0])
                break
            case Double:
                entry.value[0] = Double.doubleToRawLongBits(entry.value[0])
                break
        }
        int tag = (fieldNumber << 3) | wireType
        bytes += writeVarInt(stream, tag)
        switch (wireType) {
            case WIRETYPE_VARINT:
                long v = entry.value[0] as long
                bytes += writeVarInt(stream, v)
                break
            case WIRETYPE_LENGTH_DELIMITED:
                byte[] v = entry.value[0] as byte[]
                bytes += writeVarInt(stream, v.size())
                stream.write(v)
                bytes += v.size()
                break
            case WIRETYPE_FIXED32:
                int v = entry.value[0] as int
                for (int b = 0; b < 4; b++) {
                    stream.write((int) (v & 0x0ff))
                    bytes++
                    v >>= 8
                }
                break
            case WIRETYPE_FIXED64:
                long v = entry.value[0] as long
                for (int b = 0; b < 8; b++) {
                    stream.write((int) (v & 0x0ff))
                    bytes++
                    v >>= 8
                }
                break
            default:
                log.warn "ESPHome invalid wiretype for field ${fieldNumber} (${wireType})"
                break
        }
    }
    return bytes
}

private static boolean getBooleanTag(Map tags, int index, boolean invert = false) {
    return tags && tags[index] && tags[index][0] ? !invert : invert
}

private static double getDoubleTag(Map tags, int index, double defaultValue = 0f) {
    return tags && tags[index] ? Double.longBitsToDouble(tags[index][0] as long) : defaultValue
}

private static float getFloatTag(Map tags, int index, float defaultValue = 0f) {
    return tags && tags[index] ? Float.intBitsToFloat(tags[index][0] as int) : defaultValue
}

private static int getIntTag(Map tags, int index, int defaultValue = 0) {
    return tags && tags[index] ? tags[index][0] as int : defaultValue
}

private static List<Integer> getIntTagList(Map tags, int index) {
    return tags && tags[index] ? tags[index] as Integer[] : []
}

private static long getLongTag(Map tags, int index, long defaultValue = 0) {
    return tags && tags[index] ? tags[index][0] as long : defaultValue
}

private static String getStringTag(Map tags, int index, String defaultValue = '') {
    return tags && tags[index] ? new String(tags[index][0] as byte[], 'UTF-8') : defaultValue
}

private static List<String> getStringTagList(Map tags, int index) {
    return tags && tags[index] ? tags[index].collect { s -> new String(s as byte[], 'UTF-8') } : []
}

private static int getVarIntSize(long i) {
    if (i < 0) {
        return VARINT_MAX_BYTES
    }
    int size = 1
    while (i >= 128) {
        size++
        i >>= 7
    }
    return size
}

private static long readVarInt(ByteArrayInputStream stream, boolean permitEOF) {
    long result = 0
    int shift = 0
    // max 10 byte wire format for 64 bit integer (7 bit data per byte)
    for (int i = 0; i < VARINT_MAX_BYTES; i++) {
        int b = stream.read()
        if (b == -1) {
            if (i == 0 && permitEOF) {
                return -1
            } else {
                return 0
            }
        }
        result |= ((long) (b & 0x07f)) << shift
        if ((b & 0x80) == 0) {
            break // get out early
        }
        shift += 7
    }
    return result
}

private static int writeVarInt(ByteArrayOutputStream stream, long value) {
    int count = 0
    for (int i = 0; i < VARINT_MAX_BYTES; i++) {
        int toWrite = (int) (value & 0x7f)
        value >>>= 7
        count++
        if (value == 0) {
            stream.write(toWrite)
            break;
        } else {
            stream.write(toWrite | 0x080)
        }
    }
    return count
}

private static long zigZagDecode(long v) {
    return (v >>> 1) ^ -(v & 1)
}

private static long zigZagEncode(long v) {
    return ((v << 1) ^ -(v >>> 63))
}

/**
 * ESPHome Protobuf Enumerations
 * https://github.com/esphome/aioesphomeapi/blob/main/aioesphomeapi/api.proto
 */
@Field static final int MSG_HELLO_REQUEST = 1
@Field static final int MSG_HELLO_RESPONSE = 2
@Field static final int MSG_CONNECT_REQUEST = 3
@Field static final int MSG_CONNECT_RESPONSE = 4
@Field static final int MSG_DISCONNECT_REQUEST = 5
@Field static final int MSG_DISCONNECT_RESPONSE = 6
@Field static final int MSG_PING_REQUEST = 7
@Field static final int MSG_PING_RESPONSE = 8
@Field static final int MSG_DEVICEINFO_REQUEST = 9
@Field static final int MSG_DEVICEINFO_RESPONSE = 10
@Field static final int MSG_LIST_ENTITIES_REQUEST = 11
@Field static final int MSG_LIST_BINARYSENSOR_RESPONSE = 12
@Field static final int MSG_LIST_COVER_RESPONSE = 13
@Field static final int MSG_LIST_FAN_RESPONSE = 14
@Field static final int MSG_LIST_LIGHT_RESPONSE = 15
@Field static final int MSG_LIST_SENSOR_RESPONSE = 16
@Field static final int MSG_LIST_SWITCH_RESPONSE = 17
@Field static final int MSG_LIST_TEXT_SENSOR_RESPONSE = 18
@Field static final int MSG_LIST_ENTITIES_RESPONSE = 19
@Field static final int MSG_SUBSCRIBE_STATES_REQUEST = 20
@Field static final int MSG_BINARY_SENSOR_STATE_RESPONSE = 21
@Field static final int MSG_COVER_STATE_RESPONSE = 22
@Field static final int MSG_FAN_STATE_RESPONSE = 23
@Field static final int MSG_LIGHT_STATE_RESPONSE = 24
@Field static final int MSG_SENSOR_STATE_RESPONSE = 25
@Field static final int MSG_SWITCH_STATE_RESPONSE = 26
@Field static final int MSG_TEXT_SENSOR_STATE_RESPONSE = 27
@Field static final int MSG_SUBSCRIBE_LOGS_REQUEST = 28
@Field static final int MSG_SUBSCRIBE_LOGS_RESPONSE = 29
@Field static final int MSG_COVER_COMMAND_REQUEST = 30
@Field static final int MSG_FAN_COMMAND_REQUEST = 31
@Field static final int MSG_LIGHT_COMMAND_REQUEST = 32
@Field static final int MSG_SWITCH_COMMAND_REQUEST = 33
@Field static final int MSG_GET_TIME_REQUEST = 36
@Field static final int MSG_GET_TIME_RESPONSE = 37
@Field static final int MSG_LIST_SERVICES_RESPONSE = 41
@Field static final int MSG_EXECUTE_SERVICE_REQUEST = 42
@Field static final int MSG_LIST_CAMERA_RESPONSE = 43
@Field static final int MSG_CAMERA_IMAGE_RESPONSE = 44
@Field static final int MSG_CAMERA_IMAGE_REQUEST = 45
@Field static final int MSG_LIST_CLIMATE_RESPONSE = 46
@Field static final int MSG_CLIMATE_STATE_RESPONSE = 47
@Field static final int MSG_CLIMATE_COMMAND_REQUEST = 48
@Field static final int MSG_LIST_NUMBER_RESPONSE = 49
@Field static final int MSG_NUMBER_STATE_RESPONSE = 50
@Field static final int MSG_NUMBER_COMMAND_REQUEST = 51
@Field static final int MSG_LIST_SELECT_RESPONSE = 52
@Field static final int MSG_SELECT_STATE_RESPONSE = 53
@Field static final int MSG_SELECT_COMMAND_REQUEST = 54
@Field static final int MSG_LIST_SIREN_RESPONSE = 55
@Field static final int MSG_SIREN_STATE_RESPONSE = 56
@Field static final int MSG_SIREN_COMMAND_REQUEST = 57
@Field static final int MSG_LIST_LOCK_RESPONSE = 58
@Field static final int MSG_LOCK_STATE_RESPONSE = 59
@Field static final int MSG_LOCK_COMMAND_REQUEST = 60
@Field static final int MSG_LIST_BUTTON_RESPONSE = 61
@Field static final int MSG_BUTTON_COMMAND_REQUEST = 62
@Field static final int MSG_LIST_MEDIA_RESPONSE = 63
@Field static final int MSG_MEDIA_STATE_RESPONSE = 64
@Field static final int MSG_MEDIA_COMMAND_REQUEST = 65
@Field static final int MSG_SUBSCRIBE_BTLE_REQUEST = 66
@Field static final int MSG_BTLE_RESPONSE = 67

@Field static final int ENTITY_CATEGORY_NONE = 0
@Field static final int ENTITY_CATEGORY_CONFIG = 1
@Field static final int ENTITY_CATEGORY_DIAGNOSTIC = 2

@Field static final int COVER_OPERATION_IDLE = 0
@Field static final int COVER_OPERATION_IS_OPENING = 1
@Field static final int COVER_OPERATION_IS_CLOSING = 2

@Field static final int FAN_SPEED_LOW = 0
@Field static final int FAN_SPEED_MEDIUM = 1
@Field static final int FAN_SPEED_HIGH = 2

@Field static final int FAN_DIRECTION_FORWARD = 0
@Field static final int FAN_DIRECTION_REVERSE = 1

@Field static final int STATE_CLASS_NONE = 0
@Field static final int STATE_CLASS_MEASUREMENT = 1
@Field static final int STATE_CLASS_TOTAL_INCREASING = 2
@Field static final int STATE_CLASS_TOTAL = 3

@Field static final int LAST_RESET_NONE = 0
@Field static final int LAST_RESET_NEVER = 1
@Field static final int LAST_RESET_AUTO = 2

@Field static final int CLIMATE_MODE_OFF = 0
@Field static final int CLIMATE_MODE_HEAT_COOL = 1
@Field static final int CLIMATE_MODE_COOL = 2
@Field static final int CLIMATE_MODE_HEAT = 3
@Field static final int CLIMATE_MODE_FAN_ONLY = 4
@Field static final int CLIMATE_MODE_DRY = 5
@Field static final int CLIMATE_MODE_AUTO = 6

@Field static final int CLIMATE_FAN_ON = 0
@Field static final int CLIMATE_FAN_OFF = 1
@Field static final int CLIMATE_FAN_AUTO = 2
@Field static final int CLIMATE_FAN_LOW = 3
@Field static final int CLIMATE_FAN_MEDIUM = 4
@Field static final int CLIMATE_FAN_HIGH = 5
@Field static final int CLIMATE_FAN_MIDDLE = 6
@Field static final int CLIMATE_FAN_FOCUS = 7
@Field static final int CLIMATE_FAN_DIFFUSE = 8

@Field static final int CLIMATE_SWING_OFF = 0
@Field static final int CLIMATE_SWING_BOTH = 1
@Field static final int CLIMATE_SWING_VERTICAL = 2
@Field static final int CLIMATE_SWING_HORIZONTAL = 3

@Field static final int CLIMATE_ACTION_OFF = 0
@Field static final int CLIMATE_ACTION_COOLING = 2
@Field static final int CLIMATE_ACTION_HEATING = 3
@Field static final int CLIMATE_ACTION_IDLE = 4
@Field static final int CLIMATE_ACTION_DRYING = 5
@Field static final int CLIMATE_ACTION_FAN = 6

@Field static final int CLIMATE_PRESET_NONE = 0
@Field static final int CLIMATE_PRESET_HOME = 1
@Field static final int CLIMATE_PRESET_AWAY = 2
@Field static final int CLIMATE_PRESET_BOOST = 3
@Field static final int CLIMATE_PRESET_COMFORT = 4
@Field static final int CLIMATE_PRESET_ECO = 5
@Field static final int CLIMATE_PRESET_SLEEP = 6
@Field static final int CLIMATE_PRESET_ACTIVITY = 7

@Field static final int LOCK_STATE_NONE = 0
@Field static final int LOCK_STATE_LOCKED = 1
@Field static final int LOCK_STATE_UNLOCKED = 2
@Field static final int LOCK_STATE_JAMMED = 3
@Field static final int LOCK_STATE_LOCKING = 4
@Field static final int LOCK_STATE_UNLOCKING = 5

@Field static final int LOCK_UNLOCK = 0
@Field static final int LOCK_LOCK = 1
@Field static final int LOCK_OPEN = 2

@Field static final int MEDIA_PLAYER_STATE_NONE = 0
@Field static final int MEDIA_PLAYER_STATE_IDLE = 1
@Field static final int MEDIA_PLAYER_STATE_PLAYING = 2
@Field static final int MEDIA_PLAYER_STATE_PAUSED = 3

@Field static final int MEDIA_PLAYER_COMMAND_PLAY = 0
@Field static final int MEDIA_PLAYER_COMMAND_PAUSE = 1
@Field static final int MEDIA_PLAYER_COMMAND_STOP = 2
@Field static final int MEDIA_PLAYER_COMMAND_MUTE = 3
@Field static final int MEDIA_PLAYER_COMMAND_UNMUTE = 4

@Field static final int LOG_LEVEL_NONE = 0
@Field static final int LOG_LEVEL_ERROR = 1
@Field static final int LOG_LEVEL_WARN = 2
@Field static final int LOG_LEVEL_INFO = 3
@Field static final int LOG_LEVEL_CONFIG = 4
@Field static final int LOG_LEVEL_DEBUG = 5
@Field static final int LOG_LEVEL_VERBOSE = 6
@Field static final int LOG_LEVEL_VERY_VERBOSE = 7

@Field static final int COLOR_CAP_ON_OFF = 1
@Field static final int COLOR_CAP_BRIGHTNESS = 2
@Field static final int COLOR_CAP_WHITE = 4
@Field static final int COLOR_CAP_COLOR_TEMPERATURE = 8
@Field static final int COLOR_CAP_COLD_WARM_WHITE = 16
@Field static final int COLOR_CAP_RGB = 32
