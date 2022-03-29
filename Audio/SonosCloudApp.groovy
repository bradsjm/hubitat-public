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

import com.hubitat.app.exception.UnknownDeviceTypeException
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.scheduling.AsyncResponse

definition(
    name: 'Sonos Announcer (Cloud)',
    namespace: 'sonos',
    author: 'Jonathan Bradshaw',
    category: 'Audio',
    description: 'Playback of announcements on Sonos speakers without interrupting playback (requires cloud)',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page(name: 'prefMain')
    page(name: 'prefDevices')
}

mappings {
    path('/callback') {
        action: [
            GET: 'oauthCallback'
        ]
    }
}

Map prefMain() {
    boolean configured = settings.clientKey != null && settings.clientSecret != null
    boolean connected = state.authToken != null && state.authTokenExpires > now()

    return dynamicPage(name: 'prefMain', title: 'Sonos Announcer', nextPage: configured && connected ? 'prefDevices' : '', uninstall: true, install: false) {
        section {
            label title: 'Application Label', required: false

            input name: 'language',
                  title: 'Select Text to Speach Language',
                  type: 'enum',
                  options: ttsLanguages,
                  defaultValue: 'en-us',
                  required: true,
                  multiple: false,
                  width: 6
        }

        section('Sonos Developer Credentials:') {
            input name: 'clientKey', type: 'text', title: 'Client Key', description: '', required: true, defaultValue: '', submitOnChange: true
            input name: 'clientSecret', type: 'text', title: 'Client Secret', description: '', required: true, defaultValue: '', submitOnChange: true
        }

        if (configured) {
            section {
                String desc = ''
                if (connected) {
                    paragraph "<script>\$('button[name=\"_action_next\"]').show()</script>"
                    desc = 'Your Hubitat and Sonos accounts are connected, select to re-authorize if needed'
                } else {
                    paragraph "<script>\$('button[name=\"_action_next\"]').hide()</script>"
                    desc = 'Select to connect your Sonos and Hubitat accounts'
                }
                href url: oauthInitialize(), style: 'external', required: true, title: 'Sonos Account Authorization', description: desc
            }
        }
    }
}

Map prefDevices() {
    state.players = getHouseholds().collectMany { id -> getPlayers(id) } ?: []

    return dynamicPage(name: 'prefDevices', title: 'Sonos Players', uninstall: true, install: true) {
        section {
            paragraph 'Select your device(s) below.'
            input name: 'players',
                  title: "Select Sonos (${state.players.size()} found)",
                  type: 'enum',
                  options: state.players.collectEntries { p -> [(p.id): p.name ] },
                  multiple: true
        }
    }
}

// Called when the app is first created.
void installed() {
    LOG.info "${app.name} installed"
}

// OAuth Routines
String oauthInitialize() {
    if (state.accessToken == null) {
        createAccessToken()
        state.oauthState = "${getHubUID()}/apps/${app.id}/callback?access_token=${state.accessToken}"
    }

    String clientId = settings.clientKey
    String redirectUri = URLEncoder.encode('https://cloud.hubitat.com/oauth/stateredirect')
    String state = URLEncoder.encode(state.oauthState)

    return "https://api.sonos.com/login/v3/oauth?client_id=${clientId}&response_type=code&state=${state}&scope=playback-control-all&redirect_uri=${redirectUri}"
}

Map oauthCallback() {
    state.remove('authToken')
    state.remove('refreshToken')
    state.remove('authTokenExpires')
    state.remove('scope')

    LOG.info "oauthCallback: ${params}"
    if (params.state != state.oauthState) {
        log.error 'Aborted due to security problem: OAuth state does not match expected value'
        return authFailure()
    }

    if (params.code == null) {
        log.error 'Aborted: OAuth one-time use authorization code not received'
        return authFailure()
    }

    try {
        LOG.info 'Requesting access token'
        String clientId = settings.clientKey
        String clientSecret = settings.clientSecret
        String authorization = (clientId + ':' + clientSecret).bytes.encodeBase64()
        httpPost([
            uri: 'https://api.sonos.com/login/v3/oauth/access',
            headers: [
                'Authorization': 'Basic ' + authorization
            ],
            body: [
                'grant_type': 'authorization_code',
                'code': params.code,
                'redirect_uri': 'https://cloud.hubitat.com/oauth/stateredirect'
            ]
        ]) { resp ->
            LOG.debug 'Token request response: ' + resp.data
            if (resp && resp.data && resp.success) {
                log.info 'Received access token'
                parseToken(resp.data)
            } else {
                LOG.error 'OAuth error: ' + resp.data
            }
        }
    } catch (e) {
        LOG.exception "OAuth error: ${e}", e
    }

    return state.authToken == null ? authFailure() : authSuccess()
}

