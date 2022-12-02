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
 *  0.1 - Initial development (alpha)
 *  0.2 - Initial Beta Test release
 *  0.3 - Add condition current state feedback indicator
 *  0.4 - Add 'autostop' effect option to clear effect
 *  0.5 - Add additional device support for Inovelli Red switches and dimmers
 *  0.6 - Add additional effect types support
 *
*/

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
        title: 'Inovelli Blue Switches (7 Segments)',
        type: 'device.InovelliDimmer2-in-1BlueSeriesVZM31-SN',
        leds: [ '1': 'LED 1 (Bottom)', '2': 'LED 2', '3': 'LED 3', '4': 'LED 4', '5': 'LED 5', '6': 'LED 6', '7': 'LED 7 (Top)', 'All': 'All LEDs', 'var': 'Variable LED' ],
        effects: [ '0': 'Off', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '255': 'Stop', 'var': 'Variable Effect' ],
        effectsAll: [ '0': 'Off', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '9': 'Slow Falling', '10': 'Medium Falling', '11': 'Fast Falling', '12': 'Slow Rising', '13': 'Medium Rising', '14': 'Fast Rising', '15': 'Medium Blink', '16': 'Slow Chase', '17': 'Fast Chase', '18': 'Fast Siren', '19': 'Slow Siren', '255': 'Stop', 'var': 'Variable Effect' ],
        stopEffect: 255
    ],
    'Inovelli Blue Fan Switch': [
        title: 'Inovelli Blue Fan Switches (7 Segments)',
        type: 'device.InovelliDimmer2-in-1BlueSeriesVZM35-SN',
        leds: [ '1': 'LED 1 (Bottom)', '2': 'LED 2', '3': 'LED 3', '4': 'LED 4', '5': 'LED 5', '6': 'LED 6', '7': 'LED 7 (Top)', 'All': 'All LEDs', 'var': 'Variable LED' ],
        effects: [ '0': 'Off', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '255': 'Stop', 'var': 'Variable Effect' ],
        effectsAll: [ '0': 'Off', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '9': 'Slow Falling', '10': 'Medium Falling', '11': 'Fast Falling', '12': 'Slow Rising', '13': 'Medium Rising', '14': 'Fast Rising', '15': 'Medium Blink', '16': 'Slow Chase', '17': 'Fast Chase', '18': 'Fast Siren', '19': 'Slow Siren', '255': 'Stop', 'var': 'Variable Effect' ],
        stopEffect: 255
    ],
    'Inovelli Red Switch': [
        title: 'Inovelli Red Switches (Single Segment)',
        type: 'device.InovelliDimmerRedSeriesLZW30-SN',
        leds: [ 'All': 'Notification' ],
        effectsAll: [ '0': 'Off', '1': 'Solid', '2': 'Chase', '3': 'Fast Blink', '4': 'Slow Blink', '5': 'Pulse', 'var': 'Variable Effect' ],
        stopEffect: 0
    ],
    'Inovelli Red Dimmer': [
        title: 'Inovelli Red Dimmers (Single Segment)',
        type: 'device.InovelliDimmerRedSeriesLZW31-SN',
        leds: [ 'All': 'Notification' ],
        effectsAll: [ '0': 'Off', '1': 'Solid', '2': 'Chase', '3': 'Fast Blink', '4': 'Slow Blink', '5': 'Pulse', 'var': 'Variable Effect' ],
        stopEffect: 0
    ],
    // 'RGB': [
    //     title: 'Color Devices (Single Color)',
    //     type: 'capability.colorControl',
    //     leds: [ 'All': 'All LEDs' ],
    //     effectsAll: [ '0': 'Off', '1': 'Solid', 'var': 'Variable Effect' ],
    //     stopEffect: 0
    // ]
].asImmutable()

