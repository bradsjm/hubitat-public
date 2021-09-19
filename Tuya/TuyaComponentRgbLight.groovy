/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
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

import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.HexUtils
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.regex.Matcher

metadata {
    definition (name: 'Tuya Generic RGBW Light', namespace: 'tuya', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'ChangeLevel'
        capability 'ColorControl'
        capability 'ColorMode'
        capability 'ColorTemperature'
        capability 'Initialize'
        capability 'Light'
        capability 'Switch'
        capability 'SwitchLevel'
        capability 'Refresh'
    }
}

preferences {
    section {
        input name: 'ipAddress',
              type: 'text',
              title: 'Device IP',
              required: false

        input name: 'sendMode',
              title: 'Communications Mode',
              type: 'enum',
              required: true,
              defaultValue: 'both',
              options: [
                'local': 'Local Preferred (requires ip)',
                'both': 'Local with Cloud as backup',
                'cloud': 'Cloud only'
              ]

        input name: 'logEnable',
              type: 'bool',
              title: 'Enable debug logging',
              required: false,
              defaultValue: true

        input name: 'txtEnable',
              type: 'bool',
              title: 'Enable descriptionText logging',
              required: false,
              defaultValue: true
    }
}

// Tuya Function Categories
@Field static final List<String> brightnessFunctions = [ 'bright_value', 'bright_value_v2', 'bright_value_1' ]
@Field static final List<String> colourFunctions = [ 'colour_data', 'colour_data_v2' ]
@Field static final List<String> switchFunctions = [ 'switch_led', 'switch_led_1', 'light' ]
@Field static final List<String> temperatureFunctions = [ 'temp_value', 'temp_value_v2' ]
@Field static final List<String> workModeFunctions = [ 'work_mode' ]

// Constants
@Field static final Integer maxMireds = 500 // should this be 370?
@Field static final Integer minMireds = 153

// Called every 15 seconds to keep talking to device
void heartbeat() {
    tuyaSendCommand(getDataValue('id'), 'HEART_BEAT')
}

/**
 *  Hubitat Driver Event Handlers
 */
// Called when the device is first created
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called to initialize
void initialize() {
    unschedule('heartbeat')
    String localKey = getDataValue('local_key')
    if (ipAddress && localKey && (sendMode in ['both', 'local'])) {
        schedule('*/15 * * ? * * *', 'heartbeat')
        heartbeat()
    }
}

// Component command to turn on device
void on() {
    if (!tuyaSendCommand(getDataValue('id'), [ '1': true ])) {
        parent?.componentOn(device)
    }
}

// Component command to turn off device
void off() {
    if (!tuyaSendCommand(getDataValue('id'), [ '1': false ])) {
        parent?.componentOff(device)
    }
}

// parse responses from device
void parse(String message) {
    if (!message) { return }
    String localKey = getDataValue('local_key')
    Map result = tuyaDecode(HexUtils.hexStringToByteArray(message), localKey)
    if (logEnable) { log.debug "${device.displayName} received ${result}" }
    if (result.error) {
        log.error "${device.displayName} received error ${result.error}"
    } else if (result.commandByte == 7) { // COMMAND ACK
        tuyaSendCommand(getDataValue('id'), 'DP_QUERY')
    } else if (result.commandByte == 8 || result.commandByte == 10 ) { // STATUS or QUERY RESULTS
        Map json = new JsonSlurper().parseText(result.text)
        parseDeviceState(json.dps)
    }
}

// parse commands from parent (cloud)
void parse(List<Map> description) {
    if (ipAddress && sendMode == 'local') { return }
    if (logEnable) { log.debug description }
    description.each { d ->
        if (device.currentValue(d.name) != d.value) {
            if (d.descriptionText && txtEnable) { log.info d.descriptionText }
            sendEvent(d)
        }
    }
}

// Component command to refresh device
void refresh() {
    if (!tuyaSendCommand(getDataValue('id'), 'DP_QUERY')) {
        parent?.componentRefresh(device)
    }
}

