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
 *  FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

/**
 * Key updates:
 *
 * v0.3: Added state feedback indicator for conditions
 * v0.4: Introduced 'auto stop' effect option to clear effects
 * v0.5: Expanded device support for Inovelli Red switches and dimmers
 * v0.6: Added new effect types support
 * v0.7: Fixed split effect definitions (effects vs effectsAll)
 * v0.8: Implemented location mode condition
 * v0.9: Introduced delayed activation option per dashboard and variable level
 * v0.94: Improved Red Series Fan + Switch LZW36 support
 * v0.98: Updated Blue Fan Switch driver name and effect order consistency
 * v0.99: Added initial support for RGB child devices and custom levels
 * v1.00: Introduced illuminance value comparison conditions
 * v1.02: Replaced sunrise/sunset conditions with a single option
 * v1.03: Added cool-down period support for conditions
 * v1.04: Enhanced dashboard duplication to include devices, and added LED selection options
 * v1.10: Code refactoring, cleanup, and inline documentation for maintainability
 * v1.11: Bug fix for color devices
 * v1.12: Bug fix for duration settings
 *
 * Thanks to Mattias Fornander (@mfornander) for the original application concept
 */


import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import groovy.transform.CompileStatic
import groovy.transform.Field

import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher

@Field static final String Version = '1.13'

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

// Tracker for switch LED state to optimize traffic by only sending changes
@Field static final Map<String, Map> SwitchStateTracker = new ConcurrentHashMap<>()

/**
 * Invoked by the hub when the app is created.
 */
void installed() {
    logInfo 'mini-dashboard child created'
}

/**
 * Invoked from updated() to initialize the application.
 */
void initialize() {
    // Clear state (used mostly for tracking delayed conditions)
    state.clear()

    // Subscribe to events from supported Inovelli switches
    subscribeSwitchAttributes()

    // Subscribe to events from all conditions
    subscribeAllScenarios()

    // Subscribe to global variables from all conditions
    subscribeAllVariables()

    // Dispatch the current notifications
    runIn(1, 'notificationDispatcher')

    // Enable periodic refresh of devices if configured
    if (settings.periodicRefresh) {
        logInfo "enabling periodic forced refresh every ${settings.periodicRefreshInterval} minutes"
        final int seconds = 60 * (settings.periodicRefreshInterval as Integer ?: 60)
        runIn(seconds, 'forceRefresh')
    }
}

/**
 * Invoked by parent app to create duplicate dashboards.
 * @return The dashboard settings
 */
Map<String, Map> readSettings() {
    return settings.collectEntries { final String key, final Object value ->
        [key, [type: getSettingType(key), value: value]]
    }
}

/**
 * Invoked by the hub when a global variable is renamed.
 * Iterates through the settings and updates any references to the old name to the new name.
 * @param oldName The old name of the variable
 * @param newName The new name of the variable
 */
@CompileStatic
void renameVariable(final String oldName, final String newName) {
    getAppSettings().findAll { final Map.Entry<String, Object> entry -> entry.value == oldName }.each { final Map.Entry<String, Object> entry ->
        logInfo "updating variable name ${oldName} to ${newName} for ${entry.key}"
        entry.value = newName
    }
}

/**
 * Invoked by the hub when the app is removed.
 * Removes all subscriptions, scheduled tasks, global variables and resets notifications.
 */
void uninstalled() {
    unsubscribe()
    unschedule()
    removeAllInUseGlobalVar()
    resetNotifications()
    logInfo 'uninstalled'
}

/**
 * Invoked by the hub when the settings are updated.
 */
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

    // If paused then reset notifications and exit
    if (state.paused) {
        runIn(1, 'resetNotifications')
        return
    }

    initialize()
}

/**
 * Invoked by parent app to create duplicate dashboards.
 * Sets the dashboard state to paused.
 * @param newSettings The dashboard settings
 */
void writeSettings(final Map<String, Map> newSettings) {
    newSettings.each { final String key, final Map container ->
        app.updateSetting(key, [type: container.type, value: container.value])
    }
    state['paused'] = true
    updatePauseLabel()
}

/********************************************************************
 * APPLICATION MAIN PAGE
 */
Map mainPage() {
    updateAppLabel()
    updatePauseLabel()

    final String title = "<h2 style=\'color: #1A77C9; font-weight: bold\'>${app.label}</h2>"
    return dynamicPage(name: 'mainPage', title: title) {
        final Map<String, String> switchType = settings['deviceType'] ? getTargetSwitchType() : null
        final List<DeviceWrapper> switches = (settings['switches'] ?: []) as List<DeviceWrapper>
        section {
            renderDeviceTypeInput()
            renderPauseResumeButton()
            renderSwitchesInput(switchType)
        }
        renderNotificationScenarios(switchType)
        renderNameAndDuplicateSection()
        renderMessageSection()
        renderLoggingAndRefreshSection(switches)
    }
}

/**
 * Get the scenario status description text for the specified scenario prefix.
 */
String getScenarioStatus(final String scenarioPrefix) {
    final boolean isActive = evaluateActivationRules(scenarioPrefix)
    final Long coolDownUntil = state["${scenarioPrefix}_cooldown"] as Long
    final Long delayUntil = state["${scenarioPrefix}_delay"] as Long
    final long now = getTimeMs()
    String status = isActive ? ' &#128994;' : ''

    if (isActive && delayUntil > now) {
        final int minutes = (int) Math.ceil((delayUntil - now) / 60000)
        status = " &#128993; <span style=\'font-style: italic\'>(< ${minutes}m delay)</span>"
    } else if (!isActive && coolDownUntil > now) {
        final int minutes = (int) Math.ceil((coolDownUntil - now) / 60000)
        status = " &#128993; <span style=\'font-style: italic\'>(< ${minutes}m cooldown)</span>"
    }

    return status
}

/**
 * Render the add dashboard button.
 */
void renderAddDashboardButton() {
    href(
        name: 'addDashboard',
        title: '<i>Select to add a new Notification Scenario</i>',
        description: '',
        params: [prefix: findNextPrefix()],
        page: 'editPage',
        width: 10
    )
}

/**
 * Render the device type input.
 */
void renderDeviceTypeInput() {
    final Map<String, String> options = SupportedSwitchTypes.collectEntries { final String key, final Map value ->
        return [(key), value.title as String]
    } as Map<String,String>

    input name: 'deviceType',
        title: '',
        type: 'enum',
        description: '<b>Select the target device type</b> <i>(one type per mini-dashboard)</i>',
        options: options,
        multiple: false,
        required: true,
        submitOnChange: true,
        width: 10
}

/**
 * Render the Debug Logging and Periodic Refresh section.
 */
void renderLoggingAndRefreshSection(final List<DeviceWrapper> switches) {
    section {
        input name: 'logEnable', title: 'Enable debug logging', type: 'bool', defaultValue: false

        if (switches.size() > 1) {
            input name: 'metering', title: 'Enable metering (delay) of device updates', type: 'bool', defaultValue: false, width: 3, submitOnChange: true
            if (settings.metering) {
                input name: 'meteringInterval', title: 'Metering interval (ms):', type: 'number', width: 3, range: '10..10000', defaultValue: 100
            } else {
                app.removeSetting('meteringInterval')
            }
        } else {
            app.removeSetting('meteringInterval')
        }

        input name: 'periodicRefresh', title: 'Enable periodic forced refresh of all devices', type: 'bool', defaultValue: false, width: 3, submitOnChange: true
        if (settings.periodicRefresh) {
            input name: 'periodicRefreshInterval', title: 'Refresh interval (minutes):', type: 'number', width: 3, range: '1..1440', defaultValue: 60
        } else {
            app.removeSetting('periodicRefreshInterval')
        }
        paragraph "<span style='font-size: x-small; font-style: italic;'>Version ${Version}</span>"
    }
}

/**
 * Render the message section.
 */
void renderMessageSection() {
    if (state.message) {
        section { paragraph state.message }
        state.remove('message')
    }
}

/**
 * Render the dashboard naming and duplication button.
 */
void renderNameAndDuplicateSection() {
    if (!state.paused) {
        section {
            label title: 'Name this LED Mini-Dashboard Topic:', width: 8, submitOnChange: true, required: true
            input name: 'duplicate', title: 'Duplicate', type: 'button', width: 2
        }
    }
}

/**
 * Render all of the notification scenarios.
 */
void renderNotificationScenarios(final Map<String, String> switchType) {
    if (switchType && settings['switches']) {
        final Set<String> prefixes = getSortedScenarioPrefixes()
        final String title = "<h3 style=\'color: #1A77C9; font-weight: bold\'>${app.label} Notification Scenarios</h3>"
        section(title) {
            for (final String scenarioPrefix in prefixes) {
                renderScenarioDescription(scenarioPrefix)
            }
            renderAddDashboardButton()
        }
    }
}

/**
 * Render the Pause or Resume button.
 */
void renderPauseResumeButton() {
    if (state.paused) {
        input name: 'resume', title: 'Resume', type: 'button', width: 1
    } else {
        input name: 'pause', title: 'Pause', type: 'button', width: 1
    }
}

/**
 * Render an individual scenario description.
 */
void renderScenarioDescription(final String scenarioPrefix) {
    final String scenarioStatus = getScenarioStatus(scenarioPrefix)
    final String description = getScenarioDescription(scenarioPrefix)

    href(
        name: "edit_${scenarioPrefix}",
        title: "<b>${settings["${scenarioPrefix}_name"]}</b>${scenarioStatus}",
        description: description,
        page: 'editPage',
        params: [prefix: scenarioPrefix],
        state: scenarioStatus ? 'complete' : '',
        width: 10
    )

    input name: 'remove_' + scenarioPrefix,
        title: '<i style="font-size:1rem; color:red;" class="material-icons he-bin"></i>',
        type: 'button',
        width: 1
}

/**
 * Render the input for the switches to include in the mini-dashboard topic.
 */
void renderSwitchesInput(final Map<String, String> switchType) {
    if (switchType) {
        final String title = settings['deviceType']
        input name: 'switches',
            title: "Select ${title} devices to include in mini-dashboard topic",
            type: switchType.type,
            required: true,
            multiple: true,
            submitOnChange: true,
            width: 10
    }
}

/**
 * Update the application label if it is null.
 */
