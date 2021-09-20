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

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import hubitat.helper.HexUtils
import hubitat.helper.NetworkUtils
import hubitat.scheduling.AsyncResponse

metadata {
    definition (name: 'Tuya IoT Platform (Cloud)', namespace: 'tuya', author: 'Jonathan Bradshaw') {
        capability 'Initialize'
        capability 'Refresh'

        command 'removeDevices'

        attribute "deviceCount", "Integer"
    }

    preferences {
        section {
            input name: 'access_id',
                  type: 'text',
                  title: 'Tuya API Access/Client Id',
                  required: true

            input name: 'access_key',
                  type: 'password',
                  title: 'Tuya API Access/Client Secret',
                  required: true

            input name: 'appSchema',
                  title: 'Tuya Application',
                  type: 'enum',
                  required: true,
                  defaultValue: 'tuyaSmart',
                  options: [
                    'tuyaSmart': 'Tuya Smart Life App',
                    'smartlife': 'Smart Life App'
                ]

            input name: 'username',
                  type: 'text',
                  title: 'Tuya Application Login',
                  required: true

            input name: 'password',
                  type: 'password',
                  title: 'Tuya Application Password',
                  required: true

            input name: 'countryCode',
                  type: 'number',
                  title: 'Tuya Application Country Code',
                  required: true,
                  defaultValue: 1

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: true
        }
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

// Jason Parsing Cache
@Field static final ConcurrentHashMap<String, Map> jsonCache = new ConcurrentHashMap<>()

/**
 *  Hubitat Driver Event Handlers
 */
// Component command to turn on device
void componentOn(DeviceWrapper dw) {
    Map functions = getFunctions(dw)
    String code = getFunctionByCode(functions, switchFunctions)
    tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': true ])
}

// Component command to turn off device
void componentOff(DeviceWrapper dw) {
    Map functions = getFunctions(dw)
    String code = getFunctionByCode(functions, switchFunctions)
    tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': false ])
}

// Component command to refresh device
void componentRefresh(DeviceWrapper dw) {
    String id = dw.getDataValue('id')
    if (id && dw.getDataValue('functions')) {
        tuyaGetState(id)
    }
}

// Component command to set color
void componentSetColor(DeviceWrapper dw, Map colorMap) {
    Map functions = getFunctions(dw)
    String code = getFunctionByCode(functions, colourFunctions)
    Map color = functions[code]
    // An oddity and workaround for mapping brightness values
    Map bright = functions['bright_value'] ?: functions['bright_value_v2'] ?: color.v
    Map value = [
        h: remap(colorMap.hue, 0, 100, color.h.min, color.h.max),
        s: remap(colorMap.saturation, 0, 100, color.s.min, color.s.max),
        v: remap(colorMap.level, 0, 100, bright.min, bright.max)
    ]
    tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': value ])
}

// Component command to set color temperature
void componentSetColorTemperature(DeviceWrapper dw, BigDecimal kelvin,
                                  BigDecimal level = null, BigDecimal duration = null) {
    Map functions = getFunctions(dw)
    String code = getFunctionByCode(functions, temperatureFunctions)
    Map temp = functions[code]
    Integer value = temp.max - Math.ceil(maxMireds - remap(1000000 / kelvin, minMireds, maxMireds, temp.min, temp.max))
    tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': value ])
    if (level && dw.currentValue('level') != level) {
        componentSetLevel(dw, level, duration)
    }
}

// Component command to set effect
void componentSetEffect(DeviceWrapper dw, BigDecimal index) {
    log.warn "${device.displayName} Set effect command not supported"
}

// Component command to set hue
void componentSetHue(DeviceWrapper dw, BigDecimal hue) {
    componentSetColor(dw, [
        hue: hue,
        saturation: dw.currentValue('saturation') ?: 100,
        level: dw.currentValue('level') ?: 100
    ])
}

// Component command to set level
/* groovylint-disable-next-line UnusedMethodParameter */
void componentSetLevel(DeviceWrapper dw, BigDecimal level, BigDecimal duration = 0) {
    String colorMode = dw.currentValue('colorMode') ?: 'CT'
    if (colorMode == 'CT') {
        Map functions = getFunctions(dw)
        String code = getFunctionByCode(functions, brightnessFunctions)
        Map bright = functions[code]
        Integer value = Math.ceil(remap(level, 0, 100, bright.min, bright.max))
        tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': value ])
    } else {
        componentSetColor(dw, [
            hue: dw.currentValue('hue') ?: 100,
            saturation: dw.currentValue('saturation') ?: 100,
            level: level
        ])
    }
}

void componentSetNextEffect(DeviceWrapper device) {
    log.warn "${device.displayName} Set next effect command not supported"
}

void componentSetPreviousEffect(DeviceWrapper device) {
    log.warn "${device.displayName} Set previous effect command not supported"
}

// Component command to set saturation
void componentSetSaturation(DeviceWrapper dw, BigDecimal saturation) {
    componentSetColor(dw, [
        hue: dw.currentValue('hue') ?: 100,
        saturation: saturation,
        level: dw.currentValue('level') ?: 100
    ])
}

// Component command to start level change (up or down)
void componentStartLevelChange(DeviceWrapper dw, String direction) {
    doLevelChange([ dni: dw.deviceNetworkId, delta: (direction == 'down') ? -10 : 10 ])
}

// Component command to stop level change
void componentStopLevelChange(DeviceWrapper dw) {
    unschedule('doLevelChange')
}

void doLevelChange(Map data) {
    // TODO: Handle multiple light changes
    DeviceWrapper dw = getChildDevice(data.dni)
    int newLevel = (dw.currentValue('level') as int) + data.delta
    if (newLevel < 0) { newLevel = 0 }
    if (newLevel > 100) { newLevel = 100 }
    componentSetLevel(dw, newLevel)

    if (newLevel > 0 && newLevel < 100) {
        runInMillis(1000, 'doLevelChange', [ data: data ])
    }
}

// Called when the device is started
void initialize() {
    log.info "${device.displayName} driver initializing"
    state.clear()
    unschedule()

    state.endPoint = 'https://openapi.tuyaus.com' // default US endpoint
    state.tokenInfo = [ access_token: '', expire: now() ] // initialize token
    state.uuid = state?.uuid ?: UUID.randomUUID().toString()
    tuyaAuthenticate()
}

// Called when the device is first created
void installed() {
    log.info "${device.displayName} driver installed"
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

// Called to parse received MQTT data
void parse(String data) {
    JsonSlurper parser = new JsonSlurper()
    Map payload = parser.parseText(interfaces.mqtt.parseMessage(data).payload)
    SecretKeySpec key = new SecretKeySpec(state.mqttInfo.password[8..23].bytes, 'AES')
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.DECRYPT_MODE, key)
    Map result = parser.parse(cipher.doFinal(payload.data.decodeBase64()), 'UTF-8')
    if (result.status) {
        parseDeviceState(result)
    } else if (result.bizCode && result.bizData) {
        parseBizData(result.bizCode, result.bizData)
    } else {
        log.debug result
    }
}

// Called to parse MQTT status changes
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            runInMillis(1000, 'tuyaHubSubscribe')
            break
        case 'Error: Connection lost':
        case 'Error: send error':
            log.error "${device.displayName} MQTT connection error: " + status
            runIn(30, 'tuyaGetHubConfig')
            break
    }
}

