# Philips Hue Zigbee Drivers

## Overview
This driver is for [Philips Hue](https://www.philips-hue.com/en-us/products/smart-light-bulbs) devices that are directly paired to the Hubitat Hub using Zigbee (i.e. not using the Hue Bridge integration). It works best with the newer generation of Zigbee 3.0 products *(the ones that have Bluetooth capabilities)* but provides fallback polling for older generations.

It has been tested so far with:
- [Philips Hue White and Color Ambiance](https://www.philips-hue.com/en-us/p/hue-white-and-color-ambiance-a21---e26-smart-bulb---60-w/046677562984)
- [Philips Hue White Ambiance (Color Temperature)](https://www.philips-hue.com/en-us/p/hue-white-ambiance-a19---e26-smart-bulb---60-w/046677548490)
- [Philips Hue White (dimming only)](https://www.philips-hue.com/en-us/p/hue-white-a21---e26-smart-bulb---100-w/046677557805)

### Features
* Standard On/Off, Level, Color Temperature and Color control
* "Enhanced Hue" color command (provides expanded 0 - 360 hue range)
* Standard start and stop level change using zigbee commands
* Stepped level/hue change using zigbee commands
* Effects support for the built-in bulb effects (firmware version dependent)
* Reporting (push) updates when bulb state is changed from outside Hubitat
* Uses Hue private cluster decoding to reduce Zigbee reporting traffic (similar to the Hue bridge itself)
* Power restore state setting (dependent on recent bulb firmware)
* Independent configuration of default transition times for dimming up vs. down and color changes *(note that on and off transitions are hardcoded in the bulb and cannot be changed)*
* Support for dimming level pre-staging using preset level command when bulb is off
* Support for color pre-staging using set hue and set saturation commands while the bulb is off *(the set level and set color commands will turn on the bulb)*
* Fallback polling mode to support earlier generation of Hue bulbs *(autodetected but can be forced to use this mode using configuration)*
* Health Check attribute and scheduled device ping to detect device being offline *(option to select check interval up to an hour)* or after if a command gets no response for 10 seconds
* Configuration option to use Dim commands instead when using On/Off in order to force the use of the dim level transition times

## Usage
1. When adding a new Philips Hue bulb to the Hub after installing this driver, it should be automatically selected for you. However, you may need to select the appropriate version (Color, CT and Dimmable) based on the type of bulb you have.
2. You can change an existing device driver (i.e. the Generic RGBW or the Advanced RGBW) to this one. After making the change be sure to save the configuration which will automatically configure the bulb or push the 'configure' button.