// Definitions for condition options
@Field static final Map<String, String> PrioritiesMap = [ '1': 'Priority 1 (low)', '2': 'Priority 2', '3': 'Priority 3', '4': 'Priority 4', '5': 'Priority 5 (medium)', '6': 'Priority 6', '7': 'Priority 7', '8': 'Priority 8', '9': 'Priority 9 (high)' ].asImmutable()
@Field static final Map<String, String> TimePeriodsMap = [ '0': 'Seconds', '60': 'Minutes', '120': 'Hours', '255': 'Indefinitely' ].asImmutable()
@Field static final Map<String, String> ColorMap = [ '0': 'Red', '10': 'Orange', '40': 'Lemon', '91': 'Lime', '120': 'Green', '150': 'Teal', '180': 'Cyan', '210': 'Aqua', '241': 'Blue', '269': 'Violet', '300': 'Magenta', '332': 'Pink', '360': 'White', 'var': 'Variable Color' ].asImmutable()

// Tracker for device LED state to optimize traffic by only sending changes
@Field static final Map<String, Map> DeviceStateTracker = new ConcurrentHashMap<>()

// Defines the text used to show the application is paused
@Field static final String pauseText = '<span style=\'color: red;\'> (Paused) </span>'

/*
 * Application Main Page
 */
Map mainPage() {
    updatePauseLabel()
    if (settings.removeSettings) {
        removeSettings(settings.removeSettings)
        app.removeSetting('removeSettings')
    }

    return dynamicPage(name: 'mainPage', title: "<h2 style=\'color: #1A77C9; font-weight: bold\'>${app.label}</h2>") {
        Map deviceType = DeviceTypeMap[settings['deviceType']] ?: [:]
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
            Set<String> prefixes = getDashboardList()
            section("<h3 style=\'color: #1A77C9; font-weight: bold\'>${app.label} Activation Conditions</h3>") {
                for (String prefix in prefixes) {
                    String name = settings["${prefix}_name"]
                    String currentResult = evaluateConditions(prefix) ? ' <span style=\'color: green\'>(active)</span>' : ''
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
                label title: 'Name this LED Mini-Dashboard Topic:', width: 9, submitOnChange: true, required: true
            }
        }
    }
}

/*
 * Dashboard Edit Page
 */
Map editPage(Map params = [:]) {
    String prefix = params.prefix
    if (!prefix) { return mainPage() }
    Map deviceType = DeviceTypeMap[settings['deviceType']] ?: [:]
    String name = settings["${prefix}_name"] ?: 'New'

    return dynamicPage(name: 'editPage', title: "<h2 style=\'color: #1A77C9; font-weight: bold\'>${name} Mini-Dashboard</h2>") {
        section {
            input name: "${prefix}_name", title: '', description: 'Mini-Dashboard Name', type: 'text', width: 6, required: true, submitOnChange: true
            input name: "${prefix}_priority", title: '', description: 'Select Priority', type: 'enum', options: PrioritiesMap, defaultValue: '5', width: 3, required: true
            paragraph '<i>Higher value priority mini-dashboards take LED precedence.</i>'
        }

        renderIndicationSection(prefix)

        String ledName = deviceType.leds[settings["${prefix}_lednumber"]] ?: 'LED'
        String effectName = deviceType.effects[settings["${prefix}_effect"]] ?: 'condition'
        renderConditionSection(prefix, "<b>Activate ${ledName} ${effectName} effect when:</b>")

        section {
            String title = 'If conditions above do not match then '
            title += settings["${prefix}_autostop"] == false ? '<i>make no change</i>' : '<b>stop the effect</b>'
            input name: "${prefix}_autostop", title: title, type: 'bool', defaultValue: true, width: 7, submitOnChange: true
        }
    }
}

