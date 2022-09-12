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

/*
 * This driver would not be possible without the original work by vwt12eh8
 * decoding the serial protocol structure. https://github.com/vwt12eh8/hassio-ecoflow
 */
metadata {
    definition(name: 'Ecoflow Delta', namespace: 'ecoflow', author: 'Jonathan Bradshaw') {

        capability 'Battery'
        capability 'EstimatedTimeOfArrival'
        capability 'EnergyMeter'
        capability 'PowerMeter'
        capability 'Sensor'
        capability 'Initialize'

        attribute 'powerIn', 'number'

        attribute NETWORK_ATTRIBUTE, 'enum', [ 'connecting', 'online', 'offline' ]
    }

    preferences {
        input name: 'ipAddress',
                type: 'text',
                title: 'Device IP Address',
                required: true

        input name: 'logEnable',
                type: 'bool',
                title: 'Enable Debug Logging',
                required: false,
                defaultValue: false

        input name: 'logTextEnable',
              type: 'bool',
              title: 'Enable descriptionText logging',
              required: false,
              defaultValue: true
    }
}

import groovy.transform.Field
import hubitat.helper.HexUtils
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Field static final int PORT_NUMBER = 8055
@Field static final String NETWORK_ATTRIBUTE = 'networkStatus'

@Field static final ConcurrentHashMap<String, String> receiveBuffer = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, Map> currentState = new ConcurrentHashMap<>()

