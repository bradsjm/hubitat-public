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
import hubitat.helper.ColorUtils
import hubitat.helper.HexUtils
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.*
import java.util.regex.Matcher
import java.util.Random

metadata {
    definition (name: 'Tuya Local Switch', namespace: 'tuya', author: 'Jonathan Bradshaw',
                importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/master/Tuya/Local/TuyaLocalSwitch.groovy') {
        singleThreaded: true
        capability 'Actuator'
        capability 'Initialize'
        capability 'Light'
        capability 'Switch'
        capability 'Refresh'

        attribute 'retries', 'number'
        attribute 'errors', 'number'

        command 'sendCustomDps', [
            [
                name: 'Dps',
                type: 'NUMBER'
            ],
            [
                name: 'Value',
                type: 'STRING'
            ]
        ]
    }
}

preferences {
    section {
        input name: 'ipAddress',
              type: 'text',
              title: 'Device IP',
              required: true

        input name: 'switchDps',
              title: 'Switch Dps Id',
              type: 'number',
              required: true,
              range: '1..99',
              defaultValue: '1'

        input name: 'repeat',
              title: 'Command Retries',
              type: 'number',
              required: true,
              range: '0..5',
              defaultValue: '3'

        input name: 'timeoutSecs',
              title: 'Command Timeout (sec)',
              type: 'number',
              required: true,
              range: '1..5',
              defaultValue: '1'

        input name: 'heartbeatSecs',
              title: 'Heartbeat interval (sec)',
              type: 'number',
              required: true,
              range: '0..60',
              defaultValue: '20'

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
@Field static final Map<String, List<String>> tuyaFunctions = [
    'switch': [ 'switch_led', 'switch_led_1', 'light' ]
]

// Queue used for ACK tracking
@Field static queues = new ConcurrentHashMap<String, SynchronousQueue>()

/**
 *  Hubitat Driver Event Handlers
 */
// Called to keep device connection open
void heartbeat() {
    String id = getDataValue('id')
    String localKey = getDataValue('local_key')
    if (logEnable) { log.debug "${device} sending heartbeat" }
    tuyaSendCommand(id, ipAddress, localKey, null, 'HEART_BEAT')
    if (heartbeatSecs) { runIn(heartbeatSecs, 'heartbeat') }
}

// Called when the device is first created
void installed() {
    log.info "${device} driver installed"
}

// Called to initialize
void initialize() {
    sendEvent ([ name: 'retries', value: 0, descriptionText: 'reset' ])
    sendEvent ([ name: 'errors', value: 0, descriptionText: 'reset' ])
    heartbeat()
}

// Component command to turn on device
void on() {
    log.info "Turning ${device} on"

    if (repeatCommand([ (switchDps): true ])) {
        sendEvent([ name: 'switch', value: 'on', descriptionText: 'switch is on' ])
    } else {
        parent?.componentOn(device)
    }
}

// Component command to turn off device
void off() {
    log.info "Turning ${device} off"

    if (repeatCommand([ (switchDps): false ])) {
        sendEvent([ name: 'switch', value: 'off', descriptionText: 'switch is off' ])
    } else {
        parent?.componentOff(device)
    }
}

// parse responses from device
void parse(String message) {
    if (!message) { return }
    String localKey = getDataValue('local_key')
    Map result = tuyaDecode(HexUtils.hexStringToByteArray(message), localKey)
    if (logEnable) { log.debug "${device} received ${result}" }
    if (result.error) {
        log.error "${device} received error ${result.error}"
        int val = (device.currentValue('errors') ?: 0) as int
        sendEvent ([ name: 'errors', value: val + 1, descriptionText: result.error ])
    } else if (result.commandByte == 7) { // COMMAND ACK
        if (!getQ().offer(result)) { log.warn "${device} ACK received but no thread waiting for it" }
    } else if (result.commandByte == 9) { // HEARTBEAT ACK
        if (logEnable) { log.debug "${device} received heartbeat" }
    } else if (result.commandByte == 8 || result.commandByte == 10 ) { // STATUS or QUERY RESULTS
        Map json = new JsonSlurper().parseText(result.text)
        parseDeviceState(json.dps)
    }
}

// parse commands from parent (cloud)
void parse(List<Map> description) {
    description.each { d ->
        if (device.currentValue(d.name) != d.value) {
            if (d.descriptionText && txtEnable) { log.info "${device} ${d.descriptionText}" }
            sendEvent(d)
        }
    }
}

// Component command to refresh device
void refresh() {
    String id = getDataValue('id')
    String localKey = getDataValue('local_key')
    if (!tuyaSendCommand(id, ipAddress, localKey, null, 'DP_QUERY')) {
        parent?.componentRefresh(device)
    } else {
        log.info "Refreshing ${device}"
    }

}

// Send custom Dps command
void sendCustomDps(BigDecimal dps, String value) {
    log.info "Sending DPS ${dps} command ${value}"
    repeatCommand([ (dps): value ])
}

// Socket status updates
void socketStatus(String message) {
    if (message.contains('error')) {
        log.error "${device} socket ${message}"
        int val = (device.currentValue('errors') ?: 0) as int
        sendEvent ([ name: 'errors', value: val + 1, descriptionText: message ])
    } else {
        log.info "${device} socket ${message}"
    }
}

// Called when the device is removed
void uninstalled() {
    log.info "${device} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    log.info "${device} driver configuration updated"
    log.debug settings
    initialize()
    if (logEnable) { runIn(1800, 'logsOff') }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}

#include tuya.tuyaProtocols

private SynchronousQueue getQ() {
    return queues.computeIfAbsent(device.id) { k -> new SynchronousQueue() };
}

private void parseDeviceState(Map dps) {
    if (logEnable) { log.debug "${device} parsing dps ${dps}" }
    List<Map> events = []

    if (dps.containsKey(switchDps as String)) {
        String value = dps[switchDps as String] ? 'on' : 'off'
        events << [ name: 'switch', value: value, descriptionText: "switch is ${value}" ]
    }

    events.each { e ->
        if (device.currentValue(e.name) != e.value) {
            if (e.descriptionText && txtEnable) { log.info "${device} ${e.descriptionText}" }
            sendEvent(e)
        }
    }
}

private Map repeatCommand(Map dps) {
    Map result
    String id = getDataValue('id')
    String localKey = getDataValue('local_key')
    if (!id || !localKey || !ipAddress) return result

    for (i = 1; i <= repeat; i++) {
        try {
            if (logEnable) { log.debug "Sending DPS command ${dps} to ${device}" }
            tuyaSendCommand(id, ipAddress, localKey, dps, 'CONTROL')
        } catch (e) {
            log.error "${device} tuya send exception: ${e}"
            pauseExecution(250)
            continue
        }

        result = getQ().poll(timeoutSecs, TimeUnit.SECONDS)
        if (result) {
            log.info "Received ${device} command acknowledgement"
            break
        } else {
            log.warn "${device} command timeout (${i} of ${repeat})"
            int val = (device.currentValue('retries') ?: 0) as int
            sendEvent ([ name: 'retries', value: val + 1 ])
        }
    }

    return result
}
