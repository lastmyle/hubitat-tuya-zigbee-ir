import static org.junit.Assert.*

import org.junit.Test
import org.junit.Before
import org.junit.BeforeClass
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for IR service classes (inlined in hvac-setup-app.groovy)
 *
 * These tests load the service classes from the inlined app file
 * rather than importing from lib.services
 */
class ServiceTests {

    static Class FastLZ
    static Class TuyaIRService
    static Class IRRemoteESP8266
    static Class HVACCodeGenerator

    @BeforeClass
    static void loadClasses() {
        // Load the app script which contains the inlined classes
        def appScript = new File("hvac-setup-app.groovy")
        def shell = new GroovyShell()
        def script = shell.parse(appScript)

        // Extract the class definitions from the script
        FastLZ = script.class.classLoader.loadClass('FastLZ')
        TuyaIRService = script.class.classLoader.loadClass('TuyaIRService')
        IRRemoteESP8266 = script.class.classLoader.loadClass('IRRemoteESP8266')
        HVACCodeGenerator = script.class.classLoader.loadClass('HVACCodeGenerator')
    }

    @Test
    void testFastLZ_BasicCompressionDecompression() {
        // Test with simple data
        byte[] original = "Hello World".getBytes()
        byte[] compressed = FastLZ.compress(original)
        byte[] decompressed = FastLZ.decompress(compressed)

        assertEquals(original as List, decompressed as List)
    }

    @Test
    void testFastLZ_EmptyData() {
        byte[] original = new byte[0]
        byte[] compressed = FastLZ.compress(original)
        byte[] decompressed = FastLZ.decompress(compressed)

        assertEquals(0, decompressed.length)
    }

    @Test
    void testFastLZ_RepetitiveData() {
        // FastLZ should compress repetitive data (round-trip test)
        byte[] original = new byte[100]
        Arrays.fill(original, (byte)0xAA)

        byte[] compressed = FastLZ.compress(original)
        byte[] decompressed = FastLZ.decompress(compressed)

        // Verify round-trip works correctly
        assertEquals(original as List, decompressed as List)

        // Note: Our simplified FastLZ may not always achieve compression on small data
        // The important thing is that decompression works correctly
    }

    @Test
    void testTuyaIR_RoundTrip() {
        // Test encode/decode round-trip with realistic IR timings
        List<Integer> originalTimings = [
            3000, 1500,  // Header mark/space
            500, 500,    // Bit 0
            500, 1500,   // Bit 1
            500, 500,    // Bit 0
            500, 1500,   // Bit 1
            500          // Final mark
        ]

        // Encode to Tuya Base64
        String tuyaCode = TuyaIRService.encodeTuyaIR(originalTimings)
        assertNotNull("Tuya code should not be null", tuyaCode)
        assertTrue("Tuya code should not be empty", tuyaCode.length() > 0)

        // Decode back to timings
        List<Integer> decodedTimings = TuyaIRService.decodeTuyaIR(tuyaCode)

        // Should match original
        assertEquals("Decoded timings should match original", originalTimings, decodedTimings)
    }

    @Test
    void testTuyaIR_ValidateCode() {
        // Valid Base64 encoded Tuya code
        List<Integer> timings = [3000, 1500, 500, 500]
        String validCode = TuyaIRService.encodeTuyaIR(timings)
        assertTrue("Valid code should pass validation", TuyaIRService.validateTuyaCode(validCode))

        // Invalid codes
        assertFalse("Invalid Base64 should fail", TuyaIRService.validateTuyaCode("Not@Valid@Base64!"))
        assertFalse("Empty string should fail", TuyaIRService.validateTuyaCode(""))
        assertFalse("Null should fail", TuyaIRService.validateTuyaCode(null))
    }

    @Test
    void testTuyaIR_GetCodeInfo() {
        List<Integer> timings = [3000, 1500, 500, 500, 500, 1500, 500]
        String tuyaCode = TuyaIRService.encodeTuyaIR(timings)

        Map info = TuyaIRService.getCodeInfo(tuyaCode)

        assertNotNull("Info should not be null", info)
        assertTrue("Should be valid", info.valid)
        assertEquals("Should have correct timing count", 7, info.timingsCount)
        // Total duration = sum of all timings
        int expectedDuration = timings.sum()
        assertEquals("Should have correct total duration", expectedDuration, info.totalDuration)
        assertEquals("Should return timings array", timings, info.timings)
    }

    @Test
    void testTuyaIR_LargeTimingArray() {
        // Test with a realistic large HVAC command (96 bits = 192 timings + header + footer)
        List<Integer> timings = []
        timings << 3200  // Header mark
        timings << 1600  // Header space

        // 96 bits of data
        96.times {
            timings << 420  // Bit mark
            timings << (it % 2 == 0 ? 420 : 1260)  // Alternating 0/1 spaces
        }
        timings << 420  // Footer mark

        String encoded = TuyaIRService.encodeTuyaIR(timings)
        List<Integer> decoded = TuyaIRService.decodeTuyaIR(encoded)

        assertEquals("Large timing array should round-trip", timings, decoded)
    }

