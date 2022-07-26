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
import groovy.transform.Field
import hubitat.helper.HexUtils

metadata {
    definition(name: 'ESPHome Protobuf POC', namespace: 'ESPHome', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'HealthCheck'
        capability 'Initialize'
        capability 'Refresh'

        attribute 'state', 'enum', [
            'connecting',
            'connected',
            'disconnecting',
            'disconnected'
        ]

        command 'connect'
        command 'disconnect'
    }

    preferences {
        input name: 'ipAddress',
                type: 'text',
                title: 'Device IP Address',
                required: true

        input name: 'portNumber',
                type: 'number',
                title: 'Port Number',
                range: 1..65535,
                defaultValue: '6053',
                required: true

        input name: 'password',
                type: 'text',
                title: 'Device Password (if required)',
                required: false

        input name: 'pingInterval',
                type: 'enum',
                title: 'Device Ping Interval',
                required: true,
                defaultValue: 30,
                options: [
                    15: '15 Seconds',
                    30: '30 Seconds',
                    60: 'Every minute',
                    120: 'Every 2 minutes'
                ]

        input name: 'logEnable',
                type: 'bool',
                title: 'Enable Debug Logging',
                required: false,
                defaultValue: false

        input name: 'logTextEnable',
              type: 'bool',
              title: 'Enable descriptionText logging',
              required: false,
              defaultValue: true
    }
}

void connect() {
    LOG.info "connecting to ESPHome API at ${ipAddress}:${portNumber}"
    String state = device.currentValue('state')
    if (state != 'disconnected') {
        closeSocket()
    }
    openSocket()
}

void disconnect() {
    String state = device.currentValue('state')
    sendEvent name: 'state', value: 'disconnecting'
    if (state == 'connected') {
        LOG.info 'disconnecting from ESPHome device'
        apiDisconnectRequest()
        runIn(5, 'closeSocket')
    } else {
        closeSocket()
    }
}

// Called when the device is started.
void initialize() {
    LOG.info "${device} driver initializing"

    unschedule()        // Remove all scheduled functions
    disconnect()        // Disconnect any existing connection

    // Schedule log disable for 30 minutes
    if (logEnable) {
        //runIn(1800, 'logsOff')
    }
}

// Called when the device is first created.
void installed() {
    LOG.info "${device} driver installed"
}

// Called to disable logging after timeout
void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    LOG.info "${device} debug logging disabled"
}

// parse received protobuf messages
void parse(String hexString) {
    LOG.debug "ESPHome << received raw payload: ${hexString}"
    ByteArrayInputStream stream = new ByteArrayInputStream(HexUtils.hexStringToByteArray(hexString))
    if (stream.available() < 3) {
        LOG.error "payload length too small (${stream.available()})"
        return
    }
    receive(stream)
}

void ping() {
    apiPingRequest()
}

void refresh() {
    LOG.info 'refreshing device entities'
    apiListEntitiesRequest()
}

// Socket status updates
void socketStatus(String message) {
    if (message.contains('error')) {
        log.error "${device} socket ${message}"
        closeSocket()
    } else {
        log.info "${device} socket ${message}"
    }
}

// Called when the device is removed.
void uninstalled() {
    disconnect()
    LOG.info "${device} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    LOG.info "${device} driver configuration updated"
    LOG.debug settings
    initialize()
}


/**
 * ESPHome API Message Implementation
 */
private void apiBinarySensorStateResponse(Map tags) {
    LOG.trace '[R] Binary Sensor State Response'
    long key = getLong(tags, 1)
    boolean state = getBoolean(tags, 2)
    boolean hasState = getBoolean(tags, 3, true)
    LOG.info "Binary Sensor [key: ${key}, state: ${state}, hasState: ${hasState}]"
}

private void apiConnectRequest(String password) {
    // Message sent at the beginning of each connection to authenticate the client
    // Can only be sent by the client and only at the beginning of the connection
    LOG.trace '[S] Connect Request'
    sendMessage(3, [ 1: password ])
}

private void apiConnectResponse() {
    // todo: check for invalid password
    LOG.trace '[R] Connect Response'
    sendEvent name: 'state', value: 'connected'

    // Step 3: Send Device Info Request
    apiDeviceInfoRequest()
}

private void apiCoverStateResponse(Map tags) {
    LOG.trace '[R] Cover State Response'
    long key = getLong(tags, 1)
    float position = getFloat(tags, 3)
    float tilt = getFloat(tags, 4)
    int currentOperation = getInt(tags, 5)
    LOG.info "Cover [key: ${key}, position: ${position}, tilt: ${tilt}, currentOperation: ${currentOperation}]"
}

private void apiDeviceInfoRequest() {
    LOG.trace '[S] Device Info Request'
    sendMessage(9)
}

