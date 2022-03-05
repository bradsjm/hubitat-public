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
import java.util.concurrent.ConcurrentHashMap

definition(
    name: 'Button Lighting Instance',
    namespace: 'nrgup',
    parent: 'nrgup:Button Lighting',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Advanced control of lighting based on buttons',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page name: 'pageMain'
    page name: 'pageMode'
    page name: 'pageButtonConfig'
}

@Field static final Map<String,Map> eventMap = [
   'pushed': [ capability: 'PushableButton', userAction: 'Push', maxPresses: 5 ],
   'held': [ capability: 'HoldableButton', userAction: 'Hold', maxPresses: 1 ],
   'released': [ capability: 'ReleasableButton', userAction: 'Release', maxPresses: 1 ],
   'doubleTapped': [ capability: 'DoubleTapableButton', userAction: 'Double Tap', maxPresses: 5 ]
]

@Field static final Map<Integer, String> ordinals = [
    1: 'First',
    2: 'Second',
    3: 'Third',
    4: 'Fourth',
    5: 'Fifth'
]

@Field static final Map<String,String> buttonActions = [
   on: 'Turn on lights',
   onLevel: 'Turn on and set brightness',
   onCT: 'Turn on and set color temperature',
   onColor: 'Turn on and set light color',
   dimDown: 'Start dimming down',
   dimUp: 'Start dimming up',
   stop: 'Stop dimming',
   off: 'Turn off lights',
   restore: 'Restore previous state'
]

@Field static final Map<String, Map> counters = new ConcurrentHashMap<String, Map>()

/*
 * Configuration UI
 */