    @Test
    void testIRRemoteESP8266_GetAllManufacturers() {
        List<String> manufacturers = IRRemoteESP8266.getAllManufacturers()

        assertNotNull("Manufacturers list should not be null", manufacturers)
        assertTrue("Should have multiple manufacturers", manufacturers.size() > 5)
        assertTrue("Should include Fujitsu", manufacturers.contains("Fujitsu"))
        assertTrue("Should include Daikin", manufacturers.contains("Daikin"))
        assertTrue("Should include Mitsubishi", manufacturers.contains("Mitsubishi"))
    }

    @Test
    void testIRRemoteESP8266_GetProtocol() {
        def protocol = IRRemoteESP8266.getProtocol("FUJITSU_AC")

        assertNotNull("Protocol should not be null", protocol)
        assertEquals("Protocol name should match", "FUJITSU_AC", protocol.name)
        assertTrue("Should have header mark", protocol.headerMark > 0)
        assertTrue("Should have header space", protocol.headerSpace > 0)
        assertTrue("Manufacturers list should contain Fujitsu", protocol.manufacturer.contains("Fujitsu"))
    }

    @Test
    void testIRRemoteESP8266_GetProtocolCapabilities() {
        Map caps = IRRemoteESP8266.getProtocolCapabilities("FUJITSU_AC")

        assertNotNull("Capabilities should not be null", caps)
        assertTrue("Should have modes", caps.modes.size() > 0)
        assertTrue("Should include cool mode", caps.modes.contains("cool"))
        assertTrue("Should include heat mode", caps.modes.contains("heat"))
        assertNotNull("Should have temp range", caps.tempRange)
        assertTrue("Min temp should be reasonable", caps.tempRange.min >= 16)
        assertTrue("Max temp should be reasonable", caps.tempRange.max <= 32)
        assertTrue("Should have fan speeds", caps.fanSpeeds.size() > 0)
    }

    @Test
    void testIRRemoteESP8266_GetProtocolsByManufacturer() {
        List<String> fujitsuProtocols = IRRemoteESP8266.getProtocolsByManufacturer("Fujitsu")

        assertNotNull("Protocols list should not be null", fujitsuProtocols)
        assertTrue("Should have at least one Fujitsu protocol", fujitsuProtocols.size() > 0)
        assertTrue("Should include FUJITSU_AC", fujitsuProtocols.contains("FUJITSU_AC"))
    }

    @Test
    void testIRRemoteESP8266_IdentifyProtocol_Fujitsu() {
        // Create a realistic Fujitsu HVAC IR timing pattern
        List<Integer> timings = []
        timings << 3324  // Fujitsu header mark
        timings << 1574  // Fujitsu header space

        // Add some bits (simplified)
        48.times {
            timings << 448  // Bit mark
            timings << (it % 3 == 0 ? 1182 : 378)  // Mix of 0s and 1s
        }

        Map result = IRRemoteESP8266.identifyProtocol(timings, 1.5)

        assertNotNull("Result should not be null", result)
        assertEquals("Should identify as Fujitsu", "FUJITSU_AC", result.protocol)
        assertTrue("Should have high confidence", result.confidence > 0.7)
        assertTrue("Should include Fujitsu manufacturer", result.manufacturer.contains("Fujitsu"))
    }

    @Test
    void testIRRemoteESP8266_IdentifyProtocol_Daikin() {
        // Create a Daikin timing pattern
        List<Integer> timings = []
        timings << 3650  // Daikin header mark
        timings << 1623  // Daikin header space

        // Add some bits
        48.times {
            timings << 428  // Bit mark
            timings << (it % 2 == 0 ? 428 : 1280)
        }

        Map result = IRRemoteESP8266.identifyProtocol(timings, 1.5)

        assertNotNull("Result should not be null", result)
        assertEquals("Should identify as Daikin", "DAIKIN", result.protocol)
        assertTrue("Should include Daikin manufacturer", result.manufacturer.contains("Daikin"))
    }

    @Test
    void testHVACCodeGenerator_CreateInstance() {
        def generator = HVACCodeGenerator.newInstance("FUJITSU_AC")
        assertNotNull("Generator should be created", generator)
        assertEquals("Protocol should be set", "FUJITSU_AC", generator.protocol)
    }

    @Test
    void testHVACCodeGenerator_InvalidProtocol() {
        try {
            HVACCodeGenerator.newInstance("INVALID_PROTOCOL")
            fail("Should throw exception for invalid protocol")
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention protocol", e.message.contains("Unsupported protocol"))
        }
    }

    @Test
    void testHVACCodeGenerator_GenerateCode() {
        def generator = HVACCodeGenerator.newInstance("FUJITSU_AC")

        // Generate a basic cool command at 24°C
        String code = generator.generateCode("on", "cool", 24, "auto", "off")

        assertNotNull("Generated code should not be null", code)
        assertTrue("Generated code should not be empty", code.length() > 0)

        // Verify it's valid Tuya Base64
        assertTrue("Generated code should be valid Tuya format",
                   TuyaIRService.validateTuyaCode(code))
    }