private void apiDeviceInfoResponse(Map tags) {
    LOG.trace '[R] Device Info Response'
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

    // Step 4: Subscribe to state updates
    apiSubscribeStatesRequest()

    // Step 5: Schedule pings
    schedulePing()
}

private void apiDisconnectRequest() {
    // Request to close the connection.
    // Can be sent by both the client and server
    LOG.trace '[S] Disconnect Request'
    sendMessage(5)
}

private void apiHelloRequest() {
    // Step 1: Send the HelloRequest message
    // Can only be sent by the client and only at the beginning of the connection
    LOG.trace '[S] Hello Request'
    String client = "Hubitat ${location.hub.name}"
    sendMessage(1, [ 1: client ])
}

private void apiHelloResponse(Map tags) {
    // Confirmation of successful connection request.
    // Can only be sent by the server and only at the beginning of the connection
    LOG.trace '[R] Hello Response'
    if (tags.containsKey(1) && tags.containsKey(2)) {
        String version = tags[1] + '.' + tags[2]
        LOG.info "ESPHome API version: ${version}"
        device.updateDataValue 'API Version', version
        if (tags[1] > 1) {
            LOG.error 'ESPHome API version > 1 not supported - disconnecting'
            closeSocket()
            return
        }
    }

    String info = getString(tags, 3)
    if (info) {
        LOG.info "ESPHome server info: ${info}"
        device.updateDataValue 'Server Info', info
    }

    String name = getString(tags, 4)
    if (name) {
        LOG.info "ESPHome device name: ${name}"
        device.name = name
    }

    // Step 2: Send the ConnectRequest message
    apiConnectRequest(settings.password)
}

private void apiListEntitiesRequest() {
    LOG.trace '[S] List Entities Request'
    sendMessage(11)
}

private void apiListEntitiesBinarySensorResponse(tags) {
    LOG.trace '[R] List Entities Binary Sensor Response'
    String objectId = getString(tags, 1)
    long key = getLong(tags, 2)
    String name = getString(tags, 3)
    String uniqueId = getString(tags, 4)
    String deviceClass = getString(tags, 5)
    boolean isStatusBinarySensor = getBoolean(tags, 6)
    boolean disabledByDefault = getBoolean(tags, 7)
    String icon = getString(tags, 8)
    int entityCategory = getInt(tags, 9)
    LOG.info "binary sensor [objectId: ${objectId}, key: ${key}, name: ${name}, uniqueId: ${uniqueId}, deviceClass: ${deviceClass}, " +
        "isStatusBinarySensor: ${isStatusBinarySensor}, disabledByDefault: ${disabledByDefault}, icon: ${icon}, entityCategory: ${entityCategory}]"
}

private void apiListEntitiesCoverResponse(tags) {
    LOG.trace '[R] List Entities Cover Response'
    String objectId = getString(tags, 1)
    long key = getLong(tags, 2)
    String name = getString(tags, 3)
    String uniqueId = getString(tags, 4)
    boolean assumedState = getBoolean(tags, 5)
    boolean supportsPosition = getBoolean(tags, 6)
    boolean supportsTilt = getBoolean(tags, 7)
    String deviceClass = getString(tags, 8)
    boolean disabledByDefault = getBoolean(tags, 9)
    String icon = getString(tags, 10)
    int entityCategory = getInt(tags, 11)
    LOG.info "cover [objectId: ${objectId}, key: ${key}, name: ${name}, uniqueId: ${uniqueId}, deviceClass: ${deviceClass}, " +
        "disabledByDefault: ${disabledByDefault}, icon: ${icon}, entityCategory: ${entityCategory}]"
}

private void apiListEntitiesSensorResponse(tags) {
    LOG.trace '[R] List Entities Sensor Response'
    String objectId = getString(tags, 1)
    long key = getLong(tags, 2)
    String name = getString(tags, 3)
    String uniqueId = getString(tags, 4)
    String icon = getString(tags, 5)
    String unitOfMeasurement = getString(tags, 6)
    int accuracyDecimals = getInt(tags, 7)
    boolean forceUpdate = getBoolean(tags, 8)
    String deviceClass = getString(tags, 9)
    int sensorStateClass = getInt(tags, 10)
    int lastResetType = getInt(tags, 11)
    boolean disabledByDefault = getBoolean(tags, 12)
    int entityCategory = getInt(tags, 13)
    LOG.info "sensor [objectId: ${objectId}, key: ${key}, name: ${name}, uniqueId: ${uniqueId}, deviceClass: ${deviceClass}, " +
        "disabledByDefault: ${disabledByDefault}, icon: ${icon}, entityCategory: ${entityCategory}]"
}

private void apiListEntitiesDoneResponse() {
    LOG.trace '[R] List Entities Done Response'
}

