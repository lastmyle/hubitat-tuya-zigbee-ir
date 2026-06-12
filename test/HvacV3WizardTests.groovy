import static org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the v3 HVAC Setup Wizard App (app.groovy).
 *
 * v3 uses the Maestro /api/v3/* SmartIR-backed endpoints. The user picks
 * a manufacturer then a model; the wizard fetches the recorded command
 * set and stores it on the driver. No protocol identification from a
 * learned code — the learn step exists only to confirm the IR blaster
 * is wired and pointing at the unit.
 *
 * httpGet is stubbed per test by overriding the binding variable.
 */
class HvacV3WizardTests {

    private HubitatAppFacade newApp() {
        return new HubitatAppFacade("app.groovy")
    }

    private void stubHttpGet(HubitatAppFacade app, Closure handler) {
        app.app.binding.setVariable("httpGet", handler)
    }

    // ------------------------------------------------------------------
    // transformV3Commands — pure function
    // ------------------------------------------------------------------

    @Test
    void transformV3Commands_returnsEmptyForEmptyInput() {
        def app = newApp()
        def out = app.transformV3Commands([])
        assertEquals([], out)
    }

    @Test
    void transformV3Commands_offModeBecomesPowerOff() {
        def app = newApp()
        def out = app.transformV3Commands([
            [name: "OFF", mode: "off", fan: null, temperature: null, swing: null, tuya_code: "TUYA_OFF"]
        ])
        assertEquals(1, out.size())
        assertEquals("power_off", out[0].name)
        assertEquals("TUYA_OFF", out[0].tuya_code)
    }

    @Test
    void transformV3Commands_normalCommandIsTempModeFanLowercase() {
        def app = newApp()
        def out = app.transformV3Commands([
            [name: "COOL_AUTO_22", mode: "cool", fan: "auto", temperature: 22, swing: null, tuya_code: "TUYA_22"]
        ])
        assertEquals("22_cool_auto", out[0].name)
        assertEquals("TUYA_22", out[0].tuya_code)
    }

    @Test
    void transformV3Commands_handlesUppercaseModeAndFanFromApi() {
        def app = newApp()
        def out = app.transformV3Commands([
            [name: "HEAT_HIGH_28", mode: "HEAT", fan: "HIGH", temperature: 28, swing: null, tuya_code: "X"]
        ])
        assertEquals("28_heat_high", out[0].name)
    }

    @Test
    void transformV3Commands_skipsEntriesWithoutTuyaCode() {
        def app = newApp()
        def out = app.transformV3Commands([
            [name: "COOL_AUTO_22", mode: "cool", fan: "auto", temperature: 22, tuya_code: null],
            [name: "COOL_AUTO_23", mode: "cool", fan: "auto", temperature: 23, tuya_code: "OK"]
        ])
        assertEquals(1, out.size())
        assertEquals("23_cool_auto", out[0].name)
    }

    @Test
    void transformV3Commands_swingVariantsCollapseFirstWins() {
        def app = newApp()
        def out = app.transformV3Commands([
            [mode: "cool", fan: "auto", temperature: 22, swing: "auto", tuya_code: "SWING_AUTO"],
            [mode: "cool", fan: "auto", temperature: 22, swing: "off", tuya_code: "SWING_OFF"]
        ])
        assertEquals(1, out.size())
        assertEquals("22_cool_auto", out[0].name)
        assertEquals("SWING_AUTO", out[0].tuya_code)
    }

    @Test
    void transformV3Commands_modeWithFanButNoTemp() {
        def app = newApp()
        def out = app.transformV3Commands([
            [mode: "fan", fan: "low", temperature: null, tuya_code: "T"]
        ])
        assertEquals("fan_low", out[0].name)
    }

    @Test
    void transformV3Commands_modeOnly() {
        def app = newApp()
        def out = app.transformV3Commands([
            [mode: "dry", fan: null, temperature: null, tuya_code: "T"]
        ])
        assertEquals("dry", out[0].name)
    }

