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
import groovy.transform.Field
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import java.util.concurrent.ConcurrentLinkedQueue
//import hubitat.helper.ColorUtils
import hubitat.helper.HexUtils
import hubitat.scheduling.AsyncResponse

metadata {
    definition (name: 'Tuya IOT Cloud Bridge', namespace: 'tuya', author: 'Jonathan Bradshaw') {
        capability 'Initialize'
        capability 'Refresh'

        command 'removeDevices'
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

            input name: 'appSchema',
                  title: 'Tuya Application',
                  type: 'enum',
                  required: true,
                  defaultValue: 'tuyaSmart',
                  options: [
                    'tuyaSmart': 'Tuya Smart Life App',
                    'smartlife': 'Smart Life App'
                ]

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: true
        }
    }
}

// Constants
@Field static final List<String> switchFunctions = [ 'switch_led', 'switch_led_1' ]
@Field static final List<String> brightnessFunctions = [ 'bright_value', 'bright_value_v2', 'bright_value_1' ]

// In-Memory status
@Field static final ConcurrentLinkedQueue<Map> statusQueue = new ConcurrentLinkedQueue<>()

/**
 *  Hubitat Driver Event Handlers
 */
void componentOn(DeviceWrapper dw) {
    String id = dw.getDataValue('id')
    Map functions = parseJson(dw.getDataValue('functions'))
    String code = getFunctionCode(functions, switchFunctions)
    tuyaSendCommand(id, [ 'commands': [ [ 'code': code, 'value': true ] ] ])
}

void componentOff(DeviceWrapper dw) {
    String id = dw.getDataValue('id')
    Map functions = parseJson(dw.getDataValue('functions'))
    String code = getFunctionCode(functions, switchFunctions)
    tuyaSendCommand(id, [ 'commands': [ [ 'code': code, 'value': false ] ] ])
}

void componentRefresh(DeviceWrapper d) {
    if (logEnable) { log.debug "${d.displayName} refresh (not implemented)" }
}

/* groovylint-disable-next-line UnusedMethodParameter */
void componentSetLevel(DeviceWrapper dw, BigDecimal level, BigDecimal duration = 0) {
    String id = dw.getDataValue('id')
    Map functions = parseJson(dw.getDataValue('functions'))
    String code = getFunctionCode(functions, brightnessFunctions)
    Integer value = Math.ceil((d.functions.bright_value.max - d.functions.bright_value.min) * level / 100
        + d.functions.bright_value.min)
    tuyaSendCommand(id, [ 'commands': [ [ 'code': code, 'value': value ] ] ])

    // if ((!this.workMode || this.workMode.value === 'white' || this.workMode.value === 'light_white')
    // && this._isHaveDPCodeOfBrightValue()) {
    // code = this.brightValue.code;
    // value = percentage;
    // } else {
    // var saturation;
    // saturation = Math.floor((this.function_dp_range.saturation_range.max - this.function_dp_range.saturation_range.min) * 
    // this.getCachedState(Characteristic.Saturation) / 100 + this.function_dp_range.saturation_range.min); // value 0~100
    // var hue = this.getCachedState(Characteristic.Hue);; // 0-359
    // code = this.colourData.code;
    // value = {
    //     "h": hue,
    //     "s": saturation,
    //     "v": percentage
    // };
}

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"
    state.clear()
    unschedule()

    state.endPoint = 'https://openapi.tuyaus.com' // default US endpoint
    state.tokenInfo = [ access_token: '', expire: now() ] // initialize token
    state.uuid = UUID.randomUUID().toString()

    tuyaAuthenticate()
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

// Called to parse received MQTT data
void parse(String data) {
    Map payload = parseJson(interfaces.mqtt.parseMessage(data).payload)
    SecretKeySpec key = new SecretKeySpec(state.mqttInfo.password[8..23].bytes, 'AES')
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.DECRYPT_MODE, key)
    Map result = parseJson(new String(cipher.doFinal(payload.data.decodeBase64())))
    if (logEnable) { log.debug "${device.displayName} mqtt ${result}" }
    parseDeviceUpdate(result)
}

