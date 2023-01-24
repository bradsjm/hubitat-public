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

/*
 * Thanks to Mattias Fornander (@mfornander) for the original application concept
 *
 * Version history:
 *  0.1  - Initial development (alpha)
 *  0.2  - Initial Beta Test release
 *  0.3  - Add condition current state feedback indicator
 *  0.4  - Add 'autostop' effect option to clear effect
 *  0.5  - Add additional device support for Inovelli Red switches and dimmers
 *  0.6  - Add additional effect types support
 *  0.7  - Fixes for split effect definitions (effects vs effectsAll)
 *  0.8  - Add location mode condition
 *  0.9  - Add delayed activation option per dashboard and variable level
 *  0.91 - Increase number of priorities, fix driver titles, allow 0 for delay time
 *  0.92 - Add test/clear buttons for testing indications and duplicate dashboard
 *  0.93 - Change sorting to include condition title as second key
 *  0.94 - Fix LED display order and effect names and add Red Series Fan + Switch LZW36 support
 *  0.95 - Fix broken pause and update LZW36 support
 *  0.96 - Allow force refresh interval to be specified and fixes device tracking issue
 *  0.97 - Fixes for Red Series Fan + Switch LZW36 support
 *  0.98 - Update driver name for Blue Fan Switch and updated effect order and consistency of options
 *  0.99 - Add initial support for RGB child devices used by older Red switches and custom levels
 *
*/

@Field static final String Version = '0.99'

definition(
    name: 'LED Mini-Dashboard Topic',
    namespace: 'nrgup',
    parent: 'nrgup:LED Mini-Dashboard',
    author: 'Jonathan Bradshaw',
    description: 'LED Mini-Dashboard Child',
    importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/LedMiniDashboard/LedDashboardChild.groovy',
    iconUrl: '',
    iconX2Url: '',
    singleThreaded: false
)

preferences {
    page(name: 'mainPage', install: true, uninstall: true)
    page(name: 'editPage', previousPage: 'mainPage')
}

import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher

/*
 * Define the supported notification device types
 */
