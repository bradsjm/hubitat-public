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
static final String version() { "0.1" }
static final String deviceType() { "Sense Energy Monitor" }

import groovy.json.JsonBuilder
import groovy.transform.Field
import java.math.RoundingMode

metadata {
    definition (name: "${deviceType()}", namespace: "bradsjm", author: "Jonathan Bradshaw", importUrl: "") {
        capability "Initialize"
        capability "EnergyMeter"

        attribute "hz", "number"

        command "disconnect"

        preferences() {
            section("Connection") {
                input name: "email", type: "text", title: "Sense Email", description: "", required: true, defaultValue: ""
                input name: "password", type: "text", title: "Sense Password", description: "", required: true, defaultValue: ""
            }

            section("Misc") {
                input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Automatically disabled after 30 minutes", required: true, defaultValue: true
            }
        }
    }
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver v${version()} initializing"

    if (!settings.email || !settings.password) {
        log.error "Unable to connect because login and password are required"
        return
    }

    disconnect()
    authenticate()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver v${version()} installed"
}

// Called to parse received socket data
def parse(data) {
    Map json = parseJson(data)
    if (logEnable) log.trace "Websocket received ${json.type}"
    switch (json.type) {
        case "realtime_update":
            parseRealtimeUpdate(json.payload)
            break
    }
}

// Called with socket status messages
def webSocketStatus(status) {
    if (logEnable) log.debug "Sense websocket ${status}"
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver v${version()} uninstalled"
    disconnect()
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver v${version()} configuration updated"
    log.debug settings
    state.clear()
    initialize()

    if (logEnable) runIn(1800, "logsOff")
}

private void authenticate() {
    def params = [
        uri: "https://api.sense.com/apiservice/api/v1/",
        path: "authenticate",
        contentType: "application/json",
        requestContentType: "application/x-www-form-urlencoded",
        body: "email=${settings.email}&password=${settings.password}",
        timeout: 5
    ]
    log.info "Authenticating to Sense API as ${settings.email}"
    asynchttpPost("authHandler", params)
}

private void authHandler(response, data) {
    if (response.status == 200) {
        if (logEnable) log.debug "Sense API returned: ${response.data}"
        if (response.json) {
            log.info "Received Sense API access token"
            connect(response.json.monitors[0].id, response.json.access_token)
        }
    } else if (response.status == 401 || response.status == 400) {
        log.error "Authentication failed! Check email/password and try again."
	} else {
		log.error "Sense returned HTTP status ${response.status}"
	}
}

private void connect(int monitor_id, String token) {
    unschedule("authenticate")
    log.info "Connecting to Sense Live Data Stream for monitor ${monitor_id}"
    try {
        String url = "wss://clientrt.sense.com/monitors/${monitor_id}/realtimefeed?access_token=${token}"
        if (logEnable) log.debug "Sense socket url: ${url}"
        state.connectCount = (state?.connectCount ?: 0) + 1
        interfaces.webSocket.connect(url)
        // connected()
    } catch(e) {
        log.error "connect error: ${e}"
        runInMillis(new Random(now()).nextInt(90000), "authenticate")
    }
}

void disconnect() {
    unschedule()
    log.info "Disconnecting from Sense Live Data Stream"
    //sendEvent(name: "deviceState", value: "offline", descriptionText: "${device.displayName} connection closed by driver")

    try {
        interfaces.webSocket.close()
    }
    catch (any)
    {
        // Ignore any exceptions since we are disconnecting
    }
}

private void parseRealtimeUpdate(Map payload) {
    if (logEnable) log.debug "Sense payload: ${payload}"
    if (payload.hz) {
        def hz = payload.hz.setScale(2, RoundingMode.HALF_UP)
        if (hz != device.currentValue("hz")) sendEvent(newEvent("hz", hz, "Hz"))
    }
    if (payload.w) {
        def w = payload.w.setScale(0, RoundingMode.HALF_UP)
        if (w != device.currentValue("w")) sendEvent(newEvent("energy", w, "W"))
    }
}

private Map newEvent(String name, value, unit = null) {
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: "${device.displayName} ${name} is ${value}${unit ?: ''}"
    ]
}

private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting("logEnable", [value: "false", type: "bool"] )
}
