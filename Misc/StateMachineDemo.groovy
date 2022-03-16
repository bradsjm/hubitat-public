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
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO event SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
*/
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

definition(
    name: 'State Machine Demo',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: '',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page name: 'pageMain'
}

@Field static final Map<String,String> ActiveActions = [
    on: 'Turn on lights',
    onLevel: 'Turn on and set brightness',
    onCT: 'Turn on and set color temperature',
    onColor: 'Turn on and set light color',
    none: 'No action (do not turn on)'
].asImmutable()

@Field static final Map<String,String> InactiveActions = [
    off: 'Turn off lights',
    on: 'Turn on lights',
    dimLevel: 'Dim light level',
    restore: 'Restore previous state',
    none: 'No action (do not turn off)'
].asImmutable()

/*
 * Configuration Pages
 */
Map pageMain() {
    return dynamicPage(name: 'pageMain', title: 'Demo',
                      install: true, uninstall: true, hideWhenEmpty: true) {
        section {
            input name: 'switches',
                  title: 'Demo switches',
                  type: 'capability.switch',
                  multiple: true

            input name: 'motions',
                  title: 'Demo motion sensors',
                  type: 'capability.motionSensor',
                  multiple: true

            input name: 'contacts',
                  title: 'Demo contact sensors',
                  type: 'capability.contactSensor',
                  multiple: true

            input name: 'logEnable',
                  title: 'Enable Debug logging',
                  type: 'enum',
                  options: [
                      none: 'Off',
                      trace: 'Trace logging',
                      debug: 'Debug logging'
                  ],
                  required: false,
                  width: 4,
                  defaultValue: none
        }

        section('GraphViz', hideable: true, hidden: true) {
            paragraph '<textarea readonly rows=20 cols=55>' + visualizeStateMachine() + '</textarea>'
        }
    }
}

// Called when the app is removed.
void uninstalled() {
    LOG.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    LOG.info "${app.name} configuration updated"
    LOG.debug settings
    unsubscribe()
    unschedule()
    subscribe(settings.switches, 'switch', eventHandler)
    subscribe(settings.motions, 'motion', eventHandler)
    subscribe(settings.contacts, 'contact', eventHandler)

    List<DeviceWrapper> switches = settings.switches
    switches.each { dw ->
        getSubject(dw.id).with {
            name = dw.toString()
            device = dw
        }
        setSubjectState(dw.id, 'light-' + dw.currentValue('switch'))
    }

    settings.motions.each { dw ->
        getSubject(dw.id).with {
            name = dw.toString()
            targets = switches*.id
        }
        setSubjectState(dw.id, 'sensor-' + dw.currentValue('motion'))
    }

    settings.contacts.each { dw ->
        getSubject(dw.id).with {
            name = dw.toString()
            targets = switches*.id
        }
        setSubjectState(dw.id, dw.currentValue('contact') == 'open' ? 'sensor-active' : 'sensor-inactive')
    }
}

void eventHandler(Event evt) {
    transitionSubject(evt.device.id, "${evt.name}-${evt.value}")
}

/*-----------------------------------------------
 *  Finite State Machine Implementation
 *-----------------------------------------------
 */
@Field static final Map<String, Map> FiniteStateMachines = [
    'default': [
        'sensor-inactive': [
            notify: { targets },
            transitions: [
                [ event: 'motion-active', to: 'sensor-active' ],
                [ event: 'contact-open', to: 'sensor-active' ],
            ]
        ],
        'sensor-active': [
            notify: { targets },
            transitions: [
                [ event: 'motion-inactive', to: 'sensor-inactive' ],
                [ event: 'contact-closed', to: 'sensor-inactive' ],
            ]
        ],
        'light-on': [
            transitions: [
                [ event: 'switch-off', to: 'light-off' ],
                [ event: 'switch-on', to: null ],
                [ event: 'sensor-active', to: null ],
                [ event: 'sensor-inactive', to: null ],
            ]
        ],
        'light-off': [
            transitions: [
                [ event: 'switch-off', to: null ],
                [ event: 'switch-on', to: 'light-on' ],
                [ event: 'sensor-active', to: 'turn-on' ],
                [ event: 'sensor-inactive', to: null ],
            ]
        ],
        'turn-on': [
            entry: { device?.on() },
            timeout: 5,
            transitions: [
                [ event: 'switch-off', to: 'light-off' ],
                [ event: 'switch-on', to: 'light-off-delay' ],
                [ event: 'sensor-active', to: null ],
                [ event: 'sensor-inactive', to: null ],
                [ event: 'timeout', to: 'light-off' ],
            ]
        ],
        'light-off-delay': [
            timeout: 20,
            transitions: [
                [ event: 'switch-off', to: 'light-off' ],
                [ event: 'switch-on', to: 'light-on' ],
                [ event: 'sensor-active', to: 'light-off-delay' ],
                [ event: 'sensor-inactive', to: 'light-off-delay' ],
                [ event: 'timeout', to: 'turn-off' ],
            ]
        ],
        'turn-off': [
            entry: { device?.off() },
            timeout: 5,
            transitions: [
                [ event: 'switch-off', to: 'light-off' ],
                [ event: 'switch-on', to: 'light-on' ],
                [ event: 'sensor-active', to: 'turn-on' ],
                [ event: 'sensor-inactive', to: 'turn-on' ],
                [ event: 'timeout', to: 'light-off' ],
            ]
        ]
    ]
].asImmutable()