@Field static final Map<String, Map> DeviceTypeMap = [
    'Inovelli Blue Switch': [
        title: 'Inovelli Dimmer 2-in-1 Blue Series VZM31-SN',
        type: 'device.InovelliDimmer2-in-1BlueSeriesVZM31-SN',
        leds: [ 'All': 'All LEDs', '7': 'LED 7 (Top)', '6': 'LED 6', '5': 'LED 5', '4': 'LED 4', '3': 'LED 3', '2': 'LED 2', '1': 'LED 1 (Bottom)', 'var': 'Variable LED' ],
        effects: [ '255': 'Stop', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '0': 'Off', 'var': 'Variable Effect' ],
        effectsAll: [ '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Open/Close', '7': 'Small-to-Big', '8': 'Aurora', '9': 'Slow Falling', '10': 'Medium Falling', '11': 'Fast Falling',
            '12': 'Slow Rising', '13': 'Medium Rising', '14': 'Fast Rising', '15': 'Medium Blink', '16': 'Slow Chase', '17': 'Fast Chase', '18': 'Fast Siren', '19': 'Slow Siren', '0': 'Off', 'var': 'Variable Effect' ]
    ],
    'Inovelli Dimmer': [
        title: 'Inovelli Dimmer LZW31',
        type: 'device.InovelliDimmerLZW31',
        leds: [ 'All': 'Notification' ],
        ledLevelParam: 14,
        ledColorParam: 13,
        effects: [:],
        effectsAll: [ '255': 'Stop', '1': 'Solid', 'var': 'Variable Effect' ]
    ],
    'Inovelli Switch': [
        title: 'Inovelli Switch LZW30',
        type: 'device.InovelliSwitchLZW30',
        leds: [ 'All': 'Notification' ],
        ledLevelParam: 6,
        ledColorParam: 5,
        effects: [:],
        effectsAll: [ '255': 'Stop', '1': 'Solid', 'var': 'Variable Effect' ]
    ],
    'Inovelli Red Dimmer': [
        title: 'Inovelli Dimmer Red Series LZW31-SN',
        type: 'device.InovelliDimmerRedSeriesLZW31-SN',
        leds: [ 'All': 'Notification' ],
        effects: [:],
        effectsAll: [ '255': 'Stop', '1': 'Solid', '2': 'Chase', '3': 'Fast Blink', '4': 'Slow Blink', '5': 'Pulse', 'var': 'Variable Effect' ]
    ],
    'Inovelli Blue Fan Switch': [
        title: 'Inovelli Fan Switch Blue Series VZM35-SN',
        type: 'device.InovelliFanSwitchBlueSeriesVZM35-SN',
        leds: [ 'All': 'All LEDs', '7': 'LED 7 (Top)', '6': 'LED 6', '5': 'LED 5', '4': 'LED 4', '3': 'LED 3', '2': 'LED 2', '1': 'LED 1 (Bottom)', 'var': 'Variable LED' ],
        effects: [ '255': 'Stop', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '0': 'Off', 'var': 'Variable Effect' ],
        effectsAll: [ '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Open/Close', '7': 'Small-to-Big', '8': 'Aurora', '9': 'Slow Falling', '10': 'Medium Falling', '11': 'Fast Falling',
            '12': 'Slow Rising', '13': 'Medium Rising', '14': 'Fast Rising', '15': 'Medium Blink', '16': 'Slow Chase', '17': 'Fast Chase', '18': 'Fast Siren', '19': 'Slow Siren', '0': 'Off', 'var': 'Variable Effect' ]
    ],
    'Inovelli Red Fan Light': [
        title: 'Inovelli Fan + Light Red Series LZW36',
        type: 'device.InovelliFan%2BLightLZW36',
        leds: [ '1': 'Light', '2': 'Fan' ],
        effects: [ '255': 'Stop', '1': 'Solid', '2': 'Chase', '3': 'Fast Blink', '4': 'Slow Blink', '5': 'Pulse', 'var': 'Variable Effect' ],
        effectsAll: [:]
    ],
    'Inovelli Red Switch': [
        title: 'Inovelli Switch Red Series LZW30-SN',
        type: 'device.InovelliSwitchRedSeriesLZW30-SN',
        leds: [ 'All': 'Notification' ],
        effects: [:],
        effectsAll: [ '255': 'Stop', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', 'var': 'Variable Effect' ]
    ],
    'RGB': [
        title: 'Generic RGB Device',
        type: 'capability.colorControl',
        leds: [ 'All': 'Color' ],
        effects: [:],
        effectsAll: [ '255': 'None', '0': 'Off', '1': 'On', 'var': 'Variable Effect' ]
    ]
]

// Definitions for condition options
@Field static final Map<String, String> ColorMap = [ '0': 'Red', '10': 'Orange', '40': 'Lemon', '91': 'Lime', '120': 'Green', '150': 'Teal', '180': 'Cyan', '210': 'Aqua',
    '241': 'Blue', '269': 'Violet', '300': 'Magenta', '332': 'Pink', '360': 'White', 'val': 'Custom Color', 'var': 'Variable Color' ]

@Field static final Set<Integer> Priorities = 20..1 // can be increased if desired

@Field static final Map<String, String> LevelMap = [ '10': '10', '20': '20', '30': '30', '40': '40', '50': '50',
    '60': '60', '70': '70', '80': '80', '90': '90', '100': '100', 'val': 'Custom', 'var': 'Variable' ]

@Field static final Map<String, String> TimePeriodsMap = [ '0': 'Seconds', '60': 'Minutes', '120': 'Hours', '255': 'Infinite' ]

// Tracker for device LED state to optimize traffic by only sending changes
@Field static final Map<String, Map> DeviceStateTracker = new ConcurrentHashMap<>()

// Defines the text used to show the application is paused
@Field static final String pauseText = '<span style=\'color: red;\'> (Paused)</span>'

/*
 * Application Main Page
 */
Map mainPage() {
    if (app.label == null) {
        app.updateLabel('New LED Mini-Dashboard')
    }
    updatePauseLabel()

    if (settings.removeSettings) {
        removeSettings(settings.removeSettings)
        app.removeSetting('removeSettings')
    }

    return dynamicPage(name: 'mainPage', title: "<h2 style=\'color: #1A77C9; font-weight: bold\'>${app.label}</h2>") {
        Map deviceType = getDeviceType()
        section {
            input name: 'deviceType',
                title: '',
                description: '<b>Select the target device type</b> <i>(one type per mini-dashboard)</i>',
                type: 'enum',
                options: DeviceTypeMap.collectEntries { dt -> [ dt.key, dt.value.title ] },
                multiple: false,
                required: true,
                submitOnChange: true,
                width: 10

            if (state.paused) {
                input name: 'resume', title: 'Resume', type: 'button', width: 1
            } else {
                input name: 'pause', title: 'Pause', type: 'button', width: 1
            }

            if (deviceType) {
                input name: 'switches',
                    title: "Select ${settings['deviceType']} devices to include in mini-dashboard",
                    type: deviceType.type,
                    required: true,
                    multiple: true,
                    submitOnChange: true,
                    width: 10
            }
        }

        if (deviceType && settings['switches']) {
            Set<String> prefixes = getSortedDashboardPrefixes()
            section("<h3 style=\'color: #1A77C9; font-weight: bold\'>${app.label} Activation Conditions</h3>") {
                for (String prefix in prefixes) {
                    String name = settings["${prefix}_name"]
                    boolean active = evaluateConditions(prefix)
                    String currentResult = active ? ' <span style=\'color: green\'>(true)</span>' : ''
                    href(
                        name: "edit_${prefix}",
                        title: "<b>${name}</b>${currentResult}",
                        description: getDashboardDescription(prefix),
                        page: 'editPage',
                        params: [ prefix: prefix ],
                        state: currentResult ? 'complete' : '',
                        width: 10
                    )
                    input name: 'remove_' + prefix,
                        title: '<i style="font-size:1rem; color:red;" class="material-icons he-bin"></i>',
                        type: 'button',
                        width: 1
                }

                href(
                    name: 'addDashboard',
                    title: '<i>Select to add a new activation condition</i>',
                    description: '',
                    params: [ prefix: getNextPrefix() ],
                    page: 'editPage',
                    width: 10
                )
            }
        }

        if (!state.paused) {
            section {
                label title: 'Name this LED Mini-Dashboard Topic:', width: 8, submitOnChange: true, required: true
                input name: 'duplicate', title: 'Duplicate', type: 'button', width: 2
            }
        }

        if (state.message) {
            section { paragraph state.message }
            state.remove('message')
        }

        section {
            input name: 'logEnable', title: 'Enable debug logging', type: 'bool', defaultValue: false, width: 4
            input name: 'periodicRefresh', title: 'Enable periodic forced refresh', type: 'bool', defaultValue: false, width: 8, submitOnChange: true
            if (settings.periodicRefresh) {
                input name: 'periodicRefreshInterval', title: 'Refresh interval (minutes):', type: 'number', width: 3, range: '1..1440', defaultValue: 60
            } else {
                app.removeSetting('periodicRefreshInterval')
            }

            paragraph "<span style='font-size: x-small; font-style: italic;'>Version ${Version}</span>"
        }
    }
}

/*
 * Dashboard Edit Page
 */
Map editPage(Map params = [:]) {
    String prefix = params.prefix
    if (!prefix) { return mainPage() }
    String name = settings["${prefix}_name"] ?: 'New Activation Condition'

    return dynamicPage(name: 'editPage', title: "<h3 style=\'color: #1A77C9; font-weight: bold\'>${name}</h3><br>") {
        renderIndicationSection(prefix)
        if (settings["${prefix}_lednumber"]) {
            Map deviceType = getDeviceType()
            Map<String, String> fxOptions = settings["${prefix}_lednumber"] == 'All' ? deviceType.effectsAll : deviceType.effects
            String effectName = fxOptions[settings["${prefix}_effect"]] ?: 'mini-dashboard'
            renderConditionSection(prefix, "<span style=\'color: green; font-weight: bold\'>Select means to activate LED ${effectName} effect:</span><span class=\"required-indicator\">*</span>")

            if (settings["${prefix}_conditions"]) {
                section {
                    input name: "${prefix}_delay", title: '<i>For number of minute(s):</i>', description: '1..60', type: 'number', width: 3, range: '0..60', required: false
                    paragraph '', width: 1
                    if (settings["${prefix}_effect"] != '255') {
                        String title = 'When conditions stop matching '
                        title += settings["${prefix}_autostop"] == false ? '<i>leave effect running</i>' : '<b>stop the effect</b>'
                        input name: "${prefix}_autostop", title: title, type: 'bool', defaultValue: true, width: 4, submitOnChange: true
                    } else {
                        app.removeSetting("${prefix}_autostop")
                    }
                }

                section {
                    input name: "${prefix}_name", title: '<b>Activation Condition Name:</b>', type: 'text', defaultValue: getSuggestedConditionName(prefix), width: 7, required: true, submitOnChange: true
                    input name: "${prefix}_priority", title: '<b>Priority:</b>', type: 'enum', options: getAvailablePriorities(prefix), width: 2, required: true
                    paragraph '<i>Higher value condition priorities take LED precedence.</i>'
                    input name: "test_${prefix}", title: 'Test', type: 'button', width: 2
                    input name: 'reset', title: 'Reset', type: 'button', width: 2
                }
            }
        }
    }
}

/*
 * Dashboard Edit Page LED Indication Section
 */
Map renderIndicationSection(String prefix, String title = null) {
    Map deviceType = getDeviceType()
    String ledNumber = settings["${prefix}_lednumber"]
    String ledName = deviceType.leds[settings[ledNumber]] ?: 'LED'

    return section(title) {
        // LED Number
        input name: "${prefix}_lednumber", title: '<span style=\'color: blue;\'>LED Number</span>', type: 'enum', options: deviceType.leds, width: 3, required: true, submitOnChange: true
        if (settings["${prefix}_lednumber"] == 'var') {
            input name: "${prefix}_lednumber_var", title: "<span style=\'color: blue;\'>LED Number Variable</span>", type: 'enum', options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
        } else {
            app.removeSetting("${prefix}_lednumber_var")
        }

        // Effect
        if (ledNumber) {
            Map<String, String> fxOptions = ledNumber == 'All' ? deviceType.effectsAll : deviceType.effects
            String effect = settings["${prefix}_effect"]
            input name: "${prefix}_effect", title: "<span style=\'color: blue;\'>${ledName} Effect</span>", type: 'enum', options: fxOptions, width: 2, required: true, submitOnChange: true
            if (settings["${prefix}_effect"] == 'var') {
                input name: "${prefix}_effect_var", title: "<span style=\'color: blue;\'>Effect Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
            } else {
                app.removeSetting("${prefix}_effect_var")
            }

            // Color
            if (effect != '0' && effect != '255') {
                String color = settings["${prefix}_color"]
                input name: "${prefix}_color", title: "<span style=\'color: blue;\'>${ledName} Color</span>", type: 'enum', options: ColorMap, width: 3, required: true, submitOnChange: true
                if (color == 'val') {
                    String url = '''<a href="https://community-assets.home-assistant.io/original/3X/6/c/6c0d1ea7c96b382087b6a34dee6578ac4324edeb.png" target="_blank">'''
                    input name: "${prefix}_color_val", title: url + "<span style=\'color: blue; text-decoration: underline;\'>Hue Value</span></a>", type: 'number', range: '0..360', width: 2, required: true, submitOnChange: true
                } else {
                    app.removeSetting("${prefix}_color_val")
                }
                if (color == 'var') {
                    input name: "${prefix}_color_var", title: "<span style=\'color: blue;\'>Color Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
                } else {
                    app.removeSetting("${prefix}_color_var")
                }
            } else {
                app.removeSetting("${prefix}_color")
                app.removeSetting("${prefix}_color_var")
                app.removeSetting("${prefix}_color_val")
            }

            if (effect != '255') {
                // Time Unit
                input name: "${prefix}_unit", title: '<span style=\'color: blue;\'>Duration</span>', description: 'Select', type: 'enum', options: TimePeriodsMap, width: 2, defaultValue: 'Infinite', required: true, submitOnChange: true
                if (settings["${prefix}_unit"] in ['0', '60', '120']) {
                    // Time Duration
                    String timePeriod = TimePeriodsMap[settings["${prefix}_unit"]]
                    input name: "${prefix}_duration", title: "<span style=\'color: blue;\'>${timePeriod}&nbsp;</span>", type: 'enum', width: 2, defaultValue: 1, required: true, options:
                        [ '1', '2', '3', '4', '5', '10', '15', '20', '25', '30', '40', '50', '60' ]
                } else {
                    app.removeSetting("${prefix}_duration")
                }
            } else {
                app.removeSetting("${prefix}_unit")
                app.removeSetting("${prefix}_duration")
            }

            if (effect != '0' && effect != '255') {
                // Level
                input name: "${prefix}_level", title: "<span style=\'color: blue;\'>Level&nbsp;</span>", type: 'enum', width: 2,
                    defaultValue: 100, options: LevelMap, required: true, submitOnChange: true
                if (settings["${prefix}_level"] == 'val') {
                    input name: "${prefix}_level_val", title: "<span style=\'color: blue;\'>Level Value&nbsp;</span>", type: 'number', range: '1..100', width: 2, required: true, submitOnChange: true
                } else {
                    app.removeSetting("${prefix}_level_val")
                }
                if (settings["${prefix}_level"] == 'var') {
                    input name: "${prefix}_level_var", title: "<span style=\'color: blue;\'>Level Variable</span>", type: 'enum', options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
                } else {
                    app.removeSetting("${prefix}_level_var")
                }
            } else {
                app.removeSetting("${prefix}_level")
                app.removeSetting("${prefix}_level_var")
                app.removeSetting("${prefix}_level_val")
            }
            paragraph ''
        }
    }
}

/*
 *  Dashboard Edit Page Conditions Section
 *  Called from the application page, renders to the user interface a section to view and edit
 *  conditions and events defined in the ConditionsMap above.
 *
 *  @param prefix          The settings prefix to use (e.g. conditions_1) for persistence
 *  @param sectionTitle    The section title to use
 *  @param ruleDefinitions The rule definitions to use (see ConditionsMap)
 *  @returns page section
 */
Map renderConditionSection(String prefix, String sectionTitle = null, Map<String, Map> ruleDefinitions = ConditionsMap) {
    return section(sectionTitle) {
        Map<String, String> conditionTitles = ruleDefinitions.collectEntries { String k, Map v -> [ k, v.title ] }
        List<String> selectedConditions = settings["${prefix}_conditions"] ?: []
        input name: "${prefix}_conditions", title: '', type: 'enum', options: conditionTitles, multiple: true, required: true, submitOnChange: true, width: 9

        Boolean allConditionsMode = settings["${prefix}_conditions_all"] ?: false
        if (settings["${prefix}_conditions"]?.size() > 1) {
            String title = "${allConditionsMode ? '<b>All</b> conditions' : '<b>Any</b> condition'}"
            input name: "${prefix}_conditions_all", title: title, type: 'bool', width: 3, submitOnChange: true
        }

        boolean isFirst = true
        Map<String, Map> selectedConditionsMap = ruleDefinitions.findAll { String k, Map v -> k in selectedConditions }
        for (Map.Entry<String, Map> condition in selectedConditionsMap) {
            String id = "${prefix}_${condition.key}"
            String currentResult = evaluateCondition(id, condition.value) ? ' <span style=\'color: green\'>(currently true)</span>' : ''
            if (!isFirst) {
                paragraph allConditionsMode ? '<b>and</b>' : '<i>or</i>'
            }
            isFirst = false
            Map<String, Map> inputs = condition.value.inputs
            if (inputs.device) {
                input name: "${id}_device",
                    title: (inputs.device.title ?: condition.value.title) + currentResult,
                    type: inputs.device.type,
                    width: inputs.device.width ?: 7,
                    multiple: inputs.device.multiple,
                    submitOnChange: true,
                    required: true
                if (!inputs.device.any && settings["${id}_device"] in Collection && settings["${id}_device"]?.size() > 1) {
                    String name = inputs.device.name ?: condition.value.name
                    input name: "${id}_device_all",
                        title: settings["${id}_device_all"] ? "<b>All</b> ${name} devices" : "<b>Any</b> ${name} device",
                        type: 'bool',
                        submitOnChange: true,
                        width: 4
                }
            }

            if (inputs.choice) {
                Object options
                if (inputs.choice.options in Closure) {
                    Map ctx = [ device: settings["${id}_device"] ]
                    options = runClosure(inputs.choice.options as Closure, ctx)
                } else {
                    options = inputs.choice.options
                }
                if (options) {
                    input name: "${id}_choice",
                        title: (inputs.choice.title ?: condition.value.title) + currentResult,
                        defaultValue: inputs.choice.defaultValue,
                        options: options,
                        width: inputs.choice.width ?: 7,
                        multiple: inputs.choice.multiple,
                        type: 'enum',
                        submitOnChange: true,
                        required: true
                }
            }

            if (inputs.comparison) {
                Object options
                if (inputs.comparison.options in Closure) {
                    Map ctx = [ device: settings["${id}_device"], choice: settings["${id}_choice"] ]
                    options = runClosure(inputs.comparison.options as Closure, ctx)
                } else {
                    options = inputs.comparison.options
                }
                input name: "${id}_comparison",
                    title: (inputs.comparison.title ?: 'Comparison') + ' ',
                    width: inputs.comparison.width ?: 2,
                    type: 'enum',
                    options: options,
                    defaultValue: inputs.comparison.defaultValue,
                    required: true
            }

            if (inputs.value) {
                Object options
                if (inputs.value.options in Closure) {
                    Map ctx = [ device: settings["${id}_device"], choice: settings["${id}_choice"] ]
                    options = runClosure(inputs.value.options as Closure, ctx)
                } else {
                    options = inputs.value.options
                }
                input name: "${id}_value",
                    title: (inputs.value.title ?: 'Value') + ' ',
                    width: inputs.value.width ?: 3,
                    defaultValue: inputs.value.defaultValue,
                    options: options,
                    type: options ? 'enum' : 'text',
                    required: true
            }
        }
    }
}

/*
 * Used by parent to create duplicate dashboards
 */
Map getSettings() {
    return settings.findAll { k, v ->
        k != 'switches' && !k.endsWith('_device')
    }
}

void putSettings(Map newSettings) {
    newSettings.each { k, v -> app.updateSetting(k, v) }
    state.paused = true
    updatePauseLabel()
}

/*
 * Event Handlers (methods invoked by the Hub)
 */

// Invoked when a button input in the UI is pressed
void appButtonHandler(String buttonName) {
    logDebug "button ${buttonName} pushed"
    switch (buttonName) {
        case 'duplicate':
            parent.duplicate(app.id)
            state.message = '<span style=\'color: green\'>Duplication complete</span>'
            break
        case 'pause':
            logInfo 'pausing dashboard'
            state.paused = true
            updated()
            break
        case 'resume':
            logInfo 'resuming dashboard'
            state.paused = false
            updated()
            break
        case ~/^remove_(.+)/:
            String prefix = Matcher.lastMatcher[0][1]
            removeSettings(prefix)
            break
        case 'reset':
            runInMillis(200, 'notificationDispatcher')
            break
        case ~/^test_(.+)/:
            String prefix = Matcher.lastMatcher[0][1]
            Map<String, String> config = getDashboardConfig(prefix)
            replaceVariables(config)
            updateDeviceLedState(config)
            break
        default:
            logWarn "unknown app button ${buttonName}"
            break
    }
}

/*
 *  Main event handler receives device and location events and runs through
 *  all the conditions to determine if LED states need to be updated on switches.
 *
 *  Maintains priorities per LED to ensure that higher priority conditions take
 *  precedence over lower priorities.
 */
void eventHandler(Event event) {
    Map lastEvent = [
        descriptionText: event.descriptionText,
        deviceId: event.deviceId,
        isStateChange: event.isStateChange,
        name: event.name,
        source: event.source,
        type: event.type,
        unit: event.unit,
        unixTime: event.unixTime,
        value: event.value
    ]
    logDebug "<b>eventHandler:</b> ${lastEvent}"
    state.lastEvent = lastEvent

    // Debounce multiple events hitting at the same time by using timer job
    runInMillis(200, 'notificationDispatcher')
}

// Invoked when the app is first created.
void installed() {
    logInfo 'mini-dashboard child created'
}

// Invoked by the hub when a global variable is renamed
void renameVariable(String oldName, String newName) {
    settings.findAll { s -> s.value == oldName }.each { s ->
        logInfo "updating variable name ${oldName} to ${newName} for ${s.key}"
        s.value = newName
    }
}

// Invoked when the app is removed.
void uninstalled() {
    unsubscribe()
    unschedule()
    removeAllInUseGlobalVar()
    resetNotifications()
    logInfo 'uninstalled'
}

// Invoked when the settings are updated.
void updated() {
    logInfo 'configuration updated'

    // Clear tracked devices
    DeviceStateTracker.clear()

    // Clean out unused settings
    cleanSettings()

    // Remove any scheduled tasks
    unschedule()

    // Unsubscribe from any events
    unsubscribe()

    // Remove global variable registrations
    removeAllInUseGlobalVar()

    // Reset all notifications
    resetNotifications()

    if (state.paused) {
        return
    }

    // Clear state (used mostly for tracking delayed conditions)
    state.clear()

    // Subscribe to events from switches
    subscribeAllSwitches()

    // Subscribe to events from conditions
    subscribeAllConditions()

    // Subscribe to global variables
    subscribeVariables()

    // Dispatch the current notifications
    runInMillis(200, 'notificationDispatcher')

    if (settings.periodicRefresh) {
        logInfo "enabling periodic forced refresh every ${settings.periodicRefreshInterval} minutes"
        int seconds = 60 * (settings.periodicRefreshInterval ?: 60)
        runIn(seconds, 'forceRefresh')
    }
}

/*
 *  Track the led state changes and update the device tracker object
 *  Only supported for the Inovelli Blue LED driver
 */
@CompileStatic
void deviceStateTracker(Event event) {
    switch (event.value) {
        case 'User Cleared':
            DeviceStateTracker.remove(event.device.id)
            logInfo "clearing all LED tracking for ${event.device}"
            break
        case 'Stop All':
            Map<String, Map> tracker = DeviceStateTracker[event.device.id]
            if (tracker) {
                tracker.remove('All')
                logInfo "cleared LED tracking for ${event.device} All LED"
            }
            break
        case ~/^Stop LED(\d)$/:
            Map<String, Map> tracker = DeviceStateTracker[event.device.id]
            if (tracker) {
                String led = (Matcher.lastMatcher[0] as List)[1]
                tracker.remove(led)
                logInfo "cleared LED tracking for ${event.device} LED${led}"
            }
            break
    }
}

/**
 * If forced refresh is enabled then this is called every specified
 * interval to flush the cache and push updates out. This can be
 * helpful for devices that may not reliably receive commands but
 * should not be used unless required.
 */
void forceRefresh() {
    if (settings.periodicRefresh) {
        logInfo 'executing periodic forced refresh'
        DeviceStateTracker.clear()
        notificationDispatcher()

        int seconds = 60 * (settings.periodicRefreshInterval ?: 60)
        runIn(seconds, 'forceRefresh')
    }
}

/**
 *  Main entry point for updating LED notifications on devices
 */
void notificationDispatcher() {
    Map<String, Boolean> dashboardResults = evaluateDashboardConditions()
    Map<String, Map> ledStates = calculateLedState(dashboardResults)

    if (ledStates) {
        ledStates.values().each { config ->
            updateDeviceLedState(config)
            pauseExecution(200)
        }
    }
}

/*
 *  Dashboard evaluation function responsible for iterating each condition over
 *  the dashboards and returning a map with true/false result for each dashboard prefix.
 *
 *  Includes logic for delayed activiation by scheduling an update job
 */
Map<String, Map> evaluateDashboardConditions() {
    long nextEvaluationTime = 0
    Map<String, Map> evaluationResults = [:]
    // Iterate each dashboard
    for (String prefix in getSortedDashboardPrefixes()) {
        // Evaluate the dashboard conditions
        boolean active = evaluateConditions(prefix)
        // Check if dashboard delay configured
        String delayKey = "${prefix}_delay"
        if (active && settings[delayKey]) {
            int delayMs = (settings[delayKey] ?: 0) * 60000
            // Determine if delay has expired yet
            long targetTime = state.computeIfAbsent(delayKey) { k -> getOffsetMs(delayMs) }
            if (now() < targetTime) {
                logDebug "[evaluateDashboardConditions] ${prefix} has delayed evaluation (${delayMs}ms)"
                active = false
                // calculate when we need to check again
                if (!nextEvaluationTime || nextEvaluationTime > targetTime) {
                    nextEvaluationTime = targetTime
                }
            }
        } else {
            state.remove(delayKey)
        }
        evaluationResults[prefix] = active
    }

    if (nextEvaluationTime) {
        long delay = nextEvaluationTime - now()
        logDebug "[evaluateDashboardConditions] scheduling evaluation in ${delay}ms"
        runInMillis(delay, 'notificationDispatcher')
    }

    return evaluationResults
}

/*
 *  Calculate Notification LED States from condition results
 *  Returns a map of each LED number and the state config associated with it for actioning
 */
Map<String, Map> calculateLedState(Map<String, Boolean> results) {
    Map<String, Map> ledStates = [:]
    for (String prefix in getSortedDashboardPrefixes()) {
        Map<String, String> config = getDashboardConfig(prefix)
        Map<String, Map> oldState = ledStates[config.lednumber as String] ?: [:]
        int oldPriority = oldState.priority as Integer ?: 0
        if (results[prefix]) {
            replaceVariables(config)
            int newPriority = config.priority as Integer ?: 0
            if (newPriority >= oldPriority) {
                ledStates[config.lednumber as String] = config
            }
        } else if (config.autostop != false && !oldPriority) {
            // Auto stop effect
            ledStates[config.lednumber as String] = [
                prefix: config.prefix,
                name: "[auto stop] ${config.name}",
                lednumber: config.lednumber,
                priority: 0,    // lowest priority
                effect: '255',  // stop effect code
                color: '0',     // default color
                level: '100',   // default level
                unit: '255'     // Infinite
            ]
        }
    }
    return ledStates
}

// Scheduled stop for RGB device color
void setDeviceOff() {
    logDebug 'setDeviceOff called'
    for (DeviceWrapper device in settings['switches']) {
        Map<String, Map> tracker = getDeviceTracker(device)
        if (now() >= tracker['All']?.expires) {
            logDebug "${device}.off()"
            device.off()
            DeviceStateTracker.remove(device.id)
        }
    }
}

// Scheduled stop for Inovelli Red Gen1
void setConfigParameter() {
    logDebug 'setConfigParameter called'
    Map deviceType = getDeviceType()
    for (DeviceWrapper device in settings['switches']) {
        Map<String, Map> tracker = getDeviceTracker(device)
        if (now() >= tracker['All']?.expires) {
            logDebug "${device}.setConfigParameter(${deviceType.ledLevelParam},0,'1')"
            device.setConfigParameter(deviceType.ledLevelParam as int, 0, '1')
            DeviceStateTracker.remove(device.id)
        }
    }
}

/*
 *  Private Implementation Helper Methods
 */
private static Map<String, Map> getDeviceTracker(DeviceWrapper dw) {
    return DeviceStateTracker.computeIfAbsent(dw.id) { k -> [:].withDefault { [:] } }
}

// Cleans settings removing entries no longer in use
private void cleanSettings() {
    // Clean unused dashboard settings
    for (String prefix in getDashboardPrefixes()) {
        ConditionsMap.keySet()
            .findAll { key -> !(key in settings["${prefix}_conditions"]) }
            .each { key -> removeSettings("${prefix}_${key}") }

        // Clean unused variable settings
        [ 'lednumber', 'effect', 'color' ].each { var ->
            if (settings["${prefix}_${var}"] != 'var') {
                app.removeSetting("${prefix}_${var}_var")
            }
        }
    }
}

// Returns available priorities based on lednumber
private Map<String, String> getAvailablePriorities(String prefix) {
    String ledNumber = settings["${prefix}_lednumber"]
    if (ledNumber == 'var') {
        Map deviceType = getDeviceType()
        lednumber = lookupVariable(settings["${prefix}_lednumber_var"], deviceType.leds) ?: 'All'
    }
    Set<Integer> usedPriorities = (getDashboardPrefixes() - prefix)
        .findAll { String p -> settings["${p}_lednumber"] as String == ledNumber }
        .collect { String p -> settings["${p}_priority"] as Integer }
    return Priorities.collectEntries { Integer p ->
        String text = p as String
        if (p in usedPriorities) {
            text += ' (In Use)'
        } else if (p == 1) {
            text += ' (Low)'
        } else if (p == Priorities.max()) {
            text += ' (High)'
        }
        return [ p, text ]
    }
}

// Utility method for CSS colored text
private String getColorSpan(Integer hue, String text) {
    if (hue != null && text) {
        String css = (hue == 360) ? 'white' : "hsl(${hue}, 50%, 50%)"
        return "<span style=\'color: ${css}\'>${text}</span>"
    }
    return 'n/a'
}

// Returns key value map of specified dashboard settings
private Map<String, String> getDashboardConfig(String prefix) {
    int startPos = prefix.size() + 1
    return [ 'prefix': prefix ] + settings
        .findAll { s -> s.key.startsWith(prefix + '_') }
        .collectEntries { s -> [ s.key.substring(startPos), s.value ] }
}

private String getSuggestedConditionName(String prefix) {
    Map config = getDashboardConfig(prefix)
    Map deviceType = getDeviceType()
    Map<String, String> fxOptions = config.lednumber == 'All' ? deviceType.effectsAll : deviceType.effects
    StringBuilder sb = new StringBuilder('Set ')

    if (config.lednumber && config.lednumber != 'var') {
        sb << deviceType.leds[config.lednumber] ?: 'n/a'
    } else {
        sb << 'LED'
    }
    if (config.color && config.color != 'var' && config.color != 'val') {
        sb << ' to '
        if (fxOptions[config.effect]) {
            sb << "${fxOptions[config.effect]} "
        }
        sb << ColorMap[config.color]
    }

    List<String> conditions = config.conditions
        .findAll { c -> ConditionsMap.containsKey(c) }
        .collect { c -> ConditionsMap[c].title + (config["${c}_all"] ? ' <i>(All)</i>' : '') }
    if (conditions) {
        sb << " when ${conditions[0]}"
    }

    if (config.delay) {
        sb << " for ${config.delay}"
    }

    return sb.toString()
}

// Creates a description string for the dashboard configuration for display
private String getDashboardDescription(String prefix) {
    Map config = getDashboardConfig(prefix)
    Map deviceType = getDeviceType()
    StringBuilder sb = new StringBuilder()
    if (config.lednumber == 'var') {
        sb << "<b>LED Variable:</b> <i>${config.lednumber_var}</i>"
    } else if (config.lednumber) {
        sb << "<b>${deviceType.leds[config.lednumber] ?: 'n/a'}</b>"
    }
    sb << ", <b>Priority</b>: ${config.priority}"

    if (config.effect == 'var') {
        sb << ", <b>Effect Variable</b>: <i>${config.effect_var}</i>"
    } else if (config.effect) {
        Map<String, String> fxOptions = deviceType.effectsAll + deviceType.effects
        sb << ", <b>Effect:</b> ${fxOptions[config.effect] ?: 'n/a' }"
    }

    if (config.color == 'var') {
        sb << ", <b>Color Variable:</b> <i>${config.color_var}</i>"
    } else if (config.color == 'val') {
        sb << ', <b>Color Hue</b>: ' + getColorSpan(config.color_val as Integer, "#${config.color_val}")
    } else if (config.color) {
        sb << ', <b>Color</b>: ' + getColorSpan(config.color as Integer, ColorMap[config.color])
    }

    if (config.level == 'var') {
        sb << ", <b>Level Variable:</b> <i>${config.level_var}</i>"
    } else if (config.level == 'val') {
        sb << ", <b>Level:</b> ${config.level_val}%"
    } else if (config.level) {
        sb << ", <b>Level:</b> ${config.level}%"
    }

    if (config.duration && config.unit) {
        sb << ", <b>Duration:</b> ${config.duration} ${TimePeriodsMap[config.unit]?.toLowerCase()}"
    }

    if (config.conditions) {
        String allMode = config.conditions_all ? ' and ' : ' or '
        List<String> conditions = config.conditions
            .findAll { c -> ConditionsMap.containsKey(c) }
            .collect { c ->
                String title = ConditionsMap[c].title
                String choice = config["${c}_choice"]
                String all = config["${c}_all"] ? '<i>(All)</i>' : null
                return ([ title, choice, all ] - null).join(' ')
            }
        sb << "\n<b>Activation${conditions.size() > 1 ? 's' : ''}:</b> ${conditions.join(allMode)}"
        if (config.autostop != false) {
            sb << ' (auto stop)'
        }
    }

    if (config.delay as Integer) {
        sb << " for ${config.delay} minute"
        if (config.delay > 1) {
            sb << 's'
        }
    }
    return sb.toString()
}

// Returns a set of dashboard prefixes
private Set<String> getDashboardPrefixes() {
    return settings.keySet().findAll { s ->
        s.matches('^condition_[0-9]+_priority$')
    }.collect { s -> s - '_priority' }
}

// Returns dashboard setting prefix sorted by priority (descending) then name (ascending)
private List<String> getSortedDashboardPrefixes() {
    return getDashboardPrefixes().collect { String prefix ->
        [
            prefix: prefix,
            name: settings["${prefix}_name"] as String,
            priority: settings["${prefix}_priority"] as Integer
        ]
    }.sort { a, b -> a.priority <=> b.priority ?: a.name <=> b.name }*.prefix
}

// Returns the active device type configuration map
private Map getDeviceType() {
    Map deviceTypeMap = DeviceTypeMap.get(settings['deviceType']) ?: [:]
    if (!deviceTypeMap) {
        logError "Unable to retrieve device type map for ${settings['deviceType']}"
    }
    return deviceTypeMap
}

// Calculate milliseconds from Inovelli duration parameter (0-255)
// 1-60=seconds, 61-120=1-60 minutes, 121-254=1-134 hours, 255=Indefinitely
@CompileStatic
private long getDurationMs(Integer duration) {
    if (!duration) {
        return 0
    }
    if (duration <= 60) { // seconds (1-60)
        return duration * 1000
    }
    if (duration <= 120) { // minutes (61-120)
        return (duration - 60) * 60000
    }
    if (duration <= 254) { // hours (121-254)
        return (duration - 120) * 3600000
    }
    return 86400000 // indefinite (using 24 hours)
}

// Returns next condition settings prefix
@CompileStatic
private String getNextPrefix() {
    List<Integer> keys = getDashboardPrefixes().collect { p -> p.substring(10) as Integer }
    int maxId = keys ? Collections.max(keys) : 0
    return "condition_${maxId + 1}"
}

// Returns current time plus specified offset
private long getOffsetMs(long offset = 0) {
    return now() + offset
}

// Logs information
private void logDebug(String s) {
    if (logEnable) {
        log.debug s
    }
}

// Logs Error
private void logError(String s) {
    log.error app.label + ' ' + s
}

// Logs information
private void logWarn(String s) {
    log.warn app.label + ' ' + s
}

// Logs information
private void logInfo(String s) {
    log.info app.label + ' ' + s
}

// Looks up a variable in the given lookup table and returns the key if found
private String lookupVariable(String variableName, Map<String, String> lookupTable) {
    String globalVar = getGlobalVar(variableName)?.value
    String value = globalVar?.toLowerCase()
    return lookupTable.find { k, v -> v.toLowerCase() == value }?.key
}

// Populate configuration values with specified global variables
private void replaceVariables(Map<String, String> config) {
    Map deviceType = getDeviceType()
    if (deviceType) {
        if (config.lednumber == 'var') {
            config.lednumber = lookupVariable(config.lednumber_var, deviceType.leds) ?: 'All'
        }
        if (config.effect == 'var') {
            Map<String, String> fxOptions = deviceType.effectsAll + deviceType.effects
            config.effect = lookupVariable(config.effect_var, fxOptions) as String
        }
        if (config.color == 'var') {
            config.color = lookupVariable(config.color_var, ColorMap) as String
        }
        if (config.color == 'val') {
            config.color = config.color_val as String
        }
        if (config.level == 'var') {
            config.level = getGlobalVar(config.level_var)?.value as String
        }
        if (config.level == 'val') {
            config.level = config.level_val as String
        }
    }
}

// Set all switches to stop
private void resetNotifications() {
    Map deviceType = getDeviceType()
    if (deviceType) {
        logInfo 'resetting all device notifications'
        deviceType.leds.keySet().findAll { s -> s != 'var' }.each { String led ->
            updateDeviceLedState(
                [
                    name: 'clear notification',
                    priority: 0,
                    lednumber: led,
                    effect: '255',    // stop effect code
                    color: '0',       // default color
                    level: '100',     // default level
                    unit: '255'       // infinite
                ]
            )
        }
    }
}

// Removes all condition settings starting with prefix
private void removeSettings(String prefix) {
    Set<String> entries = settings.keySet().findAll { s -> s.startsWith(prefix) }
    entries.each { s -> app.removeSetting(s) }
}

// Subscribe to all dashboard conditions
@CompileStatic
private void subscribeAllConditions() {
    for (String prefix in getDashboardPrefixes()) {
        subscribeCondition(prefix)
    }
}

// Subscribe to switches with driver support
private void subscribeAllSwitches() {
    String type = getDeviceType().type
    switch (type) {
        case ~/^device.InovelliDimmer2-in-1BlueSeries.*/:
            logDebug "subscribing to ledEffect event for ${settings.switches}"
            subscribe(settings.switches, 'ledEffect', 'deviceStateTracker', null)
            break
    }
}

/**
 *  updateDeviceLedState is a wrapper around driver specific commands for setting specific LED notifications
 */
private void updateDeviceLedState(Map config) {
    for (DeviceWrapper device in settings['switches']) {
        logDebug "setting ${device} LED #${config.lednumber} (" +
            "id=${config.prefix}, name=${config.name}, priority=${config.priority}, " +
            "effect=${config.effect ?: ''}, color=${config.color}, level=${config.level}, " +
            "duration=${config.duration ?: ''} ${TimePeriodsMap[config.unit] ?: ''})"

        Map<String, Map> tracker = getDeviceTracker(device)
        String key = config.lednumber
        if (tracker[key] == null
            || tracker[key].effect != config.effect
            || tracker[key].color != config.color
            || tracker[key].level != config.level
            || tracker[key].unit != config.unit
            || tracker[key].duration != config.duration
            || tracker[key]?.expires <= now()
        ) {
            if (device.hasCommand('ledEffectOne')) {
                updateDeviceLedStateInovelliBlue(device, config)
            } else if (device.hasCommand('startNotification')) {
                updateDeviceLedStateInovelliRedGen2(device, config)
            } else if (device.hasCommand('setConfigParameter')) {
                updateDeviceLedStateInovelliRedGen1(device, config)
            } else if (device.hasCommand('setColor')) {
                updateDeviceColor(device, config)
            } else {
                logWarn "unable to determine notification command for ${device}"
                continue
            }
            long duration = getDurationMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
            config.expires = getOffsetMs(duration)
            tracker[key] = config
        } else {
            logDebug "skipping update to ${device} (no change detected)"
        }
    }
}

/**
 *  updateDeviceLedStateInovelliBlue is a wrapper around the Inovelli device ledEffect driver methods
 *  The wrapper uses the trackingState to reduce the Zigbee traffic by checking the
 *  assumed LED state before sending changes.
 */
private void updateDeviceLedStateInovelliBlue(DeviceWrapper dw, Map config) {
    int color, duration, effect, level
    if (config.unit != null) {
        duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255)
    }
    if (config.color != null) {
        color = Math.min(Math.round(((config.color as Integer) / 360.0) * 255), 255)
    }
    if (config.effect != null) {
        effect = config.effect as int
    }
    if (config.level != null) {
        level = config.level as int
    }
    if (config.lednumber == 'All') {
        logDebug "${dw}.ledEffectALL(${effect},${color},${level},${duration})"
        dw.ledEffectAll(effect, color, level, duration)
    } else {
        logDebug "${dw}.ledEffectONE(${config.lednumber},${effect},${color},${level},${duration})"
        dw.ledEffectOne(config.lednumber, effect, color, level, duration)
    }
}