    // ------------------------------------------------------------------
    // fetchManufacturers — GET /api/v3/manufacturers
    // ------------------------------------------------------------------

    @Test
    void fetchManufacturers_populatesNamesAndCounts() {
        def app = newApp()
        def capturedUri = null
        stubHttpGet(app) { Map params, Closure cb ->
            capturedUri = params.uri
            cb.call(new V3MockHttpResponse(status: 200, data: [
                manufacturers: [
                    [name: "Fujitsu", device_count: 3],
                    [name: "Daikin",  device_count: 5]
                ]
            ]))
        }

        app.fetchManufacturers()

        assertTrue("hit v3 manufacturers endpoint", capturedUri.endsWith("/api/v3/manufacturers"))
        assertEquals(["Fujitsu", "Daikin"], app.state.wizardState.v3.manufacturers)
        assertEquals(3, app.state.wizardState.v3.manufacturerCounts["Fujitsu"])
        assertEquals(5, app.state.wizardState.v3.manufacturerCounts["Daikin"])
    }

    @Test
    void fetchManufacturers_returnsEmptyOnNon200() {
        def app = newApp()
        stubHttpGet(app) { Map params, Closure cb ->
            cb.call(new V3MockHttpResponse(status: 500, data: null))
        }

        app.fetchManufacturers()

        def names = app.state.wizardState?.v3?.manufacturers
        assertTrue("expected null or empty manufacturers list, got: ${names}",
                   names == null || names.isEmpty())
    }

    @Test
    void fetchManufacturers_recordsErrorOnException() {
        def app = newApp()
        stubHttpGet(app) { Map params, Closure cb ->
            throw new RuntimeException("network down")
        }

        app.fetchManufacturers()

        def names = app.state.wizardState.v3.manufacturers
        assertEquals(1, names.size())
        assertTrue(names[0].toLowerCase().contains("error"))
    }

    // ------------------------------------------------------------------
    // fetchDevicesForManufacturer — GET /api/v3/manufacturers/{name}/devices
    // ------------------------------------------------------------------

    @Test
    void fetchDevicesForManufacturer_populatesModelOptions() {
        def app = newApp()
        def capturedUri = null
        stubHttpGet(app) { Map params, Closure cb ->
            capturedUri = params.uri
            cb.call(new V3MockHttpResponse(status: 200, data: [
                manufacturer: "Mitsubishi Electric",
                devices: [
                    [id: 1120, supported_models: ["MSZ-GL25", "MSZ-GL35"], operation_modes: ["cool", "heat"], fan_modes: ["low", "high"], min_temperature: 16, max_temperature: 30],
                    [id: 1121, supported_models: ["MSZ-AP25"],             operation_modes: ["cool"],        fan_modes: ["auto"],         min_temperature: 18, max_temperature: 28]
                ]
            ]))
        }

        app.fetchDevicesForManufacturer("Mitsubishi Electric")

        assertTrue("URL-encodes manufacturer with space",
                   capturedUri.contains("Mitsubishi+Electric") || capturedUri.contains("Mitsubishi%20Electric"))

        def opts = app.state.wizardState.v3.modelOptions
        assertEquals(2, opts.size())
        assertEquals("MSZ-GL25 / MSZ-GL35 (id 1120)", opts["1120"].toString())
        assertEquals("MSZ-AP25 (id 1121)", opts["1121"].toString())
    }

    @Test
    void fetchDevicesForManufacturer_devicesWithoutModelsGetGenericLabel() {
        def app = newApp()
        stubHttpGet(app) { Map params, Closure cb ->
            cb.call(new V3MockHttpResponse(status: 200, data: [
                manufacturer: "Acme",
                devices: [
                    [id: 99, supported_models: [], operation_modes: ["cool"], fan_modes: [], min_temperature: 16, max_temperature: 30]
                ]
            ]))
        }

        app.fetchDevicesForManufacturer("Acme")

        assertEquals("Device 99 (id 99)", app.state.wizardState.v3.modelOptions["99"].toString())
    }

