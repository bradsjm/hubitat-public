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
        capability 'TemperatureMeasurement'
        capability 'Initialize'
        capability 'VoltageMeasurement'

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
        state.pd = v.pd
        state.bms = v.bms
        state.inverter = v.inverter
        state.ems = v.ems
        state.mppt = v.mppt
        state.generator = v.generator

        Integer battery_level = v.pd?.soc
        if (battery_level != null && device.currentValue('battery') != battery_level) {
            sendEvent(name: 'battery', value: battery_level, unit: '%')
        }

        Integer outputWatts = v.inverter?.outputWatts
        if (outputWatts != null && device.currentValue('power') != outputWatts) {
            sendEvent(name: 'power', value: outputWatts, unit: 'W')
        }

        Integer inverterOutAmp = v.inverter?.inverterOutAmp
        if (inverterOutAmp != null && device.currentValue('amperage') != inverterOutAmp) {
            sendEvent(name: 'amperage', value: inverterOutAmp, unit: 'A')
        }

        Integer inputWatts = v.inverter?.inputWatts
        if (inputWatts != null && device.currentValue('powerIn') != inputWatts) {
            sendEvent(name: 'powerIn', value: inputWatts, unit: 'W')
        }

        Integer wattsOutTotal = v.pd?.wattsOutTotal
        if (wattsOutTotal != null && device.currentValue('energy') != wattsOutTotal) {
            sendEvent(name: 'energy', value: wattsOutTotal, unit: 'kWh')
        }

        Integer voltage = v.bms?.voltage
        if (voltage != null && device.currentValue('voltage') != voltage) {
            sendEvent(name: 'voltage', value: voltage, unit: 'V')
        }

        Integer inverterOutFreq = v.inverter?.inverterOutFreq
        if (inverterOutFreq != null && device.currentValue('frequency') != inverterOutFreq) {
            sendEvent(name: 'frequency', value: inverterOutFreq, unit: 'Hz')
        }

        Integer inverterOutTemp = v.inverter?.inverterOutTemp
        if (inverterOutTemp != null && device.currentValue('temperature') != inverterOutTemp) {
            sendEvent(name: 'temperature', value: inverterOutTemp, unit: 'C')
        }

        Duration remain_display = v.pd?.remainTime
        if (remain_display != null) {
            String eta = formatDuration(remain_display)
            if (device.currentValue('eta') != eta) {
                sendEvent(name: 'eta', value: eta)
            }
        }
    }
}

