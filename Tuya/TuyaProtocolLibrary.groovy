library (
    name: 'tuyaProtocols',
    namespace: 'tuya',
    author: 'jb@nrgup.net',
    description: 'Common code for Tuya drivers'
)

/*
 * Tuya Protocol Functions (encode, decode, encryption along with byte array manipulation functions)
 *
 * Code in this section is licensed under the Eclipse Public License 1.0. The author is grateful to
 * Wim Vissers for his work on the Tuya OpenHAB add-on code at https://github.com/wvissers/openhab2-addons-tuya
 * which has been adapted from the original for sandboxed Groovy execution.
 */
Map tuyaDecode(byte[] buffer, String localKey) {
    Map result = [:]

    if (buffer.length < 24) {
        result.error = 'Packet too short (less than 24 bytes). Length: ' + buffer.length
        return result
    }

    long prefix = getUInt32(buffer, 0)
    if (prefix != 0x000055AA) {
        result.error = 'Prefix does not match: ' + String.format('%x', prefix)
        return result
    }

    result.sequenceNumber = getUInt32(buffer, 4)
    result.commandByte = getUInt32(buffer, 8)
    result.payloadSize = getUInt32(buffer, 12)

    long expectedCrc = getUInt32(buffer, (int) (16 + result.payloadSize - 8))
    long computedCrc = tuyaCrc32(copy(buffer, 0, (int) result.payloadSize + 8))
    if (computedCrc != expectedCrc) {
        result.error = 'CRC error. Expected: ' + expectedCrc + ', computed: ' + computedCrc
        return result
    }

    // Get the return code, 0 = success
    // This field is only present in messages from the devices
    // Absent in messages sent to device
    result.returnCode = getUInt32(buffer, 16) & 0xFFFFFF00

    // Get the payload
    // Adjust for status messages lacking a return code
    byte[] payload
    boolean status = false
    if (result.returnCode != 0) {
        payload = copy(buffer, 16, (int) (result.payloadSize - 8))
    } else if (result.commandByte == 8) { // STATUS
        status = true
        payload = copy(buffer, 16 + 3, (int) (result.payloadSize - 11))
    } else {
        payload = copy(buffer, 16 + 4, (int) (result.payloadSize - 12))
    }

    try {
        byte[] data = tuyaDecrypt(payload, localKey)
        result.text = status ? new String(data, 16, data.length - 16) : new String(data, 'UTF-8')
    } catch (e) {
        result.error = e
    }

    return result
}

byte[] tuyaEncode(String command, String input, String localKey, long seq = 0) {
    byte[] payload = tuyaEncrypt(input.getBytes('UTF-8'), localKey)

    // Check if we need an extended header, only for certain CommandTypes
    if (command != 'DP_QUERY') {
        byte[] buffer = new byte[payload.length + 15]
        fill(buffer, (byte) 0x00, 0, 15)
        copy(buffer, '3.3', 0)
        copy(buffer, payload, 15)
        payload = buffer
    }

    // Allocate buffer with room for payload + 24 bytes for
    // prefix, sequence, command, length, crc, and suffix
    byte[] buffer = new byte[payload.length + 24]

    // Add prefix, command and length.
    putUInt32(buffer, 0, 0x000055AA)
    putUInt32(buffer, 8, tuyaCommandByte(command))
    putUInt32(buffer, 12, payload.length + 8)

    // Optionally add sequence number.
    putUInt32(buffer, 4, seq)

    // Add payload, crc and suffix
    copy(buffer, payload, 16)
    byte[] crcbuf = new byte[payload.length + 16]
    copy(crcbuf, buffer, 0, payload.length + 16)
    putUInt32(buffer, payload.length + 16, tuyaCrc32(crcbuf))
    putUInt32(buffer, payload.length + 20, 0x0000AA55)

    return buffer
}

Boolean tuyaSendCommand(String devId, String command = 'CONTROL') {
    return tuyaSendCommand(devId, null, command)
}

Boolean tuyaSendCommand(String devId, Map dps, String command = 'CONTROL') {
    String localKey = getDataValue('local_key')
    if (!ipAddress || !localKey || !(sendMode in ['local', 'both'])) { return false }

    byte[] output = tuyaEncode(
        command,
        JsonOutput.toJson([
            gwId: devId,
            devId: devId,
            t: Math.round(now() / 1000),
            dps: dps,
            uid: ''
        ]),
        localKey
    )

    try {
        interfaces.rawSocket.connect(ipAddress, 6668, byteInterface: true)
        interfaces.rawSocket.sendMessage(HexUtils.byteArrayToHexString(output))
    } catch (e) {
        log.error "tuya send error ${e}"
        return false
    }

    return (sendmode == 'local')
}

