import static org.junit.Assert.*

import org.junit.Test
import org.junit.Before

/**
 * Tests for the driver's HVAC command storage and lookup paths.
 *
 * Covers:
 *  - addHvacCommands accepts dual-name (v1 `name` + v3 `name_v3`)
 *    records and preserves all fields verbatim, including unknown
 *    metadata the installer might add in the future.
 *  - addHvacCommands is idempotent on re-provisioning: a second add of
 *    the same `name` replaces the prior record in-place rather than
 *    duplicating.
 *  - hvacSendCommandName (v1 entry point) continues to match by `name`
 *    case-insensitively, including on records that also carry name_v3.
 *  - hvacSendCommandNameV3 matches by `name_v3` verbatim and
 *    case-sensitively, ignoring records without a name_v3, and updates
 *    state from the record's structured axes (mode/fan/temperature).
 */
class HvacCommandStorageTests {
    private HubitatDriverFacade driver

    @Before
    void setUp() {
        driver = new HubitatDriverFacade()
    }

    private void seedCommands(List records) {
        driver.state.hvacConfigured = true
        driver.state.hvacCommands = records
        driver.state.hvacCurrentState = [mode: 'off', temp: null, fan: null]
    }

    private Map findEvent(String name) {
        return driver.sentEvents.reverse().find { it.name == name }
    }

    // ------------------------------------------------------------------
    // Storage — addHvacCommands preserves all fields and dedupes by name
    // ------------------------------------------------------------------

