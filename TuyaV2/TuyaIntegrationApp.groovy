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

import com.hubitat.app.exception.UnknownDeviceTypeException
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Field
import groovyx.net.http.HttpResponseDecorator
import hubitat.helper.HexUtils
import hubitat.scheduling.AsyncResponse
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac

definition(
    name: 'Tuya Integration',
    namespace: 'tuya',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Tuya Device Integration using OpenAPI',
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: '',
    installOnOpen: true,
    singleInstance: true,
    singleThreaded: false
)

preferences {
    page(name: 'authorizePage', uninstall: true)
    page(name: 'validatePage', uninstall: true)
    page(name: 'devicesPage', uninstall: true)
    page(name: 'createPage', install: true)
}

/*
 * Configuration UI
 */
public Map authorizePage() {
    state.clear()
    String tuyaIotUrl = 'https://developer.tuya.com/en/docs/iot/Platform_Configuration_smarthome?id=Kamcgamwoevrx'

    return dynamicPage(title: 'Tuya OpenAPI Authorization', nextPage: 'validatePage') {
        section {
            input name: 'appSchema',
                title: 'Tuya Application',
                type: 'enum',
                width: 4,
                required: true,
                defaultValue: 'tuyaSmart',
                options: [
                    'smartlife': 'Smart Life App',
                    'tuyaSmart': 'Tuya Smart Life App'
                ]

            input name: 'appCountry',
                title: 'Tuya Application Country',
                type: 'enum',
                width: 4,
                required: true,
                defaultValue: 'United States',
                options: tuyaCountries.country
        }

        section {
            input name: 'username',
                title: 'Tuya Mobile Application Login',
                type: 'text',
                width: 4,
                required: true


            input name: 'password',
                title: 'Tuya Mobile Application Password',
                type: 'password',
                width: 4,
                required: true
        }

        section {
            paragraph "Follow the Tuya instructions available at <a href=\'${tuyaIotUrl}\' target=\'_blank\'>Tuya Smart Home PaaS Configuration Wizard</a>:"

            input name: 'access_id',
                title: 'Access Id/Client Id',
                type: 'text',
                width: 4,
                required: true

            input name: 'access_key',
                title: 'Access Secret/Client Secret',
                type: 'password',
                width: 4,
                required: true
        }
    }
}

public Map validatePage() {
    if (state.tuyaAuth == null) {
        return dynamicPage(title: 'Validating API Credentials', refreshInterval: 2) {
            section {
                paragraph 'Please wait while the API credentials are validated ...'
            }

            setDatacenter(settings.appCountry)
            tuyaAuthenticateAsync()
        }
    }

    if (tuyaIsAuthenticated()) {
        return dynamicPage(title: 'Validating API Credentials', nextPage: 'devicesPage') {
            section {
                paragraph '<span style=\'color:green;\'>API credentials are validated, select next to continue</span>'
            }
        }
    }

    return dynamicPage(title: 'Validating API Credentials', nextPage: 'authorizePage') {
        section {
            paragraph '<h2>Error validating API credentials</h2>'
            paragraph "<span style=\'color:red;\'>Error ${state.tuyaAuth.code}: ${state.tuyaAuth.msg}</span>"
        }
    }
}

public Map devicesPage() {
    if (state.tuyaDevices == null) {
        return dynamicPage(title: 'Loading Tuya Devices', refreshInterval: 5) {
            section {
                paragraph 'Please wait while Tuya devices are retrieved ...'
            }
            tuyaGetDevicesAsync()
        }
    }

    return dynamicPage(title: 'Select Tuya Devices to Import') {
        section {
            Map categories = state.tuyaDevices.result.list.sort { d -> d.category_name }.groupBy { d -> d.category_name }
            for (group in categories) {
                Map options = group.value.sort { d -> d.name }.collectEntries { d -> [(d.id): d.name] }
                input name: group.key,
                    title: "${group.key} Devices (${group.value.size()})",
                    type: 'enum',
                    options: options,
                    multiple: true
            }
        }
    }
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
}

void logsOff() {
    log.info 'debug logging disabled'
    app.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

// Called when the app is removed.
void uninstalled() {
    log.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }
}

/**
 *  Tuya Open API
 *  https://developer.tuya.com/en/docs/iot/api-request?id=Ka4a8uuo1j4t4
 */

