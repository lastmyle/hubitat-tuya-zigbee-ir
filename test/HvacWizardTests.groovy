import org.junit.Test

/**
 * Tests for HVAC Setup Wizard App functionality with local protocol detection
 */
class HvacWizardTests {

    @Test
    void testAppInitialization() {
        def app = new HubitatAppFacade("app.groovy")

        // Verify app loaded
        assert app != null
        assert app.log != null
        assert app.state != null
    }

    @Test
    void testCodeMatchingFujitsu() {
        def app = new HubitatAppFacade("app.groovy")

        // Test matching Fujitsu OFF code
        def result = app.matchCodeToModel(TestCodes.FUJITSU_OFF, "Fujitsu")

        assert result != null
        assert result.smartIrId == "FUJITSU_AC"
        assert result.manufacturer.contains("Fujitsu")
        assert result.modelData != null
        assert result.modelData.commands != null
        assert result.modelData.commands.off != null
    }

    @Test
    void testCodeMatchingFujitsuCool() {
        def app = new HubitatAppFacade("app.groovy")

        // Test matching Fujitsu cool command
        def result = app.matchCodeToModel(TestCodes.FUJITSU_COOL_24_AUTO, "Fujitsu")

        assert result != null
        assert result.smartIrId == "FUJITSU_AC"
        assert result.manufacturer.contains("Fujitsu")
        assert result.modelData.operationModes.contains("cool")
        assert result.modelData.fanModes.contains("auto")
    }

    @Test
    void testCodeMatchingDaikin() {
        def app = new HubitatAppFacade("app.groovy")

        // Test matching Daikin code
        def result = app.matchCodeToModel(TestCodes.DAIKIN_COOL_24_AUTO, "Daikin")

        assert result != null
        assert result.smartIrId == "DAIKIN"
        assert result.manufacturer.contains("Daikin")
        assert result.protocolInfo != null
        assert result.protocolInfo.confidence > 0
    }

    @Test
    void testCodeMatchingPanasonic() {
        def app = new HubitatAppFacade("app.groovy")

        // Test matching Panasonic code
        def result = app.matchCodeToModel(TestCodes.PANASONIC_COOL_20_AUTO, "Panasonic")

        assert result != null
        assert result.smartIrId == "PANASONIC_AC"
        assert result.manufacturer.contains("Panasonic")
    }

    @Test
    void testCodeMatchingMitsubishi() {
        def app = new HubitatAppFacade("app.groovy")

        // Test matching Mitsubishi code
        def result = app.matchCodeToModel(TestCodes.MITSUBISHI_HEAT_26_HIGH, "Mitsubishi")

        assert result != null
        assert result.smartIrId == "MITSUBISHI_AC"
        assert result.manufacturer.toLowerCase().contains("mitsubishi")
        assert result.modelData.operationModes.contains("heat")
        assert result.modelData.fanModes.contains("high")
    }

    @Test
    void testCodeMatchingLG() {
        def app = new HubitatAppFacade("app.groovy")

        // Test matching LG code
        def result = app.matchCodeToModel(TestCodes.LG_COOL_24_AUTO, "LG")

        assert result != null
        assert result.smartIrId == "LG"
        assert result.manufacturer.contains("LG")
    }

    @Test
    void testCodeMatchingGree() {
        def app = new HubitatAppFacade("app.groovy")

        // Test matching Gree code with different fan speeds
        def resultAuto = app.matchCodeToModel(TestCodes.GREE_COOL_AUTO_22, "Gree")
        assert resultAuto != null
        assert resultAuto.smartIrId == "GREE"
        assert resultAuto.manufacturer.contains("Gree")

        def resultLow = app.matchCodeToModel(TestCodes.GREE_COOL_LOW_22, "Gree")
        assert resultLow != null
        assert resultLow.smartIrId == "GREE"

        def resultMid = app.matchCodeToModel(TestCodes.GREE_COOL_MID_22, "Gree")
        assert resultMid != null
        assert resultMid.smartIrId == "GREE"

        def resultHigh = app.matchCodeToModel(TestCodes.GREE_COOL_HIGH_22, "Gree")
        assert resultHigh != null
        assert resultHigh.smartIrId == "GREE"
    }

    @Test
    void testCodeMatchingNoMatch() {
        def app = new HubitatAppFacade("app.groovy")

        // Test with invalid code (not a real Tuya IR code)
        def result = app.matchCodeToModel("INVALID_CODE_XYZ", "LG")

        assert result == null
    }

    @Test
    void testCodeMatchingEmptyCode() {
        def app = new HubitatAppFacade("app.groovy")

        // Test with empty code
        def result = app.matchCodeToModel("", "Fujitsu")
        assert result == null

        // Test with null code
        result = app.matchCodeToModel(null, "Fujitsu")
        assert result == null

        // Test with whitespace only
        result = app.matchCodeToModel("   ", "Fujitsu")
        assert result == null
    }

    @Test
    void testCodeMatchingWithWhitespace() {
        def app = new HubitatAppFacade("app.groovy")

        // Get a real code
        String cleanCode = TestCodes.FUJITSU_COOL_24_AUTO

        // Test with code that has whitespace/newlines (simulating driver storage)
        String codeWithSpaces = cleanCode.replaceAll("(.{10})", "\$1 ")
        String codeWithNewlines = cleanCode.replaceAll("(.{20})", "\$1\n")

        def result1 = app.matchCodeToModel(codeWithSpaces, "Fujitsu")
        assert result1 != null
        assert result1.smartIrId == "FUJITSU_AC"

        def result2 = app.matchCodeToModel(codeWithNewlines, "Fujitsu")
        assert result2 != null
        assert result2.smartIrId == "FUJITSU_AC"
    }