private static byte[] copy(byte[] buffer, String source, int from) {
    return copy(buffer, source.bytes, from)
}

private static byte[] copy(byte[] buffer, byte[] source, int from) {
    for (int i = 0; i < source.length; i++) {
        buffer[i + from] = source[i]
    }
    return buffer
}

private static byte[] copy(byte[] source, int from, int length) {
    byte[] buffer = new byte[length]
    for (int i = 0; i < length; i++) {
        buffer[i] = source[i + from]
    }
    return buffer
}

private static byte[] copy(byte[] buffer, byte[] source, int from, int length) {
    for (int i = 0; i < length; i++) {
        buffer[i + from] = source[i]
    }
    return buffer
}

private static byte tuyaCommandByte(String command) {
    switch (command) {
        case 'CONTROL': return 7
        case 'STATUS': return 8
        case 'HEART_BEAT': return 9
        case 'DP_QUERY': return 10
    }
}

private static long tuyaCrc32(byte[] buffer) {
    long crc = 0xFFFFFFFFL
    for (byte b : buffer) {
        crc = ((crc >>> 8) & 0xFFFFFFFFL) ^ (tuyaCrc32Table[(int) ((crc ^ b) & 0xff)] & 0xFFFFFFFFL)
    }
    return ((crc & 0xFFFFFFFFL) ^ 0xFFFFFFFFL) & 0xFFFFFFFFL // return 0xFFFFFFFFL
}

private static byte[] tuyaDecrypt (byte[] payload, String secret) {
    SecretKeySpec key = new SecretKeySpec(secret.getBytes('UTF-8'), 'AES')
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.DECRYPT_MODE, key)
    return cipher.doFinal(payload)
}

private static byte[] tuyaEncrypt (byte[] payload, String secret) {
    SecretKeySpec key = new SecretKeySpec(secret.getBytes('UTF-8'), 'AES')
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return cipher.doFinal(payload)
}

private static byte[] fill(byte[] buffer, byte fill, int from, int length) {
    for (int i = from; i < from + length; i++) {
        buffer[i] = fill
    }

    return buffer
}

private static long getUInt32(byte[] buffer, int start) {
    long result = 0
    for (int i = start; i < start + 4; i++) {
        result *= 256
        result += (buffer[i] & 0xff)
    }

    return result
}

private static void putUInt32(byte[] buffer, int start, long value) {
    long lv = value
    for (int i = 3; i >= 0; i--) {
        buffer[start + i] = (byte) (((lv & 0xFFFFFFFF) % 0x100) & 0xFF)
        lv /= 0x100
    }
}

