import org.junit.Test

/**
 * Tests for HVAC driver app interface methods
 */
class HvacDriverInterfaceTests {

    @Test
    void testSetHvacConfig() {
        def driver = new HubitatDriverFacade()

        def config = [
            manufacturer: "Panasonic",
            model: "CS/CU-E9PKR",
            smartIrId: "1020",
            offCommand: "JgBQAAABJJISExM5EhMSExITEjkSOBMTEhMSExITEjkSExI5EjkTOBI5EjkSOBM...",
            commands: [
                cool: [
                    auto: [
                        "24": "JgBQAAABJJISExM5EhMSExITEjkSOBMTEhMSExITEjkSExI5...",
                        "25": "JgBQAAABJJISExM5EhMSExITEjkSOBMTEhMSExITEjkSExI6..."
                    ]
                ]
            ]
        ]

        driver.setHvacConfig(config)

        // Verify config stored
        assert driver.state.hvacConfig != null
        assert driver.state.hvacConfig.manufacturer == "Panasonic"
        assert driver.state.hvacConfig.smartIrId == "1020"
        assert driver.state.hvacConfig.offCommand != null

        // Verify events sent
        assert driver.sentEvents.find { it.name == "hvacManufacturer" && it.value == "Panasonic" }
        assert driver.sentEvents.find { it.name == "hvacModel" && it.value == "CS/CU-E9PKR" }
        assert driver.sentEvents.find { it.name == "hvacSmartIrId" && it.value == "1020" }
        assert driver.sentEvents.find { it.name == "hvacConfigured" && it.value == "Yes" }
    }

    @Test
    void testClearHvacConfig() {
        def driver = new HubitatDriverFacade()

        // Set config first
        driver.state.hvacConfig = [manufacturer: "LG"]

        driver.clearHvacConfig("")

        // Verify config cleared
        assert driver.state.hvacConfig == null

        // Verify events sent
        assert driver.sentEvents.find { it.name == "hvacConfigured" && it.value == "No" }
        assert driver.sentEvents.find { it.name == "hvacManufacturer" && it.value == "Not configured" }
    }

    @Test
    void testLearnIrCode() {
        def driver = new HubitatDriverFacade()

        driver.learnIrCode("wizard_callback")

        // Verify learn command sent
        def learnCmd = driver.sentCommands.find { it.contains("0xe004") }
        assert learnCmd != null
    }

    @Test
    void testGetHvacConfig() {
        def driver = new HubitatDriverFacade()

        driver.state.hvacConfig = [
            manufacturer: "Daikin",
            smartIrId: "1100"
        ]

        def config = driver.getHvacConfig()

        assert config != null
        assert config.manufacturer == "Daikin"
        assert config.smartIrId == "1100"
    }

    @Test
    void testHvacTurnOff() {
        def driver = new HubitatDriverFacade()

        // Setup config
        driver.state.hvacConfig = [
            offCommand: "JgBQAAABJJISExM5EhMSExITEjkSOBM..."
        ]

        driver.hvacTurnOff("")

        // Verify sendCode was called
        def sendCmd = driver.sentCommands.find { it.contains("0xed00") }
        assert sendCmd != null

        // Verify state updated
        def event = driver.sentEvents.find { it.name == "hvacCurrentState" }
        assert event != null
        assert event.value == "OFF"
    }

    @Test
    void testHvacTurnOffWithoutConfig() {
        def driver = new HubitatDriverFacade()

        driver.hvacTurnOff("")

        // Verify error logged
        assert driver.log.out.toString().contains("not configured")
    }

    @Test
    void testHvacRestoreState() {
        def driver = new HubitatDriverFacade()

        // Setup config with last ON command
        driver.state.hvacConfig = [
            currentState: [mode: "cool", temp: 24, fan: "auto"]
        ]

        // Mock device.currentValue
        driver.driver.binding.setVariable("device", new StubDeviceWithCurrentValue(
            currentValues: [hvacLastOnCommand: "JgBQAAABJJISExM5..."]
        ))

        driver.hvacRestoreState("")

        // Verify sendCode was called
        def sendCmd = driver.sentCommands.find { it.contains("0xed00") }
        assert sendCmd != null
    }

    @Test
    void testHvacSendCommand() {
        def driver = new HubitatDriverFacade()

        // Setup config with commands
        driver.state.hvacConfig = [
            commands: [
                cool: [
                    auto: [
                        "24": "JgBQAAABJJISExM5EhMSExITEjkSOBM..."
                    ]
                ]
            ]
        ]

        driver.hvacSendCommand("cool", 24, "auto")

        // Verify sendCode was called
        def sendCmd = driver.sentCommands.find { it.contains("0xed00") }
        assert sendCmd != null

        // Verify state updated
        assert driver.state.hvacConfig.currentState.mode == "cool"
        assert driver.state.hvacConfig.currentState.temp == 24
        assert driver.state.hvacConfig.currentState.fan == "auto"
    }

    @Test
    void testHvacSendCommandInvalidCombination() {
        def driver = new HubitatDriverFacade()

        driver.state.hvacConfig = [
            commands: [:]
        ]

        driver.hvacSendCommand("heat", 30, "high")

        // Verify error logged
        assert driver.log.out.toString().contains("No IR code found")
    }

    @Test
    void testFormatHvacState() {
        def driver = new HubitatDriverFacade()

        assert driver.formatHvacState(null) == "Unknown"
        assert driver.formatHvacState([mode: "off"]) == "OFF"

        def coolState = driver.formatHvacState([mode: "cool", temp: 24, fan: "auto"])
        assert coolState.contains("COOL")
        assert coolState.contains("24")
        assert coolState.contains("AUTO")
    }
}

class StubDeviceWithCurrentValue {
    String id = "1234"
    String deviceNetworkId = "DEAD"
    String endpointId = "BEEF"
    Map currentValues

    String currentValue(String attrName) {
        return currentValues[attrName]
    }

    String toString() {
        return "SomeDeviceId"
    }
}
