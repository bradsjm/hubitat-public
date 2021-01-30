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
    name: 'Motion Lighting (Child)',
    namespace: 'nrgup',
    parent: 'nrgup:Motion Lighting Manager',
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
    page name: 'pageDevices', content: 'pageDevices'
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

// Main configuration page
Map pageMain() {
   state.remove('pageModeName')
   state.remove('pageModeID')
   dynamicPage(name: 'pageMain', title: 'Motion Lighting Controller', install: true, uninstall: true, hideWhenEmpty: true) {
        section {
            label title: 'Application Label',
                  required: false
        }

        section {
            input name: 'masterEnable',
                  title: 'Controller enabled',
                  type: 'bool',
                  required: false,
                  defaultValue: true
        }

        section {
            href name: 'pageDevices',
                    page: 'pageDevices',
                    title: 'Configure motion trigger devices',
                    description: getDeviceDescriptions() ?: 'Motion Sensors, Contact Sensors and Buttons',
                    state: getDeviceDescriptions() ? 'complete' : null
        }

        section('Lighting Configuration', hideable: true, hidden: true) {
            href name: "pageModeSettingsDefault",
                 page: 'pageMode',
                 title: 'Default mode',
                 params: [modeName: 'Default', modeID: 0],
                 description: getModeDescription(0) ?: 'Configure default lighting configuration',
                 state: getModeDescription(0) ? 'complete' : null

            location.getModes().each { mode ->
                href name: "pageModeSettings${mode.id}",
                     page: 'pagMode',
                     title: "${mode.name} mode",
                     params: [modeName: mode.name, modeID: mode.id],
                     description: getModeDescription(mode.id) ?: "Configure ${mode.name} lighting configuration",
                     state: getModeDescription(mode.id) ? 'complete' : null
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
        }

        section {
            input name: 'sendOn',
                  title: 'Send \"On\" command after \"Set Level\" or \"Set Color\" (enable if devices use prestaging)',
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

// Device configuration page
Map pageDevices() {
   dynamicPage(name: 'pageDevices', title: 'Configure trigger devices', uninstall: false, install: false, nextPage: 'pageMain', hideWhenEmpty: true) {
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

            input name: 'activationButtons',
                  title: 'Buttons to turn on lights',
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

    dynamicPage(name: 'pageMode', title: "${modeName} Mode Settings", uninstall: false, install: false, nextPage: 'pageMain',  hideWhenEmpty: true) {
        section {
            input name: "mode.${modeID}.enable",
                    title: "Enable ${modeName} settings",
                    type: 'bool',
                    required: false,
                    defaultValue: true,
                    submitOnChange: true
        }

        if (settings["mode.${modeID}.enable"]) {
            section('Lighting') {
                input name: "mode.${modeID}.lights",
                    title: 'Choose lights to turn on/off/dim',
                    type: 'capability.switch',
                    multiple: true,
                    required: true

                input name: "mode.${modeID}.active",
                    title: 'When motion is detected...',
                    type: 'enum',
                    options: activeActions,
                    required: true,
                    submitOnChange: true

                if (settings["mode.${modeID}.action"] == 'onColor') {
                    input name: "mode.${modeID}.level",
                        title: 'level',
                        type: 'number',
                        description: '0-100',
                        range: '0..100',
                        width: 2,
                        required: false
                    input name: "mode.${modeID}.CT",
                        title: 'CT',
                        type: 'number',
                        description: '~2000-7000',
                        range: '1000..8000',
                        width: 3,
                        required: false
                    input name: "mode.${modeID}.hue",
                        type: 'number',
                        title: 'hue',
                        range: '0..100',
                        description: '0-100',
                        width: 2,
                        required: false
                    input name: "mode.${modeID}.sat",
                        title: 'saturation',
                        range: '0..100',
                        description: '0-100',
                        width: 2,
                        required: false
                }
        
                input name: "mode.${modeID}.inactive",
                    title: 'When motion stops...',
                    type: 'enum',
                    options: inactiveActions,
                    required: true,
                    submitOnChange: true

                if (settings["mode.${modeID}.inactive"] != 'none') {
                    input name: "mode.${modeID}.inactiveMinutes",
                        title: 'Minutes to wait after motion stops',
                        type: 'number',
                        range: '1..3600',
                        description: 'number of minutes'
                }

                if (settings["mode.${modeID}.inactive"] == 'off') {
                input name: "mode.${modeID}.offLights",
                        title: '<i>Additional lights to turn off (optional)</i>',
                        type: 'capability.switch',
                        multiple: true
                }
            }
        }
    }
}

// Returns String summary of device settings, or empty string if that mode is not configured
String getDeviceDescriptions() {
    List items = []
    if (activationMotionSensors) {
        int size = activationMotionSensors.size()
        items += "${size} motion sensor${size > 1 ? 's' : ''}"
    }
    if (activationContactSensors) {
        int size = activationContactSensors.size()
        items += "${size} contact sensor${size > 1 ? 's' : ''}"
    }
    if (activationButtons) {
        int size = activationButtons.size()
        items += "${size} button${size > 1 ? 's' : ''}"
    }

    String description = ''
    items.eachWithIndex{ element, index ->
        if (index > 0 && index < items.size() - 1) { description += ', ' }
        else if (index > 0 && index == items.size() -1) { description += ' and ' }
        description += element
    }
    return description
}

// Returns String summary of per-mode settings, or empty string if that mode is not configured
String getModeDescription(Long modeID) {
    if (!settings["mode.${modeID}.enable"]) { return '' }

    String description = 'Lights: '
    settings["mode.${modeID}.lights"]?.eachWithIndex{ element, index ->
        if (index > 0 && index < items.size() - 1) { description += ', ' }
        else if (index > 0 && index == items.size() -1) { description += ' and ' }
        description += element
    }

    String action = activeActions.findResult { m -> m.get(settings["mode.${modeID}.active"]) }
    description += "\nWhen active: ${action}"
    action = inactiveActions.findResult { m -> m.get(settings["mode.${modeID}.inactive"]) }
    description += "\nWhen inactive: ${action}"

    return description
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
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

// Called when the driver is initialized.
void initialize() {
    log.info "${app.name} initializing"
    childApps.each { child ->
        log.debug "  child app: ${child.label}"
    }
}

// Called from child apps to check master switch is enabled
boolean enabled() {
    return this.masterEnable && parent?.enabled()
}
