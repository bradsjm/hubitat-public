/**
 *  MIT License
 *  Copyright 2021 Jonathan Bradshaw (jb@nrgup.net)
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

import com.hubitat.hub.domain.Event
import hubitat.helper.ColorUtils
import hubitat.scheduling.AsyncResponse
import java.math.RoundingMode

definition (
    name: 'Color Magic Lighting',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Colormind is a deep learning color scheme generator with different datasets loaded each day',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page name: 'pageMain', content: 'pageMain'
}

/*
 * Configuration UI
 */
/* groovylint-disable-next-line MethodSize */
Map pageMain() {
    return dynamicPage(name: 'pageMain', title: 'Color Magic Lighting',
                      install: true, uninstall: true, hideWhenEmpty: true) {
        section {
            label title: 'Application Label',
                  required: false
        }

        section {
            input name: 'masterEnable',
                  type: 'bool',
                  title: 'Application enabled',
                  required: false,
                  defaultValue: true
        }

        section {
            input name: 'model',
                title: 'Select ColorMind color model',
                type: 'enum',
                defaultValue: 'default',
                options: state.models.collect { m -> [ (m): m.split('_')*.capitalize().join(' ') ] },
                submitOnChange: true,
                width: 8

            input name: 'modelInterval',
                title: 'ColorMind update frequency:',
                type: 'number',
                defaultValue: 60,
                description: 'minutes',
                range: '0..1440',
                required: false,
                width: 4

            input name: 'schemeType',
                title: 'Color Group Scheme Type',
                type: 'enum',
                defaultValue: 'analogic',
                options: [
                    [ 'analogic': 'Analogic (colors that are next to each other)' ],
                    [ 'complement': 'Complementary (directly across from each other)' ],
                    [ 'analogic-complement': 'Analogic-Complementary combination' ],
                    [ 'triad': 'Triad (three colors evenly spaced)' ],
                    [ 'quad': 'Quad (four colors evenly spaced)' ]
                ],
                required: true,
                width: 8
        }

        section {
            paragraph 'Select RGB devices to use as seed colors for the palette:'
            input name: 'seed',
                  type: 'capability.colorControl',
                  title: 'Seed colors devices (maximum 5)',
                  multiple: true,
                  required: false,
                  submitOnChange: false
        }

        section('Color Groups', hideable: true, hidden: false) {
            paragraph 'Select RGB devices for each palette color group. ' +
                      "Within each color group the devices will use ${settings.schemeType} colors:"

            input name: 'group0',
                  type: 'capability.colorControl',
                  title: 'Group 1 RGB lights',
                  multiple: true,
                  required: false,
                  submitOnChange: true

            input name: 'group1',
                  type: 'capability.colorControl',
                  title: 'Group 2 RGB lights',
                  multiple: true,
                  required: false,
                  submitOnChange: true

            input name: 'group2',
                  type: 'capability.colorControl',
                  title: 'Group 3 RGB lights',
                  multiple: true,
                  required: false,
                  submitOnChange: true

            input name: 'group3',
                  type: 'capability.colorControl',
                  title: 'Group 4 RGB lights',
                  multiple: true,
                  required: false,
                  submitOnChange: true

            input name: 'group4',
                  type: 'capability.colorControl',
                  title: 'Group 5 RGB lights',
                  multiple: true,
                  required: false,
                  submitOnChange: true
        }

        section('Controller Restrictions', hideable: true, hidden: true) {
            input name: 'minBright',
                title: 'Minimum brightness level:',
                type: 'number',
                defaultValue: 10,
                description: 'level',
                range: '1..100',
                required: true,
                width: 6

            input name: 'maxBright',
                title: 'Maximum brightness level:',
                type: 'number',
                defaultValue: 100,
                description: 'level',
                range: '1..100',
                required: true,
                width: 6

            input name: 'disabledSwitchWhenOn',
                  title: 'Select switch(s) to disable application when ON',
                  type: 'capability.switch',
                  multiple: true

            input name: 'disabledSwitchWhenOff',
                  title: 'Select switch(s) to disable application when OFF',
                  type: 'capability.switch',
                  multiple: true

            input name: 'disabledModes',
                  type: 'mode',
                  title: 'Select mode(s) to disable application when active',
                  multiple: true

            input name: 'disabledVariable',
                  title: 'Select variable to enable or disable:',
                  type: 'enum',
                  options: getGlobalVarsByType('boolean').collect { v -> [(v.key): "${v.key} (currently ${v.value.value})"] }
                  multiple: false
        }

        section {
            input name: 'btnTestColor',
                title: 'Test Colors',
                width: 4,
                type: 'button'
        }

        section {
            input name: 'logEnable',
                  title: 'Enable Debug logging',
                  type: 'bool',
                  required: false,
                  defaultValue: true
        }
    }
}