Map authSuccess() {
    return render(contentType: 'text/html', data: """
        <h2>Success!</h2>
        <p>Your Sonos Account is now connected to Hubitat</p>
        <p>Close this window to continue setup.</p>
    """)
}

Map authFailure() {
    return render(contentType: 'text/html', data: """
        <p>The connection could not be established!</p>
        <p>Close this window to try again.</p>
    """)
}

void componentPlayText(DeviceWrapper device, String text, BigDecimal volume = null) {
    String playerId = device.deviceNetworkId.tokenize('-').last()
    LOG.info "${device} play text ${text} (volume ${volume ?: 'not set'})"
    Map data = [
        'name': 'HE Audio Clip',
        'appId': 'sonos.hubitat.com'
    ]

    data['streamUrl'] = 'https://translate.google.com/translate_tts?' +
                        "ie=UTF-8&client=tw-ob&tl=${settings.language}&q=" +
                        URLEncoder.encode(text.take(100), 'UTF-8')

    if (volume) {
        data['volume'] = (int)volume
    }

    postJson("https://api.ws.sonos.com/control/api/v1/players/${playerId}/audioClip", data)
}

void componentPlayTrack(DeviceWrapper device, String uri, BigDecimal volume = null) {
    String playerId = device.deviceNetworkId.tokenize('-').last()
    LOG.info "${device} play track ${uri} (volume ${volume ?: 'not set'})"
    Map data = [
        'name': 'HE Audio Clip',
        'appId': 'sonos.hubitat.com'
    ]

    if (uri?.toUpperCase() != 'CHIME') {
        data['streamUrl'] = uri
    }

    if (volume) {
        data['volume'] = (int)volume
    }

    postJson("https://api.ws.sonos.com/control/api/v1/players/${playerId}/audioClip", data)
}

/* groovylint-disable-next-line UnusedMethodParameter */
void postResponse(AsyncResponse response, Map data) {
    if (response.hasError()) {
        LOG.error "Cloud request error ${response.getErrorMessage()}"
    } else if (response.status != 200) {
        LOG.error "Cloud request returned HTTP status ${response.status}"
    }
}

void refreshToken() {
    try {
        log.info 'Refreshing access token'
        String clientId = settings.clientKey
        String clientSecret = settings.clientSecret
        String authorization = (clientId + ':' + clientSecret).bytes.encodeBase64()
        httpPost([
            uri: 'https://api.sonos.com/login/v3/oauth/access',
            headers: [
                'Authorization': 'Basic ' + authorization
            ],
            body: [
                'grant_type': 'refresh_token',
                'refresh_token': state.refreshToken
            ]
        ]) { resp ->
            LOG.debug 'Token refresh response: ' + resp.data
            if (resp && resp.data && resp.success) {
                log.info 'Received access token'
                parseToken(resp.data)
            } else {
                LOG.error 'OAuth error: ' + resp.data
            }
        }
    } catch (e) {
        LOG.exception "OAuth error: ${e}", e
    }
}

// Called when the app is removed.
void uninstalled() {
    LOG.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    LOG.debug settings
    createChildDevices()
}

private void postJson(String uri, Map data) {
    asynchttpPost('postResponse', [
        uri: uri,
        headers: [
            'Authorization': 'Bearer ' + state.authToken,
        ],
        contentType: 'application/json',
        body: JsonOutput.toJson(data)
    ])
}

