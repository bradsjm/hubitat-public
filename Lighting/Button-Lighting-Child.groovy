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
    name: 'Button Lighting Controller (Child App)',
    namespace: 'nrgup',
    parent: 'nrgup:Button Lighting Controller',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Use main application parent',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

// Application Pages
preferences {
    page name: 'mainPage', install: true, uninstall: true
    page name: 'configureMode'
    page name: 'configureButtons'
}

// Available actions for button presses
@Field static final Map<String,String> ButtonActions = [
   none:     'Ignore button',
   on:       'Turn on',
   onLevel:  'Turn on and set brightness',
   onCT:     'Turn on and set color temperature',
   onColor:  'Turn on and set light color',
   dimDown:  'Start dimming down',
   dimUp:    'Start dimming up',
   stop:     'Stop dimming',
   off:      'Turn off',
   restore:  'Restore to previous state'
].asImmutable()

// Supported types of button events
@Field static final Map<String,Map> EventMap = [
   'pushed':       [ capability: 'PushableButton',      description: 'Push',       maxPresses: 5 ],
   'held':         [ capability: 'HoldableButton',      description: 'Hold',       maxPresses: 1 ],
   'released':     [ capability: 'ReleasableButton',    description: 'Release',    maxPresses: 1 ],
   'doubleTapped': [ capability: 'DoubleTapableButton', description: 'Double Tap', maxPresses: 5 ]
].asImmutable()

// Convenience object to map number to ordinal string
@Field static final Map<Integer, String> Ordinals = [
    1: 'First',
    2: 'Second',
    3: 'Third',
    4: 'Fourth',
    5: 'Fifth'
].asImmutable()

// Tracking for the number of presses of each button
@Field static final Map<String, Map> Counters = new ConcurrentHashMap<>()

// Number of seconds from first press the button counter resets to zero
@Field static final Integer PushTimeoutSeconds = 15

/*
 * Configuration UI
 */
Map mainPage() {
    atomicState.clear()

    return dynamicPage(name: 'mainPage', title: 'Button Lighting Controller') {
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
                paragraph '<b>Controller disabled by the parent application setting</b>'
            }

            paragraph 'This application will bind one or more button controllers to ' +
                      'a set of lighting devices with configurable behaviour.'

            input name: 'buttonDevices',
                  title: 'Select button controller devices',
                  type: 'capability.pushableButton',
                  multiple: true,
                  required: true,
                  submitOnChange: true,
                  width: 12

            paragraph 'Configure the individual button behaviour modes:'

            Long activeId = getActiveMode().id
            String modeDescription = getModeDescription()
            href page: 'configureMode',
                 title: 'Default Button Mappings' + (activeId == 0 && settings["mode.${activeId}.enable"] == true ? ' <span style=\'font-weight: bold\'>(Active)</span>' : ''),
                 params: [ modeId: 0 ],
                 description: modeDescription ?: 'Click to configure button mappings',
                 state: activeId == 0 ? 'complete' : null,
                 width: 12

            Map<Long, String> enabledModes = getEnabledModes()
            enabledModes.each { Long id, String name ->
                modeDescription = getModeDescription(id)
                href page: 'configureMode',
                     title: "${name} Mode Button Mappings" + (activeId == id && settings["mode.${activeId}.enable"] ? ' <span style=\'font-weight: bold\'>(Active)</span>' : ''),
                     params: [ modeId: id ],
                     description: modeDescription ?: "Click to override default button mappings in ${name} mode",
                     state: activeId == id ? 'complete' : null,
                     width: 12
            }

            Map<Long, String> availableModes = getAvailableModes()
            app.removeSetting('newMode')
            if (availableModes) {
                input name: 'newMode',
                      title: 'Select mode name to create a mode specific configuration',
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
                  title: 'Send \"On\" command after \"Set Level\" or \"Set Color\"',
                  type: 'bool',
                  required: false,
                  defaultValue: true

            input name: 'logEnable',
                  title: 'Enable Debug logging',
                  type: 'bool',
                  required: false,
                  defaultValue: false
        }
    }
}