@CompileStatic
private static String tuyaCalculateSignature(String accessId, String accessToken, String nonce, long timestamp, String stringToSign) {
    // https://developer.tuya.com/en/docs/iot/singnature?id=Ka43a5mtx1gsc
    String message = accessId + accessToken + timestamp.toString() + nonce + stringToSign
    Mac sha256HMAC = Mac.getInstance('HmacSHA256')
    sha256HMAC.init(new SecretKeySpec(access_key.bytes, 'HmacSHA256'))
    return HexUtils.byteArrayToHexString(sha256HMAC.doFinal(message.bytes))
}

@CompileStatic
private static String tuyaCreateSigningString(String accessId, String method, String path, Map query, Map body) {
    String url = query ? path + '?' + query.sort().collect { key, value -> "${key}=${value}" }.join('&') : path
    String headers = 'client_id:' + accessId + '\n'
    String bodyStream = (body == null) ? '' : JsonOutput.toJson(body)
    MessageDigest sha256 = MessageDigest.getInstance('SHA-256')
    String contentSHA256 = HexUtils.byteArrayToHexString(sha256.digest(bodyStream.bytes)).toLowerCase()
    return method.toUpperCase() + '\n' + contentSHA256 + '\n' + headers + '\n' + url
}

/**
 *  Tuya Open API Authentication
 *  https://developer.tuya.com/en/docs/cloud/c40fc05907?id=Kawfjj0r2m82l
 */

private boolean tuyaAuthenticate() {
    MessageDigest md5 = MessageDigest.getInstance('MD5')
    String md5pwd = HexUtils.byteArrayToHexString(md5.digest(settings.password.bytes)).toLowerCase()
    HttpResponseDecorator response = tuyaPost([
        uri: state.datacenter.endPoint,
        path: '/v1.0/iot-01/associated-users/actions/authorized-login',
        body: [
            'country_code': state.datacenter.countryCode,
            'username': settings.username,
            'password': md5pwd,
            'schema': settings.appSchema
        ]
    ], settings.access_id, settings.access_key)


}

private void tuyaAuthenticateResponse(AsyncResponse response, Map data) {
    state.tuyaAuth = response.json ?: [:]
    if (state.tuyaAuth.success && state.tuyaAuth.result) {
        state.datacenter.endpoint = state.tuyaAuth.result.platform_url as String
        state.tuyaAuth.expire = now() + ((long) state.tuyaAuth.result.expire_time * 1000)
        log.info "received Tuya access token (valid for ${state.tuyaAuth.result.expire_time}s)"
    } else {
        tuyaLogError(response)
    }
}

private boolean tuyaIsAuthenticated() {
    return (state.tuyaAuth?.success && state.tuyaAuth?.expire > now())
}

/**
 *  Tuya Open API Device Management
 *  https://developer.tuya.com/en/docs/cloud/fc19523d18?id=Kakr4p8nq5xsc
 */
private void tuyaGetDevicesAsync(String lastRowKey = '', Map data = [:]) {
    log.info "requesting Tuya cloud devices (lastRowKey=${lastRowKey})"
    state.tuyaDevices = [:]
    tuyaGetAsync("/v1.2/iot-03/devices", [
        last_row_key: lastRowKey,
        source_type: 'tuyaUser',
        source_id: state.tuyaAuth.result.uid
    ], 'tuyaGetDevicesResponse', data)
}

private void tuyaGetDevicesResponse(AsyncResponse response, Map data) {
    if (response.json?.success) {
        Map result = response.json.result
        log.info "received ${result.list?.size()} cloud devices (has_more: ${result.has_more})"
        log.debug result
        data.devices = (data.devices ?: []) + result.list
        if (result.has_more) {
            pauseExecution(250)
            tuyaGetDevicesAsync(result.last_row_key, data)
            return
        } else {
            state.tuyaDevices = response.json
            state.tuyaDevices.list = data.devices
        }
    } else {
        tuyaLogError(response)
    }
}