private void updateDeviceLedStateInovelliRedGen1(DeviceWrapper dw, Map config) {
    int color, effect, level
    if (config.unit != null) {
        duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255) as int
    }
    if (config.color != null) {
        color = Math.min(Math.round(((config.color as Integer) / 360.0) * 255), 255) as int
        color = color <= 2 ? 0 : (color >= 98 ? 254 : color / 100 * 255)
    }
    if (config.level != null) {
        level = Math.round((config.level as int) / 10) as int
    }
    if (config.effect != null) {
        effect = config.effect as int
    }
    if (effect == 255) { // Stop notification option is mapped to 0
        effect = level = color = 0
    }
    Map deviceType = getDeviceType()
    logDebug "${dw}.setConfigParameter(${deviceType.ledLevelParam},${level},'1')"
    dw.setConfigParameter(deviceType.ledLevelParam as int, level, '1')
    if (level > 0) {
        logDebug "${dw}.setConfigParameter(${deviceType.ledColorParam},${color},'2')"
        dw.setConfigParameter(deviceType.ledColorParam as int, color, '2')
    }
    if (config.unit && config.unit != '255') {
        long duration = getDurationMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
        logDebug 'scheduling setConfigParameter for ' + duration + 'ms'
        runInMillis(duration + 1000, 'setConfigParameter')
    } else {
        logDebug 'unschedule setConfigParameter'
        unschedule('setConfigParameter')
    }
}

