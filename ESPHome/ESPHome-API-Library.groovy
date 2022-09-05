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
    name: 'espHomeApi',
    namespace: 'esphome',
    author: 'jb@nrgup.net',
    description: 'ESPHome Native Protobuf API'
)

import groovy.transform.Field
import hubitat.helper.HexUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * espHomeHome API Message Implementation
 * https://github.com/esphome/aioesphomeapi/blob/main/aioesphomeapi/api.proto
 */

@Field static final int PING_INTERVAL_SECONDS = 30
@Field static final int PING_TIMEOUT_SECONDS = 10
@Field static final int PORT_NUMBER = 6053
@Field static final int SEND_RETRY_COUNT = 3
@Field static final int SEND_RETRY_MILLIS = 2000

@Field static final ConcurrentHashMap<String, String> espReceiveBuffer = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, ConcurrentLinkedQueue> espHomeSentCmds = new ConcurrentHashMap<>()

public void espHomeCoverCommand(Map tags) {
    if (tags.key) {
        sendMessage(30, [
            1: (int) tags.key,
            4: tags.position != null,
            5: tags.position,
            6: tags.tilt != null,
            7: tags.tilt,
            8: tags.stop
        ])
    }
}

private void parseMessage(ByteArrayInputStream stream, long length) {
    int msgType = (int) readVarInt(stream, true)
    if (msgType < 1 || msgType > 65) {
        log.warn "ESPHome message type ${msgType} out of range, skipping"
        return
    }
    Map tags = length == 0 ? [:] : decodeProtobufMessage(stream, length)
    supervisionAck(msgType, tags)

    switch (msgType) {
        case MSG_DISCONNECT_REQUEST: // Device requests us to close connection
            closeSocket("requested by device")
            scheduleConnect()
            break
        case MSG_DISCONNECT_RESPONSE: // Device confirms our disconnect request
            // Both parties are required to close the connection after this message has been received.
            closeSocket("driver")
            break
        case MSG_PING_REQUEST: // Ping Request (from device)
            sendMessage(MSG_PING_RESPONSE)
            break
        case MSG_PING_RESPONSE: // Ping Response (from device)
            device.updateDataValue 'Last Ping Response', new Date().toString()
            log.info 'ESPHome ping response received'
            break
        case MSG_DEVICEINFO_RESPONSE: // Device Info Response
            espHomeDeviceInfoResponse(tags)
            break
        case 12: // List Entities Binary Sensor Response
            parse espHomeListEntitiesBinarySensorResponse(tags)
            break
        case 13: // List Entities Cover Response
            parse espHomeListEntitiesCoverResponse(tags)
            break
        case 14: // List Entities Fan Response
            parse espHomeListEntitiesFanResponse(tags)
            break
        case 15: // List Entities Light Response
            parse espHomeListEntitiesLightResponse(tags)
            break
        case 16: // List Entities Sensor Response
            parse espHomeListEntitiesSensorResponse(tags)
            break
        case 17: // List Entities Switch Response
            parse espHomeListEntitiesSwitchResponse(tags)
            break
        case 18: // List Entities Text Sensor Response
            parse espHomeListEntitiesTextSensorResponse(tags)
            break
        case 19: // List Entities Done Response
            espHomeListEntitiesDoneResponse()
            break
        case 21: // Binary Sensor State Response
            parse espHomeBinarySensorStateResponse(tags)
            break
        case 22: // Cover State Response
            parse espHomeCoverStateResponse(tags)
            break
        case 23: // Fan State Response
            parse espHomeFanStateResponse(tags)
            break
        case 24: // Light State Response
            parse espHomeLightStateResponse(tags)
            break
        case 25: // Sensor State Response
            parse espHomeSensorStateResponse(tags)
            break
        case 26: // Switch State Response
            parse espHomeSwitchStateResponse(tags)
            break
        case 27: // Text Sensor State Response
            parse espHomeTextSensorStateResponse(tags)
            break
        case 29: // Subscribe Logs Response
            espHomeSubscribeLogsResponse(tags)
            break
        case 36: // Get Time Request
            espHomeGetTimeRequest()
            break
        case 49: // List Entities Number Response
            espHomeListEntitiesNumberResponse(tags)
            break
        case 43: // List Entities Camera Response
            espHomeListEntitiesCameraResponse(tags)
            break
        case 44: // Camera Image Response
            espHomeCameraImageResponse(tags)
            break
        case 50: // Number State Response
            parse espHomeNumberStateResponse(tags)
            break
        case 55: // List Entities Siren Response
            espHomeListEntitiesSirenResponse(tags)
            break
        case 56: // Siren State Response
            parse espHomeSirenStateResponse(tags)
            break
        case 58: // List Entities Lock Response
            parse espHomeListEntitiesLockResponse(tags)
            break
        case 59: // Lock State Response
            parse espHomeLockStateResponse(tags)
            break
        case 61: // List Entities Button Response
            parse espHomeListEntitiesButtonResponse(tags)
            break
        case 63: // List Entities Media Player Response
            parse espHomeListEntitiesMediaPlayerResponse(tags)
            break
        case 64: // Media Player State Response
            parse espHomeMediaPlayerStateResponse(tags)
    }
}

