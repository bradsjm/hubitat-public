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

/*
 * MQTT data broker provided by Mariusz Kryński
 * https://github.com/mrk-its/homeassistant-blitzortung
 * Please consider buying him coffee for his work at https://www.buymeacoffee.com/emrk
 */
@Field static String broker = 'tcp://blitzortung.ha.sed.pl:1883'

@Field static int[] bits = [16, 8, 4, 2, 1]
@Field static List base32 = ['0', '1', '2', '3', '4', '5', '6',
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
        attribute 'delta', 'number'
        attribute 'bearing', 'number'
        attribute 'direction', 'string'

        command 'reset'
        command 'test'

        preferences {
            section {
                input name: 'maxRange',
                      type: 'number',
                      title: 'Monitor Range (miles)',
                      description: 'Range for presence indicator',
                      range: '1..1500',
                      defaultValue: 5,
                      required: true

                input name: 'historyPeriod',
                      type: 'enum',
                      title: 'History Period',
                      description: 'Rolling time window for stats',
                      required: true,
                      defaultValue: 60,
                      options: [
                        10: '10 seconds',
                        30: '30 seconds',
                        60: '1 minute',
                        120: '2 minutes',
                        300: '5 minutes',
                        600: '10 minutes',
                        900: '15 minutes'
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
    unschedule()
    reset()
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
    status = status
    region = 1 - 7 are Europe, Oceania, North America, Asia, South America, Africa, and Japan
*/
void parse(String data) {
    Map message = interfaces.mqtt.parseMessage(data)
    if (logEnable) { log.debug "RCV: ${message}" }
    Map payload = parseJson(message.get('payload'))
    if (!payload.lat || !payload.lon) { return }

    List history = dataCache.computeIfAbsent(device.id) { k -> [] }
    Map closest = history ? history.min { h -> h.distance } : [:]

    long distance = Math.round(calcDistance(location.latitude, location.longitude, payload.lat, payload.lon))
    int bearing = calcBearing(location.latitude, location.longitude, payload.lat, payload.lon)
    if (logEnable) {
        log.debug "Strike ${distance} km away at ${bearing} degrees"
    }

    history.add([ time: (payload.time / 1000000) as long, distance: distance, bearing: bearing ])

    // Immediate update if the distance gets closer or this is the first report
    if (history.size() == 1 || distance < closest.distance) {
        updateStats()
    }
}

void reset() {
    dataCache[device.id] = []
    updateStats()
}

void test() {
    List history = dataCache.computeIfAbsent(device.id) { k -> [] }
    history.add([ time: now(), distance: 1.609, bearing: 99 ])
    updateStats()
}

private void updateStats() {
    List history = dataCache.computeIfAbsent(device.id) { k -> [] }
    if (!history) { return }

    int historyPeriod = settings.historyPeriod as int
    history.removeAll { d -> d.time < now() - (historyPeriod * 1000) }
    int size = history.size()
    if (size) { runIn(60, 'updateStats') }

    // Calculate number of strikes within the specified range setting
    int rangeKm = Math.round((settings.maxRange as int) * 1.609)
    int strikes = history.count { h -> h.distance <= rangeKm }
    String presence = strikes ? 'present' : 'not present'

    // Calculate the closest strike direction and distance
    Map closest = size ? history.min { h -> h.distance } : [:]
    int bearing = closest?.bearing ?: 0
    String direction = closest ? headingToString(closest.bearing) : 'n/a'
    int distance = closest?.distance ?: 0
    int delta = (device.currentValue('distance') ?: 0) - distance

    sendEvent(newEvent('presence', presence))
    if (distance <= rangeKm) {
        sendEvent(newEvent('strikes', strikes))
        sendEvent(newEvent('distance', Math.round(distance / 1.609), [ unit: 'miles' ]))
        sendEvent(newEvent('delta', Math.round(delta / 1.609), [ unit: 'miles' ]))
        sendEvent(newEvent('bearing', bearing, [ unit: '°' ]))
        sendEvent(newEvent('direction', direction))
    }
}

// Called with MQTT client status messages
void mqttClientStatus(String status) {
    // The string that is passed to this method starts with "Error" if an error occurred
    // or "Status" if this is just a status message.
    state.mqttStatus = status
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

private static char encode(int x, int y) {
    return base32[((x & 1) + ((y & 1) * 2) + ((x & 2) * 2) + ((y & 2) * 4) + ((x & 4) * 4)) % 32]
}

public static String headingToString(int heading)
{
    String[] directions = ["North", "North East", "East", "South East", "South",
                           "South West", "West", "North West", "North"]
    return directions[ (int)Math.round((  (heading % 360) / 45)) ]
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void connected() {
    if (logEnable) { log.debug 'Connected to MQTT broker' }

    int range = settings.maxRange as int
    int precision = getPrecision(range)

    String geohash = geoEncode(location.latitude, location.longitude, precision)
    List tiles = geoNeighbors(geohash, precision).plus(geohash)

    log.info "Using Geohash precision level ${precision} for ${range} mile range using ${tiles.size()} tiles"
    if (logEnable) { log.debug "Geohash tiles: ${tiles}" }

    tiles.each { tile ->
        String hash = tile.chars.join('/')
        String topic = "blitzortung/1.1/${hash}/#"
        mqttSubscribe(topic)
    }
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
 * Calculate the geohash of a neighbor of a geohash
 *
 * @param geohash the geohash of a cell
 * @param level   level of the geohash
 * @param dx      delta of the first grid coordinate (must be -1, 0 or +1)
 * @param dy      delta of the second grid coordinate (must be -1, 0 or +1)
 * @return geohash of the defined cell
 */
private String geoNeighbor(String geohash, int level, int dx, int dy) {
    int cell = base32.indexOf(geohash.charAt(level - 1))

    // Decoding the Geohash bit pattern to determine grid coordinates
    int x0 = cell & 1  // first bit of x
    int y0 = cell & 2  // first bit of y
    int x1 = cell & 4  // second bit of x
    int y1 = cell & 8  // second bit of y
    int x2 = cell & 16 // third bit of x

    // combine the bitpattern to grid coordinates.
    // note that the semantics of x and y are swapping
    // on each level
    int x = x0 + (x1 / 2) + (x2 / 4)
    int y = (y0 / 2) + (y1 / 4)

    if (level == 1) {
        // Root cells at north (namely "bcfguvyz") or at
        // south (namely "0145hjnp") do not have neighbors
        // in north/south direction
        if ((dy < 0 && y == 0) || (dy > 0 && y == 3)) {
            return null
        } else {
            return encode(x + dx, y + dy) as String
        }
    } else {
        // define grid coordinates for next level
        final int nx = ((level % 2) == 1) ? (x + dx) : (x + dy)
        final int ny = ((level % 2) == 1) ? (y + dy) : (y + dx)

        // if the defined neighbor has the same parent a the current cell
        // encode the cell directly. Otherwise find the cell next to this
        // cell recursively. Since encoding wraps around within a cell
        // it can be encoded here.
        // xLimit and YLimit must always be respectively 7 and 3
        // since x and y semantics are swapping on each level.
        if (nx >= 0 && nx <= 7 && ny >= 0 && ny <= 3) {
            return geohash.substring(0, level - 1) + encode(nx, ny)
        } else {
            String neighbor = geoNeighbor(geohash, level - 1, dx, dy)
            if(neighbor != null) {
                return neighbor + encode(nx, ny)
            } else {
                return null
            }
        }
    }
}

/**
    * Add all geohashes of the cells next to a given geohash to a list.
    *
    * @param geohash   Geohash of a specified cell
    * @param level     level of the given geohash
    * @return the given list
    */
public List geoNeighbors(String geohash, int level) {
    Set neighbors = []
    String north = geoNeighbor(geohash, level, 0, +1)
    String south = geoNeighbor(geohash, level, 0, -1)
    if (north != null) {
        neighbors.add(geoNeighbor(north, level, -1, 0))
        neighbors.add(north)
        neighbors.add(geoNeighbor(north, level, +1, 0))
    }

    neighbors.add(geoNeighbor(geohash, level, -1, 0))
    neighbors.add(geoNeighbor(geohash, level, +1, 0))

    if (south != null) {
        neighbors.add(geoNeighbor(south, level, -1, 0))
        neighbors.add(south)
        neighbors.add(geoNeighbor(south, level, +1, 0))
    }

    return neighbors as List
}

private int getPrecision(int miles) {
    float km = miles * 1.609
    if (km > 630) { return 1 }
    if (km > 79) { return 2 }
    if (km > 78) { return 3 }
    if (km > 20) { return 2 }
    return 5
}

/**
 *  Common MQTT communication methods
 */

private void mqttConnect() {
    unschedule('mqttConnect')
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