/**
 *  updateDeviceLedStateInovelliRedGen2 is a wrapper around the
 *  Inovelli device driver startnotification method.
 *  Reference https://nathanfiscus.github.io/inovelli-notification-calc/
 */
private void updateDeviceLedStateInovelliRedGen2(DeviceWrapper dw, Map config) {
    int color, duration, effect, level
    if (config.unit != null) {
        duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255) as int
    }
    if (config.color != null) {
        color = Math.min(Math.round(((config.color as Integer) / 360.0) * 255), 255) as int
    }
    if (config.level != null) {
        level = Math.round((config.level as int) / 10) as int
    }
    if (config.effect != null) {
        effect = config.effect as int
    }
    if (effect == 255) { // Stop notification option is mapped to 0
        effect = duration = level = color = 0
    }
    byte[] bytes = [effect as byte, duration as byte, level  as byte, color as byte]
    int value = new BigInteger(bytes).intValue()
    logDebug "${dw}.startNotification(${value}) [${bytes[0] & 0xff}, ${bytes[1] & 0xff}, ${bytes[2] & 0xff}, ${bytes[3] & 0xff}]"
    if (config.lednumber == 'All') {
        dw.startNotification(value)
    } else {
        dw.startNotification(value, config.lednumber as Integer)
    }
}