@Field static final Map<String, Map> Subjects = new ConcurrentHashMap<>()

// Called for states with timeouts
void transitionTimeout(Map data) {
    String stateMachineId = data.stateMachineId
    List<Map> expired = Subjects.values().findAll { s -> s.timer && now() > s.timer }
    if (expired) {
        LOG.debug "StateMachine[${stateMachineId}]: sending timeout event for ${expired}"
        transitionSubjects(expired*.id, 'timeout', stateMachineId)
    }
}

private Map getSubject(String subjectId, String defaultState = 'none') {
    return Subjects.computeIfAbsent(app.id + subjectId) { String k ->
        LOG.debug "StateMachine: created subject ${subjectId}"
        return new ConcurrentHashMap([id: subjectId, state: defaultState, history: [], timestamp: now()])
    }
}

private Map setSubjectState(String subjectId, String targetState) {
    int maxHistory = 10
    Map subject = getSubject(subjectId)
    LOG.trace "StateMachine: Setting ${subject.name ?: subject.id} state from [${subject.state}] to [${targetState}]"
    sendEvent name: 'transition', value: targetState, descriptionText: "${subject.name ?: subject.id} from [${subject.state}] to [${targetState}]"
    subject.with {
        history = (state == targetState) ? history : [ state ] + history.take(maxHistory - 1)
        state = targetState
        timestamp = now()
        remove('timer')
    }

    return subject
}

private List<Map> getSubjects(List<String> ids, String defaultState = 'none') {
    return ids.collect { String id -> getSubject(id, defaultState) }
}

// Load subject states from database
private void loadSubjectStates() {
    LOG.debug 'StateMachine: loading subject states from storage'
    atomicState.subjectStates.each { String id, String state -> getSubject(id, state) }
}

// Save states to database
private void saveSubjectStates() {
    LOG.debug 'StateMachine: saving subject states to storage'
    atomicState.subjectStates = Subjects.values().collectEntries { Map s -> [ (s.id): s.state ] }
}