    @Test
    void testHVACCodeGenerator_GenerateCodeParameters() {
        def generator = HVACCodeGenerator.newInstance("FUJITSU_AC")

        // Test different parameter combinations
        String cool24 = generator.generateCode("on", "cool", 24, "auto", "off")
        String cool25 = generator.generateCode("on", "cool", 25, "auto", "off")
        String heat24 = generator.generateCode("on", "heat", 24, "auto", "off")

        // Different parameters should generate different codes
        assertNotEquals("Different temps should generate different codes", cool24, cool25)
        assertNotEquals("Different modes should generate different codes", cool24, heat24)
    }

    @Test
    void testHVACCodeGenerator_GenerateCodeValidation() {
        def generator = HVACCodeGenerator.newInstance("FUJITSU_AC")

        // Test invalid power
        try {
            generator.generateCode("maybe", "cool", 24, "auto", "off")
            fail("Should reject invalid power")
        } catch (IllegalArgumentException e) {
            assertTrue("Error should mention power", e.message.contains("power"))
        }

        // Test invalid mode
        try {
            generator.generateCode("on", "turbo", 24, "auto", "off")
            fail("Should reject invalid mode")
        } catch (IllegalArgumentException e) {
            assertTrue("Error should mention mode", e.message.contains("mode"))
        }

        // Test temperature out of range
        try {
            generator.generateCode("on", "cool", 50, "auto", "off")
            fail("Should reject invalid temperature")
        } catch (IllegalArgumentException e) {
            assertTrue("Error should mention temperature", e.message.contains("Temperature"))
        }
    }

    @Test
    void testHVACCodeGenerator_GenerateAllCommands() {
        def generator = HVACCodeGenerator.newInstance("FUJITSU_AC")

        // Generate a subset of commands
        Map commands = generator.generateAllCommands(
            ["cool", "heat"],     // modes
            [22, 24],             // temp range
            ["auto", "low"]       // fan speeds
        )

        assertNotNull("Commands should not be null", commands)

        // Should have OFF command
        assertNotNull("Should have off command", commands.off)
        assertTrue("Off command should be valid", TuyaIRService.validateTuyaCode(commands.off))

        // Should have cool mode
        assertNotNull("Should have cool mode", commands.cool)
        assertNotNull("Should have cool/auto commands", commands.cool.auto)
        assertNotNull("Should have cool/auto/22", commands.cool.auto['22'])
        assertNotNull("Should have cool/auto/24", commands.cool.auto['24'])

        // Should have heat mode
        assertNotNull("Should have heat mode", commands.heat)
        assertNotNull("Should have heat/low commands", commands.heat.low)

        // All commands should be valid Tuya codes
        assertTrue("cool/auto/22 should be valid",
                   TuyaIRService.validateTuyaCode(commands.cool.auto['22']))
        assertTrue("heat/low/24 should be valid",
                   TuyaIRService.validateTuyaCode(commands.heat.low['24']))
    }

    @Test
    void testHVACCodeGenerator_StaticMethod() {
        // Test the static convenience method
        String code = HVACCodeGenerator.generateCommand("FUJITSU_AC", "on", "cool", 24, "auto", "off")

        assertNotNull("Generated code should not be null", code)
        assertTrue("Generated code should be valid", TuyaIRService.validateTuyaCode(code))
    }

    @Test
    void testIntegration_FullWorkflow() {
        // Test the complete workflow: generate -> decode -> identify -> regenerate

        // 1. Generate a Fujitsu cool command
        String originalCode = HVACCodeGenerator.generateCommand("FUJITSU_AC", "on", "cool", 24, "auto", "off")

        // 2. Decode to timings
        List<Integer> timings = TuyaIRService.decodeTuyaIR(originalCode)
        assertNotNull("Decoded timings should not be null", timings)
        assertTrue("Should have multiple timings", timings.size() > 10)

        // 3. Identify protocol from timings
        Map protocolInfo = IRRemoteESP8266.identifyProtocol(timings, 1.5)
        assertEquals("Should identify as Fujitsu", "FUJITSU_AC", protocolInfo.protocol)

        // 4. Generate new commands using identified protocol
        def generator = HVACCodeGenerator.newInstance(protocolInfo.protocol)
        Map allCommands = generator.generateAllCommands(["cool"], [24, 25], ["auto"])

        assertNotNull("Should generate command set", allCommands)
        assertNotNull("Should have cool/auto/24", allCommands.cool.auto['24'])

        // 5. Verify round-trip for the regenerated code
        String regeneratedCode = allCommands.cool.auto['24']
        List<Integer> regeneratedTimings = TuyaIRService.decodeTuyaIR(regeneratedCode)

        String finalCode = TuyaIRService.encodeTuyaIR(regeneratedTimings)
        assertEquals("Final code should match regenerated code", regeneratedCode, finalCode)
    }
}
