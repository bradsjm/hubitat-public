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
 *  0.2 - Beta Test
 *  0.3 - Add condition current state feedback indicator
 *  0.4 - Add 'autostop' effect option
 *
*/

definition(
    name: 'Switch LED Dashboard (Inovelli Blue Series)',
    namespace: 'nrgup',
    parent: 'nrgup:Switch LED Dashboard Manager',
    author: 'Jonathan Bradshaw',
    description: 'LED Dashboard Child for Inovelli Blue Series Switches',
    importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/SwitchLedDashboard/InovelliLedDashboardChild.groovy',
    iconUrl: '',
    iconX2Url: '',
    singleThreaded: true
)

preferences {
    page(name: 'mainPage', title: '<h2 style=\'color: #1A77C9; font-weight: bold\'>Inovelli LED Dashboard</h2>', install: true, uninstall: true)
    page(name: 'editPage', previousPage: 'mainPage')
}

import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher

// Definitions for condition options
@Field static final Map<String, String> prioritiesMap = [ '1': 'Priority 1 (low)', '2': 'Priority 2', '3': 'Priority 3', '4': 'Priority 4', '5': 'Priority 5 (medium)', '6': 'Priority 6', '7': 'Priority 7', '8': 'Priority 8', '9': 'Priority 9 (high)' ].asImmutable()
@Field static final Map<String, String> switchColorsMap = [ '0': 'Red', '7': 'Orange', '28': 'Lemon', '64': 'Lime', '85': 'Green', '106': 'Teal', '127': 'Cyan', '148': 'Aqua', '170': 'Blue', '190': 'Violet', '212': 'Magenta', '234': 'Pink', '255': 'White', 'var': 'Variable Color' ].asImmutable()
@Field static final Map<String, String> switchEffectsMap = [ '0': 'Off', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '255': 'Stop', 'var': 'Variable Effect' ].asImmutable()
@Field static final Map<String, String> switchLedsMap = [ '1': 'LED 1 (Bottom)', '2': 'LED 2', '3': 'LED 3', '4': 'LED 4', '5': 'LED 5', '6': 'LED 6', '7': 'LED 7 (Top)', 'All': 'All LEDs', 'var': 'Variable LED' ].asImmutable()
@Field static final Map<String, String> timePeriodsMap = [ '0': 'Seconds', '60': 'Minutes', '120': 'Hours', '255': 'Indefinitely' ].asImmutable()

// Inovelli Device Driver and count of LEDs on the switches
@Field static final String deviceDriver = 'InovelliDimmer2-in-1BlueSeriesVZM31-SN'
@Field static final int ledCount = 7

// Tracker for device LED state to optimize Zigbee traffic by only sending changes
@Field static final Map<String, Map> switchLedTracker = new ConcurrentHashMap<>()

@Field static final String pauseText = '<span style=\'color: red;\'> (Paused) </span>'

// Called when the app is first created.
void installed() {
    log.info "${app.name} child installed"
    subscribeSwitches()
    subscribeConditions()
}

// Called when the app is removed.
void uninstalled() {
    unsubscribe()
    removeAllInUseGlobalVar()
    resetSwitchDisplays()
    log.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    switchLedTracker.clear()
    cleanSettings()

    unsubscribe()
    if (state.paused) {
        resetSwitchDisplays()
    } else {
        subscribeSwitches()
        subscribeConditions()
    }
}

/*
 * Application Main Page
 */
Map mainPage() {
    updatePauseLabel()
    if (settings.removeSettings) {
        removeSettings(settings.removeSettings)
        app.removeSetting('removeSettings')
    }

    return dynamicPage(name: 'mainPage') {
        section {
            if (state.paused) {
                input name: 'resume',
                    title: 'Resume',
                    type: 'button',
                    width: 1
            } else {
                label title: '',
                description: 'Name this LED Dashboard',
                width: 9,
                submitOnChange: true,
                required: true

                input name: 'pause',
                    title: 'Pause',
                    type: 'button',
                    width: 1
            }
        }

        section {
            input name: 'switches',
                title: 'Select Inovelli switches to display LED dashboard on',
                type: 'device.' + deviceDriver,
                required: true,
                multiple: true,
                width: 11
        }

        Set<String> prefixes = getDashboardList()
        section('<b>LED Dashboards</b>') {
            for (String prefix in prefixes) {
                Map<String, String> config = getDashboardConfig(prefix)
                String currentResult = evaluateConditions(prefix) ? ' <span style=\'color: green\'>(active)</span>' : ''
                href(
                    name: "edit_${prefix}",
                    title: "<b>${config.name}</b>${currentResult}",
                    description: getDashboardDescription(config),
                    page: 'editPage',
                    params: [ prefix: prefix ],
                    state: 'complete',
                    width: 11
                )
                input name: 'remove_' + prefix,
                    title: '<i style="font-size:1rem; color:red;" class="material-icons he-bin"></i>',
                    type: 'button',
                    width: 1
            }

            href(
                name: 'addDashboard',
                title: 'Add new dashboard',
                description: 'Select to add new dashboard',
                params: [ prefix: getNextPrefix() ],
                page: 'editPage',
                width: 11
            )
        }
    }
}