// Execute state transition based on event
/* groovylint-disable-next-line MethodSize */
private String transitionSubject(String subjectId, String event, String stateMachineId = 'default') {
    Map subject = getSubject(subjectId)
    synchronized(subject) {
    String name = subject.name ?: subject.id
    if (!event) {
        LOG.error "StateMachine[${stateMachineId}]: event must be specified for subject ${name}"
        return subject.state
    }

    Map<String, Map> stateMachine = FiniteStateMachines[stateMachineId]
    if (!stateMachine) {
        LOG.error "StateMachine[${stateMachineId}]: invalid state machine id ${stateMachineId}"
        return subject.state
    }

    Map<String, Map> currentState = stateMachine[subject.state]
    if (!currentState) {
        LOG.error "StateMachine[${stateMachineId}]: invalid current state for subject ${name}"
        return subject.state
    }

    Map transition = null
    try {
        transition = currentState.transitions?.findAll { t -> event ==~ t.event }.find { t ->
            if (t.guard in Closure) {
                return t.guard.rehydrate(this, subject, stateMachine).call()
            }
            return true
        }
    } catch (e) {
        LOG.exception "StateMachine[${stateMachineId}]: event ${event} guard exception for subject ${name}", e
        return subject.state
    }

    if (!transition) {
        LOG.error "StateMachine[${stateMachineId}]: ${event} not found in state ${subject.state} for subject ${name}"
        return subject.state
    }

    String targetState
    if (transition.to in Closure) {
        try {
            LOG.debug "StateMachine[${stateMachineId}]: executing transition.to ${event} for subject ${name}"
            transition.to.rehydrate(this, subject, stateMachine).call()
        } catch (e) {
            LOG.exception "StateMachine[${stateMachineId}]: event ${event} transition.to exception for subject ${name}", e
            return subject.state
        }
    } else {
        targetState = transition.to
    }

    if (targetState && !stateMachine.containsKey(targetState)) {
        LOG.error "StateMachine[${stateMachineId}]: invalid target state ${targetState} specified for ${event}"
        return subject.state
    }

    if (transition.action in Closure) {
        try {
            LOG.debug "StateMachine[${stateMachineId}]: executing ${transition.to} ${event} action for subject ${name}"
            transition.action.rehydrate(this, subject, stateMachine).call()
        } catch (e) {
            LOG.exception "StateMachine[${stateMachineId}]: event ${event} transition exception for subject ${name}", e
            return subject.state
        }
    }

    if (!targetState || targetState == 'none') {
        LOG.debug "StateMachine[${stateMachineId}]: ${subject.state} transition ${transition} has no target defined"
        return subject.state
    }

    if (currentState.exit in Closure) {
        try {
            LOG.debug "StateMachine[${stateMachineId}]: executing ${subject.state} exit closure for subject ${name}"
            currentState.exit.rehydrate(this, subject, stateMachine).call()
        } catch (e) {
            LOG.exception "StateMachine[${stateMachineId}]: event ${event} exit transition exception for subject ${name}", e
        }
    }

    setSubjectState(subject.id, targetState).lastEvent = event
    currentState = stateMachine[targetState]

    if (currentState.notify in Closure) {
        List targetSubjectIds = currentState.notify.rehydrate(this, subject, stateMachine).call()
        LOG.debug "StateMachine[${stateMachineId}]: notify state ${targetState} to subjects ${targetSubjectIds}"
        transitionSubjects(targetSubjectIds?.unique() - subject.id, targetState)
    } else if (currentState.notify in List) {
        LOG.debug "StateMachine[${stateMachineId}]: notify state ${targetState} to subjects ${currentState.notify}"
        transitionSubjects(currentState.notify.unique() - subject.id, targetState)
    }

    int timeout
    if (currentState.timeout in Closure) {
        timeout = currentState.timeout.rehydrate(this, subject, stateMachine).call()
    } else {
        timeout = currentState.timeout ?: 0
    }

    if (timeout > 0) {
        LOG.debug "StateMachine[${stateMachineId}]: scheduling ${subject.state} state timeout for ${timeout}s for subject ${name}"
        subject.timer = now() + timeout * 1000
    }

    List<Map> timers = Subjects.values().findAll { s -> s.timer && s.timer > 0 }
    if (timers) {
        long nextExpireMs = timers.min { s -> s.timer }.timer
        LOG.debug "StateMachine[${stateMachineId}]: next timer in ${nextExpireMs - now()}ms (${timers.size()} in queue)"
        runInMillis(nextExpireMs - now(), transitionTimeout, [ data: [ stateMachineId: stateMachineId ] ])
    } else {
        unschedule(transitionTimeout)
    }

    if (currentState.entry in Closure) {
        try {
            LOG.debug "StateMachine[${stateMachineId}]: executing ${subject.state} entry closure for subject ${name}"
            currentState.entry.rehydrate(this, subject, stateMachine).call()
        } catch (e) {
            LOG.exception "StateMachine[${stateMachineId}]: event ${event} entry transition exception for subject ${name}", e
        }
    } else if (currentState.entry) {
        LOG.debug "StateMachine[${stateMachineId}]: executing ${subject.state} entry event ${currentState.entry} for subject ${name}"
        transitionSubject(subject.id, currentState.entry)
    }

    LOG.debug "StateMachine[${stateMachineId}]: subject state ${subject}"
    }
    return subject.state
}

// Execute state transition for multiple subjects based on event
private List<String> transitionSubjects(List<String> subjectIds, String event, String stateMachineId = 'default') {
    return subjectIds.collect { s -> transitionSubject(s, event, stateMachineId) }
}

// Returns state machine in dot notation for graphviz
private String visualizeStateMachine(String stateMachineId = 'default') {
    Map<String, Map> stateMachine = FiniteStateMachines[stateMachineId]
    StringBuilder sb = new StringBuilder()
    sb << """\
    digraph \"${stateMachineId}\" {
      size=\"8,5\"
      rankdir=LR;
    """.stripIndent()
    stateMachine.keySet().each { statename -> sb << "  \"${statename}\";\n" }
    stateMachine.each { String statename, Map state ->
        state.transitions.each { transition ->
            if (transition.to && transition.to != 'none') {
                sb << "  \"${statename}\" -> \"${transition.to}\" [ color=\"blue\"; label=\" ${transition.event} \"]\n"
            }
        }
    }
    sb << '}\n'
    return sb.toString()
}
/*-----------------------------------------------
 *  End Finite State Machine Implementation
 *-----------------------------------------------
 */

@Field private final Map LOG = [
    debug: { s -> if (settings.logEnable == 'debug') { log.debug(s) } },
    trace: { s -> if (settings.logEnable in ['debug', 'trace']) { log.trace(s) } },
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
