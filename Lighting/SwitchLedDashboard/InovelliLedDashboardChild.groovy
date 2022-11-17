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
    name: 'Switch LED Dashboard (Inovelli Blue Series)',
    namespace: 'nrgup',
    parent: 'nrgup:Switch LED Dashboard Manager',
    author: 'Jonathan Bradshaw',
    description: 'LED Dashboard Child for Inovelli Blue Series Switches',
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
import java.util.regex.Matcher

/*
 *  Provides the available conditions to select from
 */
@Field static final Map<String, Map> conditionsMap = [
    'contactClose': [
        name: 'Contact sensor',
        title: 'Contact sensor closed',
        type: 'capability.contactSensor',
        multiple: true,
        attribute: 'contact',
        value: 'closed'
    ],
    'contactOpen': [
        name: 'Contact sensor',
        title: 'Contact sensor opened',
        type: 'capability.contactSensor',
        multiple: true,
        attribute: 'contact',
        value: 'open'
    ],
    'locked': [
        name: 'Lock',
        title: 'Device locked',
        type: 'capability.lock',
        multiple: true,
        attribute: 'lock',
        value: 'locked'
    ],
    'unlocked': [
        name: 'Lock',
        title: 'Device unlocked',
        type: 'capability.lock',
        multiple: true,
        attribute: 'lock',
        value: 'unlocked'
    ],
    'motionActive': [
        name: 'Motion sensor',
        title: 'Motion sensor activated',
        type: 'capability.motionSensor',
        multiple: true,
        attribute: 'motion',
        value: 'active'
    ],
    'presence': [
        name: 'Presence sensor',
        title: 'Device becomes present',
        type: 'capability.presenceSensor',
        multiple: true,
        attribute: 'presence',
        value: 'present'
    ],
    'shock': [
        name: 'Shock sensor',
        title: 'Shock sensor activated',
        type: 'capability.shockSensor',
        multiple: true,
        attribute: 'shock',
        value: 'detected'
    ],
    'sleep': [
        name: 'Sleep sensor',
        title: 'Sleep sensor activated',
        type: 'capability.sleepSensor',
        multiple: true,
        attribute: 'sleep',
        value: 'sleeping'
    ],
    'smoke': [
        name: 'Smoke detector',
        title: 'Smoke detected',
        type: 'capability.smokeDetector',
        multiple: true,
        attribute: 'smoke',
        value: 'detected'
    ],
    'sound': [
        name: 'Sound sensor',
        title: 'Sound detected',
        type: 'capability.soundSensor',
        multiple: true,
        attribute: 'sound',
        value: 'detected'
    ],
    'switchOff': [
        name: 'Switch',
        title: 'Switch turns off',
        type: 'capability.switch',
        multiple: true,
        attribute: 'switch',
        value: 'off'
    ],
    'switchOn': [
        name: 'Switch',
        title: 'Switch turns on',
        type: 'capability.switch',
        multiple: true,
        attribute: 'switch',
        value: 'on'
    ],
    'tamper': [
        name: 'Tamper sensor',
        title: 'Tamper alert detected',
        type: 'capability.tamperAlert',
        multiple: true,
        attribute: 'tamper',
        value: 'detected'
    ],
    'valveClose': [
        name: 'Valve',
        title: 'Valve closes',
        type: 'capability.valve',
        multiple: true,
        attribute: 'valve',
        value: 'close'
    ],
    'valveOpen': [
        name: 'Valve',
        title: 'Valve opens',
        type: 'capability.valve',
        multiple: true,
        attribute: 'valve',
        value: 'open'
    ],
    'water': [
        name: 'Water sensor',
        title: 'Water detected',
        type: 'capability.waterSensor',
        multiple: true,
        attribute: 'water',
        value: 'wet'
    ],
    'global_var': [
        name: 'Variable',
        title: 'Hub Variable',
        type: 'enum',
        attribute: 'location',
        multiple: false
    ],
    'hsmStatus': [
        name: 'HSM Status',
        title: 'HSM Arming Status',
        type: 'enum',
        options: [ 'armedAway': 'Armed Away', 'armedHome': 'Armed Home', 'disarmed': 'Disarmed' ],
        attribute: 'location',
        multiple: true
    ],
    'hsmAlert': [
        name: 'HSM Alert',
        title: 'HSM Intrusion Alert',
        type: 'enum',
        options: [ 'intrusion': 'Intrusion Away', 'intrusion-home': 'Intrusion Home', 'smoke': 'Smoke', 'water': 'Water', 'arming': 'Arming fail', 'cancel': 'Alert cancelled' ],
        attribute: 'location',
        multiple: true,
    ],
].asImmutable()

