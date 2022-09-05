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
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.scheduling.AsyncResponse

definition(
    name: 'Sonos Cloud Controller',
    namespace: 'sonos',
    author: 'Jonathan Bradshaw',
    category: 'Audio',
    description: 'Provides announcements without interrupting playback and device grouping capabilities',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: '',
    singleThreaded: true
)

preferences {
    page(name: 'mainPage', install: true, uninstall: true)
    page(name: 'authorizePage')
    page(name: 'playerPage')
    page(name: 'groupPage')
}

mappings {
    path('/callback') {
        action: [
            GET: 'oauthCallback'
        ]
    }
}

@Field static final String apiPrefix = 'https://api.ws.sonos.com/control/api/v1'

/*
 * Configuration UI
 */
Map mainPage() {
    boolean configured = settings.clientKey != null && settings.clientSecret != null
    boolean authenticated = state.authToken != null

    return dynamicPage(title: 'Sonos Cloud Controller') {
        section {
            label title: 'Application Label',
                  required: false

            paragraph 'This application provides an interface to Sonos Cloud ' +
                      'based functions including announcements and grouping.'

            href page: 'authorizePage',
                title: 'Sonos Account Authorization',
                description: configured && authenticated ?
                        'Your Sonos account is connected' :
                        'Select to setup credentials and authenticate to Sonos'

            if (configured && authenticated) {
                href page: 'playerPage',
                    title: 'Sonos Virtual Player Devices',
                    description: 'Select to create Sonos player devices'

                href page: 'groupPage',
                    title: 'Sonos Virtual Group Devices',
                    description: 'Select to create Sonos group devices'
            }
        }

        section {
            input name: 'language',
                  title: 'Select Text to Speach Language',
                  type: 'enum',
                  options: ttsLanguages,
                  defaultValue: 'en-us',
                  required: true,
                  multiple: false,
                  width: 6

            input name: 'logEnable',
                  title: 'Enable Debug logging',
                  type: 'bool',
                  required: false,
                  defaultValue: false
        }
    }
}

Map authorizePage() {
    boolean configured = settings.clientKey != null && settings.clientSecret != null
    boolean authenticated = state.authToken != null

    return dynamicPage(title: 'Sonos Developer Authorization', nextPage: 'mainPage') {
        section {
            paragraph 'You will need to create an account on the <a href=\'https://developer.sonos.com/\' target=\'_blank\'>Sonos Developer site</a>, ' +
                'and then create a new <i>Control Integration</i>. Provide a display name, description and key name ' +
                'and set the redirect URI to <u>https://cloud.hubitat.com/oauth/stateredirect</u> then save the integration. ' +
                'Enter the provided Key and Secret values below then select the account authorization button.'

            input name: 'clientKey', type: 'text', title: 'Client Key', description: '', required: true, defaultValue: '', submitOnChange: true
            input name: 'clientSecret', type: 'password', title: 'Client Secret', description: '', required: true, defaultValue: '', submitOnChange: true
        }

        if (configured) {
            section {
                href url: oauthInitialize(),
                    title: 'Sonos Account Authorization',
                    style: 'external',
                    description: authenticated ?
                        'Your Sonos account is connected, select to re-authenticate' :
                        '<b>Select to authenticate to your Sonos account</b>'
            }
        }
    }
}

Map playerPage() {
    def (Map players) = getPlayersAndGroups()

    return dynamicPage(name: 'players', title: 'Sonos Player Virtual Devices', nextPage: 'mainPage') {
        section {
            paragraph 'Select Sonos players that you want to create Hubitat devices ' +
                      'for control.<br>To remove a player later simply remove the created device.'
            paragraph "Select virtual players to create (${players.size()} players found)"
            input name: 'players',
                  title: '',
                  type: 'enum',
                  options: players.collectEntries { id, player -> [(id): player.name ] },
                  multiple: true
        }
    }
}

Map groupPage() {
    def (Map players, Map groups) = getPlayersAndGroups()
    groups = groups.findAll { id, group -> group.playerIds.size() > 1 }
    Map options = groups.collectEntries { groupId, group ->
        [ (groupId): group.name + ' (' + String.join(', ', (group.playerIds - group.coordinatorId).collect { pid -> players[pid].name }) + ')' ]
    }

    app.removeSetting('groups')
    return dynamicPage(title: 'Sonos Player Virtual Groups', nextPage: 'mainPage') {
        section {
            paragraph 'This page allows you to create virtual Sonos groups that can be activated ' +
                      '(recreated on demand) using the Hubitat devices created.'
            paragraph "Select virtual groups to create (${groups.size()} active groups found)"
            input name: 'groups',
                  title: '',
                  type: 'enum',
                  multiple: true,
                  options: options
        }
    }
}

