/*
 * Custom Davis WeatherLink Live Hubitat Driver
 * https://www.davisinstruments.com/weatherlinklive/
 *
 * Copyright 2019 @jonathanb (Jonathan Bradshaw)
 */

public static String version() {  return "0.1"  }

metadata {
    definition (name: "Davis WeatherLink Live", namespace: "jonathanb", author: "Jonathan Bradshaw", importUrl: "") {
        capability "Polling"
        capability "Refresh"
        capability "Relative Humidity Measurement"
        capability "Pressure Measurement"
        capability "Sensor"
        capability "Temperature Measurement"
 		capability "Ultraviolet Index"
        capability "Water Sensor"        
        
        attribute "lastUpdated", "string"
        
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
    log.info "[WeatherLink] Executing 'poll', location: ${location.name}"
    def params = [
        uri: "http://${weatherLinkHost}:${weatherLinkPort}",
        path: "/v1/current_conditions",
        requestContentType: "application/json"
    ]
    if (logEnable) log.debug "[WeatherLink] Request ${params.uri}"
    asynchttpGet("pollHandler", params)
}

void pollHandler(response, data) {
    def status = response?.getStatus()
    if (status == 200) {
        if (logEnable) log.debug "[WeatherLink] Device returned: ${response.data}"
        def json = parseJson(response.data)
        if (json?.data) {
    		parseWeatherData(json.data)
        } else if (json?.error) {
            log.error "[WeatherLink] ${json.error}"
        } else {
            log.error "[WeatherLink] Unable to parse response (valid Json?)"
        }
	} else {
		log.error "[WeatherLink] Device returned HTTP status ${status}"
	}
}

void logsOff() {
    log.warn "[WeatherLink] debug logging disabled"
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}

void parseWeatherData(Map json) {
    def date = json.ts * 1000L
    def df = new java.text.SimpleDateFormat("MMM dd hh:mm a")
    df.setTimeZone(location.timeZone)
    def lastUpdated = df.format(date)
    def rainMultiplier = 0
    def rainUnit = ""

    json.conditions.each {
        switch (it.data_structure_type) {
        case 1:
            if (logEnable) log.debug "[WeatherLink] Received ISS Current Conditions record"
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
            sendEvent(name: "lastUpdated", value: lastUpdated)
            sendEvent(name: "temperature", value: it.temp, unit: "F")
            sendEvent(name: "humidity", value: it.hum, unit: "%")
            sendEvent(name: "ultravioletIndex", value: it.uv_index, unit: 'uvi')
            sendEvent(name: "windSpeed", value: it.wind_speed_avg_last_1_min, unit: "MPH")
            sendEvent(name: "windDirection", value: it.wind_dir_scalar_avg_last_1_min, unit: "DEGREE")
            sendEvent(name: "windGust", value: it.wind_speed_hi_last_2_min, unit: "MPH")
            sendEvent(name: "water", value: it.rainfall_last_60_min > 0 ? "wet" : "dry")
            sendEvent(name: "rainRate", value: it.rain_rate_hi * rainMultiplier, unit: rainUnit)
            sendEvent(name: "rainDaily", value: it.rainfall_daily * rainMultiplier, unit: rainUnit)
            sendEvent(name: "rain24h", value: it.rainfall_last_24_hr * rainMultiplier, unit: rainUnit)
            break
        case 2:
            if (logEnable) log.debug "[WeatherLink] Received Leaf/Soil Moisture record"
            break
        case 3:
            if (logEnable) log.debug "[WeatherLink] Received LSS BAR record"
            sendEvent(name: "pressure", value: it.bar_sea_level, unit: "inHg")
            break
        case 4:
            if (logEnable) log.debug "[WeatherLink] Received LSS Temp/Hum record"
            break
        }
    }
}