// Definitions for condition options
@Field static final Map<String, String> prioritiesMap = [ '1': 'Priority 1 (Low)', '2': 'Priority 2', '3': 'Priority 3', '4': 'Priority 4', '5': 'Priority 5 (Medium)', '6': 'Priority 6', '7': 'Priority 7', '8': 'Priority 8', '9': 'Priority 9', '10': 'Priority 10 (High)' ].asImmutable()
@Field static final Map<String, String> switchColorsMap = [ '0': 'Red', '7': 'Orange', '28': 'Lemon', '64': 'Lime', '85': 'Green', '106': 'Teal', '127': 'Cyan', '148': 'Aqua', '170': 'Blue', '190': 'Violet', '212': 'Magenta', '234': 'Pink', '255': 'White', 'var': 'Variable' ].asImmutable()
@Field static final Map<String, String> switchEffectsMap = [ '0': 'Off', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '255': 'Stop', 'var': 'Variable' ].asImmutable()
@Field static final Map<String, String> switchLedsMap = [ '1': 'LED 1', '2': 'LED 2', '3': 'LED 3', '4': 'LED 4', '5': 'LED 5', '6': 'LED 6', '7': 'LED 7', 'All': 'All LEDs', 'var': 'Variable' ].asImmutable()
@Field static final Map<String, String> timePeriodsMap = [ '0': 'Seconds', '60': 'Minutes', '120': 'Hours', '255': 'Indefinitely' ].asImmutable()

// Inovelli Device Driver and count of LEDs on the switches
@Field static final String deviceDriver = 'InovelliDimmer2-in-1BlueSeriesVZM31-SN'
@Field static final int ledCount = 7

@Field static final String pauseText = '<span style=\'color: red;\'> (Paused) </span>'

// Called when the app is first created.
void installed() {
    log.info "${app.name} child installed"
}

// Called when the app is removed.
void uninstalled() {
    unsubscribe()
    removeAllInUseGlobalVar()
    log.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    log.debug settings
    unsubscribe()
    subscribeDevices()
    subscribeVariables()
}

/*
 * Application Main Page
 */
Map mainPage() {
    updatePauseLabel()
    if (settings.removeCondition) {
        removeCondition(settings.removeCondition)
        app.removeSetting('removeCondition')
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
                title: 'Select Inovelli switches to display LED dashboard',
                type: 'device.' + deviceDriver,
                required: true,
                multiple: true,
                width: 11
        }

        Set<String> prefixes = getConditionList()
        section('<b>Dashboard Conditions</b>') {
            for (String prefix in prefixes) {
                Map config = getConditionConfig(prefix)
                href(
                    name: "edit_${prefix}",
                    title: "<b>${config.name}</b>",
                    description: getConditionDescription(config),
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
                name: 'addCondition',
                title: 'Add new condition',
                description: 'Click to add new dashboard condition',
                params: [ prefix: getNextPrefix() ],
                page: 'editPage',
                width: 11
            )
        }
    }
}

/*
 * Application Condition Edit Page
 */