// https://developer.tuya.com/en/docs/cloud/device-control?id=K95zu01ksols7#title-29-API%20address
private void tuyaGetDeviceSpecificationsAsync(String deviceID, Map data = [:]) {
    LOG.info "Requesting cloud device specifications for ${deviceID}"
    tuyaGetAsync("/v1.0/devices/${deviceID}/specifications", null, 'tuyaGetDeviceSpecificationsResponse', data)
}

private void tuyaGetDeviceSpecificationsResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == true) {
        Map result = response.json.result
        data.category = result.category
        if (result.functions != null) {
            data.functions = result.functions.collectEntries { f ->
                Map values = jsonParser.parseText(f.values ?: '{}')
                values.type = f.type
                return [ (f.code): values ]
            }
        } else {
            data.functions = [:]
        }
        if (result.status != null) {
            data.statusSet = result.status.collectEntries { f ->
                Map values = jsonParser.parseText(f.values ?: '{}')
                values.type = f.type
                return [ (f.code): values ]
            }
        } else {
            data.statusSet = [:]
        }

        //LOG.debug "Device Data: ${data}"
        createChildDevices(data)
        updateMultiDeviceStatus(data)

        if (device.currentValue('state') != 'ready') {
            sendEvent([ name: 'state', value: 'ready', descriptionText: 'Received device data from Tuya'])
        }
    }
}

/**
 *  Tuya Open API HTTP REST Implementation
 *  https://developer.tuya.com/en/docs/cloud/
 */
private HttpResponseDecorator tuyaPost(Map request, String accessId, String accessKey, String accessToken = '') {
    long now = now()
    String nonce = UUID.randomUUID()
    String stringToSign = tuyaCreateSigningString(accessId, 'post', request.path, request.query, request.body)
    String signature = tuyaCalculateSignature(accessId, accessKey, accessToken ?: '', nonce, now, stringToSign)
    request.headers = [
      't': now,
      'nonce': nonce,
      'client_id': access_id,
      'Signature-Headers': 'client_id',
      'sign': signature,
      'sign_method': 'HMAC-SHA256',
      'access_token': access_token ?: '',
      'lang': 'en' // use zh for china
    ]

    HttpResponseDecorator response
    httpPostJson(request, r -> response = r)
    return response
}

private void tuyaLogError(AsyncResponse response) {
    if (response.hasError()) {
        throw new Exception("Tuya API request error ${response.getErrorMessage()}")
    }

    if (response.status != 200) {
        throw new Exception("Tuya API request HTTP status ${response.status}")
    }

    if (response.json?.success != true) {
        log.warn "Tuya API request failed: ${response.data}"
    }
}

/**
 * Tuya Country to Code and Data Center Mapping
 * https://developer.tuya.com/en/docs/iot/oem-app-data-center-distributed?id=Kafi0ku9l07qb
 */
