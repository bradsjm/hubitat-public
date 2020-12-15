 /**
  *  Davis WeatherLink Live Hubitat Driver
  *  https://www.davisinstruments.com/weatherlinklive/
  *
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
import hubitat.scheduling.AsyncResponse

metadata {
    definition (
        name: 'Davis WeatherLink Live',
        namespace: 'nrgup',
        author: 'Jonathan Bradshaw'
    ) {
        capability 'Polling'
        capability 'Relative Humidity Measurement'
        capability 'Pressure Measurement'
        capability 'Sensor'
        capability 'Temperature Measurement'
        capability 'Ultraviolet Index'

        // Wind
        attribute 'windDirection', 'number'
        attribute 'windSpeed', 'number'
        attribute 'windGust', 'number'

        // Rain
        attribute 'raining', 'boolean'
        attribute 'rainRate', 'number'
        attribute 'rainDaily', 'number'
        attribute 'rain24h', 'number'

        preferences {
            section {
                input name: 'weatherLinkHost',
                      title: 'WeatherLink Live IP Address',
                      type: 'string',
                      required: true

                input name: 'weatherLinkPort',
                      title: 'WeatherLink Live Port Number',
                      type: 'number',
                      required: true,
                      defaultValue: 80
            }

            section {
                input name: 'transmitterId',
                      title: 'Davis Transmitter ID',
                      type: 'number',
                      required: true,
                      defaultValue: 1

                input name: 'pollInterval',
                      title: 'Polling Interval',
                      type: 'enum',
                      required: true,
                      defaultValue: 1,
                      options: [
                        1: '1 Minute',
                        5: '5 Minutes',
                        10: '10 Minutes',
                        15: '15 Minutes',
                        30: '30 Minutes',
                        60: '60 Minutes'
                    ]

                input name: 'logEnable',
                      title: 'Enable Debug logging',
                      type: 'bool',
                      description: 'Automatically disabled after 30 minutes',
                      required: false,
                      defaultValue: true
            }
        }
    }
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called when the device is removed.
void uninstalled() {
    mqttDisconnect()
    log.info "${device.displayName} driver uninstalled"
}

// Called when the preferences of a device are updated.
void updated() {
    unschedule()

    if (settings.pollInterval > 0) {
        int randomSeconds = new Random(now()).nextInt(60)
        String sched = "${randomSeconds} 0/${pollInterval} * * * ?"
        schedule("${sched}", 'poll')
    }

    if (logEnable) { runIn(1800, 'logsOff') }

    poll()
}

// Poll Weatherlink for latest conditions
void poll() {
    Map params = [
        uri: "http://${weatherLinkHost}:${weatherLinkPort}",
        path: '/v1/current_conditions',
        requestContentType: 'application/json'
    ]
    if (logEnable) { log.trace "Requesting ${params.uri}${params.path}" }
    asynchttpGet('handler', params)
}

private static String splitCamelCase(String s) {
   return s.replaceAll(
      String.format('%s|%s|%s',
         '(?<=[A-Z])(?=[A-Z][a-z])',
         '(?<=[^A-Z])(?=[A-Z])',
         '(?<=[A-Za-z])(?=[^A-Za-z])'
      ),
      ' '
   )
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void handler(AsyncResponse response, Object data) {
    int status = response.status
    if (status == 200) {
        if (logEnable) { log.trace "WeatherLink returned: ${response.data}" }
        Map json = response.json
        if (json?.data) {
            parseWeatherData(json.data)
        } else if (json?.error) {
            log.error "WeatherLink JSON parsing error: ${json.error.code} ${json.error.message}"
        }
    } else {
        log.error "WeatherLink returned HTTP status ${status}"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "${device.displayName} debug logging disabled"
    device.updateSetting('logEnable', [value:'false', type:'bool'])
}

private void parseWeatherData(Map json) {
    List<Map> events = []
    int rainMultiplier = 0
    String rainUnit = ''

    // Transfer data into state for debug
    state.lastResponse = json.conditions

    json.conditions.each { c ->
        switch (c.data_structure_type) {
            case 1:
                if (c.txid != transmitterId) { return }
                if (logEnable) { log.debug "Received ISS #${c.txid} Current Conditions data" }
                switch (c.rain_size) {
                    case 1:
                        rainMultiplier = 0.01
                        rainUnit = 'in'
                        break
                    case 2:
                        rainMultiplier = 0.2
                        rainUnit = 'mm'
                        break
                    case 3:
                        rainMultiplier = 0.1
                        rainUnit = 'mm'
                        break
                    case 4:
                        rainMultiplier = 0.001
                        rainUnit = 'in'
                        break
                }
                events << newEvent('temperature', c.temp, 'F')
                events << newEvent('humidity', c.hum, '%')
                events << newEvent('ultravioletIndex', c.uv_index, 'uvi')
                events << newEvent('windSpeed', c.wind_speed_avg_last_1_min, 'mph')
                events << newEvent('windDirection', c.wind_dir_scalar_avg_last_1_min, 'degrees')
                events << newEvent('windGust', c.wind_speed_hi_last_2_min, 'mph')
                events << newEvent('raining', c.rainfall_last_15_min > 0 ? 'true' : 'false')
                events << newEvent('rainRate', c.rain_rate_hi * rainMultiplier, rainUnit)
                events << newEvent('rainDaily', c.rainfall_daily * rainMultiplier, rainUnit)
                events << newEvent('rain24h', c.rainfall_last_24_hr * rainMultiplier, rainUnit)
                break
            case 2:
                if (c.txid != transmitterId) { return }
                if (logEnable) {
                    log.debug "[${device.displayName}] Received Leaf/Soil Moisture sensor #${c.txid} data"
                }
                break
            case 3:
                if (logEnable) { log.debug "[${device.displayName}] Received Base Barometer data" }
                events << newEvent('pressure', c.bar_sea_level, 'inHg')
                break
            case 4:
                if (logEnable) { log.debug "[${device.displayName}] Received Base Temperature/Humidity data" }
                break
        }
    }

    events.each { e ->
        log.info e.descriptionText
        if (device.currentValue(e.name) != e.value) {
            sendEvent(e)
        }
    }
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
