/**
 *  MIT License
 *  Copyright 2019 Jonathan Bradshaw (jb@nrgup.net)
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
import hubitat.device.HubAction
import hubitat.helper.HexUtils
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

metadata {
    definition (name: 'BroadLink - Window Shade', namespace: 'nrgup', author: 'Jonathan Bradshaw', importUrl: '') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'WindowShade'

        command 'stop'

        command 'storeCode', [
            [
                name: 'Name',
                type: 'ENUM',
                constraints: [ 'open', 'close', 'stop' ]
            ],
            [
                name: 'Code',
                type: 'STRING'
            ]
        ]

        preferences {
            section {
                input name: 'networkHost',
                      type: 'text',
                      title: 'Hostname/IP',
                      description: '',
                      required: true,
                      defaultValue: ''

                input name: 'sendIterations',
                      type: 'enum',
                      title: 'Repeat Sending',
                      required: true,
                      defaultValue: 1,
                      options: [
                        1: 'None',
                        2: 'Once',
                        3: 'Twice'
                      ]
            }

            section {
                input name: 'logEnable',
                      type: 'bool',
                      title: 'Enable debug logging',
                      description: 'Automatically disabled after 30 minutes',
                      required: false,
                      defaultValue: true

                input name: 'logTextEnable',
                      type: 'bool',
                      title: 'Enable descriptionText logging',
                      required: false,
                      defaultValue: true
            }
        }
    }
}

@Field static IvParameterSpec initVector = new IvParameterSpec(
    HexUtils.hexStringToByteArray('562e17996d093d28ddb3ba695a2e6f58')
)

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"

    if (!settings.networkHost) {
        log.error 'Unable to connect because host setting not configured'
        return
    }

    state.remove('deviceType')
    state.remove('internalId')
    state.remove('internalKey')
    state.remove('macAddress')
    state.remove('name')
    sendDiscovery()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

void open() {
    if (!state.codes?.open) {
        log.error "${device.displayName} Open code not defined"
    }

    log.info "${device.displayName} open"
    sendCode(state.codes.open, 'parseOpenResponse')
}

void close() {
    if (!state.codes?.close) {
        log.error "${device.displayName} Open code not defined"
    }

    log.info "${device.displayName} close"
    sendCode(state.codes.close, 'parseCloseResponse')
}

void stop() {
    if (!state.codes?.stop) {
        log.error "${device.displayName} Stop code not defined"
    }

    log.info "${device.displayName} stop"
    sendCode(state.codes.stop, 'parseStopResponse')
}

void storeCode(String name, String value) {
    log.info "${device.displayName} Setting ${name} to ${value}"
    state.codes = state.codes ?: [:]
    state.codes[name] = value
}

private static int checkSum(byte[] packet) {
    int checksum = 0xbeaf
    for (int i = 0; i < packet.size(); i++) {
        checksum = checksum + Byte.toUnsignedInt(packet[i])
        checksum = checksum  & 0xFFFF
    }
    return checksum
}

private static byte[] decrypt(byte[] value, SecretKeySpec skeySpec) {
    Cipher cipher = Cipher.getInstance('AES/CBC/NoPadding')
    cipher.init(Cipher.DECRYPT_MODE, skeySpec, initVector)
    return cipher.doFinal(value)
}

private static byte[] encrypt(byte[] value, SecretKeySpec skeySpec) {
    Cipher cipher = Cipher.getInstance('AES/CBC/NoPadding')
    cipher.init(Cipher.ENCRYPT_MODE, skeySpec, initVector)
    return cipher.doFinal(value)
}

private static byte[] padTo(byte[] source, int quotient = 16) {
    int modulo = source.length % quotient
    if (modulo == 0) {
        return source
    }

    int requiredNewSize = source.length + (quotient - modulo)
    byte[] buffer = new byte[requiredNewSize]
    for (int i = 0; i < source.length; i++) {
        buffer[i] = source[i]
    }

    return buffer
}

/* groovylint-disable-next-line ParameterCount */
private byte[] formatPacket(
        byte command,
        byte[] payload,
        byte[] mac,
        byte[] deviceId,
        SecretKeySpec key,
        int deviceType) {

    state.packetCount = (state.packetCount ?: 0 + 1) & 0xffff
    byte[] paddedPayload = padTo(payload, 16) // required for AES encryption

    byte[] packet = new byte[0x38]
    packet[0x00] = (byte) 0x5a
    packet[0x01] = (byte) 0xa5
    packet[0x02] = (byte) 0xaa
    packet[0x03] = (byte) 0x55
    packet[0x04] = (byte) 0x5a
    packet[0x05] = (byte) 0xa5
    packet[0x06] = (byte) 0xaa
    packet[0x07] = (byte) 0x55
    packet[0x24] = (byte) (deviceType & 0xff)
    packet[0x25] = (byte) (deviceType >> 8)
    packet[0x26] = command
    packet[0x28] = (byte) (state.packetCount & 0xff)
    packet[0x29] = (byte) (state.packetCount >> 8)
    packet[0x2a] = mac[0]
    packet[0x2b] = mac[1]
    packet[0x2c] = mac[2]
    packet[0x2d] = mac[3]
    packet[0x2e] = mac[4]
    packet[0x2f] = mac[5]
    packet[0x30] = deviceId[0]
    packet[0x31] = deviceId[1]
    packet[0x32] = deviceId[2]
    packet[0x33] = deviceId[3]
    int checksum = checkSum(paddedPayload)
    packet[0x34] = (byte) (checksum & 0xff)
    packet[0x35] = (byte) (checksum >> 8)
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
    outputStream.write(packet)
    outputStream.write(encrypt(paddedPayload, key))
    byte[] data = outputStream.toByteArray()
    checksum = checkSum(data)
    data[0x20] = (byte) (checksum & 0xff)
    data[0x21] = (byte) (checksum >> 8)
    return data
}