Map editPage(Map params = [:]) {
    String prefix = params.prefix
    String name = settings["${prefix}_name"] ?: 'New'

    return dynamicPage(name: 'editPage', title: "<h2 style=\'color: #1A77C9; font-weight: bold\'>${name} Condition</h2>") {
        section {
            input name: "${prefix}_name", title: '', description: 'Condition Name', type: 'text', width: 6, required: true, submitOnChange: true
            input name: "${prefix}_priority", title: '', description: 'Priority', type: 'enum', options: prioritiesMap, width: 3, required: true
            paragraph 'Higher priority conditions will take precedence.'
        }

        section('<b>Select LED indication when condition is active:</b>') {
            input name: "${prefix}_lednumber", title: '<span style=\'color: blue;\'>LED Number</span>', type: 'enum', options: switchLedsMap, defaultValue: 'All', width: 2, required: true, submitOnChange: true
            if (settings["${prefix}_lednumber"] == 'var') {
                input name: "${prefix}_lednumber_var", title: "<span style=\'color: blue;\'>LED Number Variable</span>", type: 'enum', options: getGlobalVarsByType('integer').keySet(), width: 3, required: true
            }
            input name: "${prefix}_effect", title: '<span style=\'color: blue;\'>LED Effect</span>', type: 'enum', options: switchEffectsMap, defaultValue: '1', width: 3, required: true, submitOnChange: true
            if (settings["${prefix}_effect"] == 'var') {
                input name: "${prefix}_effect_var", title: "<span style=\'color: blue;\'>LED Effect Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
            }
            input name: "${prefix}_color", title: '<span style=\'color: blue;\'>LED Color</span>', type: 'enum', options: switchColorsMap, width: 3, defaultValue: '170', required: true, submitOnChange: true
            if (settings["${prefix}_color"] == 'var') {
                input name: "${prefix}_color_var", title: "<span style=\'color: blue;\'>LED Color Variable</span>", type: 'enum', options: getGlobalVarsByType('string').keySet(), width: 3, required: true
            }
            input name: "${prefix}_unit", title: '<span style=\'color: blue;\'>Duration</span>', description: 'Select', type: 'enum', options: timePeriodsMap, width: 2, defaultValue: 'Indefinitely', required: true, submitOnChange: true
            if (settings["${prefix}_unit"] in ['0', '60', '120']) {
                input name: "${prefix}_duration", title: "<span style=\'color: blue;\'># ${timePeriodsMap[settings["${prefix}_unit"]]}&nbsp;</span>", description: '1..60', type: 'number', width: 2, defaultValue: 1, range: '1..60', required: true
            } else {
                app.removeSetting("${prefix}_duration")
            }
            input name: "${prefix}_level", title: '<span style=\'color: blue;\'>LED Level&nbsp;</span>', description: '1..100', type: 'number', width: 2, defaultValue: 100, range: '1..100', required: true
            paragraph ''
        }

        section('<b>Select conditions for activation:</b>') {
            input name: "${prefix}_conditions",
                title: '',
                type: 'enum',
                options: conditionsMap.collectEntries { k, v -> [ k, v.title ] }.sort { kv -> kv.value },
                multiple: true,
                submitOnChange: true,
                width: 7

            Boolean allMode = settings["${prefix}_conditions_all"] ?: false
            if (settings["${prefix}_conditions"]?.size() > 1) {
                String title = "Require ${allMode ? '<b>all</b> conditions' : '<b>any</b> condition'}"
                input name: "${prefix}_conditions_all",
                    title: title,
                    type: 'bool',
                    width: 4,
                    submitOnChange: true
            }

            List<String> conditionList = settings["${prefix}_conditions"] ?: []
            boolean isFirst = true
            for (Map.Entry condition in conditionsMap) {
                String id = "${prefix}_${condition.key}"
                Boolean allDeviceMode = settings["${id}_all"] ?: false
                if (condition.key in conditionList) {
                    paragraph isFirst ? '' : (allMode ? '<i>and</i>' : '<i>or</i>')
                    isFirst = false

                    input name: id,
                        title: "${condition.value.title}",
                        type: condition.value.type,
                        multiple: condition.value.multiple,
                        options: condition.key == 'global_var' ? getAllGlobalVars().keySet() : condition.value.options,
                        width: 7,
                        submitOnChange: true,
                        required: true

                    if (condition.key == 'global_var' && settings[id]) {
                        input name: "${id}_value",
                            title: settings[id] + ' Value ',
                            type: 'text',
                            required: true,
                            width: 3
                    } else if (condition.value.type != 'enum' && condition.value.multiple && settings[id]?.size() > 1) {
                        String title = "Trigger requires ${allDeviceMode ? '<b>all</b>' : '<b>any</b>'}"
                        input name: "${id}_all",
                            title: title,
                            type: 'bool',
                            width: 4,
                            submitOnChange: true
                    }
                } else if (settings[id]) {
                    app.removeSetting(id)
                    app.removeSetting(id + '_all')
                }
            }
        }
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
            removeCondition(prefix)
            break
        default:
            log.warn "unknown app button ${buttonName}"
            break
    }
}

