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
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import groovy.transform.Field

definition (
    name: 'Motion Lighting Controller',
    namespace: 'nrgup',
    parent: 'nrgup:Motion Lighting',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Advanced control of lighting based on motion, contacts and switches',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page name: 'pageMain', content: 'pageMain'
    page name: 'pageTriggers', content: 'pageTriggers'
    page name: 'pageMode', content: 'pageMode'
}

@Field static final List<Map<String,String>> activeActions = [
   [on: 'Turn on lights'],
   [onColor: 'Turn on lights and set color'],
   [none: 'No action (do not turn on)']
]

@Field static final List<Map<String,String>> inactiveActions = [
   [off: 'Turn off lights'],
   [none: 'No action (do not turn off)']
]

/*
 * Configuration UI
 */
Map pageMain() {
    state.remove('pageModeName')
    state.remove('pageModeID')

    return dynamicPage(name: 'pageMain', title: 'Motion Lighting Controller',
                      install: true, uninstall: true, hideWhenEmpty: true) {
        section {
            label title: 'Application Label',
                  required: false

            if (parent.enabled()) {
                input name: 'masterEnable',
                    title: 'Controller enabled',
                    type: 'bool',
                    required: false,
                    defaultValue: true,
                    submitOnChange: true
            } else {
                paragraph '<span style=\'font-weight: bold; color: red\'>Controller disabled - enable the parent application</span>'
            }
        }

        section {
            if (state.triggered?.running == true && state.triggered.active) {
                String ago
                int elapsed = (now() - state.triggered.active) / 1000
                if (elapsed > 1 && elapsed < 120) {
                    ago = "${elapsed} seconds ago"
                } else {
                    elapsed /= 60
                    ago = "${elapsed} minutes ago"
                }
                paragraph "<b>Triggered ${ago} by ${state.triggered?.device}</b>"
            }

            href name: 'pageTriggers',
                    page: 'pageTriggers',
                    title: 'Motion Lighting triggers',
                    description: getTriggerDescription() ?:
                        'Click to select button, contact, motion and switch devices',
                    state: getTriggerDescription() ? 'complete' : null

            href name: 'pageModeSettingsDefault',
                 page: 'pageMode',
                 title: 'Motion Lighting devices',
                 params: [modeName: 'Default', modeID: 0],
                 description: getModeDescription(0) ?: 'Click to configure default lighting activity',
                 state: getModeDescription(0) ? 'complete' : null
        }

        List configuredModes = getConfiguredModes()
        section(configuredModes ? 'Mode Overrides' : '') {
            Long activeId = getActiveMode().id
            location.getModes().each { mode ->
                if ((String)mode.id in settings?.modes || settings["mode.${mode.id}.enable"] == true) {
                    href name: "pageModeSettings${mode.id}",
                        page: 'pageMode',
                        title: "${mode.name} mode" + (mode.id == activeId ? ' (currently active)' : ''),
                        params: [modeName: mode.name, modeID: mode.id],
                        description: getModeDescription(mode.id) ?:
                            "Click to override defaults during ${mode.name} mode",
                        state: getModeDescription(mode.id) ? 'complete' : null
                }
            }

            List availableModes = getAvailableModes()
            if (availableModes) {
                input name: 'modes',
                    title: 'Select mode to create override configuration',
                    type: 'enum',
                    options: availableModes,
                    submitOnChange: true
            }
        }

        section('Controller Restrictions', hideable: true, hidden: true) {
            input name: 'disabledSwitchWhenOn',
                  title: 'Select switch(s) to disable controller when ON',
                  type: 'capability.switch',
                  multiple: true

            input name: 'disabledSwitchWhenOff',
                  title: 'Select switch(s) to disable controller when OFF',
                  type: 'capability.switch',
                  multiple: true

            input name: 'luxSensors',
                  title: 'Select lux (illuminance) sensor(s) to monitor',
                  type: 'capability.illuminanceMeasurement',
                  multiple: true

            input name: 'luxNumber',
                  title: 'Disable controller if average illuminance is above this value',
                  type: 'number'
        }

        section {
            input name: 'sendOn',
                  title: 'Send \"On\" command after \"Set Level\" or \"Set Color\" (enable if devices use pre-staging)',
                  type: 'bool',
                  required: false,
                  defaultValue: true

            input name: 'logEnable',
                  title: 'Enable Debug logging',
                  type: 'bool',
                  required: false,
                  defaultValue: true
        }
    }
}