/*
 * Dashboard Edit Page
 */
Map editPage(Map params = [:]) {
    String prefix = params.prefix
    if (!prefix) { return mainPage() }
    String name = settings["${prefix}_name"] ?: 'New'
    String ledName = switchLedsMap[settings["${prefix}_lednumber"]] ?: 'LED'

    return dynamicPage(name: 'editPage', title: "<h2 style=\'color: #1A77C9; font-weight: bold\'>${name} Dashboard</h2>") {
        section {
            input name: "${prefix}_name", title: '', description: 'Dashboard Name', type: 'text', width: 6, required: true, submitOnChange: true
            input name: "${prefix}_priority", title: '', description: 'Select Priority', type: 'enum', options: prioritiesMap, defaultValue: '5', width: 3, required: true
            paragraph '<i>Higher value priority dashboards take LED precedence.</i>'
        }

        renderIndicationSection(prefix, ledName)

        String ledEffect = switchEffectsMap[settings["${prefix}_effect"]] ?: 'condition'
        renderConditionSection(prefix, "<b>Activate ${ledName} ${ledEffect} effect when:</b>")

        section {
            String title = 'If conditions above do not match '
            if (settings["${prefix}_autostop"] == false) {
                title += '<i>make no change</i>'
            } else {
                title += '<b>stop the effect</b>'
            }
            input name: "${prefix}_autostop", title: title, type: 'bool', defaultValue: true, width: 7, submitOnChange: true
        }
    }
}