// Component command to set color
void setColor(Map colorMap) {
    Map functions = getFunctions(device)
    String code = getFunctionByCode(functions, colourFunctions)
    Map color = functions[code]
    Map bright = functions['bright_value'] ?: functions['bright_value_v2'] ?: color.v
    int h = remap(colorMap.hue, 0, 100, color.h.min, color.h.max)
    int s = remap(colorMap.saturation, 0, 100, color.s.min, color.s.max)
    int v = remap(colorMap.level, 0, 100, bright.min, bright.max)
    def (int r, int g, int b) = ColorUtils.hsvToRGB([colormap.hue, colormap.saturation, colormap.level])
    String rgb = Integer.toHexString(r).padLeft(2, '0') +
                 Integer.toHexString(g).padLeft(2, '0') +
                 Integer.toHexString(b).padLeft(2, '0')
    String hsv = Integer.toHexString(h).padLeft(4, '0') +
                 Integer.toHexString(s).padLeft(2, '0') +
                 Integer.toHexString(v).padLeft(2, '0')
    if (!tuyaSendCommand(getDataValue('id'), [ '5': rgb + hsv ])) {
        parent?.setColor(device, colorMap)
    }
}

// Component command to set color temperature
void setColorTemperature(BigDecimal kelvin, BigDecimal level = null, BigDecimal duration = null) {
    Map functions = getFunctions(device)
    String code = getFunctionByCode(functions, temperatureFunctions)
    Map temp = functions[code]
    Integer value = temp.max - Math.ceil(maxMireds - remap(1000000 / kelvin, minMireds, maxMireds, temp.min, temp.max))
    if (!tuyaSendCommand(getDataValue('id'), [ '5': value ])) {
        parent?.componentSetColorTemperature(device, kelvin, level, duration)
    }
    if (level && device.currentValue('level') != level) {
        setLevel(level, duration)
    }
}

// Component command to set hue
void setHue(BigDecimal hue) {
    setColor([
        hue: hue,
        saturation: device.currentValue('saturation'),
        level: device.currentValue('level') ?: 100
    ])
}

// Component command to set level
void setLevel(BigDecimal level, BigDecimal duration = 0) {
    String colorMode = device.currentValue('colorMode')
    if (colorMode == 'CT') {
        Map functions = getFunctions(device)
        String code = getFunctionByCode(functions, brightnessFunctions)
        Map bright = functions[code]
        Integer value = Math.ceil(remap(level, 0, 100, bright.min, bright.max))
        if (!tuyaSendCommand(getDataValue('id'), [ '3': value ])) {
            parent?.componentSetLevel(device, level, duration)
        }
    } else {
        setColor([
            hue: device.currentValue('hue'),
            saturation: device.currentValue('saturation'),
            level: level
        ])
    }
}

// Component command to set saturation
void setSaturation(BigDecimal saturation) {
    setColor([
        hue: device.currentValue('hue') ?: 100,
        saturation: saturation,
        level: device.currentValue('level') ?: 100
    ])
}

// Socket status updates
void socketStatus(String message) {
    if (message.contains('error')) {
        log.error "${device.displayName} socket ${message}"
    } else {
        log.info "${device.displayName} socket ${message}"
    }
}

// Start gradual level change
void startLevelChange(String direction) {
    doLevelChange(delta: (direction == 'down') ? -10 : 10)
}

// Stop gradual level change
void stopLevelChange() {
    unschedule('doLevelChange')
}

void doLevelChange(Integer delta) {
    int newLevel = (device.currentValue('level') as int) + delta
    if (newLevel < 0) { newLevel = 0 }
    if (newLevel > 100) { newLevel = 100 }
    setLevel(newLevel)

    if (newLevel > 0 && newLevel < 100) {
        runInMillis(1000, 'doLevelChange', delta)
    }
}

// Called when the device is removed
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    initialize()
    if (logEnable) { runIn(1800, 'logsOff') }
}

/*
 * Tuya Protocol Functions (encode, decode, encryption along with byte array manipulation functions)
 *
 * Code in this section is licensed under the Eclipse Public License 1.0. The author is grateful to
 * Wim Vissers for his work on the Tuya OpenHAB add-on code at https://github.com/wvissers/openhab2-addons-tuya
 * which has been adapted from the original for sandboxed Groovy execution.
 */
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