// Page button handler
void appButtonHandler(String buttonName) {
    switch (buttonName) {
        case 'btnTestColor':
            getModelColors()
            break
    }
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
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

// Called when the app is initialized.
void initialize() {
    unschedule()
    unsubscribe()
    log.info "${app.name} initializing"
    state.seedColors = ['N', 'N', 'N', 'N', 'N']
    subscribe(settings.seed, 'seedChangeHandler')
    schedule('0 0 3 ? * * *', 'getModels')
    getModelList()
}

// called when there is a change to the seed devices
/* groovylint-disable-next-line UnusedMethodParameter */
void seedChangeHandler(Event evt) {
    /* groovylint-disable-next-line DuplicateListLiteral */
    state.seedColors = ['N', 'N', 'N', 'N', 'N']
    settings.seed.eachWithIndex { seed, index ->
        def (int r, int g, int b) = ColorUtils.hsvToRGB([
            seed.currentValue('hue'),
            seed.currentValue('saturation'),
            seed.currentValue('level')
        ])
        state.seedColors[index] = [r, g, b]
    }

    getModelColors()
}

// Returns true if the controller is enabled
private boolean checkEnabled() {
    if (!settings.masterEnable) { return false }

    if (location.mode in settings.disabledModes) {
        log.info "${app.name} is disabled due to mode ${location.mode}"
        return false
    }

    if (settings.disabledSwitchWhenOn &&
        settings.disabledSwitchWhenOn.any { device -> device.currentValue('switch') == 'on' }) {
        log.info "${app.name} is disabled (disable switch is ON)"
        return false
    }

    if (settings.disabledSwitchWhenOff &&
        settings.disabledSwitchWhenOff.any { device -> device.currentValue('switch') == 'off' }) {
        log.info "${app.name} is disabled (disable switch is OFF)"
        return false
    }

    if (settings.disabledVariable && getGlobalVar(settings.disabledVariable)?.value == false) {
        log.info "${app.name} is disabled (${settings.disabledVariable} is false)"
        return false
    }

    // Check if any lamp is on
    return (0..5).any { group ->
        List lamps = settings["group${group}"] ?: []
        return lamps.any { d -> d?.currentValue('switch') == 'on' }
    }
}

private void getModelList() {
    Map params = [
        uri: 'http://colormind.io',
        path: '/list',
        requestContentType: 'application/json'
    ]
    if (logEnable) { log.debug 'Requesting color models' }
    asynchttpGet('getModelListHandler', params)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void getModelListHandler(AsyncResponse response, Map data) {
    if (response.status != 200) {
        log.error "colormind.com returned HTTP status ${status}"
        return
    }

    if (logEnable) { log.debug "API returned: ${response.data}" }
    state.models = response.json.result

    getModelColors()
}

private void getModelColors() {
    if (settings.modelInterval) {
        runIn(settings.modelInterval * 60, 'getModelColors')
    }

    if (!checkEnabled()) { return }

    Map params = [
        uri: 'http://colormind.io',
        path: '/api/',
        body: [
            'model': (settings.model in state.models) ? settings.model : 'default',
            'input': state.seedColors,
        ],
        requestContentType: 'application/json'
    ]

    if (logEnable) { log.debug "Requesting color palette ${params.body}" }
    asynchttpPost('getModelColorsHandler', params)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void getModelColorsHandler(AsyncResponse response, Map data) {
    if (response.status != 200) {
        log.error "colormind.com returned HTTP status ${status}"
        return
    }

    if (logEnable) { log.debug "API returned: ${response.data}" }
    state.scheme = response.json.result
    getColorScheme()
}

private void getColorScheme() {
    if (!checkEnabled()) { return }

    state.scheme.eachWithIndex { color, index ->
        List lamps = settings["group${index}"]
        if (lamps) {
            getColorScheme(color, index, lamps.size())
        }
    }
}

private void getColorScheme(List rgb, int group, int count) {
    Map params = [
        uri: 'http://www.thecolorapi.com',
        path: '/scheme',
        query: [
            'rgb': "rgb(${rgb[0]},${rgb[1]},${rgb[2]})",
            'mode': settings.schemeType,
            'count': count,
            'format': 'json'
        ],
        requestContentType: 'application/json'
    ]
    if (logEnable) { log.debug "Requesting color palette ${params.query}" }
    asynchttpGet('getColorSchemeHandler', params, [ group: group ])
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void getColorSchemeHandler(AsyncResponse response, Map data) {
    if (response.status != 200) {
        log.error "thecolorapi.com returned HTTP status ${response.status}"
        return
    } else if (response?.error) {
        log.error "thecolorapi.com Json parsing error: ${json.error.code} ${json.error.message}"
        return
    }

    if (logEnable) { log.debug "API returned: ${response.data}" }

    response.json.colors.eachWithIndex { color, index ->
        Map colorMap = [
            hue: (color.hsv.h / 3.6 as BigDecimal).setScale(1, RoundingMode.HALF_UP),
            saturation: color.hsv.s,
            level: color.hsv.v
        ]
        if (colorMap.level < settings.minBright) { colorMap.level = settings.minBright }
        if (colorMap.level > settings.maxBright) { colorMap.level = settings.maxBright }
        List lamps = settings["group${data.group}"] ?: []
        if (lamps[index]) {
            log.info "Set ${lamps[index]} (Group ${data.group + 1}) to ${colorMap}"
            lamps[index]?.setColor(colorMap)
        }
    }
}