/**
 *  updateDeviceColor is a wrapper around the color device driver methods
 */
private void updateDeviceColor(DeviceWrapper dw, Map config) {
    Integer color = config.color as Integer
    switch (config.effect) {
        case '0': // Off
        case '255':
            logDebug "${dw}.off()"
            dw.off()
            break
        case '1': // On
            int huePercent = Math.round((color / 360.0) * 100)
            logDebug "${dw}.setColor(${huePercent})"
            dw.setColor([
                hue: color == 360 ? 0 : huePercent,
                saturation: color == 360 ? 0 : 100,
                level: config.level as Integer
            ])
            break
    }
    if (config.unit && config.unit != '255') {
        long duration = getDurationMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
        logDebug 'scheduling setDeviceOff in ' + duration + 'ms'
        runInMillis(duration + 1000, 'setDeviceOff')
    } else {
        logDebug 'unschedule setDeviceOff'
        unschedule('setDeviceOff')
    }
}

// Updates the app label based on pause state
private void updatePauseLabel() {
    if (state.paused && !app.label?.endsWith(pauseText)) {
        app.updateLabel(app.label + pauseText)
    } else if (app.label?.endsWith(pauseText)) {
        app.updateLabel(app.label - pauseText)
    }
}

/*
 *  Conditions Rules Engine
 *  This section of code provides the ability for the application to present and react to defined
 *  conditions and events including device attributes, hub variables and location events (HSM)
 */

