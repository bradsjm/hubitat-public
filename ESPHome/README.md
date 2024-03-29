# ESPHome Hubitat Toolkit

## 1. Overview
[ESPHome](https://esphome.io/) is a system to control your ESP8266/ESP32 by simple yet powerful configuration files and control them remotely through Home Automation systems.

It was designed to integrate with [Home Assistant](https://www.home-assistant.io/) but the project and protocols are open source. It supports both [MQTT](https://mqtt.org/) and a native TCP streaming API based on Google's [Protocol Buffers](https://developers.google.com/protocol-buffers).

This "toolkit" provides Hubitat support for ESPHome's native API with a [communications library](ESPHome-API-Library.groovy) to be included in your driver and some example drivers that can be customized to the particular device capabilities.

The intent of the solution is to make it quick and easy to integrate your ESPHome projects into Hubitat with minimal lines of driver code. There are too many types of projects that can be created with ESPHome to be able to provide out of the box drivers for everything.

## Limitations
- Hubitat does not support MDNS so until it does you'll need to hardcode the device IP address (or use DNS)
- Transport encryption of the API layer is not supported at this time. When configuring [API](https://esphome.io/components/api.html) support make sure the "encryption" key is not specified
- While the majority of the API is supported, some capabilities are still "TODO"

## 2. Preparation
1. Make sure the ESPHome yaml for the device includes the ["api:" section](https://esphome.io/components/api.html) support and does NOT enable encryption.
2. Download the communications [library](ESPHome-API-Library.groovy) and install it in the Hubitat library section (do not try to add it to Drivers or Apps).
3. Download an example driver from the [repository](https://github.com/bradsjm/hubitat-drivers/tree/main/ESPHome) and install it in the drivers section.
4. Create a new virtual device and choose the driver you just installed for it.
5. Open the new device and set the IP address of the ESPHome device and the password if applicable. Make sure the device is NOT using encryption.
6. Save the preferences and the driver will connect and attempt to download the entities and will default to the first one it finds.
7. If the wrong entity is in the preferences, use the dropdown to pick the correct one and save the changes again.
8. If the driver doesn't do exactly what you need, you can edit the code functionality to suit your device.

## 3. Using the library in your driver

### Simple Sensors Example
For ESPHome sensors, a good place to start is with the [Contact Sensor](ESPHome-ContactSensor.groovy) code. You will need to implement the following:
1. Add the `networkStatus` attribute to the driver to track the connection state
2. Create input preferences for `ipAddress` and `password`. These will automagically be used when connecting
3. Add the library to your driver by adding the line: `#include esphome.espHomeApiHelper`
4. In your `initialize` method, call `openSocket()` (The library will automatically keep the connection alive)
5. In your `uninstalled` method, call `closeSocket('driver uninstalled')` _(you can change the reason text)_
6. Create a `parse(Map message)` method, this is where all the ESPHome events will be received by your driver:
```
void parse(Map message) {
  switch (message.type) {
    case 'device':
      // ESPHome Device information message
      break

    case 'entity':
      // Each entity detail message
      // Can be refreshed by calling espHomeListEntitiesRequest()
      break

    case 'state':
      // Entity state changes
      // You should use this for sendEvent() calls
      break
  }
}
```

## 4. Updates
I am accepting pull requests for additional example drivers or updates to the communication library.
