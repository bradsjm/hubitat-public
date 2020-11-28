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

metadata {
    definition (name: 'BenQ Projector', namespace: 'nrgup', author: 'Jonathan Bradshaw', importUrl: '') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'Polling'
        capability 'PresenceSensor'
        capability 'Switch'

        attribute '3dMode', 'string'
        attribute 'aspectRatio', 'string'
        attribute 'blankMode', 'string'
        attribute 'pictureMode', 'string'
        attribute 'source', 'string'
        attribute 'lampHours', 'number'
        attribute 'lampMode', 'string'

        command 'setSource', [
            [
                name:'Source*',
                type: 'ENUM',
                constraints: [ 'hdmi', 'hdmi2', 'RGB', 'usbreader' ]
            ]
        ]

        preferences {
            section('Connection') {
                input name: 'networkHost',
                      type: 'text',
                      title: 'Hostname/IP',
                      description: '',
                      required: true,
                      defaultValue: ''
                input name: 'networkPort',
                      type: 'number',
                      title: 'Port',
                      description: '',
                      required: true,
                      defaultValue: 5000
//input name: "pollingInterval", type: "number", title: "Poll Interval", description: "Seconds", required: true, defaultValue: 15
            }

            section('Misc') {
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
    unschedule()

    if (!settings.networkHost) {
        log.error 'Unable to connect because host setting not configured'
        return
    }

    connect()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called with socket status messages
void socketStatus(String status) {
    if (status.contains('error')) {
        log.error status
        sendEvent(name: 'presence', value: 'not present', descriptionText: "${device.displayName} {$parts[1]}")
        state.connected = false
        runInMillis(new Random(now()).nextInt(90000), 'connect')
    } else if (logEnable) {
        log.debug status
    }
}

void connected() {
    log.info "Connected to serial bridge at ${settings.networkHost}:${settings.networkPort}"
    sendEvent(name: 'presence', value: 'present', descriptionText: "${device.displayName} is connected")
    state.connected = true
    // get model name
    send('modelname', '?')
    // response takes around 500ms
    pauseExecution(1000)
    // do a single poll for values
    poll()
}

// Called to parse received socket data
void parse(String data) {
    // rawSocket and socket interfaces return Hex encoded string data
    String response = new String(hubitat.helper.HexUtils.hexStringToByteArray(data))
    Match match = response =~ /(?m)^\*(.+)#/
    if (!match.find()) { return }
    String payload = match.group(1)
    if (logEnable) { log.debug "Receive: ${payload}" }
    if (payload.contains('=')) {
        def (String cmd, String value) = match.group(1).split('=')
        updateState(cmd, value)
    }
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
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

void on() {
    log.info "${device.displayName} Switching On"
    send('pow', 'on')
    runIn(20, 'poll')
}

void off() {
    log.info "${device.displayName} Switching Off"
    send('pow', 'off')
    sendEvent(newEvent('switch', 'off'))
    runIn(20, 'poll')
}

void setSource(String name) {
    log.info "${device.displayName} Setting source to ${name}"
    send('sour', name)
}

void poll() {
    unschedule('poll')
    if (!state.connected) { return }

    List<String> cmds = ['pow']
    if (device.currentValue('switch') == 'on') {
        cmds += ['sour', 'ltim', 'lampm', 'blank', 'appmod', 'asp', '3d']
    }
    if (logEnable) { log.info "Polling ${device.displayName} for ${cmds}" }
    cmds.each { cmd ->
        send(cmd, '?')
        // response takes around 500ms
        pauseExecution(1000)
    }

    //if (pollingInterval > 0) {
    //    runIn(pollingInterval, "poll")
    //}
}

private void updateState(String cmd, String value) {
    switch (cmd) {
        case '3D':
            sendEvent(newEvent('3dMode', value.toLowerCase()))
            break
        case 'APPMOD':
            sendEvent(newEvent('pictureMode', value.toLowerCase()))
            break
        case 'ASP':
            sendEvent(newEvent('aspectRatio', value.toLowerCase()))
            break
        case 'BLANK':
            sendEvent(newEvent('blankMode', value.toLowerCase()))
            break
        case 'LAMPM':
            sendEvent(newEvent('lampMode', value.toLowerCase()))
            break
        case 'LTIM':
            sendEvent(newEvent('lampHours', value as Integer))
            break
        case 'MODELNAME':
            log.info "Setting BenQ model to ${value}"
            updateDataValue('model', value)
            updateDataValue('manufacturer', 'BenQ')
            break
        case 'POW':
            sendEvent(newEvent('switch', value.toLowerCase()))
            break
        case 'SOUR':
            sendEvent(newEvent('source', value.toLowerCase()))
            break
        default:
            log.error "Unknown command: ${cmd}"
    }
}

private Map newEvent(String name, Object value, String unit = null) {
    String splitName = splitCamelCase(name)
    String description = "${device.displayName} ${splitName} is ${value}${unit ?: ''}"
    if (logEnable) { log.info description }
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: settings.logTextEnable ? description : ''
    ]
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}

private boolean connect() {
    disconnect()
    unschedule('connect')
    try {
        log.info "Connecting to serial bridge at ${settings.networkHost}:${settings.networkPort}"
        state.connectCount = (state?.connectCount ?: 0) + 1
        interfaces.rawSocket.socket.connect(
            settings.networkHost,
            settings.networkPort as int,
        )
        connected()
        return true
    } catch (e) {
        log.error "connect error: ${e}"
        runInMillis(new Random(now()).nextInt(90000), 'connect')
    }

    return false
}

private void disconnect() {
    log.info "Disconnecting from ${settings?.networkHost}"
    interfaces.rawSocket.close()
    state.connected = false
}

private void send(String cmd, String value) {
    if (state.connected) {
        if (logEnable) { log.debug "Sending: ${cmd}=${value}" }
        interfaces.rawSocket.sendMessage("\r*${cmd}=${value}#\r")
    } else {
        if (logEnable) { log.warn "Not Sending: ${cmd}=${value} (not connected)" }
    }
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

