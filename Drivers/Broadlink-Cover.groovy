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
    definition (name: 'BroadLink Window Shade', namespace: 'nrgup', author: 'Jonathan Bradshaw', importUrl: '') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'WindowShade'

        preferences {
            section {
                input name: 'networkHost',
                      type: 'text',
                      title: 'Hostname/IP',
                      description: '',
                      required: true,
                      defaultValue: ''

                input name: 'openCommand',
                      type: 'text',
                      title: 'Broadlink Open Code Data',
                      description: '',
                      required: true

                input name: 'closeCommand',
                      type: 'text',
                      title: 'Broadlink Close Code Data',
                      description: '',
                      required: true

                input name: 'stopCommand',
                      type: 'text',
                      title: 'Broadlink Stop Code Data',
                      description: '',
                      required: true
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

@Field static byte[] aesKey = hexStringToByteArray('097628343fe99e23765c1513accf8b02')
@Field static byte[] initVector = hexStringToByteArray('562e17996d093d28ddb3ba695a2e6f58')

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
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver v${version()} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver v${version()} configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

private void sendCode(String data) {
    byte [] code = []
    // is the code a hex encoded string?
    if ( (data[0] == 'J') || (data[-1] == '=') || ((data[0] == 'J') && (data[-1] == 'A'))) { 
        code = data.decodeBase64()
    } else {
        code = hexStringToByteArray(data)
    }

    if (code == []) {
        log.error("${device.displayName} Provided IR/RF code is in a recognized format")
        return
    }

    // remove any trailing zero bytes
    String tStr = byteArrayToHexString(code)
    while (tStr[-2..-1] == '00') { tStr = tStr[0..-3] }
    code = hexStringToByteArray(tStr)

    byte [] packet = [0xD0, 0x00, 0x02, 0x00, 0x00, 0x00] + code
    sendPacket([
        devType: 0,
        MAC: '',
        internalID: ''
    ], 0x6a, packet)
}

private int getChecksum(byte[] packet) {
    int checksum = 0xbeaf
    for (i = 0; i < packet.size(); i++) { 
        checksum = checksum + Byte.toUnsignedInt(packet[i])
        checksum = checksum  & 0xFFFF
    }
    return checksum
}

private void sendPacket(Map deviceConfig, byte command, byte[] payload, String callback = "parse") {
    log.debug "Send Packet: 0x${String.format('%02X', command)}"
    state.packetCount = (state.packetCount ?: 0 + 1) & 0xffff

    byte [] packet = [ 0x5a, 0xa5, 0xaa, 0x55, 0x5a, 0xa5, 0xaa, 0x55,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x2a, 0x27, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                      ]
    packet[0x24] = (byte) (deviceConfig.devType & 0xff)
    packet[0x25] = (byte) (deviceConfig.devType >> 8)

    packet[0x26] = (byte) command
    packet[0x28] = (byte) (state.packetCount & 0xff)
    packet[0x29] = (byte) (state.packetCount >> 8)
    packet[0x2a] = (byte) hexStringToInt(deviceConfig.MAC[10,11])
    packet[0x2b] = (byte) hexStringToInt(deviceConfig.MAC[8..9])
    packet[0x2c] = (byte) hexStringToInt(deviceConfig.MAC[6..7])
    packet[0x2d] = (byte) hexStringToInt(deviceConfig.MAC[4..5])
    packet[0x2e] = (byte) hexStringToInt(deviceConfig.MAC[2..3])
    packet[0x2f] = (byte) hexStringToInt(deviceConfig.MAC[0..1])
    packet[0x30] = (byte) hexStringToInt(deviceConfig.internalID[0..1])
    packet[0x31] = (byte) hexStringToInt(deviceConfig.internalID[2..3])
    packet[0x32] = (byte) hexStringToInt(deviceConfig.internalID[4..5])
    packet[0x33] = (byte) hexStringToInt(deviceConfig.internalID[6..7])

    //pad the payload for AES encryption
    if (payload.size() > 0) {
        int numpad = 16 - (payload.size() % 16)
        String padding = '00' * numpad
        payload = hexStringToByteArray(byteArrayToHexString(payload) + padding)
    }

    int checksum = getChecksum(payload)
    packet[0x34] = (byte)(checksum & 0xff)
    packet[0x35] = (byte)(checksum >> 8)

    if (payload.size() > 0) {
        byte[] ePayload = aesEncrypt(payload)
        packet = hexStringToByteArray(byteArrayToHexString(packet) + byteArrayToHexString(ePayload))
    }

    checksum = getChecksum(packet)
    packet[0x20] = (byte)(checksum & 0xff)
    packet[0x21] = (byte)(checksum >> 8)
    sendMessage(deviceConfig, packet, callback)
}

private byte[] aesEncrypt(byte[] value) {
    try {
        IvParameterSpec iv = new IvParameterSpec(initVECTOR)
        SecretKeySpec skeySpec = new SecretKeySpec(aesKEY, 'AES')
        Cipher cipher = Cipher.getInstance('AES/CBC/NoPadding')
        cipher.init(Cipher.ENCRYPT_MODE, skeySpec, iv)
        return cipher.doFinal(value)
    } catch (e) {
        log.error 'aesEncrypt: ' + e
    }
}

private void sendMessage(Map deviceConfig, byte[] packet, String callback = 'parse') {
    try {
        String packetData = byteArrayToHexString(packet)
        sendHubCommand(new HubAction(
        packetData,
        hubitat.device.Protocol.LAN,
        [
            callback: callback,
            destinationAddress: deviceConfig.IP,
            type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
            encoding: hubitat.device.HubAction.Encoding.HEX_STRING
        ]))
    } catch (e) {
        log.error 'sendMessage: ' + e
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