// Step 1 - send discovery to get device type and mac address from device
private void sendDiscovery() {
    if (logEnable) { log.debug 'Sending discovery packet' }

    String[] localAddress = location.hubs[0].getDataValue('localIP').tokenize('.')
    int[] ipAddress = new int[4]
    for (int i = 0; i < 4; i++) {
        ipAddress[i] = Integer.parseInt(localAddress[i])
    }

    Calendar calendar = Calendar.instance
    calendar.firstDayOfWeek = 2
    int timezone = TimeZone.default.rawOffset / 0x36ee80
    byte[] packet = new byte[48]
    if (timezone < 0) {
        packet[8] = (byte) ((255 + timezone) - 1)
        packet[9] = -1
        packet[10] = -1
        packet[11] = -1
    } else {
        packet[8] = 8
        packet[9] = 0
        packet[10] = 0
        packet[11] = 0
    }
    packet[12] = (byte) (calendar.get(1) & 0xff)
    packet[13] = (byte) (calendar.get(1) >> 8)
    packet[14] = (byte) calendar.get(12)
    packet[15] = (byte) calendar.get(11)
    packet[16] = (byte) (calendar.get(1) - 2000)
    packet[17] = (byte) (calendar.get(7) + 1)
    packet[18] = (byte) calendar.get(5)
    packet[19] = (byte) (calendar.get(2) + 1)
    packet[24] = (byte) ipAddress[0]
    packet[25] = (byte) ipAddress[1]
    packet[26] = (byte) ipAddress[2]
    packet[27] = (byte) ipAddress[3]
    packet[28] = (byte) 0x80
    packet[38] = 0x06
    int checksum = checkSum(packet)
    packet[32] = (byte) (checksum & 0xff)
    packet[33] = (byte) (checksum >> 8)

    send(packet, 'parseDiscoveryResponse')
}