// TODO: Consider explicit event vs. condition (e.g. door has opened in last x seconds vs. door is open)
@Field static final Map<String, Map> ConditionsMap = [
    'buttonPress': [
        name: 'Button Push',
        title: 'Button is pushed',
        inputs: [
            device: [
                type: 'capability.pushableButton',
                multiple: true,
                any: true
            ],
            choice: [
                title: 'Select Button Number(s)',
                options: { ctx -> getButtonNumberChoices(ctx.device) },
                multiple: true
            ]
        ],
        subscribe: 'pushed',
        test: { ctx -> ctx.choice && ctx.event.value in ctx.choice }
    ],
    'contactClose': [
        name: 'Contact sensor',
        title: 'Contact sensor is closed',
        inputs: [
            device: [
                type: 'capability.contactSensor',
                multiple: true
            ]
        ],
        subscribe: 'contact',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'contact', 'closed', ctx.all) }
    ],
    'contactOpen': [
        name: 'Contact sensor',
        title: 'Contact sensor is open',
        inputs: [
            device: [
                type: 'capability.contactSensor',
                multiple: true
            ]
        ],
        subscribe: 'contact',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'contact', 'open', ctx.all) }
    ],
    'customAttribute': [
        name: 'Custom attribute',
        title: 'Custom attribute is set to',
        inputs: [
            device: [
                type: 'capability.*',
                multiple: true
            ],
            choice: [
                title: 'Select Attribute',
                options: { ctx -> ctx.device ? getAttributeChoices(ctx.device) : null },
                multiple: false
            ],
            // comparison: [
            //     options: { ctx -> ctx.device && ctx.choice ? getAttributeComparisons(ctx.device, ctx.choice) : null }
            // ],
            value: [
                title: 'Enter Value',
                options: { ctx -> ctx.device && ctx.choice ? getAttributeOptions(ctx.device, ctx.choice) : null }
            ]
        ],
        subscribe: { ctx -> ctx.choice },
        test: { ctx -> deviceAttributeHasValue(ctx.device, ctx.choice, ctx.value, ctx.all) }
    ],
    'hsmAlert': [
        name: 'HSM Alert',
        title: 'HSM intrusion alert changes to',
        inputs: [
            choice: [
                options: [ 'intrusion': 'Intrusion Away', 'intrusion-home': 'Intrusion Home', 'smoke': 'Smoke', 'water': 'Water', 'arming': 'Arming fail', 'cancel': 'Alert cancelled' ],
                multiple: true
            ]
        ],
        subscribe: 'hsmAlert',
        test: { ctx -> ctx.event.value in ctx.choice }
    ],
    'hsmStatus': [
        name: 'HSM Status',
        title: 'HSM arming status is set',
        inputs: [
            choice: [
                options: [
                    'armedAway': 'Armed Away',
                    'armingAway': 'Arming Away',
                    'armedHome': 'Armed Home',
                    'armingHome': 'Arming Home',
                    'armedNight': 'Armed Night',
                    'armingNight': 'Arming Night',
                    'disarmed': 'Disarmed',
                    'allDisarmed': 'All Disarmed'
                ],
                multiple: true
            ]
        ],
        subscribe: 'hsmStatus',
        test: { ctx -> location.hsmStatus in ctx.choice }
    ],
    'hubMode': [
        name: 'Hub Mode',
        title: 'Hub mode is active',
        inputs: [
            choice: [
                options: { ctx -> location.modes.collectEntries { m -> [ m.id as String, m.name ] } },
                multiple: true
            ],
        ],
        subscribe: 'mode',
        test: { ctx -> (location.currentMode.id as String) in ctx.choice }
    ],
    'locked': [
        name: 'Lock',
        title: 'Lock is locked',
        inputs: [
            device: [
                type: 'capability.lock',
                multiple: true
            ]
        ],
        subscribe: 'lock',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'lock', 'locked', ctx.all) }
    ],
    'motionActive': [
        name: 'Motion sensor',
        title: 'Motion sensor is active',
        inputs: [
            device: [
                type: 'capability.motionSensor',
                multiple: true
            ]
        ],
        subscribe: 'motion',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'motion', 'active', ctx.all) }
    ],
    'motionInactive': [
        name: 'Motion sensor',
        title: 'Motion sensor is inactive',
        inputs: [
            device: [
                type: 'capability.motionSensor',
                multiple: true
            ]
        ],
        subscribe: 'motion',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'motion', 'inactive', ctx.all) }
    ],
    'notpresent': [
        name: 'Presence sensor',
        title: 'Presence sensor not present',
        inputs: [
            device: [
                type: 'capability.presenceSensor',
                multiple: true
            ]
        ],
        subscribe: 'presence',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'presence', 'not present', ctx.all) }
    ],
    'present': [
        name: 'Presence sensor',
        title: 'Presence sensor is present',
        inputs: [
            device: [
                type: 'capability.presenceSensor',
                multiple: true
            ]
        ],
        subscribe: 'presence',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'presence', 'present', ctx.all) }
    ],
    'smoke': [
        name: 'Smoke detector',
        title: 'Smoke detected',
        inputs: [
            device: [
                type: 'capability.smokeDetector',
                multiple: true
            ]
        ],
        subscribe: 'smoke',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'smoke', 'detected', ctx.all) }
    ],
    'switchOff': [
        name: 'Switch',
        title: 'Switch is off',
        attribute: 'switch',
        value: 'off',
        inputs: [
            device: [
                type: 'capability.switch',
                multiple: true
            ]
        ],
        subscribe: 'switch',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'switch', 'off', ctx.all) }
    ],
    'switchOn': [
        name: 'Switch',
        title: 'Switch is on',
        attribute: 'switch',
        value: 'on',
        inputs: [
            device: [
                type: 'capability.switch',
                multiple: true
            ]
        ],
        subscribe: 'switch',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'switch', 'on', ctx.all) }
    ],
    'unlocked': [
        name: 'Lock',
        title: 'Lock is unlocked',
        attribute: 'lock',
        value: 'unlocked',
        inputs: [
            device: [
                type: 'capability.lock',
                multiple: true
            ]
        ],
        subscribe: 'lock',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'lock', 'unlocked', ctx.all) }
    ],
    'variable': [
        name: 'Hub variable',
        title: 'Variable is set',
        inputs: [
            choice: [
                options: { ctx -> getAllGlobalVars().keySet() }
            ],
            comparison: [
                options: { ctx -> getComparisonsByType(getGlobalVar(ctx.choice)?.type) }
            ],
            value: [
                title: 'Variable Value',
                options: { ctx -> getGlobalVar(ctx.choice)?.type == 'boolean' ? [ 'true': 'True', 'false': 'False' ] : null }
            ]
        ],
        subscribe: { ctx -> "variable:${ctx.choice}" },
        test: { ctx -> evaluateComparison(ctx.event.value, ctx.value, ctx.comparison) }
    ],
    'waterDry': [
        name: 'Water sensor',
        title: 'Water sensor is dry',
        inputs: [
            device: [
                type: 'capability.waterSensor',
                multiple: true
            ]
        ],
        subscribe: 'water',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'water', 'dry', ctx.all) }
    ],
    'waterWet': [
        name: 'Water sensor',
        title: 'Water sensor is wet',
        inputs: [
            device: [
                type: 'capability.waterSensor',
                multiple: true
            ]
        ],
        subscribe: 'water',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'water', 'wet', ctx.all) }
    ],
].asImmutable()