String getConditionDescription(Map config) {
    StringBuilder sb = new StringBuilder()
    sb << "Priority: ${config.priority}, LED number: ${config.lednumber}, Color: ${switchColorsMap[config.color]}, Effect: ${switchEffectsMap[config.effect]}"
    if (config.conditions) {
        List<String> conditions = config.conditions
            .findAll { c -> conditionsMap.containsKey(c) }
            .collect { c -> conditionsMap[c].title }
        sb << "\nActivation${conditions.size() > 1 ? 's' : ''}: ${conditions.join(', ')}"
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
 *  Dashboard Implementation
 */
void eventHandler(Event event) {
    logEvent(event)
    if (event.source == 'LOCATION' && event.name in conditionsMap.keySet()) {
        // Persist the latest location event value
        state[event.name] = event.value
    }

    Map<String, Map> ledStates = [:]
    for (String prefix in getConditionList()) {
        Map config = getConditionConfig(prefix)
        replaceVariables(config)
        if (checkConditions(config, state)) {
            Map oldState = ledStates[config.lednumber as String] ?: [:]
            int oldPriority = oldState.priority as Integer ?: 0
            int newPriority = config.priority as Integer ?: 0
            if (newPriority >= oldPriority) {
                ledStates[config.lednumber as String] = config
            }
        }
    }
    log.debug ledStates
    if (ledStates.containsKey('All')) {
        setLedConfiguration(ledStates['All'])
    } else {
        ledStates.values().each { config -> setLedConfiguration(config) }
    }
}

@CompileStatic
private boolean checkConditions(Map config, Map state) {
    boolean result = false
    boolean allConditions = config['conditions_all'] ?: false
    for (String testName in config.conditions) {
        boolean testResult
        Map testCondition = conditionsMap[testName]
        // Handle global variable test
        if (testName == 'global_var') {
            String currentValue = config.global_var as String
            String targetValue = config.global_var_value as String
            testResult = currentValue == targetValue
        // Handle location event tests
        } else if (testCondition?.attribute == 'location') {
            String currentValue = state[testName] as String
            List<String> options = config[testName] as List<String>
            testResult = options.contains(currentValue)
        // All other device sensor tests
        } else if (testCondition?.attribute) {
            List<DeviceWrapper> devices = config[testName] as List<DeviceWrapper>
            if (devices) {
                Closure testClosure = { DeviceWrapper d ->
                    String attribute = testCondition.attribute as String
                    String targetValue = testCondition.value as String
                    d.currentValue(attribute, true) == targetValue
                }
                boolean allDevices = config[testName + '_all'] ?: false
                testResult = allDevices ? devices.every(testClosure) : devices.any(testClosure)
            }
        }
        logInfo "${config.name} condition '${testName}' returned ${testResult}"
        if (allConditions && !testResult) {
            return false // fail fast
        }
        result |= testResult
    }
    return result
}

// Returns key value map of specific condition settings
private Map getConditionConfig(String prefix) {
    int startPos = prefix.size() + 1
    return settings
        .findAll { s -> s.key.startsWith(prefix + '_') }
        .collectEntries { s -> [ s.key.substring(startPos), s.value ] }
}

// Returns condition setting prefix sorted by priority (descending)
private Set<String> getConditionList() {
    return settings.keySet()
        .findAll { s -> s.matches('^condition_[0-9]+_priority$') }
        .sort { s -> settings[s] as int }
        .collect { s -> s - '_priority' }
        .reverse()
}

// Returns next condition settings prefix
@CompileStatic
private String getNextPrefix() {
    List<Integer> keys = getConditionList().collect { p -> p.substring(10) as Integer }
    int maxId = keys ? Collections.max(keys) : 0
    return "condition_${maxId + 1}"
}

// Logs the received event
private void logEvent(Event event) {
    log.info "${event.device ?: event.source} event ${event.name} is ${event.value}"
}

// Logs information
private void logInfo(String s) {
    log.info s
}

// Looks up a variable in the given lookup table and returns the key if found
private String lookupVariable(String variableName, Map lookupTable) {
    String globalVar = getGlobalVar(variableName)?.value
    String value = globalVar?.toLowerCase()
    return lookupTable.find { k, v -> v.toLowerCase() == value }?.key
}

// Populate configuration values with specified global variables
private void replaceVariables(Map config) {
    if (config.lednumber == 'var') {
        config.lednumber = lookupVariable(config.lednumber_var, switchLedsMap) ?: 'All'
    }
    if (config.effect == 'var') {
        config.effect = lookupVariable(config.effect_var, switchEffectsMap) ?: '1'
    }
    if (config.color == 'var') {
        config.color = lookupVariable(config.color_var, switchColorsMap) ?: '170'
    }
    if (config.global_var) {
        config.global_var = getGlobalVar(config.global_var)?.value as String
    }
}

// Removes all condition settings from application
private void removeCondition(String prefix) {
    Set<String> entries = settings.keySet().findAll { s -> s.startsWith(prefix) }
    entries.each { s -> app.removeSetting(s) }
}

// Set Inovelli switch LEDs
private void setLedConfiguration(Map config) {
    List<DeviceWrapper> devices = settings.switches as List<DeviceWrapper>
    if (devices) {
        devices.each { d ->
            int duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255)
            if (config.lednumber == 'All') {
                logInfo "setting ${d} all leds (name=${config.name}, priority=${config.priority}, effect=${config.effect}, color=${config.color}, level=${config.level}, duration=${duration})"
                d.ledEffectAll(
                    config.effect as int,
                    config.color as int,
                    config.level as int,
                    duration
                )
            } else {
                logInfo "setting ${d} led ${config.lednumber} (name=${config.name}, priority=${config.priority}, effect=${config.effect}, color=${config.color}, level=${config.level}, duration=${duration})"
                d.ledEffectOne(
                    config.lednumber as String,
                    config.effect as int,
                    config.color as int,
                    config.level as int,
                    duration
                )
            }
        }
    }
}

// Subscribe to all the devices for all the conditions
private void subscribeDevices() {
    if (!state.paused) {
        for (String prefix in getConditionList()) {
            List<String> conditionList = settings["${prefix}_conditions"] ?: []
            conditionList.each { condition ->
                String key = "${prefix}_${condition}"
                if (settings[key] && conditionsMap.containsKey(condition)) {
                    String attribute = conditionsMap[condition].attribute
                    if (attribute == 'location') {
                        log.info "subscribing to ${condition} location event"
                        subscribe(location, condition, 'eventHandler', null)
                    } else if (attribute) {
                        List<DeviceWrapper> devices = settings[key]
                        log.info "subscribing to ${attribute} on ${devices}"
                        subscribe(devices, attribute, 'eventHandler', null)
                    }
                }
            }
        }
    }
}

// Subscribe to all the variables for all the conditions
private void subscribeVariables() {
    removeAllInUseGlobalVar()
    if (!state.paused) {
        Map globalVars = settings.findAll { s -> s.key.endsWith('_var') }
        addInUseGlobalVar(globalVars*.value)
        globalVars.findAll { k, v -> k.endsWith('_global_var') }.each { k, v ->
            log.info "subscribing to variable ${v}"
            subscribe(location, "variable:${v}", 'eventHandler', null)
        }
    }
}
