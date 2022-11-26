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

import com.hubitat.app.DeviceWrapper
import groovy.transform.Field

definition(
    name: 'Inovelli Zigbee Bindings',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Easy binding of Inovelli Blue Zigbee switches to replica Zigbee devices',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    singleThreaded: true
)

@Field static final String controllerEndpoint = '02'

preferences {
    page name: 'mainPage', title: '', install: true, uninstall: true
}

void updated() {
    app.removeSetting('controller')
    app.removeSetting('replicas')
}

Map mainPage() {
    return dynamicPage(name: 'mainPage') {
        section("<h2 style='color: #1A77C9; font-weight: bold'>${app.label}</h2>") {
            paragraph 'Easy binding of Inovelli Blue Zigbee switches to replica Zigbee devices.'
        }

        section {
            input name: 'controller',
                type: 'device.InovelliDimmer2-in-1BlueSeriesVZM31-SN',
                title: 'Select Inovelli Blue controller',
                multiple: false,
                required: true,
                width: 10,
                submitOnChange: true
        }

        if (controller) {
            List validReplicas = getValidReplicas()
            section {
                input name: 'replicas',
                    type: 'capability.configuration',
                    title: 'Select Zigbee replica devices to bind controller to',
                    multiple: true,
                    required: true,
                    width: 10,
                    submitOnChange: true

                    paragraph '<b>Valid Zigbee replicas:</b> ' +
                        (validReplicas ? validReplicas.collect { d -> "${d} (0x${d.endpointId})" }.join(', ') : 'None')
            }

            if (validReplicas) {
                section {
                    paragraph 'Enable desired bindings and click the <b>bind</b> button:'

                    if (controller.hasCapability('Switch')) {
                        String title = bindPower ? 'Bind power to replicas' : 'Do not bind power to replicas'
                        input name: 'bindPower', type: 'bool', title: title, defaultValue: true, width: 2, submitOnChange: true
                    }
                    if (controller.hasCapability('SwitchLevel')) {
                        String title = bindPower ? 'Bind level to replicas' : 'Do not bind level to replicas'
                        input name: 'bindLevel', type: 'bool', title: title, defaultValue: true, width: 2, submitOnChange: true
                    }
                    if (controller.hasCapability('ColorControl')) {
                        String title = bindPower ? 'Bind color control to replicas' : 'Do not bind color control to replicas'
                        input name: 'bindColor', type: 'bool', title: title, defaultValue: true, width: 2, submitOnChange: true
                    }

                    input name: 'bind',
                        title: 'Bind',
                        type: 'button',
                        width: 2

                    input name: 'unbind',
                        title: 'UnBind',
                        type: 'button',
                        width: 2
                }
            }
        }
    }
}

void appButtonHandler(String buttonName) {
    log.debug "appButtonHandler: ${buttonName}"
    bind(buttonName)
}

private void bind(String bindAction) {
    for (DeviceWrapper replica in getValidReplicas()) {
        List<String> cmds = []
        if (bindPower) {
            cmds << "zdo ${bindAction} 0x${controller.deviceNetworkId} 0x${controllerEndpoint} 0x${replica.endpointId} 0x0006 {${controller.zigbeeId}} {${replica.zigbeeId}}"
            cmds << 'delay 200'
        }
        if (bindLevel) {
            cmds << "zdo ${bindAction} 0x${controller.deviceNetworkId} 0x${controllerEndpoint} 0x${replica.endpointId} 0x0008 {${controller.zigbeeId}} {${replica.zigbeeId}}"
            cmds << 'delay 200'
        }
        if (bindColor) {
            cmds << "zdo ${bindAction} 0x${controller.deviceNetworkId} 0x${controllerEndpoint} 0x${replica.endpointId} 0x0300 {${controller.zigbeeId}} {${replica.zigbeeId}}"
            cmds << 'delay 200'
        }

        log.debug "Zigbee ${bindAction} commands: ${cmds}"
        controller.bind(cmds)
        pauseExecution(1000)
        controller.refresh()
    }
}

private List<DeviceWrapper> getValidReplicas() {
    return replicas.findAll { d -> d.id != controller.id && d.endpointId }
}