private void apiPingRequest() {
    // Ping request can be sent by either party
    LOG.trace '[S] Ping Request'
    sendMessage(7)
    schedulePing()
}

private void apiPingResponse() {
    // Ping response can be sent by either party
    LOG.trace '[S] Ping Response'
    sendMessage(8)
}

private void apiSensorStateResponse(Map tags) {
    LOG.trace '[R] Sensor State Response'
    long key = getLong(tags, 1)
    float state = getFloat(tags, 2)
    boolean hasState = getBoolean(tags, 3, true)
    LOG.info "Sensor [key: ${key}, state: ${state}, hasState: ${hasState}]"
}

private void apiSubscribeStatesRequest() {
    LOG.trace '[S] Subscribe States Request'
    sendMessage(20)
}


/**
 * ESPHome Raw Socket IO Implementation
 */
private synchronized void closeSocket() {
    unschedule('closeSocket')
    unschedule('apiPingRequest')
    LOG.info "ESPHome closing socket to ${ipAddress}:${portNumber}"
    sendEvent name: 'state', value: 'disconnected'
    interfaces.rawSocket.disconnect()
}

private synchronized void openSocket() {
    sendEvent name: 'state', value: 'connecting'
    try {
        interfaces.rawSocket.connect(settings.ipAddress, (int) settings.portNumber, byteInterface: true)
    } catch (e) {
        LOG.exception "ESPHome error opening socket", e
        sendEvent name: 'state', value: 'disconnected'
        return
    }
    pauseExecution(100)
    apiHelloRequest()
}

private boolean isConnected() {
    return device.currentValue('state') == 'connected'
}

private void receive(ByteArrayInputStream stream) {
    int count = 1
    int b
    while ((b = stream.read()) != -1) {
        if (b == 0x00) {
            long length = readVarInt(stream, true)
            if (length != -1) {
                LOG.debug "ESPHome extracting protobuf message [count: ${count++}, length: ${length}]"
                parseMessage(stream, length)
            }
        } else {
            LOG.warn "ESPHome expecting delimiter 0x00 but got ${b} instead"
            return
        }
    }
}

private void parseMessage(ByteArrayInputStream stream, long length) {
    long msgType = readVarInt(stream, true)
    if (msgType < 1 || msgType > 65) {
        LOG.warn "ESPHome message type ${msgType} out of range, skipping"
        return
    }
    LOG.debug "ESPHome extracting message type ${msgType} (length ${length})"
    Map tags = length == 0 ? [:] : decodeProtobufMessage(stream, length)
    switch (msgType) {
        case 2:
            // Confirmation of successful connection request.
            // Can only be sent by the server and only at the beginning of the connection
            apiHelloResponse(tags)
            break
        case 4:
            // Confirmation of successful connection. After this the connection is available for all traffic.
            // Can only be sent by the server and only at the beginning of the connection
            apiConnectResponse()
            break
        case 5: // Device requests to close connection
            disconnect()
            break
        case 6: // Device confirms our disconnect request
            // Both parties are required to close the connection after this message has been received.
            closeSocket()
            break
        case 7: // Ping Request (from device)
            apiPingResponse()
            break
        case 8: // Ping Response (from device)
            unschedule('timeout')
            break
        case 10: // Device Info Response
            apiDeviceInfoResponse(tags)
            break
        case 12: // List Entities Binary Sensor Response
            apiListEntitiesBinarySensorResponse(tags)
            break
        case 13: // List Entities Cover Response
            apiListEntitiesCoverResponse(tags)
            break
        case 16: // List Entities Sensor Response
            apiListEntitiesSensorResponse(tags)
            break
        case 19: // List Entities Done Response
            apiListEntitiesDoneResponse()
            break
        case 21: // Binary Sensor State Response
            apiBinarySensorStateResponse(tags)
            break
        case 22: // Cover State Response
            apiCoverStateResponse(tags)
            break
        case 25: // Sensor State Response
            apiSensorStateResponse(tags)
            break
        default:
            LOG.warn "ESPHome message type ${msgType} not suppported"
            break
    }
}

private synchronized void schedulePing() {
    LOG.trace "ESPHome scheduling next device ping in ${settings.pingInterval} seconds"
    runIn(Integer.parseInt(settings.pingInterval), 'apiPingRequest')
}

private synchronized void sendMessage(int type, Map tags = [:]) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream()
    int length = tags ? encodeProtobufMessage(payload, tags) : 0
    ByteArrayOutputStream stream = new ByteArrayOutputStream()
    stream.write(0x00)
    writeVarInt(stream, length)
    writeVarInt(stream, type)
    payload.writeTo(stream)
    String output = HexUtils.byteArrayToHexString(stream.toByteArray())
    LOG.debug "ESPHome >> sending msg type ${type} with tags ${tags} as: ${output}"
    interfaces.rawSocket.sendMessage(output)
}