// Mode configuration page
Map configureMode(Map params) {
    Map mode = getMode(params ? params.modeId : atomicState.modeId)
    atomicState.modeId = mode.id

    return dynamicPage(name: 'configureMode', title: "${mode.name} Mode Actions") {
        section {
            input name: "mode.${mode.id}.enable",
                    title: "Enable ${mode.name} mode actions",
                    type: 'bool',
                    defaultValue: true,
                    submitOnChange: false

            String buttonDevices = getDeviceNames(settings.buttonDevices)
            paragraph "<b>Button Controller${settings.buttonDevices.size() > 1 ? 's' : ''}:</b> ${buttonDevices}"

            int buttonCount = getButtonCount()
            Map capabilities = getButtonCapabilities()
            for (int button in (1..buttonCount)) {
                capabilities.each { String event, Map capability ->
                    String desc = capability.maxPresses > 1 ? 'Actions' : 'Action'
                    String config = getButtonDescription(mode, button, event)
                    href page: 'configureButtons',
                            title: "Button ${button} <b>${capability.description}</b> ${desc}",
                            params: [ button: button, event: event ],
                            description: config ?: 'Click to configure button actions',
                            state: config ? 'complete' : null,
                            width: 12
                }
            }
        }
    }
}

// Button Configuration Page
Map configureButtons(Map params) {
    Map mode = getMode(atomicState.modeId)
    Integer button = params ? params.button : atomicState.button
    String event = params ? params.event : atomicState.event
    atomicState.button = button
    atomicState.event = event

    Map capability = EventMap[event]
    return dynamicPage(name: 'configureButtons', title: "${mode.name} Mode Button ${button} ${capability.description} Settings") {
        section {
            int maxPresses = capability.maxPresses ?: 1
            for (int count in (1..maxPresses)) {
                String name = "mode.${mode.id}.button.${button}.${event}.${count}"
                String ordinal = maxPresses > 1 ? Ordinals[count] + ' ' : ''
                String buttonAction = settings[name] ?: 'none'

                input title: "On Button ${button} ${ordinal}${capability.description}:",
                      name: name,
                      type: 'enum',
                      options: ButtonActions,
                      defaultValue: ButtonActions['none'],
                      submitOnChange: true

                if (buttonAction != 'none') {
                    String description = ButtonActions[buttonAction].toLowerCase()
                    input name: "${name}.lights",
                          title: "Select lights to ${description}",
                          description: mode.id == 0 ? 'Click to set lights' : 'Click to override default light selection',
                          type: 'capability.switchLevel',
                          multiple: true,
                          required: mode.id == 0,
                          submitOnChange: false
                } else {
                    app.removeSetting("${name}.lights")
                }

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
                        app.removeSetting("${name}.color")
                        app.removeSetting("${name}.temperature")
                        break
                    case 'onCT':
                        input name: "${name}.temperature",
                            title: 'Set color temperature (K)',
                            type: 'number',
                            range: '2000..6000',
                            width: 5,
                            required: true,
                            submitOnChange: true
                        app.removeSetting("${name}.brightness")
                        app.removeSetting("${name}.transitionTime")
                        app.removeSetting("${name}.color")
                        break
                    case 'onColor':
                        input name: "${name}.color",
                            title: 'Select light color',
                            type: 'enum',
                            options: colors.collect { c -> [ (c.rgb):c.name ] },
                            width: 5,
                            required: true,
                            submitOnChange: true
                        app.removeSetting("${name}.brightness")
                        app.removeSetting("${name}.transitionTime")
                        app.removeSetting("${name}.temperature")
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

/*
 * Application Logic
 */

// Called when a button is pressed on the settings page
void buttonHandler(Event evt) {
    Map mode = getActiveMode()
    if (checkEnabled(mode)) {
        String event = evt.name
        int button = evt.value as int
        int maxPresses = EventMap[event]?.maxPresses ?: 1
        int count = Math.min(maxPresses, getCounter(evt))
        LOG.trace "buttonHandler: ${evt.device} ${evt.name} ${evt.value} presses ${count} of ${maxPresses}"
        Map result = getButtonAction(mode.id, button, event, count)
        performAction(result)
    }
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

void logsOff() {
    log.warn 'debug logging disabled'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

// Called when the app is removed.
void uninstalled() {
    LOG.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    LOG.info "${app.name} configuration updated"
    initialize()
    if (settings.logEnable) {
        log.debug settings
        runIn(1800, logsOff)
    }
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
    Map mode = getMode(location.currentMode.id)
    return mode.enable ? mode : getMode(0)
}

// Returns list of modes available to be configured
private Map<Long, String> getAvailableModes() {
    return location.getModes()
                   .findAll { m ->  m.id != settings.newMode as Long && settings["mode.${m.id}.enable"] as Boolean != true }
                   .collectEntries { m -> [(m.id): m.name] }
}

private Map getButtonAction(Long modeId, Integer button, String event, Integer count) {
    String defaultKey = "mode.0.button.${button}.${event}.1"
    String activeKey = "mode.${modeId}.button.${button}.${event}."
    activeKey += (count..1).find { c -> (settings[activeKey + (String)c] ?: 'none') != 'none' }

    return [
        name: settings[activeKey],
        lights: settings[activeKey + '.lights'] ?: settings[defaultKey + '.lights'],
        brightness: settings[activeKey + '.brightness'] ?: settings[defaultKey + '.brightness'],
        transitionTime: settings[activeKey + '.transitionTime'] ?: settings[defaultKey + '.transitionTime'],
        temperature: settings[activeKey + '.temperature'] ?: settings[defaultKey + '.temperature'],
        color: settings[activeKey + '.color'] ?: settings[defaultKey + '.color']
    ]
}

// Returns the combined button capabiltiies
private Map getButtonCapabilities() {
    Set allCapabilities = settings.buttonDevices*.getCapabilities()?.name?.flatten() ?: []
    return EventMap.findAll { e -> e.value.capability in allCapabilities }
}

// Returns the combined largest number of buttons
private Integer getButtonCount() {
    return settings.buttonDevices.collect { d -> d.currentValue('numberOfButtons') as Integer }?.max() ?: 1
}

// Returns description of button configuration
private String getButtonDescription(Map mode, Integer button, String event) {
    Map capability = EventMap[event]
    StringBuilder sb = new StringBuilder()

    for (int count in (1..capability.maxPresses)) {
        String name = "mode.${mode.id}.button.${button}.${event}.${count}"
        if (settings[name] && settings[name] != 'none') {
            Map buttonAction = getButtonAction(mode.id, button, event, count)
            String lights = getDeviceNames(buttonAction.lights)
            String ordinal = capability.maxPresses > 1 ? Ordinals[count] + ' ' : ''
            sb << "<strong>${ordinal}${capability.description}:</strong> "
            switch (settings[name]) {
                case 'onLevel':
                    sb << 'Set ' << lights << ' level to ' + settings[name + '.brightness'] + '%'
                    break
                case 'onCT':
                    sb << 'Set ' << lights << ' color temperature to ' + settings[name + '.temperature'] + 'K'
                    break
                case 'onColor':
                    sb << 'Set ' << lights << ' color to ' + settings[name + '.color']
                    break
                default:
                    sb << ButtonActions[settings[name]] << ' ' << lights
            }
            sb << '<br>'
        }
    }

    return sb.toString()
}

// Return color map from RGB
private Map getColorByRGB(String rgb) {
    (hue, saturation, level) = ColorUtils.rgbToHSV(ColorUtils.hexToRGB(rgb))
    return [ hue: hue, saturation: saturation, level: level ]
}

// Tracks repeated events within last 'PushTimeoutSeconds' seconds
private Integer getCounter(Event evt) {
    Counters.values().removeIf { v -> now() > v.expire }
    long expire = now() + (PushTimeoutSeconds * 1000)
    String key = String.join('|', app.id as String, evt.device.id, evt.name, evt.value)
    Map<String, Map> result = Counters.computeIfAbsent(key) { k -> [ count: 0, expire: expire ] }
    result.count++
    return result.count
}

// Gets human readable list of device names up to 10 devices
private String getDeviceNames(List devices) {
    if (!devices) { return '' }
    int count = devices.size()
    if (count <= 10) {
        String description = ''
        devices.eachWithIndex { element, index ->
            if (index > 0 && index < count - 1) { description += ', ' }
            else if (index > 0 && index == count - 1) { description += ' and ' }
            description += element
        }
        return description
    }

    return "${devices.size()} devices"
}

// Returns list of modes that have been configured
private Map<Long, String> getEnabledModes() {
    return location.getModes()
                   .findAll { m -> m.id == settings.newMode as Long || settings["mode.${m.id}.enable"] as Boolean == true }
                   .collectEntries { m -> [(m.id): m.name] }
}

// Returns a map with the mode settings
private Map getMode(Long id) {
    return [
        id: id,
        name: location.getModes().find { m -> m.id == id }?.name ?: 'Default',
        enable: settings["mode.${id}.enable"] as Boolean
    ]
}

// Returns String summary of per-mode settings, or empty string if that mode is not configured
private String getModeDescription(Long modeID = 0) {
    Map mode = getMode(modeID)
    if (!mode.enable) { return '' }

    StringBuilder sb = new StringBuilder()
    int buttonCount = getButtonCount()
    Map capabilities = getButtonCapabilities()
    for (int button in (1..buttonCount)) {
        sb << "<br>Button ${button} Actions:<br>"
        capabilities.keySet().each { String event ->
            String desc = getButtonDescription(mode, button, event)
            if (desc) {
                sb << '<div style="margin-left: 20px;">' << desc << '</div>'
            }
        }
    }

    return sb.toString()
}

// Performs the action on the specified lights
private void performAction(Map action) {
    switch (action.name) {
        case 'dimLevel':
            List devices = optimize ? action.lights.findAll { d -> d.currentValue('level') != action.brightness } : action.lights
            captureLightState(devices)
            setLights(devices, 'setLevel', action.brightness, action.transitionTime)
            if (settings.sendOn) { setLights(devices, 'on') }
            break
        case 'off':
            List devices = optimize ? action.lights.findAll { d -> d.currentValue('switch') !=  'off' } : action.lights
            captureLightState(devices)
            setLights(devices, 'off')
            break
        case 'on':
            List devices = optimize ? lights.findAll { d -> d.currentValue('switch') !=  'on' } : action.lights
            captureLightState(devices)
            setLights(devices, 'on')
            break
        case 'onColor':
            captureLightState(action.lights)
            setLights(lights, 'setColor', getColorByRGB(action.color))
            if (settings.sendOn) { setLights(action.lights, 'on') }
            break
        case 'onCT':
            List devices = optimize ? action.lights.findAll { d -> d.currentValue('colorTemperature') != action.temperature } : action.lights
            captureLightState(devices)
            setLights(devices, 'setColorTemperature', action.temperature)
            if (settings.sendOn) { setLights(devices, 'on') }
            break
        case 'onLevel':
            captureLightState(action.lights)
            setLights(action.lights, 'setLevel', action.brightness, action.transitionTime)
            if (settings.sendOn) { setLights(action.lights, 'on') }
            break
        case 'dimDown':
            captureLightState(action.lights)
            if (settings.sendOn) { setLights(action.lights, 'on') }
            setLights(action.lights, 'startLevelChange', 'down')
            break
        case 'dimUp':
            captureLightState(action.lights)
            if (settings.sendOn) { setLights(action.lights, 'on') }
            setLights(action.lights, 'startLevelChange', 'up')
            break
        case 'stop':
            setLights(action.lights, 'stopLevelChange')
            break
        case 'restore':
            restoreLightState(action.lights)
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
    sendEvent(name: 'action', value: action, descriptionText: getDeviceNames(lights))

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
].asImmutable()