Map renderIndicationSection(String prefix) {
    Map deviceType = DeviceTypeMap[settings['deviceType']] ?: [:]
    String ledNumber = settings["${prefix}_lednumber"]
    String ledName = deviceType.leds[settings[ledNumber]] ?: 'LED'

    return section('<b>Select LED mini-dashboard indication when active:</b>') {
        // LED Number
        input name: "${prefix}_lednumber", title: '<span style=\'color: blue;\'>LED Number</span>', type: 'enum', options: deviceType?.leds, defaultValue: 'All', width: 2, required: true, submitOnChange: true
        if (settings["${prefix}_lednumber"] == 'var') {
            input name: "${prefix}_lednumber_var", title: "<span style=\'color: blue;\'>LED Number Variable</span>", type: 'enum', options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
        } else {
            app.removeSetting("${prefix}_lednumber_var")
        }

        // Effect
        Map<String, String> fxOptions = ledNumber == 'All' ? deviceType?.effectsAll : deviceType?.effects
        input name: "${prefix}_effect", title: "<span style=\'color: blue;\'>${ledName} Effect</span>", type: 'enum', options: fxOptions, defaultValue: '1', width: 3, required: true, submitOnChange: true
        if (settings["${prefix}_effect"] == 'var') {
            input name: "${prefix}_effect_var", title: "<span style=\'color: blue;\'>Effect Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
        } else {
            app.removeSetting("${prefix}_effect_var")
        }

        // Color
        if (settings["${prefix}_effect"] in ['0', '255']) {
            ["${prefix}_color", "${prefix}_color_var", "${prefix}_unit", "${prefix}_duration", "${prefix}_level"].each { s -> app.removeSetting(s) }
        } else {
            input name: "${prefix}_color", title: "<span style=\'color: blue;\'>${ledName} Color</span>", type: 'enum', options: ColorMap, width: 3, defaultValue: '170', required: true, submitOnChange: true
            if (settings["${prefix}_color"] == 'var') {
                input name: "${prefix}_color_var", title: "<span style=\'color: blue;\'>Color Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
            } else {
                app.removeSetting("${prefix}_color_var")
            }

            // Time Unit
            input name: "${prefix}_unit", title: '<span style=\'color: blue;\'>Duration</span>', description: 'Select', type: 'enum', options: TimePeriodsMap, width: 2, defaultValue: 'Indefinitely', required: true, submitOnChange: true
            if (settings["${prefix}_unit"] in ['0', '60', '120']) {
                // Time Duration
                String timePeriod = TimePeriodsMap[settings["${prefix}_unit"]]
                input name: "${prefix}_duration", title: "<span style=\'color: blue;\'># ${timePeriod}&nbsp;</span>", description: '1..60', type: 'number', width: 2, defaultValue: 1, range: '1..60', required: true
            } else {
                app.removeSetting("${prefix}_duration")
            }

            // Level
            input name: "${prefix}_level", title: "<span style=\'color: blue;\'>Level&nbsp;</span>", description: '1..100', type: 'number', width: 1, defaultValue: 100, range: '1..100', required: true
        }
        paragraph ''
    }
}

/*
 * Event Handlers (methods invoked by the Hub)
 */

// Invoked when a button input in the UI is pressed
void appButtonHandler(String buttonName) {
    switch (buttonName) {
        case 'pause':
            state.paused = true
            updated()
            break
        case 'resume':
            state.paused = false
            updated()
            break
        case ~/^remove_(.+)/:
            String prefix = Matcher.lastMatcher[0][1]
            removeSettings(prefix)
            break
        default:
            log.warn "unknown app button ${buttonName}"
            break
    }
}

// Invoked when the app is first created.
void installed() {
    log.info "${app.name} child installed"
    subscribeAllSwitches()
    subscribeAllConditions()
}

// Invoked by the hub when a global variable is renamed
void renameVariable(String oldName, String newName) {
    settings.findAll { s -> s.key.endsWith('_var') && s.value == oldName }.each { s ->
        log.info "changing ${s.key} from ${oldName} to ${newName}"
        s.value = newName
    }
}

// Invoked when the app is removed.
void uninstalled() {
    unsubscribe()
    removeAllInUseGlobalVar()
    resetNotifications()
    log.info "${app.name} uninstalled"
}

// Invoked when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    DeviceStateTracker.clear()
    cleanSettings()

    unsubscribe()
    if (state.paused) {
        resetNotifications()
    } else {
        subscribeAllSwitches()
        subscribeAllConditions()
        resetNotifications()
        updateAllDeviceLedStates()
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
    logEvent(event)
    state[event.name] = event.value
    updateAllDeviceLedStates(event)
}

// For Inovelli Blue devices track the led state changes and update the device tracker
void deviceStateTracker(Event event) {
    switch (event.value) {
        case 'User Cleared':
            DeviceStateTracker.remove(event.device.id)
            log.info "clearing all LED tracking for ${event.device}"
            break
        case 'Stop All':
            Map<String, Map> tracker = DeviceStateTracker[event.device.id]
            if (tracker) {
                tracker.remove('All')
                log.info "cleared LED tracking for ${event.device} All LED"
            }
            break
        case ~/^Stop LED(\d)$/:
            Map<String, Map> tracker = DeviceStateTracker[event.device.id]
            if (tracker) {
                String led = Matcher.lastMatcher[0][1]
                tracker.remove(led)
                log.info "cleared LED tracking for ${event.device} LED${led}"
            }
            break
    }
}

