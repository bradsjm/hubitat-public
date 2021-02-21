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
import com.hubitat.hub.domain.Event
import groovy.transform.Field
import hubitat.helper.ColorUtils

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
   [onLevel: 'Turn on and set brightness'],
   [onCT: 'Turn on and set color temperature'],
   [onColor: 'Turn on and set light color'],
   [none: 'No action (do not turn on)']
]

@Field static final List<Map<String,String>> inactiveActions = [
   [off: 'Turn off lights'],
   [dimLevel: 'Dim light level'],
   [restore: 'Restore previous state'],
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
            if (state.triggered?.active) {
                String ago
                int elapsed = (now() - state.triggered.active) / 1000
                if (elapsed < 120) {
                    ago = "${elapsed} second(s) ago"
                } else {
                    elapsed /= 60
                    ago = "${elapsed} minutes ago"
                }
                if (state.triggered.running) {
                    paragraph "<b>Triggered</b>: ${ago} by ${state.triggered.device} ${state.triggered.type} sensor"
                } else {
                    paragraph "Last triggered: ${ago} by ${state.triggered.device} ${state.triggered.type} sensor"
                }

                String disabled = getDisabledDescription()
                if (disabled) {
                    paragraph "<b>Disabled lights</b>: ${disabled}"
                }
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

        section('Lamp Override Settings', hideable: true, hidden: true) {
            input name: 'autoDisable',
                  title: 'Disable lamp control if turned off manually (after turned on by controller)',
                  type: 'bool',
                  required: false,
                  defaultValue: true

            input name: 'reenableDelay',
                  type: 'number',
                  title: 'Minutes to wait to re-enable automatic lamp control',
                  description: 'minutes',
                  range: '1..600',
                  required: false,
                  defaultValue: 60,
                  width: 7
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
            pageModeSectionActive(modeID)
            pageModeSectionInactive(modeID)
            pageModeSectionTest(modeID)
        }
                       }
}

Map pageModeSectionActive(Long modeID) {
    return section {
        input name: "mode.${modeID}.active",
            title: 'When activity is detected...',
            type: 'enum',
            options: activeActions,
            defaultValue: 'on',
            required: true,
            submitOnChange: true

        if (settings["mode.${modeID}.active"] == 'none') {
            app.removeSetting("mode.${modeID}.activeLights")
            app.removeSetting("mode.${modeID}.activeColor")
            app.removeSetting("mode.${modeID}.activeCT")
            app.removeSetting("mode.${modeID}.activeLevel")
            app.removeSetting("mode.${modeID}.activeTransitionTime")
        } else {
            input name: "mode.${modeID}.activeLights",
                title: 'Choose lights to turn on/off/dim',
                description: modeID == 0 ? 'Click to set' : 'Click to override default lights',
                type: 'capability.switch',
                multiple: true,
                submitOnChange: true

            switch (settings["mode.${modeID}.active"]) {
                case 'onLevel':
                    input name: "mode.${modeID}.activeLevel",
                        title: 'Set brightness (1-100) %',
                        type: 'number',
                        range: '1..100',
                        width: 5,
                        required: true,
                        submitOnChange: true
                    input name: "mode.${modeID}.activeTransitionTime",
                        title: 'Transition seconds (optional)',
                        type: 'number',
                        range: '1..600',
                        width: 5,
                        submitOnChange: true
                    break
                case 'onCT':
                    input name: "mode.${modeID}.activeCT",
                        title: 'Set color temperature (K)',
                        type: 'number',
                        range: '2000..6000',
                        width: 5,
                        required: true,
                        submitOnChange: true
                    break
                case 'onColor':
                    input name: "mode.${modeID}.activeColor",
                        title: 'Select light color',
                        type: 'enum',
                        options: colors.collect { c -> [ (c.rgb):c.name ] },
                        width: 5,
                        required: true,
                        submitOnChange: true
                    paragraph ''
                    input name: 'btnTestColor',
                        title: 'Test Color',
                        width: 4,
                        type: 'button'
                    break
            }
        }
    }
}