// Command to refresh all devices
void refresh() {
    log.info "${device.displayName} refreshing devices"
    tuyaGetDevices()
}

// Command to remove all the child devices
void removeDevices() {
    log.info "${device.displayName} removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

/**
 *  Driver Capabilities Implementation
 */
private static List<Map> parseTuyaStatus(DeviceWrapper dw, Map status, Map functions) {
    if (status.code in brightnessFunctions) {
        Map f = functions[status.code]
        Integer value = Math.floor(remap(status.value, f.min, f.max, 0, 100))
        return [ [ name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%" ] ]
    }

    if (status.code in colourFunctions) {
        Map code = functions[status.code]
        Map bright = getFunctionByCode(functions, brightnessFunctions) ?: code.v
        Map value = jsonCache.computeIfAbsent(status.value) { k -> new JsonSlurper().parseText(k) }
        Integer hue = Math.floor(remap(value.h, code.h.min, code.h.max, 0, 100))
        Integer saturation = Math.floor(remap(value.s, code.s.min, code.s.max, 0, 100))
        Integer level = Math.floor(remap(value.v, bright.min, bright.max, 0, 100))
        String colorName = translateColor(hue, saturation)
        return [
            [ name: 'hue', value: hue, descriptionText: "hue is ${hue}" ],
            [ name: 'saturation', value: saturation, descriptionText: "saturation is ${saturation}" ],
            [ name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%" ],
            [ name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}" ]
        ]
    }

    if (status.code in switchFunctions) {
        String value = status.value ? 'on' : 'off'
        return [ [ name: 'switch', value: value, descriptionText: "switch is ${value}" ] ]
    }

    if (status.code in temperatureFunctions) {
        Map code = functions[status.code]
        Integer value = Math.floor(1000000 / remap(code.max - status.value, code.min, code.max, minMireds, maxMireds))
        return [ [ name: 'colorTemperature', value: value, unit: 'K', descriptionText: "color temperature is ${value}K" ] ]
    }

    if (status.code in workModeFunctions) {
        switch (status.value) {
            case 'white':
            case 'light_white':
                return [ [ name: 'colorMode', value: 'CT', descriptionText: "color mode is CT" ] ]
            case 'colour':
                return [ [ name: 'colorMode', value: 'RGB', descriptionText: "color mode is RGB" ] ]
        }
    }

    return []
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

/**
  *  Tuya Standard Instruction Set Category Mapping to Hubitat Drivers
  *  https://developer.tuya.com/en/docs/iot/standarddescription?id=K9i5ql6waswzq
  */
private static Map mapTuyaCategory(String category) {
    switch (category) {
        case 'cz':    // Socket
        case 'pc':    // Power Switch
            return [ namespace: 'hubitat', name: 'Generic Component Switch' ]
        case 'kg':    // Switch
        case 'tdq':   // Unknown?
            return [ namespace: 'hubitat', name: 'Generic Component Switch' ]
        case 'ykq':   // Remote Control
        case 'tyndj': // Solar Light
            return [ namespace: 'hubitat', name: 'Generic Component CT' ]
        case 'tgq':   // Dimmer Light
        case 'tgkg':  // Dimmer Switch
            return [ namespace: 'hubitat', name: 'Generic Component Dimmer' ]
        case 'dc':    // String Lights
        case 'dd':    // Strip Lights
        case 'dj':    // Light
        case 'fsd':   // Ceiling Fan Light
        case 'fwd':   // Ambient Light
        case 'gyd':   // Motion Sensor Light
        case 'xdd':   // Ceiling Light
            return [ namespace: 'tuya', name: 'Tuya Generic RGBW Light' ]
    }
}

private static Map getFunctions(DeviceWrapper dw) {
    return jsonCache.computeIfAbsent(dw.getDataValue('functions')) {
        k -> new JsonSlurper().parseText(k)
    }
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

private ChildDeviceWrapper createChildDevice(Map d) {
    ChildDeviceWrapper dw = getChildDevice("${device.id}-${d.id}")
    if (!dw) {
        Map driver = mapTuyaCategory(d.category)
        log.info "${device.displayName} creating device ${d.name} using ${driver.name} driver"
        dw = addChildDevice(
            driver.namespace,
            driver.name,
            "${device.id}-${d.id}",
            [
                name: d.product_name,
                label: d.name
            ]
        )
    }

    dw.label = dw.label ?: d.name
    dw.with {
        updateDataValue 'id', d.id
        updateDataValue 'local_key', d.local_key
        updateDataValue 'category', d.category
        updateDataValue 'online', d.online as String
    }

    return childDevice
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "${device.displayName} debug logging disabled"
}

private void parseBizData(String bizCode, Map bizData) {
    String indicator = ' [Offline]'
    ChildDeviceWrapper dw = getChildDevice("${device.id}-${bizData.devId}")
    if (logEnable) { log.debug "${device.displayName} ${bizCode} ${bizData}" }
    switch (bizCode) {
        case 'nameUpdate':
            dw.label = bizData.name
            break
        case 'online':
            dw.updateDataValue('online', 'true')
            if (dw.label.endsWith(indicator)) {
                dw.label -= indicator
            }
            break
        case 'offline':
            dw.updateDataValue('online', 'false')
            if (!dw.label.endsWith(indicator)) {
                dw.label += indicator
            }
            break
        case 'bindUser':
            refresh()
            break
    }
}

private void parseDeviceState(Map d) {
    ChildDeviceWrapper dw = getChildDevice("${device.id}-${d.id ?: d.devId}")
    Map deviceFunctions = getFunctions(dw)
    List events = d.status.collectMany { status -> parseTuyaStatus(dw, status, deviceFunctions) }
    if (logEnable) { log.debug "${device.displayName} [${dw.displayName}] ${d.status} -> ${events}" }
    if (events) {
        dw.parse(events)
    }
}

/**
 *  Tuya Open API Authentication
 *  https://developer.tuya.com/en/docs/cloud/
*/
private void tuyaAuthenticate() {
    if (username && password && appSchema) {
        log.info "${device.displayName} starting Tuya cloud authentication for ${username}"
        MessageDigest digest = MessageDigest.getInstance('MD5')
        String md5pwd = HexUtils.byteArrayToHexString(digest.digest(password.bytes)).toLowerCase()
        Map body = [
            'country_code': countryCode,
            'username': username,
            'password': md5pwd,
            'schema': appSchema
        ]
        state.tokenInfo.access_token = ''
        tuyaPost('/v1.0/iot-01/associated-users/actions/authorized-login', body, 'tuyaAuthenticateResponse')
    } else {
        log.error "${device.displayName} Error - Device must be configured before authentication is enabled"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaAuthenticateResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }

    Map result = response.json.result
    log.info "${device.displayName} received access token valid for ${result.expire_time} seconds"

    state.endPoint = result.platform_url
    state.tokenInfo = [
        access_token: result.access_token,
        refresh_token: result.refresh_token,
        uid: result.uid,
        expire: result.expire_time * 1000 + now(),
    ]

    // Schedule next authentication
    runIn(result.expire_time - 60, 'tuyaAuthenticate')

    // Get MQTT details
    tuyaGetHubConfig()
}

/**
 *  Tuya Open API Device Management
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetDevices() {
    if (!jsonCache.isEmpty()) {
        log.info "${device.displayName} clearing json cache"
        jsonCache.clear()
    }

    log.info "${device.displayName} requesting cloud devices (maximum 100)"
    tuyaGet('/v1.0/iot-01/associated-users/devices', [ 'size': 100 ], 'tuyaGetDevicesResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDevicesResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    data.devices = result.devices
    log.info "${device.displayName} received ${result.devices.size()} cloud devices"
    sendEvent([ name: 'deviceCount', value: result.devices.size() as String ])

    // Create Hubitat devices from Tuya results
    result.devices.each { d ->
        createChildDevice(d)
    }

    // Get device functions in batches of 20
    result.devices.collate(20).each { collection ->
        tuyaGetDeviceFunctions(collection, data)
        pauseExecution(1000)
    }
}

private void tuyaGetDeviceFunctions(List<Map> devices, Map data = [:]) {
    log.info "${device.displayName} requesting cloud device functions for ${devices}"
    tuyaGet('/v1.0/devices/functions', [ 'device_ids': devices*.id.join(',') ], 'tuyaGetDeviceFunctionsResponse', data)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDeviceFunctionsResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    JsonSlurper parser = new JsonSlurper()
    List result = response.json.result
    log.info "${device.displayName} received ${result.size()} cloud function groups"
    result.each { group ->
        Map functions = group.functions.collectEntries { f ->
            String code = f.code
            Map values = parser.parseText(f.values)
            values.type = f.type
            [ (code): values ]
        }
        String json = JsonOutput.toJson(functions)
        group.devices.each { id ->
            ChildDeviceWrapper dw = getChildDevice("${device.id}-${id}")
            dw.updateDataValue('functions', json)
        }
    }

    // Process status updates
    data.devices.each { d -> parseDeviceState(d) }
}

private void tuyaGetState(String deviceID) {
    log.info "${device.displayName} requesting device ${deviceID} state"
    tuyaGet("/v1.0/devices/${deviceID}/status", null, 'tuyaGetStateResponse', [ id: deviceID ])
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetStateResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    data.status = response.json.result
    parseDeviceState(data)
}

private void tuyaSendDeviceCommands(String deviceID, Map...params) {
    log.info "${device.displayName} device ${deviceID} command ${params}"
    if (!state?.tokenInfo?.access_token) {
        log.error "${device.displayName} tuyaSendDeviceCommands Error - Access token is null"
        return
    }
    tuyaPost("/v1.0/devices/${deviceID}/commands", [ 'commands': params ], 'tuyaSendDeviceCommandsResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaSendDeviceCommandsResponse(AsyncResponse response, Map data) {
    tuyaCheckResponse(response)
}

/**
 *  Tuya Open API MQTT Hub
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetHubConfig() {
    log.info "${device.displayName} requesting Tuya MQTT configuration"
    Map body = [
        'uid': state.tokenInfo.uid,
        'link_id': state.uuid,
        'link_type': 'mqtt',
        'topics': 'device',
        'msg_encrypted_version': '1.0'
    ]

    tuyaPost('/v1.0/iot-03/open-hub/access-config', body, 'tuyaGetHubConfigResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetHubConfigResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    state.mqttInfo = result
    tuyaHubConnect()
}

private void tuyaHubConnect() {
    log.info "${device.displayName} connecting to Tuya MQTT hub at ${state.mqttInfo.url}"
    try {
        interfaces.mqtt.connect(
            state.mqttInfo.url,
            state.mqttInfo.client_id,
            state.mqttInfo.username,
            state.mqttInfo.password)
    } catch (e) {
        log.error "${device.displayName} MQTT connection error: " + e
        runIn(30, 'tuyaGetHubConfig')
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void tuyaHubSubscribe() {
    state.mqttInfo.source_topic.each { t ->
        log.info "${device.displayName} subscribing to Tuya MQTT hub ${t.key} topic"
        interfaces.mqtt.subscribe(t.value)
    }

    tuyaGetDevices()
}

/**
 *  Tuya Open API HTTP REST Implementation
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGet(String path, Map query, String callback, Map data = [:]) {
    tuyaRequest('get', path, callback, query, null, data)
}

private void tuyaPost(String path, Map body, String callback, Map data = [:]) {
    tuyaRequest('post', path, callback, null, body, data)
}

/* groovylint-disable-next-line ParameterCount */
private void tuyaRequest(String method, String path, String callback, Map query, Map body, Map data) {
    String accessToken = state?.tokenInfo?.access_token ?: ''
    String stringToSign = tuyaGetStringToSign(method, path, query, body)
    long now = now()
    Map headers = [
      't': now,
      'client_id': access_id,
      'Signature-Headers': 'client_id',
      'sign': tuyaGetSignature(accessToken, now, stringToSign),
      'sign_method': 'HMAC-SHA256',
      'access_token': accessToken,
      'lang': 'en'
    ]

    Map request = [
        uri: state.endPoint,
        path: path,
        query: query,
        contentType: 'application/json',
        headers: headers,
        body: JsonOutput.toJson(body),
        timeout: 5
    ]

    if (logEnable) {
        log.debug("${device.displayName} API ${method.toUpperCase()} ${request}")
    }

    switch (method) {
        case 'get': asynchttpGet(callback, request, data); break
        case 'post': asynchttpPost(callback, request, data); break
    }
}

private boolean tuyaCheckResponse(AsyncResponse response) {
    if (response.status != 200) {
        log.error "${device.displayName} cloud request returned HTTP status ${response.status}"
        return false
    }

    if (response.json?.success != true) {
        log.warn "${device.displayName} cloud request failed: ${response.data}"
        return false
    }

    if (logEnable) {
        log.debug "${device.displayName} API response ${response.json ?: response.data}"
    }

    return true
}

private String tuyaGetSignature(String accessToken, long timestamp, String stringToSign) {
    String message = access_id + accessToken + "${timestamp}" + stringToSign
    Mac sha256HMAC = Mac.getInstance('HmacSHA256')
    sha256HMAC.init(new SecretKeySpec(access_key.bytes, 'HmacSHA256'))
    return HexUtils.byteArrayToHexString(sha256HMAC.doFinal(message.bytes))
}

private String tuyaGetStringToSign(String method, String path, Map query, Map body) {
    String url = query ? path + '?' + query.collect { key, value -> "${key}=${value}" }.join('&') : path
    String headers = 'client_id:' + access_id + '\n'
    String bodyStream = (body == null) ? '' : JsonOutput.toJson(body)
    MessageDigest sha256 = MessageDigest.getInstance('SHA-256')
    String contentSHA256 = HexUtils.byteArrayToHexString(sha256.digest(bodyStream.bytes)).toLowerCase()
    return method.toUpperCase() + '\n' + contentSHA256 + '\n' + headers + '\n' + url
}