public void initialize() {
    state.clear()
    openSocket()
    runIn(30, 'updateState')

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

public void installed() {
    log.info "${device} driver installed"
}

public void logsOff() {
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

public void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

public void uninstalled() {
    closeSocket('driver uninstalled') // make sure the socket is closed when uninstalling
    log.info "${device} driver uninstalled"
}

public void updateState() {
    runIn(30, 'updateState')
    Map v = currentState.get(device.id)
    if (v) {
        Integer battery_level = v.pd?.battery_level
        if (battery_level != null && device.currentValue('battery') != battery_level) {
            sendEvent(name: 'battery', value: battery_level, unit: '%')
        }

        Integer out_power = v.pd?.out_power
        if (out_power != null && device.currentValue('power') != out_power) {
            sendEvent(name: 'power', value: out_power, unit: 'W')
        }

        Integer in_power = v.pd?.in_power
        if (in_power != null && device.currentValue('in_power') != in_power) {
            sendEvent(name: 'powerIn', value: in_power, unit: 'W')
        }

        Integer ac_out_energy = v.pd?.ac_out_energy
        if (ac_out_energy != null && device.currentValue('ac_out_energy') != ac_out_energy) {
            sendEvent(name: 'energy', value: ac_out_energy, unit: 'kWh')
        }

        Duration remain_display = v.pd?.remain_display
        if (remain_display != null) {
            String eta = formatDuration(remain_display)
            if (device.currentValue('eta') != eta) {
                sendEvent(name: 'eta', value: eta)
            }
        }
    }
}

/**
 * ECOFlow Direct Socket Protocol
 */

// parse received protobuf messages - do not change this function name or driver will break
public void parse(String hexString) {
    byte[] bytes
    String buffer = receiveBuffer.get(device.id)
    if (buffer) {
        bytes = HexUtils.hexStringToByteArray(buffer + hexString)
        receiveBuffer.remove(device.id)
    } else {
        bytes = HexUtils.hexStringToByteArray(hexString)
    }
    ByteArrayInputStream stream = new ByteArrayInputStream(bytes)
    while (stream.available() > 4) {
        stream.mark(0)
        // Packets begin with 0xAA 0x02 sequence followed by packet size
        if (stream.read() == 0xAA && stream.read() == 0x02) {
            int size = readIntLE(stream, 2)
            if (stream.available() >= size) {
                byte[] header = new byte[12]
                stream.read(header, 0, 12)
                byte[] body = new byte[size]
                stream.read(body, 0, size)
                parsePacket(header, new ByteArrayInputStream(body))
            } else {
                stream.reset()
                break
            }
        }
    }
    if (stream.available()) {
        byte[] bufferArray = new byte[stream.available()]
        stream.read(bufferArray, 0, bufferArray.size())
        receiveBuffer.put(device.id, HexUtils.byteArrayToHexString(bufferArray))
    }
}

private void parsePacket(byte[] header, ByteArrayInputStream stream) {
    setNetworkStatus('online')
    Map m = currentState.computeIfAbsent(device.id) { k -> [:] }
    if (isPd(header)) {
        m['pd'] = parseDeltaPd(stream)
    } else if (isBms(header)) {
        m['bms'] = parseDeltaBms(stream)
    } else if (isInverter(header)) {
        m['inverter'] = parseDeltaInverter(stream)
    } else if (isEms(header)) {
        m['ems'] = parseDeltaEms(stream)
    } else if (isMppt(header)) {
        m['mppt'] = parseDeltaMppt(stream)
    }
}

// Packet type detectors
private boolean isBms(byte[] header) {
    return (header[8] == 3 && header[10] == 32 && header[11] == 50) ||
        (header[8] == 6 && header[10] == 32 && header[11] == 2) ||
        (header[8] == 6 && header[10] == 32 && header[11] == 50)
}

private boolean isEms(byte[] header) {
    return header[8] == 3 && header[10] == 32 && header[11] == 2
}

private boolean isMppt(byte[] header) {
    return header[8] == 5 && header[10] == 32 && header[11] == 2
}

private boolean isInverter(byte[] header) {
    return header[8] == 4 && header[10] == 32 && header[11] == 2
}

private boolean isPd(byte[] header) {
    return header[8] == 2 && header[10] == 32 && header[11] == 2
}

private boolean isSerialMain(byte[] header) {
    return header[8] in [2, 11] && header[10] == 1 && header[11] == 65
}

// Packet decoders
private static Map parseDeltaBms(ByteArrayInputStream stream) {
    return [
        num: stream.read(),
        battery_type: stream.read(),
        battery_cell_id: stream.read(),
        battery_error: readIntLE(stream, 4),
        battery_version: readVersion(stream),
        battery_level: stream.read(),
        battery_voltage: readIntLE(stream, 4) / 1000,
        battery_current: readIntLE(stream, 4),
        battery_temp: stream.read(),
        _open_bms_idx: stream.read(),
        battery_capacity_design: readIntLE(stream, 4),
        battery_capacity_remain: readIntLE(stream, 4),
        battery_capacity_full: readIntLE(stream, 4),
        battery_cycles: readIntLE(stream, 4),
        _soh: stream.read(),
        battery_voltage_max: readIntLE(stream, 2) / 1000,
        battery_voltage_min: readIntLE(stream, 2) / 1000,
        battery_temp_max: stream.read(),
        battery_temp_min: stream.read(),
        battery_mos_temp_max: stream.read(),
        battery_mos_temp_min: stream.read(),
        battery_fault: stream.read(),
        _sys_stat_reg: stream.read(),
        _tag_chg_current: readIntLE(stream, 4),
        battery_level_f32: readFloatLE(stream, 4),
        battery_in_power: readIntLE(stream, 4),
        battery_out_power: readIntLE(stream, 4),
        battery_remain: Duration.ofMinutes(readIntLE(stream, 4))
    ]
}

private static Map parseDeltaEms(ByteArrayInputStream stream) {
    return [
        _state_charge: stream.read(),
        _chg_cmd: stream.read(),
        _dsg_cmd: stream.read(),
        battery_main_voltage: readIntLE(stream, 4) / 1000,
        battery_main_current: readIntLE(stream, 4) / 1000,
        _fan_level: stream.read(),
        battery_level_max: stream.read(),
        model: stream.read(),
        battery_main_level: stream.read(),
        _flag_open_ups: stream.read(),
        battery_main_warning: stream.read(),
        battery_remain_charge: Duration.ofMinutes(readIntLE(stream, 4)),
        battery_remain_discharge: Duration.ofMinutes(readIntLE(stream, 4)),
        battery_main_normal: stream.read(),
        battery_main_level_f32: readFloatLE(stream, 4),
        _is_connect: readIntLE(stream, 3),
        _max_available_num: stream.read(),
        _open_bms_idx: stream.read(),
        battery_main_voltage_min: readIntLE(stream, 4) / 1000,
        battery_main_voltage_max: readIntLE(stream, 4) / 1000,
        battery_level_min: stream.read(),
        generator_level_start: stream.read(),
        generator_level_stop: stream.read()
    ]
}

private static Map parseDeltaInverter(ByteArrayInputStream stream) {
    return [
        ac_error: readIntLE(stream, 4),
        ac_version: readVersion(stream),
        ac_in_type: stream.read(),
        ac_in_power: readIntLE(stream, 2),
        ac_out_power: readIntLE(stream, 2),
        ac_type: stream.read(),
        ac_out_voltage: readIntLE(stream, 4) / 1000,
        ac_out_current: readIntLE(stream, 4) / 1000,
        ac_out_freq: stream.read(),
        ac_in_voltage: readIntLE(stream, 4) / 1000,
        ac_in_current: readIntLE(stream, 4) / 1000,
        ac_in_freq: stream.read(),
        ac_out_temp: readIntLE(stream, 2),
        dc_in_voltage: readIntLE(stream, 4),
        dc_in_current: readIntLE(stream, 4),
        ac_in_temp: readIntLE(stream, 2),
        fan_state: stream.read(),
        ac_out_state: stream.read(),
        ac_out_xboost: stream.read(),
        ac_out_voltage_config: readIntLE(stream, 4) / 1000,
        ac_out_freq_config: stream.read(),
        fan_config: stream.read(),
        ac_in_pause: stream.read(),
        ac_in_limit_switch: stream.read(),
        ac_in_limit_max: readIntLE(stream, 2),
        ac_in_limit_custom: readIntLE(stream, 2),
        ac_out_timeout: readIntLE(stream, 2)
    ]
}

private static Map parseDeltaMppt(ByteArrayInputStream stream) {
    return [
        dc_in_error: readIntLE(stream, 4),
        dc_in_version: readVersion(stream),
        dc_in_voltage: readIntLE(stream, 4) / 10,
        dc_in_current: readIntLE(stream, 4) / 100,
        dc_in_power: readIntLE(stream, 2) / 10,
        _volt_out: readIntLE(stream, 4),
        _curr_out: readIntLE(stream, 4),
        _watts_out: readIntLE(stream, 2),
        dc_in_temp: readIntLE(stream, 2),
        dc_in_type: stream.read(),
        dc_in_type_config: stream.read(),
        _dc_in_type: stream.read(),
        dc_in_state: stream.read(),
        anderson_out_voltage: readIntLE(stream, 4),
        anderson_out_current: readIntLE(stream, 4),
        anderson_out_power: readIntLE(stream, 2),
        car_out_voltage: readIntLE(stream, 4) / 10,
        car_out_current: readIntLE(stream, 4) / 100,
        car_out_power: readIntLE(stream, 2) / 10,
        car_out_temp: readIntLE(stream, 2),
        car_out_state: stream.read(),
        dc24_temp: readIntLE(stream, 2),
        dc24_state: stream.read(),
        dc_in_pause: stream.read(),
        _dc_in_switch: stream.read(),
        _dc_in_limit_max: readIntLE(stream, 2),
        _dc_in_limit_custom: readIntLE(stream, 2),
    ]
}

private static Map parseDeltaPd(ByteArrayInputStream stream) {
    return [
        model: stream.read(),
        pd_error: readIntLE(stream, 4),
        pd_version: readVersion(stream),
        wifi_version: readVersion(stream),
        wifi_autorecovery: stream.read(),
        battery_level: stream.read(),
        out_power: readIntLE(stream, 2),
        in_power: readIntLE(stream, 2),
        remain_display: Duration.ofMinutes(readIntLE(stream, 4)),
        beep: stream.read(),
        watts_anderson_out: stream.read(),
        usb_out1_power: stream.read(),
        usb_out2_power: stream.read(),
        usbqc_out1_power: stream.read(),
        usbqc_out2_power: stream.read(),
        typec_out1_power: stream.read(),
        typec_out2_power: stream.read(),
        typec_out1_temp: stream.read(),
        typec_out2_temp: stream.read(),
        car_out_state: stream.read(),
        car_out_power: stream.read(),
        car_out_temp: stream.read(),
        standby_timeout: readIntLE(stream, 2),
        lcd_timeout: readIntLE(stream, 2),
        lcd_brightness: stream.read(),
        car_in_energy: readIntLE(stream, 4),
        mppt_in_energy: readIntLE(stream, 4),
        ac_in_energy: readIntLE(stream, 4),
        car_out_energy: readIntLE(stream, 4),
        ac_out_energy: readIntLE(stream, 4),
        usb_time: Duration.ofSeconds(readIntLE(stream, 4)),
        typec_time: Duration.ofSeconds(readIntLE(stream, 4)),
        car_out_time: Duration.ofSeconds(readIntLE(stream, 4)),
        ac_out_time: Duration.ofSeconds(readIntLE(stream, 4)),
        ac_in_time: Duration.ofSeconds(readIntLE(stream, 4)),
        car_in_time: Duration.ofSeconds(readIntLE(stream, 4)),
        mppt_time: Duration.ofSeconds(readIntLE(stream, 4)),
        _none: readIntLE(stream, 2),
        _ext_rj45: stream.read(),
        _ext_infinity: stream.read()
    ]
}

// Decoder helpers
private static String formatDuration(Duration duration) {
    StringBuilder sb = new StringBuilder()
    Duration period = duration
    long days = period.toDays()
    period = period.minusDays(days)
    long hours = period.toHours()
    period = period.minusHours(hours)
    long minutes = period.toMinutes()
    if (days > 0) {
        sb.append("${days}d ")
    }
    if (hours > 0) {
        sb.append("${minutes < 30 ? hours : hours + 1}h")
    } else {
        sb.append("${minutes}m")
    }
    return sb
}

private static float readFloatLE(ByteArrayInputStream stream, int size) {
    return Float.intBitsToFloat(readIntLE(stream, size))
}

private static int readIntLE(ByteArrayInputStream stream, int size) {
    int result = 0
    for (int i = 0; i < size; i++) {
        result |= stream.read() << (8 * i)
    }
    return result;
}

private static String readVersion(ByteArrayInputStream stream) {
    byte[] data = new byte[4]
    stream.read(data, 0, 4)
    return "${Byte.toUnsignedInt(data[3])}.${Byte.toUnsignedInt(data[2])}.${Byte.toUnsignedInt(data[1])}.${Byte.toUnsignedInt(data[0])}"
}


/**
 * Socket IO Implementation
 */
private void openSocket() {
    log.info "${device} opening socket to ${ipAddress}:${PORT_NUMBER}"
    setNetworkStatus('connecting')
    receiveBuffer.remove(device.id)
    try {
        interfaces.rawSocket.connect(settings.ipAddress, PORT_NUMBER, byteInterface: true)
    } catch (e) {
        log.error "${device} error opening socket: " + e
        scheduleConnect()
    }
}

private void closeSocket(String reason) {
    log.info "${device} closing socket to ${ipAddress}:${PORT_NUMBER} (${reason})"
    interfaces.rawSocket.disconnect()
    setNetworkStatus('offline', reason)
}

private boolean isOffline() {
    return device.currentValue(NETWORK_ATTRIBUTE) == 'offline'
}

private void scheduleConnect() {
    return
    state.reconnectDelay = (state.reconnectDelay ?: 1) * 2
    if (state.reconnectDelay > 60) { state.reconnectDelay = 60 }
    int jitter = (int) Math.ceil(state.reconnectDelay * 0.2)
    int interval = state.reconnectDelay + new Random().nextInt(jitter)
    log.info "${device} reconnecting in ${interval} seconds"
    runIn(interval, 'openSocket')
}

private void setNetworkStatus(String state, String reason = '') {
    if (device.currentValue(NETWORK_ATTRIBUTE) != state) {
        sendEvent([ name: NETWORK_ATTRIBUTE, value: state, descriptionText: reason ?: "${device} is ${state}" ])
    }
}

// parse received socket status - do not change this function name or driver will break
public void socketStatus(String message) {
    if (message.contains('error')) {
        log.error "${device} socket error: ${message}"
        closeSocket(message)
        scheduleConnect()
    } else {
        log.info "${device} socket status: ${message}"
    }
}
