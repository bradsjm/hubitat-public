/**
 *  MIT License
 *  Copyright 2023 Jonathan Bradshaw (jb@nrgup.net)
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
 *  0.4  - Add 'auto stop' effect option to clear effect
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
 *  1.00 - Add support for illuminance value comparison conditions
 *  1.01 - Bug fix for sunrise/sunset
 *  1.02 - Replaced sunrise/sunset conditions with new single option
 *  1.03 - Add cool down period support for condition
 *  1.04 - Duplicating dashboard will includes devices, add top/middle/bottom led selection options
 *  1.05 - Modified button selection to be more resilient
*/

@Field static final String Version = '1.05'

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
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher

// Tracker for switch LED state to optimize traffic by only sending changes
@Field static final Map<String, Map> SwitchStateTracker = new ConcurrentHashMap<>()

// Invoked when the app is first created.
void installed() {
    logInfo 'mini-dashboard child created'
}

// Invoked by the hub when a global variable is renamed
@CompileStatic
void renameVariable(String oldName, String newName) {
    getAppSettings().findAll { Map.Entry<String, Object> s -> s.value == oldName }.each { Map.Entry<String, Object> s ->
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
    SwitchStateTracker.clear()

    // Clean out unused settings
    cleanSettings()

    // Remove any scheduled tasks
    unschedule()

    // Unsubscribe from any events
    unsubscribe()

    // Remove global variable registrations
    removeAllInUseGlobalVar()

    // If paused then reset notifications
    if (state.paused) {
        runIn(1, 'resetNotifications')
        return
    }

    // Clear state (used mostly for tracking delayed conditions)
    // Reminder - Do not put this above the paused state check!
    state.clear()

    // Build list of prioritized dashboards
    state.sortedPrefixes = getSortedScenarioPrefixes()

    // Subscribe to events from supported Inovelli switches
    subscribeSwitchAttributes()

    // Subscribe to events from all conditions
    subscribeAllScenarios()

    // Subscribe to global variables from all conditions
    subscribeAllVariables()

    // Dispatch the current notifications
    runIn(1, 'notificationDispatcher')

    if (settings.periodicRefresh) {
        logInfo "enabling periodic forced refresh every ${settings.periodicRefreshInterval} minutes"
        int seconds = 60 * (settings.periodicRefreshInterval as Integer ?: 60)
        runIn(seconds, 'forceRefresh')
    }
}

/*
 * Used by parent to create duplicate dashboards
 */

Map<String, Map> readSettings() {
    return (Map<String, Map>) settings.collectEntries { k, v -> [k, [type: getSettingType(k), value: v]] }
}

void writeSettings(Map<String, Map> newSettings) { //TODO: Change method name
    newSettings.each { k, v -> app.updateSetting(k, [type: v.type, value: v.value]) }
    state.paused = true
    updatePauseLabel()
}

/********************************************************************
 * START OF USER INTERFACE SECTION
 */
Map mainPage() {
    if (app.label == null) {
        app.updateLabel('New LED Mini-Dashboard')
    }
    updatePauseLabel()

    return dynamicPage(name: 'mainPage', title: "<h2 style=\'color: #1A77C9; font-weight: bold\'>${app.label}</h2>") {
        Map switchType = settings['deviceType'] ? getTargetSwitchType() : null
        section {
            input name: 'deviceType', title: '', type: 'enum',
                description: '<b>Select the target device type</b> <i>(one type per mini-dashboard)</i>',
                options: SupportedSwitchTypes.collectEntries { String key, Map value -> [key, value.title] },
                multiple: false, required: true, submitOnChange: true, width: 10

            if (state.paused) {
                input name: 'resume', title: 'Resume', type: 'button', width: 1
            } else {
                input name: 'pause', title: 'Pause', type: 'button', width: 1
            }

            if (switchType) {
                input name: 'switches',
                    title: "Select ${settings['deviceType']} devices to include in mini-dashboard topic",
                    type: switchType.type,
                    required: true, multiple: true, submitOnChange: true, width: 10
            }
        }

        if (switchType && settings['switches']) {
            Set<String> prefixes = getSortedScenarioPrefixes()
            section("<h3 style=\'color: #1A77C9; font-weight: bold\'>${app.label} Notification Scenarios</h3>") {
                for (String scenarioPrefix in prefixes) {
                    String name = settings["${scenarioPrefix}_name"]
                    boolean isActive = evaluateActivationRules(scenarioPrefix)
                    String status = isActive ? ' &#128994;' : ''
                    Long delayUntil = state["${scenarioPrefix}_delay"] as Long
                    Long cooldownUntil = state["${scenarioPrefix}_cooldown"] as Long
                    long now = getTimeMs()
                    if (isActive && delayUntil > now) {
                        int minutes = (int) Math.ceil((delayUntil - now) / 60000)
                        status = " &#128993; <span style=\'font-style: italic\'>(< ${minutes}m delay)</span>"
                    } else if (!isActive && cooldownUntil > now) {
                        isActive = true
                        int minutes = (int) Math.ceil((cooldownUntil - now) / 60000)
                        status = " &#128993; <span style=\'font-style: italic\'>(< ${minutes}m cooldown)</span>"
                    }
                    href(
                        name: "edit_${scenarioPrefix}",
                        title: "<b>${name}</b>${status}",
                        description: getScenarioDescription(scenarioPrefix),
                        page: 'editPage',
                        params: [prefix: scenarioPrefix],
                        state: isActive ? 'complete' : '',
                        width: 10
                    )
                    input name: 'remove_' + scenarioPrefix,
                        title: '<i style="font-size:1rem; color:red;" class="material-icons he-bin"></i>',
                        type: 'button',
                        width: 1
                }

                href(
                    name: 'addDashboard',
                    title: '<i>Select to add a new Notification Scenario</i>',
                    description: '',
                    params: [prefix: findNextPrefix()],
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

Map editPage(Map params = [:]) {
    if (!params?.prefix) {
        return mainPage()
    }
    String scenarioPrefix = params.prefix
    String name = settings["${scenarioPrefix}_name"] ?: 'New Notification Scenario'

    return dynamicPage(name: 'editPage', title: "<h3 style=\'color: #1A77C9; font-weight: bold\'>${name}</h3><br>") {
        renderIndicationSection(scenarioPrefix)
        String effectName = getEffectName(scenarioPrefix)
        if (effectName) {
            renderScenariosSection(scenarioPrefix, "<span style=\'color: green; font-weight: bold\'>Select rules to activate notification LED ${effectName} effect:</span><span class=\"required-indicator\">*</span>")

            if (settings["${scenarioPrefix}_conditions"]) {
                section {
                    input name: "${scenarioPrefix}_delay", title: '<i>For at least (minutes):</i>', description: '1..60', type: 'decimal', width: 3, range: '0..60', required: false
                    if (settings["${scenarioPrefix}_effect"] != '255') {
                        String title = 'When rules stop matching '
                        title += settings["${scenarioPrefix}_autostop"] == false ? '<i>leave effect running</i>' : '<b>stop the effect</b>'
                        input name: "${scenarioPrefix}_autostop", title: title, type: 'bool', defaultValue: true, width: 3, submitOnChange: true
                    } else {
                        app.removeSetting("${scenarioPrefix}_autostop")
                    }
                    if (settings["${scenarioPrefix}_autostop"]) {
                        input name: "${scenarioPrefix}_cooldown", title: '<i>Cooldown period (minutes):</i>', description: '1..60', type: 'decimal', width: 3, range: '0..60', required: false
                    } else {
                        app.removeSetting("${scenarioPrefix}_cooldown")
                    }
                }

                section {
                    input name: "${scenarioPrefix}_name", title: '<b>Notification Scenario Name:</b>', type: 'text', defaultValue: getSuggestedScenarioName(scenarioPrefix), width: 7, required: true, submitOnChange: true
                    input name: "${scenarioPrefix}_priority", title: '<b>Priority:</b>', type: 'enum', options: getPrioritiesList(scenarioPrefix), width: 2, required: true
                    paragraph '<i>Higher value scenario priorities take LED precedence.</i>'
                    input name: "test_${scenarioPrefix}", title: '&#9658; Test Effect', type: 'button', width: 2
                    input name: 'reset', title: '<b>&#9724;</b> Stop Test', type: 'button', width: 2
                }
            }
        }
    }
}

Map renderIndicationSection(String scenarioPrefix, String title = null) {
    Map switchType = getTargetSwitchType()
    String ledNumber = settings["${scenarioPrefix}_lednumber"]
    String ledName = switchType.leds[settings[ledNumber]] ?: 'LED'

    return section(title) {
        // LED Number
        input name: "${scenarioPrefix}_lednumber", title: '<span style=\'color: blue;\'>LED Number</span>', type: 'enum', options: switchType.leds, width: 3, required: true, submitOnChange: true
        if (settings["${scenarioPrefix}_lednumber"] == 'var') {
            input name: "${scenarioPrefix}_lednumber_var", title: "<span style=\'color: blue;\'>LED Number Variable</span>", type: 'enum', options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
        } else {
            app.removeSetting("${scenarioPrefix}_lednumber_var")
        }

        // Effect
        if (ledNumber) {
            Map<String, String> fxOptions = ledNumber == 'All' ? switchType.effectsAll : switchType.effects as Map<String, String>
            input name: "${scenarioPrefix}_effect", title: "<span style=\'color: blue;\'>${ledName} Effect</span>", type: 'enum',
                options: fxOptions, width: 2, required: true, submitOnChange: true
            if (settings["${scenarioPrefix}_effect"] == 'var') {
                input name: "${scenarioPrefix}_effect_var", title: "<span style=\'color: blue;\'>Effect Variable</span>", type: 'enum',
                    options: getGlobalVarsByType('string').keySet(), width: 3, required: true
            } else {
                app.removeSetting("${scenarioPrefix}_effect_var")
            }

            // Color
            String effect = settings["${scenarioPrefix}_effect"]
            if (effect != '0' && effect != '255') {
                String color = settings["${scenarioPrefix}_color"]
                input name: "${scenarioPrefix}_color", title: "<span style=\'color: blue;\'>${ledName} Color</span>", type: 'enum', options: ColorMap, width: 3, required: true, submitOnChange: true
                if (color == 'val') {
                    String url = '''<a href="https://community-assets.home-assistant.io/original/3X/6/c/6c0d1ea7c96b382087b6a34dee6578ac4324edeb.png" target="_blank">'''
                    input name: "${scenarioPrefix}_color_val", title: url + "<span style=\'color: blue; text-decoration: underline;\'>Hue Value</span></a>", type: 'number', range: '0..360', width: 2, required: true, submitOnChange: true
                } else {
                    app.removeSetting("${scenarioPrefix}_color_val")
                }
                if (color == 'var') {
                    input name: "${scenarioPrefix}_color_var", title: "<span style=\'color: blue;\'>Color Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
                } else {
                    app.removeSetting("${scenarioPrefix}_color_var")
                }
            } else {
                app.removeSetting("${scenarioPrefix}_color")
                app.removeSetting("${scenarioPrefix}_color_var")
                app.removeSetting("${scenarioPrefix}_color_val")
            }

            if (effect != '255') {
                // Time Unit
                input name: "${scenarioPrefix}_unit", title: '<span style=\'color: blue;\'>Duration</span>', description: 'Select', type: 'enum',
                    options: TimePeriodsMap, width: 2, defaultValue: 'Infinite', required: true, submitOnChange: true
                if (settings["${scenarioPrefix}_unit"] in ['0', '60', '120']) {
                    // Time Duration
                    String timePeriod = TimePeriodsMap[settings["${scenarioPrefix}_unit"]]
                    input name: "${scenarioPrefix}_duration", title: "<span style=\'color: blue;\'>${timePeriod}&nbsp;</span>", type: 'enum', width: 2, defaultValue: 1, required: true,
                        options: ['1', '2', '3', '4', '5', '10', '15', '20', '25', '30', '40', '50', '60']
                } else {
                    app.removeSetting("${scenarioPrefix}_duration")
                }
            } else {
                app.removeSetting("${scenarioPrefix}_unit")
                app.removeSetting("${scenarioPrefix}_duration")
            }

            if (effect != '0' && effect != '255') {
                // Level
                input name: "${scenarioPrefix}_level", title: "<span style=\'color: blue;\'>Level&nbsp;</span>", type: 'enum', width: 2,
                    defaultValue: 100, options: LevelMap, required: true, submitOnChange: true
                if (settings["${scenarioPrefix}_level"] == 'val') {
                    input name: "${scenarioPrefix}_level_val", title: "<span style=\'color: blue;\'>Level Value&nbsp;</span>", type: 'number',
                        range: '1..100', width: 2, required: true, submitOnChange: true
                } else {
                    app.removeSetting("${scenarioPrefix}_level_val")
                }
                if (settings["${scenarioPrefix}_level"] == 'var') {
                    input name: "${scenarioPrefix}_level_var", title: "<span style=\'color: blue;\'>Level Variable</span>", type: 'enum',
                        options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
                } else {
                    app.removeSetting("${scenarioPrefix}_level_var")
                }
            } else {
                app.removeSetting("${scenarioPrefix}_level")
                app.removeSetting("${scenarioPrefix}_level_var")
                app.removeSetting("${scenarioPrefix}_level_val")
            }
            paragraph ''
        }
    }
}

Map renderScenariosSection(String scenarioPrefix, String sectionTitle = null) {
    return section(sectionTitle) {
        Map<String, String> ruleTitles = ActivationRules.collectEntries { String k, Map v -> [k, v.title] }
        List<String> activeRules = settings["${scenarioPrefix}_conditions"] ?: []
        input name: "${scenarioPrefix}_conditions", title: '', type: 'enum', options: ruleTitles, multiple: true, required: true, width: 9, submitOnChange: true

        Boolean allScenariosMode = settings["${scenarioPrefix}_conditions_all"] ?: false
        if (settings["${scenarioPrefix}_conditions"]?.size() > 1) {
            String title = "${allScenariosMode ? '<b>All</b> rules' : '<b>Any</b> rule'}"
            input name: "${scenarioPrefix}_conditions_all", title: title, type: 'bool', width: 3, submitOnChange: true
        } else {
            paragraph ''
        }

        boolean isFirst = true
        Map<String, Map> activeScenarioRules = ActivationRules.findAll { String k, Map v -> k in activeRules }
        for (Map.Entry<String, Map> rule in activeScenarioRules) {
            String id = "${scenarioPrefix}_${rule.key}"
            String status = evaluateRule(id, rule.value) ? ' &#128994;' : ''
            if (!isFirst) {
                paragraph allScenariosMode ? '<b>and</b>' : '<i>or</i>'
            }
            isFirst = false
            Map<String, Map> inputs = rule.value.inputs as Map<String, Map>
            if (inputs.device) {
                input name: "${id}_device",
                    title: rule.value.title + ' ' + (inputs.device.title ?: '') + status,
                    type: inputs.device.type,
                    width: inputs.device.width ?: 7,
                    multiple: inputs.device.multiple,
                    submitOnChange: true,
                    required: true
                status = ''
                if (!inputs.device.any && settings["${id}_device"] in Collection && settings["${id}_device"]?.size() > 1) {
                    String name = inputs.device.title ?: rule.value.title
                    input name: "${id}_device_all",
                        title: settings["${id}_device_all"] ? "<b>All</b> ${name} devices" : "<b>Any</b> ${name} device",
                        type: 'bool', submitOnChange: true, width: 4
                }
            }

            if (inputs.choice) {
                Object options
                if (inputs.choice.options in Closure) {
                    Map ctx = [device: settings["${id}_device"]]
                    options = runClosure((Closure) inputs.choice.options, ctx)
                } else {
                    options = inputs.choice.options
                }
                if (options) {
                    input name: "${id}_choice",
                        title: rule.value.title + ' ' + (inputs.choice.title ?: '') + status,
                        defaultValue: inputs.choice.defaultValue,
                        options: options,
                        width: inputs.choice.width ?: 7,
                        multiple: inputs.choice.multiple,
                        type: 'enum',
                        submitOnChange: true, required: true
                    status = ''
                }
            }

            if (inputs.comparison) {
                Object options
                if (inputs.comparison.options in Closure) {
                    Map ctx = [device: settings["${id}_device"], choice: settings["${id}_choice"]]
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
                    submitOnChange: true, required: true
            }

            if (inputs.value) {
                Object options
                if (inputs.value.options in Closure) {
                    Map ctx = [device: settings["${id}_device"], choice: settings["${id}_choice"]]
                    options = runClosure((Closure) inputs.value.options, ctx)
                } else {
                    options = inputs.value.options
                }
                input name: "${id}_value",
                    title: (inputs.value.title ?: 'Value') + status + ' ',
                    width: inputs.value.width ?: 3,
                    defaultValue: inputs.value.defaultValue,
                    range: inputs.value.range,
                    options: options,
                    type: inputs.value.type ? inputs.value.type : (options ? 'enum' : 'text'),
                    submitOnChange: true, required: true
            }
        }
    }
}

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
            Map<String, String> config = getScenarioConfig(prefix)
            replaceVariables(config)
            ((String)config.lednumber).tokenize(',').each { String lednumber ->
                updateSwitchLedState([
                    color    : config.color,
                    effect   : config.effect,
                    lednumber: lednumber,
                    level    : config.level,
                    name     : config.name,
                    prefix   : config.prefix,
                    priority : config.priority,
                    unit     : config.unit
                ])
            }
            break
        default:
            logWarn "unknown app button ${buttonName}"
            break
    }
}

/**
 *  Returns available priorities based on lednumber for display in dropdown.
 */
Map<String, String> getPrioritiesList(String scenarioPrefix) {
    String ledNumber = settings["${scenarioPrefix}_lednumber"]
    if (ledNumber == 'var') {
        Map switchType = getTargetSwitchType()
        lednumber = lookupVariable(settings["${scenarioPrefix}_lednumber_var"], switchType.leds) ?: 'All'
    }
    Set<Integer> usedPriorities = (getScenarioPrefixes() - scenarioPrefix)
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
        return [p, text]
    }
}

/**
 *  Utility method for displaying CSS colored text.
 */
String getColorSpan(Integer hue, String text) {
    if (hue != null && text) {
        String css = (hue == 360) ? 'white' : "hsl(${hue}, 50%, 50%)"
        return "<span style=\'color: ${css}\'>${text}</span>"
    }
    return 'n/a'
}

/**
 *  Creates a description string for the dashboard configuration for display.
 */
String getScenarioDescription(String scenarioPrefix) {
    Map config = getScenarioConfig(scenarioPrefix)
    Map switchType = getTargetSwitchType()
    StringBuilder sb = new StringBuilder()
    sb << "<b>Priority</b>: ${config.priority}, "

    if (config.lednumber == 'var') {
        sb << "<b>LED Variable:</b> <i>${config.lednumber_var}</i>"
    } else if (config.lednumber) {
        sb << "<b>${switchType.leds[config.lednumber] ?: 'n/a'}</b>"
    }

    if (config.effect == 'var') {
        sb << ", <b>Effect Variable</b>: <i>${config.effect_var}</i>"
    } else if (config.effect) {
        Map<String, String> fxOptions = switchType.effectsAll + switchType.effects
        sb << ", <b>Effect:</b> ${fxOptions[config.effect] ?: 'n/a'}"
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
        List<String> rules = config.conditions
            .findAll { String rule -> ActivationRules.containsKey(rule) }
            .collect { String rule ->
                Map ctx = [
                    device    : config["${rule}_device"],
                    title     : ActivationRules[rule].title,
                    comparison: config["${rule}_comparison"],
                    choice    : config["${rule}_choice"],
                    value     : config["${rule}_value"],
                    delay     : config.delay,
                    cooldown  : config.cooldown
                ]
                if (ctx.device) {
                    boolean isAll = config["${rule}_device_all"]
                    if (ctx.device.size() > 2) {
                        String title = ActivationRules[rule].inputs.device.title.toLowerCase()
                        ctx.device = "${ctx.device.size()} ${title}"
                        ctx.device = (isAll ? 'All ' : 'Any of ') + ctx.device
                    } else {
                        ctx.device = ctx.device*.toString().join(isAll ? ' & ' : ' or ')
                    }
                }
                if (ctx.comparison) {
                    ctx.comparison = getComparisonsByType('number').get(ctx.comparison)?.toLowerCase()
                }
                if (ctx.choice != null) {
                    Map choiceInput = ActivationRules[rule].inputs.choice
                    Object options = choiceInput.options
                    if (choiceInput.options in Closure) {
                        options = runClosure((Closure) choiceInput.options, [device: config["${rule}_device"]]) ?: [:]
                    }
                    if (options in Map && config["${rule}_choice"] in List) {
                        ctx.choice = config["${rule}_choice"].collect { String key -> options[key] ?: key }.join(' or ')
                    } else if (options in Map) {
                        ctx.choice = options[config["${rule}_choice"]]
                    }
                }
                if (ctx.value =~ /^([0-9]{4})-/) { // special case for time format
                    ctx.value = new Date(timeToday(value).time).format('hh:mm a')
                }
                if (ActivationRules[rule].template) {
                    return runClosure((Closure) ActivationRules[rule].template, ctx)
                }
                return ActivationRules[rule].title + ' <i>(' + ctx.device + ')</i>'
            }
        String allMode = config.conditions_all ? ' and ' : ' or '
        sb << "\n<b>Activation${rules.size() > 1 ? 's' : ''}:</b> ${rules.join(allMode)}"
    }

    return sb.toString()
}

/**
 *  Returns the name of the effect selected for the given scenerio.
 */
String getEffectName(String scenarioPrefix) {
    Map switchType = getTargetSwitchType()
    String ledKey = settings["${scenarioPrefix}_lednumber"]
    Map<String, String> fxOptions = ledKey == 'All' ? switchType.effectsAll : switchType.effects
    String fxKey = settings["${scenarioPrefix}_effect"]
    return fxOptions[fxKey]
}

/**
 *  Returns a suggested name for the given scenerio.
 */
String getSuggestedScenarioName(String scenarioPrefix) {
    Map config = getScenarioConfig(scenarioPrefix)
    Map switchType = getTargetSwitchType()
    StringBuilder sb = new StringBuilder('Set ')

    if (config.lednumber && config.lednumber != 'var') {
        sb << switchType.leds[config.lednumber] ?: 'n/a'
    } else {
        sb << 'LED'
    }
    if (config.color && config.color != 'var' && config.color != 'val') {
        sb << ' to '
        String effectName = getEffectName(scenarioPrefix)
        if (effectName) {
            sb << "${effectName} "
        }
        sb << ColorMap[config.color]
    }

    return sb.toString()
}

/**
 *  Updates the app label based on pause state.
 */
void updatePauseLabel() {
    if (state.paused && !app.label?.endsWith(pauseText)) {
        app.updateLabel(app.label + pauseText)
    } else if (app.label?.endsWith(pauseText)) {
        app.updateLabel(app.label - pauseText)
    }
}

/**** END USER INTERFACE *********************************************************************/

/**
 *  Common event handler used by all rules.
 */
void eventHandler(Event event) {
    Map lastEvent = [
        descriptionText: event.descriptionText,
        deviceId       : event.deviceId,
        isStateChange  : event.isStateChange,
        name           : event.name,
        source         : event.source,
        type           : event.type,
        unit           : event.unit,
        unixTime       : event.unixTime,
        value          : event.value
    ]
    logDebug "<b>eventHandler:</b> ${lastEvent}"
    setState('lastEvent', lastEvent)

    // Debounce multiple events hitting at the same time by using timer job
    runInMillis(200, 'notificationDispatcher')
}

/**
 *  Tracks the led state changes and update the device tracker object.
 *  Only supported for drivers with an 'ledEffect' attribute.
 */
void switchStateTracker(Event event) {
    Map<String, Map> tracker = SwitchStateTracker[event.device.id]

    switch (event.value) {
        case 'User Cleared':
            logDebug "clear notification button pushed on ${event.device}"
            break
        case 'Stop All':
            if (tracker) {
                tracker.remove('All')
                logDebug "${event.device} All LED effect stopped"
            }
            break
        case ~/^Stop LED(\d)$/:
            if (tracker) {
                String led = (Matcher.lastMatcher[0] as List)[1]
                tracker.remove(led)
                logDebug "${event.device} LED${led} effect stopped"
            }
            break
    }
}

/**
 *  If forced refresh is enabled then this is called every specified
 *  interval to flush the cache and push updates out. This can be
 *  helpful for devices that may not reliably receive commands but
 *  should not be used unless required.
 */
void forceRefresh() {
    if (settings.periodicRefresh) {
        logInfo 'executing periodic forced refresh'
        SwitchStateTracker.clear()
        notificationDispatcher()

        int seconds = 60 * (settings.periodicRefreshInterval ?: 60)
        runIn(seconds, 'forceRefresh')
    }
}

/**
 *  Scheduled trigger used for rules that involve sunset times
 */
void sunsetTrigger() {
    logInfo 'executing sunset trigger'
    notificationDispatcher()
    subscribeAllScenarios()
}

/**
 *  Scheduled trigger used for rules that involve sunrise times
 */
void sunriseTrigger() {
    logInfo 'executing sunrise trigger'
    notificationDispatcher()
    subscribeAllScenarios()
}

/**
 *  Scheduled trigger used for rules that involve time
 */
void timeAfterTrigger() {
    logInfo 'executing time after trigger'
    notificationDispatcher()
    subscribeAllScenarios()
}

/**
 *  Scheduled stop used for devices that don't have built-in timers
 */
void stopNotification() {
    logDebug 'stopNotification called'
    Map switchType = getTargetSwitchType()
    for (DeviceWrapper device in (settings['switches'] as List<DeviceWrapper>)) {
        Map<String, Map> tracker = getSwitchStateTracker(device)
        if (getTimeMs() >= (tracker['All']?.expires as Long)) {
            if (device.hasCommand('setConfigParameter')) {
                Integer param = switchType.ledLevelParam as Integer
                logDebug "${device}.setConfigParameter(${param}, 0, '1')"
                device.setConfigParameter(param, 0, '1')
            } else if (device.hasCommand('off')) {
                logDebug "${device}.off()"
                device.off()
            }
            SwitchStateTracker.remove(device.id)
        }
    }
}

/**
 *  Main dispatcher for setting device LED notifications
 *  It first gets a prioritized list of dashboards, then evaluates the current dashboard
 *  condition rules to get a map of dashboard results. It then processes any delayed conditions
 *  and calculates the desired LED states for each dashboard. Finally, it dispatches each LED
 *  state to devices and schedules the next evaluation time if needed.
 */
@CompileStatic
void notificationDispatcher() {
    // Get prioritized list of dashboards
    List<String> prefixes = (List) getState('sortedPrefixes') ?: getSortedScenarioPrefixes()

    // Evaluate current dashboard condition rules
    Map<String, Boolean> dashboardResults = evaluateDashboardScenarios(prefixes)

    // Process any delayed conditions
    long nextEvaluationTime = evaluateDelayedRules(dashboardResults)

    // Calculate desired LED states
    Map<String, Map> ledStates = calculateLedState(prefixes, dashboardResults)

    // Dispatch each LED state to devices
    ledStates.values().each { config -> updateSwitchLedState(config) }

    // Schedule the next evaluation time
    if (nextEvaluationTime > getTimeMs()) {
        long delay = nextEvaluationTime - getTimeMs()
        logDebug "[notificationDispatcher] scheduling next evaluation in ${delay}ms"
        runAfterMs(delay, 'notificationDispatcher')
    }
}

/**
 *  Dashboard evaluation function responsible for iterating each condition over
 *  the dashboards and returning a map with true/false result for each dashboard prefix
 *  Does not apply any delay or cooldown options at this stage of the pipeline
 */
@CompileStatic
Map<String, Boolean> evaluateDashboardScenarios(List<String> prefixes) {
    return prefixes.collectEntries { prefix -> [prefix, evaluateActivationRules(prefix)] }
}

/**
 *  This code evaluates rules that have been delayed or have a cooldown period.
 *  It takes in a map of strings and booleans, which represent the evaluation results of the rules.
 *  It then checks if there is a delay before activation or a cooldown period set for each rule.
 *  If so, it sets the evaluation result to false for the delayed rule and true for the cooled down rule.
 *  Finally, it returns the next evaluation time based on when each rule should be evaluated again.
 */
@CompileStatic
long evaluateDelayedRules(Map<String, Boolean> evaluationResults) {
    long nextEvaluationTime = 0
    for (Map.Entry<String, Boolean> result in evaluationResults) {
        String scenarioPrefix = result.key
        boolean active = result.value
        long now = getTimeMs()

        // Check if delay before activation is configured
        String delayKey = "${scenarioPrefix}_delay"
        Integer delay = getSettingInteger(delayKey)
        if (active && delay) {
            int delayMs = delay * 60000
            // Determine if delay has expired yet
            long targetTime = getState().computeIfAbsent(delayKey) { k -> getTimeMs(delayMs) } as long
            if (now < targetTime) {
                logDebug "[evaluateDelayedRules] ${scenarioPrefix} has delayed activation (${delayMs}ms)"
                evaluationResults[scenarioPrefix] = false
                // calculate when we need to check again
                if (!nextEvaluationTime || nextEvaluationTime > targetTime) {
                    nextEvaluationTime = targetTime
                }
            }
        } else {
            removeState(delayKey)
        }

        // Check if delay post activation is configured
        String cooldownKey = "${scenarioPrefix}_cooldown"
        if (getSetting(cooldownKey)) {
            long targetTime = (long) getState(cooldownKey, 0)
            if (active) {
                setState(cooldownKey, -1) // mark that it has been active
            } else if (targetTime == -1) {
                int delayMs = (getSettingInteger(cooldownKey) ?: 0) * 60000
                targetTime = getTimeMs(delayMs)
                setState(cooldownKey, targetTime) // set expiration time when first inactive
                evaluationResults[scenarioPrefix] = true
                logDebug "[evaluateDelayedRules] ${scenarioPrefix} has cooldown (${delayMs}ms)"
            } else if (targetTime > 0 && now < targetTime) {
                // still in cooldown period
                evaluationResults[scenarioPrefix] = true
            } else if (now > targetTime) {
                // we are done with cooldown so remove the state
                removeState(cooldownKey)
                logDebug "[evaluateDelayedRules] ${scenarioPrefix} has completed cooldown"
            }

            // calculate when we need to check again
            if (targetTime && (!nextEvaluationTime || nextEvaluationTime > targetTime)) {
                nextEvaluationTime = targetTime
            }
        }
    }

    return nextEvaluationTime
}

/**
 *  Calculate Notification LED States from condition results.
 *  It takes in two parameters: a list of strings (prefixes) and a map of strings and booleans (results).
 *  The code iterates through each prefix in the list, gets the scenario configuration for that prefix,
 *  and stores it in a map. It then checks if the result for that prefix is true or false. If it is true,
 *  it replaces any variables in the configuration, checks if the new priority is greater than or equal
 *  to the old priority, and stores it in another map. If it is false and auto stop is not false, then it
 *  stores an auto stop effect with lowest priority into the same map.
 *  Finally, this code returns the ledStates map.
 */
@CompileStatic
Map<String, Map> calculateLedState(List<String> prefixes, Map<String, Boolean> results) {
    Map<String, Map> ledStates = [:]
    for (String scenarioPrefix in prefixes) {
        Map<String, Object> config = getScenarioConfig(scenarioPrefix)
        Map<String, Map> oldState = ledStates[config.lednumber as String] ?: [:]
        int oldPriority = oldState.priority as Integer ?: 0
        if (results[scenarioPrefix]) {
            replaceVariables(config)
            int newPriority = config.priority as Integer ?: 0
            if (newPriority >= oldPriority) {
                ((String)config.lednumber).tokenize(',').each { String lednumber ->
                    ledStates[lednumber] = [
                        prefix   : config.prefix,
                        name     : config.name,
                        lednumber: lednumber,
                        priority : config.priority,
                        effect   : config.effect,
                        color    : config.color,
                        level    : config.level,
                        unit     : config.unit
                    ]
                }
            }
        } else if (config.autostop != false && !oldPriority) {
            // Auto stop effect
            ((String)config.lednumber).tokenize(',').each { String lednumber ->
                ledStates[lednumber] = [
                    prefix   : config.prefix,
                    name     : "[auto stop] ${config.name}",
                    lednumber: lednumber,
                    priority : 0,    // lowest priority
                    effect   : '255',  // stop effect code
                    color    : '0',     // default color
                    level    : '100',   // default level
                    unit     : '255'     // Infinite
                ]
            }
        }
    }
    return ledStates
}

/*
 *  Private Implementation Helper Methods
 */

/**
 * Calculates the total milliseconds from Inovelli duration parameter (0-255)
 * where 1-60 = seconds, 61-120 = 1-60 minutes, 121-254 = 1-134 hours, 255 = Indefinite (24 hrs)
 */
@CompileStatic
private static int convertParamToMs(Integer duration) {
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

@CompileStatic
private static Map<String, Map> getSwitchStateTracker(DeviceWrapper dw) {
    return SwitchStateTracker.computeIfAbsent(dw.id) { k -> [:].withDefault { [:] } }
}

/**
 *  Takes in a list of DeviceWrapper objects, an attribute, an operator, and a value.
 *  It then evaluates whether all or any of the DeviceWrapper objects have a currentValue
 *  for the given attribute that satisfies the comparison with the given value using the given operator.
 *  It returns true if all or any of the DeviceWrapper objects satisfy this comparison, depending on the
 *  value of "all", and false otherwise.
 */
@CompileStatic
private static boolean deviceAttributeHasValue(List<DeviceWrapper> devices, String attribute, String operator, String value, Boolean all) {
    if (devices) {
        Closure test = { DeviceWrapper d -> evaluateComparison(d.currentValue(attribute) as String, value, operator) }
        return all ? devices.every(test) : devices.any(test)
    }
    return false
}

/**
 *  Takes in three parameters: two strings (a and b) and an operator and evaluates the comparison
 *  between the two strings based on the operator and returns a boolean value.
 */
@CompileStatic
private static boolean evaluateComparison(String a, String b, String operator) {
    if (a && b && operator) {
        switch (operator) {
            case '=': return a.equalsIgnoreCase(b)
            case '!=': return !a.equalsIgnoreCase(b)
            case '<>': return !a.equalsIgnoreCase(b)
            case '>': return new BigDecimal(a) > new BigDecimal(b)
            case '>=': return new BigDecimal(a) >= new BigDecimal(b)
            case '<': return new BigDecimal(a) < new BigDecimal(b)
            case '<=': return new BigDecimal(a) <= new BigDecimal(b)
        }
    }
    return false
}

/**
 *  Given a set of devices and an attribute name, provides the distinct set of attribute names.
 */
@CompileStatic
private static List<String> getAttributeChoices(List<DeviceWrapper> devices) {
    return devices?.collectMany { DeviceWrapper d -> d.getSupportedAttributes()*.name }
}

/**
 *  Given a set of devices and an attribute name, provides the distinct set of attribute values.
 *  Iterate through each DeviceWrapper object in the list and find the supported attributes
 *  that match the given attribute string and if so, it gets the values associated with that
 *  attribute and adds them to the returned list.
 */
@CompileStatic
private static List<String> getAttributeOptions(List<DeviceWrapper> devices, String attribute) {
    return devices?.collectMany { DeviceWrapper d ->
        List<String> values = d.getSupportedAttributes().find { attr -> attr.name == attribute }?.getValues()
        return values ?: Collections.emptyList() as List<String>
    }
}

/**
 *  Given a set of button devices, provides the list of valid button numbers that can be chosen from.
 *  It then iterates through the devices to find the maximum number of buttons among all of the 
 *  button devices. If there is at least one button, it creates a map with entries for each button
 *  number (1 to maxButtons) and its corresponding label ("Button n"). If there are no buttons,
 *  it returns an empty map.
 */
@CompileStatic
private static Map<String, String> getButtonNumberChoices(List<DeviceWrapper> buttonDevices) {
    if (buttonDevices) {
        List<Integer> buttonCounts = buttonDevices.collect { DeviceWrapper d ->
            d.currentValue('numberOfButtons') as Integer ?: 0
        }
        Integer maxButtons = buttonCounts.max()
        if (maxButtons >= 1) {
            return (1..maxButtons).collectEntries { int n -> [n as String, "Button ${n}"] }
        }
    }
    return Collections.emptyMap()
}

/**
 *  Returns a map of valid comparison choices for dropdown depending on the type that is passed in.
 *  If the type is "number", "integer", or "bigdecimal", then the Map will contain additional entries
 *  for "<", "<=", ">", and ">=" otherwise the Map will only contain two entries for '=' and '<>'.
 */
@CompileStatic
private static Map<String, String> getComparisonsByType(String type) {
    Map<String, String> result = ['=': 'Equal to', '<>': 'Not equal to']
    switch (type.toLowerCase()) {
        case 'number':
        case 'integer':
        case 'bigdecimal':
            result += [
                '<' : 'Less than',
                '<=': 'Less or equal',
                '>' : 'Greater than',
                '>=': 'Greater or equal'
            ]
            break
    }
    return result
}

/**
 *  Cleans application settings removing entries no longer in use.
 */
@CompileStatic
private void cleanSettings() {
    for (String prefix in getScenarioPrefixes()) {
        // Clean unused dashboard settings
        List<String> rules = (List) getSetting("${prefix}_conditions", [])
        ActivationRules.keySet()
            .findAll { String key -> !(key in rules) }
            .each { String key -> removeSettings("${prefix}_${key}") }

        // Clean unused variable settings
        ['lednumber', 'effect', 'color'].each { var ->
            if (getSettingString("${prefix}_${var}") != 'var') {
                removeSetting("${prefix}_${var}_var")
            }
        }
    }
}

/**
 *  Returns next available scenerio settings prefix used when adding a new scenerio.
 */
@CompileStatic
private String findNextPrefix() {
    List<Integer> keys = getScenarioPrefixes().collect { String p -> p.substring(10) as Integer }
    int maxId = keys ? Collections.max(keys) : 0
    return "condition_${maxId + 1}"
}

/**
 *  Returns key value map of scenario settings for the given prefix.
 */
@CompileStatic
private Map<String, Object> getScenarioConfig(String scenarioPrefix) {
    Map<String, Object> config = [prefix: (Object) scenarioPrefix]
    int startPos = scenarioPrefix.size() + 1
    getAppSettings().findAll { String key, Object value ->
        key.startsWith(scenarioPrefix + '_')
    }.each { String key, Object value ->
        config[key.substring(startPos)] = value
    }
    return config
}

/**
 *  Returns the set of scenario prefixes from the settings.
 */
@CompileStatic
private Set<String> getScenarioPrefixes() {
    return (Set<String>) getAppSettings().keySet().findAll { Object key ->
        key.toString().matches('^condition_[0-9]+_priority$')
    }.collect { Object key -> key.toString() - '_priority' }
}

/**
 *  Returns scenerio setting prefix sorted by priority then name.
 */
@CompileStatic
private List<String> getSortedScenarioPrefixes() {
    return getScenarioPrefixes().collect { String scenarioPrefix ->
        [
            prefix  : (String) scenarioPrefix,
            name    : getSettingString("${scenarioPrefix}_name"),
            priority: getSettingInteger("${scenarioPrefix}_priority")
        ]
    }.sort { a, b ->
        (Integer) b.priority <=> (Integer) a.priority ?: (String) a.name <=> (String) b.name
    }*.prefix as List<String>
}

/**
 *  Returns the device type configuration for the currently switch type setting.
 */
@CompileStatic
private Map getTargetSwitchType() {
    String deviceType = getSettingString('deviceType')
    Map switchType = SupportedSwitchTypes.get(deviceType)
    if (!switchType) {
        logError "Unable to retrieve supported switch Type for '${deviceType}'"
    }
    return switchType
}

/**
 *  Looks up a variable in a given dropdown map and returns the key if it exists.
 *  It first gets the value of the variable from the 'getHubVariableValue' function
 *  and then checks if it is present in the lookup table. If it is present,
 *  it returns the key associated with that value. Otherwise, it returns null.
 */
@CompileStatic
private String lookupVariable(String variableName, Map<String, String> lookupTable) {
    String value = getHubVariableValue(variableName) as String
    if (value) {
        return lookupTable.find { String k, String v -> v.equalsIgnoreCase(value) }?.key
    }
    return null
}

// Removes all condition settings starting with prefix used when the user deletes a condition
@CompileStatic
private void removeSettings(String scenarioPrefix) {
    Set<String> entries = getAppSettings().keySet().findAll { String s -> s.startsWith(scenarioPrefix) }
    if (entries) {
        logDebug "removing settings ${entries}"
        entries.each { String s -> removeSetting(s) }
    }
}

/**
 *  Replace variables in the scenerio configuration settings with the appropriate values.
 *  Checks if any of the variables (lednumber, effect, color, level) are set to 'var'.
 *  If so, looks up the hub global variable value and assigns it to the configuration.
 */
@CompileStatic
private void replaceVariables(Map<String, Object> config) {
    Map switchType = getTargetSwitchType()
    if (switchType) {
        if (config.lednumber == 'var') {
            config.lednumber = lookupVariable((String) config.lednumber_var, (Map) switchType.leds) ?: 'All'
        }
        if (config.effect == 'var') {
            Map<String, String> fxOptions = (Map<String, String>) switchType.effectsAll + (Map<String, String>) switchType.effects
            config.effect = lookupVariable((String) config.effect_var, fxOptions) as String
        }
        if (config.color == 'var') {
            config.color = lookupVariable((String) config.color_var, ColorMap) as String
        }
        if (config.color == 'val') {
            config.color = config.color_val as String
        }
        if (config.level == 'var') {
            config.level = getHubVariableValue((String) config.level_var) as String
        }
        if (config.level == 'val') {
            config.level = config.level_val as String
        }
    }
}

/**
 *  Reset the notifications of a device. It first creates a map of all the leds associated with
 *  the device. Then iterates through each led, setting its name to 'clear notification',
 *  priority to 0, effect to 255 (stop effect code), color to 0 (default color), level to 100
 *  (default level), and unit to 255 (infinite).
 */
@CompileStatic
private void resetNotifications() {
    Map<String, String> leds = (Map<String, String>) getTargetSwitchType().leds
    if (leds) {
        logInfo 'resetting all device notifications'
        leds.keySet().findAll { String s -> s != 'var' }.each { String led ->
            updateSwitchLedState(
                [
                    color    : '0',       // default color
                    effect   : '255',     // stop effect code
                    lednumber: led,
                    level    : '100',     // default level
                    name     : 'clear notification',
                    priority : 0,
                    unit     : '255'      // infinite
                ]
            )
        }
    }
}

/**
 * Subscribes to all dashboard scenario rules. The method first gets all the scenario prefixes,
 * then iterates through each one of them and calls the subscribeActiveRules() method with
 * the scenario prefix as an argument.
 */
@CompileStatic
private void subscribeAllScenarios() {
    getScenarioPrefixes().each { String scenarioPrefix ->
        subscribeActiveRules(scenarioPrefix)
    }
}

/**
 *  Subscribes to switch attributes. Iterates a list of attributes (in this case, 'ledEffect')
 *  and checks if each switch device has that attribute. If it does, then it subscribes to the
 *  attribute with the 'switchStateTracker' method.
 */
private void subscribeSwitchAttributes() {
    List<String> attributes = ['ledEffect']
    settings.switches.each { DeviceWrapper dw ->
        attributes.each { attr ->
            if (dw.hasAttribute(attr)) {
                subscribe(dw, attr, 'switchStateTracker', null)
            }
        }
    }
}

/**
 *  Provides a wrapper around driver specific commands for updating the LED state of a device.
 *  It loops through a list of devices and checks if the LED state has changed.
 *  If it has, it will update the LED state accordingly.
 *  Finally, it will set an expiration time for the LED state based on the unit and duration parameters.
 */
@CompileStatic
private void updateSwitchLedState(Map config) {
    for (DeviceWrapper device in (List<DeviceWrapper>) getSetting('switches', [])) {
        logDebug "setting ${device} LED #${config.lednumber} (" +
            "id=${config.prefix}, name=${config.name}, priority=${config.priority}, " +
            "effect=${config.effect ?: ''}, color=${config.color}, level=${config.level}, " +
            "duration=${config.duration ?: ''} ${TimePeriodsMap[config.unit as String] ?: ''})"

        Map<String, Map> tracker = getSwitchStateTracker(device)
        String key = config.lednumber
        if (tracker[key] == null
            || tracker[key].effect != config.effect
            || tracker[key].color != config.color
            || tracker[key].level != config.level
            || tracker[key].unit != config.unit
            || tracker[key].duration != config.duration
            || (Long) tracker[key].expires <= getTimeMs()
        ) {
            if (device.hasCommand('ledEffectOne')) {
                updateSwitchLedStateInovelliBlue(device, config)
            } else if (device.hasCommand('startNotification')) {
                updateSwitchLedStateInovelliRedGen2(device, config)
            } else if (device.hasCommand('setConfigParameter')) {
                updateSwitchLedStateInovelliRedGen1(device, config)
            } else if (device.hasCommand('setColor')) {
                updateSwitchColor(device, config)
            } else {
                logWarn "unable to determine notification command for ${device}"
                continue
            }
            int offset = convertParamToMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
            config.expires = getTimeMs(offset)
            tracker[key] = config
        } else {
            logDebug "skipping update to ${device} (no change detected)"
        }
    }
}

/**
 *  updateSwitchColor is a wrapper around the color device driver methods
 */
private void updateSwitchColor(DeviceWrapper dw, Map config) {
    String key = "device-state-${dw.id}"
    Integer color = config.color as Integer
    switch (config.effect) {
        case '0': // Off
            logDebug "${dw}.off()"
            dw.off()
            state.remove(key)
            break
        case '1': // On
            if (dw.currentValue('switch') == 'on') {
                state[key] = [
                    hue       : dw.currentValue('hue') ?: 0,
                    saturation: dw.currentValue('saturation') ?: 0,
                    level     : dw.currentValue('level') ?: 0,
                ]
            }
            int huePercent = (int) Math.round((color / 360.0) * 100)
            logDebug "${dw}.setColor(${huePercent})"
            dw.setColor([
                hue       : color == 360 ? 0 : huePercent,
                saturation: color == 360 ? 0 : 100,
                level     : config.level as Integer
            ])
            break
        case '255':
            if (state[key]?.level) {
                logDebug "${dw}.setColor(${state[key]})"
                dw.setColor([
                    hue       : state[key].hue as Integer,
                    saturation: state[key].saturation as Integer,
                    level     : state[key].level as Integer
                ])
                removeState(key)
            } else {
                logDebug "${dw}.off()"
                dw.off()
            }
            break
    }
    if (config.unit && config.unit != '255') {
        int duration = convertParamToMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
        logDebug 'scheduling stopNotification in ' + duration + 'ms'
        runInMillis(duration + 1000, 'stopNotification')
    } else {
        unschedule('stopNotification')
    }
}

/**
 *  updateSwitchLedStateInovelliBlue is a wrapper around the Inovelli device ledEffect driver methods
 *  The wrapper uses the trackingState to reduce the Zigbee traffic by checking the
 *  assumed LED state before sending changes.
 */
private void updateSwitchLedStateInovelliBlue(DeviceWrapper dw, Map config) {
    int color, duration, effect, level
    if (config.unit != null) {
        duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255)
    }
    if (config.color != null) {
        color = (int) Math.min(Math.round(((config.color as Integer) / 360.0) * 255), 255)
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
    pauseExecution(PAUSE_DELAY_MS)
}

/**
 *  updateSwitchLedStateInovelliRedGen1 is a wrapper around the
 *  Inovelli device driver setConfigParameter method.
 */
private void updateSwitchLedStateInovelliRedGen1(DeviceWrapper dw, Map config) {
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
    Map switchType = getTargetSwitchType()
    logDebug "${dw}.setConfigParameter(${switchType.ledLevelParam},${level},'1')"
    dw.setConfigParameter(switchType.ledLevelParam as int, level, '1')
    if (level > 0) {
        logDebug "${dw}.setConfigParameter(${switchType.ledColorParam},${color},'2')"
        dw.setConfigParameter(switchType.ledColorParam as int, color, '2')
    }
    if (config.unit && config.unit != '255') {
        long duration = convertParamToMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
        logDebug 'scheduling stopNotification for ' + duration + 'ms'
        runInMillis(duration + 1000, 'stopNotification')
    } else {
        unschedule('stopNotification')
    }
    pauseExecution(PAUSE_DELAY_MS)
}

/**
 *  updateSwitchLedStateInovelliRedGen2 is a wrapper around the
 *  Inovelli device driver startnotification method. This code will no longer be required
 *  when the updated Gen2 driver is released with the startNotiication command.
 *  Reference https://nathanfiscus.github.io/inovelli-notification-calc/
 */
private void updateSwitchLedStateInovelliRedGen2(DeviceWrapper dw, Map config) {
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
    byte[] bytes = [effect as byte, duration as byte, level as byte, color as byte]
    int value = new BigInteger(bytes).intValue()
    logDebug "${dw}.startNotification(${value}) [${bytes[0] & 0xff}, ${bytes[1] & 0xff}, ${bytes[2] & 0xff}, ${bytes[3] & 0xff}]"
    if (config.lednumber == 'All') {
        dw.startNotification(value)
    } else {
        dw.startNotification(value, config.lednumber as Integer)
    }
    pauseExecution(PAUSE_DELAY_MS)
}

/**
 *  Evaluates all activation rules for a given prefix. It first gets the boolean value of the
 *  "requireAll" flag and the name associated with the prefix. It then loops through all
 *  conditions associated with the prefix and evaluates each rule. If the "requireAll" flag is true,
 *  it will return false if any of the rules fail. If it is false, it will return true if any of the rules pass.
 */
@CompileStatic
private boolean evaluateActivationRules(String prefix) {
    boolean result = false
    boolean requireAll = getSettingBoolean("${prefix}_conditions_all")
    String name = getSettingString("${prefix}_name")

    // Loop through all conditions updating the result
    List<String> activeRules = (List) getSetting("${prefix}_conditions", [])
    for (String ruleKey in activeRules) {
        Map<String, Map> rule = ActivationRules[ruleKey]
        if (rule) {
            boolean testResult = evaluateRule("${prefix}_${ruleKey}", rule)
            // If all conditions is selected and the test failed, stop and return false
            if (requireAll && !testResult) {
                result = false
                break
            // If any conditions is selected and the test passed, stop and return true
            } else if (!requireAll && testResult) {
                result = true
                break
            }
            // Otherwise update the result and try the next condition
            result |= testResult
        }
    }
    logDebug "[evaluateActivationRules] ${name} (${prefix}) returns ${result}"
    return result
}

/**
 *  Evaluates a specific rule for the given prefix.
 *  It gets the device, choice, and value from the settings based on the given prefix.
 *  Then it runs a closure to get an attribute value and stores it in a context map.
 *  The context map also contains the all, choice, comparison, device, event, and value settings
 *  Finally, it runs a closure to test the rule passing in the context map and returns true
 *  or false depending on the result.
 */
@CompileStatic
private boolean evaluateRule(String prefix, Map rule) {
    String attribute
    if (!rule) {
        return false
    }
    if (rule.subscribe in Closure) {
        Map ctx = [
            device: getSetting("${prefix}_device"),
            choice: getSetting("${prefix}_choice"),
            value : getSetting("${prefix}_value")
        ].asImmutable()
        attribute = runClosure((Closure) rule.subscribe, ctx)
    } else {
        attribute = rule.subscribe
    }
    Map ctx = [
        all       : getSettingBoolean("${prefix}_device_all"),
        attribute : attribute,
        choice    : getSetting("${prefix}_choice"),
        comparison: getSetting("${prefix}_comparison"),
        device    : getSetting("${prefix}_device"),
        event     : getState('lastEvent') ?: [:],
        value     : getSetting("${prefix}_value")
    ].asImmutable()
    boolean result = runClosure((Closure) rule.test, ctx) ?: false
    logDebug "[evaluateRule] ${rule.title} (${prefix}) is ${result}"
    return result
}

/**
 *  Used to subscribe to active rules for the provided condition prefix argument.
 *  Iterates through the active rules from the setting with the given prefix.
 *  For each rule, it checks if there is an execute closure, and if so, runs it with a context
 *  of device, choice, and value. It then checks if there is a subscribe closure or attribute
 *  and subscribes to the event handler with the given device (or location) and attribute.
 */
@CompileStatic
private void subscribeActiveRules(String prefix) {
    List<String> activeRules = (List) getSetting("${prefix}_conditions", Collections.emptyList())
    for (String ruleKey in activeRules) {
        Map<String, Map> rule = ActivationRules[ruleKey]
        if (rule.execute in Closure) {
            String key = "${prefix}_${ruleKey}"
            Map<String, Object> ctx = [
                device: getSetting("${key}_device"),
                choice: getSetting("${key}_choice"),
                value : getSetting("${key}_value")
            ].asImmutable()
            runClosure((Closure) rule.execute, ctx)
        }
        if (rule.subscribe) {
            String key = "${prefix}_${ruleKey}"
            Map ctx = [
                device: getSetting("${key}_device"),
                choice: getSetting("${key}_choice"),
                value : getSetting("${key}_value")
            ].asImmutable()
            String attribute
            if (rule.subscribe in Closure) {
                attribute = runClosure((Closure) rule.subscribe, ctx)
            } else {
                attribute = rule.subscribe
            }
            subscribeEventHandler(ctx.device ?: getLocation(), attribute)
        }
    }
}

/**
 *  Subscribe to all variables used in the dashboard by finding all settings with keys that end with
 *  '_var'. The variable names are then iterated over and subscribeEventHandler() is called for each.
 */
@CompileStatic
private void subscribeAllVariables() {
    getAppSettings().findAll { Map.Entry<String, Object> s -> s.key.endsWith('_var') }.values().each { Object var ->
        subscribeEventHandler(location, "variable:${var}")
    }
}

/**
 *  Subscribes an event handler to a target for a given attribute.
 *  If the attribute starts with "variable:", variable to a list of global variables in use.
 */
private void subscribeEventHandler(Object target, String attribute) {
    logDebug "subscribing to ${target} for attribute '${attribute}'"
    subscribe(target, attribute, 'eventHandler', null)
    if (attribute.startsWith('variable:')) {
        String variable = attribute.substring(9)
        logDebug "registering use of Hub variable '${variable}'"
        addInUseGlobalVar(variable)
    }
}

/**
 *  Creates a map of sunrise and sunset times for the current day and the next day, based on a given date
 *  (now) and an offset (default 0). It then calculates whether it is currently night or day by comparing the
 *  current time to the sunrise and sunset times. Finally, it returns a map containing all of this information,
 *  plus the offset used to calculate it.
 */
private Map getAlmanac(Date now, int offset = 0) {
    Map<String, Date> today = getSunriseAndSunset([sunriseOffset: offset, sunsetOffset: offset, date: now])
    Map<String, Date> tomorrow = getSunriseAndSunset([sunriseOffset: offset, sunsetOffset: offset, date: now + 1])
    Map<String, Date> next = [sunrise: (Date) now < (Date) today.sunrise ? (Date) today.sunrise : (Date) tomorrow.sunrise, sunset: now < today.sunset ? (Date) today.sunset : (Date) tomorrow.sunset]
    LocalTime sunsetTime = LocalTime.of(today.sunset.getHours(), today.sunset.getMinutes())
    LocalTime sunriseTime = LocalTime.of(next.sunrise.getHours(), next.sunrise.getMinutes())
    LocalTime nowTime = LocalTime.of(now.getHours(), now.getMinutes())
    boolean isNight = nowTime > sunsetTime || nowTime < sunriseTime
    Map almanac = [today: today, tomorrow: tomorrow, next: next, isNight: isNight, offset: offset]
    logDebug "almanac: ${almanac}"
    return almanac
}

/**
 *  Takes in a Date object (now) and a String (datetime) and returns a Date object.
 *  Checks if the now Date is greater than or equal to the target Date. If it is,
 *  it returns the target Date plus one day, otherwise it returns the target Date.
 */
private Date getNextTime(Date now, String datetime) {
    Date target = timeToday(datetime)
    return (now >= target) ? target + 1 : target
}

/**
 *  Method to run a closure from a Closure object (c) and a Map object (ctx) and return result.
 *  It first clones the Closure object so that it can set its delegate to "this". It then calls
 *  the closure with the passed in ctx map as an argument. If an error occurs, it logs the error
 *  along with the code for the closure and the context map.
 */
@CompileStatic
private Object runClosure(Closure c, Map ctx) {
    String code = 'unknown'
    try {
        code = c.metaClass.classNode.getDeclaredMethods('doCall')?.first()?.code?.text
        Closure closure = (Closure) c.clone()
        closure.delegate = this
        return closure.call(ctx)
    } catch (e) {
        logWarn "runClosure error ${e}: ${code} with ctx ${ctx}"
    }
    return null
}

/**
 *  Defines the supported notification device types. Each entry defines a single type of device consisting of:
 *      title      - Displayed to the user in selection dropdown
 *      type       - The internal Hubitat device type used to allow device selection
 *      leds       - Map of available leds when using this device ('All' and 'var' are special values)
 *      effects    - Map of effects that can be selected from when using this device
 *      effectsAll - Map of effects that can be selected from if led choice is set to 'All'
 */
@Field static final Map<String, Map> SupportedSwitchTypes = [
    'Inovelli Blue Switch' : [
        title     : 'Inovelli Dimmer 2-in-1 Blue Series VZM31-SN',
        type      : 'device.InovelliDimmer2-in-1BlueSeriesVZM31-SN',
        leds      : [
            'All': 'All LEDs',
            '7': 'LED 7 (Top)',
            '6': 'LED 6',
            '5': 'LED 5',
            '4': 'LED 4',
            '3': 'LED 3',
            '2': 'LED 2',
            '1': 'LED 1 (Bottom)',
            '5,6,7': 'Top Half LEDs',
            '3,4,5': 'Middle LEDs',
            '1,2,3': 'Bottom Half LEDs',
            'var': 'Variable LED'
        ],
        effects   : ['255': 'Stop', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '0': 'Off', 'var': 'Variable Effect'],
        effectsAll: ['1' : 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Open/Close', '7': 'Small-to-Big', '8': 'Aurora', '9': 'Slow Falling', '10': 'Medium Falling', '11': 'Fast Falling',
                     '12': 'Slow Rising', '13': 'Medium Rising', '14': 'Fast Rising', '15': 'Medium Blink', '16': 'Slow Chase', '17': 'Fast Chase', '18': 'Fast Siren', '19': 'Slow Siren', '0': 'Off', 'var': 'Variable Effect']
    ],
    'Inovelli Dimmer' : [
        title        : 'Inovelli Dimmer LZW31',
        type         : 'device.InovelliDimmerLZW31',
        leds         : ['All': 'Notification'],
        ledLevelParam: 14,
        ledColorParam: 13,
        effects      : [:],
        effectsAll   : ['255': 'Stop', '1': 'Solid', 'var': 'Variable Effect']
    ],
    'Inovelli Switch' : [
        title        : 'Inovelli Switch LZW30',
        type         : 'device.InovelliSwitchLZW30',
        leds         : ['All': 'Notification'],
        ledLevelParam: 6,
        ledColorParam: 5,
        effects      : [:],
        effectsAll   : ['255': 'Stop', '1': 'Solid', 'var': 'Variable Effect']
    ],
    'Inovelli Red Dimmer' : [
        title     : 'Inovelli Dimmer Red Series LZW31-SN',
        type      : 'device.InovelliDimmerRedSeriesLZW31-SN',
        leds      : ['All': 'Notification'],
        effects   : [:],
        effectsAll: ['255': 'Stop', '1': 'Solid', '2': 'Chase', '3': 'Fast Blink', '4': 'Slow Blink', '5': 'Pulse', 'var': 'Variable Effect']
    ],
    'Inovelli Blue Fan Switch': [
        title     : 'Inovelli Fan Switch Blue Series VZM35-SN',
        type      : 'device.InovelliFanSwitchBlueSeriesVZM35-SN',
        leds      : [
            'All': 'All LEDs',
            '7': 'LED 7 (Top)',
            '6': 'LED 6',
            '5': 'LED 5',
            '4': 'LED 4',
            '3': 'LED 3',
            '2': 'LED 2',
            '1': 'LED 1 (Bottom)',
            '5,6,7': 'Top Half LEDs',
            '3,4,5': 'Middle LEDs',
            '1,2,3': 'Bottom Half LEDs',
            'var': 'Variable LED'
        ],
        effects   : ['255': 'Stop', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '0': 'Off', 'var': 'Variable Effect'],
        effectsAll: ['1' : 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Open/Close', '7': 'Small-to-Big', '8': 'Aurora', '9': 'Slow Falling', '10': 'Medium Falling', '11': 'Fast Falling',
                     '12': 'Slow Rising', '13': 'Medium Rising', '14': 'Fast Rising', '15': 'Medium Blink', '16': 'Slow Chase', '17': 'Fast Chase', '18': 'Fast Siren', '19': 'Slow Siren', '0': 'Off', 'var': 'Variable Effect']
    ],
    'Inovelli Red Fan Light' : [
        title     : 'Inovelli Fan + Light Red Series LZW36',
        type      : 'device.InovelliFan%2BLightLZW36',
        leds      : ['1': 'Light', '2': 'Fan'],
        effects   : ['255': 'Stop', '1': 'Solid', '2': 'Chase', '3': 'Fast Blink', '4': 'Slow Blink', '5': 'Pulse', 'var': 'Variable Effect'],
        effectsAll: [:]
    ],
    'Inovelli Red Switch' : [
        title     : 'Inovelli Switch Red Series LZW30-SN',
        type      : 'device.InovelliSwitchRedSeriesLZW30-SN',
        leds      : ['All': 'Notification'],
        effects   : [:],
        effectsAll: ['255': 'Stop', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', 'var': 'Variable Effect']
    ],
    'RGB' : [
        title     : 'Generic RGB Device',
        type      : 'capability.colorControl',
        leds      : ['All': 'RGB Color'],
        effects   : [:],
        effectsAll: ['255': 'Stop', '0': 'Off', '1': 'On', 'var': 'Variable Effect']
    ]
].sort { a, b -> a.value.title <=> b.value.title }

// Definitions for condition options
@Field static final Map<String, String> ColorMap = [
    '0'  : 'Red',
    '10' : 'Orange',
    '40' : 'Lemon',
    '91' : 'Lime',
    '120': 'Green',
    '150': 'Teal',
    '180': 'Cyan',
    '210': 'Aqua',
    '241': 'Blue',
    '269': 'Violet',
    '300': 'Magenta',
    '332': 'Pink',
    '360': 'White',
    'val': 'Custom Color',
    'var': 'Variable Color'
]

@Field static final Set<Integer> Priorities = 20..1 // can be increased if desired

@Field static final Map<String, String> LevelMap = [
    '10' : '10',
    '20' : '20',
    '30' : '30',
    '40' : '40',
    '50' : '50',
    '60' : '60',
    '70' : '70',
    '80' : '80',
    '90' : '90',
    '100': '100',
    'val': 'Custom',
    'var': 'Variable'
]

@Field static final Map<String, String> TimePeriodsMap = [
    '0'  : 'Seconds',
    '60' : 'Minutes',
    '120': 'Hours',
    '255': 'Infinite'
]

// Defines the text used to show the application is paused
@Field static final String pauseText = '<span style=\'color: red;\'> (Paused)</span>'

// How long to pause execution between sending each device command
@Field static final int PAUSE_DELAY_MS = 200

/**
 *  List of activation rules. Each entry defines a single selectable rule consisting of:
 *      title     - Displayed to the user in dropdown selection
 *      template  - Displayed in the overview description shown for each rule
 *      inputs    - Defines the inputs required from the user when selecting the rule
 *      execute   - Optional setup closure needed for this rule (run each time rules are evaluated)
 *      subscribe - Attribute name or Closure that returns an attribute name that is subscribed to
 *      test      - Closure that determines if the rule is satisfied by returning true or false
 */
@Field static final Map<String, Map> ActivationRules = [
    'almanac'           : [
        title   : 'Time period is between',
        template: { Map ctx -> "Time period is from ${ctx.choice}" },
        inputs  : [
            choice: [
                options : [
                    sunriseToSunset: 'Sunrise to Sunset (Day)',
                    sunsetToSunrise: 'Sunset to Sunrise (Night)'
                ],
                multiple: false,
                width   : 5
            ],
            value : [
                title       : 'Offset minutes',
                type        : 'number',
                range       : '-300..300',
                defaultValue: 0,
                width       : 2
            ]
        ],
        execute : { Map ctx ->
            Map almanac = getAlmanac(new Date(), (ctx.value as Integer) ?: 0)
            runOnce(almanac.next.sunset, 'sunsetTrigger')
            runOnce(almanac.next.sunrise, 'sunriseTrigger')
        },
        test    : { Map ctx ->
            Map almanac = getAlmanac(new Date(), (ctx.value as Integer) ?: 0)
            switch (ctx.choice) {
                case 'sunsetToSunrise': return almanac.isNight
                case 'sunriseToSunset': return !almanac.isNight
                default: return false
            }
        }
    ],
    'accelerationActive': [
        title    : 'Acceleration becomes active',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.accelerationSensor',
                multiple: true
            ]
        ],
        subscribe: 'contact',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'acceleration', '=', 'active', ctx.all as Boolean) }
    ],
    'buttonPress'       : [
        title    : 'Button is pressed',
        template : { Map ctx -> "${ctx.choice} is pressed <i>(${ctx.device})</i>" },
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.pushableButton',
                multiple: true,
                any     : true
            ],
            choice: [
                title   : 'Select Button Number(s)',
                options : { Map ctx -> getButtonNumberChoices(ctx.device as List<DeviceWrapper>) },
                multiple: true
            ]
        ],
        subscribe: 'pushed',
        test     : { Map ctx -> ctx.choice && ctx.event.value in ctx.choice }
    ],
    'contactClose'      : [
        title    : 'Contact closes',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.contactSensor',
                multiple: true
            ]
        ],
        subscribe: 'contact',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'contact', '=', 'closed', ctx.all as Boolean) }
    ],
    'contactOpen'       : [
        title    : 'Contact opens',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.contactSensor',
                multiple: true
            ]
        ],
        subscribe: 'contact',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'contact', '=', 'open', ctx.all as Boolean) }
    ],
    'customAttribute'   : [
        title    : 'Custom attribute',
        template : { Map ctx -> "${ctx.choice.capitalize()} is ${ctx.comparison} ${ctx.value} <i>(${ctx.device})</i>" },
        inputs   : [
            device    : [
                title   : 'Select Devices',
                type    : 'capability.*',
                multiple: true
            ],
            choice    : [
                title   : 'Select Custom Attribute',
                options : { Map ctx -> ctx.device ? getAttributeChoices(ctx.device as List<DeviceWrapper>) : null },
                multiple: false
            ],
            comparison: [
                options: { Map ctx -> getComparisonsByType('number') }
            ],
            value     : [
                title  : 'Attribute Value',
                options: { Map ctx -> (ctx.device && ctx.choice) ? getAttributeOptions(ctx.device as List<DeviceWrapper>, ctx.choice as String) : null }
            ]
        ],
        subscribe: { Map ctx -> ctx.choice },
        test     : { Map ctx ->
            deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, ctx.choice as String, ctx.comparison as String,
                ctx.value as String, ctx.all as Boolean)
        }
    ],
    'hsmAlert'          : [
        title    : 'HSM intrusion alert becomes',
        template : { Map ctx -> "HSM intrusion alert becomes ${ctx.choice}" },
        inputs   : [
            choice: [
                options : [
                    'intrusion'     : 'Intrusion Away',
                    'intrusion-home': 'Intrusion Home',
                    'smoke'         : 'Smoke',
                    'water'         : 'Water',
                    'arming'        : 'Arming fail',
                    'cancel'        : 'Alert cancelled'
                ],
                multiple: true
            ]
        ],
        subscribe: 'hsmAlert',
        test     : { Map ctx -> ctx.event.value in ctx.choice }
    ],
    'hsmStatus'         : [
        title    : 'HSM arming status becomes',
        template : { Map ctx -> "HSM arming status becomes ${ctx.choice}" },
        inputs   : [
            choice: [
                options : [
                    'armedAway'  : 'Armed Away',
                    'armingAway' : 'Arming Away',
                    'armedHome'  : 'Armed Home',
                    'armingHome' : 'Arming Home',
                    'armedNight' : 'Armed Night',
                    'armingNight': 'Arming Night',
                    'disarmed'   : 'Disarmed',
                    'allDisarmed': 'All Disarmed'
                ],
                multiple: true
            ]
        ],
        subscribe: 'hsmStatus',
        test     : { Map ctx -> location.hsmStatus in ctx.choice }
    ],
    'hubMode'           : [
        title    : 'Hub mode becomes',
        template : { Map ctx -> "Hub mode becomes ${ctx.choice}" },
        inputs   : [
            choice: [
                options : { Map ctx -> location.modes.collectEntries { m -> [m.id as String, m.name] } },
                multiple: true
            ],
        ],
        subscribe: 'mode',
        test     : { Map ctx -> (location.currentMode.id as String) in ctx.choice }
    ],
    'locked'            : [
        title    : 'Lock is locked',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.lock',
                multiple: true
            ]
        ],
        subscribe: 'lock',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'lock', '=', 'locked', ctx.all as Boolean) }
    ],
    'luminosityAbove'   : [
        title    : 'Illuminance rises above',
        template : { Map ctx -> "Illuminance rises above ${ctx.value} <i>(${ctx.device})</i>" },
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.illuminanceMeasurement',
                multiple: true
            ],
            value : [
                title: 'Enter Illuminance Value'
            ]
        ],
        subscribe: { 'illuminance' },
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'illuminance', '>', ctx.value as String, ctx.all as Boolean) }
    ],
    'luminosityBelow'   : [
        title    : 'Illuminance falls below',
        template : { Map ctx -> "Illuminance falls below ${ctx.value} <i>(${ctx.device})</i>" },
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.illuminanceMeasurement',
                multiple: true
            ],
            value : [
                title: 'Enter Illuminance Value'
            ]
        ],
        subscribe: { 'illuminance' },
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'illuminance', '<', ctx.value as String, ctx.all as Boolean) }
    ],
    'motionActive'      : [
        title    : 'Motion becomes active',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.motionSensor',
                multiple: true
            ]
        ],
        subscribe: 'motion',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'motion', '=', 'active', ctx.all as Boolean) }
    ],
    'motionInactive'    : [
        title    : 'Motion becomes inactive',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.motionSensor',
                multiple: true
            ]
        ],
        subscribe: 'motion',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'motion', '=', 'inactive', ctx.all as Boolean) }
    ],
    'notpresent'        : [
        title    : 'Presence sensor becomes not present',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.presenceSensor',
                multiple: true
            ]
        ],
        subscribe: 'presence',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'presence', '=', 'not present', ctx.all as Boolean) }
    ],
    'present'           : [
        title    : 'Presence sensor becomes present',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.presenceSensor',
                multiple: true
            ]
        ],
        subscribe: 'presence',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'presence', '=', 'present', ctx.all as Boolean) }
    ],
    'smoke'             : [
        title    : 'Smoke is detected',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.smokeDetector',
                multiple: true
            ]
        ],
        subscribe: 'smoke',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'smoke', '=', 'detected', ctx.all as Boolean) }
    ],
    'switchOff'         : [
        title    : 'Switch turns off',
        attribute: 'switch',
        value    : 'off',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.switch',
                multiple: true
            ]
        ],
        subscribe: 'switch',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'switch', '=', 'off', ctx.all as Boolean) }
    ],
    'switchOn'          : [
        title    : 'Switch turns on',
        attribute: 'switch',
        value    : 'on',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.switch',
                multiple: true
            ]
        ],
        subscribe: 'switch',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'switch', '=', 'on', ctx.all as Boolean) }
    ],
    'timeBefore'        : [
        title   : 'Time is before',
        template: { ctx -> "Time is before ${ctx.value}" },
        inputs  : [
            value: [
                title: 'Before Time',
                type : 'time',
                width: 2
            ]
        ],
        test    : { Map ctx -> new Date() < timeToday(ctx.value as String) }
    ],
    'timeAfter'         : [
        title   : 'Time is after',
        template: { Map ctx -> "Time is after ${ctx.value}" },
        inputs  : [
            value: [
                title: 'After Time',
                type : 'time',
                width: 2
            ]
        ],
        execute : { Map ctx -> runOnce(getNextTime(new Date(), ctx.value as String), 'timeAfterTrigger') },
        test    : { Map ctx -> new Date() >= timeToday(ctx.value as String) }
    ],
    'unlocked'          : [
        title    : 'Lock unlocks',
        attribute: 'lock',
        value    : 'unlocked',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.lock',
                multiple: true
            ]
        ],
        subscribe: 'lock',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'lock', '=', 'unlocked', ctx.all as Boolean) }
    ],
    'variable'          : [
        title    : 'Variable',
        template : { Map ctx -> "Variable '${ctx.choice}' is ${ctx.comparison} ${ctx.value}" },
        inputs   : [
            choice    : [
                options: { Map ctx -> getAllGlobalVars().keySet() }
            ],
            comparison: [
                options: { Map ctx -> getComparisonsByType(getGlobalVar(ctx.choice as String)?.type as String) }
            ],
            value     : [
                title  : 'Variable Value',
                options: { Map ctx -> getGlobalVar(ctx.choice)?.type == 'boolean' ? ['true': 'True', 'false': 'False'] : null }
            ]
        ],
        subscribe: { Map ctx -> "variable:${ctx.choice}" },
        test     : { Map ctx -> evaluateComparison(ctx.event.value as String, ctx.value as String, ctx.comparison as String) }
    ],
    'waterDry'          : [
        title    : 'Water sensor becomes dry',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.waterSensor',
                multiple: true
            ]
        ],
        subscribe: 'water',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'water', '=', 'dry', ctx.all as Boolean) }
    ],
    'waterWet'          : [
        title    : 'Water sensor becomes wet',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.waterSensor',
                multiple: true
            ]
        ],
        subscribe: 'water',
        test     : { Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'water', '=', 'wet', ctx.all as Boolean) }
    ],
].sort { a, b -> a.value.title <=> b.value.title }