// Trigger devices configuration page
Map pageTriggers() {
   return dynamicPage(name: 'pageTriggers', title: 'Configure trigger devices',
                      uninstall: false, install: false, nextPage: 'pageMain', hideWhenEmpty: true) {
        section {
            input name: 'activationMotionSensors',
                  title: 'Motion sensors when active',
                  type: 'capability.motionSensor',
                  multiple: true,
                  required: false,
                  submitOnChange: true

            if (activationMotionSensors) {
                input name: 'additionalMotionSensors',
                    title: '<i>Additional motion sensors to keep lights on (optional)</i>',
                    type: 'capability.motionSensor',
                    multiple: true,
                    required: false
            }

            input name: 'activationContactSensors',
                  title: 'Contact sensors when opened',
                  type: 'capability.contactSensor',
                  multiple: true,
                  required: false,
                  submitOnChange: true

            if (activationContactSensors) {
                input name: 'additionalContactSensors',
                    title: '<i>Additional contact sensors to keep lights on (optional)</i>',
                    type: 'capability.contactSensor',
                    multiple: true,
                    required: false
            }

            input name: 'activationOnSwitches',
                  title: 'Switches when turned on',
                  type: 'capability.switch',
                  multiple: true,
                  required: false

            input name: 'activationOffSwitches',
                  title: 'Switches when turned off',
                  type: 'capability.switch',
                  multiple: true,
                  required: false

            input name: 'activationButtons',
                  title: 'Buttons when pressed',
                  type: 'capability.pushableButton',
                  multiple: true,
                  required: false,
                  submitOnChange: true

            if (activationButtons) {
                input name: 'activationButtonNumber',
                    title: 'Set button number to activate on',
                    type: 'number',
                    range: '1..99',
                    defaultValue: 1,
                    required: true
            }
        }
    }
}

// Mode configuration page
Map pageMode(Map params) {
    if (params) {
        state.pageModeName = params.modeName
        state.pageModeID = params.modeID
    }
    Long modeID = modeID ?: state.pageModeID
    String modeName = modeName ?: state.pageModeName

    return dynamicPage(name: 'pageMode', title: "${modeName} Mode Settings",
                       uninstall: false, install: false, nextPage: 'pageMain', hideWhenEmpty: true) {
        section {
            input name: "mode.${modeID}.enable",
                    title: "Enable ${modeName} settings",
                    type: 'bool',
                    defaultValue: true,
                    submitOnChange: true
        }

        if (settings["mode.${modeID}.enable"] != false) {
            section {
                input name: "mode.${modeID}.lights",
                    title: 'Choose lights to turn on/off/dim',
                    type: 'capability.light',
                    multiple: true,
                    required: true

                input name: "mode.${modeID}.active",
                    title: 'When activity is detected...',
                    type: 'enum',
                    options: activeActions,
                    defaultValue: 'on',
                    required: true,
                    submitOnChange: true

                if (settings["mode.${modeID}.action"] == 'onColor') {
                    input name: "mode.${modeID}.level",
                        title: 'level',
                        type: 'number',
                        description: '0-100',
                        range: '0..100',
                        width: 2
                    input name: "mode.${modeID}.CT",
                        title: 'CT',
                        type: 'number',
                        description: '~2000-7000',
                        range: '1000..8000',
                        width: 3
                    input name: "mode.${modeID}.hue",
                        type: 'number',
                        title: 'hue',
                        range: '0..100',
                        description: '0-100',
                        width: 2
                    input name: "mode.${modeID}.sat",
                        title: 'saturation',
                        range: '0..100',
                        description: '0-100',
                        width: 2
                }

                input name: "mode.${modeID}.inactive",
                    title: 'When activity stops...',
                    type: 'enum',
                    options: inactiveActions,
                    defaultValue: 'off',
                    required: true,
                    submitOnChange: true

                if (settings["mode.${modeID}.inactive"] != 'none') {
                    input name: "mode.${modeID}.inactiveMinutes",
                        title: 'Minutes to wait after motion stops',
                        description: 'number of minutes',
                        type: 'number',
                        range: '1..3600',
                        required: true
                }

                if (settings["mode.${modeID}.inactive"] == 'off') {
                input name: "mode.${modeID}.additionalOffLights",
                        title: '<i>Additional lights to turn off (optional)</i>',
                        type: 'capability.switch',
                        multiple: true
                }
            }

            section('<u>Activity Testing</u>') {
                paragraph 'Use buttons below to emulate activity starting and stopping'
                input name: 'btnTestActive',
                      title: 'Test Activity Detected',
                      width: 4,
                      type: 'button'
                input name: 'btnTestInactive',
                      title: 'Test Activity Stopped',
                      width: 4,
                      type: 'button'
            }
        }
    }
}