void updateAppLabel() {
    if (app.label == null) {
        app.updateLabel('New LED Mini-Dashboard')
    }
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

/********************************************************************
 * APPLICATION EDIT PAGE
 */
Map editPage(final Map<String, Object> params = [:]) {
    if (!params?.prefix) {
        return mainPage()
    }
    final String scenarioPrefix = params.prefix
    final String name = settings["${scenarioPrefix}_name"] ?: 'New Notification Scenario'
    final String title = "<h3 style='color: #1A77C9; font-weight: bold'>${name}</h3><br>"

    return dynamicPage(name: 'editPage', title: title) {
        renderIndicationSection(scenarioPrefix)

        final String effectName = getEffectName(scenarioPrefix)
        if (effectName) {
            final String sectionTitle = "<span style='color: green; font-weight: bold'>Select rules to activate notification LED ${effectName} effect:</span><span class=\"required-indicator\">*</span>"
            renderScenariosSection(scenarioPrefix, sectionTitle)

            if (settings["${scenarioPrefix}_conditions"]) {
                renderDelayAndAutoStopInputs(scenarioPrefix)
                renderScenarioNameSection(scenarioPrefix)
            }
        }
    }
}

/**
 * Returns the name of the effect selected for the given scenario.
 * @param scenarioPrefix The scenario prefix.
 */
String getEffectName(final String scenarioPrefix) {
    final Map switchType = getTargetSwitchType()
    final String ledKey = settings["${scenarioPrefix}_lednumber"]
    final Map<String, String> fxOptions = (ledKey == 'All' ? switchType.effectsAll : switchType.effects) as Map<String,String>
    final String fxKey = settings["${scenarioPrefix}_effect"]
    return fxOptions[fxKey]
}

/**
 * Returns available priorities based on ledNumber for display in dropdown.
 * @param scenarioPrefix The scenario prefix.
 */
Map<String, String> getPrioritiesList(final String scenarioPrefix) {
    final String ledNumber = settings["${scenarioPrefix}_lednumber"]

    // If ledNumber is a variable, get the led number from the current value
    if (ledNumber == 'var') {
        final Map switchType = getTargetSwitchType()
        final Map<String, String> ledMap = switchType.leds as Map<String,String>
        final String variable = settings["${scenarioPrefix}_lednumber_var"] as String
        lednumber = lookupVariable(variable, ledMap) ?: 'All'
    }

    // Get the priorities that are already in use for the given ledNumber
    final Set<Integer> usedPriorities = (getScenarioPrefixes() - scenarioPrefix)
        .findAll { final String prefix -> settings["${prefix}_lednumber"] as String == ledNumber }
        .collect { final String prefix -> settings["${prefix}_priority"] as Integer }

    // Return the priorities list with the priorities that are already in use marked as such
    return Priorities.collectEntries { final Integer priority ->
        String text = priority as String
        if (priority in usedPriorities) {
            text += ' (In Use)'
        } else if (priority == 1) {
            text += ' (Low)'
        } else if (priority == Priorities.max()) {
            text += ' (High)'
        }
        return [priority, text]
    }
}

/**
 * Returns a suggested name for the given scenario.
 * The name is based on the LED number and effect/color.
 * @param scenarioPrefix The scenario prefix.
 */
String getSuggestedScenarioName(final String scenarioPrefix) {
    final Map config = getScenarioConfig(scenarioPrefix)
    final Map switchType = getTargetSwitchType()
    final StringBuilder sb = new StringBuilder('Set ')

    // Add the LED number
    if (config.lednumber && config.lednumber != 'var') {
        sb << switchType.leds[config.lednumber as String] ?: 'n/a'
    } else {
        sb << 'LED'
    }

    // Add the effect and color name
    if (config.color && config.color != 'var' && config.color != 'val') {
        sb << ' to '
        final String effectName = getEffectName(scenarioPrefix)
        if (effectName) {
            sb << "${effectName} "
        }
        sb << ColorMap[config.color as String]
    }

    return sb.toString()
}

/**
 * Render the color input for the given notification scenario and led.
 * @param scenarioPrefix The prefix of the scenario to render the input for.
 * @param ledName The name of the LED to render the input for.
 */
void handleColorInput(final String scenarioPrefix, final String ledName) {
    final String url = '''<a href="https://community-assets.home-assistant.io/original/3X/6/c/6c0d1ea7c96b382087b6a34dee6578ac4324edeb.png" target="_blank">'''
    final String color = settings["${scenarioPrefix}_color"]

    input name: "${scenarioPrefix}_color",
        title: "<span style='color: blue;'>${ledName} Color</span>",
        type: 'enum',
        options: ColorMap,
        width: 3,
        required: true,
        submitOnChange: true

    if (color == 'val') {
        input name: "${scenarioPrefix}_color_val",
            title: url + "<span style='color: blue; text-decoration: underline;'>Hue Value</span></a>",
            type: 'number',
            range: '0..360',
            width: 2,
            required: true,
            submitOnChange: true
    } else {
        app.removeSetting("${scenarioPrefix}_color_val")
    }

    if (color == 'var') {
        input name: "${scenarioPrefix}_color_var",
            title: "<span style='color: blue;'>Color Variable</span>",
            type: 'enum',
            options: getGlobalVarsByType('string').keySet(),
            width: 3,
            required: true
    } else {
        app.removeSetting("${scenarioPrefix}_color_var")
    }
}

/**
 * Render the duration input for the given notification scenario.
 * @param scenarioPrefix The prefix of the scenario to render the input for.
 */
void handleDurationInput(final String scenarioPrefix) {
    input name: "${scenarioPrefix}_unit",
        title: $/<span style='color: blue;'>Duration</span>/$,
        description: 'Select',
        type: 'enum',
        options: TimePeriodsMap,
        width: 2,
        defaultValue: 'Infinite',
        required: true,
        submitOnChange: true

    if (['0', '60', '120'].contains(settings["${scenarioPrefix}_unit"])) {
        final String timePeriod = TimePeriodsMap[settings["${scenarioPrefix}_unit"] as String]
        input name: "${scenarioPrefix}_duration",
            title: "<span style='color: blue;'>${timePeriod} </span>",
            type: 'enum',
            options: ['1', '2', '3', '4', '5', '10', '15', '20', '25', '30', '40', '50', '60'],
            width: 2,
            defaultValue: 1,
            required: true
    } else {
        app.removeSetting("${scenarioPrefix}_duration")
    }
}

/**
 * Render the level input for the given notification scenario.
 * @param scenarioPrefix The prefix of the scenario to render the input for.
 */
void handleLevelInput(final String scenarioPrefix) {
    input name: "${scenarioPrefix}_level",
        title: "<span style='color: blue;'>Level </span>",
        type: 'enum',
        options: LevelMap,
        width: 2,
        defaultValue: 100,
        required: true,
        submitOnChange: true

    if (settings["${scenarioPrefix}_level"] == 'val') {
        input name: "${scenarioPrefix}_level_val",
            title: "<span style='color: blue;'>Level Value </span>",
            type: 'number',
            range: '1..100',
            width: 2,
            required: true,
            submitOnChange: true
    } else {
        app.removeSetting("${scenarioPrefix}_level_val")
    }

    if (settings["${scenarioPrefix}_level"] == 'var') {
        input name: "${scenarioPrefix}_level_var",
            title: "<span style='color: blue;'>Level Variable</span>",
            type: 'enum',
            options: getGlobalVarsByType('integer').keySet(),
            width: 3,
            required: true
    } else {
        app.removeSetting("${scenarioPrefix}_level_var")
    }
}

/**
 * Render the choice input for the given rule.
 * @param key The key of the rule to render the input for.
 * @param rule The rule to render the input for.
 * @param choiceInput The choice input to render.
 * @param status The status to render the input for.
 */
void renderChoiceInput(final String key, final Map.Entry<String, Map> rule, final Map choiceInput, final String status) {
    final Object options = getValueOrClosure(choiceInput.options, [
        device: settings["${key}_device"]
    ])

    if (options) {
        input name: "${key}_choice",
            title: rule.value.title + ' ' + (choiceInput.title ?: '') + status,
            defaultValue: choiceInput.defaultValue,
            type: 'enum',
            options: options,
            width: choiceInput.width ?: 6,
            multiple: choiceInput.multiple,
            submitOnChange: true, required: true
    }
}

/**
 * Render the comparison input for the given rule.
 * @param key The key of the rule to render the input for.
 * @param comparisonInput The comparison input to render.
 */
void renderComparisonInput(final String key, final Map comparisonInput) {
    final Object options = getValueOrClosure(comparisonInput.options, [
        device: settings["${key}_device"],
        choice: settings["${key}_choice"]
    ])

    if (options) {
        input name: "${key}_comparison",
            title: (comparisonInput.title ?: 'Comparison') + ' ',
            width: comparisonInput.width ?: 3,
            type: 'enum',
            options: options,
            defaultValue: comparisonInput.defaultValue,
            submitOnChange: true, required: true
    }
}

/**
 * Render the optional delay duration and auto stop inputs for the given scenario.
 * @param scenarioPrefix The prefix of the scenario to render the inputs for.
 */
void renderDelayAndAutoStopInputs(final String scenarioPrefix) {
    final List<String> activeRules = settings["${scenarioPrefix}_conditions"] as List<String>

    section {
        if (activeRules?.every { final String ruleKey -> ActivationRules[ruleKey].inputs.delay }) {
            input name: "${scenarioPrefix}_delay", title: '<i>For at least (minutes):</i>', description: '1..60', type: 'decimal', width: 3, range: '0..60', required: false
        } else {
            app.removeSetting("${scenarioPrefix}_delay")
        }

        if (settings["${scenarioPrefix}_unit"] == '255' && settings["${scenarioPrefix}_effect"] != '255') {
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
}

/**
 * Render the device all input for the given rule.
 * @param key The key of the rule to render the input for.
 * @param deviceInput The device input to render.
 */
void renderDeviceAllInput(final String key, final Map deviceInput) {
    final String name = deviceInput.title ?: rule.value.title
    final String title = settings["${key}_device_all"] ? "<b>All</b> ${name} devices" : "<b>Any</b> ${name} device"

    input name: "${key}_device_all",
        title: title,
        type: 'bool',
        submitOnChange: true,
        width: 4
}

/**
 * Render the device input for the given rule.
 * @param key The key of the rule to render the input for.
 * @param rule The rule to render the input for.
 * @param deviceInput The device input to render.
 * @param status The status to render the input for.
 */
void renderDeviceInput(final String key, final Map.Entry<String, Map> rule, final Map deviceInput, final String status) {
    input name: "${key}_device",
        title: rule.value.title + ' ' + (deviceInput.title ?: '') + status,
        type: deviceInput.type,
        width: deviceInput.width ?: 7,
        multiple: deviceInput.multiple,
        submitOnChange: true,
        required: true
}

/**
 * Render the indication LED input section for the given switch type.
 * @param switchType The switch type to render the input section for.
 * @param scenarioPrefix The prefix of the scenario to render the input section for.
 */
void renderIndicationLedSection(final Map switchType, final String scenarioPrefix) {
    final String effect = settings["${scenarioPrefix}_effect"]
    final String ledNumber = settings["${scenarioPrefix}_lednumber"]
    final String ledName = switchType.leds[settings[ledNumber] as String] ?: 'LED'
    final Map<String, String> fxOptions = (ledNumber == 'All' ? switchType.effectsAll : switchType.effects) as Map<String, String>

    input name: "${scenarioPrefix}_effect",
        title: "<span style='color: blue;'>${ledName} Effect</span>",
        type: 'enum',
        options: fxOptions,
        width: 2,
        required: true,
        submitOnChange: true

    if (settings["${scenarioPrefix}_effect"] == 'var') {
        input name: "${scenarioPrefix}_effect_var",
            title: "<span style='color: blue;'>Effect Variable</span>",
            type: 'enum',
            options: getGlobalVarsByType('string').keySet(),
            width: 3,
            required: true
    } else {
        app.removeSetting("${scenarioPrefix}_effect_var")
    }

    if (effect && effect != '0' && effect != '255') {
        handleColorInput(scenarioPrefix, ledName)
        handleDurationInput(scenarioPrefix)
        handleLevelInput(scenarioPrefix)
    } else {
        for (final String setting in ['color', 'color_var', 'color_val', 'unit', 'duration', 'level', 'level_var', 'level_val']) {
            app.removeSetting("${scenarioPrefix}_${setting}")
        }
    }
    paragraph ''
}

/**
 * Render the indication section for the given scenario.
 * @param scenarioPrefix The prefix of the scenario to render the section for.
 * @param title The title of the section.
 */
void renderIndicationSection(final String scenarioPrefix, final String title = null) {
    final Map switchType = getTargetSwitchType()
    final String ledNumber = settings["${scenarioPrefix}_lednumber"]

    section(title) {
        input name: "${scenarioPrefix}_priority",
            title: '<b>Priority:</b>',
            type: 'enum',
            options: getPrioritiesList(scenarioPrefix),
            width: 2,
            required: true

        input name: "${scenarioPrefix}_lednumber",
            title: $/<span style='color: blue;'>LED Number</span>/$,
            type: 'enum',
            options: switchType.leds as Map<String, String>,
            width: 3,
            required: true,
            submitOnChange: true

        if (ledNumber == 'var') {
            input name: "${scenarioPrefix}_lednumber_var",
            title: "<span style=\'color: blue;\'>LED Number Variable</span>",
            type: 'enum',
            options: getGlobalVarsByType('integer').keySet(),
            width: 3,
            required: true
        } else {
            app.removeSetting("${scenarioPrefix}_lednumber_var")
        }

        // Effect
        if (ledNumber) {
            renderIndicationLedSection(switchType, scenarioPrefix)
        }
    }
}

/**
 * Render a specific rule for the given scenario.
 * @param scenarioPrefix The prefix of the scenario to render the rule for.
 * @param rule The rule to render.
 * @param isFirst Whether this is the first rule to render.
 * @param allScenariosMode Whether to render the rule in all scenarios mode.
 */
void renderRule(final String scenarioPrefix, final Map.Entry<String, Map> rule, final boolean isFirst, final boolean allScenariosMode) {
    final String key = "${scenarioPrefix}_${rule.key}"
    String status = evaluateRule(key, rule.value) ? ' &#128994;' : ''

    if (!isFirst) {
        paragraph allScenariosMode ? '<b>and</b>' : '<i>or</i>'
    }

    final Map<String, Map> inputs = rule.value.inputs as Map<String, Map>
    if (inputs.device) {
        renderDeviceInput(key, rule, inputs.device, status)
        status = ''
        if (!inputs.device.any && settings["${key}_device"] instanceof Collection && settings["${key}_device"]?.size() > 1) {
            renderDeviceAllInput(key, inputs.device)
        }
    }

    if (inputs.choice) {
        renderChoiceInput(key, rule, inputs.choice, status)
        status = ''
    }

    if (inputs.comparison) {
        renderComparisonInput(key, inputs.comparison)
        status = ''
    }

    if (inputs.value) {
        renderValueInput(key, inputs.value, status)
    }
}

/**
 * Renders the scenarios section.
 * This section is used to define the scenarios that will be used to activate the LED effect.
 * @param scenarioPrefix The prefix to use for the scenario settings.
 * @param sectionTitle The title to use for the section.
 */
void renderScenariosSection(final String scenarioPrefix, final String sectionTitle = null) {
    section(sectionTitle) {
        final Map<String, String> ruleTitles = ActivationRules.collectEntries { final String key, final Map value -> [(key), value.title] } as Map<String, String>
        final List<String> activeRules = (settings["${scenarioPrefix}_conditions"] ?: []) as List<String>
        final boolean allScenariosMode = settings["${scenarioPrefix}_conditions_all"] ?: false

        input name: "${scenarioPrefix}_conditions",
            title: '',
            type: 'enum',
            options: ruleTitles,
            multiple: true,
            required: true,
            width: 9,
            submitOnChange: true

        if (activeRules.size() > MAX_RULES) {
            paragraph "<span style='color: red;'>Maximum rules supported is ${MAX_RULES}.</span>"
            return
        }

        if (activeRules.size() > 1) {
            final String title = "${allScenariosMode ? '<b>All</b> rules' : '<b>Any</b> rule'}"
            input name: "${scenarioPrefix}_conditions_all", title: title, type: 'bool', width: 3, submitOnChange: true
        } else {
            paragraph ''
        }

        boolean isFirst = true
        final Map<String, Map> activeScenarioRules = ActivationRules.findAll { final String key, final Map value -> key in activeRules }
        for (final Map.Entry<String, Map> rule in activeScenarioRules) {
            renderRule(scenarioPrefix, rule, isFirst, allScenariosMode)
            isFirst = false
        }
    }
}

void renderScenarioNameSection(final String scenarioPrefix) {
    section {
        input name: "${scenarioPrefix}_name", title: '<b>Notification Scenario Name:</b>', type: 'text', defaultValue: getSuggestedScenarioName(scenarioPrefix), required: true, submitOnChange: true
        paragraph '<i>Higher value scenario priorities take LED precedence.</i>'
        input name: "test_${scenarioPrefix}", title: '&#9658; Test Effect', type: 'button', width: 2
        input name: 'reset', title: '<b>&#9724;</b> Stop Test', type: 'button', width: 2
    }
}

void renderValueInput(final String key, final Map valueInput, final String status) {
    final Object options = getValueOrClosure(valueInput.options, [
        device: settings["${key}_device"],
        choice: settings["${key}_choice"]
    ])
    final String optionsType = options ? 'enum' : 'text'

    input name: "${key}_value",
        title: (valueInput.title ?: 'Value') + status + ' ',
        width: valueInput.width ?: 3,
        defaultValue: valueInput.defaultValue,
        range: valueInput.range,
        options: options,
        type: valueInput.type ? valueInput.type : optionsType,
        submitOnChange: true, required: true
}

/**
 *  Creates a description string for the dashboard configuration for display.
 */
String getScenarioDescription(final String scenarioPrefix) {
    final Map<String, Object> config = getScenarioConfig(scenarioPrefix)
    final Map switchType = getTargetSwitchType()
    final StringBuilder str = new StringBuilder()

    str << "<b>Priority</b>: ${config.priority}, "

    if (config.lednumber == 'var') {
        str << "<b>LED Variable:</b> <i>${config.lednumber_var}</i>"
    } else if (config.lednumber) {
        str << "<b>${switchType.leds[config.lednumber as String] ?: 'n/a'}</b>"
    }

    if (config.effect == 'var') {
        str << ", <b>Effect Variable</b>: <i>${config.effect_var}</i>"
    } else if (config.effect) {
        final Map<String, String> fxOptions = switchType.effectsAll + switchType.effects
        str << ", <b>Effect:</b> ${fxOptions[config.effect as String] ?: 'n/a'}"
    }

    if (config.color == 'var') {
        str << ", <b>Color Variable:</b> <i>${config.color_var}</i>"
    } else if (config.color == 'val') {
        str << ', <b>Color Hue</b>: ' + getColorSpan(config.color_val as Integer, "#${config.color_val}")
    } else if (config.color) {
        str << ', <b>Color</b>: ' + getColorSpan(config.color as Integer, ColorMap[config.color as String])
    }

    if (config.level == 'var') {
        str << ", <b>Level Variable:</b> <i>${config.level_var}</i>"
    } else if (config.level == 'val') {
        str << ", <b>Level:</b> ${config.level_val}%"
    } else if (config.level) {
        str << ", <b>Level:</b> ${config.level}%"
    }

    if (config.duration && config.unit) {
        str << ", <b>Duration:</b> ${config.duration} ${TimePeriodsMap[config.unit as String]?.toLowerCase()}"
    }

    if (config.conditions) {
        final List<String> rules = getConditionDescriptions(config)
        final String allMode = config.conditions_all ? ' and ' : ' or '
        str << "\n<b>Activation${rules.size() > 1 ? 's' : ''}:</b> ${rules.join(allMode)}"
    }

    return str.toString()
}

/**
 * Creates a list description string for the active conditions.
 * @param config The scenario configuration.
 * @return A list of condition descriptions.
 */
List<String> getConditionDescriptions(final Map<String, Object> config) {
    final Closure<String> formatRule = { final String rule ->
        final Map ctx = createTemplateContext(config, rule)
        if (ActivationRules[rule].template) {
            return (String)runClosure((Closure) ActivationRules[rule].template, ctx) ?: ''
        }
        return ActivationRules[rule].title + ' <i>(' + ctx.device + ')</i>'
    }

    return (config.conditions as List<String>)
        .take(MAX_RULES)
        .findAll { final String rule -> ActivationRules.containsKey(rule) }
        .collect { final String rule -> formatRule(rule) } as List<String>
}

/**
 * Creates a display template context for the rule.
 * @param config The scenario configuration.
 * @param rule The rule to format.
 */
Map createTemplateContext(final Map<String, Object> config, final String rule) {
    final Map ctx = [
        device      : config["${rule}_device" as String],
        title       : ActivationRules[rule].title,
        comparison  : config["${rule}_comparison" as String],
        choice      : config["${rule}_choice" as String],
        value       : config["${rule}_value" as String],
        delay       : config.delay,
        cooldown    : config.cooldown
    ]

    if (ctx.device) {
        final boolean isAll = config["${rule}_device_all" as String]
        if (ctx.device instanceof List && ctx.device.size() > 2) {
            final String title = ActivationRules[rule].inputs.device.title.toLowerCase()
            ctx['device'] = "${ctx.device.size()} ${title}"
            ctx['device'] = (isAll ? 'All ' : 'Any of ') + ctx['device']
        } else if (ctx.device instanceof List) {
            ctx['device'] = ctx.device*.toString().join(isAll ? ' & ' : ' or ')
        }
    }

    if (ctx.comparison) {
        ctx['comparison'] = getComparisonsByType('number')[ctx.comparison as String]?.toLowerCase()
    }

    if (ctx.choice != null) {
        final Map choiceInput = ActivationRules[rule].inputs.choice
        final Object options = getValueOrClosure(choiceInput.options, [
            device: config["${rule}_device" as String]
            ]) ?: [:]
        if (options instanceof Map && config["${rule}_choice" as String] instanceof List) {
        ctx['choice'] = config["${rule}_choice" as String].collect { final String key -> options[key] ?: key }.join(' or ')
        } else if (options instanceof Map) {
        ctx['choice'] = options[config["${rule}_choice" as String]]
        }
    }

    if (ctx.value =~ /^([0-9]{4})-/) { // special case for time format
        ctx['value'] = new Date((timeToday(ctx.value) as Date).time).format('hh:mm a')
    }

    return ctx
}

/**
 * Application Button Handler
 */
void appButtonHandler(final String buttonName) {
    logDebug "button ${buttonName} pushed"
    switch (buttonName) {
        case 'duplicate':
            parent.duplicate(app.id)
            state['message'] = $/<span style='color: green'>Duplication complete</span>/$
            break
        case 'pause':
            logInfo 'pausing dashboard'
            state['paused'] = true
            updated()
            break
        case 'resume':
            logInfo 'resuming dashboard'
            state['paused'] = false
            updated()
            break
        case ~/^remove_(.+)/:
            final String prefix = ((List)Matcher.lastMatcher[0])[1] as String
            if (prefix) {
                removeSettings(prefix)
            }
            break
        case 'reset':
            runInMillis(200, 'notificationDispatcher')
            break
        case ~/^test_(.+)/:
            final String prefix = ((List)Matcher.lastMatcher[0])[1] as String
            if (prefix) {
                final Map<String, String> config = getScenarioConfig(prefix) as Map<String,String>
                replaceVariables(config)
                ((String)config.lednumber).tokenize(',').each { final String ledNumber ->
                    updateSwitchLedState([
                        color    : config.color,
                        effect   : config.effect,
                        lednumber: ledNumber,
                        level    : config.level,
                        name     : config.name,
                        prefix   : config.prefix,
                        priority : config.priority,
                        unit     : config.unit,
                        duration : config.duration
                    ])
                }
            }
            break
        default:
            logWarn "unknown app button ${buttonName}"
            break
    }
}

/**** END USER INTERFACE *********************************************************************/

/**
 * Common event handler used by all rules.
 * @param event The event to process.
 */
void eventHandler(final Event event) {
    final Map lastEvent = [
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
 * Tracks the led state changes and update the device tracker object.
 * Only supported for drivers with an 'ledEffect' attribute.
 * @param event The event to process.
 */
void switchStateTracker(final Event event) {
    final Map<String, Map> tracker = SwitchStateTracker[event.device.id]

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
                final String led = (Matcher.lastMatcher[0] as List)[1]
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

        final int seconds = 60 * (settings.periodicRefreshInterval ?: 60)
        runIn(seconds, 'forceRefresh')
    }
}

/**
 * Scheduled trigger used for rules that involve sunset times
 */
void sunsetTrigger() {
    logInfo 'executing sunset trigger'
    notificationDispatcher()
    subscribeAllScenarios()
}

/**
 * Scheduled trigger used for rules that involve sunrise times
 */
void sunriseTrigger() {
    logInfo 'executing sunrise trigger'
    notificationDispatcher()
    subscribeAllScenarios()
}

/**
 * Scheduled trigger used for rules that involve time
 */
void timeAfterTrigger() {
    logInfo 'executing time after trigger'
    notificationDispatcher()
    subscribeAllScenarios()
}

/**
 * Scheduled stop used for devices that don't have built-in timers
 */
void stopNotification() {
    logDebug 'stopNotification called'
    final Map switchType = getTargetSwitchType()
    for (final DeviceWrapper device in (settings['switches'] as List<DeviceWrapper>)) {
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
 * Main dispatcher for setting device LED notifications
 * It first gets a prioritized list of dashboards, then evaluates the current dashboard
 * condition rules to get a map of dashboard results. It then processes any delayed conditions
 * and calculates the desired LED states for each dashboard. Finally, it dispatches each LED
 * state to devices and schedules the next evaluation time if needed.
 */
@CompileStatic
void notificationDispatcher() {
    // Get prioritized list of dashboards
    final List<String> prefixes = getSortedScenarioPrefixes()

    // Evaluate current dashboard condition rules
    final Map<String, Boolean> dashboardResults = evaluateDashboardScenarios(prefixes)

    // Process any delayed conditions
    final long nextEvaluationTime = evaluateDelayedRules(dashboardResults)

    // Calculate desired LED states
    final Collection<Map> ledStates = calculateLedStates(dashboardResults)

    // Dispatch each LED state to devices
    ledStates.each { final config -> updateSwitchLedState(config) }

    // Schedule the next evaluation time
    if (nextEvaluationTime > getTimeMs()) {
        final long delay = nextEvaluationTime - getTimeMs()
        logDebug "[notificationDispatcher] scheduling next evaluation in ${delay}ms"
        runAfterMs(delay, 'notificationDispatcher')
    }
}

/**
 * Dashboard evaluation function responsible for iterating each condition over
 * the dashboards and returning a map with true/false result for each dashboard prefix
 * Does not apply any delay or cool-down options at this stage of the pipeline
 * @param prefixes List of dashboard prefixes
 * @return Map of dashboard prefixes and their evaluation results
 */
@CompileStatic
Map<String, Boolean> evaluateDashboardScenarios(final List<String> prefixes) {
    return prefixes.collectEntries { final prefix -> [prefix, evaluateActivationRules(prefix)] }
}

/**
 * This code evaluates rules that have been delayed or have a cool down period.
 * It takes in a map of strings and booleans, which represent the evaluation results of the rules.
 * It then checks if there is a delay before activation or a cool down period set for each rule.
 * If so, it sets the evaluation result to false for the delayed rule and true for the cooled down rule.
 * Finally, it returns the next evaluation time based on when each rule should be evaluated again.
 * @param evaluationResults Map of strings and booleans representing the evaluation results of the rules
 * @return The next evaluation time
 */
@CompileStatic
long evaluateDelayedRules(final Map<String, Boolean> evaluationResults) {
    long nextEvaluationTime = 0
    for (final Map.Entry<String, Boolean> result in evaluationResults) {
        String scenarioPrefix = result.key
        boolean active = result.value
        long now = getTimeMs()

        // Check if delay before activation is configured
        String delayKey = "${scenarioPrefix}_delay"
        Integer delay = getSettingInteger(delayKey)
        if (active && delay) {
            int delayMs = delay * 60000
            // Determine if delay has expired yet
            long targetTime = getState().computeIfAbsent(delayKey) { final key -> getTimeMs(delayMs) } as long
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
        String coolDownKey = "${scenarioPrefix}_cooldown"
        if (getSetting(coolDownKey)) {
            long targetTime = (long) getState(coolDownKey, 0)
            if (active) {
                setState(coolDownKey, -1) // mark that it has been active
            } else if (targetTime == -1) {
                int delayMs = (getSettingInteger(coolDownKey) ?: 0) * 60000
                targetTime = getTimeMs(delayMs)
                setState(coolDownKey, targetTime) // set expiration time when first inactive
                evaluationResults[scenarioPrefix] = true
                logDebug "[evaluateDelayedRules] ${scenarioPrefix} has cooldown (${delayMs}ms)"
            } else if (targetTime > 0 && now < targetTime) {
                // still in cool down period
                evaluationResults[scenarioPrefix] = true
            } else if (now > targetTime) {
                // we are done with cool down so remove the state
                removeState(coolDownKey)
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
 * Calculate Notification LED States from condition results.
 * It takes in two parameters: a list of strings (prefixes) and a map of strings and booleans (results).
 * The code iterates through each prefix in the list, gets the scenario configuration for that prefix,
 * and stores it in a map. It then checks if the result for that prefix is true or false. If it is true,
 * it replaces any variables in the configuration, checks if the new priority is greater than or equal
 * to the old priority, and stores it in another map. If it is false and auto stop is not false, then it
 * stores an auto stop effect with lowest priority into the same map.
 * Finally, this code returns the ledStates map.
 * @param prefixes List of dashboard prefixes
 * @param results Map of dashboard prefixes and their evaluation results
 * @return Map of LED states
 */
@CompileStatic
Collection<Map> calculateLedStates(final Map<String, Boolean> results) {
    // Initialize the LED states map
    final Map<String, Map> ledStates = [:]

    // Iterate through each scenario prefix
    for (final Map.Entry<String, Boolean> result in results) {
        // Get scenario config for the given prefix
        Map<String, Object> config = getScenarioConfig(result.key)

        // Get the previous state of the LED, if available
        String ledNumberKey = config.lednumber as String
        Map<String, Map> oldState = ledStates[ledNumberKey] ?: [:]
        int oldPriority = oldState.priority as Integer ?: 0

        // If the scenario is true in the results, update the LED states
        if (result.value) {
            // Replace variables in the config
            replaceVariables(config)

            // Get the new priority from the config
            int newPriority = config.priority as Integer ?: 0

            // Update the LED states if the new priority is higher or equal to the old priority
            if (newPriority >= oldPriority) {
                // Get the list of LED numbers from the config
                final List<String> ledNumbers = ledNumberKey.tokenize(',')

                // Update the state for each LED number
                ledNumbers.each { final String ledNumber ->
                    ledStates[ledNumber] = [
                        prefix   : config.prefix,
                        name     : config.name,
                        lednumber: ledNumber,
                        priority : config.priority,
                        effect   : config.effect,
                        color    : config.color,
                        level    : config.level,
                        unit     : config.unit,
                        duration : config.duration
                    ]
                }
            }
        } else if (config.autostop != false && !oldPriority) {
            // If auto stop is enabled and the old priority is zero then stop
            final List<String> ledNumbers = ledNumberKey.tokenize(',')

            // Update the state for each LED number with the stop effect
            ledNumbers.each { final String ledNumber ->
                ledStates[ledNumber] = [
                    prefix   : config.prefix,
                    name     : "[auto stop] ${config.name}",
                    lednumber: ledNumber,
                    priority : 0,       // lowest priority
                    effect   : '255',   // stop effect code
                    color    : '0',     // default color
                    level    : '100',   // default level
                    unit     : '255'    // Infinite
                ]
            }
        }
    }

    // Return the updated LED states
    return ledStates.values()
}

/*
 * Private Implementation Helper Methods
 */

/**
 * Calculates the total milliseconds from Inovelli duration parameter (0-255)
 * where 1-60 = seconds, 61-120 = 1-60 minutes, 121-254 = 1-134 hours, 255 = Indefinite (24 hrs)
 * @param duration The duration parameter
 * @return The total milliseconds
 */
@CompileStatic
private static int convertParamToMs(final Integer duration) {
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

/**
 * Utility method for displaying CSS colored text.
 * @param hue The hue value (0-360)
 * @param text The text to display
 * @return The HTML span with the color
 */
private static String getColorSpan(final Integer hue, final String text) {
    if (hue != null && text) {
        final String css = (hue == 360) ? 'white' : "hsl(${hue}, 50%, 50%)"
        return "<span style=\'color: ${css}\'>${text}</span>"
    }
    return 'n/a'
}

/**
 * Returns the switch state tracker for the given device.
 * @param device The device
 * @return The switch state tracker
 */
@CompileStatic
private static Map<String, Map> getSwitchStateTracker(final DeviceWrapper device) {
    return SwitchStateTracker.computeIfAbsent(device.id) { final key -> [:].withDefault { [:] } }
}

/**
 * Takes in a list of DeviceWrapper objects, an attribute, an operator, and a value.
 * It then evaluates whether all or any of the DeviceWrapper objects have a currentValue
 * for the given attribute that satisfies the comparison with the given value using the given operator.
 * It returns true if all or any of the DeviceWrapper objects satisfy this comparison, depending on the
 * value of "all", and false otherwise.
 * @param devices List of DeviceWrapper objects
 * @param attribute The attribute to check
 * @param operator The operator to use for comparison
 * @param value The value to compare against
 * @param all Whether to check if all or any of the devices satisfy the comparison
 * @return Whether all or any of the devices satisfy the comparison
 */
@CompileStatic
private static boolean deviceAttributeHasValue(final List<DeviceWrapper> devices, final String attribute, final String operator, final String value, final Boolean all) {
    // if no devices, return false
    if (devices == null || devices.isEmpty()) {
        return false
    // if no attribute, operator, or value, return false
    } else if (attribute == null || operator == null || value == null) {
        return false
    }
    final Closure test = { final DeviceWrapper device -> evaluateComparison(device.currentValue(attribute) as String, value, operator) }
    // if all is true, return true if all devices satisfy the comparison otherwise return true if any satisfy the comparison
    return all == true ? devices.every(test) : devices.any(test)
}

/**
 * Takes in three parameters: two strings (a and b) and an operator and evaluates the comparison
 * between the two strings based on the operator and returns a boolean value.
 * @param valueA The first string
 * @param valueB The second string
 * @param operator The operator to use for comparison
 * @return Whether the comparison is true or false
 */
@CompileStatic
private static boolean evaluateComparison(final String valueA, final String valueB, final String operator) {
    if (valueA && valueB && operator) {
        switch (operator) {
            case '=': return valueA.equalsIgnoreCase(valueB)
            case '!=': return !valueA.equalsIgnoreCase(valueB)
            case '<>': return !valueA.equalsIgnoreCase(valueB)
            case '>': return new BigDecimal(valueA) > new BigDecimal(valueB)
            case '>=': return new BigDecimal(valueA) >= new BigDecimal(valueB)
            case '<': return new BigDecimal(valueA) < new BigDecimal(valueB)
            case '<=': return new BigDecimal(valueA) <= new BigDecimal(valueB)
        }
    }
    return false
}

/**
 * Given a set of devices and an attribute name, provides the distinct set of attribute names.
 * @param devices List of DeviceWrapper objects
 * @return The list of distinct attribute names
 */
@CompileStatic
private static List<String> getAttributeChoices(final List<DeviceWrapper> devices) {
    return devices?.collectMany { final DeviceWrapper device -> device.getSupportedAttributes()*.name }
}

/**
 * Given a set of devices and an attribute name, provides the distinct set of attribute values.
 * Iterate through each DeviceWrapper object in the list and find the supported attributes
 * that match the given attribute string and if so, it gets the values associated with that
 * attribute and adds them to the returned list.
 * @param devices List of DeviceWrapper objects
 * @param attribute The attribute name
 * @return The list of distinct attribute values
 */
@CompileStatic
private static List<String> getAttributeOptions(final List<DeviceWrapper> devices, final String attribute) {
    return devices?.collectMany { final DeviceWrapper device ->
        final List<String> values = device.getSupportedAttributes().find { final attr -> attr.name == attribute }?.getValues()
        return values ?: Collections.emptyList() as List<String>
    }
}

/**
 * Given a set of button devices, provides the list of valid button numbers that can be chosen from.
 * It then iterates through the devices to find the maximum number of buttons among all of the
 * button devices. If there is at least one button, it creates a map with entries for each button
 * number (1 to maxButtons) and its corresponding label ("Button n"). If there are no buttons,
 * it returns an empty map.
 * @param buttonDevices List of DeviceWrapper objects
 * @return The map of button numbers and labels
 */
@CompileStatic
private static Map<String, String> getButtonNumberChoices(final List<DeviceWrapper> buttonDevices) {
    if (buttonDevices) {
        final List<Integer> buttonCounts = buttonDevices.collect { final DeviceWrapper device ->
            device.currentValue('numberOfButtons') as Integer ?: 0
        }
        final Integer maxButtons = buttonCounts.max()
        if (maxButtons >= 1) {
            return (1..maxButtons).collectEntries { final int buttonNumber -> [buttonNumber as String, "Button ${buttonNumber}"] }
        }
    }
    return Collections.emptyMap()
}

/**
 * Returns a map of valid comparison choices for dropdown depending on the type that is passed in.
 * If the type is "number", "integer", or big decimal, then the Map will contain additional entries
 * for "<", "<=", ">", and ">=" otherwise the Map will only contain two entries for '=' and '<>'.
 * @param type The type of the attribute
 * @return The map of comparison choices
 */
@CompileStatic
private static Map<String, String> getComparisonsByType(final String type) {
    Map<String, String> result = ['=': 'Equal to', '<>': 'Not equal to']
    switch (type.toLowerCase()) {
        case 'number':
        case 'integer':
        case 'bigdecimal':
            result += [
                '<' : 'Less than',
                '<=': 'Less or equal to',
                '>' : 'Greater than',
                '>=': 'Greater or equal to'
            ]
            break
    }
    return result
}

/**
 * Determines whether the tracker has changed.
 * @param map1 The first map
 * @param map2 The second map
 * @return Whether the tracker has changed
 */
private static boolean isTrackerChanged(final Map map1, final Map map2) {
    if (map1 == null || map2 == null) {
        return true
    }
    final List<String> keys = ['effect', 'color', 'level', 'unit', 'duration']
    return keys.any { final String key -> map1[key] != map2[key] }
}

/**
 * Cleans application settings removing entries no longer in use.
 */
@CompileStatic
private void cleanSettings() {
    for (final String prefix in getScenarioPrefixes()) {
        // Clean unused dashboard settings
        List<String> rules = (List) getSetting("${prefix}_conditions", [])
        ActivationRules.keySet()
            .findAll { final String key -> !(key in rules) }
            .each { final String key -> removeSettings("${prefix}_${key}") }

        // Clean unused variable settings
        ['lednumber', 'effect', 'color'].each { final var ->
            if (getSettingString("${prefix}_${var}") != 'var') {
                removeSetting("${prefix}_${var}_var")
            }
        }
    }
}

/**
 * Given a value or closure, returns the value or the result of running the closure.
 * @param valueOrClosure The value or closure
 * @param ctx The context map to provide to the closure
 * @return The value or the result of running the closure
 */
private Object getValueOrClosure(final Object valueOrClosure, final Map ctx) {
    return valueOrClosure instanceof Closure ? runClosure(valueOrClosure as Closure, ctx) : valueOrClosure
}

/**
 * Returns next available scenario settings prefix used when adding a new scenario.
 * It iterates through the settings and finds the highest scenario prefix number and
 * increments it by one to get the next available prefix.
 * @return The next available scenario settings prefix
 */
@CompileStatic
private String findNextPrefix() {
    final List<Integer> keys = getScenarioPrefixes().collect { final String prefix -> prefix.substring(10) as Integer }
    final int maxId = keys ? Collections.max(keys) : 0
    return "condition_${maxId + 1}"
}

/**
 * Returns key value map of scenario settings for the given prefix.
 * @param scenarioPrefix The scenario settings prefix
 * @return The key value map of scenario settings
 */
@CompileStatic
private Map<String, Object> getScenarioConfig(final String scenarioPrefix) {
    final Map<String, Object> config = [prefix: (Object) scenarioPrefix]
    final int startPos = scenarioPrefix.size() + 1
    getAppSettings().findAll { final String key, final Object value ->
        key.startsWith(scenarioPrefix + '_')
    }.each { final String key, final Object value ->
        config[key.substring(startPos)] = value
    }
    return config
}

/**
 * Returns the set of scenario prefixes from the settings.
 * @return The set of scenario prefixes
 */
@CompileStatic
private Set<String> getScenarioPrefixes() {
    return (Set<String>) getAppSettings().keySet().findAll { final Object key ->
        key.toString().matches('^condition_[0-9]+_priority$')
    }.collect { final Object key -> key.toString() - '_priority' }
}

/**
 * Returns scenario setting prefix sorted by priority then name.
 * @return The sorted list of scenario prefixes
 */
@CompileStatic
private List<String> getSortedScenarioPrefixes() {
    return getScenarioPrefixes().collect { final String scenarioPrefix ->
        [
            prefix  : (String) scenarioPrefix,
            name    : getSettingString("${scenarioPrefix}_name"),
            priority: getSettingInteger("${scenarioPrefix}_priority")
        ]
    }.sort { final valueA, final valueB ->
        (Integer) valueB.priority <=> (Integer) valueA.priority ?: (String) valueA.name <=> (String) valueB.name
    }*.prefix as List<String>
}

/**
 * Returns the device type configuration for the currently switch type setting.
 * @return The device type configuration
 */
@CompileStatic
private Map getTargetSwitchType() {
    final String deviceType = getSettingString('deviceType')
    final Map switchType = SupportedSwitchTypes[deviceType]
    if (!switchType) {
        logError "Unable to retrieve supported switch Type for '${deviceType}'"
    }
    return switchType
}

/**
 * Looks up a variable in a given dropdown map and returns the key if it exists.
 * It first gets the value of the variable from the 'getHubVariableValue' function
 * and then checks if it is present in the lookup table. If it is present,
 * it returns the key associated with that value. Otherwise, it returns null.
 * @param variableName The name of the variable to lookup
 * @param lookupTable The lookup table to use
 * @return The key associated with the variable value or null if not found
 */
@CompileStatic
private String lookupVariable(final String variableName, final Map<String, String> lookupTable) {
    final String variableValue = getHubVariableValue(variableName) as String
    if (variableValue) {
        return lookupTable.find { final String key, final String value -> value.equalsIgnoreCase(variableValue) }?.key
    }
    return null
}

/**
 * Removes all settings starting with the given prefix used when the user deletes a condition.
 * @param scenarioPrefix The scenario settings prefix
 */
@CompileStatic
private void removeSettings(final String scenarioPrefix) {
    final Set<String> entries = getAppSettings().keySet().findAll { final String name -> name.startsWith(scenarioPrefix) }
    if (entries) {
        logDebug "removing settings ${entries}"
        entries.each { final String name -> removeSetting(name) }
    }
}

/**
 * Replace variables in the scenario configuration settings with the appropriate values.
 * Checks if any of the variables (led-number, effect, color, level) are set to 'var'.
 * If so, looks up the hub global variable value and assigns it to the configuration.
 * @param config The scenario configuration settings
 * @return The scenario configuration settings with variables replaced
 */
@CompileStatic
private void replaceVariables(final Map<String, Object> config) {
    final Map switchType = getTargetSwitchType()
    if (switchType) {
        if (config.lednumber == 'var') {
            config['lednumber'] = lookupVariable((String) config.lednumber_var, (Map) switchType.leds) ?: 'All'
        }
        if (config.effect == 'var') {
            final Map<String, String> fxOptions = (Map<String, String>) switchType.effectsAll + (Map<String, String>) switchType.effects
            config['effect'] = lookupVariable((String) config.effect_var, fxOptions) as String
        }
        if (config.color == 'var') {
            config['color'] = lookupVariable((String) config.color_var, ColorMap) as String
        }
        if (config.color == 'val') {
            config['color'] = config.color_val as String
        }
        if (config.level == 'var') {
            config['level'] = getHubVariableValue((String) config.level_var) as String
        }
        if (config.level == 'val') {
            config['level'] = config.level_val as String
        }
    }
}

/**
 * Reset the notifications of a device. It first creates a map of all the LEDs associated with
 * the device. Then iterates through each led, setting its name to 'clear notification',
 * priority to 0, effect to 255 (stop effect code), color to 0 (default color), level to 100
 * (default level), and unit to 255 (infinite).
 */
@CompileStatic
private void resetNotifications() {
    final Map<String, String> ledMap = (Map<String, String>) getTargetSwitchType().leds
    if (ledMap) {
        logInfo 'resetting all device notifications'
        ledMap.keySet().findAll { final String ledNumber -> ledNumber != 'var' }.each { final String ledNumber ->
            updateSwitchLedState(
                [
                    color    : '0',       // default color
                    effect   : '255',     // stop effect code
                    lednumber: ledNumber,
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
 * Restores the color of a device from the state map.
 * @param device The device to restore
 * @param key The key to use to restore the device color from state
 */
private void restoreDeviceColor(final DeviceWrapper device, final String key) {
    if (state[key]?.level) {
        logDebug "${device}.setColor(${state[key]})"
        device.setColor([
            hue       : state[key].hue as Integer,
            saturation: state[key].saturation as Integer,
            level     : state[key].level as Integer
        ])
        removeState(key)
    } else {
        logDebug "${device}.off()"
        device.off()
    }
}

/**
 * Saves the color of a device to the state map.
 * @param device The device to save
 * @param key The key to use to save the device color to state
 */
private void saveDeviceColor(final DeviceWrapper device, final String key) {
    state[key] = [
        hue       : device.currentValue('hue') ?: 0,
        saturation: device.currentValue('saturation') ?: 0,
        level     : device.currentValue('level') ?: 0,
    ]
}

/**
 * Schedules the stopNotification method to run after the duration of the notification.
 * @param config The configuration to use to determine the duration of the notification
 */
private void scheduleStopNotification(final Map config) {
    if (config.unit && config.unit != '255') {
        final int duration = convertParamToMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
        logDebug 'scheduling stopNotification in ' + duration + 'ms'
        runInMillis(duration + 1000, 'stopNotification')
    } else {
        unschedule('stopNotification')
    }
}

/**
 * Provides a wrapper around the color device driver methods
 * @param device the device to update
 * @param config the configuration to update the device with
 */
private void setColorDeviceEffect(final DeviceWrapper device, final Map config) {
    final String key = "device-state-${device.id}"

    switch (config.effect) {
        case '0': // Off
            turnDeviceOff(device, key)
            break
        case '1': // On
            if (device.currentValue('switch') == 'on') {
                saveDeviceColor(device, key)
            }
            setDeviceColor(device, config)
            break
        case '255':
            restoreDeviceColor(device, key)
            break
    }

    scheduleStopNotification(config)
}

/**
 * Provides a wrapper around the color device driver methods
 * @param device the device to update
 * @param config the configuration to update the device with
 */
private void setDeviceColor(final DeviceWrapper device, final Map config) {
    final int color = config.color as int
    final int level = config.level as int
    final int huePercent = (int) Math.round((color / 360.0) * 100)
    logDebug "${device}.setColor(${huePercent})"
    device.setColor([
        hue       : huePercent,
        saturation: 100,
        level     : level
    ])
}

/**
 * Provides a wrapper around the ledEffect driver methods for the Inovelli Blue devices.
 * This method translates the values in the config map to the appropriate values for the device.
 * @param device the device to update
 * @param config the configuration to update the device with
 */
private void setInovelliBlueLedEffect(final DeviceWrapper device, final Map config) {
    int color = 0, duration = 0, effect = 0, level = 0

    // Calculate duration based on given unit and duration values, with a max value of 255
    if (config.unit) {
        duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255)
    }

    // Calculate color value based on given color value (0-360), scaling it to a range of 0-255
    if (config.color) {
        color = (int) Math.min(Math.round(((config.color as Integer) / 360.0) * 255), 255)
    }

    // Set the effect value if provided
    if (config.effect) {
        effect = config.effect as int
    }

    // Set the level value if provided
    if (config.level) {
        level = config.level as int
    }

    if (config.lednumber == 'All') {
        // Apply the effect, color, level, and duration to all LEDs if 'All' is specified
        logDebug "${device}.ledEffectALL(${effect},${color},${level},${duration})"
        device.ledEffectAll(effect, color, level, duration)
    } else {
        // Otherwise, apply the effect, color, level, and duration to the specified LED number
        logDebug "${device}.ledEffectONE(${config.lednumber},${effect},${color},${level},${duration})"
        device.ledEffectOne(config.lednumber as int, effect, color, level, duration)
    }
}

/**
 * Provides a wrapper around the Inovelli device driver setConfigParameter method.
 * @param device the device to update
 * @param config the configuration to update the device with
 */
private void setInovelliRedGen1Effect(final DeviceWrapper device, final Map config) {
    int color = 0, effect = 0, level = 0

    // Calculate color based on the provided color value, ensuring it remains within the valid range (0-254)
    if (config.color) {
        color = config.color as int
        // Normalize color value to a range between 0 and 255
        if (color <= 2) {
            color = 0
        } else if (color >= 356) {
            color = 255
        } else {
            color = (int)((color / 360) * 255)
        }
    }

    // Calculate level based on the provided level value
    if (config.level) {
        level = Math.round((config.level as int) / 10) as int
    }

    // Set effect based on the provided effect value
    if (config.effect) {
        effect = config.effect as int
    }

    // If the effect value is 255, stop the notification and reset all values
    if (effect == 255) {
        level = color = 0
    }

    final Map switchType = getTargetSwitchType()

    // Set LED level parameter
    logDebug "${device}.setConfigParameter(${switchType.ledLevelParam},${level},'1')"
    device.setConfigParameter(switchType.ledLevelParam as int, level, '1')

    // Set LED color parameter if level is greater than 0
    if (level > 0) {
        logDebug "${device}.setConfigParameter(${switchType.ledColorParam},${color},'2')"
        device.setConfigParameter(switchType.ledColorParam as int, color, '2')
    }

    // Schedule stopNotification
    scheduleStopNotification(config)
}

/**
 * Provides a wrapper around the Inovelli device driver start-notification method.
 * This code will no longer be required when the updated Gen2 driver is released with the
 * startNotification command.
 * Reference https://nathanfiscus.github.io/inovelli-notification-calc/
 * @param device the device to update
 * @param config the configuration to update the device with
 */
private void setInovelliRedGen2Effect(final DeviceWrapper device, final Map config) {
    int color = 0, duration = 0, effect = 0, level = 0

    // Calculate duration based on given unit and duration values, with a max value of 255
    if (config.unit != null) {
        duration = Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255)
    }

    // Calculate color value based on given color value (0-360), scaling it to a range of 0-255
    if (config.color != null) {
        color = (int)Math.min(Math.round(((config.color as Integer) / 360.0) * 255), 255)
    }

    // Set level if available in config
    if (config.level) {
        level = (int)Math.round((config.level as int) / 10)
    }

    // Set effect if available in config
    if (config.effect) {
        effect = config.effect as int
    }

    // Reset effect, duration, level, and color if effect is 255 (stop notification)
    if (effect == 255) {
        effect = duration = level = color = 0
    }

    // Create byte array with effect, duration, level, and color values
    final byte[] bytes = [
        effect as byte,
        duration as byte,
        level as byte,
        color as byte
    ]

    // Convert byte array to integer value
    final int value = new BigInteger(bytes).intValue()

    // Log notification details
    logDebug "${device}.startNotification(${value}) [${bytes[0] & 0xff}, ${bytes[1] & 0xff}, ${bytes[2] & 0xff}, ${bytes[3] & 0xff}]"

    // Send notification to device based on 'led-number' in config
    if (config.lednumber == 'All') {
        device.startNotification(value)
    } else {
        device.startNotification(value, config.lednumber as int)
    }
}

private void turnDeviceOff(final DeviceWrapper device, final String key) {
    logDebug "${device}.off()"
    device.off()
    state.remove(key)
}


/**
 * Subscribes to all dashboard scenario rules. The method first gets all the scenario prefixes,
 * then iterates through each one of them and calls the subscribeActiveRules() method with
 * the scenario prefix as an argument.
 */
@CompileStatic
private void subscribeAllScenarios() {
    getScenarioPrefixes().each { final String scenarioPrefix ->
        subscribeActiveRules(scenarioPrefix)
    }
}

/**
 * Subscribes to switch attributes. Iterates a list of attributes (in this case, 'ledEffect')
 * and checks if each switch device has that attribute. If it does, then it subscribes to the
 * attribute with the 'switchStateTracker' method.
 */
private void subscribeSwitchAttributes() {
    final List<String> attributes = ['ledEffect']
    settings.switches.each { final DeviceWrapper device ->
        attributes.each { final attr ->
            if (device.hasAttribute(attr)) {
                subscribe(device, attr, 'switchStateTracker', null)
            }
        }
    }
}

/**
 * Provides a wrapper around the device driver methods for updating the LED state of a device.
 * It checks the available commands on the device and calls the appropriate method.
 * @param device the device to update
 * @param config the configuration to update the device with
 */
private void updateDeviceLedState(final DeviceWrapper device, final Map config) {
    if (device.hasCommand('ledEffectOne')) {
        setInovelliBlueLedEffect(device, config)
    } else if (device.hasCommand('startNotification')) {
        setInovelliRedGen2Effect(device, config)
    } else if (device.hasCommand('setConfigParameter')) {
        setInovelliRedGen1Effect(device, config)
    } else if (device.hasCommand('setColor')) {
        setColorDeviceEffect(device, config)
    } else {
        logWarn "Unable to determine notification command for ${device}"
        return
    }

    if (settings.metering && settings.meteringInterval > 0) {
        pauseExecution(settings.meteringInterval as int)
    }
}

/**
 * Provides a wrapper around driver specific commands for updating the LED state of a device.
 * It loops through a list of devices and checks if the LED state has changed.
 * If it has, it will update the LED state accordingly.
 * Finally, it will set an expiration time for the LED state based on the unit and duration parameters.
 */
@CompileStatic
private void updateSwitchLedState(final Map config) {
    final List<DeviceWrapper> devices = getSetting('switches', []) as List<DeviceWrapper>

    for (final DeviceWrapper device in devices) {
        logDebug "Setting ${device} LED #${config.lednumber} (id=${config.prefix}, name=${config.name}, " +
                "priority=${config.priority}, effect=${config.effect ?: ''}, color=${config.color}, " +
                "level=${config.level}, duration=${config.duration ?: '(none)'} " +
                "${TimePeriodsMap[config.unit as String] ?: ''})"

        Map<String, Map> tracker = getSwitchStateTracker(device)
        String key = config.lednumber
        boolean isTrackerExpired = (Long) tracker[key]?.expires <= getTimeMs()
        if (isTrackerChanged(tracker[key], config) || isTrackerExpired) {
            updateDeviceLedState(device, config)

            int offset = convertParamToMs(Math.min(((config.unit as Integer) ?: 0) + ((config.duration as Integer) ?: 0), 255))
            config['expires'] = getTimeMs(offset)
            tracker[key] = config
        } else {
            logDebug "Skipping update to ${device} (no change detected)"
        }
    }
}

/**
 * Evaluates all activation rules for a given prefix. It first gets the boolean value of the
 * "requireAll" flag and the name associated with the prefix. It then loops through all
 * conditions associated with the prefix and evaluates each rule. If the "requireAll" flag is true,
 * it will return false if any of the rules fail. If it is false, it will return true if any of the rules pass.
 * @param prefix the prefix to evaluate
 * @return true if the activation rules pass, false otherwise
 */
@CompileStatic
private boolean evaluateActivationRules(final String prefix) {
    boolean result = false
    final boolean requireAll = getSettingBoolean("${prefix}_conditions_all")
    final String name = getSettingString("${prefix}_name")

    // Loop through all conditions updating the result
    final List<String> activeRules = (getSetting("${prefix}_conditions", []) as List<String>).take(MAX_RULES)
    for (final String ruleKey in activeRules) {
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
 * Evaluates a specific rule for the given prefix.
 * It gets the device, choice, and value from the settings based on the given prefix.
 * Then it runs a closure to get an attribute value and stores it in a context map.
 * The context map also contains the all, choice, comparison, device, event, and value settings
 * Finally, it runs a closure to test the rule passing in the context map and returns true
 * or false depending on the result.
 * @param prefix the prefix to evaluate
 * @param rule the rule to evaluate
 * @return true if the rule passes, false otherwise
 */
@CompileStatic
private boolean evaluateRule(final String prefix, final Map rule) {
    if (!rule) {
        return false
    }
    final String attribute = getValueOrClosure(rule.subscribe, [
        device: getSetting("${prefix}_device"),
        choice: getSetting("${prefix}_choice"),
        value : getSetting("${prefix}_value")
    ]) as String
    final Map ctx = [
        all       : getSettingBoolean("${prefix}_device_all"),
        attribute : attribute,
        choice    : getSetting("${prefix}_choice"),
        comparison: getSetting("${prefix}_comparison"),
        device    : getSetting("${prefix}_device"),
        event     : getState('lastEvent') ?: [:],
        value     : getSetting("${prefix}_value")
    ].asImmutable()
    final boolean result = runClosure((Closure) rule.test, ctx) ?: false
    logDebug "[evaluateRule] ${rule.title} (${prefix}) is ${result}"
    return result
}

/**
 * Used to subscribe to active rules for the provided condition prefix argument.
 * Iterates through the active rules from the setting with the given prefix.
 * For each rule, it checks if there is an execute closure, and if so, runs it with a context
 * of device, choice, and value. It then checks if there is a subscribe closure or attribute
 * and subscribes to the event handler with the given device (or location) and attribute.
 * @param prefix the prefix to subscribe to
 */
@CompileStatic
private void subscribeActiveRules(final String prefix) {
    final List<String> activeRules = (List) getSetting("${prefix}_conditions", Collections.emptyList())
    for (final String ruleKey in activeRules) {
        Map<String, Map> rule = ActivationRules[ruleKey]
        String key = "${prefix}_${ruleKey}"
        if (rule.execute instanceof Closure) {
            runClosure((Closure) rule.execute, [
                device: getSetting("${key}_device"),
                choice: getSetting("${key}_choice"),
                value : getSetting("${key}_value")
            ])
        }
        if (rule.subscribe) {
            String attribute = getValueOrClosure(rule.subscribe, [
                device: getSetting("${key}_device"),
                choice: getSetting("${key}_choice"),
                value : getSetting("${key}_value")
            ]) as String
            subscribeEventHandler(getSetting("${key}_device") ?: getLocation(), attribute)
        }
    }
}

/**
 * Subscribe to all variables used in the dashboard by finding all settings with keys that end with
 * '_var'. The variable names are then iterated over and subscribeEventHandler() is called for each.
 */
@CompileStatic
private void subscribeAllVariables() {
    getAppSettings().findAll { final Map.Entry<String, Object> entry -> entry.key.endsWith('_var') }.values().each { final Object var ->
        subscribeEventHandler(location, "variable:${var}")
    }
}

/**
 * Subscribes an event handler to a target for a given attribute.
 * If the attribute starts with "variable:", variable to a list of global variables in use.
 * @param target the target to subscribe to
 * @param attribute the attribute to subscribe to
 */
private void subscribeEventHandler(final Object target, final String attribute) {
    logDebug "subscribing to ${target} for attribute '${attribute}'"
    subscribe(target, attribute, 'eventHandler', null)
    if (attribute.startsWith('variable:')) {
        final String variable = attribute.substring(9)
        logDebug "registering use of Hub variable '${variable}'"
        addInUseGlobalVar(variable)
    }
}

/**
 * Creates a map of sunrise and sunset times for the current day and the next day, based on a given date
 * (now) and an offset (default 0). It then calculates whether it is currently night or day by comparing the
 * current time to the sunrise and sunset times. Finally, it returns a map containing all of this information,
 * plus the offset used to calculate it.
 * @param now the date to calculate the sunrise and sunset times for
 * @param offset the offset to use when calculating the sunrise and sunset times
 * @return a map containing the sunrise and sunset times, the current time, the offset, and whether it is night or day
 */
private Map getAlmanac(final Date now, final int offset = 0) {
    final Map<String, Date> today = getSunriseAndSunset([sunriseOffset: offset, sunsetOffset: offset, date: now])
    final Map<String, Date> tomorrow = getSunriseAndSunset([sunriseOffset: offset, sunsetOffset: offset, date: now + 1])
    final Map<String, Date> next = [sunrise: (Date) now < (Date) today.sunrise ? (Date) today.sunrise : (Date) tomorrow.sunrise, sunset: now < today.sunset ? (Date) today.sunset : (Date) tomorrow.sunset]
    final LocalTime sunsetTime = LocalTime.of(today.sunset.getHours(), today.sunset.getMinutes())
    final LocalTime sunriseTime = LocalTime.of(next.sunrise.getHours(), next.sunrise.getMinutes())
    final LocalTime nowTime = LocalTime.of(now.getHours(), now.getMinutes())
    final boolean isNight = nowTime > sunsetTime || nowTime < sunriseTime
    final Map almanac = [today: today, tomorrow: tomorrow, next: next, isNight: isNight, offset: offset]
    logDebug "almanac: ${almanac}"
    return almanac
}

/**
 * Takes in a Date object (now) and a String (datetime) and returns a Date object.
 * Checks if the now Date is greater than or equal to the target Date. If it is,
 * it returns the target Date plus one day, otherwise it returns the target Date.
 * @param now the current date
 * @param datetime the date to compare to
 * @return the target date plus one day if the current date is greater than or equal to the target date, otherwise the target date
 */
private Date getNextTime(final Date now, final String datetime) {
    final Date target = timeToday(datetime)
    return (now >= target) ? target + 1 : target
}

/**
 * Method to run a closure from a Closure object (c) and a Map object (ctx) and return result.
 * It first clones the Closure object so that it can set its delegate to "this". It then calls
 * the closure with the passed in ctx map as an argument. If an error occurs, it logs the error
 * along with the code for the closure and the context map.
 * @param template the closure to run
 * @param ctx the context to run the closure in
 * @return the result of the closure
 */
@CompileStatic
private Object runClosure(final Closure template, final Map ctx) {
    try {
        final Closure closure = (Closure) template.clone()
        closure.delegate = this
        return closure.call(ctx)
    } catch (final e) {
        final String code = template?.metaClass?.classNode?.getDeclaredMethods('doCall')?.first()?.code?.text
        logWarn "runClosure error ${e}: ${code ?: 'unknown code'} with ctx ${ctx}"
    }
    return null
}

/**
 * Defines the supported notification device types. Each entry defines a single type of device consisting of:
 *      title      - Displayed to the user in selection dropdown
 *      type       - The internal Hubitat device type used to allow device selection
 *      LEDs       - Map of available LEDs when using this device ('All' and 'var' are special values)
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
        effects   : ['255': 'Stop Effect', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '0': 'LED Off', 'var': 'Variable Effect'],
        effectsAll: ['255': 'Stop Effect', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Open/Close', '7': 'Small-to-Big', '8': 'Aurora', '9': 'Slow Falling', '10': 'Medium Falling', '11': 'Fast Falling',
                     '12': 'Slow Rising', '13': 'Medium Rising', '14': 'Fast Rising', '15': 'Medium Blink', '16': 'Slow Chase', '17': 'Fast Chase', '18': 'Fast Siren', '19': 'Slow Siren', '0': 'LEDs Off', 'var': 'Variable Effect']
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
        effects   : ['255': 'Stop Effect', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Falling', '7': 'Rising', '8': 'Aurora', '0': 'LED Off', 'var': 'Variable Effect'],
        effectsAll: ['255': 'Stop Effect', '1': 'Solid', '2': 'Fast Blink', '3': 'Slow Blink', '4': 'Pulse', '5': 'Chase', '6': 'Open/Close', '7': 'Small-to-Big', '8': 'Aurora', '9': 'Slow Falling', '10': 'Medium Falling', '11': 'Fast Falling',
                     '12': 'Slow Rising', '13': 'Medium Rising', '14': 'Fast Rising', '15': 'Medium Blink', '16': 'Slow Chase', '17': 'Fast Chase', '18': 'Fast Siren', '19': 'Slow Siren', '0': 'LEDs Off', 'var': 'Variable Effect']
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
].sort { final valueA, final valueB -> valueA.value.title <=> valueB.value.title } as Map<String,Map>

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
@Field static final String pauseText = $/<span style='color: red;'> (Paused)</span>/$

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
        template: { final Map ctx -> "Time period is from ${ctx.choice}" },
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
            ],
            delay: true
        ],
        execute : { final Map ctx ->
            final Map almanac = getAlmanac(new Date(), (ctx.value as Integer) ?: 0)
            runOnce(almanac.next.sunset, 'sunsetTrigger')
            runOnce(almanac.next.sunrise, 'sunriseTrigger')
        },
        test    : { final Map ctx ->
            final Map almanac = getAlmanac(new Date(), (ctx.value as Integer) ?: 0)
            switch (ctx.choice) {
                case 'sunsetToSunrise': return almanac.isNight
                case 'sunriseToSunset': return !almanac.isNight
                default: return false
            }
        }
    ],
    'accelerationActive': [
        title    : 'Acceleration is active',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.accelerationSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'acceleration',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'acceleration', '=', 'active', ctx.all as Boolean) }
    ],
    'buttonPress'       : [
        title    : 'Button is pressed',
        template : { final Map ctx -> "When ${ctx.choice} is pressed <i>(${ctx.device})</i>" },
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.pushableButton',
                multiple: true,
                any     : true
            ],
            choice: [
                title   : 'button number(s)',
                options : { final Map ctx -> getButtonNumberChoices(ctx.device as List<DeviceWrapper>) },
                multiple: true
            ],
            delay: false
        ],
        subscribe: 'pushed',
        test     : { final Map ctx -> ctx.choice && ctx.event.value in ctx.choice }
    ],
    'contactClose'      : [
        title    : 'Contact is closed',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.contactSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'contact',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'contact', '=', 'closed', ctx.all as Boolean) }
    ],
    'contactOpen'       : [
        title    : 'Contact is open',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.contactSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'contact',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'contact', '=', 'open', ctx.all as Boolean) }
    ],
    'customAttribute'   : [
        title    : 'Custom attribute is value',
        template : { final Map ctx -> "${ctx.choice?.capitalize()} is ${ctx.comparison} ${ctx.value} <i>(${ctx.device})</i>" },
        inputs   : [
            device    : [
                title   : 'device',
                type    : 'capability.*',
                multiple: true
            ],
            choice    : [
                title   : 'Select Custom Attribute',
                options : { final Map ctx -> ctx.device ? getAttributeChoices(ctx.device as List<DeviceWrapper>) : null },
                multiple: false
            ],
            comparison: [
                options: { final Map ctx -> getComparisonsByType('number') }
            ],
            value     : [
                title  : 'Attribute Value',
                options: { final Map ctx -> (ctx.device && ctx.choice) ? getAttributeOptions(ctx.device as List<DeviceWrapper>, ctx.choice as String) : null }
            ],
            delay: true
        ],
        subscribe: { final Map ctx -> ctx.choice },
        test     : { final Map ctx ->
            deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, ctx.choice as String, ctx.comparison as String,
                ctx.value as String, ctx.all as Boolean)
        }
    ],
    'eventAttribute'   : [
        title    : 'When custom attribute becomes',
        template : { final Map ctx -> "When ${ctx.choice?.capitalize()} becomes ${ctx.comparison} ${ctx.value} <i>(${ctx.device})</i>" },
        inputs   : [
            device    : [
                title   : 'device',
                type    : 'capability.*',
                multiple: false
            ],
            choice    : [
                title   : 'Select Event Attribute',
                options : { final Map ctx -> ctx.device ? getAttributeChoices(ctx.device as List<DeviceWrapper>) : null },
                multiple: false
            ],
            comparison: [
                options: { final Map ctx -> getComparisonsByType('number') }
            ],
            value     : [
                title  : 'Attribute Value',
                options: { final Map ctx -> (ctx.device && ctx.choice) ? getAttributeOptions(ctx.device as List<DeviceWrapper>, ctx.choice as String) : null }
            ],
            delay: false
        ],
        subscribe: { final Map ctx -> ctx.choice },
        test     : { final Map ctx ->
            ctx.event.deviceId == (ctx.device as DeviceWrapper).idAsLong &&
            ctx.event.name == ctx.choice &&
            evaluateComparison(ctx.event.value as String, ctx.value as String, ctx.comparison as String)
        }
    ],
    'hsmAlert'          : [
        title    : 'HSM intrusion alert becomes',
        template : { final Map ctx -> "When HSM intrusion becomes ${ctx.choice}" },
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
            ],
            delay: false
        ],
        subscribe: 'hsmAlert',
        test     : { final Map ctx -> ctx.event.value in ctx.choice }
    ],
    'hsmStatus'         : [
        title    : 'HSM arming status becomes',
        template : { final Map ctx -> "When HSM arming status becomes ${ctx.choice}" },
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
            ],
            delay: false
        ],
        subscribe: 'hsmStatus',
        test     : { final Map ctx -> location.hsmStatus in ctx.choice }
    ],
    'hubMode'           : [
        title    : 'Hub mode becomes',
        template : { final Map ctx -> "When Hub mode becomes ${ctx.choice}" },
        inputs   : [
            choice: [
                options : { final Map ctx -> location.modes.collectEntries { final Object mode -> [mode.id as String, mode.name] } },
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'mode',
        test     : { final Map ctx -> (location.currentMode.id as String) in ctx.choice }
    ],
    'locked'            : [
        title    : 'Lock is locked',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.lock',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'lock',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'lock', '=', 'locked', ctx.all as Boolean) }
    ],
    'luminosityAbove'   : [
        title    : 'Illuminance is above',
        template : { final Map ctx -> "Illuminance is above ${ctx.value} <i>(${ctx.device})</i>" },
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.illuminanceMeasurement',
                multiple: true
            ],
            value : [
                title: 'Enter Illuminance Value'
            ],
            delay: true
        ],
        subscribe: { 'illuminance' },
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'illuminance', '>', ctx.value as String, ctx.all as Boolean) }
    ],
    'luminosityBelow'   : [
        title    : 'Illuminance is below',
        template : { final Map ctx -> "Illuminance is below ${ctx.value} <i>(${ctx.device})</i>" },
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.illuminanceMeasurement',
                multiple: true
            ],
            value : [
                title: 'Enter Illuminance Value'
            ],
            delay: true
        ],
        subscribe: { 'illuminance' },
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'illuminance', '<', ctx.value as String, ctx.all as Boolean) }
    ],
    'motionActive'      : [
        title    : 'Motion is active',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.motionSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'motion',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'motion', '=', 'active', ctx.all as Boolean) }
    ],
    'motionInactive'    : [
        title    : 'Motion is inactive',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.motionSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'motion',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'motion', '=', 'inactive', ctx.all as Boolean) }
    ],
    'notpresent'        : [
        title    : 'Presence sensor is not present',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.presenceSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'presence',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'presence', '=', 'not present', ctx.all as Boolean) }
    ],
    'present'           : [
        title    : 'Presence sensor is present',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.presenceSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'presence',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'presence', '=', 'present', ctx.all as Boolean) }
    ],
    'smoke'             : [
        title    : 'Smoke is detected',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.smokeDetector',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'smoke',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'smoke', '=', 'detected', ctx.all as Boolean) }
    ],
    'switchOff'         : [
        title    : 'Switch is off',
        attribute: 'switch',
        value    : 'off',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.switch',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'switch',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'switch', '=', 'off', ctx.all as Boolean) }
    ],
    'switchOn'          : [
        title    : 'Switch is on',
        attribute: 'switch',
        value    : 'on',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.switch',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'switch',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'switch', '=', 'on', ctx.all as Boolean) }
    ],
    'timeBefore'        : [
        title   : 'Time is before',
        template: { final Map ctx -> "Time is before ${ctx.value}" },
        inputs  : [
            value: [
                title: 'Before Time',
                type : 'time',
                width: 2
            ],
            delay: false
        ],
        test    : { final Map ctx -> new Date() < (timeToday(ctx.value as String) as Date) }
    ],
    'timeAfter'         : [
        title   : 'Time is after',
        template: { final Map ctx -> "Time is after ${ctx.value}" },
        inputs  : [
            value: [
                title: 'After Time',
                type : 'time',
                width: 2
            ],
            delay: false
        ],
        execute : { final Map ctx -> runOnce(getNextTime(new Date(), ctx.value as String), 'timeAfterTrigger') },
        test    : { final Map ctx -> new Date() >= (timeToday(ctx.value as String) as Date) }
    ],
    'unlocked'          : [
        title    : 'Lock is unlocked',
        attribute: 'lock',
        value    : 'unlocked',
        inputs   : [
            device: [
                title   : 'devices',
                type    : 'capability.lock',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'lock',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'lock', '=', 'unlocked', ctx.all as Boolean) }
    ],
    'variable'          : [
        title    : 'Variable is value',
        template : { final Map ctx -> "Variable '${ctx.choice}' is ${ctx.comparison} ${ctx.value}" },
        inputs   : [
            choice    : [
                options: { final Map ctx -> getAllGlobalVars().keySet() }
            ],
            comparison: [
                options: { final Map ctx -> ctx.choice ? getComparisonsByType(getGlobalVar(ctx.choice as String)?.type as String) : null }
            ],
            value     : [
                title  : 'Variable Value',
                options: { final Map ctx -> (ctx.choice && getGlobalVar(ctx.choice)?.type == 'boolean') ? ['true': 'True', 'false': 'False'] : null }
            ],
            delay: true
        ],
        subscribe: { final Map ctx -> "variable:${ctx.choice}" },
        test     : { final Map ctx -> evaluateComparison(ctx.event?.value as String, ctx.value as String, ctx.comparison as String) }
    ],
    'waterDry'          : [
        title    : 'Water sensor is dry',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.waterSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'water',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'water', '=', 'dry', ctx.all as Boolean) }
    ],
    'waterWet'          : [
        title    : 'Water sensor is wet',
        inputs   : [
            device: [
                title   : 'sensors',
                type    : 'capability.waterSensor',
                multiple: true
            ],
            delay: true
        ],
        subscribe: 'water',
        test     : { final Map ctx -> deviceAttributeHasValue(ctx.device as List<DeviceWrapper>, 'water', '=', 'wet', ctx.all as Boolean) }
    ],
].sort { final valueA, final valueB -> valueA.value.title <=> valueB.value.title } as Map<String,Map>

// Maximum number of rules that can be created (to prevent memory and performance issues)
@Field private static final int MAX_RULES = 10

//-------------------------------------------------------------------------------
// Hubitat wrapper methods to allow for dynamic calls from @CompileStatic methods
//-------------------------------------------------------------------------------
private String getCurrentMode() { return (String) location.getCurrentMode().getName() }

private String getHsmStatus() { return (String) location.hsmStatus }

private Object getHubVariableValue(final String name) { return getGlobalVar(name)?.value }

private Object getSetting(final String name, final Object defaultValue = null) {
    return settings.containsKey(name) ? settings.get(name) : defaultValue
}

private Map<String, Object> getAppSettings() { return settings }

private Boolean getSettingBoolean(final String name, final Boolean defaultValue = false) {
    return settings.containsKey(name) ? settings.get(name).toBoolean() : defaultValue
}

private String getSettingString(final String name, final String defaultValue = '') {
    return settings.containsKey(name) ? settings.get(name).toString() : defaultValue
}

private Integer getSettingInteger(final String name, final Integer defaultValue = null) {
    return settings.containsKey(name) ? settings.get(name).toInteger() : defaultValue
}

private Map getState() { return state }

private Object getState(final String name, final Object defaultValue = null) {
    return state.containsKey(name) ? state[name] : defaultValue
}

private Object getLocation() { return location }

private long getTimeMs(final int offset = 0) { return (long) now() + offset }

private void logDebug(final String string) {
    if (settings.logEnable) {
        log.debug string
    }
}

private void logError(final String string) { log.error app.label + ' ' + string }

private void logInfo(final String string) { log.info app.label + ' ' + string }

private void logWarn(final String string) { log.warn app.label + ' ' + string }

private void removeSetting(final String string) { app.removeSetting(string) }

private void removeState(final String string) { state.remove(string) }

private void runAfterMs(final long delay, final String handler) { runInMillis(delay, handler) }

private void setState(final String name, final Object value) { state[name]=value }
