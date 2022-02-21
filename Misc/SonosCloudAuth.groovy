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

definition(
    name: 'Sonos Cloud',
    namespace: 'sonos',
    author: 'Jonathan Bradshaw',
    category: 'Audio',
    description: 'Sonos Cloud Music Controller',
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
    return dynamicPage(name: 'prefMain', title: 'Sonos Cloud', nextPage: 'prefDevices', uninstall:false, install: false) {
        section {
            String desc = ''
            if (state.authToken != null && state.authTokenExpires > now()) {
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

Map prefDevices() {
    return dynamicPage(name: 'prefDevices', title: 'Sonos Devices', uninstall:true, install: true) {
        section {
        }
    }
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
}

// OAuth Routines
String oauthInitialize() {
    if (state.accessToken == null) {
        createAccessToken()
        state.oauthState = "${getHubUID()}/apps/${app.id}/callback?access_token=${state.accessToken}"
    }

    String clientId = ''
    String redirectUri = URLEncoder.encode('https://cloud.hubitat.com/oauth/stateredirect')
    String state = URLEncoder.encode(state.oauthState)

    return "https://api.sonos.com/login/v3/oauth?client_id=${clientId}&response_type=code&state=${state}&scope=playback-control-all&redirect_uri=${redirectUri}"
}

Map oauthCallback() {
    state.remove('authToken')
    state.remove('authTokenExpires')
    state.remove('refreshToken')
    state.remove('scope')

    log.info "oauthCallback: ${params}"
    if (params.state != state.oauthState) {
        log.error 'Aborted due to security problem: OAuth state does not match expected value'
        return authFailure()
    }

    if (params.code == null) {
        log.error 'Aborted: OAuth one-time use authorization code not received'
        return authFailure()
    }

    try {
        log.info 'Requesting access token'
        String clientId = ''
        String clientSecret = ''
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
            log.debug 'Token request response: ' + resp.data
            if (resp && resp.data && resp.success) {
                log.info 'Received access token'
                state.authToken = resp.data.access_token
                state.authTokenExpires = (now() + (resp.data.expires_in * 1000)) - 60000
                state.refreshToken = resp.data.refresh_token
                state.scope = resp.data.scope
            } else {
                log.error 'OAuth error: ' + resp.data
            }
        }
    } catch (e) {
        log.error "OAuth error: ${e}"
    }

    return state.authToken == null ? authFailure() : authSuccess()
}

Map authSuccess() {
    return render(contentType: 'text/html', data: """
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

// Called when the app is removed.
void uninstalled() {
    log.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    log.debug settings
    initialize()
}

// Called when the driver is initialized.
void initialize() {
    log.info "${app.name} initializing"
}
