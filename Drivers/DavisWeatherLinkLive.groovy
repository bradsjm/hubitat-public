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

        attribute 'avgWindDirectionLast10min', 'number'
        attribute 'avgWindDirectionLast2min', 'number'
        attribute 'avgWindDirectionLastMin', 'number'
        attribute 'avgWindSpeedLast10min', 'number'
        attribute 'avgWindSpeedLast2min', 'number'
        attribute 'avgWindSpeedLastMin', 'number'
        attribute 'batteryFlag', 'number'
        attribute 'dewPoint', 'number'
        attribute 'dewPointInside', 'number'
        attribute 'feelsLike', 'number'
        attribute 'heatIndex', 'number'
        attribute 'heatIndexInside', 'number'
        attribute 'insideHumidity', 'number'
        attribute 'insideTemperature', 'number'
        attribute 'pressureTrend', 'number'
        attribute 'rainfallDaily', 'number'
        attribute 'rainfallLast15min', 'number'
        attribute 'rainfallLast24hr', 'number'
        attribute 'rainfallLast60min', 'number'
        attribute 'rainfallMonthly', 'number'
        attribute 'rainfallStormEnd', 'string'
        attribute 'rainfallStormStart', 'string'
        attribute 'rainfallStormTotal', 'string'
        attribute 'rainfallYearly', 'number'
        attribute 'raining', 'string'
        attribute 'rainRateLast15min', 'number'
        attribute 'rainRateLastMin', 'number'
        attribute 'rxState', 'number'
        attribute 'wetBulb', 'number'
        attribute 'windChill', 'number'
        attribute 'windGustLast10min', 'number'
        attribute 'windGustLast2min', 'number'

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

                input name: 'logTextEnable',
                      type: 'bool',
                      title: 'Enable descriptionText logging',
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
    state.clear()

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
    if (logEnable) { state.lastResponse = json.conditions }

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
                events << newEvent('dewPoint', c.dew_point, 'F')
                events << newEvent('heatIndex', c.heat_index, 'F')
                events << newEvent('windChill', c.wind_chill, 'F')
                events << newEvent('wetBulb', c.wet_bulb, 'F')
                events << newEvent('feelsLike', c.thw_index, 'F')
                events << newEvent('ultravioletIndex', c.uv_index, 'uvi')
                events << newEvent('rxState', c.rx_state)
                events << newEvent('batteryFlag', c.trans_battery_flag)

                events << newEvent('avgWindSpeedLastMin', c.wind_speed_avg_last_1_min, 'mph')
                events << newEvent('avgWindSpeedLast2min', c.wind_speed_avg_last_2_min, 'mph')
                events << newEvent('avgWindSpeedLast10min', c.wind_speed_avg_last_10_min, 'mph')
                events << newEvent('avgWindDirectionLastMin', c.wind_dir_scalar_avg_last_1_min, 'deg')
                events << newEvent('avgWindDirectionLast2min', c.wind_dir_scalar_avg_last_2_min, 'deg')
                events << newEvent('avgWindDirectionLast10min', c.wind_dir_scalar_avg_last_10_min, 'deg')
                events << newEvent('windGustLast2min', c.wind_speed_hi_last_2_min, 'mph')
                events << newEvent('windGustLast10min', c.wind_speed_hi_last_10_min, 'mph')

                events << newEvent('raining', c.rainfall_last_15_min ?: 0 > 0 ? 'true' : 'false')
                events << newEvent('rainfallStormTotal', c.rain_storm_last ?: 0 * rainMultiplier, rainUnit)
                events << newEvent('rainfallStormStart', c.rain_storm_last_start_at ? new Date((long)c.rain_storm_last_start_at * 1000).toString() : '')
                events << newEvent('rainfallStormEnd', c.rain_storm_last_end_at ? new Date((long)c.rain_storm_last_end_at * 1000).toString() : '')
                events << newEvent('rainRateLastMin', c.rain_rate_hi ?: 0 * rainMultiplier, rainUnit)
                events << newEvent('rainRateLast15min', c.rain_rate_hi_last_15_min ?: 0 * rainMultiplier, rainUnit)
                events << newEvent('rainfallLast15min', c.rainfall_last_15_min ?: 0 * rainMultiplier, rainUnit)
                events << newEvent('rainfallLast60min', c.rainfall_last_60_min ?: 0 * rainMultiplier, rainUnit)
                events << newEvent('rainfallLast24hr', c.rainfall_last_24_hr ?: 0 * rainMultiplier, rainUnit)
                events << newEvent('rainfallDaily', c.rainfall_daily ?: 0 * rainMultiplier, rainUnit)
                events << newEvent('rainfallMonthly', c.rainfall_monthly ?: 0 * rainMultiplier, rainUnit)
                events << newEvent('rainfallYearly', c.rainfall_yearly ?: 0 * rainMultiplier, rainUnit)
                break
            case 2:
                if (c.txid != transmitterId) { return }
                if (logEnable) {
                    log.debug "[${device.displayName}] Skipping received Leaf/Soil Moisture sensor #${c.txid} data"
                }
                break
            case 3:
                if (logEnable) { log.debug "[${device.displayName}] Received Base Barometer data" }
                events << newEvent('pressure', c.bar_sea_level, 'inHg')
                events << newEvent('pressureTrend', c.bar_trend, 'inHg')
                break
            case 4:
                if (logEnable) { log.debug "[${device.displayName}] Received Base Temperature/Humidity data" }
                events << newEvent('insideTemperature', c.temp_in, 'F')
                events << newEvent('insideHumidity', c.hum_in, '%')
                events << newEvent('dewPointInside', c.dew_point_in, 'F')
                events << newEvent('heatIndexInside', c.heat_index_in, 'F')
                break
        }
    }

    events.each { e ->
        if (e.descriptionText) { log.info e.descriptionText }
        if (e.value != null && device.currentValue(e.name) != e.value) {
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