private Map espHomeBinarySensorStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getBoolean(tags, 2),
        hasState: getBoolean(tags, 3, true)
    ]
}

private void espHomeButtonCommandRequest(Long key) {
    sendMessage(62, [ 1: (int) key ])
}

private void espHomeCameraImageRequest(Boolean single, Boolean stream = false) {
    sendMessage(45, [
        1: single,
        2: stream
    ])
}

private Map espHomeCameraImageResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        image: tags[2],
        done: getBoolean(tags, 3)
    ]
}

private void espHomeConnectRequest(String password = null) {
    // Message sent at the beginning of each connection to authenticate the client
    // Can only be sent by the client and only at the beginning of the connection
    sendMessage([
        msgType: MSG_CONNECT_REQUEST,
        tags: [ 1: password ],
        expectedTypes: [ MSG_CONNECT_RESPONSE ],
        onSuccess: 'espHomeConnectResponse',
        onFailed: 'espHomeConnectTimeout'
    ])
}

private void espHomeConnectResponse(Map tags) {
    // todo: check for invalid password

    setNetworkStatus('online')
    state.remove('reconnectDelay')
    schedulePing()

    // Step 3: Send Device Info Request
    espHomeDeviceInfoRequest()
}

private void espHomeConnectTimeout() {
    closeSocket('connect request timeout')
    scheduleConnect()
}

private Map espHomeCoverStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        legacyState: getInt(tags, 2), // legacy: state has been removed in 1.13
        position: getFloat(tags, 3),
        tilt: getFloat(tags, 4),
        currentOperation: getInt(tags, 5)
    ]
}

private void espHomeDeviceInfoRequest() {
    sendMessage(9)
}

private void espHomeDeviceInfoResponse(Map tags) {
    if (tags.containsKey(2)) {
        device.name = getString(tags, 2)
    }
    if (tags.containsKey(3)) {
        device.updateDataValue 'MAC Address', getString(tags, 3)
    }
    if (tags.containsKey(4)) {
        device.updateDataValue 'ESPHome Version', getString(tags, 4)
    }
    if (tags.containsKey(5)) {
        device.updateDataValue 'Compile Time', getString(tags, 5)
    }
    if (tags.containsKey(6)) {
        device.updateDataValue 'Board Model', getString(tags, 6)
    }
    if (tags.containsKey(8)) {
        device.updateDataValue 'Project Name', getString(tags, 8)
    }
    if (tags.containsKey(9)) {
        device.updateDataValue 'Project Version', getString(tags, 9)
    }
    if (tags.containsKey(10)) {
        device.updateDataValue 'Web Server', "http://${ipAddress}:${tags[10]}"
    }

    // Step 4: Get device entities
    espHomeListEntitiesRequest()
}

private void espHomeDisconnectRequest() {
    // Request to close the connection.
    // Can be sent by both the client and server
    sendMessage(5)
    runInMillis(250, 'closeSocket')
}

