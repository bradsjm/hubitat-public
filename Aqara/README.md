# Aqara FP1 Zigbee Human Presence Detector Sensor (RTCZCGQ11LM)

## Introduction
The Aqara FP1 is a [presence detector](https://www.aqara.com/cn/Aqara-Presence-Detector-FP1_overview) that incorporates a millimeter-wave radar sensor for motion, space, distance, and direction detection. With a wide 120° horizontal angle and 5-meter radial detection distance, the sensor is capable of detecting people whether they are in motion or still, as well as locating them to the left or right of the device and detecting their proximity.

This makes it particularly suitable for use in living rooms or other locations where occupants may be present but not moving enough to activate traditional passive infrared (PIR) sensors.

## Zigbee Channel
This sensor reportedly [does not work](https://github.com/Koenkk/zigbee2mqtt/issues/11019#issuecomment-1064063808) on Zigbee channel 21-24.

## Pairing
Press and hold the reset button on the device for +- 5 seconds (until the blue light starts blinking).
After this the device will automatically join. If this doesn't work, try with a single short button press.

## Driver Configuration
The driver provides the following attributes:
* Motion Sensor (motion)
* Presence Sensor (presence)
* activity (custom attribute)
* region1 through region10 (custom attributes)
* healthStatus (custom attribute)

### Motion Attribute
The motion attribute becomes 'active' when the activity becomes enter and 'inactive' when the activity becomes leave. This will provide the fastest response similar to a traditional PIR and can be used for automation of lighting.

### Presence Attribute
The presence attribute is tied to the FP1 presence data and is slower than the motion attribute. It will trigger once the FP1 has determined presence (generally takes about 5 seconds after detection) and should become not present after leaving (around 30 seconds)

### Activity Attribute Values
One of the following:
* enter
* leave
* enter (left)
* enter (right)
* leave (left)
* leave (right)
* towards (i.e. movement towards sensor)
* away (i.e. movement away from sensor)

Left and right values are only provided if they are enabled in the driver configuration.

### Region Attributes
If regions are defined in the configuration then an attribute of 'region1' through 'region10' will be available. Each region attribute can have one of the following values:
* enter
* occupied
* leave
* unoccupied

The initial state will become enter or leave and then *should* be followed by presence indication of 'occupied' or 'unoccupied' however this does not always appear to happen.

When multipe regions are defined, it is possible to configure the regions to overlap and have multiple regions showing occupied.

To reduce the impact of rapid region updates on Hubitat, changes are buffered. When there have been no changes for half a second the region attributes will be updated to their latest states.

### Approach Distance
The approach distance setting allows you to define how far away the sensor is. This helps the FP1 to determine if someone is moving towards the sensor or away from it. One person posted they thought the setting impacted the region grid as follows *(this has not been confirmed)*:
- Detection set to "Far" = Y-grid is around ~80cm (2.6feet)
- Detection set to "Medium" = Y-grid is around ~60cm (2feet)
- Detection set to "Near" = Y-grid is around ~35cm (1.15feet)

### Motion Sensitivity
The motion sensitivity can be adjusted to reduce false positives (e.g. animals, fans etc.) by reducing it to Low or Medium. However, if it is set too low it may not detect the continued presence of people sitting very still. At the highest level it should be sensitive to people breathing.

### Detection Regions
The FP1 supports up to 10 regions and the driver provides a customized entry grid to define each of them. Each grid is 7 x 4. The 7 rows represent the distance away from the device (top is closest). The columns represent location from left to right of the device in the middle.

To define a region simply click in each square of the grid to select it and it will turn blue. Click it again to deselect. You may have overlapping regions if desired.

### Interference Region
The special interference region allows you to define grid squares that the FP1 should consider as having interference or false positives. Finding the right squares to block out is a matter of trial and error.

### Entrances and Exits Region
To be documented.

### Edges Region
To be documented.

### Presence Watchdog
If the watchdog is enabled, it will send a reset to the FP1 if presence exceeds the selected duration. This can help with situations where interference causes the FP1 to get stuck in the present state. There is also a reset presence command available for use in rules or for manual resets.