Map renderIndicationSection(String prefix, String ledName) {
    return section('<b>Select LED indication when condition is active:</b>') {
        // LED Number
        input name: "${prefix}_lednumber", title: '<span style=\'color: blue;\'>LED Number</span>', type: 'enum', options: switchLedsMap, defaultValue: 'All', width: 2, required: true, submitOnChange: true
        if (settings["${prefix}_lednumber"] == 'var') {
            input name: "${prefix}_lednumber_var", title: "<span style=\'color: blue;\'>LED Number Variable</span>", type: 'enum', options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
        } else {
            app.removeSetting("${prefix}_lednumber_var")
        }

        // Effect
        input name: "${prefix}_effect", title: "<span style=\'color: blue;\'>${ledName} Effect</span>", type: 'enum', options: switchEffectsMap, defaultValue: '1', width: 3, required: true, submitOnChange: true
        if (settings["${prefix}_effect"] == 'var') {
            input name: "${prefix}_effect_var", title: "<span style=\'color: blue;\'>Effect Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
        } else {
            app.removeSetting("${prefix}_effect_var")
        }

        // Color
        if (settings["${prefix}_effect"] in ['0', '255']) {
            ["${prefix}_color", "${prefix}_color_var", "${prefix}_unit", "${prefix}_duration", "${prefix}_level"].each { s -> app.removeSetting(s) }
        } else {
            input name: "${prefix}_color", title: "<span style=\'color: blue;\'>${ledName} Color</span>", type: 'enum', options: switchColorsMap, width: 3, defaultValue: '170', required: true, submitOnChange: true
            if (settings["${prefix}_color"] == 'var') {
                input name: "${prefix}_color_var", title: "<span style=\'color: blue;\'>Color Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
            } else {
                app.removeSetting("${prefix}_color_var")
            }

            // Time Unit
            input name: "${prefix}_unit", title: '<span style=\'color: blue;\'>Duration</span>', description: 'Select', type: 'enum', options: timePeriodsMap, width: 2, defaultValue: 'Indefinitely', required: true, submitOnChange: true
            if (settings["${prefix}_unit"] in ['0', '60', '120']) {
                // Time Duration
                String timePeriod = timePeriodsMap[settings["${prefix}_unit"]]
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

void appButtonHandler(String buttonName) {
    switch (buttonName) {
        case 'pause':
            state.paused = true
            break
        case 'resume':
            state.paused = false
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

// Utility method for CSS colored text
String getColorSpan(Integer color, String text) {
    String css = 'white'
    if (color != 255) {
        int hue = Math.round((color / 254) * 360)
        css = "hsl(${hue}, 50%, 50%)"
    }
    return "<span style=\'color: ${css}\'>${text}</span>"
}

// Creates a description string for the dashboard configuration for display
String getDashboardDescription(Map<String, String> config) {
    StringBuilder sb = new StringBuilder()
    if (config.lednumber && config.lednumber != 'var') {
        sb << "<b>${switchLedsMap[config.lednumber]}</b>"
    } else if (config.lednumber == 'var') {
        sb << "<b>LED Variable:</b> <i>${config.lednumber_var}</i>"
    }
    sb << ", <b>Priority</b>: ${config.priority}"
    if (config.effect && config.effect != 'var') {
        sb << ", <b>Effect:</b> ${switchEffectsMap[config.effect]}"
    } else if (config.effect == 'var') {
        sb << ", <b>Effect Variable</b>: <i>${config.effect_var}</i>"
    }
    if (config.color && config.color != 'var') {
        sb << ", <b>Color</b>: ${getColorSpan(config.color as Integer, switchColorsMap[config.color])}"
    } else if (config.color == 'var') {
        sb << ", <b>Color Variable:</b> <i>${config.color_var}</i>"
    }
    if (config.level) {
        sb << ", <b>Level:</b> ${config.level}%"
    }
    if (config.duration && config.unit) {
        sb << ", <b>Duration:</b> ${config.duration} ${timePeriodsMap[config.unit]?.toLowerCase()}"
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

// Called by the hub when a global variable is renamed
void renameVariable(String oldName, String newName) {
    settings.findAll { s -> s.key.endsWith('_var') && s.value == oldName }.each { s ->
        log.info "changing ${s.key} from ${oldName} to ${newName}"
        s.value = newName
    }
}

// Updates the app label based on pause state
void updatePauseLabel() {
    if (state.paused && !app.label?.endsWith(pauseText)) {
        app.updateLabel(app.label + pauseText)
    } else if (app.label?.endsWith(pauseText)) {
        app.updateLabel(app.label - pauseText)
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
    Map<String, Map> ledStates = [:]
    for (String prefix in getDashboardList()) {
        Map<String, String> config = getDashboardConfig(prefix)
        Map<String, Map> oldState = ledStates[config.lednumber as String] ?: [:]
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
                effect: 255 // stop effect
            ]
        }
    }

    // Update switch LEDs as required
    if (ledStates.containsKey('All')) {
        // Any 'All LED' condition takes precedence over individual LED conditions
        setLedConfiguration(ledStates['All'])
        sendEvent([ name: ledStates['All'].title, value: 'All', descriptionText: ledStates['All'].toString() ])
    } else if (ledStates) {
        // Sending individual LED updates is inefficient but necessary
        ledStates.values().each { config ->
            setLedConfiguration(config)
            sendEvent([ name: config.title, value: config.lednumber, descriptionText: ledStates.toString() ])
        }
    }
}

// Inovelli Blue Switch Tracker
void switchTracker(Event event) {
    switch (event.value) {
        case 'Stop All':
        case 'User Cleared':
            switchLedTracker.remove(event.device.id)
            log.info "clearing LED tracking for ${event.device}"
            break
        case ~/^Stop LED(\d)$/:
            Map<String, Map> tracker = switchLedTracker[event.device.id]
            if (tracker) {
                String led = Matcher.lastMatcher[0][1]
                tracker.remove(led)
                log.info "cleared LED tracking for ${event.device} LED${led}"
            }
            break
    }
}

// Cleans settings removing entries no longer in use
private void cleanSettings() {
    [ 'lednumber', 'effect', 'color' ].each { var ->
        if (settings["${prefix}_${var}"] != 'var') {
            app.removeSetting("${prefix}_${var}_var")
        }
    }

    for (String prefix in getDashboardList()) {
        List<String> selectedConditions = settings["${prefix}_conditions"] ?: []
        conditionsMap.keySet().findAll { key -> !(key in selectedConditions) }.each { key ->
            removeSettings("${prefix}_${key}")
        }
    }
}

// Returns key value map of specified dashboard settings
private Map<String, String> getDashboardConfig(String prefix) {
    int startPos = prefix.size() + 1
    return [ 'prefix': prefix ] + settings
        .findAll { s -> s.key.startsWith(prefix + '_') }
        .collectEntries { s -> [ s.key.substring(startPos), s.value ] }
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
private long getDurationMs(int duration) {
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

/**
 *  ledEffectAll is a wrapper around the Inovelli device driver method of the same name
 *  The wrapper uses the trackingState to reduce the Zigbee traffic by checking the
 *  assumed LED state before sending changes.
 */
private void ledEffectAll(DeviceWrapper dw, Map params) {
    Map<String, Map> tracker = switchLedTracker.computeIfAbsent(dw.id) { k -> [:].withDefault { [:] } }
    if (tracker['All'].effect != params.effect
        || tracker['All'].color != params.color
        || tracker['All'].level != params.level
        || tracker['All'].duration != params.duration
        || tracker['All'].expires <= now()
    ) {
        dw.ledEffectAll(params.effect, params.color, params.level, params.duration)
        // the switch will tell us when the effect stops so expires is a backup method
        params.expires = now() + getDurationMs(params.duration)
        tracker.clear()
        tracker['All'] = params
    } else {
        log.info 'skipping update (no change to leds detected)'
    }
}

/**
 *  ledEffectOne is a wrapper around the Inovelli device driver method of the same name
 *  The wrapper uses the trackingState to reduce the Zigbee traffic by checking the
 *  assumed LED state before sending changes.
 */
private void ledEffectOne(DeviceWrapper dw, Map params) {
    Map<String, Map> tracker = switchLedTracker.computeIfAbsent(dw.id) { k -> [:].withDefault { [:] } }
    String key = params.lednumber
    if (tracker[key].effect != params.effect
        || tracker[key].color != params.color
        || tracker[key].level != params.level
        || tracker[key].duration != params.duration
        || tracker[key].expires <= now()
    ) {
        dw.ledEffectOne(params.lednumber, params.effect, params.color, params.level, params.duration)
        params.expires = now() + getDurationMs(params.duration)
        tracker.remove('All')
        tracker[key] = params
    } else {
        log.info 'skipping update (no change to leds detected)'
    }
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
    if (config.lednumber == 'var') {
        config.lednumber = lookupVariable(config.lednumber_var, switchLedsMap) ?: 'All'
    }
    if (config.effect == 'var') {
        config.effect = lookupVariable(config.effect_var, switchEffectsMap) ?: '1'
    }
    if (config.color == 'var') {
        config.color = lookupVariable(config.color_var, switchColorsMap) ?: '170'
    }
}

// Set all switches to stop
private void resetSwitchDisplays() {
    for (DeviceWrapper device in settings.switches) {
        ledEffectAll(device, [ 255, null, null, null ])
    }
}

// Removes all condition settings from application
private void removeSettings(String prefix) {
    Set<String> entries = settings.keySet().findAll { s -> s.startsWith(prefix) }
    entries.each { s -> app.removeSetting(s) }
}

// Set Inovelli switch LEDs
private void setLedConfiguration(Map<String, String> config) {
    List<DeviceWrapper> devices = settings.switches as List<DeviceWrapper>
    if (devices) {
        devices.each { device ->
            Integer duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255)
            if (config.lednumber == 'All') {
                logInfo "setting ${device} ALL LEDs (id=${config.prefix}, name=${config.name}, priority=${config.priority}, effect=${config.effect}, color=${config.color}, level=${config.level}, duration=${duration})"
                ledEffectAll(device, [
                    effect: config.effect as Integer,
                    color: config.color as Integer,
                    level: config.level as Integer,
                    duration: duration
                ])
            } else {
                logInfo "setting ${device} LED #${config.lednumber} (id=${config.prefix}, name=${config.name}, priority=${config.priority}, effect=${config.effect}, color=${config.color}, level=${config.level}, duration=${duration})"
                ledEffectOne(device, [
                    lednumber: config.lednumber as String,
                    effect: config.effect as Integer,
                    color: config.color as Integer,
                    level: config.level as Integer,
                    duration: duration
                ])
            }
        }
    }
}

// Subscribe to all dashboard conditions
private void subscribeConditions() {
    for (String prefix in getDashboardList()) {
        subscribeCondition(prefix)
    }
}

private void subscribeSwitches() {
    log.info "subscribing to ledEffect for ${settings.switches}"
    subscribe(settings.switches, 'ledEffect', 'switchTracker', null)
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
        test: { ctx -> ctx.event.hsmStatus in ctx.choice }
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
        test: { ctx -> ctx.event.hsmAlert in ctx.choice }
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
 *  Evaluates the provided condition configuration and returns a pass/fail (boolean)
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

@CompileStatic
private Map<String, String> getAttributeComparisons(List<DeviceWrapper> devices, String attribute) {
    List<String> types = devices?.collect { d -> d.getSupportedAttributes().find { a -> a.name == attribute }.dataType }.unique()
    return types.inject([:]) { map, type -> map += getComparisonsByType(type) }
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
