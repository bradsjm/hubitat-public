/**
  *  Eternal Sunshine
  *
  *  MIT License
  *  Copyright 2019 Jonathan Bradshaw (jb@nrgup.net)
  *  includes slope formulas code Copyright 2016 Elfege
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
    name: "Eternal Sunshine",
    namespace: "jbradshaw",
    author: "Jonathan Bradshaw",
    description: "Adjust dimmers using illuminance",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page name: "pageMain"
}

def pageMain() {
    def pageProperties = [
        name:       "pageMain",
        title:      "${app.label}" + (state.paused ? " <font color='red'>(paused)</font>" : ""),
        nextPage:   null,
        install:    true,
        uninstall:  true
    ]

    return dynamicPage(pageProperties) {
        section {
            if (state.paused)
                input "pause", "button", title: "Resume"
            else
                input "pause", "button", title: "Pause"
        }

        section {
            input "dimmers", "capability.switchLevel", title: "Which lights do you want to control?", required: false, multiple: true
        }

        section {
            input "sensor", "capability.illuminanceMeasurement", title: "Which illuminance sensor to use?", required: false, multiple: false, submitOnChange: true

            input "minDimValue", "number", title: "Minimum dimmer value", required: true, defaultValue: 1
            input "maxDimValue", "number", title: "Maximum dimmer value", required: true, defaultValue: 100

            if (sensor) {
                input "idk", "bool", title:"I don't know the maximum illuminance value", submitOnChange: true, defaultValue: false
                if(!idk)
                {
                    input "maxValue", "number", title: "Maximum lux value for illuminance sensor", default: false, required:true, submitOnChange: true, defaultValue:defset
                }
                else 
                {
                    paragraph "Application will learn the maximum illuminance value"
                    state.maxValue = 1000
                }
            }
        }

        section {
            input "restrictedModes", "mode", title:"Disable if mode is", required: false, multiple: true, submitOnChange: true 
        }

        section {
            input "incrementStep", "number", title: "Increment step for dimmer adjustment", required: true, defaultValue: 1
            input "delayTime", "number", title: "Delay (in seconds) between each dimmer step", required: true, defaultValue: 20
            input "logEnable", "bool", title: "Enable logging", value:false, submitOnChange:true
        }

        section {
            label title: "Edit Name for Application", required: false
        }
    }
}

void installed() {
    log.info "Installed with settings: ${settings}"
    initialize()
}

void updated() {
    log.info "updated with settings: ${settings}"
    unsubscribe()
    initialize()
}

void initialize() {
    state.maxValue = 1000
    state.paused = false

    if (!idk)
        state.maxValue = maxValue.toInteger()

    if (logEnable) runIn(1800, logsOff)

    if (sensor && dimmers) {
        illuminanceHandler([name: "illuminance", value: sensor.currentValue("illuminance")])
        subscribe(sensor, "illuminance", illuminanceHandler)
    }
}

void appButtonHandler(btn) {
    switch(btn) {
        case "pause":
            state.paused = !state.paused
    }
}

void illuminanceHandler(evt) {
    if (state.paused || location.mode in restrictedModes)
    {
        if (logEnable) log.debug "Illuminance event ignored due to restrictions"
        return
    }

    int illum = evt.value.toInteger()

    // learn max value if required
    if (idk && illum > state.maxValue)
    {
        state.maxValue = illum
        log.info "New maximum lux value now ${state.maxValue}"
    }
    else 
    {
        state.maxValue = maxValue
    }

    state.targetDimLevel = getDimVal(illum, state.maxValue)
    log.info "Lux now ${illum}, new dimmer target is ${state.targetDimLevel}"

    updateDimmers()
}

private int getDimVal(illum, maxIllum) {
    def xa = 0            // min illuminance
    def ya = maxDimValue  // corresponding dimmer level

    def xb = maxIllum     // max illuminance
    def yb = minDimValue  // corresponding dimmer level

    def slope = (yb - ya) / (xb - xa)
    def b = ya - slope * xa

    def dimVal = slope * illum + b
    return dimVal.toInteger()
}

private void updateDimmers() {
    if (state.paused || location.mode in restrictedModes) return
    
    boolean updated = false
    dimmers?.each {
        if (it.currentValue("switch") == "on" && it.currentValue("level") != state.targetDimLevel) {
            setDimmer(it, state.targetDimLevel)
            updated = true
        }
    }

    if (updated) {
        runIn(delayTime, "updateDimmers")
        if (logEnable) log.debug "Delaying ${delayTime}s for next incremental adjustment"
    } else {
        log.info "All dimmers now at target level ${state.targetDimLevel}"
    }
}

private void setDimmer(def dimmer, int target) {
    int dimLevel = dimmer.currentValue("level")

    if (dimLevel > target)
        dimLevel = Math.max(target, dimLevel - incrementStep)
    else if (dimLevel < target)
        dimLevel = Math.min(target, dimLevel + incrementStep)

    if (logEnable) log.debug "Setting ${dimmer.displayName} to ${dimLevel} (target ${target})"
    dimmer.setLevel(dimLevel)
}

void logsOff() {
    app.updateSetting("enablelogging",[value:"false",type:"bool"])
    log.info "logging disabled!"
}
