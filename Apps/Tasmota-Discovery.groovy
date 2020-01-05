/**
 *  Tasmota Device Discovery App for Hubitat
 *  https://github.com/arendst/Tasmota
 *
 *  MIT License
 *  Copyright 2019 Jonathan Bradshaw (jb@nrgup.net)
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
    name: "Tasmota Device Discovery",
    namespace: "tasmota",
    author: "Jonathan Bradshaw",
    description: "Discover Tasmota devices on network",
    category: "Discovery",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

import groovy.transform.Field
@Field static java.util.concurrent.atomic.AtomicInteger discoveryCount = new java.util.concurrent.atomic.AtomicInteger()
@Field static java.util.concurrent.ConcurrentLinkedQueue discoveryQueue = new java.util.concurrent.ConcurrentLinkedQueue()
@Field static java.util.concurrent.ConcurrentHashMap foundDeviceMap = new java.util.concurrent.ConcurrentHashMap()

preferences {
    page(name: "mainPage")
    page(name: "discoveryPage")
}

void installed() {
}

void updated() {
}

void initialize() {
    discoveryCount.set(0)
    discoveryQueue.clear()
    foundDeviceMap.clear()
}

def uninstalled() {
	log.debug "uninstalling app"
	// for (device in getChildDevices())
	// {
	// 	deleteChildDevice(device.deviceNetworkId)
	// }
}

private void appButtonHandler(btn) {
    if (btn == "btnDiscover") {
        scanSubnets()
    }
}

private void scanSubnets() {
    log.info "Starting subnet scan ..."
    def subnets = getSubnets()

    // Populate queue with IPs for each subnet
    subnets.each { subnet ->
        0.upto(254) {
            discoveryQueue.add(subnet + it)
        }
    }

    // Record total number of IPs to be probed
    discoveryCount.set(discoveryQueue.size())

    // Probe each IP address
    while (ip = discoveryQueue.poll()) {
        def params = [
            uri: "http://${ip}",
            path: "/cm",
            query: [
                cmnd: "Status 0"
            ],
            timeout: 1
        ]
        def data = [
            ip: ip
        ]

        log.info "Probing ${params.uri} ..."
        asynchttpGet("processCallBack", params, data)
        pauseExecution(1000)
    }
}

private void processCallBack(response, data) {
    if (response.status == 200 && !response.hasError()) {
        def content = response.getData()
        if (content.startsWith('{"Status":')) {
        log.debug content
            log.info "Tasmota Device Found at ${data.ip} ..."
            foundDeviceMap.put(data.ip, response.json)
            createDevice(response.json)
        }
    }
}

private void createDevice(json) {
    def status = json.Status
    def statusNet = json.StatusNET
    def statusPrm = json.StatusPRM
    def statusMqt = json.StatusMQT
    def child = getChildDevice(statusNet.Mac.toLowerCase())
    if (!child) {
        log.info "Creating Tasmota device ${status.FriendlyName[0]} with topic ${status.Topic}"
        try {
            child = addChildDevice(
                    "tasmota",
                    "Tasmota RGBW Driver",
                    statusNet.Mac.toLowerCase(),
                    null,
                    [
                        name: status.Topic,
                        label: status.FriendlyName[0]
                    ]
            )
        } catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
            log.warn "${e.message} - you need to install the appropriate driver"
        }
    }

    child.updateSetting("deviceTopic", status.Topic)
    child.updateSetting("groupTopic", statusPrm.GroupTopic)
    child.updateSetting("mqttBroker", "tcp://${statusMqt.MqttHost}:${statusMqt.MqttPort}")
}

def getProgressPercentage()
{
    def total = discoveryCount.get()
    def completed = total - discoveryQueue.size()
    if (total) {
        def completedPercent = Math.round(completed / total * 100f)
        return "${completedPercent}%"
    }
    return "0%"
}

String[] getSubnets() {
    def ipSegment = settings.baseIpSegment
    def baseIps = ipSegment == null ? null : ipSegment.split(/,/)?.collect { return parseIPSegment(it) }
    if (baseIps) {
        return baseIps
    }

    def ip = location.hubs[0].localIP
    def m = ip =~ /^(\d{1,3}\.\d{1,3}\.\d{1,3}\.)\d{1,3}/
    if (!m) {
        log.warn "${ip} does not match pattern"
        return null
    }

    return [ m.group(1) ]
}

private String parseIPSegment(String ipSegment) {
    def m = ipSegment =~ /^(\d{1,3}\.\d{1,3}\.\d{1,3})/
    if (!m) {
        logDebug "null segment"
        return null
    }
    def segment = m.group(1)
    (segment.endsWith(".")) ? segment : segment + "."
}

/**
 * Pages
 */
def mainPage() {
    initialize()

    dynamicPage(name: "mainPage", title: "Tasmota Discovery Options", nextPage: "discoveryPage", uninstall: true) {
        section {
            input "namePrefix", "text", title: "Device name prefix", description: "If you specify a prefix then all your device names will be preceded by this value", submitOnChange: false
            input "baseIpSegment", "text", title: "IP subnet(s)", description: "e.g. 192.168.0 or 192.168.1, separate multiple subnets with commas", submitOnChange: false
            input "savePreferences", "button", title: "Save", submitOnChange: true
        }
    }
}

def discoveryPage() {
    dynamicPage(name: "discoveryPage", title: "Tasmota Device Discovery", refreshInterval: 5) {
        section {
            input "btnDiscover", "button", title: "Start Discovery"
            paragraph(
                "<div class='meter'>" +
                "<span style='width:${getProgressPercentage()}'><strong>${getProgressPercentage()}</strong></span>" +
                "</div>"
            )
            paragraph(
                getFoundDevices()
            )
        }
        section {
            paragraph(styles())
        }
    }
}

private String getFoundDevices() {
    def count = foundDeviceMap.size()
    if (!count) {
        return "No Tasmota devices found ..."
    }

    def text = "Found <strong>${count}</strong> Tasmota device(s):<br>"
    text <<= '<ul class="device">'

    for (entry in foundDeviceMap.entrySet()) {
        def status = entry.value.Status
        def statusNet = entry.value.StatusNET
        def statusPrm = entry.value.StatusPRM
        text <<= "<li class='device'>"
        text <<= "${statusNet.IPAddress}: ${status.FriendlyName[0]} (Topic: ${status.Topic} Group: ${statusPrm.GroupTopic})"
        text <<= "</li>"
    }
    text <<= "</ul>"

    return text
}

/**
 * Styles for pages
 */
private String styles() {
    $/<style>
    /* Progress bar - modified from https://css-tricks.com/examples/ProgressBars/ */
    .meter {
        height: 20px;  /* Can be anything */
        position: relative;
        background: #D9ECB1;
        -moz-border-radius: 5px;
        -webkit-border-radius: 5px;
        border-radius: 5px;
        padding: 0px;
    }

    .meter > span {
          display: block;
          height: 100%;
          border-top-right-radius: 2px;
          border-bottom-right-radius: 2px;
          border-top-left-radius: 5px;
          border-bottom-left-radius: 5px;
          background-color: #81BC00;
          position: relative;
          overflow: hidden;
          text-align: center;
    }
</style>/$
}
