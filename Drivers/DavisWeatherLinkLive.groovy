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
static final String version() {  return "0.1"  }

metadata {
    definition (name: "Davis WeatherLink Live", namespace: "jonathanb", author: "Jonathan Bradshaw", importUrl: "https://raw.githubusercontent.com/bradsjm/hubitat/master/DavisWeatherLinkLive.groovy") {
        capability "Polling"
        capability "Refresh"
        capability "Relative Humidity Measurement"
        capability "Pressure Measurement"
        capability "Sensor"
        capability "Temperature Measurement"
 		capability "Ultraviolet Index"
        capability "Water Sensor"

        // Wind
        attribute "windDirection", "number"
        attribute "windSpeed", "number"
        attribute "windGust", "number"
        // Rain
        attribute "rainRate", "number"
        attribute "rainDaily", "number"
        attribute "rain24h", "number"
    }

    preferences() {
        input "autoPoll", "bool", required: true, title: "Enable Auto Poll", defaultValue: false
        input "pollInterval", "enum", title: "Auto Poll Interval:", required: true, defaultValue: "1 Minute", options: [1:"1 Minute", 5:"5 Minutes", 10:"10 Minutes", 15:"15 Minutes", 30:"30 Minutes", 60:"60 Minutes"]
        input "weatherLinkHost", "string", required: true, title: "WeatherLink Live IP Address"
        input "weatherLinkPort", "number", required: true, title: "WeatherLink Live Port Number", defaultValue: 80
        input "transmitterId", "number", required: true, title: "Davis Transmitter ID", defaultValue: 1
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

void updated() {
    unschedule()
    def pollIntervalVal = (settings?.pollInterval)

    if (autoPoll && pollIntervalVal > 0) {
    	def randomSeconds = new Random(now()).nextInt(60)
        def sched = "${randomSeconds} 0/${pollIntervalVal} * * * ?"
        schedule("${sched}", "poll")
    }

    if (logEnable) runIn(1800, logsOff)

    poll()
}

void refresh() {
    poll()
}

void poll() {
    log.info "[${device?.displayName}] Executing 'poll', location: ${location.name}"
    def params = [
        uri: "http://${weatherLinkHost}:${weatherLinkPort}",
        path: "/v1/current_conditions",
        requestContentType: "application/json"
    ]
    if (logEnable) log.debug "[${device?.displayName}] Request ${params.uri}/${params.path}"
    asynchttpGet("pollHandler", params)
}

void pollHandler(response, data) {
    def status = response.getStatus()
    if (status == 200) {
        if (logEnable) log.debug "[${device?.displayName}] Device returned: ${response.data}"
        def json = response.getJson()
        if (json?.data) {
    		parseWeatherData(json.data)
        } else if (json?.error) {
            log.error "[${device?.displayName}] ${json.error.code} ${json.error.message}"
        } else {
            log.error "[${device?.displayName}] Unable to parse response (valid Json?)"
        }
	} else {
		log.error "[${device?.displayName}] Device returned HTTP status ${status}"
	}
}

void logsOff() {
    log.warn "[${device?.displayName}] debug logging disabled"
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}

void parseWeatherData(Map json) {
    def rainMultiplier = 0
    def rainUnit = ""

    json.conditions.each {
        // Transfer data into state for debug
        it.each {
            if (it.key != "data_structure_type") state[it.key] = it.value
        }

        // Parse data structures
        switch (it.data_structure_type) {
        case 1:
            if (it.txid != transmitterId) return
            if (logEnable) log.debug "[${device?.displayName}] Received ISS #${it.txid} Current Conditions data"
            switch (it.rain_size) {
                case 1:
                    rainMultiplier = 0.01
                    rainUnit = "in"
                    break
                case 2:
                    rainMultiplier = 0.2
                    rainUnit = "mm"
                    break
                case 3:
                    rainMultiplier = 0.1
                    rainUnit = "mm"
                    break
                case 4:
                    rainMultiplier = 0.001
                    rainUnit = "in"
                    break
            }
            sendEvent(name: "temperature", value: it.temp, unit: "F")
            sendEvent(name: "humidity", value: it.hum, unit: "%")
            sendEvent(name: "ultravioletIndex", value: it.uv_index, unit: 'uvi')
            sendEvent(name: "windSpeed", value: it.wind_speed_avg_last_1_min, unit: "MPH")
            sendEvent(name: "windDirection", value: it.wind_dir_scalar_avg_last_1_min, unit: "DEGREE")
            sendEvent(name: "windGust", value: it.wind_speed_hi_last_2_min, unit: "MPH")
            sendEvent(name: "water", value: it.rainfall_last_60_min > 0 ? "wet" : "dry")
            sendEvent(name: "rainRate", value: (it.rain_rate_hi * rainMultiplier), unit: rainUnit)
            sendEvent(name: "rainDaily", value: (it.rainfall_daily * rainMultiplier), unit: rainUnit)
            sendEvent(name: "rain24h", value: (it.rainfall_last_24_hr * rainMultiplier), unit: rainUnit)
            break
        case 2:
            if (it.txid != transmitterId) return
            if (logEnable) log.debug "[${device?.displayName}] Received Leaf/Soil Moisture sensor #${it.txid} data"
            break
        case 3:
            if (logEnable) log.debug "[${device?.displayName}] Received Base Barometer data"
            sendEvent(name: "pressure", value: it.bar_sea_level, unit: "inHg")
            break
        case 4:
            if (logEnable) log.debug "[${device?.displayName}] Received Base Temperature/Humidity data"
            break
        }
    }
}
