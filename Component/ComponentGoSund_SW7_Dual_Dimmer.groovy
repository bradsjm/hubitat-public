/*
	GoSund SW7 Dual Dimmer
    2022-04-30 popcornhax
        -GoSund dual dimmer control for use with TuyaOpenCloudAPI by Jonathan Bradshaw (https://github.com/bradsjm/)
*/

import com.hubitat.app.exception.UnknownDeviceTypeException
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper

metadata {
    definition(name: "GoSund SW7 Dual Dimmer", namespace: "tuya", author: "popcornhax", component: true) {
        capability "Refresh"
        command "createChildDevices"
        command "removeChildDevices"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void updated() {
    log.info "Updated..."
    log.warn "description logging is: ${txtEnable == true}"
    if (!childDevices) {
        createChildDevices()
    } 
}

void installed() {
    log.info "Installed..."
    device.updateSetting("txtEnable",[type:"bool",value:true])
    createChildDevices()
    refresh()
}

void parse(List<Map> description) {
    description.each {
        if (it.name in ["switch","level"]) {
            String dimid = it.statusCode[-1]
            if (txtEnable) log.info "${it.descriptionText} - Dimmer $dimid"
            dni = "$device.deviceNetworkId-$dimid"
            ChildDeviceWrapper dw = getChildDevice(dni)
            dw.sendEvent(it)
        }
    }
}

void refresh() {
    parent?.componentRefresh(this.device)
}

void componentOn(DeviceWrapper dw) {
    log.info "ComponentOn Called from Child ${dw}"
    String dimid = dw.deviceNetworkId[-1]
    parent?.tuyaSendDeviceCommandsAsync(this.device.getDataValue('id'), [ 'code': "switch_led_$dimid", 'value': true ])
    dw.sendEvent(name: "switch", value: "on", isStateChange: true)
}

void componentRefresh(DeviceWrapper dw) {
    log.info "ComponentRefresh (NOT IMPLEMENTED) Called from Child ${dw}"
}

void componentOff(DeviceWrapper dw) {
    log.info "ComponentOff Called from Child ${dw}"
    String dimid = dw.deviceNetworkId[-1]
    parent?.tuyaSendDeviceCommandsAsync(this.device.getDataValue('id'), [ 'code': "switch_led_$dimid", 'value': false ])
    dw.sendEvent(name: "switch", value: "off", isStateChange: true)
}

void componentSetLevel(DeviceWrapper dw, BigDecimal level, BigDecimal duration = 0) {

    log.info "ComponentSetLevel Called from Child ${dw}"
    String dimid = dw.deviceNetworkId[-1]
    int value = Math.ceil(parent?.remap((int)level, 0, 100, 10, 1000))
    parent?.tuyaSendDeviceCommandsAsync(this.device.getDataValue('id'), [ 'code': "bright_value_$dimid", 'value': value ])
    dw.sendEvent(name: "level", value: level, isStateChange: true)

}

void componentStartLevelChange(DeviceWrapper dw, String direction) {
    log.info "ComponentStartLevelChange (NOT IMPLEMENTED) Called from Child ${dw.deviceNetworkId}"
}

void componentStopLevelChange(DeviceWrapper dw) {
    log.info "ComponentStopLevelChange (NOT IMPLEMENTED) Called from Child ${dw.deviceNetworkId}"
}

private void createChildDevices() {
    log.debug "Checking/Creating Child Dimmers"


    for (i in 1..2) {
        dni = "$device.deviceNetworkId-$i"
        try {
            addChildDevice('hubitat', 'Generic Component Dimmer', dni,
                [
                    name: "Dimmer Child $i",
                    label: "$device.displayName $i",
                    isComponent: true
                ]
            )
        } catch (UnknownDeviceTypeException e) {
                log.debug "Device creation failed"
                log.debug e
        }
    }

}

def removeChildDevices() {
    log.debug "Deleting children"
    def children = getChildDevices()
    children.each {child->
        deleteChildDevice(child.deviceNetworkId)
    }
}