/**
 * Minimal Protobuf Implementation
 */
@Field static final int WIRETYPE_VARINT = 0;
@Field static final int WIRETYPE_FIXED64 = 1;
@Field static final int WIRETYPE_LENGTH_DELIMITED = 2;
@Field static final int WIRETYPE_FIXED32 = 5;

private Map decodeProtobufMessage(ByteArrayInputStream stream, long available) {
    Map tags = [:]
    while (available > 0) {
        long tagAndType = readVarInt(stream, true)
        if (tagAndType == -1) {
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
                    if (count <= 0) {
                        break
                    }
                    pos += count
                }
                tags[tag] = data
                break
            default:
                LOG.warn("Protobuf unknown wire type ${wireType}")
                break
        }
        LOG.debug "Protobuf decode [tag: ${tag}, wireType: ${wireType}, data: ${tags[tag]}]"
    }
    return tags
}

private int encodeProtobufMessage(ByteArrayOutputStream sink, Map tags) {
    int bytes = 0
    for (entry in tags) {
        if (entry.value) {
            int fieldNumber = entry.key
            int wireType = entry.value instanceof String ? 2 : 0
            int tag = (fieldNumber << 3) | wireType
            LOG.debug "Protobuf encode [fieldNumber: ${fieldNumber}, wireType: ${wireType}, value: ${entry.value}]"
            bytes += writeVarInt(sink, tag)
            switch (wireType) {
                case WIRETYPE_VARINT:
                    bytes += writeVarInt(sink, entry.value)
                    break
                case WIRETYPE_FIXED32:
                    int v = entry.value
                    for (int b = 0; b < 4; b++) {
                        sink.write((int) (v & 0x0ff))
                        bytes++
                        v >>= 8
                    }
                    break
                case WIRETYPE_FIXED64:
                    long v = entry.value
                    for (int b = 0; b < 8; b++) {
                        sink.write((int) (v & 0x0ff))
                        bytes++
                        v >>= 8
                    }
                    break
                case WIRETYPE_LENGTH_DELIMITED:
                    byte[] v = entry.value instanceof String ? entry.value.getBytes('UTF-8') : entry.value
                    bytes += writeVarInt(sink, v.size())
                    sink.write(v)
                    bytes += v.size()
                    break
            }
        }
    }
    return bytes
}

private static String getString(Map tags, int index, String defaultValue = '') {
    return tags[index] ? new String(tags[index], 'UTF-8') : defaultValue
}

private static boolean getBoolean(Map tags, int index, boolean invert = false) {
    return tags[index] ? !invert : invert
}

private static float getFloat(Map tags, int index, float defaultValue = 0.0) {
    return tags[index] ? Float.intBitsToFloat((int) tags[index]) : defaultValue
}

private static int getInt(Map tags, int index, int defaultValue = 0) {
    return tags[index] ? (int) tags[index] : defaultValue
}

private static long getLong(Map tags, int index, long defaultValue = 0) {
    return tags[index] ? tags[index] : defaultValue
}

private static int getVarIntSize(long i) {
    if (i < 0) {
      return 10
    }
    int size = 1
    while (i >= 128) {
        size++
        i >>= 7
    }
    return size
}

private static int writeVarInt(ByteArrayOutputStream os, long value) {
    int count = 0
    for (int i = 0; i < 10; i++) {
        int toWrite = (int) (value & 0x7f)
        value >>>= 7
        count++
        if (value == 0) {
            os.write(toWrite)
            break;
        } else {
            os.write(toWrite | 0x080)
        }
    }
    return count
}

private static long readVarInt(ByteArrayInputStream stream, boolean permitEOF) {
    long result = 0
    int shift = 0
    // max 10 byte wire format for 64 bit integer (7 bit data per byte)
    for (int i = 0; i < 10; i++) {
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

private static long zigZagDecode(long v) {
    return (v >>> 1) ^ -(v & 1)
}

private static long zigZagEncode(long v) {
    return ((v << 1) ^ -(v >>> 63))
}

@Field private final Map LOG = [
    debug: { s -> if (settings.logEnable) { log.debug(s) } },
    trace: { s -> if (settings.logEnable) { log.trace(s) } },
    info: { s -> log.info(s) },
    warn: { s -> log.warn(s) },
    error: { s -> log.error(s) },
    exception: { message, exception ->
        List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith('user_app') }
        Integer line = relevantEntries[0]?.lineNumber
        String method = relevantEntries[0]?.methodName
        log.error("${message}: ${exception} at line ${line} (${method})")
        if (settings.logEnable) {
            log.debug("App exception stack trace:\n${relevantEntries.join('\n')}")
        }
    }
].asImmutable()

/**
 * ESPHome Enumerations
 */
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