// Called when the app is first created.
void installed() {
    LOG.info "${app.name} installed"
}

void logsOff() {
    log.warn 'debug logging disabled'
    app.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

// Called when the app is removed.
void uninstalled() {
    LOG.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    LOG.info "${app.name} configuration updated"
    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }

    createPlayerDevices()
    createGroupDevices()
}

/*
 * OAuth Methods
 */
String oauthInitialize() {
    if (state.accessToken == null) {
        createAccessToken()
        state.oauthState = "${apiServerUrl('oauth')}?access_token=${state.accessToken}"
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
        LOG.error 'Aborted due to security problem: OAuth state does not match expected value'
        return authFailure()
    }

    if (params.code == null) {
        LOG.error 'Aborted: OAuth one-time use authorization code not received'
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
                LOG.info 'Received access token'
                parseAuthorizationToken(resp.data)
            } else {
                LOG.error 'OAuth error: ' + resp.data
            }
        }
    } catch (e) {
        LOG.exception "OAuth error: ${e}", e
    }

    return state.authToken == null ? oauthFailure() : oauthSuccess()
}

Map oauthSuccess() {
    return render(contentType: 'text/html', data: """
        <h2 style='color: green;'>Success!</h2>
        <p>Your Sonos Account is now connected to Hubitat</p>
        <p>Close this window to continue setup.</p>
    """)
}

Map oauthFailure() {
    return render(contentType: 'text/html', data: """
        <h2 style='color: red;'>Failed!</h2>
        <p>The connection could not be established!</p>
        <p>Close this window to try again.</p>
    """)
}

void refreshToken() {
    LOG.info 'Refreshing access token'
    String clientId = settings.clientKey
    String clientSecret = settings.clientSecret
    String authorization = (clientId + ':' + clientSecret).bytes.encodeBase64()

    if (clientId == null || clientSecret == null) {
        LOG.error 'Unable to authenticate as client key and secret must be set'
        return
    }

    if (state.refreshToken == null) {
        LOG.error 'Unable to authenticate as refresh token is not set'
        return
    }

    try {
        httpPost([
            uri: 'https://api.sonos.com/login/v3/oauth/access',
            headers: [
                'Authorization': 'Basic ' + authorization
            ],
            body: [
                'grant_type': 'refresh_token',
                'refresh_token': state.refreshToken
            ]
        ]) { response ->
            LOG.debug 'Token refresh response: ' + response.data
            if (response?.data && response?.success) {
                LOG.info 'Received access token'
                parseAuthorizationToken(response.data)
            } else {
                LOG.error 'OAuth error: ' + response.data
            }
        }
    } catch (e) {
        LOG.exception "OAuth error: ${e}", e
    }
}

/*
 * Component Handers
 */
void componentOn(DeviceWrapper device) {
    String householdId = device.getDataValue('householdId')
    List<String> playerIds = device.getDataValue('playerIds')[1..-2].tokenize(', ')
    if (householdId && playerIds) {
        LOG.info "${device} activate household ${householdId} group with players ${playerIds}"
        Map data = [ 'playerIds': playerIds ]
        postJsonAsync("${apiPrefix}/households/${householdId}/groups/createGroup", data)
    }
}

void componentOff(DeviceWrapper device) {
    LOG.debug "${device} off"
}

void componentRefresh(DeviceWrapper device) {
    LOG.debug "${device} refresh"
}

void componentPlayText(DeviceWrapper device, String text, BigDecimal volume = null) {
    String playerId = device.getDataValue('id')
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

    postJsonAsync("${apiPrefix}/${playerId}/audioClip", data)
}

void componentPlayTrack(DeviceWrapper device, String uri, BigDecimal volume = null) {
    String playerId = device.getDataValue('id')
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

    postJsonAsync("${apiPrefix}/players/${playerId}/audioClip", data)
}

/* groovylint-disable-next-line UnusedMethodParameter */
void postResponse(AsyncResponse response, Map data) {
    if (response.hasError()) {
        LOG.error "post request error: ${response.getErrorMessage()}"
    } else if (response.status != 200) {
        LOG.error "post request returned HTTP status ${response.status}"
    } else {
        LOG.debug "postResponse: ${response.data}"
    }
}

/*
 * Internal Methods
 */