/*
 * Internal Methods
 */

/*
 *  Calculate Notification LED States
 *  Evaluates the dashboard rules with priorities and determines the resulting led state
 *  Returns a map of each LED number and the state config associated with it for actioning
 */
private Map<String, Map> calculateLedState(Event event = null) {
    Map<String, Map> ledStates = [:]
    for (String prefix in getDashboardList()) {
        Map<String, String> config = getDashboardConfig(prefix)
        Map<String, Map> oldState = ledStates[config.lednumber as String] ?: [:]
        Map deviceType = DeviceTypeMap[settings['deviceType']] ?: [:]
        int oldPriority = oldState.priority as Integer ?: 0
        if (evaluateConditions(prefix, event)) {
            replaceVariables(config)
            int newPriority = config.priority as Integer ?: 0
            if (newPriority >= oldPriority) {
                ledStates[config.lednumber as String] = config
            }
        } else if (config.autostop != false && !oldPriority) {
            // Auto stop effect
            ledStates[config.lednumber as String] = [
                prefix: config.prefix,
                name: config.name,
                lednumber: config.lednumber,
                priority: 0, // lowest priority
                effect: deviceType.stopEffect
            ]
        }
    }
    return ledStates
}

// Cleans settings removing entries no longer in use
private void cleanSettings() {
    // Clean unused variable settings
    [ 'lednumber', 'effect', 'color' ].each { var ->
        if (settings["${prefix}_${var}"] != 'var') {
            app.removeSetting("${prefix}_${var}_var")
        }
    }

    // Clean unused dashboard settings
    for (String prefix in getDashboardList()) {
        conditionsMap.keySet()
            .findAll { key -> !(key in settings["${prefix}_conditions"]) }
            .each { key -> removeSettings("${prefix}_${key}") }
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

// Creates a description string for the dashboard configuration for display
private String getDashboardDescription(String prefix) {
    Map config = getDashboardConfig(prefix)
    Map deviceType = DeviceTypeMap[settings['deviceType']] ?: [:]
    StringBuilder sb = new StringBuilder()
    if (config.lednumber && config.lednumber != 'var') {
        sb << "<b>${deviceType?.leds[config.lednumber] ?: 'n/a'}</b>"
    } else if (config.lednumber == 'var') {
        sb << "<b>LED Variable:</b> <i>${config.lednumber_var}</i>"
    }
    sb << ", <b>Priority</b>: ${config.priority}"
    if (config.effect && config.effect != 'var') {
        sb << ", <b>Effect:</b> ${deviceType?.effects[config.effect] ?: 'n/a' }"
    } else if (config.effect == 'var') {
        sb << ", <b>Effect Variable</b>: <i>${config.effect_var}</i>"
    }
    if (config.color && config.color != 'var') {
        sb << ", <b>Color</b>: ${getColorSpan(config.color as Integer, ColorMap[config.color])}"
    } else if (config.color == 'var') {
        sb << ", <b>Color Variable:</b> <i>${config.color_var}</i>"
    }
    if (config.level) {
        sb << ", <b>Level:</b> ${config.level}%"
    }
    if (config.duration && config.unit) {
        sb << ", <b>Duration:</b> ${config.duration} ${TimePeriodsMap[config.unit]?.toLowerCase()}"
    }
    if (config.conditions) {
        String allMode = config.conditions_all ? ' and ' : ' or '
        List<String> conditions = config.conditions
            .findAll { c -> conditionsMap.containsKey(c) }
            .collect { c -> conditionsMap[c].title + (config["${c}_all"] ? ' <i>(All)</i>' : '') }
        sb << "\n<b>Activation${conditions.size() > 1 ? 's' : ''}:</b> ${conditions.join(allMode)}"
        if (config.autostop != false) {
            sb << ' (Autostop)'
        }
    }
    return sb.toString()
}

// Returns dashboard setting prefix sorted by priority (descending)
private Set<String> getDashboardList() {
    return settings.keySet()
        .findAll { s -> s.matches('^condition_[0-9]+_priority$') }
        .sort { s -> settings[s] as int }
        .collect { s -> s - '_priority' }
        .reverse()
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
    List<Integer> keys = getDashboardList().collect { p -> p.substring(10) as Integer }
    int maxId = keys ? Collections.max(keys) : 0
    return "condition_${maxId + 1}"
}

// Logs the received event
private void logEvent(Event event) {
    log.info "event ${event.name} received from ${event.device ?: event.source} (value ${event.value})"
}

// Logs information
private void logWarn(String s) {
    log.warn s
}

// Logs information
private void logInfo(String s) {
    log.info s
}

// Looks up a variable in the given lookup table and returns the key if found
private String lookupVariable(String variableName, Map<String, String> lookupTable) {
    String globalVar = getGlobalVar(variableName)?.value
    String value = globalVar?.toLowerCase()
    return lookupTable.find { k, v -> v.toLowerCase() == value }?.key
}

// Populate configuration values with specified global variables
private void replaceVariables(Map<String, String> config) {
    Map deviceType = DeviceTypeMap[settings['deviceType']] ?: [:]
    if (deviceType) {
        if (config.lednumber == 'var') {
            config.lednumber = lookupVariable(config.lednumber_var, deviceType.leds) ?: 'All'
        }
        if (config.effect == 'var') {
            Map<String, String> fxOptions = deviceType.leds == 'All' ? deviceType.effectsAll : deviceType.effects
            config.effect = lookupVariable(config.effect_var, fxOptions) ?: '1'
        }
        if (config.color == 'var') {
            config.color = lookupVariable(config.color_var, ColorMap) ?: '170'
        }
    }
}

// Set all switches to stop
private void resetNotifications() {
    Map deviceType = DeviceTypeMap[settings['deviceType']]
    if (deviceType) {
        deviceType.leds.keySet().findAll { s -> s != 'var' }.each { led ->
            updateDeviceLedState([ lednumber: led, effect: deviceType.stopEffect ?: 0 ])
        }
    }
}

// Removes all condition settings starting with prefix
private void removeSettings(String prefix) {
    Set<String> entries = settings.keySet().findAll { s -> s.startsWith(prefix) }
    entries.each { s -> app.removeSetting(s) }
}

// Subscribe to all dashboard conditions
private void subscribeAllConditions() {
    for (String prefix in getDashboardList()) {
        subscribeCondition(prefix)
    }
}

private void subscribeAllSwitches() {
    String type = DeviceTypeMap[settings.deviceType]?.type
    switch (type) {
        case ~/^device.InovelliDimmer2-in-1BlueSeries.*/:
            log.info "subscribing to ledEffect event for ${settings.switches}"
            subscribe(settings.switches, 'ledEffect', 'deviceStateTracker', null)
            break
    }
}

/**
 *  Main entry point for changing LED notifications on devices
 */
private void updateAllDeviceLedStates(Event event = null) {
    // Calculate led state and sends device led update notifications
    Map<String, Map> ledStates = calculateLedState(event)
    if (ledStates) {
        // Sending individual LED updates
        ledStates.values().each { config ->
            updateDeviceLedState(config)
            pauseExecution(200)
        }
    }
}

/**
 *  updateDeviceLedState is a wrapper around driver specific commands for setting specific LED notifications
 */
private void updateDeviceLedState(Map config) {
    for (DeviceWrapper device in settings['switches']) {
        logInfo "setting ${device} LED #${config.lednumber} (" +
            "id=${config.prefix}, name=${config.name}, priority=${config.priority}, " +
            "effect=${config.effect}, color=${config.color}, level=${config.level}, " +
            "duration=${config.duration} ${TimePeriodsMap[config.unit]})"

        if (device.hasCommand('ledEffectOne')) {
            updateDeviceLedStateInovelliBlue(device, config)
        } else if (device.hasCommand('startNotification')) {
            updateDeviceLedStateInovelliRed(device, config)
        } else if (device.hasCommand('setColor')) {
            updateDeviceLedStateColor(device, config)
        } else {
            logWarn "unable to determine notification command for ${device}"
        }
    }
}

/**
 *  updateDeviceLedStateInovelliBlue is a wrapper around the Inovelli device ledEffect driver methods
 *  The wrapper uses the trackingState to reduce the Zigbee traffic by checking the
 *  assumed LED state before sending changes.
 */
private void updateDeviceLedStateInovelliBlue(DeviceWrapper dw, Map config) {
    Map<String, Map> tracker = DeviceStateTracker.computeIfAbsent(dw.id) { k -> [:].withDefault { [:] } }
    String key = config.lednumber
    if (tracker[key].effect != config.effect
        || tracker[key].color != config.color
        || tracker[key].level != config.level
        || tracker[key].duration != config.duration
        || tracker[key].expires <= now()
    ) {
        Integer color, duration
        if (config.duration != null && config.unit != null) {
            duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255)
        }
        if (config.color != null) {
            color = Math.min(Math.round(((config.color as Integer) / 360.0) * 255), 255)
        }
        if (config.lednumber == 'All') {
            log.debug "${dw}.ledEffectALL(${config.effect},${color},${config.level},${duration})"
            dw.ledEffectAll(config.effect, color, config.level, duration)
        } else {
            log.debug "${dw}.ledEffectONE(${config.lednumber},${config.effect},${color},${config.level},${duration})"
            dw.ledEffectOne(config.lednumber, config.effect, color, config.level, duration)
        }
        config.expires = now() + getDurationMs(duration)
        tracker[key] = config
    } else {
        log.info 'skipping update (no change to leds detected)'
    }
}

/**
 *  updateDeviceLedStateInovelliRed is a wrapper around the Inovelli device driver method of the same name
 */
private void updateDeviceLedStateInovelliRed(DeviceWrapper dw, Map config) {
    Map<String, Map> tracker = DeviceStateTracker.computeIfAbsent(dw.id) { k -> [:].withDefault { [:] } }
    String key = 'All'
    if (tracker[key].effect != config.effect
        || tracker[key].color != config.color
        || tracker[key].level != config.level
        || tracker[key].duration != config.duration
        || tracker[key].expires <= now()
    ) {
        int value = 0
        if (config.effect) {
            if (config.color) {
                value += Math.round(((config.color as int) / 360.0) * 255)
            }
            if (config.level) {
                value += ((config.level as int) * 256)
            }
            if (config.duration) {
                value += ((config.duration as int) * 65536)
            }
            if (config.effect) {
                value += ((config.effect as int) * 16777216)
            }
        }
        log.debug "startNotification(${value})"
        dw.startNotification(value)
        config.expires = now() + getDurationMs(duration)
        tracker[key] = config
    } else {
        log.info 'skipping update (no change to leds detected)'
    }
}

/**
 *  updateDeviceLedStateColor is a wrapper around the color device driver methods
 */
private void updateDeviceLedStateColor(DeviceWrapper dw, Map config) {
    if (config.color < 360) {
        int huePercent = Math.round(((config.color as int) / 360.0) * 100)
        dw.setColor([
            hue: huePercent,
            saturation: 100,
            level: config.level as Integer
        ])
    } else if (config.colr == 360) { // white
        dw.setColor([
            hue: 0,
            saturation: 0,
            level: config.level as Integer
        ])
    }
    // TODO: Duration and effect support
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
@Field static final Map<String, Map> conditionsMap = [
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
        test: { ctx -> ctx.event.pushed in ctx.choice }
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
        title: 'Custom attribute is set',
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
    'hsmStatus': [
        name: 'HSM Status',
        title: 'HSM arming status changes to',
        inputs: [
            choice: [
                options: [ 'armedAway': 'Armed Away', 'armedHome': 'Armed Home', 'disarmed': 'Disarmed' ],
                multiple: true
            ]
        ],
        subscribe: 'hsmStatus',
        test: { ctx -> ctx.state.hsmStatus in ctx.choice }
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
        test: { ctx -> ctx.state.hsmAlert in ctx.choice }
    ],
    'variable': [
        name: 'Hub variable',
        title: 'Hub variable is set',
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
        test: { ctx -> evaluateComparison(ctx.event["variable:${ctx.choice}"], ctx.value, ctx.comparison) }
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
 *  Supports tests against devices, hub (global) variables, and state values
 *  Enables any/all type options against devices or conditions
 */
private boolean evaluateConditions(String prefix, Event event = null, Map<String, Map> ruleDefinitions = conditionsMap) {
    boolean result = false
    boolean allConditionsFlag = settings["${prefix}_conditions_all"] ?: false

    // Loop through all conditions updating the result
    List<String> selectedConditions = settings["${prefix}_conditions"] ?: []
    for (String conditionKey in selectedConditions) {
        Map<String, Map> condition = ruleDefinitions[conditionKey]
        if (condition) {
            boolean testResult = evaluateCondition("${prefix}_${conditionKey}", condition, event)
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
    logInfo "${prefix}: condition returns ${result ? 'TRUE' : 'false'}"
    return result
}

/**
 *  Evaluates the provided condition configuration and returns a pass/fail (boolean)
 *  Supports tests against devices, hub (global) variables, and state values
 */
private boolean evaluateCondition(String prefix, Map condition, Event event = null) {
    if (!condition) { return false }
    String attribute
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
        all: settings["${prefix}_all"],
        attribute: attribute,
        choice: settings["${prefix}_choice"],
        comparison: settings["${prefix}_comparison"],
        device: settings["${prefix}_device"],
        event: event ? [ (event.name): event.value ] : [:],
        state: state,
        value: settings["${prefix}_value"]
    ].asImmutable()
    boolean result = runClosure(condition.test as Closure, ctx) ?: false
    logInfo "${prefix}: '${condition.title}' is ${result ? 'TRUE' : 'false'}"
    return result
}

// Subscribe to a condition (devices, location, variable etc.)
private void subscribeCondition(String prefix, Map<String, Map> ruleDefinitions = conditionsMap) {
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
        }
    }
}

// Given a set of devices, returns if the attribute has the specified value (any or all as specified)
// TODO: Support comparisons
@CompileStatic
private boolean deviceAttributeHasValue(List<DeviceWrapper> devices, String attribute, String value, Boolean all) {
    if (all) {
        return devices?.every { DeviceWrapper d -> d.currentValue(attribute) as String == value }
    }
    return devices?.any { DeviceWrapper d -> d.currentValue(attribute) as String == value }
}

// Given two strings return true if satisfied by the operator
@CompileStatic
private boolean evaluateComparison(String a, String b, String operator) {
    switch (operator) {
        case '=': return a == b
        case '<>': return a != b
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

/*
 *  Called from the application page, renders to the user interface a section to view and edit
 *  conditions and events defined in the conditionsMap above.
 *
 *  @param prefix          The settings prefix to use (e.g. conditions_1) for persistence
 *  @param sectionTitle    The section title to use
 *  @param ruleDefinitions The rule definitions to use (see conditionsMap)
 *  @returns page section
 */
private Map renderConditionSection(String prefix, String sectionTitle, Map<String, Map> ruleDefinitions = conditionsMap) {
    return section(sectionTitle) {
        Map<String, String> conditionTitles = ruleDefinitions.collectEntries { String k, Map v -> [ k, v.title ] }
        List<String> selectedConditions = settings["${prefix}_conditions"] ?: []
        input name: "${prefix}_conditions", title: '', type: 'enum', options: conditionTitles, multiple: true, submitOnChange: true, width: 9

        Boolean allConditionsMode = settings["${prefix}_conditions_all"] ?: false
        if (settings["${prefix}_conditions"]?.size() > 1) {
            String title = "${allConditionsMode ? '<b>All</b> conditions' : '<b>Any</b> condition'}"
            input name: "${prefix}_conditions_all", title: title, type: 'bool', width: 3, submitOnChange: true
        }

        boolean isFirst = true
        Map<String, Map> selectedConditionsMap = ruleDefinitions.findAll { String k, Map v -> k in selectedConditions }
        for (Map.Entry<String, Map> condition in selectedConditionsMap) {
            String id = "${prefix}_${condition.key}"
            String currentResult = evaluateCondition(id, condition.value) ? ' <span style=\'color: green\'>(true)</span>' : ''
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