Map pageModeSectionInactive(Long modeID) {
    if (settings["mode.${modeID}.active"] == 'none') {
        app.removeSetting("mode.${modeID}.inactive")
        app.removeSetting("mode.${modeID}.inactiveLevel")
        app.removeSetting("mode.${modeID}.inactiveLights")
        app.removeSetting("mode.${modeID}.inactiveTransitionTime")
        return [:]
    }

    return section {
        input name: "mode.${modeID}.inactive",
            title: 'When activity stops...',
            type: 'enum',
            options: inactiveActions,
            defaultValue: 'off',
            required: true,
            submitOnChange: true

        if (settings["mode.${modeID}.inactive"] != 'none') {
            input name: "mode.${modeID}.inactiveMinutes",
                  title: 'Delay after activity stops',
                  description: modeID == 0 ? 'number of minutes' : 'override default mode minutes',
                  type: 'number',
                  range: '1..3600',
                  width: 4,
                  required: modeID == 0
        }

        switch (settings["mode.${modeID}.inactive"]) {
            case 'dimLevel':
                input name: "mode.${modeID}.inactiveLevel",
                      title: 'Dim to level (0-100) %',
                      type: 'number',
                      range: '0..100',
                      width: 4,
                      required: true,
                      submitOnChange: true
                input name: "mode.${modeID}.inactiveTransitionTime",
                      title: 'Transition seconds (optional)',
                      type: 'number',
                      range: '1..600',
                      width: 4,
                      submitOnChange: true
                break
            case 'off':
                input name: "mode.${modeID}.inactiveLights",
                      title: '<i>Additional lights to turn off (optional)</i>',
                      type: 'capability.switch',
                      multiple: true,
                      submitOnChange: true
                break
            case 'none':
                app.removeSetting("mode.${modeID}.inactiveLevel")
                app.removeSetting("mode.${modeID}.inactiveLights")
                app.removeSetting("mode.${modeID}.inactiveMinutes")
                app.removeSetting("mode.${modeID}.inactiveTransitionTime")
                break
        }
    }
}