// Page button handler
void appButtonHandler(String buttonName) {
    switch (buttonName) {
        case 'btnTestActive':
            log.info "${app.name} testing ${state.pageModeName} mode activity detected"
            performActiveAction(getModeSettings(state.pageModeID))
            break
        case 'btnTestInactive':
            log.info "${app.name} testing ${state.pageModeName} mode activity stopped"
            performInactiveAction(getModeSettings(state.pageModeID))
            break
    }
}

// Returns list of modes that have been configured
List getConfiguredModes() {
    List configuredModes = []
    location.getModes().each { mode ->
        if ((String)mode.id in settings?.modes || settings["mode.${mode.id}.enable"] == true) {
            configuredModes += mode
        }
    }

    return configuredModes
}

// Returns list of modes available to be configured
List getAvailableModes() {
    List availableModes = []
    location.getModes().each { mode ->
        if (!((String)mode.id in settings?.modes || settings["mode.${mode.id}.enable"] == true)) {
            availableModes += [ (mode.id): mode.name ]
        }
    }

    return availableModes
}

// Returns String summary of device settings, or empty string if that mode is not configured
String getTriggerDescription() {
    List devices = []
    devices.addAll(settings.activationMotionSensors*.displayName ?: [])
    devices.addAll(settings.activationContactSensors*.displayName ?: [])
    devices.addAll(settings.activationButtons*.displayName ?: [])
    devices.addAll(settings.activationOnSwitches*.displayName ?: [])
    devices.addAll(settings.activationOffSwitches*.displayName ?: [])

    if (!devices) { return '' }

    String description = ''
    if (devices.size() <= 10) {
        devices.eachWithIndex { element, index ->
            if (index > 0 && index < devices.size() - 1) { description += ', ' }
            else if (index > 0 && index == devices.size() - 1) { description += ' and ' }
            description += element
        }
    } else {
        description = "${devices.size()} trigger devices"
    }

    return description
}

// Returns String summary of per-mode settings, or empty string if that mode is not configured
String getModeDescription(Long modeID) {
    if (!settings["mode.${modeID}.enable"]) { return '' }

    List lights = settings["mode.${modeID}.lights"]
    if (!lights) { return '' }

    String description = ''
    if (lights.size() <= 10) {
        lights.eachWithIndex { element, index ->
            if (index > 0 && index < lights.size() - 1) { description += ', ' }
            else if (index > 0 && index == lights.size() - 1) { description += ' and ' }
            description += element
        }
    } else {
        description = "${lights.size()} lights configured"
    }

    String action = activeActions.findResult { m -> m.get(settings["mode.${modeID}.active"]) }
    description += "\nWhen active: ${action}"
    action = inactiveActions.findResult { m -> m.get(settings["mode.${modeID}.inactive"]) }
    description += "\nWhen inactive: ${action}"
    if (settings["mode.${modeID}.additionalOffLights"]) {
        description += ' (plus additional lights)'
    }

    return description
}

/*
 * Application Logic
 */

// Called when a button is pressed on the settings page
void buttonHandler(Event evt) {
    Map mode = getActiveMode()
    log.trace "buttonHandler: ${evt.device} ${evt.value} (mode ${mode.name})"
    if (!checkEnabled()) { return }

    if (evt.value == settings.activationButtonNumber) {
        state.triggered = [
            type: 'button',
            device: evt.device.displayName,
            value: evt.value
        ]
        performActiveAction(mode)
        scheduleInactiveAction(mode)
    }
}