    // ------------------------------------------------------------------
    // fetchDeviceCommands — GET /api/v3/devices/{id}
    // ------------------------------------------------------------------

    @Test
    void fetchDeviceCommands_buildsDetectedModelWithTransformedCommands() {
        def app = newApp()
        def capturedUri = null
        stubHttpGet(app) { Map params, Closure cb ->
            capturedUri = params.uri
            cb.call(new V3MockHttpResponse(status: 200, data: [
                id: 1120,
                manufacturer: "Mitsubishi Electric",
                supported_models: ["MSZ-GL25", "MSZ-GL35"],
                supported_controller: "Broadlink",
                operation_modes: ["cool", "heat"],
                fan_modes: ["low", "high"],
                swing_modes: null,
                min_temperature: 16,
                max_temperature: 30,
                commands: [
                    [name: "OFF",          mode: "off",  fan: null,   temperature: null, swing: null, tuya_code: "OFF_CODE"],
                    [name: "COOL_AUTO_22", mode: "cool", fan: "auto", temperature: 22,   swing: null, tuya_code: "COOL22"]
                ]
            ]))
        }

        def detected = app.fetchDeviceCommands(1120)

        assertTrue("hit v3 device endpoint", capturedUri.endsWith("/api/v3/devices/1120"))
        assertNotNull(detected)
        assertEquals(1120, detected.deviceId)
        assertEquals("Mitsubishi Electric", detected.manufacturer)
        assertEquals("MSZ-GL25", detected.model)
        assertEquals(["MSZ-GL25", "MSZ-GL35"], detected.modelData.supportedModels)
        assertEquals(["cool", "heat"], detected.modelData.operationModes)
        assertEquals(16, detected.modelData.minTemperature)
        assertEquals(30, detected.modelData.maxTemperature)

        def cmds = detected.modelData.commands
        assertEquals(2, cmds.size())
        assertEquals("power_off",    cmds[0].name)
        assertEquals("OFF_CODE",     cmds[0].tuya_code)
        assertEquals("22_cool_auto", cmds[1].name)
        assertEquals("COOL22",       cmds[1].tuya_code)
    }

    @Test
    void fetchDeviceCommands_returnsNullOnApiError() {
        def app = newApp()
        stubHttpGet(app) { Map params, Closure cb ->
            cb.call(new V3MockHttpResponse(status: 404, data: null))
        }

        assertNull(app.fetchDeviceCommands(999))
    }

    @Test
    void fetchDeviceCommands_returnsNullForMissingId() {
        def app = newApp()
        assertNull(app.fetchDeviceCommands(null))
    }

    @Test
    void fetchDeviceCommands_fallsBackToSyntheticModelWhenNoSupportedModels() {
        def app = newApp()
        stubHttpGet(app) { Map params, Closure cb ->
            cb.call(new V3MockHttpResponse(status: 200, data: [
                id: 7, manufacturer: "Acme", supported_models: [],
                operation_modes: [], fan_modes: [], min_temperature: 16, max_temperature: 30,
                commands: []
            ]))
        }

        def detected = app.fetchDeviceCommands(7)

        assertEquals("device-7", detected.model.toString())
    }

    // ------------------------------------------------------------------
    // codeLearnedHandler — verification only in v3
    // ------------------------------------------------------------------

    @Test
    void codeLearnedHandler_storesCodeAndMarksReady() {
        def app = newApp()
        app.codeLearnedHandler(new V3MockEvent(value: "ABCDEF"))

        assertEquals("ABCDEF", app.state.wizardState.learnedCode)
        assertEquals(false, app.state.wizardState.learningInProgress)
        assertEquals(true, app.state.wizardState.readyForNextPage)
    }