// chargeState 3 - charging? 1 - idle? 2 - ? discharge?

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
    } else if (isGenerator(header)) {
        m['generator'] = parseGenerator(stream)
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

private boolean isGenerator(byte[] header) {
    return header[8] == 8 && header[10] == 32 && header[11] == 2
}

// Packet decoders
private static Map parseDeltaBms(ByteArrayInputStream stream) {
    return [
        num: stream.read(),
        type: stream.read(),
        cellId: stream.read(),
        errorCode: readIntLE(stream, 4),
        sysVersion: readVersion(stream),
        soc: stream.read(),
        voltage: readIntLE(stream, 4) / 1000,
        amp: readIntLE(stream, 4),
        temp: stream.read(),
        openBmsIdx: stream.read(),
        batteryCapacityDesign: readIntLE(stream, 4),
        batteryCapacityRemain: readIntLE(stream, 4),
        batteryCapacityFull: readIntLE(stream, 4),
        batteryCycles: readIntLE(stream, 4),
        soh: stream.read(),
        cellVoltageMax: readIntLE(stream, 2) / 1000,
        cellVoltageMin: readIntLE(stream, 2) / 1000,
        cellTempMax: stream.read(),
        cellTempMin: stream.read(),
        mosTempMax: stream.read(),
        mosTempMin: stream.read(),
        bmsFault: stream.read(),
        bqSysStatReg: stream.read(),
        tagChargeAmp: readIntLE(stream, 4),
        socF32: readFloatLE(stream, 4),
        inputWatts: readIntLE(stream, 4),
        outputWatts: readIntLE(stream, 4),
        remainTime: Duration.ofMinutes(readIntLE(stream, 4))
    ]
}

private static Map parseDeltaEms(ByteArrayInputStream stream) {
    return [
        chargeState: stream.read(),
        chargeCommand: stream.read(),
        dischargeCommand: stream.read(),
        chargeVoltage: readIntLE(stream, 4) / 1000,
        chargeAmp: readIntLE(stream, 4) / 1000,
        fanLevel: stream.read(),
        maxChargeSoc: stream.read(),
        bmsModel: stream.read(),
        lcdShowSoc: stream.read(),
        openUpsFlag: stream.read(),
        bmsWarningState: stream.read(),
        chargeRemainTime: Duration.ofMinutes(readIntLE(stream, 4)),
        dischargeRemainTime: Duration.ofMinutes(readIntLE(stream, 4)),
        emsIsNormalFlag: stream.read(),
        lcdShowSocF32: readFloatLE(stream, 4),
        bmsIsConnected: readIntLE(stream, 3),
        maxAvailableNum: stream.read(),
        openBmsIdx: stream.read(),
        batteryMainVoltageMin: readIntLE(stream, 4) / 1000,
        batteryMainVoltageMax: readIntLE(stream, 4) / 1000,
        minDischargeSoc: stream.read(),
        generatorLevelStart: stream.read(),
        generatorLevelStop: stream.read()
    ]
}

private static Map parseDeltaInverter(ByteArrayInputStream stream) {
    return [
        errorCode: readIntLE(stream, 4),
        sysVersion: readVersion(stream),
        chargerType: stream.read(),
        inputWatts: readIntLE(stream, 2),
        outputWatts: readIntLE(stream, 2),
        inverterType: stream.read(),
        inverterOutVoltage: readIntLE(stream, 4) / 1000,
        inverterOutAmp: readIntLE(stream, 4) / 1000,
        inverterOutFreq: stream.read(),
        acInVoltage: readIntLE(stream, 4) / 1000,
        acInAmp: readIntLE(stream, 4) / 1000,
        acInFreq: stream.read(),
        inverterOutTemp: readIntLE(stream, 2),
        dcInVoltage: readIntLE(stream, 4),
        dcInAmp: readIntLE(stream, 4),
        dcInTemp: readIntLE(stream, 2),
        fanState: stream.read(),
        cfgAcEnabled: stream.read(),
        cfgAcXboost: stream.read(),
        cfgAcOutVoltage: readIntLE(stream, 4) / 1000,
        cfgAcOutFreq: stream.read(),
        cfgAcWorkMode: stream.read(),
        cfgPauseFlag: stream.read(),
        acDipSwitch: stream.read(),
        cfgFastChargeWatts: readIntLE(stream, 2),
        cfgSlowChargrWatts: readIntLE(stream, 2),
        standbyMins: readIntLE(stream, 2),
        dischargeType: stream.read(),
        acPassByAutoEnable: stream.read()
    ]
}

private static Map parseDeltaMppt(ByteArrayInputStream stream) {
    return [
        faultCode: readIntLE(stream, 4),
        softwareVersion: readVersion(stream),
        inVoltage: readIntLE(stream, 4) / 10,
        inAmp: readIntLE(stream, 4) / 100,
        inWatts: readIntLE(stream, 2) / 10,
        outVoltage: readIntLE(stream, 4),
        outAmps: readIntLE(stream, 4),
        outWatts: readIntLE(stream, 2),
        mpptTemp: readIntLE(stream, 2),
        xt60ChargeType: stream.read(),
        configChargeType: stream.read(),
        chargeType: stream.read(),
        chargeState: stream.read(),
        andersonVoltage: readIntLE(stream, 4),
        andersonAmp: readIntLE(stream, 4),
        andersonWatts: readIntLE(stream, 2),
        carVoltage: readIntLE(stream, 4) / 10,
        carAmps: readIntLE(stream, 4) / 100,
        carWatts: readIntLE(stream, 2) / 10,
        carTemp: readIntLE(stream, 2),
        carState: stream.read(),
        dc24Temp: readIntLE(stream, 2),
        dc24State: stream.read(),
        chargePauseFlag: stream.read(),
        dcDipSwitch: stream.read(),
        fastChargeWatts: readIntLE(stream, 2),
        slowChargeWatts: readIntLE(stream, 2),
    ]
}

private static Map parseDeltaPd(ByteArrayInputStream stream) {
    return [
        model: stream.read(),
        errCode: readIntLE(stream, 4),
        sysVersion: readVersion(stream),
        wifiVersion: readVersion(stream),
        wifiAutoRecovery: stream.read(),
        soc: stream.read(),
        wattsOutTotal: readIntLE(stream, 2),
        wattsInTotal: readIntLE(stream, 2),
        remainTime: Duration.ofMinutes(readIntLE(stream, 4)),
        beepMode: stream.read(),
        dcOutState: stream.read(),
        usb1Watts: stream.read(),
        usb2Watts: stream.read(),
        qcUsb1Watts: stream.read(),
        qcUsb2Watts: stream.read(),
        typec1Watts: stream.read(),
        typec2Watts: stream.read(),
        typec1Temp: stream.read(),
        typec2Temp: stream.read(),
        carState: stream.read(),
        carWatts: stream.read(),
        carTemp: stream.read(),
        standbyMinutes: readIntLE(stream, 2),
        lcdOffSec: readIntLE(stream, 2),
        lcdBrightness: stream.read(),
        chargePowerDc: readIntLE(stream, 4),
        chargeSunPower: readIntLE(stream, 4),
        chargePowerAc: readIntLE(stream, 4),
        dischargePowerDc: readIntLE(stream, 4),
        dischargePowerAc: readIntLE(stream, 4),
        usbUsedTime: Duration.ofSeconds(readIntLE(stream, 4)),
        typecUsedTime: Duration.ofSeconds(readIntLE(stream, 4)),
        carUsedTime: Duration.ofSeconds(readIntLE(stream, 4)),
        inverterUsedTime: Duration.ofSeconds(readIntLE(stream, 4)),
        dcInUsedTime: Duration.ofSeconds(readIntLE(stream, 4)),
        mpptUsedTime: Duration.ofSeconds(readIntLE(stream, 4)),
        _reserved_: readIntLE(stream, 2),
        extRj45Port: stream.read(),
        extInfinityPort: stream.read()
    ]
}

private static Map parseGenerator(ByteArrayInputStream stream) {
    return [
        num: stream.read(),
        type: stream.read(),
        cellId: stream.read(),
        errorCode: readIntLE(stream, 4),
        sysVersion: readVersion(stream),
        oilValue: stream.read(),
        totalPower: readIntLE(stream, 4) / 1000,
        acPower: readIntLE(stream, 4) / 1000,
        dcPower: readIntLE(stream, 4) / 1000,
        remainTime: Duration.ofMinutes(readIntLE(stream, 4)),
        motorUseTime: Duration.ofMinutes(readIntLE(stream, 4)),
        acState: stream.read(),
        dcState: stream.read(),
        oilMaxOutPower: readIntLE(stream, 2),
        dcVoltage: readIntLE(stream, 2),
        dcCurrent: readIntLE(stream, 2),
        acVoltage: readIntLE(stream, 2),
        acCurrent: readIntLE(stream, 2),
        temp: stream.read()
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
