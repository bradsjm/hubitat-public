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
import com.hubitat.app.ChildDeviceWrapper
import hubitat.scheduling.AsyncResponse
import java.math.RoundingMode

metadata {
    definition (name: 'Sense Energy Monitor',
                namespace: 'nrgup',
                author: 'Jonathan Bradshaw',
                importUrl: ''
    ) {
        capability 'Initialize'
        capability 'EnergyMeter'
        capability 'VoltageMeasurement'

        attribute 'hz', 'number'
        attribute 'leg1', 'number'
        attribute 'leg2', 'number'

        command 'disconnect'

        preferences {
            section {
                input name: 'email',
                      type: 'text',
                      title: 'Sense Email',
                      description: '',
                      required: true

                input name: 'password',
                      type: 'text',
                      title: 'Sense Password',
                      required: true
            }

            section {
                input name: 'changeDelta',
                      title: 'Minimum Energy (W) Change',
                      type: 'enum',
                      required: true,
                      defaultValue: 1,
                      options: [
                        1: '1%',
                        5: '5%',
                        10: '10%',
                        15: '15%'
                    ]

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

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"

    if (!settings.email || !settings.password) {
        log.error 'Unable to connect because login and password are required'
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
/* groovylint-disable-next-line UnusedPrivateMethod, UnusedMethodParameter */
void parse(String data) {
    if (logEnable) { log.debug "Websocket received: ${data}" }
    Map json = parseJson(data)
    switch (json.type) {
        case 'realtime_update':
            parseRealtimeUpdate(json.payload)
            break
    }
}

// Called with socket status messages
void webSocketStatus(String status) {
    if (logEnable) { log.debug "Sense websocket ${status}" }
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

private void authenticate() {
    Map params = [
        uri: 'https://api.sense.com/apiservice/api/v1/',
        path: 'authenticate',
        contentType: 'application/json',
        requestContentType: 'application/x-www-form-urlencoded',
        body: "email=${settings.email}&password=${settings.password}",
        timeout: 5
    ]
    log.info "Authenticating to Sense API as ${settings.email}"
    asynchttpPost('authHandler', params)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void authHandler(AsyncResponse response, Object data) {
    if (response.status == 200) {
        if (logEnable) { log.debug "Sense API returned: ${response.data}" }
        if (response.json) {
            log.info 'Received Sense API access token'
            connect(response.json.monitors[0].id, response.json.access_token)
        }
    } else if (response.status == 401 || response.status == 400) {
        log.error 'Authentication failed! Check email/password and try again.'
    } else {
        log.error "Sense returned HTTP status ${response.status}"
    }
}

private void connect(int monitorId, String token) {
    unschedule('authenticate')
    log.info "Connecting to Sense Live Data Stream for monitor ${monitorId}"
    try {
        String url = "wss://clientrt.sense.com/monitors/${monitorId}/realtimefeed?access_token=${token}"
        if (logEnable) { log.debug "Sense socket url: ${url}" }
        state.connectCount = (state?.connectCount ?: 0) + 1
        interfaces.webSocket.connect(url)
    } catch (e) {
        log.error "connect error: ${e}"
        runInMillis(new Random(now()).nextInt(90000), 'authenticate')
    }
}

private boolean delta(BigDecimal prevValue, BigDecimal newValue) {
    if (!prevValue || !newValue) { return true }
    BigDecimal increase = (1 - (prevValue / newValue)) * 100.0
    boolean result = Math.abs(increase) >= (settings.changeDelta as int)
    return result
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

private Map newEvent(String name, Object value, String unit = null) {
    String splitName = splitCamelCase(name).toLowerCase()
    String description
    if (device.currentValue(name) && value == device.currentValue(name)) {
        description = "${device.displayName} ${splitName} is ${value}${unit ?: ''}"
    } else {
        description = "${device.displayName} ${splitName} was set to ${value}${unit ?: ''}"
    }
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: settings.logTextEnable ? description : ''
    ]
}

private void parseRealtimeUpdate(Map payload) {
    List<Map> events = []

    if (payload.hz) {
        BigDecimal hz = payload.hz.setScale(0, RoundingMode.HALF_UP)
        if (device.currentValue('hz') != hz) { events << newEvent('hz', hz, 'Hz') }
    }

    if (payload.w) {
        BigDecimal w = payload.w.setScale(0, RoundingMode.HALF_UP)
        if (delta(device.currentValue('energy'), w)) { events << newEvent('energy', w, 'W') }
    }

    if (payload.voltage) {
        BigDecimal l1 = payload.voltage[0].setScale(0, RoundingMode.HALF_UP)
        BigDecimal l2 = payload.voltage[1].setScale(0, RoundingMode.HALF_UP)
        BigDecimal avg = ((l1 + l2) / 2).setScale(0, RoundingMode.HALF_UP)
        if (device.currentValue('voltage') != avg) { events << newEvent('voltage', avg, 'V') }
        if (device.currentValue('leg1') != l1) { events << newEvent('leg1', l1, 'V') }
        if (device.currentValue('leg2') != l2) { events << newEvent('leg2', l2, 'V') }
    }

    events.each { e ->
        if (e.descriptionText) { log.info e.descriptionText }
        sendEvent(e)
    }

    payload?.devices.each { device ->
        parseDeviceUpdate(device)
    }
}

private void parseDeviceUpdate(Map deviceData) {
    List<Map> events = []
    String dni = 'sense' + device.id + '-' + deviceData.id
    String label = deviceData.name + ' Energy Meter'
    String name = deviceData.tags['UserDeviceTypeDisplayString']

    ChildDeviceWrapper childDevice = getOrCreateDevice(
        dni,
        label,
        name
    )

    if (childDevice.name != name) {
        childDevice.name = name
    }
    if (childDevice.label != label) {
        childDevice.label = label
    }

    if (deviceData.w) {
        BigDecimal w = deviceData.w.setScale(0, RoundingMode.HALF_UP)
        if (delta(childDevice.currentValue('energy'), w)) { events << newEvent('energy', w, 'W') }
    }

    events << newEvent('icon', deviceData.icon)
    childDevice.parse(events)
}

private ChildDeviceWrapper getOrCreateDevice(String deviceNetworkId, String name, String label) {
    ChildDeviceWrapper childDevice = getChildDevice(deviceNetworkId)
    if (!childDevice) {
        log.info "Creating child device ${name} [${deviceNetworkId}]"
        childDevice = addChildDevice(
            'nrgup',
            'Sense Energy Monitor Child Device',
            deviceNetworkId,
            [
                name: name,
                //isComponent: true
            ]
        )
    } else if (logEnable) {
        log.debug "Autodiscovery: Found child device ${name} [${deviceNetworkId}] (${driverName})"
    }

    if (childDevice.name != name) { childDevice.name = name }
    if (childDevice.label != label) { childDevice.label = label }

    return childDevice
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
