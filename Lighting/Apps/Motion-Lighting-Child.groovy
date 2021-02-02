/* groovylint-disable UnnecessaryObjectReferences */
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
   [onLevel: 'Turn on and set level'],
   //[onCT: 'Turn on and set color temperature'],
   [onColor: 'Turn on and set color'],
   [none: 'No action (do not turn on)']
]

@Field static final List<Map<String,String>> inactiveActions = [
   [off: 'Turn off lights'],
   [onLevel: 'Set light level'],
   [none: 'No action (do not turn off)']
]

/*
 * Configuration UI
 */
/* groovylint-disable-next-line MethodSize */
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
                paragraph '<span style=\'font-weight: bold; color: red\'>' +
                          'Controller disabled - enable the parent application</span>'
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
            } else {
                app.removeSetting('additionalMotionSensors')
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
            } else {
                app.removeSetting('additionalContactSensors')
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
            } else {
                app.removeSetting('activationButtonNumber')
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
                input name: "mode.${modeID}.active",
                    title: 'When activity is detected...',
                    type: 'enum',
                    options: activeActions,
                    defaultValue: 'on',
                    required: true,
                    submitOnChange: true

                if (settings["mode.${modeID}.active"] != 'none') {
                    input name: "mode.${modeID}.lights",
                        title: 'Choose lights to turn on/off/dim',
                        description: modeID == 0 ? 'Click to set' : 'Click to override default lights',
                        type: 'capability.light',
                        multiple: true
                } else {
                    app.removeSetting("mode.${modeID}.lights")
                }

                if (settings["mode.${modeID}.active"] == 'onLevel') {
                    app.removeSetting("mode.${modeID}.color")
                    input name: "mode.${modeID}.level",
                        title: 'Set brightness (1-100)',
                        type: 'number',
                        range: '1..100',
                        width: 5,
                        required: true
                } else if (settings["mode.${modeID}.active"] == 'onColor') {
                    app.removeSetting("mode.${modeID}.level")
                    input name: "mode.${modeID}.color",
                        title: 'Select light color',
                        type: 'enum',
                        options: colors,
                        width: 5,
                        required: true
                } else {
                    app.removeSetting("mode.${modeID}.color")
                    app.removeSetting("mode.${modeID}.level")
                }

                if (settings["mode.${modeID}.active"] != 'none') {
                    input name: "mode.${modeID}.inactive",
                        title: 'When activity stops...',
                        type: 'enum',
                        options: inactiveActions,
                        defaultValue: 'off',
                        required: true,
                        submitOnChange: true

                    if (settings["mode.${modeID}.inactive"] == 'onLevel') {
                        input name: "mode.${modeID}.level2",
                            title: 'Set brightness (1-100)',
                            type: 'number',
                            range: '1..100',
                            width: 5,
                            required: true
                    } else {
                        app.removeSetting("mode.${modeID}.level2")
                    }

                    if (settings["mode.${modeID}.inactive"] != 'none') {
                        input name: "mode.${modeID}.inactiveMinutes",
                            title: 'Minutes to wait after motion stops',
                            description: 'number of minutes',
                            type: 'number',
                            range: '1..3600',
                            width: 5,
                            required: true
                    } else {
                        app.removeSetting("mode.${modeID}.inactiveMinutes")
                    }

                    if (settings["mode.${modeID}.inactive"] == 'off') {
                        input name: "mode.${modeID}.additionalOffLights",
                              title: '<i>Additional lights to turn off (optional)</i>',
                              type: 'capability.switch',
                              multiple: true
                    } else {
                        app.removeSetting("mode.${modeID}.additionalOffLights")
                    }
                } else {
                    app.removeSetting("mode.${modeID}.inactive")
                    app.removeSetting("mode.${modeID}.level2")
                    app.removeSetting("mode.${modeID}.inactiveMinutes")
                    app.removeSetting("mode.${modeID}.additionalOffLights")
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
    if (!lights && modeID > 0) { lights = settings['mode.0.lights'] }
    if (!lights) { return '' }

    String description = ''
    if (settings["mode.${modeID}.active"] != 'none') {
        if (lights.size() <= 10) {
            lights.eachWithIndex { element, index ->
                if (index > 0 && index < lights.size() - 1) { description += ', ' }
                else if (index > 0 && index == lights.size() - 1) { description += ' and ' }
                description += element
            }
        } else {
            description = "${lights.size()} lights configured"
        }
        description += '\n'
    }

    String action = activeActions.findResult { m -> m.get(settings["mode.${modeID}.active"]) }
    description += "When active: ${action}\n"
    action = inactiveActions.findResult { m -> m.get(settings["mode.${modeID}.inactive"]) }
    description += "When inactive: ${action}"
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
    if (!checkEnabled(mode)) { return }

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
    if (!checkEnabled(mode) || evt.value != 'open') { return }

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
    state.lastMode = state.lastMode ?: getActiveMode().id
    state.triggered = state.triggered ?: [ running: false ]
}

// Called when a the mode changes
void modeChangeHandler(Event evt) {
    Map mode = getActiveMode()
    Map lastMode = getModeSettings(state.lastMode)
    state.lastMode = mode.id
    log.trace "modeChangeHandler: location mode = ${evt.value}, active mode = ${mode.name}"
    if (state.triggered.running == true) {
        performTransitionAction(lastMode, mode)
    }
}

// Called when a subscribed motion sensor changes
void motionHandler(Event evt) {
    Map mode = getActiveMode()
    log.trace "motionHandler: ${evt.device} ${evt.value} (mode ${mode.name})"
    if (!checkEnabled(mode) || evt.value != 'active') { return }

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
    if (!checkEnabled(mode)) { return }

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
private boolean checkEnabled(Map mode) {
    if (!mode.enable) {
        log.info "${mode.name} is disabled"
        return false
    }

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
        log.info "${app.name} is disabled (lux exceeds ${settings.luxNumber})"
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

// Puts light in the disabled devices list which will stop it being updated
private void disableLight(DeviceWrapper device) {
    state.disabledDevices.put(device.id, now())
}

// Returns the currently active mode (which may be default if not overridden)
private Map getActiveMode() {
    Long id = location.currentMode.id
    if (settings["mode.${id}.enable"] != true) { id = 0 }
    return getModeSettings(id)
}

// Return color map from color name
private Map getColorByName(String name) {
    Map color = colors.find { c -> (String)c.name == name }
    return [ hue: color.h, saturation: color.s, level: color.l ]
}

// Returns a map with the mode settings
private Map getModeSettings(Long id) {
    Map mode = [
        id: id,
        name: id > 0 ? location.getModes().find { m -> m.id == id }.name : 'Default'
    ]

    mode.enable = settings["mode.${mode.id}.enable"] as Boolean
    if (mode.enable == true) {
        mode.active = settings["mode.${mode.id}.active"] as String
        mode.additionalOffLights = settings["mode.${mode.id}.additionalOffLights"] ?: []
        mode.color = settings["mode.${mode.id}.color"] as String
        mode.inactive = settings["mode.${mode.id}.inactive"] as String
        mode.inactiveMinutes = settings["mode.${mode.id}.inactiveMinutes"] as Integer
        mode.level = settings["mode.${mode.id}.level"] as BigDecimal
        mode.level2 = settings["mode.${mode.id}.level2"] as BigDecimal
        mode.lights = settings["mode.${mode.id}.lights"] ?: []

        // If mode has no lights use the default mode values
        if (mode.id > 0 && !mode.lights) {
            mode.lights = settings['mode.0.lights'] ?: []
        }
    }

    return mode
}

// Performs the action on the specified lights
private void performAction(String action, List lights) {
    switch (action) {
        case 'on':
            setLights(mode.lights, 'on')
            break
        case 'onLevel':
            setLights(mode.lights, 'setLevel', mode.level)
            if (settings.sendOn) { setLights(mode.lights, 'on') }
            break
        case 'onColor':
            setLights(mode.lights, 'setColor', getColorByName(mode.color))
            if (settings.sendOn) { setLights(mode.lights, 'on') }
            break
        case 'off':
            setLights(lights, 'off')
            break
    }
}

// Performs the configured actions when specified mode triggered
private void performActiveAction(Map mode) {
    if (logEnable) { log.debug "Performing active action for mode ${mode.name}" }

    state.triggered.running = true
    state.triggered.active = now()

    performAction(mode.active, mode.lights)
}

// Performs the configured actions when specified mode becomes inactive
private void performInactiveAction() {
    performInactiveAction( getActiveMode() )
}

private void performInactiveAction(Map mode) {
    if (logEnable) { log.debug "Performing inactive action for mode ${mode.name}" }
    if (mode.active == 'none' || mode.inactive == 'none') { return }

    List lights = mode.lights
    lights.addAll(mode.additionalOffLights ?: [])

    state.triggered.running = false
    state.triggered.inactive = now()

    performAction(mode.active, mode.lights)
}

// Performs the configured actions when changing between modes
private void performTransitionAction(Map oldMode, Map newMode) {
    if (state.triggered?.running == true) {
        List newLights = newMode.activity == 'none' ? [] : newMode.lights*.id
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
    if (mode.active != 'none' && mode.inactive != 'none' && mode.inactiveMinutes > 0) {
        if (logEnable) { log.debug "Scheduling inaction activity in ${mode.inactiveMinutes} minutes" }
        runIn(60 * mode.inactiveMinutes, 'performInactiveAction')
    }
}

// Sets the specified lights using the provided action and optional value
private void setLights(List lights, String action, Object value = null) {
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

@Field static final List colors = [
    [name:'Alice Blue',    rgb:'#F0F8FF',    h:208,    s:100,    l:97],
    [name:'Antique White',    rgb:'#FAEBD7',    h:34,    s:78,    l:91],
    [name:'Aqua',    rgb:'#00FFFF',    h:180,    s:100,    l:50],
    [name:'Aquamarine',    rgb:'#7FFFD4',    h:160,    s:100,    l:75],
    [name:'Azure',    rgb:'#F0FFFF',    h:180,    s:100,    l:97],
    [name:'Beige',    rgb:'#F5F5DC',    h:60,    s:56,    l:91],
    [name:'Bisque',    rgb:'#FFE4C4',    h:33,    s:100,    l:88],
    [name:'Blanched Almond',    rgb:'#FFEBCD',    h:36,    s:100,    l:90],
    [name:'Blue',    rgb:'#0000FF',    h:240,    s:100,    l:50],
    [name:'Blue Violet',    rgb:'#8A2BE2',    h:271,    s:76,    l:53],
    [name:'Brown',    rgb:'#A52A2A',    h:0,    s:59,    l:41],
    [name:'Burly Wood',    rgb:'#DEB887',    h:34,    s:57,    l:70],
    [name:'Cadet Blue',    rgb:'#5F9EA0',    h:182,    s:25,    l:50],
    [name:'Chartreuse',    rgb:'#7FFF00',    h:90,    s:100,    l:50],
    [name:'Chocolate',    rgb:'#D2691E',    h:25,    s:75,    l:47],
    [name:'Cool White',    rgb:'#F3F6F7',    h:187,    s:19,    l:96],
    [name:'Coral',    rgb:'#FF7F50',    h:16,    s:100,    l:66],
    [name:'Corn Flower Blue',    rgb:'#6495ED',    h:219,    s:79,    l:66],
    [name:'Corn Silk',    rgb:'#FFF8DC',    h:48,    s:100,    l:93],
    [name:'Crimson',    rgb:'#DC143C',    h:348,    s:83,    l:58],
    [name:'Cyan',    rgb:'#00FFFF',    h:180,    s:100,    l:50],
    [name:'Dark Blue',    rgb:'#00008B',    h:240,    s:100,    l:27],
    [name:'Dark Cyan',    rgb:'#008B8B',    h:180,    s:100,    l:27],
    [name:'Dark Golden Rod',    rgb:'#B8860B',    h:43,    s:89,    l:38],
    [name:'Dark Gray',    rgb:'#A9A9A9',    h:0,    s:0,    l:66],
    [name:'Dark Green',    rgb:'#006400',    h:120,    s:100,    l:20],
    [name:'Dark Khaki',    rgb:'#BDB76B',    h:56,    s:38,    l:58],
    [name:'Dark Magenta',    rgb:'#8B008B',    h:300,    s:100,    l:27],
    [name:'Dark Olive Green',    rgb:'#556B2F',    h:82,    s:39,    l:30],
    [name:'Dark Orange',    rgb:'#FF8C00',    h:33,    s:100,    l:50],
    [name:'Dark Orchid',    rgb:'#9932CC',    h:280,    s:61,    l:50],
    [name:'Dark Red',    rgb:'#8B0000',    h:0,    s:100,    l:27],
    [name:'Dark Salmon',    rgb:'#E9967A',    h:15,    s:72,    l:70],
    [name:'Dark Sea Green',    rgb:'#8FBC8F',    h:120,    s:25,    l:65],
    [name:'Dark Slate Blue',    rgb:'#483D8B',    h:248,    s:39,    l:39],
    [name:'Dark Slate Gray',    rgb:'#2F4F4F',    h:180,    s:25,    l:25],
    [name:'Dark Turquoise',    rgb:'#00CED1',    h:181,    s:100,    l:41],
    [name:'Dark Violet',    rgb:'#9400D3',    h:282,    s:100,    l:41],
    [name:'Daylight White',    rgb:'#CEF4FD',    h:191,    s:9,    l:90],
    [name:'Deep Pink',    rgb:'#FF1493',    h:328,    s:100,    l:54],
    [name:'Deep Sky Blue',    rgb:'#00BFFF',    h:195,    s:100,    l:50],
    [name:'Dim Gray',    rgb:'#696969',    h:0,    s:0,    l:41],
    [name:'Dodger Blue',    rgb:'#1E90FF',    h:210,    s:100,    l:56],
    [name:'Fire Brick',    rgb:'#B22222',    h:0,    s:68,    l:42],
    [name:'Floral White',    rgb:'#FFFAF0',    h:40,    s:100,    l:97],
    [name:'Forest Green',    rgb:'#228B22',    h:120,    s:61,    l:34],
    [name:'Fuchsia',    rgb:'#FF00FF',    h:300,    s:100,    l:50],
    [name:'Gainsboro',    rgb:'#DCDCDC',    h:0,    s:0,    l:86],
    [name:'Ghost White',    rgb:'#F8F8FF',    h:240,    s:100,    l:99],
    [name:'Gold',    rgb:'#FFD700',    h:51,    s:100,    l:50],
    [name:'Golden Rod',    rgb:'#DAA520',    h:43,    s:74,    l:49],
    [name:'Gray',    rgb:'#808080',    h:0,    s:0,    l:50],
    [name:'Green',    rgb:'#008000',    h:120,    s:100,    l:25],
    [name:'Green Yellow',    rgb:'#ADFF2F',    h:84,    s:100,    l:59],
    [name:'Honeydew',    rgb:'#F0FFF0',    h:120,    s:100,    l:97],
    [name:'Hot Pink',    rgb:'#FF69B4',    h:330,    s:100,    l:71],
    [name:'Indian Red',    rgb:'#CD5C5C',    h:0,    s:53,    l:58],
    [name:'Indigo',    rgb:'#4B0082',    h:275,    s:100,    l:25],
    [name:'Ivory',    rgb:'#FFFFF0',    h:60,    s:100,    l:97],
    [name:'Khaki',    rgb:'#F0E68C',    h:54,    s:77,    l:75],
    [name:'Lavender',    rgb:'#E6E6FA',    h:240,    s:67,    l:94],
    [name:'Lavender Blush',    rgb:'#FFF0F5',    h:340,    s:100,    l:97],
    [name:'Lawn Green',    rgb:'#7CFC00',    h:90,    s:100,    l:49],
    [name:'Lemon Chiffon',    rgb:'#FFFACD',    h:54,    s:100,    l:90],
    [name:'Light Blue',    rgb:'#ADD8E6',    h:195,    s:53,    l:79],
    [name:'Light Coral',    rgb:'#F08080',    h:0,    s:79,    l:72],
    [name:'Light Cyan',    rgb:'#E0FFFF',    h:180,    s:100,    l:94],
    [name:'Light Golden Rod Yellow',    rgb:'#FAFAD2',    h:60,    s:80,    l:90],
    [name:'Light Gray',    rgb:'#D3D3D3',    h:0,    s:0,    l:83],
    [name:'Light Green',    rgb:'#90EE90',    h:120,    s:73,    l:75],
    [name:'Light Pink',    rgb:'#FFB6C1',    h:351,    s:100,    l:86],
    [name:'Light Salmon',    rgb:'#FFA07A',    h:17,    s:100,    l:74],
    [name:'Light Sea Green',    rgb:'#20B2AA',    h:177,    s:70,    l:41],
    [name:'Light Sky Blue',    rgb:'#87CEFA',    h:203,    s:92,    l:75],
    [name:'Light Slate Gray',    rgb:'#778899',    h:210,    s:14,    l:53],
    [name:'Light Steel Blue',    rgb:'#B0C4DE',    h:214,    s:41,    l:78],
    [name:'Light Yellow',    rgb:'#FFFFE0',    h:60,    s:100,    l:94],
    [name:'Lime',    rgb:'#00FF00',    h:120,    s:100,    l:50],
    [name:'Lime Green',    rgb:'#32CD32',    h:120,    s:61,    l:50],
    [name:'Linen',    rgb:'#FAF0E6',    h:30,    s:67,    l:94],
    [name:'Maroon',    rgb:'#800000',    h:0,    s:100,    l:25],
    [name:'Medium Aquamarine',    rgb:'#66CDAA',    h:160,    s:51,    l:60],
    [name:'Medium Blue',    rgb:'#0000CD',    h:240,    s:100,    l:40],
    [name:'Medium Orchid',    rgb:'#BA55D3',    h:288,    s:59,    l:58],
    [name:'Medium Purple',    rgb:'#9370DB',    h:260,    s:60,    l:65],
    [name:'Medium Sea Green',    rgb:'#3CB371',    h:147,    s:50,    l:47],
    [name:'Medium Slate Blue',    rgb:'#7B68EE',    h:249,    s:80,    l:67],
    [name:'Medium Spring Green',    rgb:'#00FA9A',    h:157,    s:100,    l:49],
    [name:'Medium Turquoise',    rgb:'#48D1CC',    h:178,    s:60,    l:55],
    [name:'Medium Violet Red',    rgb:'#C71585',    h:322,    s:81,    l:43],
    [name:'Midnight Blue',    rgb:'#191970',    h:240,    s:64,    l:27],
    [name:'Mint Cream',    rgb:'#F5FFFA',    h:150,    s:100,    l:98],
    [name:'Misty Rose',    rgb:'#FFE4E1',    h:6,    s:100,    l:94],
    [name:'Moccasin',    rgb:'#FFE4B5',    h:38,    s:100,    l:85],
    [name:'Navajo White',    rgb:'#FFDEAD',    h:36,    s:100,    l:84],
    [name:'Navy',    rgb:'#000080',    h:240,    s:100,    l:25],
    [name:'Old Lace',    rgb:'#FDF5E6',    h:39,    s:85,    l:95],
    [name:'Olive',    rgb:'#808000',    h:60,    s:100,    l:25],
    [name:'Olive Drab',    rgb:'#6B8E23',    h:80,    s:60,    l:35],
    [name:'Orange',    rgb:'#FFA500',    h:39,    s:100,    l:50],
    [name:'Orange Red',    rgb:'#FF4500',    h:16,    s:100,    l:50],
    [name:'Orchid',    rgb:'#DA70D6',    h:302,    s:59,    l:65],
    [name:'Pale Golden Rod',    rgb:'#EEE8AA',    h:55,    s:67,    l:80],
    [name:'Pale Green',    rgb:'#98FB98',    h:120,    s:93,    l:79],
    [name:'Pale Turquoise',    rgb:'#AFEEEE',    h:180,    s:65,    l:81],
    [name:'Pale Violet Red',    rgb:'#DB7093',    h:340,    s:60,    l:65],
    [name:'Papaya Whip',    rgb:'#FFEFD5',    h:37,    s:100,    l:92],
    [name:'Peach Puff',    rgb:'#FFDAB9',    h:28,    s:100,    l:86],
    [name:'Peru',    rgb:'#CD853F',    h:30,    s:59,    l:53],
    [name:'Pink',    rgb:'#FFC0CB',    h:350,    s:100,    l:88],
    [name:'Plum',    rgb:'#DDA0DD',    h:300,    s:47,    l:75],
    [name:'Powder Blue',    rgb:'#B0E0E6',    h:187,    s:52,    l:80],
    [name:'Purple',    rgb:'#800080',    h:300,    s:100,    l:25],
    [name:'Red',    rgb:'#FF0000',    h:0,    s:100,    l:50],
    [name:'Rosy Brown',    rgb:'#BC8F8F',    h:0,    s:25,    l:65],
    [name:'Royal Blue',    rgb:'#4169E1',    h:225,    s:73,    l:57],
    [name:'Saddle Brown',    rgb:'#8B4513',    h:25,    s:76,    l:31],
    [name:'Salmon',    rgb:'#FA8072',    h:6,    s:93,    l:71],
    [name:'Sandy Brown',    rgb:'#F4A460',    h:28,    s:87,    l:67],
    [name:'Sea Green',    rgb:'#2E8B57',    h:146,    s:50,    l:36],
    [name:'Sea Shell',    rgb:'#FFF5EE',    h:25,    s:100,    l:97],
    [name:'Sienna',    rgb:'#A0522D',    h:19,    s:56,    l:40],
    [name:'Silver',    rgb:'#C0C0C0',    h:0,    s:0,    l:75],
    [name:'Sky Blue',    rgb:'#87CEEB',    h:197,    s:71,    l:73],
    [name:'Slate Blue',    rgb:'#6A5ACD',    h:248,    s:53,    l:58],
    [name:'Slate Gray',    rgb:'#708090',    h:210,    s:13,    l:50],
    [name:'Snow',    rgb:'#FFFAFA',    h:0,    s:100,    l:99],
    [name:'Soft White',    rgb:'#B6DA7C',    h:83,    s:44,    l:67],
    [name:'Spring Green',    rgb:'#00FF7F',    h:150,    s:100,    l:50],
    [name:'Steel Blue',    rgb:'#4682B4',    h:207,    s:44,    l:49],
    [name:'Tan',    rgb:'#D2B48C',    h:34,    s:44,    l:69],
    [name:'Teal',    rgb:'#008080',    h:180,    s:100,    l:25],
    [name:'Thistle',    rgb:'#D8BFD8',    h:300,    s:24,    l:80],
    [name:'Tomato',    rgb:'#FF6347',    h:9,    s:100,    l:64],
    [name:'Turquoise',    rgb:'#40E0D0',    h:174,    s:72,    l:56],
    [name:'Violet',    rgb:'#EE82EE',    h:300,    s:76,    l:72],
    [name:'Warm White',    rgb:'#DAF17E',    h:72,    s:20,    l:72],
    [name:'Wheat',    rgb:'#F5DEB3',    h:39,    s:77,    l:83],
    [name:'White',    rgb:'#FFFFFF',    h:0,    s:0,    l:100],
    [name:'White Smoke',    rgb:'#F5F5F5',    h:0,    s:0,    l:96],
    [name:'Yellow',    rgb:'#FFFF00',    h:60,    s:100,    l:50],
    [name:'Yellow Green',    rgb:'#9ACD32',    h:80,    s:61,    l:50]
]
