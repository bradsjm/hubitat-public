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
import com.hubitat.app.DeviceWrapper
import groovy.transform.Field
import hubitat.scheduling.AsyncResponse
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

metadata {
    definition (name: 'Sense Energy Monitor',
                namespace: 'nrgup',
                author: 'Jonathan Bradshaw',
                importUrl: ''
    ) {
        capability 'Initialize'
        capability 'EnergyMeter'
        capability 'Refresh'
        capability 'PresenceSensor'
        capability 'VoltageMeasurement'

        attribute 'hz', 'number'
        attribute 'amps', 'number'
        attribute 'leg1', 'number'
        attribute 'leg2', 'number'

        command 'disconnect'
        command 'removeDevices'

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
                input name: 'updateInterval',
                      title: 'Display update interval',
                      type: 'enum',
                      required: true,
                      defaultValue: 10,
                      options: [
                        1: '1 second',
                        5: '5 seconds',
                        10: '10 seconds',
                        15: '15 seconds',
                        30: '30 seconds',
                        60: '1 minute',
                        120: '2 minutes',
                        300: '5 minutes'
                      ]

                input name: 'minimumWatts',
                      title: 'Minimum Watts for ON state',
                      type: 'number',
                      required: true,
                      defaultValue: 1
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

// Cache for tracking rolling average for energy
@Field static final ConcurrentHashMap<String, List> rollingAverage = new ConcurrentHashMap<>()

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

// command to remove all the child devices
void removeDevices() {
    log.info "${device.displayName} removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

// Called to parse received socket data
/* groovylint-disable-next-line UnusedPrivateMethod, UnusedMethodParameter */
void parse(String data) {
    //if (logEnable) { log.debug "Websocket received: ${data}" }
    Map json = parseJson(data)
    switch (json.type) {
        case 'realtime_update':
            if (logEnable) { log.debug 'Realtime update received' }
            parseRealtimeUpdate(json.payload)
            break
        case 'error':
            log.error json.payload
            break
    }
}

// Called with socket status messages
void webSocketStatus(String socketStatus) {
    log.trace socketStatus
    if (logEnabled) { log.debug "socketStatus: ${socketStatus}" }

    if (socketStatus.startsWith('status: open')) {
        log.info "${device.displayName} - Connected"
        sendEvent(name: 'presence', value: 'present')
        pauseExecution(500)
        state.remove('delay')
    } else if (socketStatus.startsWith('status: closing')) {
        log.warn "${device.displayName} - Closing connection"
        sendEvent(name: 'presence', value: 'not present')
    } else if (socketStatus.startsWith('failure:')) {
        log.warn "${device.displayName} - Connection has failed with error [${socketStatus}]"
        sendEvent(name: 'presence', value: 'not present')
        autoReconnectWebSocket()
    } else {
        log.warn "${device.displayName} - reconnecting"
        sendEvent(name: 'presence', value: 'not present')
        autoReconnectWebSocket()
    }
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
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

void componentOn(DeviceWrapper device) {
    log.warn "${device} Command Not supported"
}

void componentOff(DeviceWrapper device) {
    log.warn "${device} Command Not supported"
}

void componentRefresh(DeviceWrapper device) {
    List<Map> events = []
    String dni = device.getDeviceNetworkId()
    if (logEnable) log.debug "Refresh ${device} ..."

    BigDecimal dw = rollingAverage(dni + 'w').setScale(0, RoundingMode.HALF_UP)
    String state = dw >= minimumWatts ? 'on' : 'off'

    if (device.currentValue('energy') != dw) {
        events << newEvent(device.displayName, 'energy', dw, 'W')
    }

    if (device.currentValue('switch') != state) {
        events << newEvent(device.displayName, 'switch', state)
    }

    if (events) { device.parse(events) }
}

void refresh() {
    if (logEnable) log.debug 'Refresh ...'
    List<Map> events = []
    String dni = device.getDeviceNetworkId()

    BigDecimal hz = rollingAverage(dni + 'hz').setScale(0, RoundingMode.HALF_UP)
    BigDecimal w = rollingAverage(dni + 'w').setScale(0, RoundingMode.HALF_UP)
    BigDecimal l1 = rollingAverage(dni + 'l1').setScale(0, RoundingMode.HALF_UP)
    BigDecimal l2 = rollingAverage(dni + 'l2').setScale(0, RoundingMode.HALF_UP)
    BigDecimal voltage = ((l1 + l2) / 2).setScale(0, RoundingMode.HALF_UP)
    BigDecimal amps = (w / (l1 + l2)).setScale(0, RoundingMode.HALF_UP)

    events << newEvent(device.displayName, 'hz', hz, 'Hz')
    events << newEvent(device.displayName, 'voltage', voltage, 'V')
    events << newEvent(device.displayName, 'amps', amps, 'A')
    events << newEvent(device.displayName, 'leg1', l1, 'V')
    events << newEvent(device.displayName, 'leg2', l2, 'V')
    events << newEvent(device.displayName, 'energy', w, 'W')

    events.each { e ->
        if (device.currentValue(e.name) != e.value) { 
            if (e.descriptionText) { log.info e.descriptionText }
            sendEvent(e)
        }
    }

    /* groovylint-disable-next-line UnnecessaryGetter */
    getChildDevices().each { childDevice -> componentRefresh(childDevice) }

    runIn(settings.updateInterval as int, 'refresh')
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

private void autoReconnectWebSocket() {
    state.delay = (state.delay ?: 0) + 30
    if (state.delay > 600) { state.delay = 600 }

    log.warn "${device.displayName} - Connection lost, will try to reconnect in ${state.delay} seconds"
    runIn(state.delay, 'authenticate')
}

private void connect(int monitorId, String token) {
    log.info "Connecting to Sense Live Data Stream for monitor ${monitorId}"
    try {
        String url = "wss://clientrt.sense.com/monitors/${monitorId}/realtimefeed?access_token=${token}"
        if (logEnable) { log.debug "Sense socket url: ${url}" }
        interfaces.webSocket.connect(url)
        runIn(3, 'refresh')
        runIn(15 * 60, 'authenticate')
    } catch (e) {
        log.error "connect error: ${e}"
        autoReconnectWebSocket()
    }
}

private void disconnect() {
    unschedule()
    log.info 'Disconnecting from Sense Live Data Stream'
    interfaces.webSocket.close()
    rollingAverage.clear()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}

private Map newEvent(String device, String name, Object value, String unit = null) {
    String description
    description = "${device} ${name} is ${value}${unit ?: ''}"
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: settings.logTextEnable ? description : ''
    ]
}

private void parseRealtimeUpdate(Map payload) {
    String dni = device.getDeviceNetworkId()
    rollingAverage(dni + 'hz', payload.hz)
    rollingAverage(dni + 'w', payload.w)
    rollingAverage(dni + 'l1', payload.voltage[0])
    rollingAverage(dni + 'l2', payload.voltage[1])

    /* groovylint-disable-next-line UnnecessaryGetter */
    Set<String> childIds = getChildDevices()*.getDeviceNetworkId() as Set
    payload.devices.each { dp ->
        childIds.remove(getDni(dp.id))
        updateDevice(dp)
    }

    childIds.each { d-> resetDevice(d) }
}

private String getDni(String id) {
    return 'sense' + device.id + '-' + id
}

private ChildDeviceWrapper getOrCreateDevice(Map payload) {
    String label = payload.name + ' Energy Meter'
    String name = payload.tags['UserDeviceTypeDisplayString']
    String deviceNetworkId = getDni(payload.id)

    if (payload.containsKey('given_location')) {
        name += ' in ' + payload['given_location']
    }

    ChildDeviceWrapper childDevice = getChildDevice(deviceNetworkId)
    if (!childDevice) {
        log.info "Creating child device ${name} [${deviceNetworkId}]"
        childDevice = addChildDevice(
            'hubitat',
            'Generic Component Metering Switch',
            deviceNetworkId,
            [
                name: name,
                //isComponent: true
            ]
        )
    }

    if (childDevice.name != name) { childDevice.name = name }
    if (childDevice.label != label) { childDevice.label = label }

    return childDevice
}

private void resetDevice(String dni) {
    ChildDeviceWrapper childDevice = getChildDevice(dni)
    if (childDevice && childDevice.currentValue('energy')) {
        rollingAverage.remove(dni + 'w')
        childDevice.parse([
            newEvent(childDevice.displayName, 'energy', 0, 'W'),
            newEvent(childDevice.displayName, 'switch', 'off')
        ])
    }
}

private BigDecimal rollingAverage(String key) {
    List<BigDecimal> values = rollingAverage.getOrDefault(key, [])
    return values ? values.sum() / values.size() : 0
}

private BigDecimal rollingAverage(String key, BigDecimal newValue) {
    if (newValue == null) return 0
    int size = (settings.updateInterval as int) ?: 1
    List<BigDecimal> values = rollingAverage.merge(key, [ newValue ], { prev, v ->
        prev.takeRight(size - 1) + v
    })
    return values.sum() / values.size()
}

private void updateDevice(Map payload) {
    if (payload.tags['DeviceListAllowed'] == 'true' && payload.containsKey('w')) {
        BigDecimal w = rollingAverage(getDni(payload.id) + 'w', payload.w)
        ChildDeviceWrapper childDevice = getOrCreateDevice(payload)
        if (w >= settings.minimumWatts && childDevice.currentValue('switch') == 'off') {
            childDevice.parse([newEvent(childDevice.displayName, 'switch', 'on')])
        } else if (w < settings.minimumWatts && childDevice.currentValue('switch') == 'on') {
            childDevice.parse([newEvent(childDevice.displayName, 'switch', 'off')])
        }
    }
}