// Step 2 - Send authentication to device to get back private key
private void sendAuthentication() {
    if (logEnable) { log.debug 'Creating authentication packet' }

    if (!state.macAddress || !state.deviceType) {
        log.error 'Unable to send authentication until discovery is successful'
        return
    }

    // https://github.com/mjg59/python-broadlink/blob/master/protocol.md
    byte [] payload = [0x00, 0x00, 0x00, 0x00, 0x31, 0x31, 0x31, 0x31,
                       0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31,
                       0x31, 0x31, 0x31, 0x01, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                       ]
    payload[0x1e] = 0x01
    payload[0x2d] = 0x01
    payload[0x30] = (byte) 'H'
    payload[0x31] = (byte) 'u'
    payload[0x32] = (byte) 'b'
    payload[0x33] = (byte) 'i'
    payload[0x34] = (byte) 't'
    payload[0x35] = (byte) 'a'
    payload[0x36] = (byte) 't'

    SecretKeySpec key = new SecretKeySpec(HexUtils.hexStringToByteArray('097628343fe99e23765c1513accf8b02'), 'AES')
    byte[] packet = formatPacket(
        (byte) 0x0065,
        payload,
        (byte[]) HexUtils.hexStringToByteArray(state.macAddress),
        (byte[]) [0, 0, 0, 0],
        key,
        (byte) state.deviceType
    )

    send(packet, 'parseAuthResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private int parse(String description) {
    Map resp = parseLanMessage(description)
    if (logEnable) { log.debug "parse: ${resp}" }

    byte[] parseData = HexUtils.hexStringToByteArray(resp.payload)
    int dErr = parseData[0x22] | (parseData[0x23] << 8)
    if (parseData.size() > 0x38) {
        SecretKeySpec key = new SecretKeySpec((byte[]) state.deviceKey, 'AES')
        byte[] payload = decrypt((byte[]) parseData[0x38..-1], key)
        if (payload) {
            log.info ("${device.displayName} [${HexUtils.byteArrayToHexString(payload)}]")
        }
    }
    if (dErr != 0) {
        log.error ("${device.displayName} returned error code ${String.format('%02X', dErr & 0xffff)}")
    }

    return dErr
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void parseOpenResponse(String description) {
    int result = parse(description)
    if (result == 0) {
        sendEvent(newEvent('windowShade', 'open'))
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void parseCloseResponse(String description) {
    int result = parse(description)
    if (result == 0) {
        sendEvent(newEvent('windowShade', 'closed'))
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void parseStopResponse(String description) {
    int result = parse(description)
    if (result == 0) {
        sendEvent(newEvent('windowShade', 'partially open'))
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void parseDiscoveryResponse(String description) {
    Map resp = parseLanMessage(description)
    if (logEnable) { log.debug "discoveryResponse: ${resp}" }

    byte[] payload = HexUtils.hexStringToByteArray(resp.payload)
    state.name = new String((byte[]) payload[0x40..-1]).trim()
    state.deviceType = ((payload[0x35] & 0xff) << 8) + (payload[0x34] & 0xff) as byte
    state.macAddress = ((byte[]) payload[0x3f..0x3a]).encodeHex().toString()

    log.info "${device.displayName} Discovered ${state.name} (${state.macAddress}) " +
             "deviceType 0x${Integer.toHexString(state.deviceType)}"

    sendAuthentication()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void parseAuthResponse(String description) {
    Map resp = parseLanMessage(description)
    if (logEnable) { log.debug "authResponse: ${resp}" }

    SecretKeySpec key = new SecretKeySpec(HexUtils.hexStringToByteArray('097628343fe99e23765c1513accf8b02'), 'AES')

    byte[] authData = HexUtils.hexStringToByteArray(resp.payload)
    if (authData.size() <= 0x38) {
        log.error 'authResponse ERROR - packet does not contain an Auth payload'
        return
    }
    byte[] dPayload = decrypt((byte[]) authData[0x38..-1], key)
    byte[] internalId = dPayload[0x00..0x03]
    byte[] internalKey = dPayload[0x04..0x13]

    state.deviceId = internalId
    state.deviceKey = internalKey

    log.info "${device.displayName} Received device authentication key"
}

private void send(byte[] packet, String callback = 'parse') {
    if (logEnable) { log.debug "sendHubCommand to ${settings.networkHost} using callback ${callback}" }
    try {
        sendHubCommand(new HubAction(
            HexUtils.byteArrayToHexString(packet),
            hubitat.device.Protocol.LAN,
            [
                callback: callback,
                destinationAddress: settings.networkHost,
                type: HubAction.Type.LAN_TYPE_UDPCLIENT,
                encoding: HubAction.Encoding.HEX_STRING
            ]))
    } catch (e) {
        log.error 'sendMessage: ' + e
    }
}

// Step 3 - Send code for transmit
private void sendCode(String code, String callback) {
    if (!state.deviceKey || !state.deviceId) {
        log.error 'Unable to send code until authentication is successful'
        return
    }

    if (logEnable) { log.debug 'Sending code ' + code }

    List<Byte> payload = [0xD0, 00, 0x02, 0x00, 0x00, 0x00]
    if ( (code[0] == 'J') || (code[-1] == '=') || ((code[0] == 'J') && (code[-1] == 'A'))) {
        // assume base 64 string
        payload += code.decodeBase64() as List
    } else {
        // assume its a hex string
        payload += HexUtils.hexStringToByteArray(code) as List
    }

    SecretKeySpec key = new SecretKeySpec((byte[]) state.deviceKey, 'AES')
    byte[] packet = formatPacket(
        (byte) 0x006a,
        (byte[]) payload,
        (byte[]) HexUtils.hexStringToByteArray(state.macAddress),
        (byte[]) state.deviceId,
        key,
        (byte) state.deviceType
    )

    int iterations = settings.sendIterations as int
    if (iterations > 1) { log.info "Repeating code ${iterations-1} times" }
    iterations.times {
        send(packet, callback)
    }
}

private Map newEvent(String name, Object value, String unit = null) {
    String splitName = splitCamelCase(name)
    String description = "${device.displayName} ${splitName} is ${value}${unit ?: ''}"
    if (logEnable) { log.info description }
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: settings.logTextEnable ? description : ''
    ]
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}

private String splitCamelCase(String s) {
   return s.replaceAll(
      String.format('%s|%s|%s',
         '(?<=[A-Z])(?=[A-Z][a-z])',
         '(?<=[^A-Z])(?=[A-Z])',
         '(?<=[A-Za-z])(?=[^A-Za-z])'
      ),
      ' '
   )
}
