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

/*
 * Thanks to Mattias Fornander (@mfornander) for the original application concept
 *
 * Version history:
 *  0.1 - Initial development (alpha)
 *  0.2 - Beta Test
 *
*/

definition(
    name: 'LED Mini-Dashboard Manager',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Turn your switch LEDs into mini-dashboards',
    importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/LedMiniDashboard/LedDashboardManager.groovy',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    singleThreaded: true
)

preferences {
    page name: 'mainPage', title: '', install: true, uninstall: true
}

// Main Application Page
Map mainPage() {
    return dynamicPage(name: 'mainPage') {
        section("<h2 style='color: #1A77C9; font-weight: bold'>${app.label}</h2>") {
            paragraph getDescription()
        }

        section('<b>LED Dashboard Topics:</b>') {
            app(
                name: 'childApp',
                appName: 'LED Dashboard Topic',
                namespace: 'nrgup',
                title: 'Add LED dashboard topic',
                multiple: true
            )
        }

        section {
            label title: '<b>Name this LED Dashboard Manager:</b>', width: 6
        }
    }
}

private String getDescription() {
    return '''\
        <b>This application allows easy linking of Hubitat device, variables and HSM
        states to LEDs of your switches.</b><br>You can link states such as contact
        sensors open/closed, motion sensors active/inactive, locks locked/unlocked
        and more to LEDs of various colors on your switch. States can share an LED
        by using priority levels allowing the same LED to show yellow if a door is
        unlocked, red if locked and blink if the alarm has been tripped.
    '''.stripIndent().replaceAll('\n', ' ')
}
