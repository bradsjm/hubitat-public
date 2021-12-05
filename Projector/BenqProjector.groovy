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
import hubitat.helper.HexUtils
import java.util.regex.Matcher
import java.util.concurrent.*

metadata {
    definition (name: 'BenQ Projector', namespace: 'nrgup', author: 'Jonathan Bradshaw', importUrl: '') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'Polling'
        capability 'Switch'

        attribute '3dMode', 'string'
        attribute 'aspectRatio', 'string'
        attribute 'blankMode', 'string'
        attribute 'pictureMode', 'string'
        attribute 'source', 'string'
        attribute 'lampHours', 'number'
        attribute 'lampMode', 'string'

        attribute 'retries', 'number'

        command 'setSource', [
            [
                name:'Source*',
                type: 'ENUM',
                constraints: [ 'hdmi', 'hdmi2', 'RGB', 'usbreader' ]
            ]
        ]

        command 'setPictureMode', [
            [
                name:'Picture Mode*',
                type: 'ENUM',
                constraints: [ 'bright', 'vivid', 'cine', 'sport', 'football', 'user1', 'user2' ]
            ]
        ]

        command 'setLampMode', [
            [
                name:'Lamp Mode*',
                type: 'ENUM',
                constraints: [ 'lnor', 'eco', 'seco', 'lampsave' ]
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

// Queue used for response tracking
@Field static queues = new ConcurrentHashMap<String, SynchronousQueue>()

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"
    sendEvent ([ name: 'retries', value: 0 ])
    if (!settings.networkHost) {
        log.error 'Unable to connect because host setting not configured'
        return
    }

    send('modelname', '?')
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called with socket status messages
void socketStatus(String status) {
    if (status.contains('error')) {
        log.error status
    } else if (logEnable) {
        log.debug status
    }
}

// Called to parse received socket data
void parse(String data) {
    Matcher match = new String(HexUtils.hexStringToByteArray(data)) =~ /(?m)^\*(.+)#/
    if (match) {
        String payload = match.group(1)
        if (logEnable) { log.debug "Receive: ${payload}" }
        getQ().offer(payload)
        if (payload.contains('=')) {
            def (String cmd, String value) = payload.split('=')
            updateState(cmd, value)
        }
    }
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

void on() {
    log.info "${device.displayName} Switching On"
    if (send('pow', 'on') == 'POW=ON') {
        runIn(20, 'poll')
    }
}

void off() {
    log.info "${device.displayName} Switching Off"
    if (send('pow', 'off') == 'POW=OFF') {
        sendEvent(newEvent('switch', 'off'))
        runIn(20, 'poll')
    }
}

void setSource(String name) {
    log.info "${device.displayName} Setting source to ${name}"
    send('sour', name)
}

void setLampMode(String name) {
    log.info "${device.displayName} Setting lamp mode to ${name}"
    send('lampm', name)
}

void setPictureMode(String name) {
    log.info "${device.displayName} Setting picture mode to ${name}"
    send('appmod', name)
}

void poll() {
    List<String> cmds = ['pow']
    if (device.currentValue('switch') == 'on') {
        cmds += ['sour', 'ltim', 'lampm', 'blank', 'appmod', 'asp', '3d']
    }

    if (logEnable) { log.info "Polling ${device.displayName} for ${cmds}" }
    cmds.each { cmd ->
        send(cmd, '?')
    }
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

private String send(String cmd, String value, int repeat = 3, int timeout = 1) {
    for (i = 1; i <= repeat; i++) {
        try {
            if (logEnable) { log.debug "${device} sending: ${cmd}=${value}" }
            interfaces.rawSocket.connect(settings.networkHost, settings.networkPort as int)
            interfaces.rawSocket.sendMessage("\r*${cmd}=${value}#\r")
        } catch (e) {
            log.error "${device} send exception: ${e}"
            pauseExecution(250)
            continue
        }

        result = getQ().poll(timeout, TimeUnit.SECONDS)
        if (result) {
            return result
        } else {
            log.warn "${device} command timeout (${i} of ${repeat})"
            int val = (device.currentValue('retries') ?: 0) as int
            sendEvent ([ name: 'retries', value: val + 1 ])
        }
    }

    return null
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

private SynchronousQueue getQ() {
    return queues.computeIfAbsent(device.id) { k -> new SynchronousQueue() };
}