private void espHomeFanCommandRequest(Long key, Boolean state, Boolean oscillating = null, Integer direction = null, Integer speedLevel = null) {
    sendMessage(31, [
        1: (int) key,
        2: state != null,
        3: state,
        6: oscillating != null,
        7: oscillating,
        8: direction != null,
        9: direction,
        10: speedLevel != null,
        11: speedLevel
    ])
}

private Map espHomeFanStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getBoolean(tags, 2),
        oscillating: getBoolean(tags, 3),
        speed: getInt(tags, 4), // deprecated
        direction: getInt(tags, 5),
        speedLevel: getInt(tags, 6)
    ]
}

private void espHomeGetTimeRequest() {
    sendMessage(37, [ 1: (int) (new Date().getTime() / 1000) ])
}

private void espHomeHelloRequest() {
    // Step 1: Send the HelloRequest message
    // Can only be sent by the client and only at the beginning of the connection
    String client = "Hubitat ${location.hub.name}"
    sendMessage(1, [ 1: client ])

    sendMessage([
        msgType: MSG_HELLO_REQUEST,
        tags: [ 1: client ],
        expectedTypes: [ MSG_HELLO_RESPONSE ],
        onSuccess: 'espHomeHelloResponse',
        onFailed: 'espHomeHelloTimeout'
    ])
}

private void espHomeHelloResponse(Map tags) {
    // Confirmation of successful connection request.
    // Can only be sent by the server and only at the beginning of the connection
    if (tags.containsKey(1) && tags.containsKey(2)) {
        String version = tags[1] + '.' + tags[2]
        log.info "ESPHome API version: ${version}"
        device.updateDataValue 'API Version', version
        if (tags[1] > 1) {
            log.error 'ESPHome API version > 1 not supported - disconnecting'
            closeSocket('API version not supported')
            return
        }
    }

    String info = getString(tags, 3)
    if (info) {
        log.info "ESPHome server info: ${info}"
        device.updateDataValue 'Server Info', info
    }

    String name = getString(tags, 4)
    if (name) {
        log.info "ESPHome device name: ${name}"
        device.name = name
    }

    // Step 2: Send the ConnectRequest message
    espHomeConnectRequest(settings.password)
}

private void espHomeHelloTimeout() {
    closeSocket('hello request timeout')
    scheduleConnect()
}

private void espHomeLightCommandRequest(Long key, Boolean state, Float masterBrightness = null, Integer colorMode = null, Float colorBrightness = null,
        Float red = null, Float green = null, Float blue = null, Float white = null, Float colorTemperature = null, Float coldWhite = null, Float warmWhite = null, 
        Integer transitionLength = null, Boolean flashLength = null, String effect = null, Boolean effectSpeed = null) {
    sendMessage(32, [
        1: (int) key,
        2: state != null,
        3: state,
        4: masterBrightness != null,
        5: masterBrightness,
        6: red != null && blue != null && green != null,
        7: red,
        8: green,
        9: blue,
        10: white != null,
        11: white,
        12: colorTemperature != null,
        13: colorTemperature,
        14: transitionLength != null,
        15: transitionLength,
        16: flashLength != null,
        17: flashLength,
        18: effect != null,
        19: effect,
        20: colorBrightness != null,
        21: colorBrightness,
        22: colorMode != null,
        23: colorMode
    ])
}

private Map espHomeLightStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getBoolean(tags, 2),
        brightness: getFloat(tags, 3),
        colorMode: getInt(tags, 11),
        colorBrightness: getFloat(tags, 10),
        red: getFloat(tags, 4),
        green: getFloat(tags, 5),
        blue: getFloat(tags, 6),
        white: getFloat(tags, 7),
        colorTemperature: getFloat(tags, 8),
        coldWhite: getFloat(tags, 12),
        warmWhite: getFloat(tags, 13),
        effect: getString(tags, 9)
    ]
}

private void espHomeListEntitiesRequest() {
    sendMessage(11)
}

private Map espHomeListEntitiesBinarySensorResponse(Map tags) {
    return parseEntity(tags) + [
        isStatusBinarySensor: getBoolean(tags, 6),
        disabledByDefault: getBoolean(tags, 7),
        icon: getString(tags, 8),
        entityCategory: getInt(tags, 9)
    ]
}