// Called when a subscribed contact sensor changes
void contactHandler(Event evt) {
    Map mode = getActiveMode()
    log.trace "contactHandler: ${evt.device} ${evt.value} (mode ${mode.name})"
    if (!checkEnabled() || evt.value != 'open') { return }

    if (
        (evt.device.id in settings.activationContactSensors*.id) ||
        (state.triggered?.running == true && evt.device.id in settings.additionalContactSensors*.id)
    ) {
        state.triggered = [
            type: 'contact',
            device: evt.device.displayName,
            value: evt.value
        ]
        performActiveAction(mode)
        scheduleInactiveAction(mode)
    }
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
}

// Called when the driver is initialized.
void initialize() {
    log.info "${app.name} initializing"
    unsubscribe()
    subscribe(settings.activationMotionSensors, 'motion', motionHandler)
    subscribe(settings.additionalMotionSensors, 'motion', motionHandler)
    subscribe(settings.activationContactSensors, 'contact', contactHandler)
    subscribe(settings.additionalContactSensors, 'contact', contactHandler)
    subscribe(settings.activationOnSwitches, 'switch', switchHandler)
    subscribe(settings.activationOffSwitches, 'switch', switchHandler)
    subscribe(settings.activationButtons, 'pushed', buttonHandler)
    subscribe(location, 'mode', modeChangeHandler)

    state.disabledDevices = [:]
    if (!state.lastMode) { state.lastMode = getActiveMode().id }
    if (!state.triggered) { state.triggered = [ running: false ] }
}

// Called when a the mode changes
void modeChangeHandler(Event evt) {
    Map mode = getActiveMode()
    Map lastMode = getModeSettings(state.lastMode)
    state.lastMode = mode.id
    log.trace "modeChangeHandler: location mode = ${evt.value}, active mode = ${mode.name}"

    performTransitionAction(lastMode, mode)
}

// Called when a subscribed motion sensor changes
void motionHandler(Event evt) {
    Map mode = getActiveMode()
    log.trace "motionHandler: ${evt.device} ${evt.value} (mode ${mode.name})"
    if (!checkEnabled() || evt.value != 'active') { return }

    if (
        (evt.device.id in settings.activationMotionSensors*.id) ||
        (state.triggered?.running == true && evt.device.id in settings.additionalMotionSensors*.id)
    ) {
        state.triggered = [
            type: 'motion',
            device: evt.device.displayName,
            value: evt.value
        ]
        performActiveAction(mode)
        scheduleInactiveAction(mode)
    }
}