private static Map tuyaDecode(byte[] buffer, String localKey) {
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

private static byte[] tuyaEncode(String command, String input, String localKey, long seq = 0) {
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

private static Map getFunctions(DeviceWrapper device) {
    return new JsonSlurper().parseText(device.getDataValue('functions'))
}

private static String getFunctionByCode(Map functions, List codes) {
    return codes.find { c -> functions.containsKey(c) } ?: codes.first()
    // TODO: Include default function values
}

private static BigDecimal remap(BigDecimal oldValue, BigDecimal oldMin, BigDecimal oldMax,
                                BigDecimal newMin, BigDecimal newMax) {
    BigDecimal value = oldValue
    if (value < oldMin) { value = oldMin }
    if (value > oldMax) { value = oldMax }
    BigDecimal newValue = ( (value - oldMin) / (oldMax - oldMin) ) * (newMax - newMin) + newMin
    return newValue.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private static String translateColor(Integer hue, Integer saturation) {
    if (saturation < 1) {
        return 'White'
    }

    switch (hue * 3.6 as int) {
        case 0..15: return 'Red'
        case 16..45: return 'Orange'
        case 46..75: return 'Yellow'
        case 76..105: return 'Chartreuse'
        case 106..135: return 'Green'
        case 136..165: return 'Spring'
        case 166..195: return 'Cyan'
        case 196..225: return 'Azure'
        case 226..255: return 'Blue'
        case 256..285: return 'Violet'
        case 286..315: return 'Magenta'
        case 316..345: return 'Rose'
        case 346..360: return 'Red'
    }

    return ''
}

private void parseDeviceState(Map dps) {
    if (logEnable) { log.debug "${device.displayName} parsing dps ${dps}" }
    Map functions = getFunctions(device)

    String colorMode = device.currentValue('colorMode')
    List<Map> events = []

    if (dps.containsKey('1')) {
        String value = dps['1'] ? 'on' : 'off'
        events << [ name: 'switch', value: value, descriptionText: "switch is ${value}" ]
    }

    // Determine if we are in RGB or CT mode either explicitly or implicitly
    if (dps.containsKey('2')) {
        colorMode = dps['2'] == 'colour' ? 'RGB' : 'CT'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
    } else if (dps.containsKey('4')) {
        colorMode = 'CT'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
    } else if (dps.containsKey('5')) {
        colorMode = 'RGB'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
    }

    if (dps.containsKey('3') && colorMode == 'CT') {
        Map code = functions[getFunctionByCode(functions, brightnessFunctions)]
        Integer value = Math.floor(remap(dps[3] as Integer, code.min, code.max, 0, 100))
        events << [ name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%" ]
    }

    if (dps.containsKey('4')) {
        Map code = functions[getFunctionByCode(functions, temperatureFunctions)]
        Integer value = Math.floor(1000000 / remap(code.max - dps['4'] as Integer,
                                   code.min, code.max, minMireds, maxMireds))
        events << [ name: 'colorTemperature', value: value, unit: 'K',
                    descriptionText: "color temperature is ${value}K" ]
    }

    if (dps.containsKey('5') && colorMode == 'RGB') {
        String value = dps['5']
        // first six characters are RGB values which we ignore and use HSV instead
        Matcher match = value =~ /^.{6}([0-9a-f]{4})([0-9a-f]{2})([0-9a-f]{2})$/
        if (match.find()) {
            Map code = functions[getFunctionByCode(functions, colourFunctions)]
            Map bright = functions[getFunctionByCode(functions, brightnessFunctions)]
            int h = Integer.parseInt(match.group(1), 16)
            int s = Integer.parseInt(match.group(2), 16)
            int v = Integer.parseInt(match.group(3), 16)
            Integer hue = Math.floor(remap(h, code.h.min, code.h.max, 0, 100))
            Integer saturation = Math.floor(remap(s, code.s.min, code.s.max, 0, 100))
            Integer level = Math.floor(remap(v, bright.min, bright.max, 0, 100))
            String colorName = translateColor(hue, saturation)
            events << [ name: 'hue', value: hue, descriptionText: "hue is ${hue}%" ]
            events << [ name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}%" ]
            events << [ name: 'saturation', value: saturation, descriptionText: "saturation is ${saturation}%" ]
            events << [ name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%" ]
        }
    }

    events.each { e ->
        if (device.currentValue(e.name) != e.value) {
            if (e.descriptionText && txtEnable) { log.info e.descriptionText }
            sendEvent(e)
        }
    }
}

private Boolean tuyaSendCommand(String devId, String command = 'CONTROL') {
    return tuyaSendCommand(devId, null, command)
}

private Boolean tuyaSendCommand(String devId, Map dps, String command = 'CONTROL') {
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

    if (logEnable) { log.debug "${device.displayName} sending ${command} ${dps ?: ''} to ${ipAddress}" }
    try {
        interfaces.rawSocket.connect(ipAddress, 6668, byteInterface: true)
        interfaces.rawSocket.sendMessage(HexUtils.byteArrayToHexString(output))
    } catch (e) {
        log.error "${device.displayName} send error ${e}"
        return false
    }

    return true
}