// Hubitat wrapper methods to allow for calls from functions with @CompileStatic attribute
private String getCurrentMode() { return (String) location.getCurrentMode().getName() }
private String getHsmStatus() { return (String) location.hsmStatus }
private Object getHubVariableValue(String name) { return getGlobalVar(name)?.value }
private Object getSetting(String name, Object defaultValue = null) {
    return settings.containsKey(name) ? settings.get(name) : defaultValue
}
private Map<String, Object> getAppSettings() { return settings }
private Boolean getSettingBoolean(String name, Boolean defaultValue = false) {
    return settings.containsKey(name) ? settings.get(name).toBoolean() : defaultValue
}
private String getSettingString(String name, String defaultValue = '') {
    return settings.containsKey(name) ? settings.get(name).toString() : defaultValue
}
private Integer getSettingInteger(String name, Integer defaultValue = null) {
    return settings.containsKey(name) ? settings.get(name).toInteger() : defaultValue
}
private Map getState() { return state }
private Object getState(String name, Object defaultValue = null) {
    return state.containsKey(name) ? state.get(name) : defaultValue
}
private Object getLocation() { return location }
private long getTimeMs(int offset = 0) { return (long) now() + offset }
private void logDebug(String s) {
    if (settings.logEnable) {
        log.debug s
    }
}
private void logError(String s) { log.error app.label + ' ' + s }
private void logInfo(String s) { log.info app.label + ' ' + s }
private void logWarn(String s) { log.warn app.label + ' ' + s }
private void removeSetting(String s) { app.removeSetting(s) }
private void removeState(String s) { state.remove(s) }
private void runAfterMs(long delay, String handler) { runInMillis(delay, handler) }
private void setState(String name, Object value) { state.put(name, value) }