@Field static final List<Map> tuyaCountries = [
    country('Afghanistan', '93', 'https://openapi.tuyaeu.com'),
    country('Albania', '355', 'https://openapi.tuyaeu.com'),
    country('Algeria', '213', 'https://openapi.tuyaeu.com'),
    country('American Samoa', '1-684', 'https://openapi.tuyaeu.com'),
    country('Andorra', '376', 'https://openapi.tuyaeu.com'),
    country('Angola', '244', 'https://openapi.tuyaeu.com'),
    country('Anguilla', '1-264', 'https://openapi.tuyaeu.com'),
    country('Antarctica', '672', 'https://openapi.tuyaus.com'),
    country('Antigua and Barbuda', '1-268', 'https://openapi.tuyaeu.com'),
    country('Argentina', '54', 'https://openapi.tuyaus.com'),
    country('Armenia', '374', 'https://openapi.tuyaeu.com'),
    country('Aruba', '297', 'https://openapi.tuyaeu.com'),
    country('Australia', '61', 'https://openapi.tuyaeu.com'),
    country('Austria', '43', 'https://openapi.tuyaeu.com'),
    country('Azerbaijan', '994', 'https://openapi.tuyaeu.com'),
    country('Bahamas', '1-242', 'https://openapi.tuyaeu.com'),
    country('Bahrain', '973', 'https://openapi.tuyaeu.com'),
    country('Bangladesh', '880', 'https://openapi.tuyaeu.com'),
    country('Barbados', '1-246', 'https://openapi.tuyaeu.com'),
    country('Belarus', '375', 'https://openapi.tuyaeu.com'),
    country('Belgium', '32', 'https://openapi.tuyaeu.com'),
    country('Belize', '501', 'https://openapi.tuyaeu.com'),
    country('Benin', '229', 'https://openapi.tuyaeu.com'),
    country('Bermuda', '1-441', 'https://openapi.tuyaeu.com'),
    country('Bhutan', '975', 'https://openapi.tuyaeu.com'),
    country('Bolivia', '591', 'https://openapi.tuyaus.com'),
    country('Bosnia and Herzegovina', '387', 'https://openapi.tuyaeu.com'),
    country('Botswana', '267', 'https://openapi.tuyaeu.com'),
    country('Brazil', '55', 'https://openapi.tuyaus.com'),
    country('British Indian Ocean Territory', '246', 'https://openapi.tuyaus.com'),
    country('British Virgin Islands', '1-284', 'https://openapi.tuyaeu.com'),
    country('Brunei', '673', 'https://openapi.tuyaeu.com'),
    country('Bulgaria', '359', 'https://openapi.tuyaeu.com'),
    country('Burkina Faso', '226', 'https://openapi.tuyaeu.com'),
    country('Burundi', '257', 'https://openapi.tuyaeu.com'),
    country('Cambodia', '855', 'https://openapi.tuyaeu.com'),
    country('Cameroon', '237', 'https://openapi.tuyaeu.com'),
    country('Canada', '1', 'https://openapi.tuyaus.com'),
    country('Capo Verde', '238', 'https://openapi.tuyaeu.com'),
    country('Cayman Islands', '1-345', 'https://openapi.tuyaeu.com'),
    country('Central African Republic', '236', 'https://openapi.tuyaeu.com'),
    country('Chad', '235', 'https://openapi.tuyaeu.com'),
    country('Chile', '56', 'https://openapi.tuyaus.com'),
    country('China', '86', 'https://openapi.tuyacn.com'),
    country('Christmas Island', '61'),
    country('Cocos Islands', '61'),
    country('Colombia', '57', 'https://openapi.tuyaus.com'),
    country('Comoros', '269', 'https://openapi.tuyaeu.com'),
    country('Cook Islands', '682', 'https://openapi.tuyaus.com'),
    country('Costa Rica', '506', 'https://openapi.tuyaeu.com'),
    country('Croatia', '385', 'https://openapi.tuyaeu.com'),
    country('Cuba', '53'),
    country('Curacao', '599', 'https://openapi.tuyaus.com'),
    country('Cyprus', '357', 'https://openapi.tuyaeu.com'),
    country('Czech Republic', '420', 'https://openapi.tuyaeu.com'),
    country('Democratic Republic of the Congo', '243', 'https://openapi.tuyaeu.com'),
    country('Denmark', '45', 'https://openapi.tuyaeu.com'),
    country('Djibouti', '253', 'https://openapi.tuyaeu.com'),
    country('Dominica', '1-767', 'https://openapi.tuyaeu.com'),
    country('Dominican Republic', '1-809', 'https://openapi.tuyaus.com'),
    country('East Timor', '670', 'https://openapi.tuyaus.com'),
    country('Ecuador', '593', 'https://openapi.tuyaus.com'),
    country('Egypt', '20', 'https://openapi.tuyaeu.com'),
    country('El Salvador', '503', 'https://openapi.tuyaeu.com'),
    country('Equatorial Guinea', '240', 'https://openapi.tuyaeu.com'),
    country('Eritrea', '291', 'https://openapi.tuyaeu.com'),
    country('Estonia', '372', 'https://openapi.tuyaeu.com'),
    country('Ethiopia', '251', 'https://openapi.tuyaeu.com'),
    country('Falkland Islands', '500', 'https://openapi.tuyaus.com'),
    country('Faroe Islands', '298', 'https://openapi.tuyaeu.com'),
    country('Fiji', '679', 'https://openapi.tuyaeu.com'),
    country('Finland', '358', 'https://openapi.tuyaeu.com'),
    country('France', '33', 'https://openapi.tuyaeu.com'),
    country('French Polynesia', '689', 'https://openapi.tuyaeu.com'),
    country('Gabon', '241', 'https://openapi.tuyaeu.com'),
    country('Gambia', '220', 'https://openapi.tuyaeu.com'),
    country('Georgia', '995', 'https://openapi.tuyaeu.com'),
    country('Germany', '49', 'https://openapi.tuyaeu.com'),
    country('Ghana', '233', 'https://openapi.tuyaeu.com'),
    country('Gibraltar', '350', 'https://openapi.tuyaeu.com'),
    country('Greece', '30', 'https://openapi.tuyaeu.com'),
    country('Greenland', '299', 'https://openapi.tuyaeu.com'),
    country('Grenada', '1-473', 'https://openapi.tuyaeu.com'),
    country('Guam', '1-671', 'https://openapi.tuyaeu.com'),
    country('Guatemala', '502', 'https://openapi.tuyaus.com'),
    country('Guernsey', '44-1481'),
    country('Guinea', '224'),
    country('Guinea-Bissau', '245', 'https://openapi.tuyaus.com'),
    country('Guyana', '592', 'https://openapi.tuyaeu.com'),
    country('Haiti', '509', 'https://openapi.tuyaeu.com'),
    country('Honduras', '504', 'https://openapi.tuyaeu.com'),
    country('Hong Kong', '852', 'https://openapi.tuyaus.com'),
    country('Hungary', '36', 'https://openapi.tuyaeu.com'),
    country('Iceland', '354', 'https://openapi.tuyaeu.com'),
    country('India', '91', 'https://openapi.tuyain.com'),
    country('Indonesia', '62', 'https://openapi.tuyaus.com'),
    country('Iran', '98'),
    country('Iraq', '964', 'https://openapi.tuyaeu.com'),
    country('Ireland', '353', 'https://openapi.tuyaeu.com'),
    country('Isle of Man', '44-1624'),
    country('Israel', '972', 'https://openapi.tuyaeu.com'),
    country('Italy', '39', 'https://openapi.tuyaeu.com'),
    country('Ivory Coast', '225', 'https://openapi.tuyaeu.com'),
    country('Jamaica', '1-876', 'https://openapi.tuyaeu.com'),
    country('Japan', '81', 'https://openapi.tuyaus.com'),
    country('Jersey', '44-1534'),
    country('Jordan', '962', 'https://openapi.tuyaeu.com'),
    country('Kazakhstan', '7', 'https://openapi.tuyaeu.com'),
    country('Kenya', '254', 'https://openapi.tuyaeu.com'),
    country('Kiribati', '686', 'https://openapi.tuyaus.com'),
    country('Kosovo', '383'),
    country('Kuwait', '965', 'https://openapi.tuyaeu.com'),
    country('Kyrgyzstan', '996', 'https://openapi.tuyaeu.com'),
    country('Laos', '856', 'https://openapi.tuyaeu.com'),
    country('Latvia', '371', 'https://openapi.tuyaeu.com'),
    country('Lebanon', '961', 'https://openapi.tuyaeu.com'),
    country('Lesotho', '266', 'https://openapi.tuyaeu.com'),
    country('Liberia', '231', 'https://openapi.tuyaeu.com'),
    country('Libya', '218', 'https://openapi.tuyaeu.com'),
    country('Liechtenstein', '423', 'https://openapi.tuyaeu.com'),
    country('Lithuania', '370', 'https://openapi.tuyaeu.com'),
    country('Luxembourg', '352', 'https://openapi.tuyaeu.com'),
    country('Macao', '853', 'https://openapi.tuyaus.com'),
    country('Macedonia', '389', 'https://openapi.tuyaeu.com'),
    country('Madagascar', '261', 'https://openapi.tuyaeu.com'),
    country('Malawi', '265', 'https://openapi.tuyaeu.com'),
    country('Malaysia', '60', 'https://openapi.tuyaus.com'),
    country('Maldives', '960', 'https://openapi.tuyaeu.com'),
    country('Mali', '223', 'https://openapi.tuyaeu.com'),
    country('Malta', '356', 'https://openapi.tuyaeu.com'),
    country('Marshall Islands', '692', 'https://openapi.tuyaeu.com'),
    country('Mauritania', '222', 'https://openapi.tuyaeu.com'),
    country('Mauritius', '230', 'https://openapi.tuyaeu.com'),
    country('Mayotte', '262', 'https://openapi.tuyaeu.com'),
    country('Mexico', '52', 'https://openapi.tuyaus.com'),
    country('Micronesia', '691', 'https://openapi.tuyaeu.com'),
    country('Moldova', '373', 'https://openapi.tuyaeu.com'),
    country('Monaco', '377', 'https://openapi.tuyaeu.com'),
    country('Mongolia', '976', 'https://openapi.tuyaeu.com'),
    country('Montenegro', '382', 'https://openapi.tuyaeu.com'),
    country('Montserrat', '1-664', 'https://openapi.tuyaeu.com'),
    country('Morocco', '212', 'https://openapi.tuyaeu.com'),
    country('Mozambique', '258', 'https://openapi.tuyaeu.com'),
    country('Myanmar', '95', 'https://openapi.tuyaus.com'),
    country('Namibia', '264', 'https://openapi.tuyaeu.com'),
    country('Nauru', '674', 'https://openapi.tuyaus.com'),
    country('Nepal', '977', 'https://openapi.tuyaeu.com'),
    country('Netherlands', '31', 'https://openapi.tuyaeu.com'),
    country('Netherlands Antilles', '599'),
    country('New Caledonia', '687', 'https://openapi.tuyaeu.com'),
    country('New Zealand', '64', 'https://openapi.tuyaus.com'),
    country('Nicaragua', '505', 'https://openapi.tuyaeu.com'),
    country('Niger', '227', 'https://openapi.tuyaeu.com'),
    country('Nigeria', '234', 'https://openapi.tuyaeu.com'),
    country('Niue', '683', 'https://openapi.tuyaus.com'),
    country('North Korea', '850'),
    country('Northern Mariana Islands', '1-670', 'https://openapi.tuyaeu.com'),
    country('Norway', '47', 'https://openapi.tuyaeu.com'),
    country('Oman', '968', 'https://openapi.tuyaeu.com'),
    country('Pakistan', '92', 'https://openapi.tuyaeu.com'),
    country('Palau', '680', 'https://openapi.tuyaeu.com'),
    country('Palestine', '970', 'https://openapi.tuyaus.com'),
    country('Panama', '507', 'https://openapi.tuyaeu.com'),
    country('Papua New Guinea', '675', 'https://openapi.tuyaus.com'),
    country('Paraguay', '595', 'https://openapi.tuyaus.com'),
    country('Peru', '51', 'https://openapi.tuyaus.com'),
    country('Philippines', '63', 'https://openapi.tuyaus.com'),
    country('Pitcairn', '64'),
    country('Poland', '48', 'https://openapi.tuyaeu.com'),
    country('Portugal', '351', 'https://openapi.tuyaeu.com'),
    country('Puerto Rico', '1-787, 1-939', 'https://openapi.tuyaus.com'),
    country('Qatar', '974', 'https://openapi.tuyaeu.com'),
    country('Republic of the Congo', '242', 'https://openapi.tuyaeu.com'),
    country('Reunion', '262', 'https://openapi.tuyaeu.com'),
    country('Romania', '40', 'https://openapi.tuyaeu.com'),
    country('Russia', '7', 'https://openapi.tuyaeu.com'),
    country('Rwanda', '250', 'https://openapi.tuyaeu.com'),
    country('Saint Barthelemy', '590', 'https://openapi.tuyaeu.com'),
    country('Saint Helena', '290'),
    country('Saint Kitts and Nevis', '1-869', 'https://openapi.tuyaeu.com'),
    country('Saint Lucia', '1-758', 'https://openapi.tuyaeu.com'),
    country('Saint Martin', '590', 'https://openapi.tuyaeu.com'),
    country('Saint Pierre and Miquelon', '508', 'https://openapi.tuyaeu.com'),
    country('Saint Vincent and the Grenadines', '1-784', 'https://openapi.tuyaeu.com'),
    country('Samoa', '685', 'https://openapi.tuyaeu.com'),
    country('San Marino', '378', 'https://openapi.tuyaeu.com'),
    country('Sao Tome and Principe', '239', 'https://openapi.tuyaus.com'),
    country('Saudi Arabia', '966', 'https://openapi.tuyaeu.com'),
    country('Senegal', '221', 'https://openapi.tuyaeu.com'),
    country('Serbia', '381', 'https://openapi.tuyaeu.com'),
    country('Seychelles', '248', 'https://openapi.tuyaeu.com'),
    country('Sierra Leone', '232', 'https://openapi.tuyaeu.com'),
    country('Singapore', '65', 'https://openapi.tuyaeu.com'),
    country('Sint Maarten', '1-721', 'https://openapi.tuyaus.com'),
    country('Slovakia', '421', 'https://openapi.tuyaeu.com'),
    country('Slovenia', '386', 'https://openapi.tuyaeu.com'),
    country('Solomon Islands', '677', 'https://openapi.tuyaus.com'),
    country('Somalia', '252', 'https://openapi.tuyaeu.com'),
    country('South Africa', '27', 'https://openapi.tuyaeu.com'),
    country('South Korea', '82', 'https://openapi.tuyaus.com'),
    country('South Sudan', '211'),
    country('Spain', '34', 'https://openapi.tuyaeu.com'),
    country('Sri Lanka', '94', 'https://openapi.tuyaeu.com'),
    country('Sudan', '249'),
    country('Suriname', '597', 'https://openapi.tuyaus.com'),
    country('Svalbard and Jan Mayen', '4779', 'https://openapi.tuyaus.com'),
    country('Swaziland', '268', 'https://openapi.tuyaeu.com'),
    country('Sweden', '46', 'https://openapi.tuyaeu.com'),
    country('Switzerland', '41', 'https://openapi.tuyaeu.com'),
    country('Syria', '963'),
    country('Taiwan', '886', 'https://openapi.tuyaus.com'),
    country('Tajikistan', '992', 'https://openapi.tuyaeu.com'),
    country('Tanzania', '255', 'https://openapi.tuyaeu.com'),
    country('Thailand', '66', 'https://openapi.tuyaus.com'),
    country('Togo', '228', 'https://openapi.tuyaeu.com'),
    country('Tokelau', '690', 'https://openapi.tuyaus.com'),
    country('Tonga', '676', 'https://openapi.tuyaeu.com'),
    country('Trinidad and Tobago', '1-868', 'https://openapi.tuyaeu.com'),
    country('Tunisia', '216', 'https://openapi.tuyaeu.com'),
    country('Turkey', '90', 'https://openapi.tuyaeu.com'),
    country('Turkmenistan', '993', 'https://openapi.tuyaeu.com'),
    country('Turks and Caicos Islands', '1-649', 'https://openapi.tuyaeu.com'),
    country('Tuvalu', '688', 'https://openapi.tuyaeu.com'),
    country('U.S. Virgin Islands', '1-340', 'https://openapi.tuyaeu.com'),
    country('Uganda', '256', 'https://openapi.tuyaeu.com'),
    country('Ukraine', '380', 'https://openapi.tuyaeu.com'),
    country('United Arab Emirates', '971', 'https://openapi.tuyaeu.com'),
    country('United Kingdom', '44', 'https://openapi.tuyaeu.com'),
    country('United States', '1', 'https://openapi.tuyaus.com'),
    country('Uruguay', '598', 'https://openapi.tuyaus.com'),
    country('Uzbekistan', '998', 'https://openapi.tuyaeu.com'),
    country('Vanuatu', '678', 'https://openapi.tuyaus.com'),
    country('Vatican', '379', 'https://openapi.tuyaeu.com'),
    country('Venezuela', '58', 'https://openapi.tuyaus.com'),
    country('Vietnam', '84', 'https://openapi.tuyaus.com'),
    country('Wallis and Futuna', '681', 'https://openapi.tuyaeu.com'),
    country('Western Sahara', '212', 'https://openapi.tuyaeu.com'),
    country('Yemen', '967', 'https://openapi.tuyaeu.com'),
    country('Zambia', '260', 'https://openapi.tuyaeu.com'),
    country('Zimbabwe', '263', 'https://openapi.tuyaeu.com')
].asImmutable()

@CompileStatic
private static Map country(String country, String countryCode, String endpoint = 'https://openapi.tuyaus.com') {
    return [ country: country, countryCode: countryCode, endpoint: endpoint ]
}

/**
 * Utility Functions
 */
private void setDatacenter(String country) {
    state.datacenter = tuyaCountries.find { c -> c.country == settings.appCountry }
}