// Called when a subscribed switch changes
void switchHandler(Event evt) {
    Map mode = getActiveMode()
    log.trace "switchHandler: ${evt.device} ${evt.value} (mode ${mode.name})"
    if (!checkEnabled()) { return }

    if ((evt.device.id in settings.activationOnSwitches*.id && evt.value == 'on') ||
        (evt.device.id in settings.activationOffSwitches*.id && evt.value == 'off')) {
        state.triggered = [
            type: 'switch',
            device: evt.device.displayName,
            value: evt.value
        ]
        performActiveAction(mode)
        scheduleInactiveAction(mode)
    }
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

/*
 * Internal Application Logic
 */

// Returns true if the controller is enabled
private boolean checkEnabled() {
    if (!settings.masterEnable) {
        log.info "${app.name} is disabled"
        return false
    }

    if (!parent.enabled()) {
        log.info "${parent.name} (parent) is disabled"
        return false
    }

    if (settings.luxSensors && settings.luxNumber &&
        currentLuxLevel() > (int)settings.luxNumber) {
        log.info "${app.name} is disabled due to lux level"
        return false
    }

    if (settings.disabledSwitchWhenOn &&
        settings.disabledSwitchWhenOn.any { device -> device.currentValue('switch') == 'on' }) {
        log.info "${app.name} is disabled due to a switch set to ON"
        return false
    }

    if (settings.disabledSwitchWhenOff &&
        settings.disabledSwitchWhenOff.any { device -> device.currentValue('switch') == 'off' }) {
        log.info "${app.name} is disabled due to a switch set to OFF"
        return false
    }

    return true
}

// Returns the average lux level
private int currentLuxLevel() {
    int total = 0
    int count = 0
    settings.luxSensors?.each { d ->
        int value = d.currentValue('illuminance')
        if (value) { count++ }
        total += value
    }
    return count ? Math.round(total / count) : 0
}

// Returns the currently active mode (which may be default if not overridden)
private Map getActiveMode() {
    Long id = location.currentMode.id
    if (settings["mode.${id}.enable"] != true) {
        id = 0
    }

    return getModeSettings(id)
}

// Returns a map with the mode settings
private Map getModeSettings(Long id) {
    Map mode = [
        id: id,
        name: id > 0 ? location.getModes().find { m -> m.id == id }.name : 'Default'
    ]

    mode.enable = settings["mode.${mode.id}.enable"] as boolean
    if (mode.enable == true) {
        mode.active = settings["mode.${mode.id}.active"] as String
        mode.additionalOffLights = settings["mode.${mode.id}.additionalOffLights"] ?: []
        mode.inactive = settings["mode.${mode.id}.inactive"] as String
        mode.inactiveMinutes = settings["mode.${mode.id}.inactiveMinutes"] as int
        mode.lights = settings["mode.${mode.id}.lights"] ?: []
    }

    return mode
}

// Performs the configured actions when specified mode triggered
private void performActiveAction(Map mode) {
    if (logEnable) { log.debug "Performing active action for mode ${mode.name}" }

    if (!mode.enable) {
        log.info "${app.name} ${mode.name} mode is disabled"
        return
    }

    state.triggered.running = true
    state.triggered.active = now()

    switch (mode.active) {
        case 'on':
            setLights(mode.lights, 'on')
            break
        case 'onColor':
            break
        default:
            if (logEnable) { log.debug "No activation activity for ${mode.name} mode, aborting" }
            break
    }
}

// Performs the configured actions when specified mode becomes inactive
private void performInactiveAction() {
    performInactiveAction( getActiveMode() )
}

private void performInactiveAction(Map mode) {
    if (logEnable) { log.debug "Performing inactive action for mode ${mode.name}" }

    List lights = mode.lights
    lights.addAll(mode.additionalOffLights ?: [])

    state.triggered.running = false
    state.triggered.inactive = now()

    switch (mode.inactive) {
        case 'off':
            setLights(lights, 'off')
            break
        default:
            if (logEnable) { log.debug "No inactive activity for ${mode.name} mode, aborting" }
            break
    }
}

// Performs the configured actions when changing between modes
private void performTransitionAction(Map oldMode, Map newMode) {
    if (state.triggered?.running == true) {
        List newLights = newMode.lights*.id
        if (newLights) {
            oldMode.lights = oldMode.lights.findAll { device -> !(device.id in newLights) }
            oldMode.additionalOffLights = oldMode.additionalOffLights.findAll { device -> !(device.id in newLights) }
        }
        unschedule('performInactiveAction')
        performInactiveAction(oldMode)
        performActiveAction(newMode)
    }
}

// Schedules the inactive action to be executed
private void scheduleInactiveAction(Map mode) {
    if (mode.inactiveMinutes) {
        if (logEnable) { log.debug "Scheduling inaction activity in ${mode.inactiveMinutes} minutes" }
        runIn(60 * mode.inactiveMinutes, 'performInactiveAction')
    }
}

// Sets the specified lights using the provided action and optional value
private void setLights(List lights, String action, String value = null) {
    if (logEnable) { log.debug "set ${lights} to ${action} ${value ?: ''}" }

    if (settings.reenableDelay && settings.disabledDevices) {
        long expire = now() - (settings.reenableDelay * 60000)
        settings.disabledDevices.values().removeIf { v -> v <= expire }
    }

    lights.findAll { device -> !settings.disabledDevices?.containsKey(device.id) }.each { device ->
        if (value) {
            device."$action"(value)
        } else {
            device."$action"()
        }
    }
}