Map pageModeSectionTest(Long modeID) {
    if (settings["mode.${modeID}.active"] != 'none') {
        return section('<u>Activity Testing</u>') {
            paragraph 'Use buttons below to emulate actions'
            input name: 'btnTestActive',
                title: 'Test Activity Detected',
                width: 4,
                type: 'button'
            if (settings["mode.${modeID}.inactive"] != 'none') {
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
    Map mode = getModeSettings(state.pageModeID)
    switch (buttonName) {
        case 'btnTestColor':
            setLights(mode.activeLights, 'setColor', getColorByRGB(mode.color))
            if (settings.sendOn) { setLights(mode.activeLights, 'on') }
            break
        case 'btnTestActive':
            log.info "${app.name} testing ${mode.name} mode activity detected"
            state.triggered = [
                type: 'test',
                device: "${mode.name} mode test"
            ]
            performActiveAction(mode)
            break
        case 'btnTestInactive':
            log.info "${app.name} testing ${mode.name} mode activity stopped"
            state.triggered = [
                type: 'test',
                device: "${mode.name} mode test"
            ]
            performInactiveAction(mode)
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

// Returns a description of lights that are currently disabled
String getDisabledDescription() {
    Map mode = getActiveMode()
    List lights = mode.activeLights.findAll {
        device -> state.disabledDevices.containsKey(device.id)
    }*.displayName
    if (!lights) { return '' }
    lights.sort()
    String description = ''
    if (lights.size() <= 10) {
        lights.eachWithIndex { element, index ->
            if (index > 0 && index < lights.size() - 1) { description += ', ' }
            else if (index > 0 && index == lights.size() - 1) { description += ' and ' }
            description += element
        }
    } else {
        description = "${lights.size()} lights"
    }

    return description
}

// Returns String summary of device settings, or empty string if that mode is not configured
String getTriggerDescription() {
    List devices = []
    devices.addAll(settings.activationMotionSensors*.displayName ?: [])
    devices.addAll(settings.activationContactSensors*.displayName ?: [])
    devices.addAll(settings.activationButtons*.displayName ?: [])
    devices.addAll(settings.activationOnSwitches*.displayName ?: [])
    devices.addAll(settings.activationOffSwitches*.displayName ?: [])
    devices.sort()

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
    Map mode = getModeSettings(modeID)
    if (!mode.enable) { return '' }

    if (!mode.activeLights) { return '' }

    String description = ''
    if (mode.active != 'none') {
        List lights = mode.activeLights*.displayName
        lights.sort()
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

    String action = activeActions.findResult { m -> m.get(mode.active) }
    if (action) {
        description += "When active: ${action}\n"
        action = inactiveActions.findResult { m -> m.get(mode.inactive) }
        if (action) {
            description += "When inactive: ${action}"
            if (mode.inactiveMinutes) {
                description += " after ${mode.inactiveMinutes} minute"
                if (mode.inactiveMinutes > 1) { description += 's' }
            }
            if (mode.inactiveLights) {
                description += ' (plus additional lights)'
            }
        }
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
        if (!state.triggered.running) {
            state.triggered = [
                type: 'button',
                device: evt.device.displayName,
                value: evt.value
            ]
            performActiveAction(mode)
        }
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
        if (!state.triggered.running) {
            state.triggered = [
                type: 'contact',
                device: evt.device.displayName,
                value: evt.value
            ]
            performActiveAction(mode)
        }
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

    if (!settings.masterEnable || !parent.enabled()) {
        unschedule()
        state.triggered = [ running: false ]
    }
}

void lightHandler(Event evt) {
    if (!state.triggered.running || evt.value == 'on') { return }
    log.info "${app.name} ${evt.device} was manually switched off"
    if (settings.autoDisable == true) {
        sendEvent name: 'disable', value: evt.device, descriptionText: 'Disabling light control (turned off manually)'
        state.disabledDevices.put(device.id, now())
    }
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

    // evt.value inactive should extend schedule?

    if (
        (evt.device.id in settings.activationMotionSensors*.id) ||
        (state.triggered?.running == true && evt.device.id in settings.additionalMotionSensors*.id)
    ) {
        if (!state.triggered.running) {
            state.triggered = [
                type: 'motion',
                device: evt.device.displayName,
                value: evt.value
            ]
            performActiveAction(mode)
        }
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
        if (!state.triggered.running) {
            state.triggered = [
                type: 'switch',
                device: evt.device.displayName,
                value: evt.value
            ]
            performActiveAction(mode)
        }
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

// Capture state of lights
private void captureLightState(List lights) {
    state.capture = [:]
    lights.each { device ->
        String colorMode = device.currentValue('colorMode')
        List<String> captureAttributes = []
        if (colorMode == 'RGB') { captureAttributes += ['color'] }
        if (colorMode == 'CT') { captureAttributes += ['colorTemperature'] }
        captureAttributes += [ 'level', 'switch' ]
        state.capture[device.id] = [:]
        captureAttributes.each { attr ->
            if (device.hasAttribute(attr)) {
                state.capture[device.id][attr] = device.currentValue(attr)
            }
        }
    }
}

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

// Returns the currently active mode (which may be default if not overridden)
private Map getActiveMode() {
    Long id = location.currentMode.id
    if (settings["mode.${id}.enable"] != true) { id = 0 }
    return getModeSettings(id)
}

// Return color map from RGB
private Map getColorByRGB(String rgb) {
    (hue, saturation, level) = ColorUtils.rgbToHSV(ColorUtils.hexToRGB(rgb))
    return [ hue: hue, saturation: saturation, level: level ]
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
        mode.activeColor = settings["mode.${mode.id}.activeColor"] as String
        mode.activeColorTemperature = settings["mode.${mode.id}.activeCT"] as BigDecimal
        mode.activeLevel = settings["mode.${mode.id}.activeLevel"] as BigDecimal
        mode.activeLights = settings["mode.${mode.id}.activeLights"] ?: []
        mode.activeTransitionTime = settings["mode.${mode.id}.activeTransitionTime"] as BigDecimal

        mode.inactive = settings["mode.${mode.id}.inactive"] as String
        mode.inactiveLevel = settings["mode.${mode.id}.inactiveLevel"] as BigDecimal
        mode.inactiveLights = settings["mode.${mode.id}.inactiveLights"] ?: []
        mode.inactiveMinutes = settings["mode.${mode.id}.inactiveMinutes"] as Integer
        mode.inactiveTransitionTime = settings["mode.${mode.id}.inactiveTransitionTime"] as BigDecimal

        // If mode has no lights use the default mode values
        if (mode.id > 0) {
            mode.activeLights = mode.activeLights ?: settings['mode.0.activeLights'] ?: []
            mode.inactiveMinutes = mode.inactiveMinutes ?: settings['mode.0.inactiveMinutes'] as Integer
        }
    }

    return mode
}

// Performs the action on the specified lights
private void performAction(Map mode, String action) {
    switch (mode[action]) {
        case 'dimLevel':
            setLights(mode.activeLights, 'setLevel', mode.inactiveLevel, mode.inactiveTransitionTime)
            if (settings.sendOn) { setLights(mode.activeLights, 'on') }
            break
        case 'off':
            List lights = mode.activeLights
            lights.addAll(mode.inactiveLights ?: [])
            setLights(lights, 'off')
            break
        case 'on':
            setLights(mode.activeLights, 'on')
            break
        case 'onColor':
            setLights(mode.activeLights, 'setColor', getColorByRGB(mode.activeColor))
            if (settings.sendOn) { setLights(mode.activeLights, 'on') }
            break
        case 'onCT':
            setLights(lights, 'setColorTemperature', mode.activeColorTemperature)
            if (settings.sendOn) { setLights(mode.activeLights, 'on') }
            break
        case 'onLevel':
            setLights(mode.activeLights, 'setLevel', mode.activeLevel, mode.activeTransitionTime)
            if (settings.sendOn) { setLights(mode.activeLights, 'on') }
            break
        case 'restore':
            restoreLightState(mode.activeLights)
            break
    }
}

// Performs the configured actions when specified mode triggered
private void performActiveAction(Map mode) {
    if (logEnable) { log.debug "Performing active action for mode ${mode.name}" }
    if (!mode.activeLights || mode.active == 'none') { return }

    state.triggered.running = true
    state.triggered.active = now()

    sendEvent name: 'active', value: mode.name, descriptionText: "Triggered by ${state.triggered.device}"

    if (mode.inactive == 'restore') { captureLightState(mode.activeLights) }
    performAction(mode, 'active')
    subscribe(mode.activeLights, 'switch', lightHandler)
}

// Performs the configured actions when specified mode becomes inactive
private void performInactiveAction() {
    performInactiveAction( getActiveMode() )
}

private void performInactiveAction(Map mode) {
    if (logEnable) { log.debug "Performing inactive action for mode ${mode.name}" }
    if (!mode.activeLights || mode.active == 'none' || mode.inactive == 'none') { return }

    state.triggered.running = false
    state.triggered.inactive = now()

    sendEvent name: 'inactive', value: mode.name

    unsubscribe(mode.activeLights)
    performAction(mode, 'inactive')
}

// Performs the configured actions when changing between modes
private void performTransitionAction(Map oldMode, Map newMode) {
    if (state.triggered.running == true) {
        List newLights = newMode.activity == 'none' ? [] : newMode.activeLights*.id
        if (newLights) {
            oldMode.activeLights = oldMode.activeLights.findAll { device -> !(device.id in newLights) }
            oldMode.inactiveLights = oldMode.inactiveLights.findAll { device -> !(device.id in newLights) }
        }
        sendEvent name: 'transition',
                  value: oldMode.name,
                  descriptionText: "Transitioning from ${oldMode.name} to ${newMode.name}"
        performInactiveAction(oldMode)
        performActiveAction(newMode)
    }
}

// Restore state of lights
private void restoreLightState(List lights) {
    if (!state.capture) { return }

    lights.each { device ->
        if (state.capture[device.id]) {
            state.capture[device.id].each { attr ->
                Object value = state.capture[device.id][attr]
                switch (attr) {
                    case 'color': device.setColor(value); break
                    case 'colorTemperature': device.setColorTemperature(value); break
                    case 'level': device.setLevel(value); break
                    case 'switch': if (value == 'on') { device.on() } else { device.off() } ; break
                }
            }
        }
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
private void setLights(List lights, String action, Object[] value) {
    if (logEnable) { log.debug "${lights} ${action} ${value ?: ''}" }

    if (settings.reenableDelay && state.disabledDevices) {
        long expire = now() - (settings.reenableDelay * 60000)
        state.disabledDevices.values().removeIf { v -> v <= expire }
    }

    lights.findAll { device -> !state.disabledDevices.containsKey(device.id) }.each { device ->
        if (value) {
            device."$action"(value)
        } else {
            device."$action"()
        }
}
}

@Field static final List<Map<String, String>> colors = [
    [name:'Alice Blue', rgb:'#F0F8FF'],
    [name:'Antique White', rgb:'#FAEBD7'],
    [name:'Aqua', rgb:'#00FFFF'],
    [name:'Aquamarine', rgb:'#7FFFD4'],
    [name:'Azure', rgb:'#F0FFFF'],
    [name:'Beige', rgb:'#F5F5DC'],
    [name:'Bisque', rgb:'#FFE4C4'],
    [name:'Blanched Almond', rgb:'#FFEBCD'],
    [name:'Blue', rgb:'#0000FF'],
    [name:'Blue Violet', rgb:'#8A2BE2'],
    [name:'Brown', rgb:'#A52A2A'],
    [name:'Burly Wood', rgb:'#DEB887'],
    [name:'Cadet Blue', rgb:'#5F9EA0'],
    [name:'Chartreuse', rgb:'#7FFF00'],
    [name:'Chocolate', rgb:'#D2691E'],
    [name:'Cool White', rgb:'#F3F6F7'],
    [name:'Coral', rgb:'#FF7F50'],
    [name:'Corn Flower Blue', rgb:'#6495ED'],
    [name:'Corn Silk', rgb:'#FFF8DC'],
    [name:'Crimson', rgb:'#DC143C'],
    [name:'Cyan', rgb:'#00FFFF'],
    [name:'Dark Blue', rgb:'#00008B'],
    [name:'Dark Cyan', rgb:'#008B8B'],
    [name:'Dark Golden Rod', rgb:'#B8860B'],
    [name:'Dark Gray', rgb:'#A9A9A9'],
    [name:'Dark Green', rgb:'#006400'],
    [name:'Dark Khaki', rgb:'#BDB76B'],
    [name:'Dark Magenta', rgb:'#8B008B'],
    [name:'Dark Olive Green', rgb:'#556B2F'],
    [name:'Dark Orange', rgb:'#FF8C00'],
    [name:'Dark Orchid', rgb:'#9932CC'],
    [name:'Dark Red', rgb:'#8B0000'],
    [name:'Dark Salmon', rgb:'#E9967A'],
    [name:'Dark Sea Green', rgb:'#8FBC8F'],
    [name:'Dark Slate Blue', rgb:'#483D8B'],
    [name:'Dark Slate Gray', rgb:'#2F4F4F'],
    [name:'Dark Turquoise', rgb:'#00CED1'],
    [name:'Dark Violet', rgb:'#9400D3'],
    [name:'Daylight White', rgb:'#CEF4FD'],
    [name:'Deep Pink', rgb:'#FF1493'],
    [name:'Deep Sky Blue', rgb:'#00BFFF'],
    [name:'Dim Gray', rgb:'#696969'],
    [name:'Dodger Blue', rgb:'#1E90FF'],
    [name:'Fire Brick', rgb:'#B22222'],
    [name:'Floral White', rgb:'#FFFAF0'],
    [name:'Forest Green', rgb:'#228B22'],
    [name:'Fuchsia', rgb:'#FF00FF'],
    [name:'Gainsboro', rgb:'#DCDCDC'],
    [name:'Ghost White', rgb:'#F8F8FF'],
    [name:'Gold', rgb:'#FFD700'],
    [name:'Golden Rod', rgb:'#DAA520'],
    [name:'Gray', rgb:'#808080'],
    [name:'Green', rgb:'#008000'],
    [name:'Green Yellow', rgb:'#ADFF2F'],
    [name:'Honeydew', rgb:'#F0FFF0'],
    [name:'Hot Pink', rgb:'#FF69B4'],
    [name:'Indian Red', rgb:'#CD5C5C'],
    [name:'Indigo', rgb:'#4B0082'],
    [name:'Ivory', rgb:'#FFFFF0'],
    [name:'Khaki', rgb:'#F0E68C'],
    [name:'Lavender', rgb:'#E6E6FA'],
    [name:'Lavender Blush', rgb:'#FFF0F5'],
    [name:'Lawn Green', rgb:'#7CFC00'],
    [name:'Lemon Chiffon', rgb:'#FFFACD'],
    [name:'Light Blue', rgb:'#ADD8E6'],
    [name:'Light Coral', rgb:'#F08080'],
    [name:'Light Cyan', rgb:'#E0FFFF'],
    [name:'Light Golden Rod Yellow', rgb:'#FAFAD2'],
    [name:'Light Gray', rgb:'#D3D3D3'],
    [name:'Light Green', rgb:'#90EE90'],
    [name:'Light Pink', rgb:'#FFB6C1'],
    [name:'Light Salmon', rgb:'#FFA07A'],
    [name:'Light Sea Green', rgb:'#20B2AA'],
    [name:'Light Sky Blue', rgb:'#87CEFA'],
    [name:'Light Slate Gray', rgb:'#778899'],
    [name:'Light Steel Blue', rgb:'#B0C4DE'],
    [name:'Light Yellow', rgb:'#FFFFE0'],
    [name:'Lime', rgb:'#00FF00'],
    [name:'Lime Green', rgb:'#32CD32'],
    [name:'Linen', rgb:'#FAF0E6'],
    [name:'Maroon', rgb:'#800000'],
    [name:'Medium Aquamarine', rgb:'#66CDAA'],
    [name:'Medium Blue', rgb:'#0000CD'],
    [name:'Medium Orchid', rgb:'#BA55D3'],
    [name:'Medium Purple', rgb:'#9370DB'],
    [name:'Medium Sea Green', rgb:'#3CB371'],
    [name:'Medium Slate Blue', rgb:'#7B68EE'],
    [name:'Medium Spring Green', rgb:'#00FA9A'],
    [name:'Medium Turquoise', rgb:'#48D1CC'],
    [name:'Medium Violet Red', rgb:'#C71585'],
    [name:'Midnight Blue', rgb:'#191970'],
    [name:'Mint Cream', rgb:'#F5FFFA'],
    [name:'Misty Rose', rgb:'#FFE4E1'],
    [name:'Moccasin', rgb:'#FFE4B5'],
    [name:'Navajo White', rgb:'#FFDEAD'],
    [name:'Navy', rgb:'#000080'],
    [name:'Old Lace', rgb:'#FDF5E6'],
    [name:'Olive', rgb:'#808000'],
    [name:'Olive Drab', rgb:'#6B8E23'],
    [name:'Orange', rgb:'#FFA500'],
    [name:'Orange Red', rgb:'#FF4500'],
    [name:'Orchid', rgb:'#DA70D6'],
    [name:'Pale Golden Rod', rgb:'#EEE8AA'],
    [name:'Pale Green', rgb:'#98FB98'],
    [name:'Pale Turquoise', rgb:'#AFEEEE'],
    [name:'Pale Violet Red', rgb:'#DB7093'],
    [name:'Papaya Whip', rgb:'#FFEFD5'],
    [name:'Peach Puff', rgb:'#FFDAB9'],
    [name:'Peru', rgb:'#CD853F'],
    [name:'Pink', rgb:'#FFC0CB'],
    [name:'Plum', rgb:'#DDA0DD'],
    [name:'Powder Blue', rgb:'#B0E0E6'],
    [name:'Purple', rgb:'#800080'],
    [name:'Red', rgb:'#FF0000'],
    [name:'Rosy Brown', rgb:'#BC8F8F'],
    [name:'Royal Blue', rgb:'#4169E1'],
    [name:'Saddle Brown', rgb:'#8B4513'],
    [name:'Salmon', rgb:'#FA8072'],
    [name:'Sandy Brown', rgb:'#F4A460'],
    [name:'Sea Green', rgb:'#2E8B57'],
    [name:'Sea Shell', rgb:'#FFF5EE'],
    [name:'Sienna', rgb:'#A0522D'],
    [name:'Silver', rgb:'#C0C0C0'],
    [name:'Sky Blue', rgb:'#87CEEB'],
    [name:'Slate Blue', rgb:'#6A5ACD'],
    [name:'Slate Gray', rgb:'#708090'],
    [name:'Snow', rgb:'#FFFAFA'],
    [name:'Soft White', rgb:'#B6DA7C'],
    [name:'Spring Green', rgb:'#00FF7F'],
    [name:'Steel Blue', rgb:'#4682B4'],
    [name:'Tan', rgb:'#D2B48C'],
    [name:'Teal', rgb:'#008080'],
    [name:'Thistle', rgb:'#D8BFD8'],
    [name:'Tomato', rgb:'#FF6347'],
    [name:'Turquoise', rgb:'#40E0D0'],
    [name:'Violet', rgb:'#EE82EE'],
    [name:'Warm White', rgb:'#DAF17E'],
    [name:'Wheat', rgb:'#F5DEB3'],
    [name:'White', rgb:'#FFFFFF'],
    [name:'White Smoke', rgb:'#F5F5F5'],
    [name:'Yellow', rgb:'#FFFF00'],
    [name:'Yellow Green', rgb:'#9ACD32']
].asImmutable()