private Map espHomeListEntitiesButtonResponse(Map tags) {
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        entityCategory: getInt(tags, 7),
        deviceClass: getString(tags, 8)
    ]
}

private Map espHomeListEntitiesCameraResponse(Map tags) {
    return parseEntity(tags) + [
        disabledByDefault: getBoolean(tags, 5),
        icon: getString(tags, 6),
        entityCategory: getInt(tags, 7)
    ]
}

private Map espHomeListEntitiesCoverResponse(Map tags) {
    return parseEntity(tags) + [
        assumedState: getBoolean(tags, 5),
        supportsPosition: getBoolean(tags, 6),
        supportsTilt: getBoolean(tags, 7),
        deviceClass: getString(tags, 8),
        disabledByDefault: getBoolean(tags, 9),
        icon: getString(tags, 10),
        entityCategory: getInt(tags, 11)
    ]
}

private Map espHomeListEntitiesLockResponse(Map tags) {
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        entityCategory: getInt(tags, 7),
        assumedState: getBoolean(tags, 8),
        supportsOpen: getBoolean(tags, 9),
        requiresCode: getBoolean(tags, 10),
        codeFormat: getString(tags, 11)
    ]
}

private Map espHomeListEntitiesFanResponse(Map tags) {
    return parseEntity(tags) + [
        supportsOscillation: getBoolean(tags, 5),
        supportsSpeed: getBoolean(tags, 6),
        supportsDirection: getBoolean(tags, 7),
        supportedSpeedLevels: getInt(tags, 8),
        disabledByDefault: getBoolean(tags, 9),
        icon: getString(tags, 10),
        entityCategory: getInt(tags, 11)
    ]
}

private Map espHomeListEntitiesLightResponse(Map tags) {
    return parseEntity(tags) + [
        supportedColorModes: tags[12],
        minMireds: getInt(tags, 9),
        maxMireds: getInt(tags, 10),
        effects: getString(tags, 11),
        disabledByDefault: getBoolean(tags, 13),
        icon: getString(tags, 14),
        entityCategory: getInt(tags, 15)
    ]
}

private Map espHomeListEntitiesMediaPlayerResponse(Map tags) {
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        entityCategory: getInt(tags, 7)
    ]
}

private Map espHomeListEntitiesNumberResponse(Map tags) {
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        minValue: getFloat(tags, 6),
        maxValue: getFloat(tags, 7),
        step: getFloat(tags, 8),
        disabledByDefault: getBoolean(tags, 9),
        entityCategory: getInt(tags, 10),
        unitOfMeasurement: getString(tags, 11),
        numberMode: getInt(tags, 12)
    ]
}

private Map espHomeListEntitiesSensorResponse(Map tags) {
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        unitOfMeasurement: getString(tags, 6),
        accuracyDecimals: getInt(tags, 7),
        forceUpdate: getBoolean(tags, 8),
        deviceClass: getString(tags, 9),
        sensorStateClass: getInt(tags, 10),
        lastResetType: getInt(tags, 11),
        disabledByDefault: getBoolean(tags, 12),
        entityCategory: getInt(tags, 13)
    ]
}

private Map espHomeListEntitiesSirenResponse(Map tags) {
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        // TODO repeated string: tones: getString(tags, 7),
        supportsDuration: getBoolean(tags, 8),
        supportsVolume: getBoolean(tags, 9),
        entityCategory: getInt(tags, 10)
    ]
}

private Map espHomeListEntitiesSwitchResponse(Map tags) {
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        assumedState: getBoolean(tags, 6),
        disabledByDefault: getBoolean(tags, 7),
        entityCategory: getInt(tags, 8),
        deviceClass: getString(tags, 9)
    ]
}

private Map espHomeListEntitiesTextSensorResponse(Map tags) {
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        entityCategory: getInt(tags, 7)
    ]
}

private void espHomeListEntitiesDoneResponse() {
    espHomeSubscribeStatesRequest()
    espHomeSubscribeLogsRequest(settings.logEnable ? LOG_LEVEL_DEBUG : LOG_LEVEL_INFO)
}

