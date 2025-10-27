import org.junit.Test

/**
 * Tests for HVAC event handling during wizard setup with local protocol detection
 * Tests the event-driven mechanism for IR code learning
 */
class HvacEventHandlingTests {

    @Test
    void testAppInitializeSubscribesToEvents() {
        def app = new HubitatAppFacade("app.groovy")

        // Setup mock device
        def mockDevice = new MockIrDeviceWithEvents()
        app.binding.setVariable("irDevice", mockDevice)

        // Call initialize
        app.initialize()

        // Verify subscription was made (subscriptions are tracked in app.subscribedEvents)
        assert app.subscribedEvents.size() > 0
        assert app.subscribedEvents.find { it.attribute == "lastLearnedCode" }
        assert app.subscribedEvents.find { it.handlerName == "codeLearnedHandler" }
    }

    @Test
    void testCodeLearnedEventHandler() {
        def app = new HubitatAppFacade("app.groovy")

        // Setup manufacturer
        app.binding.setVariable("hvacManufacturer", "Fujitsu")

        // Simulate learned code event with real Fujitsu code
        def mockEvent = new HvacMockEvent(value: TestCodes.FUJITSU_OFF)
        app.codeLearnedHandler(mockEvent)

        // Verify code was stored
        assert app.state.wizardState.learnedCode == TestCodes.FUJITSU_OFF
        assert app.state.wizardState.learningInProgress == false

        // Verify protocol was detected
        assert app.state.wizardState.detectedModel != null
        assert app.state.wizardState.detectedModel.smartIrId == "FUJITSU_AC"
        assert app.state.wizardState.detectedModel.manufacturer.contains("Fujitsu")
    }

    @Test
    void testCodeLearnedEventHandlerDaikin() {
        def app = new HubitatAppFacade("app.groovy")

        app.binding.setVariable("hvacManufacturer", "Daikin")

        // Simulate learned code event with real Daikin code
        def mockEvent = new HvacMockEvent(value: TestCodes.DAIKIN_COOL_24_AUTO)
        app.codeLearnedHandler(mockEvent)

        // Verify code was stored
        assert app.state.wizardState.learnedCode == TestCodes.DAIKIN_COOL_24_AUTO

        // Verify protocol was detected
        assert app.state.wizardState.detectedModel != null
        assert app.state.wizardState.detectedModel.smartIrId == "DAIKIN"
        assert app.state.wizardState.detectedModel.manufacturer.contains("Daikin")
    }

    @Test
    void testCodeLearnedEventHandlerNoMatch() {
        def app = new HubitatAppFacade("app.groovy")

        app.binding.setVariable("hvacManufacturer", "LG")

        // Simulate learned code event with invalid code
        def mockEvent = new HvacMockEvent(value: "INVALID_CODE")
        app.codeLearnedHandler(mockEvent)

        // Verify code was stored
        assert app.state.wizardState.learnedCode == "INVALID_CODE"

        // Verify model was NOT detected
        assert app.state.wizardState.detectedModel == null
        assert app.state.wizardState.matchError != null
        assert app.state.wizardState.matchError.contains("identify protocol")
    }

    @Test
    void testCodeLearnedEventHandlerNoManufacturer() {
        def app = new HubitatAppFacade("app.groovy")

        // No manufacturer selected (should still work - manufacturer is just a hint)
        app.binding.setVariable("hvacManufacturer", null)

        // Use real code
        def mockEvent = new HvacMockEvent(value: TestCodes.PANASONIC_COOL_20_AUTO)
        app.codeLearnedHandler(mockEvent)

        // Verify code was stored
        assert app.state.wizardState.learnedCode == TestCodes.PANASONIC_COOL_20_AUTO

        // Should still detect protocol (manufacturer hint not required)
        assert app.state.wizardState.detectedModel != null
        assert app.state.wizardState.detectedModel.smartIrId == "PANASONIC_AC"
    }

    @Test
    void testCodeLearnedEventHandlerEmptyCode() {
        def app = new HubitatAppFacade("app.groovy")

        def mockEvent = new HvacMockEvent(value: "")
        app.codeLearnedHandler(mockEvent)

        // Should log warning but not crash
        assert app.log.out.toString().contains("Empty code")
        assert app.state.wizardState.learningInProgress == false
    }

    @Test
    void testCodeLearnedEventHandlerWithWhitespace() {
        def app = new HubitatAppFacade("app.groovy")

        app.binding.setVariable("hvacManufacturer", "Fujitsu")

        // Event value with newlines (as stored by driver)
        String codeWithWhitespace = TestCodes.FUJITSU_COOL_24_AUTO.replaceAll("(.{20})", "\$1\n")
        def mockEvent = new HvacMockEvent(value: codeWithWhitespace)
        app.codeLearnedHandler(mockEvent)

        // Should normalize and match
        assert app.state.wizardState.detectedModel != null
        assert app.state.wizardState.detectedModel.smartIrId == "FUJITSU_AC"
    }