/* groovylint-disable-next-line MethodSize */
Map pageMain() {
    state.remove('pageModeName')
    state.remove('pageModeID')

    return dynamicPage(name: 'pageMain', title: 'Button Lighting Controller',
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
                paragraph '<span style=\'font-weight: bold;\'>' +
                          'All controllers disabled by the parent application setting</span>'
            }

            paragraph 'This application will bind one or more button controllers to ' +
                      'a set of lighting devices with configurable behaviour.'

            input name: 'buttonDevices',
                  title: 'Select button device(s)',
                  type: 'capability.pushableButton',
                  multiple: true,
                  required: true,
                  submitOnChange: true,
                  width: 12

            paragraph 'Configure the individual button behaviour below:'

            href name: 'pageModeSettingsDefault',
                 page: 'pageMode',
                 title: 'Default button mappings',
                 params: [ modeId: 0 ],
                 description: getModeDescription(0) ?: 'Click to configure default button mappings',
                 state: settings['mode.0.enable'] ? 'complete' : null,
                 width: 12
        }

        List configuredModes = getConfiguredModes()
        section(configuredModes ? 'Mode Overrides:' : '') {
            Long activeId = getActiveMode().id
            location.getModes().each { mode ->
                if ((String)mode.id in settings?.modes || settings["mode.${mode.id}.enable"] == true) {
                    href name: 'pageModeHref',
                        page: 'pageMode',
                        title: mode.id == activeId ? "<span style='font-weight: bold'>${mode.name} mode (currently active)</span>" : "${mode.name} mode",
                        params: [ modeId: mode.id ],
                        description: getModeDescription(mode.id) ?:
                            "Click to override default button mappings in ${mode.name} mode",
                        state: settings["mode.${mode.id}.enable"] ? 'complete' : null,
                        width: 12
                }
            }

            List availableModes = getAvailableModes()
            if (availableModes) {
                input name: 'modes',
                    title: 'Select mode to create override configuration',
                    type: 'enum',
                    options: availableModes,
                    submitOnChange: true,
                    width: 12
            }
        }

        section {
            input name: 'optimize',
                  title: 'Enable on/off optimization',
                  type: 'bool',
                  required: false,
                  defaultValue: false

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

// Mode configuration page
Map pageMode(Map params) {
    Long modeId = params ? params.modeId : atomicState.modeId
    Map mode = getModeSettings(modeId)
    atomicState.modeId = modeId

    return dynamicPage(name: 'pageMode', title: "${mode.name} Mode Actions") {
        section {
            input name: "mode.${mode.id}.enable",
                    title: "Enable ${mode.name} mode actions",
                    type: 'bool',
                    defaultValue: true,
                    submitOnChange: true
        }

        if (settings["mode.${mode.id}.enable"] != false) {
            section {
                input name: "mode.${mode.id}.lights",
                    title: 'Select lights to turn on/off/dim',
                    description: mode.id == 0 ? 'Click to set' : 'Click to override default lights',
                    type: 'capability.switchLevel',
                    multiple: true,
                    submitOnChange: false
            }

            int buttonCount = getButtonCount()
            Map capabilities = getButtonCapabilities()

            section('Configure Button Actions') {
                for (int button in (1..buttonCount)) {
                    capabilities.each { String action, Map capability ->
                        Map p = [modeId: mode.id, button: button, action: action]
                        String desc = capability.maxPresses > 1 ? 'Actions' : 'Action'
                        String config = getButtonConfig(p)
                        href name: 'pageButtonConfigHref',
                            page: 'pageButtonConfig',
                            title: "Button ${button} ${capability.userAction} ${desc}",
                            params: p,
                            description: config ?: 'Click to configure button actions',
                            state: config ? 'complete' : null,
                            width: 12
                    }
                }
            }
        }
    }
}

Map pageButtonConfig(Map params) {
    Long modeId = params ? params.modeId : atomicState.modeId
    Map mode = getModeSettings(modeId)
    Integer button = params ? params.button : atomicState.button
    String action = params ? params.action : atomicState.action
    atomicState.modeId = modeId
    atomicState.button = button
    atomicState.action = action

    Map capability = eventMap[action]
    int maxPresses = capability.maxPresses ?: 1
    return dynamicPage(name: 'pageButtonConfig', title: "${mode.name} Mode Button ${button} ${capability.userAction} Settings") {
        section {
            for (int count in (1..maxPresses)) {
                String name = "mode.${mode.id}.button.${button}.${action}.${count}"
                String ordinal = maxPresses > 1 ? ordinals[count] + ' ' : ''
                input title: "On Button ${button} ${ordinal}${capability.userAction}:",
                    name: name,
                    type: 'enum',
                    options: buttonActions,
                    required: false,
                    defaultValue: getDefaultButtonAction(button, action, count),
                    submitOnChange: true

                switch (settings[name]) {
                    case 'onLevel':
                        input name: "${name}.brightness",
                            title: 'Set brightness (1-100) %',
                            type: 'number',
                            range: '1..100',
                            width: 5,
                            required: true,
                            submitOnChange: true
                        input name: "${name}.transitionTime",
                            title: 'Transition seconds (optional)',
                            type: 'number',
                            range: '1..600',
                            width: 5,
                            submitOnChange: true
                        break
                    case 'onCT':
                        input name: "${name}.temperature",
                            title: 'Set color temperature (K)',
                            type: 'number',
                            range: '2000..6000',
                            width: 5,
                            required: true,
                            submitOnChange: true
                        break
                    case 'onColor':
                        input name: "${name}.color",
                            title: 'Select light color',
                            type: 'enum',
                            options: colors.collect { c -> [ (c.rgb):c.name ] },
                            width: 5,
                            required: true,
                            submitOnChange: true
                        break
                    default:
                        app.removeSetting("${name}.brightness")
                        app.removeSetting("${name}.transitionTime")
                        app.removeSetting("${name}.color")
                        app.removeSetting("${name}.temperature")
                        break
                }
            }
        }
    }
}

// Returns the combined button capabiltiies
Map getButtonCapabilities() {
    Set allCapabilities = settings.buttonDevices*.getCapabilities()?.name?.flatten() ?: []
    return eventMap.findAll { e -> e.value.capability in allCapabilities }
}

// Returns the combined largest number of buttons
Integer getButtonCount() {
    return settings.buttonDevices.collect { d -> d.currentValue('numberOfButtons') as Integer }?.max() ?: 0
}

// Returns description of button configuration
String getButtonConfig(Map params) {
    Map mode = getModeSettings(params.modeId)
    Integer button = params.button
    String action = params.action
    Map capability = eventMap[action]
    StringBuilder sb = new StringBuilder()

    for (int count in (1..capability.maxPresses)) {
        String name = "mode.${mode.id}.button.${button}.${action}.${count}"
        if (settings[name] && settings[name] != 'none') {
            String ordinal = capability.maxPresses > 1 ? ordinals[count] + ' ' : ''
            sb << "<strong>${ordinal}${capability.userAction}:</strong> "
            switch (settings[name]) {
                case 'onLevel':
                    sb << 'Set level to ' + settings[name + '.brightness'] + '%'
                    break
                case 'onCT':
                    sb << 'Set color temperature to ' + settings[name + '.temperature'] + 'K'
                    break
                case 'onColor':
                    sb << 'Set color to ' + settings[name + '.color']
                    break
                default:
                    sb << buttonActions[settings[name]]
            }
            sb << '<br>'
        }
    }

    return sb.toString()
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

// Helper to select common functions
String getDefaultButtonAction(int button, String action, int count) {
    switch (action) {
        case 'pushed':
            if (button == 1 && count == 1) {
                return buttonActions['on']
            }

            if (button == 2 && count == 1) {
                return buttonActions['off']
            }
            break
        case 'held':
            if (button == 1) {
                return buttonActions['dimUp']
            }

            if (button == 2) {
                return buttonActions['dimDown']
            }
            break
        case 'released':
            return buttonActions['stop']
        default:
            return ''
    }
}

String getLightNames(List lights) {
    if (lights.size() <= 10) {
        String description = ''
        lights.eachWithIndex { element, index ->
            if (index > 0 && index < lights.size() - 1) { description += ', ' }
            else if (index > 0 && index == lights.size() - 1) { description += ' and ' }
            description += element
        }
        return description
    }

    return "${lights.size()} lights configured"
}

// Returns String summary of per-mode settings, or empty string if that mode is not configured
String getModeDescription(Long modeID) {
    Map mode = getModeSettings(modeID)
    if (!mode.enable) { return '' }

    StringBuilder sb = new StringBuilder()
    if (mode.active != 'none' && mode.lights) {
        List lights = mode.lights*.displayName
        lights.sort()
        sb << '<strong>Lights:</strong> ' + getLightNames(lights) + '<br>'

        int buttonCount = getButtonCount()
        Map capabilities = getButtonCapabilities()

        for (int button in (1..buttonCount)) {
            sb << "<br>Button ${button} Actions:<br>"
            capabilities.keySet().each { String action ->
                String desc = getButtonConfig([modeId: mode.id, button: button, action: action])
                if (desc) {
                    sb << '<div style="margin-left: 20px;">' << desc << '</div>'
                }
            }
        }
    }

    return sb.toString()
}

/*
 * Application Logic
 */

// Called when a button is pressed on the settings page
void buttonHandler(Event evt) {
    Map mode = getActiveMode()
    LOG.trace "buttonHandler: ${evt.device} ${evt.name} ${evt.value} (mode ${mode.name})"
    if (!checkEnabled(mode)) { return }
    String action = evt.name
    int button = evt.value as int
    int maxPresses = eventMap[action]?.maxPresses ?: 1
    int count = Math.min(maxPresses, getCounter(evt))
    String name = "mode.${mode.id}.button.${button}.${action}.${count}"
    performAction(mode, settings[name])
}

// Called when the app is first created.
void installed() {
    LOG.info "${app.name} installed"
}

// Called when the driver is initialized.
void initialize() {
    LOG.info "${app.name} initializing"
    unsubscribe()
    subscribe(settings.buttonDevices, 'buttonHandler', [:])
}

// Called when the app is removed.
void uninstalled() {
    LOG.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    LOG.info "${app.name} configuration updated"
    LOG.debug settings
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
    if (!mode?.enable) {
        LOG.info "${mode.name} is disabled"
        return false
    }

    if (!settings.masterEnable) {
        LOG.info "${app.name} is disabled"
        return false
    }

    if (!parent.enabled()) {
        LOG.info "${parent.name} (parent) is disabled"
        return false
    }

    return true
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

// Tracks repeated events within last 15 seconds
private Integer getCounter(Event evt) {
    counters.values().removeIf { v -> now() > v.expire }
    String key = String.join('|', app.id as String, evt.device.id, evt.name, evt.value)
    Map result = counters.computeIfAbsent(key) { k -> [count: 0, expire: now() + 15000] }
    result.count++
    return result.count
}

// Returns a map with the mode settings
private Map getModeSettings(Long id) {
    Map mode = [
        id: id,
        name: id > 0 ? location.getModes().find { m -> m.id == id }.name : 'Default',
    ]

    mode.enable = settings["mode.${mode.id}.enable"] as Boolean
    if (mode.enable == true) {
        mode.lights = (settings["mode.${mode.id}.lights"] ?: settings['mode.0.lights'])
    }

    return mode
}

// Performs the action on the specified lights
private void performAction(Map mode, String action) {
    List lights = mode.lights ?: []
    switch (mode[action]) {
        case 'dimLevel':
            lights = optimize ? lights.findAll { d -> d.currentValue('level') != mode.inactiveLevel } : lights
            captureLightState(lights)
            setLights(lights, 'setLevel', mode.brightness, mode.transitionTime)
            if (settings.sendOn) { setLights(lights, 'on') }
            break
        case 'off':
            if (mode.inactiveLights) { lights = mode.inactiveLights }
            lights = optimize ? lights.findAll { d -> d.currentValue('switch') !=  'off' } : lights
            captureLightState(lights)
            setLights(lights, 'off')
            break
        case 'on':
            lights = optimize ? lights.findAll { d -> d.currentValue('switch') !=  'on' } : lights
            captureLightState(lights)
            setLights(lights, 'on')
            break
        case 'onColor':
            captureLightState(lights)
            setLights(lights, 'setColor', getColorByRGB(mode.color))
            if (settings.sendOn) { setLights(lights, 'on') }
            break
        case 'onCT':
            lights = optimize ? lights.findAll { d -> d.currentValue('colorTemperature') != mode.activeColorTemperature } : lights
            captureLightState(lights)
            setLights(lights, 'setColorTemperature', mode.temperature)
            if (settings.sendOn) { setLights(lights, 'on') }
            break
        case 'onLevel':
            captureLightState(lights)
            setLights(lights, 'setLevel', mode.brightness, mode.transitionTime)
            if (settings.sendOn) { setLights(lights, 'on') }
            break
        case 'dimDown':
            captureLightState(lights)
            if (settings.sendOn) { setLights(lights, 'on') }
            setLights(lights, 'startLevelChange', 'down')
            break
        case 'dimUp':
            captureLightState(lights)
            if (settings.sendOn) { setLights(lights, 'on') }
            setLights(lights, 'startLevelChange', 'up')
            break
        case 'stop':
            setLights(lights, 'stopLevelChange')
            break
        case 'restore':
            restoreLightState(lights)
            break
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

// Sets the specified lights using the provided action and optional value
private void setLights(List lights, String action, Object[] value) {
    LOG.debug "setLights: ${lights} ${action} ${value ?: ''}"

    lights.each { device ->
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

@Field private final Map LOG = [
    debug: { s -> if (settings.logEnable == true) { log.debug(s) } },
    trace: { s -> if (settings.logEnable == true) { log.trace(s) } },
    info: { s -> log.info(s) },
    warn: { s -> log.warn(s) },
    error: { s -> log.error(s) },
    exception: { message, exception ->
        List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith('user_app') }
        Integer line = relevantEntries[0]?.lineNumber
        String method = relevantEntries[0]?.methodName
        log.error("${message}: ${exception} at line ${line} (${method})")
        if (settings.logEnable) {
            log.debug("App exception stack trace:\n${relevantEntries.join('\n')}")
        }
    }
]