private void espHomeLockCommandRequest(Long key, Integer lockCommand, String code = null) {
    sendMessage(60, [
        1: (int) key,
        2: lockCommand,
        3: code != null,
        4: code
    ])
}

private Map espHomeLockStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getInt(tags, 2),
    ]
}

private void espHomeMediaPlayerCommandRequest(Long key, Integer mediaPlayerCommand = null, Float volume = null, String mediaUrl = null) {
    sendMessage(65, [
        1: (int) key,
        2: mediaPlayerCommand != null,
        3: mediaPlayerCommand,
        4: volume != null,
        5: volume,
        6: mediaUrl != null,
        7: mediaUrl
    ])
}

private Map espHomeMediaPlayerStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getInt(tags, 2),
        volume: getFloat(tags, 3),
        muted: getBoolean(tags, 4)
    ]
}

private void espHomeNumberCommandRequest(Long key, Float state) {
    sendMessage(51, [ 1: (int) key, 2: state ])
}

private Map espHomeNumberStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getFloat(tags, 2),
        hasState: getBoolean(tags, 3, true)
    ]
}

private void espHomePingRequest() {
    log.info "ESPHome ping request (device ping)"
    sendMessage([
        msgType: MSG_PING_REQUEST,
        expectedTypes: [ MSG_PING_RESPONSE ],
        onFailed: 'espHomePingTimeout'
    ])
}

private void espHomePingTimeout() {
    closeSocket('ping response timeout')
    scheduleConnect()
}

private Map espHomeSensorStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getFloat(tags, 2),
        hasState: getBoolean(tags, 3, true)
    ]
}

private void espHomeSirenCommandRequest(Long key, Boolean state, String tone = null, Integer duration = null, Float volume = null) {
    sendMessage(57, [
        1: (int) key,
        2: state != null,
        3: state,
        4: tone != null,
        5: tone,
        6: duration != null,
        7: duration,
        8: volume != null,
        9: volume
    ])
}

private Map espHomeSirenStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getInt(tags, 2)
    ]
}

private void espHomeSwitchCommandRequest(Long key, Boolean state) {
    sendMessage(33, [
        1: (int) key,
        2: state
    ])
}

private Map espHomeSwitchStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getBoolean(tags, 2)
    ]
}

private void espHomeSubscribeLogsRequest(Integer logLevel, Boolean dumpConfig = true) {
    sendMessage(28, [
        1: logLevel,
        2: dumpConfig ? 1 : 0
    ])
}

private void espHomeSubscribeLogsResponse(Map tags) {
    String message = getString(tags, 3).replaceAll(/\x1b\[[0-9;]*m/, '')
    switch (getInt(tags, 1)) {
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
    sendMessage(20)
}

private Map espHomeTextSensorStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getString(tags, 2),
        hasState: getBoolean(tags, 3, true)
    ]
}

private static Map parseEntity(Map tags) {
    return [
        objectId: getString(tags, 1),
        key: getLong(tags, 2),
        name: getString(tags, 3),
        uniqueId: getString(tags, 4)
    ]
}


/**
 * espHomeHome Native API Plaintext Socket IO Implementation
 */
private synchronized void closeSocket(String reason = 'socket closed') {
    unschedule('closeSocket')
    unschedule('espPingRequest')
    unschedule('supervisionCheck')
    log.info "ESPHome closing socket to ${ipAddress}:${PORT_NUMBER} (${reason})"
    interfaces.rawSocket.disconnect()
    espReceiveBuffer.remove(device.id)
    espHomeSentCmds.remove(device.id)
    setNetworkStatus('offline', reason)
}

private String encodeMessage(int type, Map tags = [:]) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream()
    int length = tags ? encodeProtobufMessage(payload, tags) : 0
    ByteArrayOutputStream stream = new ByteArrayOutputStream()
    stream.write(0x00)
    writeVarInt(stream, length)
    writeVarInt(stream, type)
    payload.writeTo(stream)
    return HexUtils.byteArrayToHexString(stream.toByteArray())
}

