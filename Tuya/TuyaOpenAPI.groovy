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
//import groovy.transform.Field
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
//import hubitat.helper.ColorUtils
import hubitat.helper.HexUtils
import hubitat.scheduling.AsyncResponse

metadata {
    definition (name: 'Tuya Cloud', namespace: 'tuya', author: 'Jonathan Bradshaw') {
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

/**
 *  Hubitat Driver Event Handlers
 */
void componentRefresh(DeviceWrapper d) {
    log.debug "${d.displayName} refresh (not implemented)"
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
    log.info result
    updateChildDevice(result)
}

// Called to parse MQTT status changes
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            // without this delay the `parse` method is never called
            // (it seems that there needs to be some delay after connection to subscribe)
            runIn(1, 'tuyaSubscribeTopics')
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
private Map getDeviceDriver(String category) {
    switch (category) {
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

private ChildDeviceWrapper createChildDevice(Map d) {
    ChildDeviceWrapper childDevice = getChildDevice(device.id + '-' + d.id)
    if (!childDevice) {
        Map driver = getDeviceDriver(d.category)
        log.info "TuyaOpenAPI: Creating device ${d.name} using ${driver.name}"
        childDevice = addChildDevice(
            driver.namespace,
            driver.name,
            device.id + '-' + d.id,
            [
                name: d.name
            ]
        )
    }

    if (childDevice.name != d.name) { childDevice.name = d.name }
    if (childDevice.label == null) { childDevice.label = d.name }

    return childDevice
}

private void updateChildDevice(Map d) {
    String id = d.id ?: d.devId
    if (!id) { return }
    ChildDeviceWrapper childDevice = getChildDevice(device.id + '-' + id)
    if (!childDevice || !d.status) { return }

    List<Map> events = []
    d.status.each { s ->
        switch (s.code) {
            case 'switch_led':
            case 'switch_led_1':
                events << [ name: 'switch', value: s.value ? 'on' : 'off' ]
                break
        }
    }

    if (events) {
        childDevice.parse(events)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}

/**
 *  Tuya OpenAPI
 *  https://developer.tuya.com/en/docs/cloud/
*/
private void tuyaGetDevices() {
    log.info 'TuyaOpenAPI requesting devices'
    tuyaGet('/v1.0/iot-01/associated-users/devices', [ 'size': 100 ], 'tuyaGetDevicesResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDevicesResponse(AsyncResponse response, Object data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    log.info "TuyaOpenAPI received ${result.devices.size()} devices"
    result.devices.each { d ->
        createChildDevice(d)
        updateChildDevice(d)
    }
}

/**
 * Tuya Open API MQTT Hub Implementation
 */
private void tuyaGetHubConfig() {
    log.info 'TuyaOpenAPI requesting MQTT configuration'
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
    log.info 'TuyaOpenAPI received MQTT configuration'
    state.mqttInfo = result
    tuyaConnectHub()
}

private void tuyaConnectHub() {
    log.info 'TuyaOpenAPI connecting to MQTT hub'
    interfaces.mqtt.connect(
        state.mqttInfo.url,
        state.mqttInfo.client_id,
        state.mqttInfo.username,
        state.mqttInfo.password
    )
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void tuyaSubscribeTopics() {
    log.info 'TuyaOpenAPI connected to MQTT hub'
    interfaces.mqtt.subscribe(state.mqttInfo.source_topic.device)
}

/**
 * Tuya Open API Authentication Implementation
 */
private void tuyaAuthenticate() {
    state.tokenInfo.access_token = ''
    MessageDigest digest = MessageDigest.getInstance('MD5')
    String md5pwd = HexUtils.byteArrayToHexString(digest.digest(password.bytes)).toLowerCase()
    Map body = [
      'country_code': countryCode,
      'username': username,
      'password': md5pwd,
      'schema': appSchema
    ]

    if (logEnable) { log.info "TuyaOpenAPI sending authorization request for ${username}" }
    tuyaPost('/v1.0/iot-01/associated-users/actions/authorized-login', body, 'tuyaAuthenticateResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaAuthenticateResponse(AsyncResponse response, Object data) {
    if (!tuyaCheckResponse(response)) { return }

    Map result = response.json.result
    if (logEnable) { log.info "TuyaOpenAPI received access token ${result.access_token}" }

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
        log.trace("TuyaOpenAPI ${method} request: ${request}")
    }

    switch (method) {
        case 'get': asynchttpGet(callback, request); break
        case 'post': asynchttpPost(callback, request); break
    }
}

private boolean tuyaCheckResponse(AsyncResponse response) {
    if (response.status != 200) {
        log.error "TuyaOpenAPI request returned HTTP status ${response.status}"
        return false
    }

    if (response.json?.success != true) {
        log.warn "TuyaOpenAPI request failed: ${response.data}"
        return false
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