private List<Map> createChildDevices() {
    List<Map> devices = state.players.findAll { player -> player.id in settings.players }
    LOG.debug 'createChildDevices: ' + devices
    for (Map player in devices) {
        String dni = "${app.id}-${player.id}"
        if (getChildDevice(dni) == null) {
            try {
                LOG.info "Creating child audio notification device for ${player.name}"
                addChildDevice('component', 'Generic Component Audio Notification', dni,
                    [
                        name: player.name,
                        label: player.name + ' Notifier',
                    ]
                )
            } catch (UnknownDeviceTypeException e) {
                LOG.exception 'Generic Component Audio Notification driver not found (needs installing?)', e
            }
        }
    }

    return devices
}

// Sonos API Methods
private List<String> getHouseholds() {
    List<String> households = []
    LOG.info 'Requesting households'
    httpGet([
        uri: 'https://api.ws.sonos.com/control/api/v1/households',
        headers: [
            'Authorization': 'Bearer ' + state.authToken
        ]
    ]) { resp ->
        LOG.debug 'getHouseholds request response: ' + resp.data
        if (resp && resp.data && resp.success) {
            households = resp.data['households']*.id ?: []
            LOG.info "Received ${households.size()} households"
        } else {
            LOG.error 'getHouseholds error: ' + resp.data
        }
    }

    return households
}

private List<Map> getPlayers(String household) {
    List<Map> available = []
    LOG.info "Requesting players for ${household}"
    httpGet([
        uri: "https://api.ws.sonos.com/control/api/v1/households/${household}/groups",
        headers: [
            'Authorization': 'Bearer ' + state.authToken
        ]
    ]) { resp ->
        LOG.debug 'getPlayers request response: ' + resp.data
        if (resp && resp.data && resp.success) {
            List<Map> players = resp.data['players'] ?: [:]
            LOG.info "Received ${players.size()} players"
            available = players.findAll { player ->
                player['isUnregistered'] == false &&
                'AUDIO_CLIP' in player['capabilities']
            }
        } else {
            LOG.error 'getPlayers error: ' + resp.data
        }
    }

    return available
}

private void parseToken(Map data) {
    state.authToken = data.access_token
    state.refreshToken = data.refresh_token
    state.authTokenExpires = now() + (data.expires_in * 1000)
    state.scope = data.scope
    runIn((int)data.expires_in * 0.90, refreshToken)
}

@Field private final Map LOG = [
    debug: { s -> log.debug(s) },
    info: { s -> log.info(s) },
    warn: { s -> log.warn(s) },
    error: { s -> log.error(s) },
    exception: { message, exception ->
        List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith('user_app') }
        Integer line = relevantEntries[0]?.lineNumber
        String method = relevantEntries[0]?.methodName
        log.error("${message}: ${exception} at line ${line} (${method})")
        if (settings.logEnable) {
            log.debug("App exception stack trace:\n${relevantEntries.join('\n')}")
        }
    }
].asImmutable()

@Field private static final String[] ttsLanguages = [
    'af',
    'ar',
    'bg',
    'bn',
    'bs',
    'ca',
    'cs',
    'cy',
    'da',
    'de',
    'el',
    'en',
    'eo',
    'es',
    'et',
    'fi',
    'fr',
    'gu',
    'hi',
    'hr',
    'hu',
    'hy',
    'id',
    'is',
    'it',
    'iw',
    'ja',
    'jw',
    'km',
    'kn',
    'ko',
    'la',
    'lv',
    'mk',
    'ml',
    'mr',
    'my',
    'ne',
    'nl',
    'no',
    'pl',
    'pt',
    'ro',
    'ru',
    'si',
    'sk',
    'sq',
    'sr',
    'su',
    'sv',
    'sw',
    'ta',
    'te',
    'th',
    'tl',
    'tr',
    'uk',
    'ur',
    'vi',
    // dialects
    'zh-CN',
    'zh-cn',
    'zh-tw',
    'en-us',
    'en-ca',
    'en-uk',
    'en-gb',
    'en-au',
    'en-gh',
    'en-in',
    'en-ie',
    'en-nz',
    'en-ng',
    'en-ph',
    'en-za',
    'en-tz',
    'fr-ca',
    'fr-fr',
    'pt-br',
    'pt-pt',
    'es-es',
    'es-us'
]