@Field static final long[] tuyaCrc32Table = [ 0x00000000, 0x77073096, 0xEE0E612C, 0x990951BA, 0x076DC419, 0x706AF48F,
    0xE963A535, 0x9E6495A3, 0x0EDB8832, 0x79DCB8A4, 0xE0D5E91E, 0x97D2D988, 0x09B64C2B, 0x7EB17CBD, 0xE7B82D07,
    0x90BF1D91, 0x1DB71064, 0x6AB020F2, 0xF3B97148, 0x84BE41DE, 0x1ADAD47D, 0x6DDDE4EB, 0xF4D4B551, 0x83D385C7,
    0x136C9856, 0x646BA8C0, 0xFD62F97A, 0x8A65C9EC, 0x14015C4F, 0x63066CD9, 0xFA0F3D63, 0x8D080DF5, 0x3B6E20C8,
    0x4C69105E, 0xD56041E4, 0xA2677172, 0x3C03E4D1, 0x4B04D447, 0xD20D85FD, 0xA50AB56B, 0x35B5A8FA, 0x42B2986C,
    0xDBBBC9D6, 0xACBCF940, 0x32D86CE3, 0x45DF5C75, 0xDCD60DCF, 0xABD13D59, 0x26D930AC, 0x51DE003A, 0xC8D75180,
    0xBFD06116, 0x21B4F4B5, 0x56B3C423, 0xCFBA9599, 0xB8BDA50F, 0x2802B89E, 0x5F058808, 0xC60CD9B2, 0xB10BE924,
    0x2F6F7C87, 0x58684C11, 0xC1611DAB, 0xB6662D3D, 0x76DC4190, 0x01DB7106, 0x98D220BC, 0xEFD5102A, 0x71B18589,
    0x06B6B51F, 0x9FBFE4A5, 0xE8B8D433, 0x7807C9A2, 0x0F00F934, 0x9609A88E, 0xE10E9818, 0x7F6A0DBB, 0x086D3D2D,
    0x91646C97, 0xE6635C01, 0x6B6B51F4, 0x1C6C6162, 0x856530D8, 0xF262004E, 0x6C0695ED, 0x1B01A57B, 0x8208F4C1,
    0xF50FC457, 0x65B0D9C6, 0x12B7E950, 0x8BBEB8EA, 0xFCB9887C, 0x62DD1DDF, 0x15DA2D49, 0x8CD37CF3, 0xFBD44C65,
    0x4DB26158, 0x3AB551CE, 0xA3BC0074, 0xD4BB30E2, 0x4ADFA541, 0x3DD895D7, 0xA4D1C46D, 0xD3D6F4FB, 0x4369E96A,
    0x346ED9FC, 0xAD678846, 0xDA60B8D0, 0x44042D73, 0x33031DE5, 0xAA0A4C5F, 0xDD0D7CC9, 0x5005713C, 0x270241AA,
    0xBE0B1010, 0xC90C2086, 0x5768B525, 0x206F85B3, 0xB966D409, 0xCE61E49F, 0x5EDEF90E, 0x29D9C998, 0xB0D09822,
    0xC7D7A8B4, 0x59B33D17, 0x2EB40D81, 0xB7BD5C3B, 0xC0BA6CAD, 0xEDB88320, 0x9ABFB3B6, 0x03B6E20C, 0x74B1D29A,
    0xEAD54739, 0x9DD277AF, 0x04DB2615, 0x73DC1683, 0xE3630B12, 0x94643B84, 0x0D6D6A3E, 0x7A6A5AA8, 0xE40ECF0B,
    0x9309FF9D, 0x0A00AE27, 0x7D079EB1, 0xF00F9344, 0x8708A3D2, 0x1E01F268, 0x6906C2FE, 0xF762575D, 0x806567CB,
    0x196C3671, 0x6E6B06E7, 0xFED41B76, 0x89D32BE0, 0x10DA7A5A, 0x67DD4ACC, 0xF9B9DF6F, 0x8EBEEFF9, 0x17B7BE43,
    0x60B08ED5, 0xD6D6A3E8, 0xA1D1937E, 0x38D8C2C4, 0x4FDFF252, 0xD1BB67F1, 0xA6BC5767, 0x3FB506DD, 0x48B2364B,
    0xD80D2BDA, 0xAF0A1B4C, 0x36034AF6, 0x41047A60, 0xDF60EFC3, 0xA867DF55, 0x316E8EEF, 0x4669BE79, 0xCB61B38C,
    0xBC66831A, 0x256FD2A0, 0x5268E236, 0xCC0C7795, 0xBB0B4703, 0x220216B9, 0x5505262F, 0xC5BA3BBE, 0xB2BD0B28,
    0x2BB45A92, 0x5CB36A04, 0xC2D7FFA7, 0xB5D0CF31, 0x2CD99E8B, 0x5BDEAE1D, 0x9B64C2B0, 0xEC63F226, 0x756AA39C,
    0x026D930A, 0x9C0906A9, 0xEB0E363F, 0x72076785, 0x05005713, 0x95BF4A82, 0xE2B87A14, 0x7BB12BAE, 0x0CB61B38,
    0x92D28E9B, 0xE5D5BE0D, 0x7CDCEFB7, 0x0BDBDF21, 0x86D3D2D4, 0xF1D4E242, 0x68DDB3F8, 0x1FDA836E, 0x81BE16CD,
    0xF6B9265B, 0x6FB077E1, 0x18B74777, 0x88085AE6, 0xFF0F6A70, 0x66063BCA, 0x11010B5C, 0x8F659EFF, 0xF862AE69,
    0x616BFFD3, 0x166CCF45, 0xA00AE278, 0xD70DD2EE, 0x4E048354, 0x3903B3C2, 0xA7672661, 0xD06016F7, 0x4969474D,
    0x3E6E77DB, 0xAED16A4A, 0xD9D65ADC, 0x40DF0B66, 0x37D83BF0, 0xA9BCAE53, 0xDEBB9EC5, 0x47B2CF7F, 0x30B5FFE9,
    0xBDBDF21C, 0xCABAC28A, 0x53B39330, 0x24B4A3A6, 0xBAD03605, 0xCDD70693, 0x54DE5729, 0x23D967BF, 0xB3667A2E,
    0xC4614AB8, 0x5D681B02, 0x2A6F2B94, 0xB40BBE37, 0xC30C8EA1, 0x5A05DF1B, 0x2D02EF8D ]
/* End of Tuya Protocol Functions */
