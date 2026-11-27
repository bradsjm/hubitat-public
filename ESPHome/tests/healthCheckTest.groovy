// Run from the repository root: groovy ESPHome/tests/healthCheckTest.groovy
def loader = new GroovyClassLoader()
loader.parseClass('''
    package hubitat.helper
    class HexUtils {
        static String byteArrayToHexString(byte[] bytes) { bytes.encodeHex().toString() }
        static byte[] hexStringToByteArray(String hex) { hex.decodeHex() }
    }
''')
String source = new File('ESPHome/ESPHome-API-Library.groovy').getText('UTF-8')
Class helperClass = loader.parseClass(source + '''
    void parse(Map message) { received << message }
''')

def invoke = { helper, String name, Class[] types, Object[] args ->
    def method = helperClass.getDeclaredMethod(name, types)
    method.accessible = true
    method.invoke(helper, args)
}
def invokeNoArgs = { helper, String name ->
    invoke(helper, name, [] as Class[], [] as Object[])
}

def fixture = { String id ->
    Map jobs = [:]
    List schedules = []
    List writes = []
    List messages = []
    List logs = []
    Map attributes = [networkStatus: 'offline']
    Map state = [apiVersionMajor: 1, apiVersionMinor: 14]
    def device = new Expando(id: id, disabled: false)
    device.isDisabled = { -> device.disabled }
    device.currentValue = { String name -> attributes[name] }
    device.updateDataValue = { String name, String value -> }
    def socket = new Expando()
    socket.sendMessage = { String value -> writes << value }
    socket.close = { -> }
    def logger = new Expando()
    logger.debug = { Object msg -> logs << msg.toString() }
    logger.trace = { Object msg -> }
    logger.info = { Object msg -> }
    logger.warn = { Object msg -> }
    logger.error = { Object msg -> }
    def helper = helperClass.getDeclaredConstructor().newInstance()
    helper.binding = new Binding([
        device: device, state: state, settings: [ipAddress: 'example.test'], logEnable: true,
        interfaces: [rawSocket: socket], log: logger, received: messages
    ])
    helper.metaClass.runIn = { Integer seconds, String handler ->
        jobs[handler] = seconds
        schedules << [handler: handler, seconds: seconds]
    }
    helper.metaClass.runInMillis = { Integer millis, String handler -> }
    helper.metaClass.unschedule = { String handler -> jobs.remove(handler) }
    helper.metaClass.sendEvent = { Map event -> attributes[event.name] = event.value }
    helper.metaClass.pauseExecution = { Integer millis -> }
    return [helper: helper, device: device, state: state, jobs: jobs, schedules: schedules,
        writes: writes, messages: messages, logs: logs, attributes: attributes]
}
def connect = { f ->
    invoke(f.helper, 'espHomeConnectRequest', [String] as Class[], [null] as Object[])
}
def fireHealthCheck = { f ->
    assert f.jobs.remove('healthCheck') != null
    invokeNoArgs(f.helper, 'healthCheck')
}
def healthSchedules = { f -> f.schedules.findAll { it.handler == 'healthCheck' } }

// Fast-path connections schedule a heartbeat; ordinary updates must not reschedule it.
def normal = fixture('normal')
connect(normal)
assert normal.jobs.healthCheck in 31..60
assert healthSchedules(normal).size() == 1
normal.helper.parse('00002f' * 250)
assert normal.messages.count { it.platform == 'climate' && it.type == 'state' } == 250
assert healthSchedules(normal).size() == 1
assert normal.logs.count { it.startsWith('ESPHome scheduling healthCheck') } == 1
fireHealthCheck(normal)
assert normal.writes.last() == '000007'
normal.helper.parse('000008')
assert normal.jobs.healthCheck in 31..60
assert healthSchedules(normal).size() == 2

// A pending climate command must not permanently stop the heartbeat.
def busy = fixture('busy')
connect(busy)
busy.helper.espHomeClimateCommand([key: 42L, mode: 3])
assert busy.jobs.sendMessageQueue == 5
fireHealthCheck(busy)
assert busy.jobs.healthCheck == 5
fireHealthCheck(busy)
assert busy.jobs.healthCheck == 5
assert !busy.writes.contains('000007')
busy.helper.parse('00002f')
assert !busy.jobs.containsKey('sendMessageQueue')
fireHealthCheck(busy)
assert busy.writes.last() == '000007'
busy.helper.parse('000008')
assert busy.jobs.healthCheck in 31..60

// An unanswered command must still time out and reconnect, canceling the retrying health check.
def timeout = fixture('timeout')
connect(timeout)
timeout.helper.espHomeClimateCommand([key: 42L, mode: 3])
fireHealthCheck(timeout)
6.times { invokeNoArgs(timeout.helper, 'sendMessageQueue') }
assert timeout.attributes.networkStatus == 'offline'
assert timeout.jobs.openSocket > 0
assert !timeout.jobs.containsKey('healthCheck')

// Incoming state traffic must not hide an unanswered ping.
def missingPong = fixture('missing-pong')
connect(missingPong)
fireHealthCheck(missingPong)
6.times {
    missingPong.helper.parse('00002f')
    invokeNoArgs(missingPong.helper, 'sendMessageQueue')
}
assert missingPong.attributes.networkStatus == 'offline'
assert missingPong.jobs.openSocket > 0

// Legacy authentication responses must still schedule the first heartbeat.
def legacy = fixture('legacy')
legacy.state.apiVersionMinor = 11
connect(legacy)
assert !legacy.jobs.containsKey('healthCheck')
legacy.helper.parse('000004')
assert legacy.jobs.healthCheck in 31..60

println 'healthCheckTest passed'