    @Test
    void codeLearnedHandler_doesNotPopulateDetectedModelInV3() {
        def app = newApp()
        app.codeLearnedHandler(new V3MockEvent(value: "ABCDEF"))

        // v3 picks the model from the user's brand+model selection, not
        // from a learned code. The handler must not fabricate one.
        assertNull(app.state.wizardState.v3?.detectedModel)
        assertNull(app.state.wizardState.detectedModel)
    }

    @Test
    void codeLearnedHandler_emptyCodeMarksFailure() {
        def app = newApp()
        app.codeLearnedHandler(new V3MockEvent(value: ""))

        assertTrue(app.state.wizardState.learningStatus?.toLowerCase()?.contains("failed"))
        assertEquals(false, app.state.wizardState.learningInProgress)
    }

    // ------------------------------------------------------------------
    // appButtonHandler
    // ------------------------------------------------------------------

    @Test
    void appButtonHandler_refreshManufacturers_clearsAndRefetches() {
        def app = newApp()
        app.state.wizardState = [v3: [manufacturers: ["stale"], manufacturerCounts: [stale: 1]]]

        def calls = 0
        stubHttpGet(app) { Map params, Closure cb ->
            calls++
            cb.call(new V3MockHttpResponse(status: 200, data: [manufacturers: [[name: "Fresh", device_count: 2]]]))
        }

        app.appButtonHandler("refreshManufacturers")

        assertEquals(1, calls)
        assertEquals(["Fresh"], app.state.wizardState.v3.manufacturers)
    }

    @Test
    void appButtonHandler_loadCommandsBtn_populatesDetectedModel() {
        def app = newApp()
        app.settings.selectedDeviceId = "1120"
        stubHttpGet(app) { Map params, Closure cb ->
            cb.call(new V3MockHttpResponse(status: 200, data: [
                id: 1120, manufacturer: "Mitsubishi", supported_models: ["MSZ-GL25"],
                operation_modes: ["cool"], fan_modes: ["auto"],
                min_temperature: 16, max_temperature: 30,
                commands: [[mode: "off", fan: null, temperature: null, tuya_code: "T"]]
            ]))
        }

        app.appButtonHandler("loadCommandsBtn")

        def detected = app.state.wizardState.v3.detectedModel
        assertNotNull(detected)
        assertEquals(1120, detected.deviceId)
        assertEquals("MSZ-GL25", detected.model)
        assertTrue(app.state.wizardState.v3.loadStatus.toLowerCase().contains("loaded"))
    }

    @Test
    void appButtonHandler_loadCommandsBtn_recordsFailureWhenApiReturnsError() {
        def app = newApp()
        app.settings.selectedDeviceId = "999"
        stubHttpGet(app) { Map params, Closure cb ->
            cb.call(new V3MockHttpResponse(status: 404, data: null))
        }

        app.appButtonHandler("loadCommandsBtn")

        assertNull(app.state.wizardState.v3.detectedModel)
        assertTrue(app.state.wizardState.v3.loadStatus.toLowerCase().contains("failed"))
    }

    @Test
    void appButtonHandler_skipLearn_marksReady() {
        def app = newApp()
        app.appButtonHandler("skipLearn")

        assertEquals(true, app.state.wizardState.readyForNextPage)
        assertEquals("Skipped", app.state.wizardState.learningStatus)
    }

    @Test
    void appButtonHandler_reconfigureNow_clearsWizardState() {
        def app = newApp()
        app.state.wizardState = [v3: [manufacturers: ["X"], detectedModel: [foo: 1]], learnedCode: "abc"]

        app.appButtonHandler("reconfigureNow")

        assertNotNull(app.state.wizardState)
        assertNotNull(app.state.wizardState.v3)
        assertNull(app.state.wizardState.v3.manufacturers)
        assertNull(app.state.wizardState.v3.detectedModel)
        assertNull(app.state.wizardState.learnedCode)
    }

