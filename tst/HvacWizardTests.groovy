import org.junit.Test

/**
 * Tests for HVAC Setup Wizard App functionality
 */
class HvacWizardTests {

    @Test
    void testAppInitialization() {
        def app = new HubitatAppFacade("hvac-setup-app.groovy")

        // Verify app loaded
        assert app != null
        assert app.log != null
        assert app.state != null
    }

    @Test
    void testCodeMatching() {
        def app = new HubitatAppFacade("hvac-setup-app.groovy")

        // Set up mock models in cache
        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis(),
            manufacturers: [
                "Panasonic": [
                    "1020": [
                        manufacturer: "Panasonic",
                        supportedModels: ["CS/CU-E9PKR"],
                        operationModes: ["cool", "heat"],
                        fanModes: ["low", "mid", "high", "auto"],
                        minTemperature: 16,
                        maxTemperature: 30,
                        commands: [
                            off: "TEST_OFF_CODE_123",
                            cool: [
                                auto: [
                                    "24": "TEST_COOL_24_AUTO"
                                ]
                            ]
                        ]
                    ]
                ]
            ]
        ]

        // Test matching OFF code
        def result = app.matchCodeToModel("TEST_OFF_CODE_123", "Panasonic")

        assert result != null
        assert result.smartIrId == "1020"
        assert result.manufacturer == "Panasonic"
        assert result.detectedState.mode == "off"
    }

    @Test
    void testCodeMatchingCoolMode() {
        def app = new HubitatAppFacade("hvac-setup-app.groovy")

        // Set up mock models
        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis(),
            manufacturers: [
                "Daikin": [
                    "1100": [
                        manufacturer: "Daikin",
                        supportedModels: ["ARC433A1"],
                        commands: [
                            cool: [
                                auto: [
                                    "22": "TEST_COOL_22_AUTO"
                                ]
                            ]
                        ]
                    ]
                ]
            ]
        ]

        // Test matching cool command
        def result = app.matchCodeToModel("TEST_COOL_22_AUTO", "Daikin")

        assert result != null
        assert result.smartIrId == "1100"
        assert result.detectedState.mode == "cool"
        assert result.detectedState.temp == 22
        assert result.detectedState.fan == "auto"
    }

    @Test
    void testCodeMatchingNoMatch() {
        def app = new HubitatAppFacade("hvac-setup-app.groovy")

        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis(),
            manufacturers: [
                "LG": [
                    "1060": [
                        manufacturer: "LG",
                        commands: [
                            off: "DIFFERENT_CODE"
                        ]
                    ]
                ]
            ]
        ]

        // Test with non-matching code
        def result = app.matchCodeToModel("UNKNOWN_CODE_XYZ", "LG")

        assert result == null
    }

    @Test
    void testCacheValidation() {
        def app = new HubitatAppFacade("hvac-setup-app.groovy")

        // No cache - should be invalid
        assert !app.isCacheValid()

        // Old cache - should be invalid
        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis() - (25 * 60 * 60 * 1000) // 25 hours ago
        ]
        assert !app.isCacheValid()

        // Fresh cache - should be valid
        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis() - (1 * 60 * 60 * 1000) // 1 hour ago
        ]
        assert app.isCacheValid()
    }

}
