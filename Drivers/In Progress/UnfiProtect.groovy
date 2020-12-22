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
import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.helper.HexUtils
import hubitat.scheduling.AsyncResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

metadata {
    definition (name: 'UniFi Protect', namespace: 'nrgup', author: 'Jonathan Bradshaw', importUrl: '') {
        capability 'Initialize'

        command 'disconnect'

        preferences {
            section('Connection') {
                input name: 'server',
                      type: 'text',
                      title: 'UniFi Protect Server URL',
                      description: '',
                      required: true,
                      defaultValue: '192.168.1.1'

                input name: 'username',
                      type: 'text',
                      title: 'UniFi Protect Login',
                      description: '',
                      required: true,
                      defaultValue: ''

                input name: 'password',
                      type: 'text',
                      title: 'UniFi Protect Password',
                      description: '',
                      required: true,
                      defaultValue: ''
            }

            section {
                input name: 'logEnable',
                      type: 'bool',
                      title: 'Enable debug logging',
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

/**
 *  Hubitat Driver Event Handlers
 */

// Called when the device is started.
void initialize() {
    log.info "${device.displayName} driver initializing"

    if (!settings.username || !settings.password) {
        log.error 'Unable to connect because lusername and password are required'
        return
    }

    disconnect()
    authenticate()
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called to parse received socket data
void parse(String message) {
    byte[] buffer = HexUtils.hexStringToByteArray(message)
    decodeUpdatePacket(buffer)
}

// Called with socket status messages
void webSocketStatus(String status) {
    if (logEnable) { log.debug "Websocket status: ${status}" }
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
    state.clear()
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

/* A packet header is composed of 8 bytes in this order:
 *
 * Byte Offset  Description      Bits  Values
 * 0            Packet Type      8     1 - action frame, 2 - payload frame.
 * 1            Payload Format   8     1 - JSON object, 2 - UTF8-encoded string, 3 - Node Buffer.
 * 2            Deflated         8     0 - uncompressed, 1 - compressed / deflated (zlib-based compression).
 * 3            Unknown          8     Always 0. Possibly reserved for future use by Ubiquiti?
 * 4-7          Payload Size:    32    Size of payload in network-byte order (big endian).
 */
private Map decodeUpdatePacket(byte[] packet) {
    Map result = [:]

    int packetType = packet[0]
    int payloadFormat = packet[1]
    boolean deflated = packet[2]
    int payloadSize = getUInt32(packet, 4)

    if (deflated) {
        decompress(new ByteArrayInputStream(packet, 8, payloadSize))
    }

    return [:]
}

private ByteArrayOutputStream decompress(ByteArrayInputStream input) {
    ByteArrayOutputStream outstream = new ByteArrayOutputStream()
    byte[] dictionary = new byte[32 * 1024]
    Map ctx = [
        input: input,
        currentByte: 0 as byte,
        numBitsRemaining: 0 as byte
    ]

    // Zlib Header
    byte cmf = input.read()
    byte flg = input.read()

    boolean bFinal = readBit(ctx) == 1
    int bType = readInt(ctx, 2)

    switch (bType) {
        case 2:
            Map litLenAndDist = decodeHuffmanCodes(ctx)
            break
    }

    log.debug "bFinal ${bFinal}"
    log.debug "bType ${bType}"

    return outstream
}

// Reads from the bit input stream, decodes the Huffman code
// specifications into code trees, and returns the trees.
private Map decodeHuffmanCodes(Map ctx) {
    int numLitLenCodes = readInt(ctx, 5) + 257;  // hlit + 257
    int numDistCodes = readInt(ctx, 5) + 1;      // hdist + 1

    // Read the code length code lengths
    int numCodeLenCodes = readInt(ctx, 4) + 4;   // hclen + 4
    int[] codeLenCodeLen = new int[19];  // This array is filled in a strange order
    codeLenCodeLen[16] = readInt(ctx, 3);
    codeLenCodeLen[17] = readInt(ctx, 3);
    codeLenCodeLen[18] = readInt(ctx, 3);
    codeLenCodeLen[ 0] = readInt(ctx, 3);

    for (int i = 0; i < numCodeLenCodes - 4; i++) {
        int j = (i % 2 == 0) ? (8 + i / 2) : (7 - i / 2);
        codeLenCodeLen[j] = readInt(ctx, 3);
    }

    Map codeLenCode = canonicalCode(codeLenCodeLen)

    // Read the main code lengths and handle runs
    int[] codeLens = new int[numLitLenCodes + numDistCodes]
    for (int codeLensIndex = 0; codeLensIndex < codeLens.length ) {
        int sym = codeLenCode.decodeNextSymbol(input);
        if (0 <= sym && sym <= 15) {
            codeLens[codeLensIndex] = sym;
            codeLensIndex++;
        } else {
            int runLen;
            int runVal = 0;
            if (sym == 16) {
                if (codeLensIndex == 0)
                    throw new DataFormatException("No code length value to copy");
                runLen = readInt(2) + 3;
                runVal = codeLens[codeLensIndex - 1];
            } else if (sym == 17)
                runLen = readInt(3) + 3;
            else if (sym == 18)
                runLen = readInt(7) + 11;
            else
                throw new AssertionError("Symbol out of range");
            int end = codeLensIndex + runLen;
            if (end > codeLens.length)
                throw new DataFormatException("Run exceeds number of codes");
            Arrays.fill(codeLens, codeLensIndex, end, runVal);
            codeLensIndex = end;
        }
    }
}

// Decompresses a Huffman-coded block from the bit input stream based on the given Huffman codes.
private void decompressHuffmanBlock(Map ctx) {
    
}

private Map canonicalCode(int[] codeLengths) {
    int[] symbolCodeBits = new int[codeLengths.length]
    int[] symbolValues   = new int[codeLengths.length]
    int numSymbolsAllocated = 0
    int nextCode = 0
    for (int codeLength = 1; codeLength <= 15; codeLength++) {
        nextCode <<= 1
        int startBit = 1 << codeLength
        for (int symbol = 0; symbol < codeLengths.length; symbol++) {
            if (codeLengths[symbol] != codeLength)
                continue
            symbolCodeBits[numSymbolsAllocated] = startBit | nextCode;
            symbolValues  [numSymbolsAllocated] = symbol;
            numSymbolsAllocated++;
            nextCode++;
        }
    }
    // Trim unused trailing elements
    symbolCodeBits = Arrays.copyOf(symbolCodeBits, numSymbolsAllocated)
    symbolValues   = Arrays.copyOf(symbolValues  , numSymbolsAllocated)
    return [
        symbolCodeBits: symbolCodeBits,
        symbolValues: symbolValues
    ]
}

private byte readBit(Map ctx) {
    ctx.with {
        if (currentByte == -1) { return -1 }
        if (numBitsRemaining == 0) {
            currentByte = ctx.input.read()
            if (currentByte == -1) { return -1 }
            numBitsRemaining = 8
        }
        numBitsRemaining--
        return (currentByte >>> (7 - numBitsRemaining)) & 1
    }
}

private int readInt(Map ctx, int numBits) {
    int result = 0
    for (int i = 0; i < numBits; i++)
        result |= readBit(ctx) << i
    return result;
}

private static long getUInt32(byte[] buffer, long start) {
    long result = 0
    for (int i = start; i < start + 4; i++) {
        result *= 256
        result += (buffer[i] & 0xff)
    }

    return result
}

private void authenticate() {
    Map params = [
        uri: "https://${settings.server}/api/auth/login",
        ignoreSSLIssues: true,
        contentType: 'application/json',
        body: JsonOutput.toJson([
            password: settings.password,
            username: settings.username
        ]),
        timeout: 60
    ]
    log.info "Authenticating to UniFi Protect as ${settings.username}"
    asynchttpPost('authHandler', params)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void authHandler(AsyncResponse response, Object data) {
    switch (response.status) {
        case 200:
            log.debug response.headers
            //String csrfToken = response.headers['X-CSRF-Token']
            String cookie = response.headers['Set-Cookie']
            connect(cookie)
            break
        case 400:
        case 401:
             log.error 'Authentication failed! Check username/password and try again.'
             return
        default:
            log.error "Returned HTTP status ${response.status}"
            return
    }
}

private void connect(String cookie, String lastUpdateId = '') {
    try {
        String url = "wss://${settings.server}/proxy/protect/ws/updates?lastUpdateId=${lastUpdateId}"
        log.info 'Connecting to Live Data Stream'
        interfaces.webSocket.connect(url,
            headers: [
                'Cookie': cookie
            ],
            ignoreSSLIssues: true,
            pingInterval: 10 // UniFi OS expects to hear from us every 15 seconds
        )
    } catch (e) {
        log.error "connect error: ${e}"
    }
}

private void disconnect() {
    unschedule()
    log.info 'Disconnecting from Sense Live Data Stream'

    interfaces.webSocket.close()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device.displayName}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}
