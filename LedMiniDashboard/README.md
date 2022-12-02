# LED Mini-Dashboard

This application allows easy linking of Hubitat device, variables and HSM states to LEDs of your [Inovelli Blue Series Zigbee switches](https://inovelli.com/collections/inovelli-blue-series).

>Special thanks to Mattias Fornander (@mfornander). This driver was inspired by his original implementation *(but works only with the Inovelli switches)*. 

### Overview
You can link states such as contact sensors open/closed, motion sensors active/inactive, locks locked/unlocked and more to LEDs of various colors on your switch with various effects and for specified durations.

Dashboards can share an LED by using priority levels allowing, for example, the same LED to show yellow if a door is unlocked, red if locked and blink if the alarm has been tripped.

### Application Details
- Each *LED Mini-Dashboard Manager* app may have one or more LED dashboards
- You can create one or more *LED Mini-Dashboard Manager* apps to organize your dashboards
- Using the same switches across multiple dashboards and managers is supported
- Each dashboard may be *paused and resumed* using the button next to the name field
- Each dashboard may have any number of **conditions** defined with each condition being assigned a priority from 1 - 9
- Conditions with a higher priority number (e.g. 9) are prioritized over a conditions with lower numbers (e.g. 1)
- Each condition has the following settings:
    1. LED Number: Select a specific LED, 'All' or use a [Hub variable](https://docs2.hubitat.com/en/user-interface/settings/hub-variables)
    2. LED Effect: Select a specific effect or use a Hub variable
    3. LED Color: Select a specific color or use a Hub variable
    4. Duration: Use *Indefinitely* to leave the effect running or select the time unit (Hours, Minutes or Seconds) and enter the numeric value
    5. Level: Set the brightness level for the LED effect
- One of more condition rules can be defined using the select dropdown:
    1. When using multiple condition rules, you can select to match on *Any* or *All*
    2. When selecting multiple devices you can also select to match on *Any* or *All* devices
    3. You can select a *Hub variable* and a matching value to trigger on
    4. You can also trigger based on [Hubitat Safety Monitor](https://docs2.hubitat.com/en/apps/hubitat-safety-monitor) events (armed/intrusion etc.)

### Switch LED Activation
- The application will subscribe to all the devices, variables and HSM as needed when the **Done** button is selected 
- When an event is received all the conditions will be evaluated for changes and any conflicts resolved based on priority *(if a conflict happens between the same priority level the result is undefined)*
- Conditions using *All LEDs* will take priority over those using individual LEDs
- The application tracks each LED on each switch *(by subscribing to the switch ledEffect events)* and attempts to send only changes in order to minimize the Zigbee traffic