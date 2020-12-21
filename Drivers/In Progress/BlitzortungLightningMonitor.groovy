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

@Field int[] BITS = [16, 8, 4, 2, 1]
@Field char[] BASE_32 = ['0', '1', '2', '3', '4', '5', '6',
            '7', '8', '9', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j', 'k', 'm', 'n',
            'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z']


metadata {
    definition (name: 'Blitzortung Lighting Monitor',
                namespace: 'nrgup',
                author: 'Jonathan Bradshaw',
                importUrl: ''
    ) {
        capability 'Initialize'
        capability 'PresenceSensor'

        preferences {
            section {
                input name: 'precisionLevel',
                      type: 'number',
                      title: 'Precision Level',
                      required: true,
                      defaultValue: 6,
                      range: '1..12',
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

void test() {
}

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"
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
void parse(String data) {
    Map message = interfaces.mqtt.parseMessage(data)
    String topic = message.get('topic')
    String payload = message.get('payload')
    if (logEnable) { log.debug "RCV: ${topic} = ${payload}" }
    state.data = (state.last ?: []) + payload
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

void connected() {
    if (logEnable) { log.debug 'Connected to MQTT broker' }
    String geohash = geoEncode(location.latitude, location.longitude, settings.precisionLevel as int)
    List<String> neighbors = geoNeighbors(geohash, settings.precisionLevel as int)
    if (logEnable) { log.debug "Geohash: ${geohash} neighbors: ${neighbors}"}

    mqttSubscribe('component/hello')
    ([ geohash ] + neighbors).each { g ->
        String hash = g.getChars().join('/')
        String topic = "blitzortung/1.1/${hash}/#"
        mqttSubscribe(topic)
    }
}

// blitzortung/1.1/d/h/v/p/q/x/m/4/n/t/8/u
// blitzortung/1.1/d/h/2/g/6/u/z/q/f/8/n/h
//{"time": 1608502148116358700, "lat": 24.504791, "lon": -88.814391, "alt": 0, "pol": 0, "mds": 7319, "mcg": 219, "status": 3, "region": 0, "delay": 2.2, "sig_num": 40}

/**
 * Encodes the given latitude and longitude into a geohash
 * http://home.apache.org/~rmuir/es-coverage/combined/org.elasticsearch.common.geo/GeoHashUtils.java.html
 *
 * @param latitude  Latitude to encode
 * @param longitude Longitude to encode
 * @return Geohash encoding of the longitude and latitude
 */
private String geoEncode(BigDecimal latitude, BigDecimal longitude, int precision = 12) {
    double latInterval0 = -90.0
    double latInterval1 = 90.0
    double lngInterval0 = -180.0
    double lngInterval1 = 180.0

    StringBuilder geohash = new StringBuilder()
    boolean isEven = true
    int bit = 0
    int ch = 0

    while (geohash.length() < precision) {
        double mid = 0.0
        if (isEven) {
            mid = (lngInterval0 + lngInterval1) / 2D
            if (longitude > mid) {
                ch |= BITS[bit]
                lngInterval0 = mid
            } else {
                lngInterval1 = mid
            }
        } else {
            mid = (latInterval0 + latInterval1) / 2D
            if (latitude > mid) {
                ch |= BITS[bit]
                latInterval0 = mid
            } else {
                latInterval1 = mid
            }
        }

        isEven = !isEven;

        if (bit < 4) {
            bit++
        } else {
            geohash.append(BASE_32[ch])
            bit = 0
            ch = 0
        }
    }

    return geohash.toString()
}

private char encode(int x, int y) {
    return BASE_32[((x & 1) + ((y & 1) * 2) + ((x & 2) * 2) + ((y & 2) * 4) + ((x & 4) * 4)) % 32];
}

/**
 * Calculate the geohash of a neighbor of a geohash
 * http://home.apache.org/~rmuir/es-coverage/combined/org.elasticsearch.common.geo/GeoHashUtils.java.html
 *
 * @param geohash the geohash of a cell
 * @param level   level of the geohash
 * @param dx      delta of the first grid coordinate (must be -1, 0 or +1)
 * @param dy      delta of the second grid coordinate (must be -1, 0 or +1)
 * @return geohash of the defined cell
 */
private String geoNeighbor(String geohash, int level, int dx, int dy) {
    int cell = geoDecode(geohash.charAt(level - 1))

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
 * http://home.apache.org/~rmuir/es-coverage/combined/org.elasticsearch.common.geo/GeoHashUtils.java.html
 *
 * @param geohash   Geohash of a specified cell
 * @param level     level of the given geohash
 * @return the given list
 */
private List<String> geoNeighbors(String geohash, int level = 12) {
    List<String> neighbors = []

    String south = geoNeighbor(geohash, level, 0, -1)
    String north = geoNeighbor(geohash, level, 0, +1)
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

    return neighbors
}

private int geoDecode(char geo) {
    switch (geo) {
        case '0':
            return 0
        case '1':
            return 1
        case '2':
            return 2
        case '3':
            return 3
        case '4':
            return 4
        case '5':
            return 5
        case '6':
            return 6
        case '7':
            return 7
        case '8':
            return 8
        case '9':
            return 9
        case 'b':
            return 10
        case 'c':
            return 11
        case 'd':
            return 12
        case 'e':
            return 13
        case 'f':
            return 14
        case 'g':
            return 15
        case 'h':
            return 16
        case 'j':
            return 17
        case 'k':
            return 18
        case 'm':
            return 19
        case 'n':
            return 20
        case 'p':
            return 21
        case 'q':
            return 22
        case 'r':
            return 23
        case 's':
            return 24
        case 't':
            return 25
        case 'u':
            return 26
        case 'v':
            return 27
        case 'w':
            return 28
        case 'x':
            return 29
        case 'y':
            return 30
        case 'z':
            return 31
    }
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

private void mqttPublish(String topic, String payload = '', int qos = 0) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "PUB: ${topic} = ${payload}" }
        interfaces.mqtt.publish(topic, payload, qos, false)
    }
}

private void mqttSubscribe(String topic) {
    if (interfaces.mqtt.connected) {
        if (logEnable) { log.debug "SUB: ${topic}" }
        interfaces.mqtt.subscribe(topic)
    }
}
