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
import groovy.json.JsonOutput
import hubitat.scheduling.AsyncResponse
import hubitat.helper.HexUtils

metadata {
    definition (name: 'UniFi Protect', namespace: 'nrgup', author: 'Jonathan Bradshaw', importUrl: '') {
        capability 'Initialize'

        command 'disconnect'

        preferences {
            section('Connection') {
                input name: 'server',
                      type: 'text',
                      title: 'UniFi Protect Server URL',
                      description: '',
                      required: true,
                      defaultValue: '192.168.1.1'

                input name: 'username',
                      type: 'text',
                      title: 'UniFi Protect Login',
                      description: '',
                      required: true,
                      defaultValue: ''

                input name: 'password',
                      type: 'text',
                      title: 'UniFi Protect Password',
                      description: '',
                      required: true,
                      defaultValue: ''
            }

            section {
                input name: 'logEnable',
                      type: 'bool',
                      title: 'Enable debug logging',
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

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"

    if (!settings.username || !settings.password) {
        log.error 'Unable to connect because lusername and password are required'
        return
    }

    disconnect()
    authenticate()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called to parse received socket data
void parse(String message) {
    byte[] buffer = HexUtils.hexStringToByteArray(message)
    decodeUpdatePacket(buffer)
}

// Called with socket status messages
void webSocketStatus(String status) {
    if (logEnable) { log.debug "Websocket status: ${status}" }
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
    disconnect()
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    state.clear()
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

private static Map decodeUpdatePacket(byte[] packet) {
    Map result = [:]
    int dataOffset = getUInt32(packet, 4) + 8 as int // UPDATE_PACKET_HEADER_SIZE is 8
    result.size = (dataOffset + 8 + getUInt32(packet, dataOffset + 4)) // PAYLOAD_SIZE is 4
    if (packet.length != result.size) {
        result.error = "Packet length ${packet.length} does not match header information ${result.size}"
        return result
    }

    return decodeUpdateFrame(packet, dataOffset)
}

private static Map decodeUpdateFrame(byte[] frame, int dataOffset) {
    String packetType
    String payloadFormat

    switch (frame[dataOffset]) {
            case 1:
                packetType = 'action'
                break
            case 2:
                packetType = 'payload'
                break
    }

    switch (frame[dataOffset + 1]) {
            case 1:
                payloadFormat = 'json'
                break
            case 2:
                payloadFormat = 'string'
                break
            case 3:
                payloadFormat = 'buffer'
                break
    }

    boolean compressed = frame[dataOffset + 2] as boolean
    if (compressed) {
    }

    return [
        packetType : packetType,
        payloadFormat : payloadFormat,
        compressed: compressed
    ]
}

private static long getUInt32(byte[] buffer, long start) {
    long result = 0
    for (int i = start; i < start + 4; i++) {
        result *= 256
        result += (buffer[i] & 0xff)
    }

    return result
}

private void authenticate() {
    Map params = [
        uri: "https://${settings.server}/api/auth/login",
        ignoreSSLIssues: true,
        contentType: 'application/json',
        body: JsonOutput.toJson([
            password: settings.password,
            username: settings.username
        ]),
        timeout: 60
    ]
    log.info "Authenticating to UniFi Protect as ${settings.username}"
    asynchttpPost('authHandler', params)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void authHandler(AsyncResponse response, Object data) {
    switch (response.status) {
        case 200:
            log.debug response.headers
            //String csrfToken = response.headers['X-CSRF-Token']
            String cookie = response.headers['Set-Cookie']
            connect(cookie)
            break
        case 400:
        case 401:
             log.error 'Authentication failed! Check username/password and try again.'
             return
        default:
            log.error "Returned HTTP status ${response.status}"
            return
    }
}

private void connect(String cookie, String lastUpdateId = '') {
    try {
        String url = "wss://${settings.server}/proxy/protect/ws/updates?lastUpdateId=${lastUpdateId}"
        log.info 'Connecting to Live Data Stream'
        interfaces.webSocket.connect(url,
            headers: [
                'Cookie': cookie
            ],
            ignoreSSLIssues: true,
            pingInterval: 10 // UniFi OS expects to hear from us every 15 seconds
        )
    } catch (e) {
        log.error "connect error: ${e}"
    }
}

private void disconnect() {
    unschedule()
    log.info 'Disconnecting from Sense Live Data Stream'

    interfaces.webSocket.close()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}