/**
 *  Evaluates all conditions and returns a pass/fail (boolean)
 *
 *  @param prefix           The settings prefix to use (e.g. conditions_1) for persistence
 *  @param ruleDefinitions  The rule definitions to use (defaults to global ConditionsMap)
 */
private boolean evaluateConditions(String prefix, Map<String, Map> ruleDefinitions = ConditionsMap) {
    boolean result = false
    boolean allConditionsFlag = settings["${prefix}_conditions_all"] ?: false
    String name = settings["${prefix}_name"]

    // Loop through all conditions updating the result
    List<String> selectedConditions = settings["${prefix}_conditions"] ?: []
    for (String conditionKey in selectedConditions) {
        Map<String, Map> condition = ruleDefinitions[conditionKey]
        if (condition) {
            boolean testResult = evaluateCondition("${prefix}_${conditionKey}", condition)
            // If all conditions is selected and the test failed, stop and return false
            if (allConditionsFlag && !testResult) {
                result = false
                break
            // If any conditions is selected and the test passed, stop and return true
            } else if (!allConditionsFlag && testResult) {
                result = true
                break
            }
            // Otherwise update the result and try the next condition
            result |= testResult
        }
    }
    logDebug "[evaluateConditions] ${name} (${prefix}) returns ${result}"
    return result
}