    @Test
    void testCodeMatchingManufacturerMismatch() {
        def app = new HubitatAppFacade("app.groovy")

        // Test Fujitsu code with Daikin hint - protocol detection should still work
        // (it will log a warning but proceed)
        def result = app.matchCodeToModel(TestCodes.FUJITSU_COOL_24_AUTO, "Daikin")

        assert result != null
        assert result.smartIrId == "FUJITSU_AC"  // Should detect Fujitsu despite hint
    }

    @Test
    void testCodeMatchingTemperatureBoundaries() {
        def app = new HubitatAppFacade("app.groovy")

        // Test minimum temperature
        def resultMin = app.matchCodeToModel(TestCodes.FUJITSU_COOL_16_AUTO, "Fujitsu")
        assert resultMin != null
        assert resultMin.modelData.minTemperature == 16

        // Test maximum temperature
        def resultMax = app.matchCodeToModel(TestCodes.FUJITSU_COOL_30_AUTO, "Fujitsu")
        assert resultMax != null
        assert resultMax.modelData.maxTemperature == 30
    }

    @Test
    void testGeneratedCommandStructure() {
        def app = new HubitatAppFacade("app.groovy")

        def result = app.matchCodeToModel(TestCodes.FUJITSU_COOL_24_AUTO, "Fujitsu")

        assert result != null
        assert result.modelData.commands != null

        // Should have off command
        assert result.modelData.commands.off != null

        // Should have mode commands (cool, heat, etc.)
        assert result.modelData.commands.cool != null

        // Each mode should have fan speeds
        assert result.modelData.commands.cool.auto != null

        // Each fan speed should have temperature mappings
        assert result.modelData.commands.cool.auto['24'] != null
    }

    @Test
    void testProtocolCapabilities() {
        def app = new HubitatAppFacade("app.groovy")

        def result = app.matchCodeToModel(TestCodes.FUJITSU_COOL_24_AUTO, "Fujitsu")

        assert result != null
        assert result.modelData != null

        // Check standard HVAC capabilities
        assert result.modelData.operationModes.size() > 0
        assert result.modelData.operationModes.contains("cool")
        assert result.modelData.operationModes.contains("heat")

        assert result.modelData.fanModes.size() > 0
        assert result.modelData.fanModes.contains("auto")

        assert result.modelData.minTemperature >= 16
        assert result.modelData.maxTemperature <= 30
    }

    @Test
    void testProtocolConfidenceScore() {
        def app = new HubitatAppFacade("app.groovy")

        def result = app.matchCodeToModel(TestCodes.FUJITSU_COOL_24_AUTO, "Fujitsu")

        assert result != null
        assert result.protocolInfo != null
        assert result.protocolInfo.confidence != null
        assert result.protocolInfo.confidence > 0.5  // Should have reasonable confidence
        assert result.protocolInfo.confidence <= 1.0
    }

    @Test
    void testCacheValidation() {
        def app = new HubitatAppFacade("app.groovy")

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

    @Test
    void testCacheExpiryEdgeCase() {
        def app = new HubitatAppFacade("app.groovy")

        // Exactly 24 hours ago (should be invalid, >= comparison)
        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        ]
        assert !app.isCacheValid()

        // Just under 24 hours (should be valid)
        app.state.smartirCache = [
            lastFetched: System.currentTimeMillis() - (24 * 60 * 60 * 1000 - 1000)
        ]
        assert app.isCacheValid()
    }

    @Test
    void testSaveConfigToDeviceSuccess() {
        def app = new HubitatAppFacade("app.groovy")

        // Setup mock device
        def mockDevice = new MockIrDevice()
        app.binding.setVariable("irDevice", mockDevice)

        // Use real protocol detection result
        def detectedModel = app.matchCodeToModel(TestCodes.FUJITSU_COOL_24_AUTO, "Fujitsu")

        // Setup wizard state
        app.state.wizardState = [
            detectedModel: detectedModel
        ]

        def result = app.saveConfigToDevice()

        assert result == true
        assert mockDevice.savedConfig != null
        assert mockDevice.savedConfig.manufacturer != null
        assert mockDevice.savedConfig.offCommand != null
        assert mockDevice.savedConfig.commands != null
    }

    @Test
    void testSaveConfigToDeviceNoModel() {
        def app = new HubitatAppFacade("app.groovy")

        app.state.wizardState = [:]  // No detected model

        def result = app.saveConfigToDevice()

        assert result == false
    }

    @Test
    void testDeviceHasHvacSupport() {
        def app = new HubitatAppFacade("app.groovy")

        // Device with HVAC support
        def supportedDevice = new MockIrDevice()
        assert app.deviceHasHvacSupport(supportedDevice)

        // Device without HVAC support
        def unsupportedDevice = new Object()
        assert !app.deviceHasHvacSupport(unsupportedDevice)
    }

}

/**
 * Mock IR device for testing
 */
class MockIrDevice {
    Map savedConfig = null
    List<String> events = []
    String displayName = "MockIrDevice"

    void setHvacConfig(Map config) {
        savedConfig = config
    }

    boolean hasCommand(String commandName) {
        return commandName == "setHvacConfig" || commandName == "learnIrCode"
    }

    void learnIrCode(String callback) {
        // Mock implementation
    }
}