private List<Map> createGroupDevices() {
    def (Map players, Map groups) = getPlayersAndGroups()

    List<Map> devices = groups.values().findAll { group -> group.id in settings.groups } ?: []
    for (Map group in devices) {
        String dni = "${app.id}-${group.id}"
        DeviceWrapper device = getChildDevice(dni)
        if (device == null) {
            String name = String.join(', ', group.playerIds.collect { pid -> players[pid].name })
            try {
                LOG.info "Creating group device for ${name}"
                device = addChildDevice('hubitat', 'Generic Component Switch', dni,
                    [
                        name: 'Sonos Group',
                        label: name + ' Group',
                    ]
                )
            } catch (UnknownDeviceTypeException e) {
                LOG.exception 'createGroupDevices', e
            }
        }

        group.each { key, value -> device.updateDataValue(key, value as String) }
    }

    app.removeSetting('groups')
    return devices
}

private List<Map> createPlayerDevices() {
    def (Map players) = getPlayersAndGroups()

    List<Map> devices = players.values().findAll { player -> player.id in settings.players } ?: []
    for (Map player in devices) {
        String dni = "${app.id}-${player.id}"
        DeviceWrapper device = getChildDevice(dni)
        if (device == null) {
            try {
                LOG.info "Creating child audio notification device for ${player.name}"
                device = addChildDevice('component', 'Generic Component Audio Notification', dni,
                    [
                        name: 'Sonos Player',
                        label: player.name + ' Player',
                    ]
                )
            } catch (UnknownDeviceTypeException e) {
                LOG.exception 'Generic Component Audio Notification driver not found (needs installing?)', e
            }
        }

        player.each { key, value -> device.updateDataValue(key, value as String) }
    }

    app.removeSetting('players')
    return devices
}

// Sonos API Methods
private List<String> getHouseholds() {
    List<String> households = []
    LOG.info 'Requesting households'
    httpGet([
        uri: "${apiPrefix}/households",
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

private Map[] getPlayersAndGroups() {
    Map<String, Map> groups = [:]
    Map<String, Map> players = [:]

    for (String householdId in getHouseholds()) {
        def (Map p, Map g) = getPlayersAndGroups(householdId)
        players += p
        groups += g
    }

    return [ players, groups ]
}

private Map[] getPlayersAndGroups(String householdId) {
    Map<String, List<Map>> result = [:]
    LOG.info "Requesting players and groups for ${householdId}"
    httpGet([
        uri: "${apiPrefix}/households/${householdId}/groups",
        headers: [
            'Authorization': 'Bearer ' + state.authToken
        ]
    ]) { resp ->
        if (resp?.data && resp?.success) {
            LOG.debug 'getPlayers request response: ' + resp.data
            result = resp.data
        } else {
            LOG.error 'getPlayers error: ' + resp.data
        }
    }

    Map household = [ householdId: householdId ]
    return [
        result.players.collectEntries { p -> [ (p.id): p + household ] },
        result.groups.collectEntries { g -> [ (g.id): g + household ] }
    ]
}

private void parseAuthorizationToken(Map data) {
    state.authToken = data.access_token
    state.refreshToken = data.refresh_token
    state.authTokenExpires = now() + (data.expires_in * 1000)
    state.scope = data.scope
}

private void postJsonAsync(String uri, Map data = [:]) {
    if (state.authToken == null) {
        LOG.error 'Authorization token not set, please connect the sonos account'
        return
    }

    if (now() >= state.authTokenExpires - 600) {
        refreshToken()
    }

    LOG.debug "post ${uri}: ${data}"
    asynchttpPost('postResponse', [
        uri: uri,
        headers: [
            'Authorization': 'Bearer ' + state.authToken,
        ],
        contentType: 'application/json',
        body: JsonOutput.toJson(data)
    ])
}

@Field private final Map LOG = [
    debug: { s -> if (settings.logEnable) { log.debug(s) } },
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
    'af',    'ar',    'bg',    'bn',    'bs',    'ca',    'cs',    'cy',
    'da',    'de',    'el',    'en',    'eo',    'es',    'et',    'fi',
    'fr',    'gu',    'hi',    'hr',    'hu',    'hy',    'id',    'is',
    'it',    'iw',    'ja',    'jw',    'km',    'kn',    'ko',    'la',
    'lv',    'mk',    'ml',    'mr',    'my',    'ne',    'nl',    'no',
    'pl',    'pt',    'ro',    'ru',    'si',    'sk',    'sq',    'sr',
    'su',    'sv',    'sw',    'ta',    'te',    'th',    'tl',    'tr',
    'uk',    'ur',    'vi',
    // dialects
    'zh-CN',    'zh-cn',    'zh-tw',    'en-us',    'en-ca',    'en-uk',    'en-gb',    'en-au',
    'en-gh',    'en-in',    'en-ie',    'en-nz',    'en-ng',    'en-ph',    'en-za',    'en-tz',
    'fr-ca',    'fr-fr',    'pt-br',    'pt-pt',    'es-es',    'es-us'
]