    @Test
    void addHvacCommands_preservesDualNameAndStructuredAxes() {
        driver.driver.addHvacCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", mode: "cool", fan: "high",
             temperature: 22, swing: null, tuya_code: "TUYA_22"]
        ])

        def stored = driver.state.hvacCommands
        assertEquals(1, stored.size())
        assertEquals("22_cool_high", stored[0].name)
        assertEquals("COOL_HIGH_22", stored[0].name_v3)
        assertEquals("cool", stored[0].mode)
        assertEquals("high", stored[0].fan)
        assertEquals(22, stored[0].temperature)
        assertEquals("TUYA_22", stored[0].tuya_code)
        assertTrue(driver.state.hvacConfigured)
    }

    @Test
    void addHvacCommands_preservesUnknownFutureFields() {
        driver.driver.addHvacCommands([
            [name: "power_off", name_v3: "OFF", tuya_code: "T",
             futureField: "abc", swing_modes: ["auto", "static"]]
        ])

        def stored = driver.state.hvacCommands[0]
        assertEquals("abc", stored.futureField)
        assertEquals(["auto", "static"], stored.swing_modes)
    }

    @Test
    void addHvacCommands_replacesByNameOnReprovision() {
        driver.driver.addHvacCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", tuya_code: "OLD"]
        ])
        driver.driver.addHvacCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", tuya_code: "NEW"]
        ])

        def stored = driver.state.hvacCommands
        assertEquals("expected dedup by name on re-provision", 1, stored.size())
        assertEquals("NEW", stored[0].tuya_code)
    }

    @Test
    void addHvacCommands_appendsNewNamesAcrossBatches() {
        driver.driver.addHvacCommands([
            [name: "power_off", tuya_code: "T1"]
        ])
        driver.driver.addHvacCommands([
            [name: "22_cool_high", tuya_code: "T2"],
            [name: "23_cool_high", tuya_code: "T3"]
        ])

        def stored = driver.state.hvacCommands
        assertEquals(3, stored.size())
        assertEquals(["power_off", "22_cool_high", "23_cool_high"], stored*.name)
    }

    @Test
    void addHvacCommands_partialReplaceLeavesOtherRecordsUntouched() {
        driver.driver.addHvacCommands([
            [name: "power_off",    tuya_code: "OFF_OLD"],
            [name: "22_cool_high", tuya_code: "C22_OLD"],
            [name: "23_cool_high", tuya_code: "C23_OLD"]
        ])
        driver.driver.addHvacCommands([
            [name: "22_cool_high", tuya_code: "C22_NEW"]
        ])

        def stored = driver.state.hvacCommands
        assertEquals(3, stored.size())
        assertEquals("OFF_OLD", stored.find { it.name == "power_off" }.tuya_code)
        assertEquals("C22_NEW", stored.find { it.name == "22_cool_high" }.tuya_code)
        assertEquals("C23_OLD", stored.find { it.name == "23_cool_high" }.tuya_code)
    }

    @Test
    void addHvacCommands_rejectsEmptyList() {
        driver.driver.addHvacCommands([])
        assertTrue(driver.log.out.toString().contains("Invalid commands"))
        assertNull(driver.state.hvacCommands)
    }

    @Test
    void addHvacCommandsBase64_decodesAndStores() {
        String json = '[{"name":"22_cool_high","name_v3":"COOL_HIGH_22","tuya_code":"T"}]'
        String b64 = json.bytes.encodeBase64().toString()

        driver.invokeMethod("addHvacCommandsBase64", [b64])

        def stored = driver.state.hvacCommands
        assertEquals(1, stored.size())
        assertEquals("22_cool_high", stored[0].name)
        assertEquals("COOL_HIGH_22", stored[0].name_v3)
    }

    // ------------------------------------------------------------------
    // setHvacConfig (Map) — bulk write
    // ------------------------------------------------------------------

    @Test
    void setHvacConfigMap_storesDualNameCommandsVerbatim() {
        driver.invokeMethod("setHvacConfig", [[
            model: "MSZ-GL25",
            brand: "Mitsubishi Electric",
            commands: [
                [name: "power_off",    name_v3: "OFF",          tuya_code: "T0"],
                [name: "22_cool_high", name_v3: "COOL_HIGH_22", mode: "cool",
                 fan: "high", temperature: 22, tuya_code: "T1"]
            ]
        ]])

        assertEquals("MSZ-GL25", driver.state.hvacModel)
        assertEquals("Mitsubishi Electric", driver.state.hvacBrand)
        assertEquals(2, driver.state.hvacCommands.size())
        assertEquals("OFF", driver.state.hvacCommands[0].name_v3)
        assertEquals("COOL_HIGH_22", driver.state.hvacCommands[1].name_v3)
        assertTrue(driver.state.hvacConfigured)
        // Model and brand are exposed as device attributes so Maker API
        // readers (maestro-installer) can see them without reading state.
        assertEquals("MSZ-GL25", findEvent("hvacModel").value)
        assertEquals("Mitsubishi Electric", findEvent("hvacBrand").value)
    }

    @Test
    void setHvacConfigString_metadataOnly_emitsModelAndBrand() {
        driver.invokeMethod("setHvacConfig", [
            '{"model":"MSZ-GL25","brand":"Mitsubishi Electric"}'
        ])

        assertEquals("MSZ-GL25", driver.state.hvacModel)
        assertEquals("Mitsubishi Electric", driver.state.hvacBrand)
        assertEquals("MSZ-GL25", findEvent("hvacModel").value)
        assertEquals("Mitsubishi Electric", findEvent("hvacBrand").value)
        // Metadata-only: commands arrive later via addHvacCommandsBase64.
        assertEquals("false", findEvent("hvacConfigured").value)
    }

    @Test
    void clearHvacConfig_blanksModelAndBrandAttributes() {
        driver.invokeMethod("setHvacConfig", [[
            model: "MSZ-GL25",
            brand: "Mitsubishi Electric",
            commands: [[name: "power_off", tuya_code: "T0"]]
        ]])

        driver.invokeMethod("clearHvacConfig", null)

        assertNull(driver.state.hvacModel)
        assertNull(driver.state.hvacBrand)
        assertEquals("", findEvent("hvacModel").value)
        assertEquals("", findEvent("hvacBrand").value)
    }

    // ------------------------------------------------------------------
    // Lookup — hvacSendCommandName (v1) keeps its existing behaviour
    // ------------------------------------------------------------------

    @Test
    void hvacSendCommandName_findsByNameOnDualNameRecord() {
        seedCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", mode: "cool",
             fan: "high", temperature: 22, tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandName", ["22_cool_high"])

        assertEquals([mode: "cool", temp: 22, fan: "high"], driver.state.hvacCurrentState)
        assertEquals("22_cool_high", findEvent("hvacCommand").value)
    }

    @Test
    void hvacSendCommandName_caseInsensitiveLookupStillWorks() {
        seedCommands([
            [name: "22_cool_high", tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandName", ["22_COOL_HIGH"])

        // No error — existing v1 contract preserved.
        assertFalse("v1 entry point must remain case-insensitive",
                    driver.log.out.toString().contains("not found"))
    }

    @Test
    void hvacSendCommandName_doesNotMatchByNameV3() {
        seedCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandName", ["COOL_HIGH_22"])

        assertTrue("v1 lookup must not fall back to name_v3",
                   driver.log.out.toString().contains("not found"))
    }

    // ------------------------------------------------------------------
    // Lookup — hvacSendCommandNameV3 (v3) strict, structured-axes state
    // ------------------------------------------------------------------

    @Test
    void hvacSendCommandNameV3_findsByNameV3AndUpdatesStateFromAxes() {
        seedCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", mode: "cool",
             fan: "high", temperature: 22, tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandNameV3", ["COOL_HIGH_22"])

        assertEquals([mode: "cool", temp: 22, fan: "high"], driver.state.hvacCurrentState)
        assertEquals("cool", findEvent("hvacMode").value)
        assertEquals(22,     findEvent("hvacTemperature").value)
        assertEquals("high", findEvent("hvacFanSpeed").value)
    }

    @Test
    void hvacSendCommandNameV3_emitsV1NameOnHvacCommandEventForRuleCompat() {
        seedCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", mode: "cool",
             fan: "high", temperature: 22, tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandNameV3", ["COOL_HIGH_22"])

        // Rules listening on hvacCommand == "22_cool_high" must keep working.
        assertEquals("22_cool_high", findEvent("hvacCommand").value)
    }

    @Test
    void hvacSendCommandNameV3_emitsV3NameWhenV1NameIsMissing() {
        seedCommands([
            [name_v3: "COOL_HIGH_22_SWING_STATIC", mode: "cool",
             fan: "high", temperature: 22, swing: "static", tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandNameV3", ["COOL_HIGH_22_SWING_STATIC"])

        assertEquals("COOL_HIGH_22_SWING_STATIC", findEvent("hvacCommand").value)
    }

    @Test
    void hvacSendCommandNameV3_isCaseSensitive() {
        seedCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandNameV3", ["cool_high_22"])

        assertTrue("v3 lookup must be strict — no case folding",
                   driver.log.out.toString().contains("not found"))
    }

    @Test
    void hvacSendCommandNameV3_skipsRecordsWithoutNameV3() {
        seedCommands([
            [name: "22_cool_high", tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandNameV3", ["COOL_HIGH_22"])

        assertTrue(driver.log.out.toString().contains("not found"))
    }

    @Test
    void hvacSendCommandNameV3_offModeUpdatesStateCorrectly() {
        seedCommands([
            [name: "power_off", name_v3: "OFF", mode: "off",
             fan: null, temperature: null, tuya_code: "T0"]
        ])

        driver.invokeMethod("hvacSendCommandNameV3", ["OFF"])

        assertEquals([mode: "off", temp: null, fan: null], driver.state.hvacCurrentState)
        assertEquals("off",  findEvent("hvacMode").value)
        assertEquals(0,      findEvent("hvacTemperature").value)
        assertEquals("auto", findEvent("hvacFanSpeed").value)
        assertEquals("power_off", findEvent("hvacCommand").value)
    }

    @Test
    void hvacSendCommandNameV3_missingAxesSkipsStateUpdateButStillSends() {
        seedCommands([
            // Malformed: name_v3 present but no structured axes
            [name_v3: "WEIRD_NAME", tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandNameV3", ["WEIRD_NAME"])

        // State left at the seed value because axes were missing
        assertEquals([mode: "off", temp: null, fan: null], driver.state.hvacCurrentState)
        // hvacCommand event still fires so callers know it ran
        assertEquals("WEIRD_NAME", findEvent("hvacCommand").value)
    }

    @Test
    void hvacSendCommandNameV3_errorsWhenNotConfigured() {
        // Don't seed — leave hvacConfigured unset
        driver.invokeMethod("hvacSendCommandNameV3", ["COOL_HIGH_22"])

        assertTrue(driver.log.out.toString().contains("not configured"))
    }

    @Test
    void hvacSendCommandNameV3_errorsWhenCommandMissing() {
        seedCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", tuya_code: "T1"]
        ])

        driver.invokeMethod("hvacSendCommandNameV3", ["HEAT_LOW_18"])

        assertTrue(driver.log.out.toString().contains("not found"))
    }

    // ------------------------------------------------------------------
    // getHvacConfig — returns records verbatim
    // ------------------------------------------------------------------

    @Test
    void getHvacConfig_returnsDualNameRecordsVerbatim() {
        seedCommands([
            [name: "22_cool_high", name_v3: "COOL_HIGH_22", mode: "cool",
             fan: "high", temperature: 22, tuya_code: "T1"]
        ])
        driver.state.hvacModel = "MSZ-GL25"
        driver.state.hvacBrand = "Mitsubishi"

        def config = driver.invokeMethod("getHvacConfig", null)

        assertEquals("MSZ-GL25", config.model)
        assertEquals("Mitsubishi", config.brand)
        assertEquals(1, config.commands.size())
        assertEquals("COOL_HIGH_22", config.commands[0].name_v3)
    }
}
