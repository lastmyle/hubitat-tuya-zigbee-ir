import java.util.regex.Pattern

/**
 * Test facade for Hubitat apps
 * Similar to HubitatDriverFacade but for testing apps
 */
class HubitatAppFacade {
    Script app
    HubitatAppStringLog log
    Map state
    Map settings
    List<Map> sentEvents
    List<Map> subscribedEvents
    Map<String, Object> mockDevices

    HubitatAppFacade(String appFilePath) {
        log = new HubitatAppStringLog()
        state = [:]
        settings = [:]
        sentEvents = []
        subscribedEvents = []
        mockDevices = [:]

        // Create a GroovyShell with Hubitat DSL stubs in the binding
        final Binding binding = new Binding()

        // Set up app binding BEFORE parsing
        binding.setVariable("log", log)
        binding.setVariable("state", state)
        binding.setVariable("settings", settings)
        binding.setVariable("sentEvents", sentEvents)
        binding.setVariable("subscribedEvents", subscribedEvents)

        // Mock HTTP methods
        binding.setVariable("httpGet", this.&mockHttpGet)

        // Mock time methods
        binding.setVariable("now", this.&mockNow)
        binding.setVariable("pauseExecution", this.&mockPauseExecution)

        // Mock subscription methods
        binding.setVariable("subscribe", this.&mockSubscribe)
        binding.setVariable("unsubscribe", this.&mockUnsubscribe)

        // Mock Hubitat DSL methods (definition, preferences, pages)
        binding.setVariable("definition", this.&mockDefinition)
        binding.setVariable("preferences", this.&mockPreferences)
        binding.setVariable("page", this.&mockPage)
        binding.setVariable("dynamicPage", this.&mockDynamicPage)
        binding.setVariable("section", this.&mockSection)
        binding.setVariable("paragraph", this.&mockParagraph)
        binding.setVariable("input", this.&mockInput)
        binding.setVariable("href", this.&mockHref)

        final GroovyShell shell = new GroovyShell(binding)

        // Read app file and add necessary imports for @Field annotation
        String appText = new File(appFilePath).text
        if (!appText.contains("import groovy.transform.Field")) {
            appText = "import groovy.transform.Field\n" + appText
        }

        app = shell.parse(appText)
        app.run()
    }

    /**
     * Invoke a method on the app
     */
    def invokeMethod(final String methodName, args) {
        return app.invokeMethod(methodName, args)
    }

    /**
     * Handle missing methods by delegating to the app script
     */
    def methodMissing(String name, args) {
        return app.invokeMethod(name, args)
    }

    /**
     * Handle missing properties by delegating to the app script
     */
    def propertyMissing(String name) {
        return app.getProperty(name)
    }

    def propertyMissing(String name, value) {
        app.setProperty(name, value)
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
            def evt = new HubitatAppMockEvent(
                device: device,
                name: attributeName,
                value: value
            )
            subscription.handler.call(evt)
        }
    }

    /**
     * Mock Hubitat DSL methods
     */
    def mockDefinition(Map params) {
        log.debug("definition() called with: ${params}")
        return null
    }

    def mockPreferences(Closure closure) {
        log.debug("preferences() called")
        // Execute the closure to register pages
        if (closure) {
            closure.delegate = app
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
        }
        return null
    }

    def mockPage(Map params) {
        log.debug("page() called with: ${params}")
        return null
    }

    def mockDynamicPage(Map params, Closure closure) {
        log.debug("dynamicPage() called: ${params.name}")
        // Execute the closure to render the page content
        if (closure) {
            closure.delegate = app
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
        }
        return null
    }

    def mockSection(String title = null, Closure closure) {
        log.debug("section() called: ${title}")
        if (closure) {
            closure.delegate = app
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
        }
        return null
    }

    def mockParagraph(String text) {
        log.debug("paragraph() called")
        return null
    }

    def mockInput(String name, String type, Map params = [:]) {
        log.debug("input() called: ${name} (${type})")
        return null
    }

    def mockInput(Map params) {
        log.debug("input() called with map: ${params}")
        return null
    }

    def mockHref(String pageName, Map params) {
        log.debug("href() called: ${pageName}")
        return null
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
class HubitatAppMockEvent {
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
class HubitatAppStringLog {
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
