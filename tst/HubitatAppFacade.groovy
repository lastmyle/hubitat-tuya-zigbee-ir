import java.util.regex.Pattern

/**
 * Test facade for Hubitat apps
 * Similar to HubitatDriverFacade but for testing apps
 */
class HubitatAppFacade {
    Script app
    StringLog log
    Map state
    Map settings
    List<Map> sentEvents
    List<Map> subscribedEvents
    Map<String, Object> mockDevices

    HubitatAppFacade(String appFilePath) {
        final GroovyShell shell = new GroovyShell()
        final String appText = new File(appFilePath).text
        app = shell.parse(appText)
        app.run()

        log = new StringLog()
        state = [:]
        settings = [:]
        sentEvents = []
        subscribedEvents = []
        mockDevices = [:]

        // Set up app binding
        app.binding.setVariable("log", log)
        app.binding.setVariable("state", state)
        app.binding.setVariable("settings", settings)
        app.binding.setVariable("sentEvents", sentEvents)
        app.binding.setVariable("subscribedEvents", subscribedEvents)

        // Mock HTTP methods
        app.binding.setVariable("httpGet", this.&mockHttpGet)

        // Mock time methods
        app.binding.setVariable("now", this.&mockNow)
        app.binding.setVariable("pauseExecution", this.&mockPauseExecution)

        // Mock subscription method
        app.binding.setVariable("subscribe", this.&mockSubscribe)
        app.binding.setVariable("unsubscribe", this.&mockUnsubscribe)
    }

    /**
     * Invoke a method on the app
     */
    def invokeMethod(final String methodName, args) {
        return app.invokeMethod(methodName, args)
    }

    /**
     * Set a setting value (simulating user input)
     */
    void setSetting(String name, Object value) {
        app.binding.setVariable(name, value)
    }

    /**
     * Get a setting value
     */
    def getSetting(String name) {
        return app.binding.getVariable(name)
    }

    /**
     * Create and add a mock device
     */
    MockDevice addMockDevice(String id, String displayName) {
        def device = new MockDevice(id: id, displayName: displayName)
        mockDevices[id] = device
        return device
    }

    /**
     * Mock httpGet implementation
     */
    void mockHttpGet(Map params, Closure closure) {
        // This should be overridden in tests with specific responses
        log.debug("Mock httpGet called with uri: ${params.uri}")

        // Default mock response (empty)
        def resp = new MockHttpResponse(
            status: 404,
            data: []
        )

        closure.call(resp)
    }

    /**
     * Mock now() implementation
     */
    long mockNow() {
        return System.currentTimeMillis()
    }

    /**
     * Mock pauseExecution implementation
     */
    void mockPauseExecution(long millis) {
        // Do nothing in tests
    }

    /**
     * Mock subscribe implementation
     */
    void mockSubscribe(device, String attributeName, Closure handler) {
        subscribedEvents.add([
            device: device,
            attribute: attributeName,
            handler: handler
        ])
        log.debug("Subscribed to ${attributeName} on ${device}")
    }

    /**
     * Mock unsubscribe implementation
     */
    void mockUnsubscribe() {
        subscribedEvents.clear()
        log.debug("Unsubscribed from all events")
    }

    /**
     * Simulate an event being fired
     */
    void fireEvent(device, String attributeName, value) {
        def subscription = subscribedEvents.find {
            it.device == device && it.attribute == attributeName
        }

        if (subscription) {
            def evt = new MockEvent(
                device: device,
                name: attributeName,
                value: value
            )
            subscription.handler.call(evt)
        }
    }
}

/**
 * Mock device for testing
 */
class MockDevice {
    String id
    String displayName
    Map<String, Object> attributes = [:]
    List<String> availableCommands = []

    def invokeMethod(String name, args) {
        // Log command invocations
        println "Device ${displayName} called: ${name}(${args})"
        return null
    }

    boolean hasCommand(String commandName) {
        return availableCommands.contains(commandName)
    }

    void setAttribute(String name, Object value) {
        attributes[name] = value
    }

    def currentValue(String attributeName) {
        return attributes[attributeName]
    }

    String toString() {
        return displayName ?: id
    }
}

/**
 * Mock HTTP response
 */
class MockHttpResponse {
    int status
    Object data
    Map headers = [:]
}

/**
 * Mock event
 */
class MockEvent {
    def device
    String name
    def value
    Date date = new Date()

    String toString() {
        return "[${name}: ${value}]"
    }
}

/**
 * StringLog for capturing log output
 */
class StringLog {
    StringWriter out = new StringWriter()

    def error(String message) {
        out.write("[error] ${message}\n")
    }

    def warn(String message) {
        out.write("[warn] ${message}\n")
    }

    def info(String message) {
        out.write("[info] ${message}\n")
    }

    def debug(String message) {
        out.write("[debug] ${message}\n")
    }

    String getOutput() {
        return out.toString()
    }

    void clear() {
        out = new StringWriter()
    }
}
