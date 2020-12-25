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
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field int[] bits = [16, 8, 4, 2, 1]
@Field char[] base32 = ['0', '1', '2', '3', '4', '5', '6',
            '7', '8', '9', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j', 'k', 'm', 'n',
            'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z']

metadata {
    definition (name: 'Blitzortung Lightning Monitor',
                namespace: 'nrgup',
                author: 'Jonathan Bradshaw',
                importUrl: ''
    ) {
        capability 'Initialize'
        capability 'PresenceSensor'

        attribute 'strikes', 'number'
        attribute 'distance', 'number'
        attribute 'bearing', 'number'
        attribute 'direction', 'string'

        command 'reset'

        preferences {
            section {
                input name: 'precisionLevel',
                      type: 'enum',
                      title: 'Monitor Distance',
                      required: true,
                      defaultValue: 6,
                      options: [
                        1: '± 1500 miles',
                        2: '± 400 miles',
                        3: '± 50 miles',
                        4: '± 10 miles',
                        5: '± 1.5 miles',
                        6: '± 600 yards',
                        7: '± 80 yards',
                        8: '± 60 feet',
                        9: '± 8 feet'
                      ]

                input name: 'historyPeriod',
                      type: 'enum',
                      title: 'History Period',
                      required: true,
                      defaultValue: 60,
                      options: [
                        10: '10 seconds',
                        30: '30 seconds',
                        60: '1 minute',
                        300: '5 minutes',
                        600: '10 minutes'
                      ]

                input name: 'updateInterval',
                      type: 'enum',
                      title: 'Update Interval',
                      required: true,
                      defaultValue: 15,
                      options: [
                        10: '10 seconds',
                        15: '15 seconds',
                        30: '30 seconds',
                        45: '45 seconds'
                      ]
            }

            section {
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

// Cache for tracking rolling average for energy
@Field static final ConcurrentHashMap<Integer, List<Map>> dataCache = new ConcurrentHashMap<>()

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"
    mqttDisconnect()
    mqttConnect()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called when the device is removed.
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
    disconnect()
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

// Called to parse received MQTT data
/* {
  "time": 1608839988772850200,
  "lat": 27.795992,
  "lon": -85.896862,
  "alt": 0,
  "pol": 0,
  "mds": 8590,
  "mcg": 195,
  "status": 0,
  "region": 3,
  "delay": 3.9,
  "sig_num": 27
}
    time = time stamp in nano seconds
    lat = latitude in degree (decimal)
    lon = longitude in degree (decimal)
    alt = altitude in meter
    pol = polarity, -1 or +1
    mds = maximal deviation span in nano seconds
    mcg = maximal circular gap in degree (ex: 210 = the detectors are in a sector of 150 degree from impact position)
    status = status, optional
*/
void parse(String data) {
    Map message = interfaces.mqtt.parseMessage(data)
    if (logEnable) { log.debug "RCV: ${message}" }
    Map payload = parseJson(message.get('payload'))
    if (!payload.lat || !payload.lon) { return }

    long distance = Math.round(calcDistance(location.latitude, location.longitude, payload.lat, payload.lon))
    int bearing = calcBearing(location.latitude, location.longitude, payload.lat, payload.lon)
    if (logEnable) {
        log.debug "Strike ${distance} km away at ${bearing} degrees"
    }

    List history = dataCache.computeIfAbsent(device.id) { k -> [] }
    if (!history.size()) {
        schedule('*/${settings.updateInterval} * * ? * * *', 'updateStats')
    }

    history.add([ time: (payload.time / 1000000) as long, distance: distance, bearing: bearing ])
}

void reset() {
    dataCache[device.id] = []
    updateStats()
}

private void updateStats() {
    List history = dataCache.computeIfAbsent(device.id) { k -> [] }
    history.removeAll { d -> d.time < now() - ((settings.historyPeriod as int) * 1000) }
    int size = history.size()
    if (!size) {
        unschedule('updateStats')
    }

    Map min = history.min { d -> d.distance }
    int bearing = size ? min.bearing : 0
    int distance = size ? Math.round(min.distance / 1.609) : 0
    String direction = size ? headingToString(min.bearing) : 'N/A'
    String presence = size ? 'present' : 'not present'
    sendEvent(newEvent('strikes', size))
    sendEvent(newEvent('distance', distance, [ unit: 'm' ]))
    sendEvent(newEvent('bearing', bearing, [ unit: '°' ]))
    sendEvent(newEvent('direction', direction))
    sendEvent(newEvent('presence', presence))
}

// Called with MQTT client status messages
void mqttClientStatus(String status) {
    // The string that is passed to this method with start with "Error" if an error occurred
    // or "Status" if this is just a status message.
    List<String> parts = status.split(': ')
    switch (parts[0]) {
        case 'Error':
            log.warn "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection lost':
                case 'send error':
                    runIn(30, 'initialize')
                    break
            }
            break
        case 'Status':
            log.info "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection succeeded':
                    // without this delay the `parse` method is never called
                    // (it seems that there needs to be some delay after connection to subscribe)
                    runIn(1, 'connected')
                    break
            }
            break
        default:
            log.warn "MQTT ${status}"
            break
    }
}

/**
 * Calculate distance between two points in latitude and longitude
 * Uses Haversine method as its base.
 * lat1, lon1 Start point lat2, lon2 End point
 * @returns Distance in km
 */
private static BigDecimal calcDistance(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
    BigDecimal latDistance = Math.toRadians(lat2 - lat1)
    BigDecimal lonDistance = Math.toRadians(lon2 - lon1)
    BigDecimal a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2)
    BigDecimal c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    BigDecimal distance = 6371 * c
    distance = Math.pow(distance, 2)

    return Math.sqrt(distance)
}

private static int calcBearing(BigDecimal lat1, BigDecimal long1, BigDecimal lat2, BigDecimal long2) {
    double dLon = long2 - long1
    double y = Math.sin(dLon) * Math.cos(lat2)
    double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
    double brng = Math.atan2(y, x)
    brng = Math.toDegrees(brng)
    brng = (brng + 360) % 360
    brng = 360 - brng; // count degrees counter-clockwise - remove to make clockwise

    return brng;
}

public static String headingToString(int heading)
{
    String[] directions = ["N", "NE", "E", "SE", "S", "SW", "W", "NW", "N"]
    return directions[ (int)Math.round((  (heading % 360) / 45)) ]
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void connected() {
    if (logEnable) { log.debug 'Connected to MQTT broker' }
    String geohash = geoEncode(location.latitude, location.longitude, settings.precisionLevel as int)
    if (logEnable) { log.debug "Geohash: ${geohash}" }

    String hash = geohash.chars.join('/')
    String topic = "blitzortung/1.1/${hash}/#"
    mqttSubscribe(topic)
}

/**
 * Encodes the given latitude and longitude into a geohash
 * http://home.apache.org/~rmuir/es-coverage/combined/org.elasticsearch.common.geo/GeoHashUtils.java.html
 *
 * @param latitude  Latitude to encode
 * @param longitude Longitude to encode
 * @return Geohash encoding of the longitude and latitude
 */
private String geoEncode(BigDecimal latitude, BigDecimal longitude, int precision = 12) {
    BigDecimal latInterval0 = -90.0
    BigDecimal latInterval1 = 90.0
    BigDecimal lngInterval0 = -180.0
    BigDecimal lngInterval1 = 180.0

    StringBuilder geohash = new StringBuilder()
    boolean isEven = true
    int bit = 0
    int ch = 0

    while (geohash.length() < precision) {
        BigDecimal mid = 0.0
        if (isEven) {
            mid = (lngInterval0 + lngInterval1) / 2D
            if (longitude > mid) {
                ch |= bits[bit]
                lngInterval0 = mid
            } else {
                lngInterval1 = mid
            }
        } else {
            mid = (latInterval0 + latInterval1) / 2D
            if (latitude > mid) {
                ch |= bits[bit]
                latInterval0 = mid
            } else {
                latInterval1 = mid
            }
        }

        isEven = !isEven

        if (bit < 4) {
            bit++
        } else {
            geohash.append(base32[ch])
            bit = 0
            ch = 0
        }
    }

    return geohash.toString()
}

/**
 *  Common Tasmota MQTT communication methods
 */

private void mqttConnect() {
    unschedule('mqttConnect')
    String broker = 'tcp://blitzortung.ha.sed.pl:1883'
    try {
        String clientId = device.hub.hardwareID + '-' + device.id
        log.info "Connecting to MQTT broker at ${broker}"
        interfaces.mqtt.connect(
            broker,
            clientId,
            null,
            null
        )
    } catch (e) {
        log.error "MQTT connect error: ${e}"
        runIn(30, 'mqttConnect')
    }
}

private void mqttDisconnect() {
    if (interfaces.mqtt.connected) {
        log.info 'Disconnecting from MQTT'
        interfaces.mqtt.disconnect()
    }
}

private void mqttSubscribe(String topic) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "SUB: ${topic}" }
        interfaces.mqtt.subscribe(topic)
    }
}

private Map newEvent(String name, Object value, Map params = [:]) {
    String splitName = splitCamelCase(name).toLowerCase()
    String description = "${device.displayName} ${splitName} is ${value}${params.unit ?: ''}"
    if (device.currentValue(name) != value) { log.info description }
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: settings.logTextEnable ? description : ''
    ] + params
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

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}