    @Test
    void testAppButtonHandlerLearnTrigger() {
        def app = new HubitatAppFacade("app.groovy")

        def mockDevice = new MockIrDeviceWithEvents()
        app.binding.setVariable("irDevice", mockDevice)

        // Simulate button press
        app.appButtonHandler("triggerLearn")

        // Verify learnIrCode was called on device
        assert mockDevice.learnIrCodeCalled
        assert mockDevice.learnIrCodeCallback == "wizard"

        // Verify wizard state updated
        assert app.state.wizardState.learningInProgress == true
    }

    @Test
    void testAppButtonHandlerLearnTriggerNoDevice() {
        def app = new HubitatAppFacade("app.groovy")

        app.binding.setVariable("irDevice", null)

        // Should not crash
        app.appButtonHandler("triggerLearn")

        // Should log error
        assert app.log.out.toString().contains("No device selected")
    }

    @Test
    void testAppButtonHandlerUnknownButton() {
        def app = new HubitatAppFacade("app.groovy")

        // Should not crash
        app.appButtonHandler("unknownButton")

        // Should log warning
        assert app.log.out.toString().contains("Unknown button")
    }

    @Test
    void testEventHandlerExceptionHandling() {
        def app = new HubitatAppFacade("app.groovy")

        app.binding.setVariable("hvacManufacturer", "Gree")

        // Use invalid code to trigger exception path
        def mockEvent = new HvacMockEvent(value: "NOT_VALID_BASE64!")

        // Should not crash
        app.codeLearnedHandler(mockEvent)

        // Should have error in wizard state
        assert app.state.wizardState.matchError != null
    }

    @Test
    void testMultipleCodeLearnedEvents() {
        def app = new HubitatAppFacade("app.groovy")

        app.binding.setVariable("hvacManufacturer", "Gree")

        // First event - OFF code
        def event1 = new HvacMockEvent(value: TestCodes.GREE_OFF)
        app.codeLearnedHandler(event1)

        assert app.state.wizardState.detectedModel != null
        assert app.state.wizardState.detectedModel.smartIrId == "GREE"

        // Second event - COOL code (should replace first)
        def event2 = new HvacMockEvent(value: TestCodes.GREE_COOL_AUTO_22)
        app.codeLearnedHandler(event2)

        assert app.state.wizardState.learnedCode == TestCodes.GREE_COOL_AUTO_22
        assert app.state.wizardState.detectedModel.smartIrId == "GREE"
    }

    @Test
    void testCodeLearnedWithDifferentProtocols() {
        def app = new HubitatAppFacade("app.groovy")

        // Test Mitsubishi
        app.binding.setVariable("hvacManufacturer", "Mitsubishi")
        def event1 = new HvacMockEvent(value: TestCodes.MITSUBISHI_HEAT_26_HIGH)
        app.codeLearnedHandler(event1)

        def model = app.state.wizardState.detectedModel

        assert model != null
        assert model.smartIrId == "MITSUBISHI_AC"
        assert model.manufacturer.toLowerCase().contains("mitsubishi")
        assert model.modelData.operationModes.contains("heat")
        assert model.modelData.fanModes.contains("high")
    }

    @Test
    void testManufacturerHintMismatch() {
        def app = new HubitatAppFacade("app.groovy")

        // Set hint to Daikin but use Fujitsu code
        app.binding.setVariable("hvacManufacturer", "Daikin")

        def event = new HvacMockEvent(value: TestCodes.FUJITSU_COOL_24_AUTO)
        app.codeLearnedHandler(event)

        // Should still detect correctly (protocol detection is independent)
        assert app.state.wizardState.detectedModel != null
        assert app.state.wizardState.detectedModel.smartIrId == "FUJITSU_AC"

        // Check log for warning about mismatch
        assert app.log.out.toString().contains("don't match hint")
    }

    @Test
    void testReadyForNextPageFlag() {
        def app = new HubitatAppFacade("app.groovy")

        app.binding.setVariable("hvacManufacturer", "LG")

        // Successful detection should set readyForNextPage
        def event = new HvacMockEvent(value: TestCodes.LG_COOL_24_AUTO)
        app.codeLearnedHandler(event)

        assert app.state.wizardState.readyForNextPage == true

        // Failed detection should NOT set readyForNextPage
        def event2 = new HvacMockEvent(value: "INVALID")
        app.codeLearnedHandler(event2)

        assert app.state.wizardState.readyForNextPage == false
    }

}

/**
 * Mock IR device for testing event handlers
 */
class MockIrDeviceWithEvents {
    boolean learnIrCodeCalled = false
    String learnIrCodeCallback = null
    String displayName = "MockIrDevice"
    def id = "mock-123"
    def deviceNetworkId = "mock-dni"
    List<String> supportedCommands = [[name: "learnIrCode"], [name: "setHvacConfig"]]

    void learnIrCode(String callback) {
        learnIrCodeCalled = true
        learnIrCodeCallback = callback
    }

    boolean hasCommand(String commandName) {
        return commandName == "setHvacConfig" || commandName == "learnIrCode"
    }
}

/**
 * Mock event for testing (local definition to avoid classpath issues)
 */
class HvacMockEvent {
    def device
    String name = "lastLearnedCode"
    def value
    Date date = new Date()

    String toString() {
        return "[${name}: ${value}]"
    }
}
