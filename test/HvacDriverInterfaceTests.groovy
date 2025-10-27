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

    @Test
    void testHvacSendCommandAllModes() {
        def driver = new HubitatDriverFacade()

        // Setup config with all modes
        driver.state.hvacConfig = [
            commands: [
                cool: [auto: ["24": "COOL_AUTO_24"]],
                heat: [auto: ["22": "HEAT_AUTO_22"]],
                fan_only: [low: ["20": "FAN_LOW_20"]]
            ]
        ]

        // Test cool mode
        driver.hvacSendCommand("cool", 24, "auto")
        assert driver.state.hvacConfig.currentState.mode == "cool"

        // Test heat mode
        driver.hvacSendCommand("heat", 22, "auto")
        assert driver.state.hvacConfig.currentState.mode == "heat"

        // Test fan_only mode
        driver.hvacSendCommand("fan_only", 20, "low")
        assert driver.state.hvacConfig.currentState.mode == "fan_only"
    }

    @Test
    void testHvacSendCommandTemperatureBoundaries() {
        def driver = new HubitatDriverFacade()

        driver.state.hvacConfig = [
            commands: [
                cool: [
                    auto: [
                        "16": "COOL_16",  // Min temp
                        "30": "COOL_30"   // Max temp
                    ]
                ]
            ]
        ]

        // Test minimum temperature
        driver.hvacSendCommand("cool", 16, "auto")
        assert driver.state.hvacConfig.currentState.temp == 16

        // Test maximum temperature
        driver.hvacSendCommand("cool", 30, "auto")
        assert driver.state.hvacConfig.currentState.temp == 30
    }

    @Test
    void testHvacSendCommandAllFanSpeeds() {
        def driver = new HubitatDriverFacade()

        driver.state.hvacConfig = [
            commands: [
                cool: [
                    auto: ["24": "COOL_AUTO_24"],
                    low: ["24": "COOL_LOW_24"],
                    mid: ["24": "COOL_MID_24"],
                    high: ["24": "COOL_HIGH_24"]
                ]
            ]
        ]

        // Test each fan speed
        driver.hvacSendCommand("cool", 24, "auto")
        assert driver.state.hvacConfig.currentState.fan == "auto"

        driver.hvacSendCommand("cool", 24, "low")
        assert driver.state.hvacConfig.currentState.fan == "low"

        driver.hvacSendCommand("cool", 24, "mid")
        assert driver.state.hvacConfig.currentState.fan == "mid"

        driver.hvacSendCommand("cool", 24, "high")
        assert driver.state.hvacConfig.currentState.fan == "high"
    }

    @Test
    void testHvacSendCommandUpdatesLastOnCommand() {
        def driver = new HubitatDriverFacade()

        driver.state.hvacConfig = [
            commands: [
                cool: [auto: ["24": "COOL_AUTO_24_LONG_CODE"]]
            ]
        ]

        driver.hvacSendCommand("cool", 24, "auto")

        // Verify lastOnCommand event was sent
        def event = driver.sentEvents.find { it.name == "hvacLastOnCommand" }
        assert event != null
        assert event.value != null
        assert event.value.length() <= 50  // Should be truncated
    }

    @Test
    void testHvacTurnOffUpdatesState() {
        def driver = new HubitatDriverFacade()

        // Start with ON state
        driver.state.hvacConfig = [
            offCommand: "OFF_CODE",
            currentState: [mode: "cool", temp: 24, fan: "auto"]
        ]

        driver.hvacTurnOff("")

        // Verify state updated to OFF
        assert driver.state.hvacConfig.currentState.mode == "off"

        // Verify event sent
        def event = driver.sentEvents.find { it.name == "hvacCurrentState" }
        assert event.value == "OFF"
    }

    @Test
    void testHvacRestoreStateWithNoLastCommand() {
        def driver = new HubitatDriverFacade()

        driver.state.hvacConfig = [
            currentState: [mode: "off"]
        ]

        // Mock device.currentValue to return "None"
        driver.driver.binding.setVariable("device", new StubDeviceWithCurrentValue(
            currentValues: [hvacLastOnCommand: "None"]
        ))

        driver.hvacRestoreState("")

        // Should log warning
        assert driver.log.out.toString().contains("No previous ON state")
    }

    @Test
    void testHvacCommandsWithDecimalTemperature() {
        def driver = new HubitatDriverFacade()

        driver.state.hvacConfig = [
            commands: [
                cool: [auto: ["24": "COOL_24"]]
            ]
        ]

        // Pass decimal temperature (should convert to integer)
        driver.hvacSendCommand("cool", 24.5, "auto")

        // Should round to integer
        assert driver.state.hvacConfig.currentState.temp == 24
    }

    @Test
    void testSetHvacConfigWithMinimalData() {
        def driver = new HubitatDriverFacade()

        def config = [
            smartIrId: "9999"  // Minimal required field
        ]

        driver.setHvacConfig(config)

        assert driver.state.hvacConfig != null
        assert driver.state.hvacConfig.smartIrId == "9999"
    }

    @Test
    void testSetHvacConfigWithMissingSmartIrId() {
        def driver = new HubitatDriverFacade()

        def config = [
            manufacturer: "TestBrand"
            // Missing smartIrId
        ]

        driver.setHvacConfig(config)

        // Should log error and not save
        assert driver.log.out.toString().contains("Invalid config")
    }

    @Test
    void testSetHvacConfigWithNullInput() {
        def driver = new HubitatDriverFacade()

        driver.setHvacConfig(null)

        // Should log error and not crash
        assert driver.log.out.toString().contains("Invalid config")
    }

    @Test
    void testGetHvacConfigWhenNotConfigured() {
        def driver = new HubitatDriverFacade()

        def config = driver.getHvacConfig()

        assert config == null
    }

    @Test
    void testHvacCommandStateFormatting() {
        def driver = new HubitatDriverFacade()

        // Test OFF formatting
        assert driver.formatHvacState([mode: "off"]) == "OFF"

        // Test COOL formatting
        def coolStr = driver.formatHvacState([mode: "cool", temp: 24, fan: "auto"])
        assert coolStr == "COOL 24°C Fan:AUTO"

        // Test HEAT formatting
        def heatStr = driver.formatHvacState([mode: "heat", temp: 22, fan: "low"])
        assert heatStr == "HEAT 22°C Fan:LOW"

        // Test FAN_ONLY formatting
        def fanStr = driver.formatHvacState([mode: "fan_only", temp: 20, fan: "high"])
        assert fanStr == "FAN_ONLY 20°C Fan:HIGH"
    }

    @Test
    void testHvacSendCommandMultipleSequentialCalls() {
        def driver = new HubitatDriverFacade()

        driver.state.hvacConfig = [
            commands: [
                cool: [
                    auto: [
                        "22": "COOL_22",
                        "24": "COOL_24",
                        "26": "COOL_26"
                    ]
                ]
            ]
        ]

        // Send multiple commands in sequence
        driver.hvacSendCommand("cool", 22, "auto")
        assert driver.state.hvacConfig.currentState.temp == 22

        driver.hvacSendCommand("cool", 24, "auto")
        assert driver.state.hvacConfig.currentState.temp == 24

        driver.hvacSendCommand("cool", 26, "auto")
        assert driver.state.hvacConfig.currentState.temp == 26

        // Verify multiple Zigbee commands sent
        def zigbeeCommands = driver.sentCommands.findAll { it.contains("0xed00") }
        assert zigbeeCommands.size() == 3
    }

    @Test
    void testClearHvacConfigClearsAllAttributes() {
        def driver = new HubitatDriverFacade()

        // Setup config first
        driver.state.hvacConfig = [
            manufacturer: "TestBrand",
            model: "TestModel",
            smartIrId: "1234",
            offCommand: "OFF",
            commands: [cool: [auto: ["24": "COOL_24"]]],
            currentState: [mode: "cool", temp: 24, fan: "auto"]
        ]

        driver.clearHvacConfig("")

        // Verify state cleared
        assert driver.state.hvacConfig == null

        // Verify all attribute events sent with "Not configured" or "No"
        assert driver.sentEvents.find { it.name == "hvacManufacturer" && it.value == "Not configured" }
        assert driver.sentEvents.find { it.name == "hvacModel" && it.value == "Not configured" }
        assert driver.sentEvents.find { it.name == "hvacSmartIrId" && it.value == "Not configured" }
        assert driver.sentEvents.find { it.name == "hvacOffCommand" && it.value == "Not configured" }
        assert driver.sentEvents.find { it.name == "hvacCurrentState" && it.value == "Not configured" }
        assert driver.sentEvents.find { it.name == "hvacConfigured" && it.value == "No" }
        assert driver.sentEvents.find { it.name == "hvacLastOnCommand" && it.value == "Not configured" }
    }

    @Test
    void testLearnIrCodeTriggersLearnMode() {
        def driver = new HubitatDriverFacade()

        driver.learnIrCode("test_callback")

        // Verify learn command was sent to device (cluster 0xe004 is the learn cluster)
        def learnCmd = driver.sentCommands.find { it.contains("0xe004") }
        assert learnCmd != null
    }

    @Test
    void testHvacTurnOffWithNullCurrentState() {
        def driver = new HubitatDriverFacade()

        // Config without currentState
        driver.state.hvacConfig = [
            offCommand: "OFF_CODE",
            currentState: null
        ]

        driver.hvacTurnOff("")

        // Should create new currentState
        assert driver.state.hvacConfig.currentState != null
        assert driver.state.hvacConfig.currentState.mode == "off"
    }

    @Test
    void testFormatHvacStateWithNullFields() {
        def driver = new HubitatDriverFacade()

        // State with null temperature and fan
        def str = driver.formatHvacState([mode: "cool", temp: null, fan: null])
        assert str.contains("COOL")
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
