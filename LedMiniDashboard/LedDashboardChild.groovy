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
 *  1.00 - Add support for illuminence value comparison conditions
 *  1.01 - Bug fix for sunrise/sunset
 *  1.02 - Replaced sunrise/sunset conditions with new single option
 *  1.03 - Add cool down period support for condition
 *
*/

@Field static final String Version = '1.03'

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
    page(name: 'editConditionPage', previousPage: 'mainPage')
}

import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher

// Tracker for device LED state to optimize traffic by only sending changes
@Field static final Map<String, Map> SwitchStateTracker = new ConcurrentHashMap<>()

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
    SwitchStateTracker.clear()

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
    // Reminder - Do not put this above the paused state check!
    state.clear()

    // Subscribe to events from supported Inovelli switches
    subscribeAllSwitches()

    // Subscribe to events from all conditions
    subscribeAllConditions()

    // Subscribe to global variables from all conditions
    subscribeAllVariables()

    // Dispatch the current notifications
    runInMillis(200, 'notificationDispatcher')

    if (settings.periodicRefresh) {
        logInfo "enabling periodic forced refresh every ${settings.periodicRefreshInterval} minutes"
        int seconds = 60 * (settings.periodicRefreshInterval ?: 60)
        runIn(seconds, 'forceRefresh')
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

/********************************************************************
 * START OF USER INTERFACE SECTION
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
        Map switchType = getTargetSwitchType()
        section {
            input name: 'deviceType', title: '', type: 'enum',
                description: '<b>Select the target device type</b> <i>(one type per mini-dashboard)</i>',
                options: SupportedSwitchTypes.collectEntries { dt -> [ dt.key, dt.value.title ] },
                multiple: false, required: true, submitOnChange: true, width: 10

            if (state.paused) {
                input name: 'resume', title: 'Resume', type: 'button', width: 1
            } else {
                input name: 'pause', title: 'Pause', type: 'button', width: 1
            }

            if (switchType) {
                input name: 'switches',
                    title: "Select ${settings['deviceType']} devices to include in mini-dashboard",
                    type: switchType.type,
                    required: true, multiple: true, submitOnChange: true, width: 10
            }
        }

        if (switchType && settings['switches']) {
            Set<String> prefixes = getSortedConditionPrefixes()
            section("<h3 style=\'color: #1A77C9; font-weight: bold\'>${app.label} Activation Conditions</h3>") {
                for (String conditionPrefix in prefixes) {
                    String name = settings["${conditionPrefix}_name"]
                    boolean isActive = evaluateAllConditions(conditionPrefix)
                    String status = isActive ? ' &#128994;' : ''
                    Long delayUntil = state["${conditionPrefix}_delay"] as Long
                    Long cooldownUntil = state["${conditionPrefix}_cooldown"] as Long
                    if (isActive && delayUntil > now()) {
                        int minutes = Math.ceil((delayUntil - now()) / 60000)
                        status = " &#128993; <span style=\'font-style: italic\'>(< ${minutes}m delay)</span>"
                    } else if (!isActive && cooldownUntil > now()) {
                        isActive = true
                        int minutes = Math.ceil((cooldownUntil - now()) / 60000)
                        status = " &#128993; <span style=\'font-style: italic\'>(< ${minutes}m cooldown)</span>"
                    }
                    href(
                        name: "edit_${conditionPrefix}",
                        title: "<b>${name}</b>${status}",
                        description: getConditionDescription(conditionPrefix),
                        page: 'editConditionPage',
                        params: [ conditionPrefix: conditionPrefix ],
                        state: isActive ? 'complete' : '',
                        width: 10
                    )
                    input name: 'remove_' + conditionPrefix,
                        title: '<i style="font-size:1rem; color:red;" class="material-icons he-bin"></i>',
                        type: 'button',
                        width: 1
                }

                href(
                    name: 'addDashboard',
                    title: '<i>Select to add a new activation condition</i>',
                    description: '',
                    params: [ prefix: findNextPrefix() ],
                    page: 'editConditionPage',
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

Map editConditionPage(Map params = [:]) {
    String conditionPrefix = params.conditionPrefix
    if (!conditionPrefix) { return mainPage() }
    String name = settings["${conditionPrefix}_name"] ?: 'New Activation Condition'

    return dynamicPage(name: 'editConditionPage', title: "<h3 style=\'color: #1A77C9; font-weight: bold\'>${name}</h3><br>") {
        renderIndicationSection(conditionPrefix)
        String effectName = getEffectName(conditionPrefix)
        if (effectName) {
            renderConditionsSection(conditionPrefix, "<span style=\'color: green; font-weight: bold\'>Select means to activate LED ${effectName} effect:</span><span class=\"required-indicator\">*</span>")

            if (settings["${conditionPrefix}_conditions"]) {
                section {
                    input name: "${conditionPrefix}_delay", title: '<i>For at least (minutes):</i>', description: '1..60', type: 'decimal', width: 3, range: '0..60', required: false
                    if (settings["${conditionPrefix}_effect"] != '255') {
                        String title = 'When conditions stop matching '
                        title += settings["${conditionPrefix}_autostop"] == false ? '<i>leave effect running</i>' : '<b>stop the effect</b>'
                        input name: "${conditionPrefix}_autostop", title: title, type: 'bool', defaultValue: true, width: 4, submitOnChange: true
                    } else {
                        app.removeSetting("${conditionPrefix}_autostop")
                    }
                    if (settings["${conditionPrefix}_autostop"]) {
                        input name: "${conditionPrefix}_cooldown", title: '<i>Cooldown period (minutes):</i>', description: '1..60', type: 'decimal', width: 3, range: '0..60', required: false
                    } else {
                        app.removeSetting("${conditionPrefix}_cooldown")
                    }
                }

                section {
                    input name: "${conditionPrefix}_name", title: '<b>Activation Condition Name:</b>', type: 'text', defaultValue: getSuggestedConditionName(conditionPrefix), width: 7, required: true, submitOnChange: true
                    input name: "${conditionPrefix}_priority", title: '<b>Priority:</b>', type: 'enum', options: getPrioritiesList(conditionPrefix), width: 2, required: true
                    paragraph '<i>Higher value condition priorities take LED precedence.</i>'
                    input name: "test_${conditionPrefix}", title: 'Test', type: 'button', width: 2
                    input name: 'reset', title: 'Reset', type: 'button', width: 2
                }
            }
        }
    }
}

Map renderIndicationSection(String conditionPrefix, String title = null) {
    Map switchType = getTargetSwitchType()
    String ledNumber = settings["${conditionPrefix}_lednumber"]
    String ledName = switchType.leds[settings[ledNumber]] ?: 'LED'

    return section(title) {
        // LED Number
        input name: "${conditionPrefix}_lednumber", title: '<span style=\'color: blue;\'>LED Number</span>', type: 'enum', options: switchType.leds, width: 3, required: true, submitOnChange: true
        if (settings["${conditionPrefix}_lednumber"] == 'var') {
            input name: "${conditionPrefix}_lednumber_var", title: "<span style=\'color: blue;\'>LED Number Variable</span>", type: 'enum', options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
        } else {
            app.removeSetting("${conditionPrefix}_lednumber_var")
        }

        // Effect
        if (ledNumber) {
            Map<String, String> fxOptions = ledNumber == 'All' ? switchType.effectsAll : switchType.effects
            input name: "${conditionPrefix}_effect", title: "<span style=\'color: blue;\'>${ledName} Effect</span>", type: 'enum',
                options: fxOptions, width: 2, required: true, submitOnChange: true
            if (settings["${conditionPrefix}_effect"] == 'var') {
                input name: "${conditionPrefix}_effect_var", title: "<span style=\'color: blue;\'>Effect Variable</span>", type: 'enum',
                    options: getGlobalVarsByType('string').keySet(), width: 3, required: true
            } else {
                app.removeSetting("${conditionPrefix}_effect_var")
            }

            // Color
            String effect = settings["${conditionPrefix}_effect"]
            if (effect != '0' && effect != '255') {
                String color = settings["${conditionPrefix}_color"]
                input name: "${conditionPrefix}_color", title: "<span style=\'color: blue;\'>${ledName} Color</span>", type: 'enum', options: ColorMap, width: 3, required: true, submitOnChange: true
                if (color == 'val') {
                    String url = '''<a href="https://community-assets.home-assistant.io/original/3X/6/c/6c0d1ea7c96b382087b6a34dee6578ac4324edeb.png" target="_blank">'''
                    input name: "${conditionPrefix}_color_val", title: url + "<span style=\'color: blue; text-decoration: underline;\'>Hue Value</span></a>", type: 'number', range: '0..360', width: 2, required: true, submitOnChange: true
                } else {
                    app.removeSetting("${conditionPrefix}_color_val")
                }
                if (color == 'var') {
                    input name: "${conditionPrefix}_color_var", title: "<span style=\'color: blue;\'>Color Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
                } else {
                    app.removeSetting("${conditionPrefix}_color_var")
                }
            } else {
                app.removeSetting("${conditionPrefix}_color")
                app.removeSetting("${conditionPrefix}_color_var")
                app.removeSetting("${conditionPrefix}_color_val")
            }

            if (effect != '255') {
                // Time Unit
                input name: "${conditionPrefix}_unit", title: '<span style=\'color: blue;\'>Duration</span>', description: 'Select', type: 'enum',
                    options: TimePeriodsMap, width: 2, defaultValue: 'Infinite', required: true, submitOnChange: true
                if (settings["${conditionPrefix}_unit"] in ['0', '60', '120']) {
                    // Time Duration
                    String timePeriod = TimePeriodsMap[settings["${conditionPrefix}_unit"]]
                    input name: "${conditionPrefix}_duration", title: "<span style=\'color: blue;\'>${timePeriod}&nbsp;</span>", type: 'enum', width: 2, defaultValue: 1, required: true,
                        options: [ '1', '2', '3', '4', '5', '10', '15', '20', '25', '30', '40', '50', '60' ]
                } else {
                    app.removeSetting("${conditionPrefix}_duration")
                }
            } else {
                app.removeSetting("${conditionPrefix}_unit")
                app.removeSetting("${conditionPrefix}_duration")
            }

            if (effect != '0' && effect != '255') {
                // Level
                input name: "${conditionPrefix}_level", title: "<span style=\'color: blue;\'>Level&nbsp;</span>", type: 'enum', width: 2,
                    defaultValue: 100, options: LevelMap, required: true, submitOnChange: true
                if (settings["${conditionPrefix}_level"] == 'val') {
                    input name: "${conditionPrefix}_level_val", title: "<span style=\'color: blue;\'>Level Value&nbsp;</span>", type: 'number',
                        range: '1..100', width: 2, required: true, submitOnChange: true
                } else {
                    app.removeSetting("${conditionPrefix}_level_val")
                }
                if (settings["${conditionPrefix}_level"] == 'var') {
                    input name: "${conditionPrefix}_level_var", title: "<span style=\'color: blue;\'>Level Variable</span>", type: 'enum',
                        options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
                } else {
                    app.removeSetting("${conditionPrefix}_level_var")
                }
            } else {
                app.removeSetting("${conditionPrefix}_level")
                app.removeSetting("${conditionPrefix}_level_var")
                app.removeSetting("${conditionPrefix}_level_val")
            }
            paragraph ''
        }
    }
}

Map renderConditionsSection(String conditionPrefix, String sectionTitle = null, Map<String, Map> ruleDefinitions = ConditionsMap) {
    return section(sectionTitle) {
        Map<String, String> conditionTitles = ruleDefinitions.collectEntries { String k, Map v -> [ k, v.title ] }
        List<String> activeConditions = settings["${conditionPrefix}_conditions"] ?: []
        input name: "${conditionPrefix}_conditions", title: '', type: 'enum', options: conditionTitles, multiple: true, required: true, width: 9, submitOnChange: true

        Boolean allConditionsMode = settings["${conditionPrefix}_conditions_all"] ?: false
        if (settings["${conditionPrefix}_conditions"]?.size() > 1) {
            String title = "${allConditionsMode ? '<b>All</b> conditions' : '<b>Any</b> condition'}"
            input name: "${conditionPrefix}_conditions_all", title: title, type: 'bool', width: 3, submitOnChange: true
        } else {
            paragraph ''
        }

        boolean isFirst = true
        Map<String, Map> activeConditionRules = ruleDefinitions.findAll { String k, Map v -> k in activeConditions }
        for (Map.Entry<String, Map> condition in activeConditionRules) {
            String id = "${conditionPrefix}_${condition.key}"
            String status = evaluateCondition(id, condition.value) ? ' &#128994;' : ''
            if (!isFirst) {
                paragraph allConditionsMode ? '<b>and</b>' : '<i>or</i>'
            }
            isFirst = false
            Map<String, Map> inputs = condition.value.inputs
            if (inputs.device) {
                input name: "${id}_device",
                    title: (inputs.device.title ?: condition.value.title) + status,
                    type: inputs.device.type,
                    width: inputs.device.width ?: 7,
                    multiple: inputs.device.multiple,
                    submitOnChange: true,
                    required: true
                status = ''
                if (!inputs.device.any && settings["${id}_device"] in Collection && settings["${id}_device"]?.size() > 1) {
                    String name = inputs.device.title ?: condition.value.title
                    input name: "${id}_device_all",
                        title: settings["${id}_device_all"] ? "<b>All</b> ${name} devices" : "<b>Any</b> ${name} device",
                        type: 'bool', submitOnChange: true, width: 4
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
                        title: (inputs.choice.title ?: condition.value.title) + status,
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
                    submitOnChange: true, required: true
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
            Map<String, String> config = getConditionConfig(prefix)
            replaceVariables(config)
            updateSwitchLedState(config)
            break
        default:
            logWarn "unknown app button ${buttonName}"
            break
    }
}

// Returns available priorities based on lednumber for display in dropdown
Map<String, String> getPrioritiesList(String conditionPrefix) {
    String ledNumber = settings["${conditionPrefix}_lednumber"]
    if (ledNumber == 'var') {
        Map switchType = getTargetSwitchType()
        lednumber = lookupVariable(settings["${conditionPrefix}_lednumber_var"], switchType.leds) ?: 'All'
    }
    Set<Integer> usedPriorities = (getConditionPrefixes() - conditionPrefix)
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

// Utility method for displaying CSS colored text
String getColorSpan(Integer hue, String text) {
    if (hue != null && text) {
        String css = (hue == 360) ? 'white' : "hsl(${hue}, 50%, 50%)"
        return "<span style=\'color: ${css}\'>${text}</span>"
    }
    return 'n/a'
}

// Creates a description string for the dashboard configuration for display
String getConditionDescription(String conditionPrefix) {
    Map config = getConditionConfig(conditionPrefix)
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
        List<String> conditions = config.conditions
            .findAll { String condition -> ConditionsMap.containsKey(condition) }
            .collect { String condition ->
                Map ctx = [
                    device: config["${condition}_device"],
                    title: ConditionsMap[condition].title,
                    comparison: config["${condition}_comparison"],
                    choice: config["${condition}_choice"],
                    value: config["${condition}_value"],
                    delay: config.delay,
                    cooldown: config.cooldown
                ]
                if (ctx.device) {
                    if (ctx.device.size() > 2) {
                        String title = ConditionsMap[condition].inputs.device.title.toLowerCase()
                        ctx.device = "${ctx.device.size()} ${title}"
                        ctx.device = (config["${c}_device_all"] ? 'All ' : 'Any of ') + ctx.device
                    } else {
                        boolean isAll = config["${condition}_device_all"]
                        ctx.device = ctx.device*.toString().join(isAll ? ' & ' : ' or ')
                    }
                }
                if (ctx.comparison) {
                    ctx.comparison = getComparisonsByType('number').get(ctx.comparison)?.toLowerCase()
                }
                if (ctx.choice != null) {
                    Map choiceInput = ConditionsMap[condition].inputs.choice
                    Object options = choiceInput.options
                    if (choiceInput.options in Closure) {
                        options = runClosure(choiceInput.options as Closure, [ device: config["${condition}_device"] ]) ?: [:]
                    }
                    if (options in Map && config["${condition}_choice"] in List) {
                        ctx.choice = config["${condition}_choice"].collect { String key -> options[key] ?: key }.join(' or ')
                    } else if (options in Map) {
                        ctx.choice = options[config["${condition}_choice"]]
                    }
                }
                if (ctx.value =~ /^([0-9]{4})-/) { // special case for time format
                    ctx.value = new Date(timeToday(value).time).format('hh:mm a')
                }
                if (ConditionsMap[condition].template) {
                    return runClosure(ConditionsMap[condition].template as Closure, ctx)
                }
                return ConditionsMap[condition].title + ' <i>(' + ctx.device + ')</i>'
            }
        String allMode = config.conditions_all ? ' and ' : ' or '
        sb << "\n<b>Activation${conditions.size() > 1 ? 's' : ''}:</b> ${conditions.join(allMode)}"
    }

    return sb.toString()
}

String getEffectName(String conditionPrefix) {
    Map switchType = getTargetSwitchType()
    String ledKey = settings["${conditionPrefix}_lednumber"]
    Map<String, String> fxOptions = ledKey == 'All' ? switchType.effectsAll : switchType.effects
    String fxKey = settings["${conditionPrefix}_effect"]
    return fxOptions[fxKey]
}

String getSuggestedConditionName(String conditionPrefix) {
    Map config = getConditionConfig(conditionPrefix)
    Map switchType = getTargetSwitchType()
    StringBuilder sb = new StringBuilder('Set ')

    if (config.lednumber && config.lednumber != 'var') {
        sb << switchType.leds[config.lednumber] ?: 'n/a'
    } else {
        sb << 'LED'
    }
    if (config.color && config.color != 'var' && config.color != 'val') {
        sb << ' to '
        String effectName = getEffectName(conditionPrefix)
        if (effectName) {
            sb << "${effectName} "
        }
        sb << ColorMap[config.color]
    }

    List<String> conditions = config.conditions
        .findAll { String condition -> ConditionsMap.containsKey(condition) }
        .collect { String condition -> ConditionsMap[condition].title + (config["${condition}_all"] ? ' <i>(All)</i>' : '') }
    if (conditions) {
        sb << " when ${conditions[0]}"
    }

    return sb.toString()
}

// Updates the app label based on pause state
void updatePauseLabel() {
    if (state.paused && !app.label?.endsWith(pauseText)) {
        app.updateLabel(app.label + pauseText)
    } else if (app.label?.endsWith(pauseText)) {
        app.updateLabel(app.label - pauseText)
    }
}

/**** END USER INTERFACE *********************************************************************/

/*
 * Common event handler used by all rule conditions
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

/*
 *  Track the led state changes and update the device tracker object
 *  Only supported for the Inovelli Blue LED driver
 */
void switchStateTracker(Event event) {
    Map<String, Map> tracker = switchStateTracker[event.device.id]

    switch (event.value) {
        case 'User Cleared':
            logInfo "clear notification button pushed on ${event.device}"
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
 * If forced refresh is enabled then this is called every specified
 * interval to flush the cache and push updates out. This can be
 * helpful for devices that may not reliably receive commands but
 * should not be used unless required.
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

void sunsetTrigger() {
    logInfo 'executing sunset trigger'
    notificationDispatcher()
    subscribeAllConditions()
}

void sunriseTrigger() {
    logInfo 'executing sunrise trigger'
    notificationDispatcher()
    subscribeAllConditions()
}

void timeAfterTrigger() {
    logInfo 'executing time after trigger'
    notificationDispatcher()
    subscribeAllConditions()
}

// Scheduled stop used for devices that don't have built-in timers
void stopNotification() {
    logDebug 'stopNotification called'
    Map switchType = getTargetSwitchType()
    for (DeviceWrapper device in settings['switches']) {
        Map<String, Map> tracker = getSwitchStateTracker(device)
        if (now() >= tracker['All']?.expires) {
            if (device.hasCommand('setConfigParameter')) {
                Integer param = switchType.ledLevelParam
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

/*************************************************************************/

/**
 *  Main dispatcher for setting device LED notifications
 */
void notificationDispatcher() {
    // Evaluate current dashboard condition rules
    Map<String, Boolean> dashboardResults = evaluateDashboardConditions()

    // Process any delayed conditions
    long nextEvaluationTime = evaluateDelayedConditions(dashboardResults)

    // Calculate desired LED states
    Map<String, Map> ledStates = calculateLedState(dashboardResults)

    // Dispatch each LED state to devices
    ledStates.values().each { config ->
        updateSwitchLedState(config)
        pauseExecution(200)
    }

    // Schedule the next evaluation time
    if (nextEvaluationTime > now()) {
        long delay = nextEvaluationTime - now()
        logDebug "[evaluateDashboardConditions] scheduling evaluation in ${delay}ms"
        runInMillis(delay, 'notificationDispatcher')
    }
}

/*
 *  Dashboard evaluation function responsible for iterating each condition over
 *  the dashboards and returning a map with true/false result for each dashboard prefix
 */
Map<String, Boolean> evaluateDashboardConditions() {
    return getSortedConditionPrefixes().collectEntries { prefix ->
        [ prefix, evaluateAllConditions(prefix) ]
    }
}

/*
 *  Processes dashboard evaluation results for delayed activiation and cooldown
 *  If found, changes the result to false and returns the next evaluation time
 */
long evaluateDelayedConditions(Map<String, Boolean> evaluationResults) {
    long nextEvaluationTime = 0
    for (Map.Entry<String, Boolean> result in evaluationResults) {
        String conditionPrefix = result.key
        boolean active = result.value

        // Check if delay before activation is configured
        String delayKey = "${conditionPrefix}_delay"
        if (active && settings[delayKey]) {
            int delayMs = (settings[delayKey] ?: 0) * 60000
            // Determine if delay has expired yet
            long targetTime = state.computeIfAbsent(delayKey) { k -> nowPlusOffset(delayMs) }
            if (now() < targetTime) {
                logDebug "[evaluateDelayedConditions] ${conditionPrefix} has delayed activation (${delayMs}ms)"
                evaluationResults[conditionPrefix] = false
                // calculate when we need to check again
                if (!nextEvaluationTime || nextEvaluationTime > targetTime) {
                    nextEvaluationTime = targetTime
                }
            }
        } else {
            state.remove(delayKey)
        }

        // Check if delay post activation is configured
        String cooldownKey = "${conditionPrefix}_cooldown"
        if (settings[cooldownKey]) {
            Long targetTime = state[cooldownKey]
            if (active) {
                state[cooldownKey] = -1 // mark that it has been active
            } else if (targetTime == -1) {
                int delayMs = (settings[cooldownKey] ?: 0) * 60000
                targetTime = nowPlusOffset(delayMs)
                state[cooldownKey] = targetTime // set expiration time when first inactive
                evaluationResults[conditionPrefix] = true
                logDebug "[evaluateDelayedConditions] ${conditionPrefix} has cooldown (${delayMs}ms)"
            } else if (targetTime > 0 && now() < targetTime) {
                // still in cooldown period
                evaluationResults[conditionPrefix] = true
            } else if (now() > targetTime) {
                // we are done with cooldown so remove the state
                state.remove(cooldownKey)
                logDebug "[evaluateDelayedConditions] ${conditionPrefix} has completed cooldown"
            }

            // calculate when we need to check again
            if (targetTime && (!nextEvaluationTime || nextEvaluationTime > targetTime)) {
                nextEvaluationTime = targetTime
            }
        }
    }

    return nextEvaluationTime
}

/*
 *  Calculate Notification LED States from condition results
 *  Returns a map of each LED number and the state config associated with it for actioning
 */
@CompileStatic
Map<String, Map> calculateLedState(Map<String, Boolean> results) {
    Map<String, Map> ledStates = [:]
    for (String conditionPrefix in getSortedConditionPrefixes()) {
        Map<String, String> config = getConditionConfig(conditionPrefix)
        Map<String, Map> oldState = ledStates[config.lednumber as String] ?: [:]
        int oldPriority = oldState.priority as Integer ?: 0
        if (results[conditionPrefix]) {
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

/*
 *  Private Implementation Helper Methods
 */

private static Map<String, Map> getSwitchStateTracker(DeviceWrapper dw) {
    return SwitchStateTracker.computeIfAbsent(dw.id) { k -> [:].withDefault { [:] } }
}

// Cleans settings removing entries no longer in use
private void cleanSettings() {
    for (String prefix in getConditionPrefixes()) {
        // Clean unused dashboard settings
        ConditionsMap.keySet()
            .findAll { String key -> !(key in settings["${prefix}_conditions"]) }
            .each { String key -> removeSettings("${prefix}_${key}") }

        // Clean unused variable settings
        [ 'lednumber', 'effect', 'color' ].each { var ->
            if (settings["${prefix}_${var}"] != 'var') {
                app.removeSetting("${prefix}_${var}_var")
            }
        }
    }
}

// Calculate milliseconds from Inovelli duration parameter (0-255)
// 1-60=seconds, 61-120=1-60 minutes, 121-254=1-134 hours, 255=Indefinitely
@CompileStatic
private long convertParamToMs(Integer duration) {
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
private String findNextPrefix() {
    List<Integer> keys = getConditionPrefixes().collect { String p -> p.substring(10) as Integer }
    int maxId = keys ? Collections.max(keys) : 0
    return "condition_${maxId + 1}"
}

// Returns key value map of specified condition settings
private Map<String, String> getConditionConfig(String conditionPrefix) {
    int startPos = conditionPrefix.size() + 1
    return [ 'prefix': conditionPrefix ] + settings
        .findAll { s -> s.key.startsWith(conditionPrefix + '_') }
        .collectEntries { s -> [ s.key.substring(startPos), s.value ] }
}

// Returns a set of condition prefixes
private Set<String> getConditionPrefixes() {
    return settings.keySet().findAll { String s ->
        s.matches('^condition_[0-9]+_priority$')
    }.collect { String s -> s - '_priority' }
}

// Returns condition setting prefix sorted by priority then name
private List<String> getSortedConditionPrefixes() {
    return getConditionPrefixes().collect { String conditionPrefix ->
        [
            prefix: conditionPrefix,
            name: settings["${conditionPrefix}_name"] as String,
            priority: settings["${conditionPrefix}_priority"] as Integer
        ]
    }.sort { a, b -> b.priority <=> a.priority ?: a.name <=> b.name }*.prefix
}

// Returns the active device type configuration map
private Map getTargetSwitchType() {
    Map switchType = SupportedSwitchTypes.get(settings['deviceType']) ?: [:]
    if (!switchType) {
        logError "Unable to retrieve device type map for ${settings['deviceType']}"
    }
    return switchType
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
    return lookupTable.find { String k, String v -> v.equalsIgnoreCase(value) }?.key
}

// Returns current time plus specified offset
private long nowPlusOffset(long offset = 0) {
    return now() + offset
}

// Removes all condition settings starting with prefix
private void removeSettings(String conditionPrefix) {
    Set<String> entries = settings.keySet().findAll { String s -> s.startsWith(conditionPrefix) }
    entries.each { String s -> app.removeSetting(s) }
}

// Populate configuration values with specified global variables
private void replaceVariables(Map<String, String> config) {
    Map switchType = getTargetSwitchType()
    if (switchType) {
        if (config.lednumber == 'var') {
            config.lednumber = lookupVariable(config.lednumber_var, switchType.leds) ?: 'All'
        }
        if (config.effect == 'var') {
            Map<String, String> fxOptions = switchType.effectsAll + switchType.effects
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
    Map switchType = getTargetSwitchType()
    if (switchType) {
        logInfo 'resetting all device notifications'
        switchType.leds.keySet().findAll { String s -> s != 'var' }.each { String led ->
            updateSwitchLedState(
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

// Subscribe to all dashboard conditions
@CompileStatic
private void subscribeAllConditions() {
    getConditionPrefixes().each { String conditionPrefix ->
        subscribeCondition(conditionPrefix)
    }
}

// Subscribe to switches with driver support
private void subscribeAllSwitches() {
    String type = getTargetSwitchType().type
    switch (type) {
        case ~/^device.InovelliDimmer2-in-1BlueSeries.*/:
            logDebug "subscribing to ledEffect event for ${settings.switches}"
            subscribe(settings.switches, 'ledEffect', 'switchStateTracker', null)
            break
    }
}

/**
 *  updateSwitchLedState provides a wrapper around driver specific commands
 *  for setting specific LED notifications
 */
private void updateSwitchLedState(Map config) {
    for (DeviceWrapper device in settings['switches']) {
        logDebug "setting ${device} LED #${config.lednumber} (" +
            "id=${config.prefix}, name=${config.name}, priority=${config.priority}, " +
            "effect=${config.effect ?: ''}, color=${config.color}, level=${config.level}, " +
            "duration=${config.duration ?: ''} ${TimePeriodsMap[config.unit] ?: ''})"

        Map<String, Map> tracker = getSwitchStateTracker(device)
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
            long duration = convertParamToMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
            config.expires = nowPlusOffset(duration)
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
                    hue: dw.currentValue('hue') ?: 0,
                    saturation: dw.currentValue('saturation') ?: 0,
                    level: dw.currentValue('level') ?: 0,
                ]
            }
            int huePercent = Math.round((color / 360.0) * 100)
            logDebug "${dw}.setColor(${huePercent})"
            dw.setColor([
                hue: color == 360 ? 0 : huePercent,
                saturation: color == 360 ? 0 : 100,
                level: config.level as Integer
            ])
            break
        case '255':
            if (state[key]?.level) {
                logDebug "${dw}.setColor(${state[key]})"
                dw.setColor([
                    hue: state[key].hue as Integer,
                    saturation: state[key].saturation as Integer,
                    level: state[key].level as Integer
                ])
                state.remove(key)
            } else {
                logDebug "${dw}.off()"
                dw.off()
            }
            break
    }
    if (config.unit && config.unit != '255') {
        long duration = convertParamToMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
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
}

/**
 *  updateSwitchLedStateInovelliRedGen2 is a wrapper around the
 *  Inovelli device driver startnotification method.
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
 *  Evaluates all conditions and returns a pass/fail (boolean)
 *
 *  @param prefix           The settings prefix to use (e.g. conditions_1) for persistence
 *  @param ruleDefinitions  The rule definitions to use (defaults to global ConditionsMap)
 */
private boolean evaluateAllConditions(String prefix, Map<String, Map> ruleDefinitions = ConditionsMap) {
    boolean result = false
    boolean allConditionsFlag = settings["${prefix}_conditions_all"] ?: false
    String name = settings["${prefix}_name"]

    // Loop through all conditions updating the result
    List<String> activeConditions = settings["${prefix}_conditions"] ?: []
    for (String conditionKey in activeConditions) {
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
    logDebug "[evaluateAllConditions] ${name} (${prefix}) returns ${result}"
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
    List<String> activeConditions = settings["${prefix}_conditions"] ?: []
    for (String conditionKey in activeConditions) {
        Map<String, Map> condition = ruleDefinitions[conditionKey]
        if (condition.execute in Closure) {
            String id = "${prefix}_${conditionKey}"
            Map ctx = [
                device: settings["${id}_device"],
                choice: settings["${id}_choice"],
                value: settings["${id}_value"]
            ].asImmutable()
            runClosure(condition.execute as Closure, ctx)
        }
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
            logInfo "${name} [${condition.title}] subscribing to ${ctx.device ?: 'location'} for '${attribute}'"
            subscribe(ctx.device ?: location, attribute, 'eventHandler', null)
            if (attribute.startsWith('variable:')) {
                addInUseGlobalVar(attribute.substring(9))
            }
        }
    }
}

private void subscribeAllVariables() {
    settings.findAll { s -> s.key.endsWith('_var') }.each { s ->
        logDebug "subscribing to variable ${s.value}"
        addInUseGlobalVar(s.value)
        subscribe(location, 'variable:' + s.value, 'eventHandler', null)
    }
}

// Given a set of devices, returns if the attribute has the specified value (any or all as specified)
@CompileStatic
private boolean deviceAttributeHasValue(List<DeviceWrapper> devices, String attribute, String operator, String value, Boolean all) {
    Closure test = { DeviceWrapper d -> evaluateComparison(d.currentValue(attribute) as String, value, operator) }
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
    return devices?.collectMany { DeviceWrapper d -> d.getSupportedAttributes()*.name }
}

// Given a set of devices, provides the distinct set of attribute names
@CompileStatic
private List<String> getAttributeOptions(List<DeviceWrapper> devices, String attribute) {
    return devices?.collectMany { DeviceWrapper d -> d.getSupportedAttributes().find { a -> a.name == attribute }.getValues() }
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
    Map<String, String> result = [ '=': 'Equal to', '<>': 'Not equal to' ]
    if (type.toLowerCase() in [ 'number', 'integer', 'bigdecimal' ]) {
        result += [
            '<': 'Less than',
            '<=': 'Less or equal',
            '>': 'Greater than',
            '>=': 'Greater or equal'
        ]
    }
    return result
}

private Map getAlmanac(Date now, int offset = 0) {
    Map today = getSunriseAndSunset([ sunriseOffset: offset, sunsetOffset: offset, date: now ])
    Map tomorrow = getSunriseAndSunset([ sunriseOffset: offset, sunsetOffset: offset, date: now + 1 ])
    Map next = [ sunrise: now < today.sunrise ? today.sunrise : tomorrow.sunrise, sunset: now < today.sunset ? today.sunset : tomorrow.sunset ]
    LocalTime sunsetTime = LocalTime.of(today.sunset.getHours(), today.sunset.getMinutes())
    LocalTime sunriseTime = LocalTime.of(next.sunrise.getHours(), next.sunrise.getMinutes())
    LocalTime nowTime = LocalTime.of(now.getHours(), now.getMinutes())
    boolean isNight = nowTime > sunsetTime || nowTime < sunriseTime
    Map almanac = [ today: today, tomorrow: tomorrow, next: next, isNight: isNight, offset: offset ]
    if (settings.logEnable) { log.debug "almanac: ${almanac}" }
    return almanac
}

private Date getNextTime(Date now, String datetime) {
    Date target = timeToday(datetime)
    return (now >= target) ? target + 1 : target
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

/*
 * Define the supported notification device types
 */
@Field static final Map<String, Map> SupportedSwitchTypes = [
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
        leds: [ 'All': 'RGB Color' ],
        effects: [:],
        effectsAll: [ '255': 'Stop', '0': 'Off', '1': 'On', 'var': 'Variable Effect' ]
    ]
]

// Definitions for condition options
@Field static final Map<String, String> ColorMap = [ '0': 'Red', '10': 'Orange', '40': 'Lemon', '91': 'Lime', '120': 'Green', '150': 'Teal', '180': 'Cyan', '210': 'Aqua',
    '241': 'Blue', '269': 'Violet', '300': 'Magenta', '332': 'Pink', '360': 'White', 'val': 'Custom Color', 'var': 'Variable Color' ]

@Field static final Set<Integer> Priorities = 20..1 // can be increased if desired

@Field static final Map<String, String> LevelMap = [ '10': '10', '20': '20', '30': '30', '40': '40', '50': '50',
    '60': '60', '70': '70', '80': '80', '90': '90', '100': '100', 'val': 'Custom', 'var': 'Variable' ]

@Field static final Map<String, String> TimePeriodsMap = [ '0': 'Seconds', '60': 'Minutes', '120': 'Hours', '255': 'Infinite' ]

// Defines the text used to show the application is paused
@Field static final String pauseText = '<span style=\'color: red;\'> (Paused)</span>'

@Field static final Map<String, Map> ConditionsMap = [
    'almanac': [
        title: 'Time period is between',
        template: { ctx -> "Time period is from ${ctx.choice}" },
        inputs: [
            choice: [
                title: 'Select time period',
                options: [
                    sunriseToSunset: 'Sunrise to Sunset (Day)',
                    sunsetToSunrise: 'Sunset to Sunrise (Night)'
                ],
                multiple: false,
                width: 5
            ],
            value: [
                title: 'Offset minutes',
                type: 'number',
                range: '-300..300',
                defaultValue: 0,
                width: 2
            ]
        ],
        execute: { ctx ->
            Map almanac = getAlmanac(new Date(), (ctx.value as Integer) ?: 0)
            runOnce(almanac.next.sunset, 'sunsetTrigger')
            runOnce(almanac.next.sunrise, 'sunriseTrigger')
        },
        test: { ctx ->
            Map almanac = getAlmanac(new Date(), (ctx.value as Integer) ?: 0)
            switch (ctx.choice) {
                case 'sunsetToSunrise': return almanac.isNight
                case 'sunriseToSunset': return !almanac.isNight
                default: return false
            }
        }
    ],
    'accelerationActive': [
        title: 'Acceleration becomes active',
        inputs: [
            device: [
                title: 'Acceleration Sensors',
                type: 'capability.accelerationSensor',
                multiple: true
            ]
        ],
        subscribe: 'contact',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'acceleration', '=', 'active', ctx.all) }
    ],
    'buttonPress': [
        title: 'Button is pressed',
        template: { ctx -> "${ctx.choice} is pressed <i>(${ctx.device})</i>" },
        inputs: [
            device: [
                title: 'Pushable Buttons',
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
        title: 'Contact closes',
        inputs: [
            device: [
                title: 'Contact Sensors',
                type: 'capability.contactSensor',
                multiple: true
            ]
        ],
        subscribe: 'contact',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'contact', '=', 'closed', ctx.all) }
    ],
    'contactOpen': [
        title: 'Contact opens',
        inputs: [
            device: [
                title: 'Contact Sensors',
                type: 'capability.contactSensor',
                multiple: true
            ]
        ],
        subscribe: 'contact',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'contact', '=', 'open', ctx.all) }
    ],
    'customAttribute': [
        title: 'Custom attribute',
        template: { ctx -> "${ctx.choice.capitalize()} is ${ctx.comparison} ${ctx.value} <i>(${ctx.device})</i>" },
        inputs: [
            device: [
                title: 'Select Devices',
                type: 'capability.*',
                multiple: true
            ],
            choice: [
                title: 'Select Custom Attribute',
                options: { ctx -> ctx.device ? getAttributeChoices(ctx.device) : null },
                multiple: false
            ],
            comparison: [
                options: { ctx -> getComparisonsByType('number') }
            ],
            value: [
                title: 'Attribute Value',
                options: { ctx -> ctx.device && ctx.choice ? getAttributeOptions(ctx.device, ctx.choice) : null }
            ]
        ],
        subscribe: { ctx -> ctx.choice },
        test: { ctx -> deviceAttributeHasValue(ctx.device, ctx.choice, ctx.comparison, ctx.value, ctx.all) }
    ],
    'hsmAlert': [
        title: 'HSM intrusion alert becomes',
        template: { ctx -> "HSM intrusion alert becomes ${ctx.choice}" },
        inputs: [
            choice: [
                options: [
                    'intrusion': 'Intrusion Away',
                    'intrusion-home': 'Intrusion Home',
                    'smoke': 'Smoke',
                    'water': 'Water',
                    'arming': 'Arming fail',
                    'cancel': 'Alert cancelled'
                ],
                multiple: true
            ]
        ],
        subscribe: 'hsmAlert',
        test: { ctx -> ctx.event.value in ctx.choice }
    ],
    'hsmStatus': [
        title: 'HSM arming status becomes',
        template: { ctx -> "HSM arming status becomes ${ctx.choice}" },
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
        title: 'Hub mode becomes',
        template: { ctx -> "Hub mode becomes ${ctx.choice}" },
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
        title: 'Lock is locked',
        inputs: [
            device: [
                title: 'Locks',
                type: 'capability.lock',
                multiple: true
            ]
        ],
        subscribe: 'lock',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'lock', '=', 'locked', ctx.all) }
    ],
    'luminosityAbove': [
        title: 'Illuminance rises above',
        template: { ctx -> "Illuminance rises above ${ctx.value} <i>(${ctx.device})</i>" },
        inputs: [
            device: [
                title: 'Illuminance Sensors',
                type: 'capability.illuminanceMeasurement',
                multiple: true
            ],
            value: [
                title: 'Enter Illuminance Value'
            ]
        ],
        subscribe: { 'illuminance' },
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'illuminance', '>', ctx.value, ctx.all) }
    ],
    'luminosityBelow': [
        title: 'Illuminance falls below',
        template: { ctx -> "Illuminance falls below ${ctx.value} <i>(${ctx.device})</i>" },
        inputs: [
            device: [
                title: 'Illuminance Sensors',
                type: 'capability.illuminanceMeasurement',
                multiple: true
            ],
            value: [
                title: 'Enter Illuminance Value'
            ]
        ],
        subscribe: { 'illuminance' },
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'illuminance', '<', ctx.value, ctx.all) }
    ],
    'motionActive': [
        title: 'Motion becomes active',
        inputs: [
            device: [
                title: 'Motion Sensors',
                type: 'capability.motionSensor',
                multiple: true
            ]
        ],
        subscribe: 'motion',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'motion', '=', 'active', ctx.all) }
    ],
    'motionInactive': [
        title: 'Motion becomes inactive',
        inputs: [
            device: [
                title: 'Motion Sensors',
                type: 'capability.motionSensor',
                multiple: true
            ]
        ],
        subscribe: 'motion',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'motion', '=', 'inactive', ctx.all) }
    ],
    'notpresent': [
        title: 'Presence sensor becomes not present',
        inputs: [
            device: [
                title: 'Presence Sensors',
                type: 'capability.presenceSensor',
                multiple: true
            ]
        ],
        subscribe: 'presence',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'presence', '=', 'not present', ctx.all) }
    ],
    'present': [
        title: 'Presence sensor becomes present',
        inputs: [
            device: [
                title: 'Presence Sensors',
                type: 'capability.presenceSensor',
                multiple: true
            ]
        ],
        subscribe: 'presence',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'presence', '=', 'present', ctx.all) }
    ],
    'smoke': [
        title: 'Smoke is detected',
        inputs: [
            device: [
                title: 'Smoke Detectors',
                type: 'capability.smokeDetector',
                multiple: true
            ]
        ],
        subscribe: 'smoke',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'smoke', '=', 'detected', ctx.all) }
    ],
    'switchOff': [
        title: 'Switch turns off',
        attribute: 'switch',
        value: 'off',
        inputs: [
            device: [
                title: 'Switch',
                type: 'capability.switch',
                multiple: true
            ]
        ],
        subscribe: 'switch',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'switch', '=', 'off', ctx.all) }
    ],
    'switchOn': [
        title: 'Switch turns on',
        attribute: 'switch',
        value: 'on',
        inputs: [
            device: [
                title: 'Switch',
                type: 'capability.switch',
                multiple: true
            ]
        ],
        subscribe: 'switch',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'switch', '=', 'on', ctx.all) }
    ],
    'timeBefore': [
        title: 'Time is before',
        template: { ctx -> "Time is before ${ctx.value}" },
        inputs: [
            value: [
                title: 'Before Time',
                type: 'time',
                width: 2
            ]
        ],
        test: { ctx -> new Date() < timeToday(ctx.value) }
    ],
    'timeAfter': [
        title: 'Time is after',
        template: { ctx -> "Time is after ${ctx.value}" },
        inputs: [
            value: [
                title: 'After Time',
                type: 'time',
                width: 2
            ]
        ],
        execute: { ctx -> runOnce(getNextTime(new Date(), ctx.value), 'timeAfterTrigger') },
        test: { ctx -> new Date() >= timeToday(ctx.value) }
    ],
    'unlocked': [
        title: 'Lock unlocks',
        attribute: 'lock',
        value: 'unlocked',
        inputs: [
            device: [
                name: 'Locks',
                type: 'capability.lock',
                multiple: true
            ]
        ],
        subscribe: 'lock',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'lock', '=', 'unlocked', ctx.all) }
    ],
    'variable': [
        title: 'Variable',
        template: { ctx -> "Variable '${ctx.choice}' is ${ctx.comparison} ${ctx.value}" },
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
        title: 'Water sensor becomes dry',
        inputs: [
            device: [
                title: 'Water sensors',
                type: 'capability.waterSensor',
                multiple: true
            ]
        ],
        subscribe: 'water',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'water', '=', 'dry', ctx.all) }
    ],
    'waterWet': [
        title: 'Water sensor becomes wet',
        inputs: [
            device: [
                title: 'Water sensors',
                type: 'capability.waterSensor',
                multiple: true
            ]
        ],
        subscribe: 'water',
        test: { ctx -> deviceAttributeHasValue(ctx.device, 'water', '=', 'wet', ctx.all) }
    ],
].sort { a, b -> a.value.title <=> b.value.title }