    @Test
    void appButtonHandler_testPowerOff_forwardsToDevice() {
        def app = newApp()
        def device = new V3MockDevice()
        app.app.binding.setVariable("irDevice", device)

        app.appButtonHandler("testPowerOff")

        assertEquals(["power_off"], device.commandsSent)
    }

    @Test
    void appButtonHandler_testCool24Auto_forwardsToDevice() {
        def app = newApp()
        def device = new V3MockDevice()
        app.app.binding.setVariable("irDevice", device)

        app.appButtonHandler("testCool24Auto")

        assertEquals(["24_cool_auto"], device.commandsSent)
    }

    @Test
    void appButtonHandler_unknownButton_logsWarning() {
        def app = newApp()
        app.appButtonHandler("doesNotExist")
        assertTrue(app.log.getOutput().contains("Unknown button"))
    }

    // ------------------------------------------------------------------
    // saveConfigToDevice
    // ------------------------------------------------------------------

    @Test
    void saveConfigToDevice_returnsFalseWhenNoDetectedModel() {
        def app = newApp()
        app.state.wizardState = [v3: [:]]
        // irDevice must exist so app.label/updateLabel branches do not NPE;
        // but saveConfigToDevice should return early before touching it.
        assertFalse(app.saveConfigToDevice())
    }

    @Test
    void saveConfigToDevice_writesV3ShapedConfigToDevice() {
        def app = newApp()
        def device = new V3MockDevice()
        app.app.binding.setVariable("irDevice", device)
        // Mock app.updateLabel (Hubitat-only API)
        app.app.metaClass.app = [updateLabel: { String s -> device.lastLabel = s }, label: ""]

        app.state.wizardState = [v3: [detectedModel: [
            deviceId: 1120,
            manufacturer: "Mitsubishi Electric",
            model: "MSZ-GL25",
            modelData: [
                supportedModels: ["MSZ-GL25"],
                commands: [[name: "power_off", tuya_code: "T"]],
                minTemperature: 16,
                maxTemperature: 30,
                operationModes: ["cool", "heat"],
                fanModes: ["auto"]
            ]
        ]]]

        assertTrue(app.saveConfigToDevice())
        assertNotNull(device.savedConfig)
        assertEquals("MSZ-GL25", device.savedConfig.model)
        assertEquals("Mitsubishi Electric", device.savedConfig.brand)
        assertEquals(1, device.savedConfig.commands.size())
        assertEquals("power_off", device.savedConfig.commands[0].name)
    }

    // ------------------------------------------------------------------
    // deviceHasHvacSupport
    // ------------------------------------------------------------------

    @Test
    void deviceHasHvacSupport_trueForDeviceWithSetHvacConfig() {
        def app = newApp()
        assertTrue(app.deviceHasHvacSupport(new V3MockDevice()))
    }

    @Test
    void deviceHasHvacSupport_falseForUnrelatedObject() {
        def app = newApp()
        assertFalse(app.deviceHasHvacSupport(new Object()))
    }
}

/**
 * Minimal mock IR device for v3 tests.
 */
class V3MockDevice {
    String displayName = "MockIrDevice"
    Map savedConfig = null
    List<String> commandsSent = []
    String lastLabel = null

    boolean hasCommand(String name) {
        return name in ["setHvacConfig", "hvacSendCommandName", "learn"]
    }

    void setHvacConfig(Map config) {
        savedConfig = config
    }

    void hvacSendCommandName(String name) {
        commandsSent << name
    }

    void learn(String tag) {}
}

/**
 * Local mock event class to keep this file self-contained.
 */
class V3MockEvent {
    def device
    String name = "lastLearnedCode"
    def value
    Date date = new Date()
}

/**
 * Local stand-in for HubitatAppFacade.MockHttpResponse so this file
 * compiles even when the facade is not on the test runner's classpath.
 */
class V3MockHttpResponse {
    int status
    Object data
    Map headers = [:]
}