// Called to parse MQTT status changes
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            runInMillis(1000, 'tuyaSubscribeTopics')
            break
        case 'Error: Connection lost':
        case 'Error: send error':
            log.error 'TuyaOpenAPI MQTT Hub status: ' + status
            break
    }
}

/**
 *  Custom Commands
 */
void refresh() {
    log.info "${device.displayName} refreshing devices"
    tuyaGetDevices()
}

// command to remove all the child devices
void removeDevices() {
    log.info "${device.displayName} removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

/**
 *  Driver Implementation Code
 */
private List<Map> parseFunction(Map status, Map functions) {
    if (status.code in switchFunctions) {
        return [ [ name: 'switch', value: status.value ? 'on' : 'off' ] ]
    }

    if (status.code in brightnessFunctions) {
        Integer value = Math.ceil(
                (status.value - functions.bright_value.min) * 100 /
                (functions.bright_value.max - functions.bright_value.min)
        )
        return [ [ name: 'level', value: value, unit: '%' ] ]
    }

    return []
}

private Map getDeviceDriver(String category) {
    switch (category) {
        case 'cz':
        case 'pc':
            return [ namespace: 'hubitat', name: 'Generic Component Switch' ] // Outlet
        case 'kg':
        case 'tdq':
            return [ namespace: 'hubitat', name: 'Generic Component Switch' ]
        case 'dj':
        case 'dd':
        case 'fwd':
        case 'tgq':
        case 'xdd':
        case 'dc':
        case 'tgkg':
            return [ namespace: 'hubitat', name: 'Generic Component RGBW' ]
    }
}

private void parseDeviceUpdate(Map d) {
    List<Map> events = []
    ChildDeviceWrapper dw = getChildDevice("${device.id}-${d.id ?: d.devId}")
    if (logEnable) { log.debug "${device.displayName} updating ${dw.displayName} with ${d.status}" }

    Map functions = parseJson(dw.getDataValue('functions'))
    d.status.each { s -> events += parseFunction(s, functions) }
    if (events) {
        if (logEnable) { log.debug "${device.displayName} sending events ${events}" }
        dw.parse(events)
    }
}

private ChildDeviceWrapper createChildDevice(Map d) {
    ChildDeviceWrapper dw = getChildDevice("${device.id}-${d.id}")
    if (!dw) {
        Map driver = getDeviceDriver(d.category)
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

private String getFunctionCode(Map functions, List codes) {
    return codes.find { c -> functions.containsKey(c) } ?: codes.first()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "${device.displayName} debug logging disabled"
}

/**
 *  Tuya OpenAPI
 *  https://developer.tuya.com/en/docs/cloud/
*/
private void tuyaGetDevices() {
    log.info "${device.displayName} requesting cloud devices (maximum 100)"
    tuyaGet('/v1.0/iot-01/associated-users/devices', [ 'size': 100 ], 'tuyaGetDevicesResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDevicesResponse(AsyncResponse response, Object data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    log.info "${device.displayName} received ${result.devices.size()} cloud devices"

    // Create Hubitat devices from Tuya results
    result.devices.each { d ->
        createChildDevice(d)
        statusQueue.add([ devId: d.id, status: d.status ])
    }

    // Get device functions in batches of 20
    result.devices.collate(20).each { c ->
        tuyaGetDeviceFunctions(c)
        pauseExecution(1000)
    }
}

private void tuyaGetDeviceFunctions(List<Map> devices) {
    log.info "${device.displayName} requesting cloud device functions"
    tuyaGet('/v1.0/devices/functions', [ 'device_ids': devices*.id.join(',') ], 'tuyaGetDeviceFunctionsResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDeviceFunctionsResponse(AsyncResponse response, Object data) {
    if (!tuyaCheckResponse(response)) { return }
    List result = response.json.result
    log.info "${device.displayName} received ${result.size()} cloud function groups"
    result.each { group ->
        Map functions = group.functions.collectEntries { f ->
            String code = f.code
            Map values = parseJson(f.values)
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
    statusQueue.forEach { i -> parseDeviceUpdate(i) }
}

private void tuyaSendCommand(String deviceID, Map params) {
    tuyaPost("/v1.0/devices/${deviceID}/commands", params, 'tuyaSendCommandResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaSendCommandResponse(AsyncResponse response, Object data) {
    tuyaCheckResponse(response)
}

/**
 * Tuya Open API Authentication Implementation
 */
private void tuyaAuthenticate() {
    if (username && password && appSchema) {
        log.info "${device.displayName} starting Tuya cloud authentication for ${username}"
        state.tokenInfo.access_token = ''
        MessageDigest digest = MessageDigest.getInstance('MD5')
        String md5pwd = HexUtils.byteArrayToHexString(digest.digest(password.bytes)).toLowerCase()
        Map body = [
        'country_code': countryCode,
        'username': username,
        'password': md5pwd,
        'schema': appSchema
        ]
        tuyaPost('/v1.0/iot-01/associated-users/actions/authorized-login', body, 'tuyaAuthenticateResponse')
    } else {
        log.error "${device.displayName} Error - Device must be configured before authentication is enabled"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaAuthenticateResponse(AsyncResponse response, Object data) {
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
 * Tuya Open API MQTT Hub Implementation
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
private void tuyaGetHubConfigResponse(AsyncResponse response, Object data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    state.mqttInfo = result
    tuyaConnectHub()
}

private void tuyaConnectHub() {
    log.info "${device.displayName} connecting to Tuya MQTT hub at ${state.mqttInfo.url}"
    interfaces.mqtt.connect(
        state.mqttInfo.url,
        state.mqttInfo.client_id,
        state.mqttInfo.username,
        state.mqttInfo.password
    )
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void tuyaSubscribeTopics() {
    state.mqttInfo.source_topic.each { t ->
        log.info "${device.displayName} subscribing to Tuya MQTT hub ${t.key} topic"
        interfaces.mqtt.subscribe(t.value)
    }

    tuyaGetDevices()
}

/**
 * Tuya Open API REST Endpoints Implementation
 */
private void tuyaGet(String path, Map params, String callback) {
    tuyaRequest('get', path, callback, params, null)
}

private void tuyaPost(String path, Map params, String callback) {
    tuyaRequest('post', path, callback, null, params)
}

private void tuyaRequest(String method, String path, String callback, Map params = null, Map body = null) {
    String accessToken = state.tokenInfo.access_token
    String stringToSign = tuyaGetStringToSign(method, path, params, body)
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
        query: params,
        contentType: 'application/json',
        headers: headers,
        body: JsonOutput.toJson(body),
        timeout: 5
    ]

    if (logEnable) {
        log.debug("${device.displayName} API ${method} request: ${request}")
    }

    switch (method) {
        case 'get': asynchttpGet(callback, request); break
        case 'post': asynchttpPost(callback, request); break
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
        log.debug "${device.displayName} API response: ${response.data}"
    }

    return true
}

private String tuyaGetSignature(String accessToken, long timestamp, String stringToSign) {
    String message = access_id + accessToken + "${timestamp}" + stringToSign
    Mac sha256HMAC = Mac.getInstance('HmacSHA256')
    sha256HMAC.init(new SecretKeySpec(access_key.bytes, 'HmacSHA256'))
    return HexUtils.byteArrayToHexString(sha256HMAC.doFinal(message.bytes))
}

private String tuyaGetStringToSign(String method, String path, Map params, Map body) {
    String url = params ? path + '?' + params.collect { key, value -> "${key}=${value}" }.join('&') : path
    String headers = 'client_id:' + access_id + '\n'
    String bodyStream = (body == null) ? '' : JsonOutput.toJson(body)
    MessageDigest sha256 = MessageDigest.getInstance('SHA-256')
    String contentSHA256 = HexUtils.byteArrayToHexString(sha256.digest(bodyStream.bytes)).toLowerCase()
    return method.toUpperCase() + '\n' + contentSHA256 + '\n' + headers + '\n' + url
}