/**
 *  Evaluates the specified condition configuration and returns a pass/fail (boolean)
 *
 *  @param prefix       The settings prefix to use (e.g. conditions_1) for persistence
 *  @param condition    The condition to evaluate
 */
private boolean evaluateCondition(String prefix, Map condition) {
    String attribute
    if (!condition) { return false }
    if (condition.subscribe in Closure) {
        Map ctx = [
            device: settings["${prefix}_device"],
            choice: settings["${prefix}_choice"],
            value: settings["${prefix}_value"]
        ].asImmutable()
        attribute = runClosure(condition.subscribe as Closure, ctx)
    } else {
        attribute = condition.subscribe
    }
    Map ctx = [
        all: settings["${prefix}_device_all"],
        attribute: attribute,
        choice: settings["${prefix}_choice"],
        comparison: settings["${prefix}_comparison"],
        device: settings["${prefix}_device"],
        event: state.lastEvent ?: [:],
        value: settings["${prefix}_value"]
    ].asImmutable()
    boolean result = runClosure(condition.test as Closure, ctx) ?: false
    logDebug "[evaluateCondition] ${condition.title} (${prefix}) is ${result}"
    return result
}

// Subscribe to a condition (devices, location, variable etc.)
private void subscribeCondition(String prefix, Map<String, Map> ruleDefinitions = ConditionsMap) {
    String name = settings["${prefix}_name"] ?: prefix
    List<String> selectedConditions = settings["${prefix}_conditions"] ?: []
    for (String conditionKey in selectedConditions) {
        Map<String, Map> condition = ruleDefinitions[conditionKey]
        if (condition.subscribe) {
            String id = "${prefix}_${conditionKey}"
            Map ctx = [
                device: settings["${id}_device"],
                choice: settings["${id}_choice"],
                value: settings["${id}_value"]
            ].asImmutable()
            String attribute
            if (condition.subscribe in Closure) {
                attribute = runClosure(condition.subscribe as Closure, ctx)
            } else {
                attribute = condition.subscribe
            }
            logInfo "${name} [${condition.name}] subscribing to ${ctx.device ?: 'location'} for '${attribute}'"
            subscribe(ctx.device ?: location, attribute, 'eventHandler', null)
            if (attribute.startsWith('variable:')) {
                addInUseGlobalVar(attribute.substring(9))
            }
        }
    }
}

private void subscribeVariables() {
    settings.findAll { s -> s.key.endsWith('_var') }.each { s ->
        logDebug "subscribing to variable ${s.value}"
        addInUseGlobalVar(s.value)
        subscribe(location, 'variable:' + s.value, 'eventHandler', null)
    }
}

// Given a set of devices, returns if the attribute has the specified value (any or all as specified)
// TODO: Support comparisons
@CompileStatic
private boolean deviceAttributeHasValue(List<DeviceWrapper> devices, String attribute, String value, Boolean all) {
    Closure test = { DeviceWrapper d -> d.currentValue(attribute) as String == value }
    return all ? devices?.every(test) : devices?.any(test)
}

// Given two strings return true if satisfied by the operator
@CompileStatic
private boolean evaluateComparison(String a, String b, String operator) {
    switch (operator) {
        case '=': return a.equalsIgnoreCase(b)
        case '!=': return !a.equalsIgnoreCase(b)
        case '<>': return !a.equalsIgnoreCase(b)
        case '>': return new BigDecimal(a) > new BigDecimal(b)
        case '>=': return new BigDecimal(a) >= new BigDecimal(b)
        case '<': return new BigDecimal(a) < new BigDecimal(b)
        case '<=': return new BigDecimal(a) <= new BigDecimal(b)
    }
    return false
}

// Given a set of devices, provides the distinct set of attribute names
@CompileStatic
private List<String> getAttributeChoices(List<DeviceWrapper> devices) {
    return devices?.collectMany { d -> d.getSupportedAttributes()*.name }.unique()
}

// Given a set of devices, provides the distinct set of attribute names
@CompileStatic
private List<String> getAttributeOptions(List<DeviceWrapper> devices, String attribute) {
    return devices?.collectMany { d -> d.getSupportedAttributes().find { a -> a.name == attribute }.getValues() }.unique()
}

// Given a set of button devices, provides the list of buttons to choose from
@CompileStatic
private Map<String, String> getButtonNumberChoices(List<DeviceWrapper> buttonDevices) {
    Integer max = buttonDevices?.collect { DeviceWrapper d -> d.currentValue('numberOfButtons') as Integer ?: 0 }?.max()
    if (max) {
        return (1..max).collectEntries { int n -> [ n as String, "Button ${n}" ] }
    }
    return Collections.emptyMap()
}

// Given the Hubitat type, determine what comparisons are valid choices to present
@CompileStatic
private Map<String, String> getComparisonsByType(String type) {
    Map<String, String> result = [ '=': 'Equals to', '<>': 'Not equals to' ]
    if (type.toLowerCase() in [ 'number', 'integer', 'bigdecimal' ]) {
        result += [
            '<': 'Less than',
            '<=': 'Less or equals',
            '>': 'Greater than',
            '>=': 'Greater or equals'
        ]
    }
    return result
}

// Internal method to call closure (with this as delegate) passing the context parameter
@CompileStatic
private Object runClosure(Closure c, Map ctx) {
    try {
        Closure closure = c.clone() as Closure
        closure.delegate = this
        return closure.call(ctx)
    } catch (e) {
        logWarn "runClosure (${ctx}): ${e}"
    }
    return null
}