private void openSocket() {
    log.info "ESPHome opening socket to ${ipAddress}:${PORT_NUMBER}"
    setNetworkStatus('connecting')
    try {
        interfaces.rawSocket.connect(settings.ipAddress, PORT_NUMBER, byteInterface: true)
    } catch (e) {
        log.error "ESPHome error opening socket: " + e
        scheduleConnect()
        return
    }
    pauseExecution(100)
    espHomeHelloRequest()
}

// parse received protobuf messages
public void parse(String hexString) {
    byte[] bytes
    String buffer = espReceiveBuffer.get(device.id)
    if (buffer) {
        bytes = HexUtils.hexStringToByteArray(buffer + hexString)
        espReceiveBuffer.remove(device.id)
    } else {
        bytes = HexUtils.hexStringToByteArray(hexString)
    }

    int b
    ByteArrayInputStream stream = new ByteArrayInputStream(bytes)
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
            log.error 'Driver does not support espHomeHome native API encryption'
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

private void schedulePing() {
    int jitter = (int) Math.ceil(PING_INTERVAL_SECONDS * 0.2)
    int interval = PING_INTERVAL_SECONDS + new Random().nextInt(jitter)
    runIn(interval, 'espHomePingRequest')
}

private void sendMessage(int msgType, Map tags = [:]) {
    if (msgType < 1 || msgType > 65) {
        log.warn "ESPHome message type ${msgType} out of range, skipping"
        return
    }
    interfaces.rawSocket.sendMessage(encodeMessage(msgType, tags))
}

private void sendMessage(Map params = [:]) {
    Map options = [
        msgType: 0,
        tags: [:],
        expectedTypes: [],
        retries: SEND_RETRY_COUNT,
        onSuccess: '',
        onFailed: ''
    ]
    options << params
    espHomeSentCmds.computeIfAbsent(device.id) { k -> new ConcurrentLinkedQueue<Map>() }.add(options)
    sendMessage(options.msgType, options.tags)
    runInMillis(SEND_RETRY_MILLIS, 'supervisionCheck')
}

private void setNetworkStatus(String state, String reason = '') {
    sendEvent([ name: 'networkStatus', value: state, descriptionText: reason ?: "${device} is ${state}" ])
}

public void socketStatus(String message) {
    if (message.contains('error')) {
        log.error "ESPHome socket error: ${message}"
        closeSocket(message)
        scheduleConnect()
    } else {
        log.info "ESPHome socket status: ${message}"
    }
}

private void supervisionAck(int type, Map tags = [:]) {
    ConcurrentLinkedQueue<Map> sent = espHomeSentCmds.get(device.id)
    sent?.removeIf { options ->
        if (type in options.expectedTypes) {
            if (options.onSuccess) {
                runInMillis(0, options.onSuccess, [ data: tags ])
            }
            return true
        } else {
            return false
        }
    }
}

private void supervisionCheck() {
    ConcurrentLinkedQueue<Map> sent = espHomeSentCmds.get(device.id)
    if (sent) {
        List expired = []

        // retry outstanding messages and decrement retry counter
        sent.each { options ->
            if (options.retries > 0) {
                options.retries--
                log.info "ESPHome retrying message type #${options.msgType} (retries left ${options.retries})"
                sendMessage(options.msgType, options.tags)
            } else {
                log.info "ESPHome message type #${options.msgType} retries exceeded"
                expired.add(options)
            }
        }

        // for any expired messages call the onFailed handler if specified
        expired.forEach { options ->
            if (options.onFailed) {
                runInMillis(200, options.onFailed)
            }
        }

        // remove expired messages from sent commands
        sent.removeAll(expired)

        // reschedule check if there are outstanding messages
        if (!sent.isEmpty()) {
            runInMillis(SEND_RETRY_MILLIS, 'supervisionCheck')
        }
    }
}

/**
 * Minimal Protobuf Implementation for use with espHomeHome
 */
@Field static final int WIRETYPE_VARINT = 0
@Field static final int WIRETYPE_FIXED64 = 1
@Field static final int WIRETYPE_LENGTH_DELIMITED = 2
@Field static final int WIRETYPE_FIXED32 = 5
@Field static final int VARINT_MAX_BYTES = 10

private Map decodeProtobufMessage(ByteArrayInputStream stream, long available) {
    Map tags = [:]
    while (available > 0) {
        long tagAndType = readVarInt(stream, true)
        if (tagAndType == -1) {
            log.warn 'ESPHome unexpected EOF decoding protobuf message'
            break
        }
        available -= getVarIntSize(tagAndType)
        int wireType = ((int) tagAndType) & 0x07
        int tag = (int) (tagAndType >>> 3)
        switch (wireType) {
            case WIRETYPE_VARINT:
                long v = readVarInt(stream, false)
                available -= getVarIntSize(v)
                tags[tag] = v
                break
            case WIRETYPE_FIXED32:
            case WIRETYPE_FIXED64:
                long v = 0
                int shift = 0
                int count = (wireType == WIRETYPE_FIXED32) ? 4 : 8
                available -= count
                while (count-- > 0) {
                    long l = stream.read()
                    v |= l << shift
                    shift += 8
                }
                tags[tag] = v
                break
            case WIRETYPE_LENGTH_DELIMITED:
                int total = (int) readVarInt(stream, false)
                available -= getVarIntSize(total)
                available -= total
                byte[] data = new byte[total]
                int pos = 0
                while (pos < total) {
                    count = stream.read(data, pos, total - pos)
                    if (count < (total - pos)) {
                        log.warn 'ESPHome unexpected EOF decoding protobuf message'
                        break
                    }
                    pos += count
                }
                tags[tag] = data
                break
            default:
                log.warn("Protobuf unknown wire type ${wireType}")
                break
        }
    }
    return tags
}

private int encodeProtobufMessage(ByteArrayOutputStream stream, Map tags) {
    int bytes = 0
    for (entry in tags) {
        if (entry.value) {
            int fieldNumber = entry.key
            int wireType = entry.value instanceof String ? 2 : 0
            int tag = (fieldNumber << 3) | wireType
            bytes += writeVarInt(stream, tag)
            switch (wireType) {
                case WIRETYPE_VARINT:
                    bytes += writeVarInt(stream, (long) entry.value)
                    break
                case WIRETYPE_FIXED32:
                    int v = entry.value
                    for (int b = 0; b < 4; b++) {
                        stream.write((int) (v & 0x0ff))
                        bytes++
                        v >>= 8
                    }
                    break
                case WIRETYPE_FIXED64:
                    long v = entry.value
                    for (int b = 0; b < 8; b++) {
                        stream.write((int) (v & 0x0ff))
                        bytes++
                        v >>= 8
                    }
                    break
                case WIRETYPE_LENGTH_DELIMITED:
                    byte[] v = entry.value instanceof String ? entry.value.getBytes('UTF-8') : entry.value
                    bytes += writeVarInt(stream, v.size())
                    stream.write(v)
                    bytes += v.size()
                    break
            }
        }
    }
    return bytes
}

private static boolean getBoolean(Map tags, int index, boolean invert = false) {
    return tags && tags[index] ? !invert : invert
}

private static double getDouble(Map tags, int index, double defaultValue = 0.0) {
    return tags && tags[index] ? Double.intBitsToDouble(tags[index]) : defaultValue
}

private static float getFloat(Map tags, int index, float defaultValue = 0.0) {
    return tags && tags[index] ? Float.intBitsToFloat((int) tags[index]) : defaultValue
}

private static int getInt(Map tags, int index, int defaultValue = 0) {
    return tags && tags[index] ? (int) tags[index] : defaultValue
}

private static long getLong(Map tags, int index, long defaultValue = 0) {
    return tags && tags[index] ? tags[index] : defaultValue
}

private static String getString(Map tags, int index, String defaultValue = '') {
    return tags && tags[index] ? new String(tags[index], 'UTF-8') : defaultValue
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
 * espHomeHome Protobuf Enumerations
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
@Field static final int MSG_LISTENTITIES_REQUEST = 11
@Field static final int MSG_LISTENTITIES_RESPONSE = 19
@Field static final int MSG_SUBSCRIBESTATES_REQUEST = 11

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
