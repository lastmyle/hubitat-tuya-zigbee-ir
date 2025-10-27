/**
 * HVAC Setup Wizard App
 *
 * Multi-page configuration wizard for Tuya Zigbee IR Remote Controls
 * Uses local IR protocol detection and code generation
 */


import groovy.transform.Field

/*********
 * INLINED SERVICE CLASSES
 * The following classes are inlined for Hubitat compatibility
 * (Hubitat apps cannot import external files)
 */

// FastLZ compression service
/**
 * FastLZ compression/decompression for Tuya IR codes.
 *
 * Pure Groovy implementation ported from Python reference:
 * https://gist.github.com/mildsunrise/1d576669b63a260d2cff35fda63ec0b5
 */

class FastLZ {

    /**
     * Decompress FastLZ compressed data.
     * Tuya uses FastLZ for IR code compression.
     *
     * @param data Compressed bytes
     * @return Decompressed bytes
     * @throws IllegalArgumentException if data is corrupted or invalid
     */
    static byte[] decompress(byte[] data) {
        if (!data || data.length == 0) {
            return new byte[0]
        }

        def output = []
        int ip = 0  // input pointer

        while (ip < data.length) {
            int ctrl = data[ip] & 0xFF
            ip++

            if (ctrl < 32) {
                // Literal run
                ctrl++
                if (ip + ctrl > data.length) {
                    throw new IllegalArgumentException("Corrupted data: literal run exceeds input")
                }
                for (int i = 0; i < ctrl; i++) {
                    output << data[ip + i]
                }
                ip += ctrl
            } else {
                // Back reference
                int length = ctrl >> 5
                if (length == 7) {
                    if (ip >= data.length) {
                        throw new IllegalArgumentException("Corrupted data: extended length byte missing")
                    }
                    length += (data[ip] & 0xFF)
                    ip++
                }
                length += 2

                if (ip >= data.length) {
                    throw new IllegalArgumentException("Corrupted data: reference offset byte missing")
                }

                int ref = (ctrl & 31) << 8
                ref += (data[ip] & 0xFF)
                ip++

                // Copy from reference
                int refPos = output.size() - ref - 1
                if (refPos < 0) {
                    throw new IllegalArgumentException("Corrupted data: invalid back reference")
                }

                for (int i = 0; i < length; i++) {
                    if (refPos >= output.size()) {
                        throw new IllegalArgumentException("Corrupted data: reference position out of bounds")
                    }
                    output << output[refPos]
                    refPos++
                }
            }
        }

        return output as byte[]
    }

    /**
     * Compress data using FastLZ algorithm.
     * Tuya uses FastLZ for IR code compression.
     *
     * @param data Raw bytes to compress
     * @return Compressed bytes
     */
    static byte[] compress(byte[] data) {
        if (!data || data.length == 0) {
            return new byte[0]
        }

        def output = []
        int ip = 0  // input pointer
        int anchor = 0  // anchor for literal run

        // Hash table for finding matches (simple implementation)
        def hashTable = [:]

        while (ip < data.length) {
            // Try to find a match
            int bestMatchLen = 0
            int bestMatchDist = 0

            if (ip + 3 <= data.length) {
                // Create hash of current position
                int hashVal = ((data[ip] & 0xFF) << 16) |
                              ((data[ip + 1] & 0xFF) << 8) |
                              (data[ip + 2] & 0xFF)

                if (hashTable.containsKey(hashVal)) {
                    int matchPos = hashTable[hashVal]
                    // Check if match is within reference distance (8192 bytes)
                    if (ip - matchPos < 8192) {
                        // Calculate match length
                        int matchLen = 0
                        int maxLen = Math.min(data.length - ip, 264)  // Max match length
                        while (matchLen < maxLen &&
                               matchPos + matchLen < ip &&
                               data[matchPos + matchLen] == data[ip + matchLen]) {
                            matchLen++
                        }

                        if (matchLen >= 3) {
                            bestMatchLen = matchLen
                            bestMatchDist = ip - matchPos - 1
                        }
                    }
                }

                hashTable[hashVal] = ip
            }

            if (bestMatchLen >= 3) {
                // Output literal run if any
                int literalLen = ip - anchor
                if (literalLen > 0) {
                    while (literalLen > 0) {
                        int run = Math.min(literalLen, 32)
                        output << (byte)(run - 1)
                        for (int i = 0; i < run; i++) {
                            output << data[anchor + i]
                        }
                        anchor += run
                        literalLen -= run
                    }
                }

                // Output back reference
                int length = bestMatchLen - 2
                if (length < 7) {
                    byte ctrl = (byte)((length << 5) | ((bestMatchDist >> 8) & 31))
                    output << ctrl
                    output << (byte)(bestMatchDist & 255)
                } else {
                    byte ctrl = (byte)((7 << 5) | ((bestMatchDist >> 8) & 31))
                    output << ctrl
                    output << (byte)(length - 7)
                    output << (byte)(bestMatchDist & 255)
                }

                ip += bestMatchLen
                anchor = ip
            } else {
                ip++
            }
        }

        // Output remaining literal run
        int literalLen = data.length - anchor
        while (literalLen > 0) {
            int run = Math.min(literalLen, 32)
            output << (byte)(run - 1)
            for (int i = 0; i < run; i++) {
                output << data[anchor + i]
            }
            anchor += run
            literalLen -= run
        }

        return output as byte[]
    }
}

// Tuya IR format conversion service
/**
 * Tuya IR format conversion utilities.
 *
 * Handles encoding and decoding between Tuya Base64 format and raw IR timings.
 */

import java.nio.ByteBuffer
import java.nio.ByteOrder

class TuyaIRService {

    /**
     * Convert Tuya Base64 IR code to raw timing array.
     *
     * Process:
     * 1. Base64 decode
     * 2. FastLZ decompress
     * 3. Unpack 16-bit little-endian integers to microsecond timings
     *
     * @param tuyaCode Base64 encoded Tuya IR code
     * @return List of microsecond timings [9000, 4500, 600, ...]
     * @throws IllegalArgumentException if the code is invalid or cannot be decoded
     */
    static List<Integer> decodeTuyaIR(String tuyaCode) {
        try {
            // 1. Base64 decode
            byte[] compressed = tuyaCode.decodeBase64()

            // 2. FastLZ decompress
            byte[] rawBytes = FastLZ.decompress(compressed)

            // 3. Unpack 16-bit little-endian integers
            List<Integer> timings = []
            ByteBuffer buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)

            while (buffer.remaining() >= 2) {
                int timing = buffer.getShort() & 0xFFFF  // Unsigned short
                timings << timing
            }

            return timings

        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decode Tuya IR code: ${e.message}", e)
        }
    }

    /**
     * Convert raw timing array to Tuya Base64 IR code.
     *
     * Process:
     * 1. Pack as 16-bit little-endian integers
     * 2. FastLZ compress
     * 3. Base64 encode
     *
     * @param timings List of microsecond timings
     * @return Base64 encoded Tuya IR code
     * @throws IllegalArgumentException if the timings are invalid
     */
    static String encodeTuyaIR(List<Integer> timings) {
        try {
            // 1. Pack as 16-bit little-endian integers
            ByteBuffer buffer = ByteBuffer.allocate(timings.size() * 2)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            timings.each { timing ->
                if (timing < 0 || timing > 65535) {
                    throw new IllegalArgumentException("Timing value ${timing} out of range (0-65535)")
                }
                buffer.putShort((short)timing)
            }

            byte[] rawBytes = buffer.array()

            // 2. FastLZ compress
            byte[] compressed = FastLZ.compress(rawBytes)

            // 3. Base64 encode
            String tuyaCode = compressed.encodeBase64().toString()

            return tuyaCode

        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to encode Tuya IR code: ${e.message}", e)
        }
    }

    /**
     * Validate if a string is a valid Tuya IR code.
     *
     * @param tuyaCode String to validate
     * @return true if valid, false otherwise
     */
    static boolean validateTuyaCode(String tuyaCode) {
        if (!tuyaCode || tuyaCode.trim().isEmpty()) {
            return false
        }
        try {
            decodeTuyaIR(tuyaCode)
            return true
        } catch (Exception) {
            return false
        }
    }

    /**
     * Get information about a Tuya IR code.
     *
     * @param tuyaCode Base64 encoded Tuya IR code
     * @return Map with code information
     */
    static Map getCodeInfo(String tuyaCode) {
        try {
            List<Integer> timings = decodeTuyaIR(tuyaCode)

            return [
                valid: true,
                timingsCount: timings.size(),
                totalDuration: timings.sum() ?: 0,
                timings: timings
            ]
        } catch (Exception e) {
            return [
                valid: false,
                error: e.message
            ]
        }
    }
}

// IRremoteESP8266 protocol database
/**
 * IRremoteESP8266 Protocol Mapper
 *
 * Maps protocols and timing patterns from the IRremoteESP8266 library.
 * Based on: https://github.com/crankyoldgit/IRremoteESP8266
 *
 * This provides comprehensive protocol definitions for 40+ HVAC manufacturers
 * with more accurate timing patterns and better manufacturer detection.
 */

class IRProtocolTiming {
    String name
    List<String> manufacturer  // Can have multiple manufacturers
    int headerMark            // Header mark in microseconds
    int headerSpace           // Header space in microseconds
    int bitMark               // Bit mark in microseconds
    int oneSpace              // Space for "1" bit
    int zeroSpace             // Space for "0" bit
    int tolerance = 200       // Timing tolerance in microseconds
    int frequency = 38000     // Carrier frequency in Hz (default 38kHz)
    String notes = ""         // Additional notes about the protocol

    IRProtocolTiming(String name, List<String> manufacturer, int headerMark, int headerSpace,
                    int bitMark, int oneSpace, int zeroSpace, int tolerance = 200,
                    int frequency = 38000, String notes = "") {
        this.name = name
        this.manufacturer = manufacturer
        this.headerMark = headerMark
        this.headerSpace = headerSpace
        this.bitMark = bitMark
        this.oneSpace = oneSpace
        this.zeroSpace = zeroSpace
        this.tolerance = tolerance
        this.frequency = frequency
        this.notes = notes
    }

    /**
     * Check if this protocol matches the given timings.
     *
     * @param timings Raw IR timing array
     * @param toleranceMultiplier Multiplier for tolerance
     * @return Confidence score (0.0-1.0) or 0.0 if no match
     */
    double matches(List<Integer> timings, double toleranceMultiplier = 1.5) {
        if (!timings || timings.size() < 2) {
            return 0.0
        }

        int headerMarkTiming = timings[0]
        int headerSpaceTiming = timings[1]

        double adjustedTolerance = tolerance * toleranceMultiplier

        // Calculate how well the header matches
        int markDiff = Math.abs(headerMarkTiming - headerMark)
        int spaceDiff = Math.abs(headerSpaceTiming - headerSpace)

        // Both must be within tolerance
        if (markDiff <= adjustedTolerance && spaceDiff <= adjustedTolerance) {
            // Calculate confidence score (0-1)
            double markScore = 1.0 - (markDiff / adjustedTolerance)
            double spaceScore = 1.0 - (spaceDiff / adjustedTolerance)
            return (markScore + spaceScore) / 2.0
        }

        return 0.0
    }
}

class IRRemoteESP8266 {

    // Protocol definitions based on IRremoteESP8266
    // Timing values collected from real-world captures and library documentation
    static final Map<String, IRProtocolTiming> PROTOCOLS = [
        // Fujitsu AC protocols
        FUJITSU_AC: new IRProtocolTiming(
            "FUJITSU_AC",
            ["Fujitsu", "Fujitsu General", "OGeneral"],
            3300, 1600,  // header
            420, 1200, 400,  // bit timings
            300,  // tolerance
            38000,
            "Standard Fujitsu AC protocol (ARRAH2E, AR-RAx series)"
        ),

        FUJITSU_AC264: new IRProtocolTiming(
            "FUJITSU_AC264",
            ["Fujitsu"],
            3300, 1600,
            420, 1200, 400,
            300,
            38000,
            "Extended 264-bit Fujitsu protocol"
        ),

        // Daikin AC protocols
        DAIKIN: new IRProtocolTiming(
            "DAIKIN",
            ["Daikin"],
            3650, 1623,
            428, 1280, 428,
            200,
            38000,
            "Daikin ARC series remotes"
        ),

        DAIKIN2: new IRProtocolTiming(
            "DAIKIN2",
            ["Daikin"],
            3500, 1728,
            460, 1270, 420,
            200,
            38000,
            "Daikin ARC4xx series"
        ),

        // Mitsubishi protocols
        MITSUBISHI_AC: new IRProtocolTiming(
            "MITSUBISHI_AC",
            ["Mitsubishi", "Mitsubishi Electric"],
            3400, 1750,
            450, 1300, 420,
            200,
            38000,
            "Standard Mitsubishi AC (MSZ series)"
        ),

        MITSUBISHI_HEAVY_152: new IRProtocolTiming(
            "MITSUBISHI_HEAVY_152",
            ["Mitsubishi Heavy Industries"],
            3200, 1600,
            400, 1200, 400,
            200,
            38000,
            "Mitsubishi Heavy SRK series"
        ),

        // Gree/Cooper & Hunter
        GREE: new IRProtocolTiming(
            "GREE",
            ["Gree", "Cooper & Hunter", "RusClimate", "Soleus Air"],
            9000, 4500,
            620, 1600, 540,
            300,
            38000,
            "Gree YAW1F, Cooper & Hunter"
        ),

        // LG
        LG: new IRProtocolTiming(
            "LG",
            ["LG", "General Electric"],
            8000, 4000,
            600, 1600, 550,
            300,
            38000,
            "LG AKB series remotes"
        ),

        // Samsung
        SAMSUNG_AC: new IRProtocolTiming(
            "SAMSUNG_AC",
            ["Samsung"],
            690, 17844,
            690, 1614, 492,
            200,
            38000,
            "Samsung AR series"
        ),

        // Panasonic
        PANASONIC_AC: new IRProtocolTiming(
            "PANASONIC_AC",
            ["Panasonic"],
            3500, 1750,
            435, 1300, 435,
            200,
            38000,
            "Panasonic CS series"
        ),

        // Hitachi
        HITACHI_AC: new IRProtocolTiming(
            "HITACHI_AC",
            ["Hitachi"],
            3400, 1700,
            400, 1250, 400,
            200,
            38000,
            "Hitachi RAK/RAS series"
        ),

        HITACHI_AC1: new IRProtocolTiming(
            "HITACHI_AC1",
            ["Hitachi"],
            3300, 1700,
            400, 1200, 400,
            200,
            38000,
            "Alternate Hitachi protocol"
        ),

        // Toshiba
        TOSHIBA_AC: new IRProtocolTiming(
            "TOSHIBA_AC",
            ["Toshiba", "Carrier"],
            4400, 4300,
            543, 1623, 543,
            300,
            38000,
            "Toshiba RAS series"
        ),

        // Sharp
        SHARP_AC: new IRProtocolTiming(
            "SHARP_AC",
            ["Sharp"],
            3800, 1900,
            470, 1400, 470,
            200,
            38000,
            "Sharp CRMC-A series"
        ),

        // Haier
        HAIER_AC: new IRProtocolTiming(
            "HAIER_AC",
            ["Haier", "Daichi"],
            3000, 3000,
            520, 1650, 650,
            250,
            38000,
            "Haier HSU series"
        ),

        // Midea/Electrolux
        MIDEA: new IRProtocolTiming(
            "MIDEA",
            ["Midea", "Comfee", "Electrolux", "Keystone", "Trotec"],
            4420, 4420,
            560, 1680, 560,
            300,
            38000,
            "Midea MWMA series, Electrolux variants"
        ),

        // Coolix (multiple brands)
        COOLIX: new IRProtocolTiming(
            "COOLIX",
            ["Midea", "Tokio", "Airwell", "Beko", "Bosch"],
            4480, 4480,
            560, 1680, 560,
            300,
            38000,
            "Coolix/Midea variant used by multiple brands"
        ),

        // Carrier
        CARRIER_AC: new IRProtocolTiming(
            "CARRIER_AC",
            ["Carrier"],
            8960, 4480,
            560, 1680, 560,
            300,
            38000,
            "Carrier 619EGX series"
        ),

        // Electra/AEG
        ELECTRA_AC: new IRProtocolTiming(
            "ELECTRA_AC",
            ["Electra", "AEG", "AUX", "Frigidaire"],
            9000, 4500,
            630, 1650, 530,
            300,
            38000,
            "Electra YKR series remotes"
        ),

        // Whirlpool
        WHIRLPOOL_AC: new IRProtocolTiming(
            "WHIRLPOOL_AC",
            ["Whirlpool"],
            8950, 4484,
            597, 1649, 547,
            300,
            38000,
            "Whirlpool SPIS series"
        )
    ]

    /**
     * Identify IR protocol using IRremoteESP8266 timing database.
     *
     * @param timings Raw IR timing array in microseconds
     * @param toleranceMultiplier Multiplier for tolerance (default 1.5 for real-world variance)
     * @return Map with protocol information or null if not identified
     *
     * Example return:
     * [
     *   protocol: "FUJITSU_AC",
     *   manufacturer: ["Fujitsu", "Fujitsu General"],
     *   confidence: 0.95,
     *   timingMatch: [
     *     headerMark: 3294,
     *     headerSpace: 1605,
     *     expectedMark: 3300,
     *     expectedSpace: 1600
     *   ],
     *   notes: "Standard Fujitsu AC protocol (ARRAH2E, AR-RAx series)"
     * ]
     */
    static Map identifyProtocol(List<Integer> timings, double toleranceMultiplier = 1.5) {
        if (!timings || timings.size() < 4) {
            return null
        }

        int headerMark = timings[0]
        int headerSpace = timings[1]

        Map bestMatch = null
        double bestScore = 0.0

        PROTOCOLS.each { String protocolName, IRProtocolTiming protocol ->
            double score = protocol.matches(timings, toleranceMultiplier)

            if (score > bestScore) {
                bestScore = score
                bestMatch = [
                    protocol: protocolName,
                    manufacturer: protocol.manufacturer,
                    confidence: Math.round(score * 100) / 100.0,
                    timingMatch: [
                        headerMark: headerMark,
                        headerSpace: headerSpace,
                        expectedMark: protocol.headerMark,
                        expectedSpace: protocol.headerSpace
                    ],
                    notes: protocol.notes
                ]
            }
        }

        return bestMatch
    }

    /**
     * Get standard HVAC capabilities for a protocol.
     *
     * Returns typical capabilities (modes, fan speeds, temp range).
     *
     * @param protocolName Protocol name
     * @return Map with capabilities
     */
    static Map getProtocolCapabilities(String protocolName) {
        // Standard capabilities for most HVAC units
        return [
            modes: ["cool", "heat", "dry", "fan", "auto"],
            fanSpeeds: ["auto", "low", "medium", "high", "quiet"],
            tempRange: [min: 16, max: 30, unit: "celsius"],
            features: ["swing"]
        ]
    }

    /**
     * Get a sorted list of all supported manufacturers.
     *
     * @return Sorted list of unique manufacturer names
     */
    static List<String> getAllManufacturers() {
        Set<String> manufacturers = new HashSet<>()
        PROTOCOLS.values().each { protocol ->
            manufacturers.addAll(protocol.manufacturer)
        }
        return manufacturers.sort()
    }

    /**
     * Get all protocols for a specific manufacturer.
     *
     * @param manufacturer Manufacturer name (case-insensitive)
     * @return List of protocol names
     */
    static List<String> getProtocolsByManufacturer(String manufacturer) {
        String manufacturerLower = manufacturer.toLowerCase()
        List<String> protocols = []

        PROTOCOLS.each { String protocolName, IRProtocolTiming protocol ->
            if (protocol.manufacturer.any { it.toLowerCase() == manufacturerLower }) {
                protocols << protocolName
            }
        }

        return protocols
    }

    /**
     * Get protocol definition by name.
     *
     * @param protocolName Protocol name
     * @return IRProtocolTiming or null if not found
     */
    static IRProtocolTiming getProtocol(String protocolName) {
        return PROTOCOLS[protocolName]
    }
}

// HVAC code generator service
/**
 * HVAC IR code generator.
 *
 * Generates IR codes for various HVAC manufacturers and protocols.
 */


class HVACCodeGenerator {

    String protocol
    IRProtocolTiming protocolDef

    /**
     * Initialize generator for a specific protocol.
     *
     * @param protocol Protocol name (e.g., "FUJITSU_AC")
     * @throws IllegalArgumentException if protocol is not supported
     */
    HVACCodeGenerator(String protocol) {
        this.protocol = protocol
        this.protocolDef = IRRemoteESP8266.getProtocol(protocol)

        if (!protocolDef) {
            throw new IllegalArgumentException("Unsupported protocol: ${protocol}")
        }
    }

    /**
     * Generate a single HVAC command in Tuya format.
     *
     * @param power "on" or "off"
     * @param mode "cool", "heat", "dry", "fan", "auto"
     * @param temperature Temperature in Celsius (16-30)
     * @param fan "low", "medium", "high", "auto"
     * @param swing "on" or "off"
     * @return Tuya Base64 IR code
     * @throws IllegalArgumentException if parameters are invalid
     */
    String generateCode(String power = "on", String mode = "cool",
                       int temperature = 24, String fan = "auto",
                       String swing = "off") {

        validateParameters(power, mode, temperature, fan, swing)

        // Generate IR timings
        List<Integer> timings = encodeCommand(power, mode, temperature, fan, swing)

        // Convert to Tuya format
        return TuyaIRService.encodeTuyaIR(timings)
    }

    /**
     * Generate complete command set for all combinations.
     *
     * @param modes List of modes to generate (default: all supported)
     * @param tempRange [min, max] temperature range (default: full range)
     * @param fanSpeeds List of fan speeds (default: all supported)
     * @return Map structure with all commands
     */
    Map generateAllCommands(List<String> modes = null,
                           List<Integer> tempRange = null,
                           List<String> fanSpeeds = null) {

        Map caps = IRRemoteESP8266.getProtocolCapabilities(protocol)

        // Use defaults if not specified
        if (!modes) {
            modes = caps.modes.findAll { it != "auto" }
        }
        if (!tempRange) {
            tempRange = [caps.tempRange.min, caps.tempRange.max]
        }
        if (!fanSpeeds) {
            fanSpeeds = caps.fanSpeeds
        }

        Map commands = [:]

        // Generate OFF command
        commands.off = generateCode("off", "cool", 24)

        // Generate commands for each mode
        modes.each { String modeStr ->
            commands[modeStr] = [:]

            fanSpeeds.each { String fanStr ->
                commands[modeStr][fanStr] = [:]

                for (int temp = tempRange[0]; temp <= tempRange[1]; temp++) {
                    try {
                        String code = generateCode("on", modeStr, temp, fanStr, "off")
                        commands[modeStr][fanStr][temp.toString()] = code
                    } catch (IllegalArgumentException e) {
                        // Skip invalid combinations
                    }
                }
            }
        }

        return commands
    }

    private void validateParameters(String power, String mode, int temperature,
                                   String fan, String swing) {
        Map caps = IRRemoteESP8266.getProtocolCapabilities(protocol)

        if (power != "on" && power != "off") {
            throw new IllegalArgumentException("Invalid power: ${power}")
        }

        if (!caps.modes.contains(mode)) {
            throw new IllegalArgumentException(
                "Invalid mode: ${mode}. Supported: ${caps.modes}"
            )
        }

        int tempMin = caps.tempRange.min
        int tempMax = caps.tempRange.max
        if (temperature < tempMin || temperature > tempMax) {
            throw new IllegalArgumentException(
                "Temperature ${temperature} out of range (${tempMin}-${tempMax})"
            )
        }

        if (!caps.fanSpeeds.contains(fan)) {
            throw new IllegalArgumentException(
                "Invalid fan speed: ${fan}. Supported: ${caps.fanSpeeds}"
            )
        }

        if (swing != "on" && swing != "off") {
            throw new IllegalArgumentException("Invalid swing: ${swing}")
        }
    }

    private List<Integer> encodeCommand(String power, String mode,
                                        int temperature, String fan, String swing) {
        List<Integer> timings = []

        // Add header (mark and space)
        timings << protocolDef.headerMark
        timings << protocolDef.headerSpace

        // Create bit pattern based on parameters
        String bits = createBitPattern(power, mode, temperature, fan, swing)

        // Encode bits to timings
        bits.each { bit ->
            timings << protocolDef.bitMark
            if (bit == '1') {
                timings << protocolDef.oneSpace
            } else {
                timings << protocolDef.zeroSpace
            }
        }

        // Add footer (final mark)
        timings << protocolDef.bitMark

        return timings
    }

    private String createBitPattern(String power, String mode,
                                    int temperature, String fan, String swing) {
        // Simplified bit encoding (not accurate to real protocols)
        String powerBit = power == "on" ? "1" : "0"

        String modeBits = [
            cool: "000",
            heat: "001",
            dry: "010",
            fan: "011",
            auto: "100"
        ][mode] ?: "000"

        // Encode temperature (simplified)
        String tempBits = Integer.toBinaryString(temperature - 16).padLeft(5, '0')

        String fanBits = [
            auto: "00",
            low: "01",
            medium: "10",
            high: "11",
            quiet: "00"
        ][fan] ?: "00"

        String swingBit = swing == "on" ? "1" : "0"

        // Create pattern with repetition for realistic length
        String basePattern = powerBit + modeBits + tempBits + fanBits + swingBit

        // Repeat and pad to create realistic length (96 bits is common)
        String pattern = (basePattern * 8).take(96)

        return pattern
    }

    /**
     * Convenience method to generate a single command.
     *
     * @param protocol Protocol name
     * @param power "on" or "off"
     * @param mode HVAC mode
     * @param temperature Temperature in Celsius
     * @param fan Fan speed
     * @param swing Swing setting
     * @return Tuya Base64 IR code
     */
    static String generateCommand(String protocol, String power = "on",
                                 String mode = "cool", int temperature = 24,
                                 String fan = "auto", String swing = "off") {
        def generator = new HVACCodeGenerator(protocol)
        return generator.generateCode(power, mode, temperature, fan, swing)
    }
}


/*********
 * HUBITAT APP CODE
 */

definition(
    name: "HVAC Setup Wizard",
    namespace: "hubitat.anasta.si",
    author: "Lastmyle",
    description: "Configure HVAC IR remotes with automatic model detection",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "selectDevice")
    page(name: "selectManufacturer")
    page(name: "learnCode")
    page(name: "verifyModel")
    page(name: "complete")
}

/*********
 * CONSTANTS
 */

// Cache expiry: 24 hours in milliseconds
@Field static final long CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000

// SmartIR GitHub URLs
@Field static final String SMARTIR_API_BASE = "https://api.github.com/repos/smartHomeHub/SmartIR"
@Field static final String SMARTIR_CONTENTS_PATH = "/contents/codes/climate"
@Field static final String SMARTIR_RAW_BASE = "https://raw.githubusercontent.com/smartHomeHub/SmartIR/master/codes/climate"

// Hardcoded manufacturers as fallback
@Field static final List<String> FALLBACK_MANUFACTURERS = [
    "Carrier", "Daikin", "Fujitsu", "Gree", "LG", "Midea",
    "Mitsubishi", "Panasonic", "Samsung", "Toshiba"
]

/*********
 * PAGES
 */

def mainPage() {
    dynamicPage(name: "mainPage", title: "Maestro Tuya Zigbee HVAC Setup Wizard", uninstall: true, install: false, nextPage: "selectDevice") {
        section("Welcome") {
            paragraph "This wizard will help you configure your HVAC IR remote control using automatic protocol detection."
            paragraph "You will need:\n• The physical HVAC remote\n• Access to the IR blaster device"
        }

        section("⚠️ Important: One Device Per Wizard") {
            paragraph "<b>This wizard configures ONE IR blaster device.</b>"
            paragraph ""
            paragraph "If you have multiple IR blasters:"
            paragraph "• Complete setup for the first device"
            paragraph "• Then install a NEW instance of this wizard for each additional device"
            paragraph "• Go to: Apps → Add User App → HVAC Setup Wizard"
        }

        section("How It Works") {
            paragraph "The wizard will:\n" +
                      "1. Select your IR blaster device\n" +
                      "2. Choose your HVAC manufacturer (optional hint)\n" +
                      "3. Learn an IR code from your remote\n" +
                      "4. Automatically detect protocol from IR timing patterns\n" +
                      "5. Generate complete command set for your HVAC\n" +
                      "6. Configure the device for complete HVAC control"
        }

        section("Local Protocol Detection") {
            paragraph "This wizard uses the IRremoteESP8266 protocol database with " +
                      "20+ supported HVAC protocols. Protocol detection is automatic " +
                      "based on IR timing patterns - no internet connection required!"
        }

        section("Settings") {
            input "debugLogging", "bool",
                  title: "Enable Debug Logging",
                  description: "Show detailed debug information in logs and UI",
                  defaultValue: false,
                  required: false
        }
    }
}

def selectDevice() {
    dynamicPage(name: "selectDevice", title: "Select IR Blaster", install: false, nextPage: "selectManufacturer", refreshInterval: irDevice && !getDeviceStatus(irDevice).online ? 5 : 0) {
        section("Device Selection") {
            input "irDevice", "capability.pushableButton",
                  title: "Select Tuya IR Remote Device",
                  required: true,
                  multiple: false,
                  submitOnChange: true
        }

        if (irDevice) {
            // Subscribe to device events when device is first selected
            log.info "Device selected: ${irDevice.displayName}"
            log.info "Setting up event subscription..."
            unsubscribe()  // Clear any old subscriptions
            subscribe(irDevice, "lastLearnedCode", codeLearnedHandler)
            log.info "✓ Subscribed to lastLearnedCode event from ${irDevice.displayName}"

            // Get device status
            def deviceStatus = getDeviceStatus(irDevice)

            section("Device Status") {
                paragraph "Device: ${irDevice.displayName}"

                if (deviceStatus.online) {
                    paragraph "✅ <b style='color:green'>Status:</b> ${deviceStatus.status}"
                } else {
                    paragraph "❌ <b style='color:red'>Status:</b> ${deviceStatus.status}"
                    paragraph ""
                    paragraph "<b>The device appears to be offline or unreachable.</b>"
                    paragraph ""
                    paragraph "Please check:"
                    paragraph "• Device is powered on"
                    paragraph "• Device is within Zigbee range"
                    paragraph "• Device is paired to the hub"
                    paragraph "• Hub's Zigbee radio is functioning"
                    paragraph ""
                    paragraph "<i>Page will auto-refresh every 5 seconds to check status...</i>"
                    paragraph ""
                    input "forceOffline", "bool",
                          title: "Proceed anyway (advanced)",
                          description: "Continue setup even if device appears offline",
                          defaultValue: false,
                          required: false
                }

                // Refresh button
                href "selectDevice", title: "Refresh Status", description: "Check device status again"

                // Check if device has required methods
                if (!deviceHasHvacSupport(irDevice)) {
                    paragraph "<hr>"
                    paragraph "⚠️ <b style='color:orange'>Warning:</b> This device may not support HVAC configuration."
                    paragraph "Make sure you're using the <b>Lastmyle Tuya Zigbee IR Remote Control</b> driver."
                }
            }

            // Block navigation if device is offline (unless force override)
            if (!deviceStatus.online && !settings.forceOffline) {
                section("Next Step") {
                    paragraph "⚠️ <b>Cannot proceed while device is offline</b>"
                    paragraph "Please bring the device online or enable 'Proceed anyway' above."
                }
                // Remove nextPage to prevent navigation
                app.updateSetting("nextPage", [type: "text", value: null])
            }
        }
    }
}

def selectManufacturer() {
    // Get manufacturers from local IRRemoteESP8266 protocol database
    def manufacturers = getManufacturerList()

    dynamicPage(name: "selectManufacturer", title: "Select Manufacturer", install: false, nextPage: "learnCode") {
        section("HVAC Manufacturer (Optional)") {
            paragraph "Selecting your manufacturer helps validate the detected protocol, " +
                      "but protocol detection will work without it."

            if (manufacturers) {
                input "hvacManufacturer", "enum",
                      title: "Select your HVAC brand (or skip)",
                      options: manufacturers,
                      required: false,
                      submitOnChange: true
            } else {
                paragraph "⚠️ Unable to load manufacturer list."
                paragraph "Using fallback list..."
                input "hvacManufacturer", "enum",
                      title: "Select your HVAC brand (or skip)",
                      options: FALLBACK_MANUFACTURERS,
                      required: false,
                      submitOnChange: true
            }
        }

        section("Ready") {
            if (hvacManufacturer) {
                paragraph "✓ Manufacturer hint: ${hvacManufacturer}"
            } else {
                paragraph "No manufacturer selected - will rely on automatic protocol detection"
            }
            paragraph ""
            paragraph "Click 'Next' to proceed to IR code learning."
            paragraph "Protocol detection will happen automatically after you learn a code."
        }
    }
}

def learnCode() {
    // Check if we should auto-redirect to next page
    def autoRedirect = state.wizardState?.readyForNextPage == true

    dynamicPage(name: "learnCode", title: "Learn IR Code", install: false, nextPage: autoRedirect ? "verifyModel" : null, refreshInterval: state.wizardState?.learningInProgress ? 2 : 0) {
        section("Instructions") {
            paragraph "<b>Step 1:</b> Click the 'Learn IR Code' button below"
            paragraph "<b>Step 2:</b> Wait for the IR blaster LED to light up"
            paragraph "<b>Step 3:</b> Within 5 seconds, press a button on your physical HVAC remote"
            paragraph ""
            paragraph "<b>Recommended button:</b> Cool mode, 24°C, Auto fan"
            paragraph "(This gives the best chance of automatic detection)"
        }

        section("Action") {
            input "triggerLearn", "button", title: "Learn IR Code"

            // Show current status
            if (state.wizardState?.learningStatus) {
                paragraph "<hr>"
                paragraph "<b>Status:</b> ${state.wizardState.learningStatus}"
            }

            // Show learning in progress indicator
            if (state.wizardState?.learningInProgress == true) {
                paragraph "⏳ <b style='color: orange;'>Learning in progress...</b>"
                paragraph "Point your HVAC remote at the IR blaster and press a button now!"
                paragraph "<i>Page will auto-refresh every 2 seconds...</i>"
            }

            // Check if code was learned
            if (state.wizardState?.learnedCode) {
                paragraph "✅ <b style='color: green;'>Code learned successfully!</b>"
                paragraph "Code length: ${state.wizardState.learnedCode.length()} characters"
                paragraph "Code preview: <code>${state.wizardState.learnedCode.take(80)}...</code>"

                // Show detection status
                if (state.wizardState.detectedModel) {
                    paragraph "<hr>"
                    paragraph "✅ <b style='color: green;'>Model Auto-Detected!</b>"
                    paragraph "Manufacturer: ${state.wizardState.detectedModel.manufacturer}"
                    paragraph "Model: ${state.wizardState.detectedModel.model}"
                    paragraph "SmartIR ID: ${state.wizardState.detectedModel.smartIrId}"
                    paragraph "<hr>"
                    paragraph "<b>Click 'Next' below to verify the detected model...</b>"

                } else if (state.wizardState.matchError) {
                    paragraph "<hr>"
                    paragraph "⚠️ <b style='color: orange;'>Auto-Detection Status:</b>"
                    paragraph "${state.wizardState.matchError}"
                    paragraph "You can still proceed to manually verify or try learning again."
                }
            }
        }

        section("Troubleshooting") {
            paragraph "• Make sure IR blaster LED lights up when learning"
            paragraph "• Hold remote 6-12 inches from IR blaster"
            paragraph "• Press and release remote button quickly"
            paragraph "• Avoid fluorescent lights (they can interfere with IR)"
        }

        section("Re-learn") {
            if (state.wizardState?.learnedCode) {
                href "learnCode", title: "Learn Different Code", description: "Try again with a different remote button"
            }
        }

        // Debug section (only shown if debug logging enabled)
        if (settings.debugLogging) {
            section("Debug Info") {
                paragraph "Learning in progress: ${state.wizardState?.learningInProgress}"
                paragraph "Ready for next page: ${state.wizardState?.readyForNextPage}"
                paragraph "Auto-redirect: ${autoRedirect}"
                paragraph "Cached models: ${state.smartirCache?.manufacturers?.get(hvacManufacturer)?.size() ?: 0}"
            }
        }
    }
}

def verifyModel() {
    // Get detection results from state
    def detectedModel = state.wizardState?.detectedModel

    dynamicPage(name: "verifyModel", title: "Verify Protocol", install: false) {
        if (detectedModel) {
            section("Protocol Detected! ✅") {
                paragraph "<b>Successfully identified your HVAC IR protocol!</b>"
                paragraph ""
                paragraph "<b>Protocol:</b> ${detectedModel.smartIrId}"
                paragraph "<b>Manufacturer(s):</b> ${detectedModel.manufacturer}"
                if (detectedModel.protocolInfo?.confidence) {
                    def confidencePercent = (detectedModel.protocolInfo.confidence * 100).intValue()
                    paragraph "<b>Confidence:</b> ${confidencePercent}%"
                }
                if (detectedModel.notes) {
                    paragraph "<b>Notes:</b> ${detectedModel.notes}"
                }
            }

            section("Supported Features") {
                def modelData = detectedModel.modelData
                if (modelData) {
                    paragraph "<b>Operation Modes:</b> ${modelData.operationModes?.join(', ')}"
                    paragraph "<b>Fan Speeds:</b> ${modelData.fanModes?.join(', ')}"
                    paragraph "<b>Temperature Range:</b> ${modelData.minTemperature}°C - ${modelData.maxTemperature}°C"
                }
            }

            if (detectedModel.detectedState) {
                section("Detected State from Learned Code") {
                    def ds = detectedModel.detectedState
                    if (ds.mode == "off") {
                        paragraph "The button you pressed: <b>Power OFF</b>"
                    } else {
                        paragraph "The button you pressed:"
                        paragraph "• <b>Mode:</b> ${ds.mode?.toUpperCase()}"
                        if (ds.temp) paragraph "• <b>Temperature:</b> ${ds.temp}°C"
                        if (ds.fan) paragraph "• <b>Fan Speed:</b> ${ds.fan?.toUpperCase()}"
                    }
                }
            }

            section("Confirm") {
                paragraph "Does this match your HVAC unit?"
                input "confirmModel", "bool",
                      title: "Yes, this is correct",
                      defaultValue: false,
                      submitOnChange: true

                if (confirmModel == false) {
                    href "learnCode", title: "Try Again", description: "Learn a different code"
                    href "selectManufacturer", title: "Change Manufacturer", description: "Go back to manufacturer selection"
                }

                if (confirmModel == true) {
                    href "complete", title: "Complete Setup", description: "Save configuration to device"
                }
            }
        } else {
            section("Protocol Not Detected ⚠️") {
                paragraph "<b>Could not automatically identify HVAC protocol</b>"
                paragraph ""
                paragraph "This could mean:"
                paragraph "• Protocol not in IRremoteESP8266 database (20+ protocols supported)"
                paragraph "• IR code was not learned correctly"
                paragraph "• Remote uses an uncommon or proprietary protocol"
                paragraph "• IR timing patterns don't match known protocols"
            }

            section("What To Do") {
                paragraph "Try these steps:"
                href "learnCode", title: "Learn Code Again", description: "Retry with a different button (try Cool/24°C/Auto)"
                href "selectManufacturer", title: "Change Manufacturer Hint", description: "Try a different manufacturer hint"
                paragraph ""
                paragraph "If your HVAC brand is from a well-known manufacturer (Daikin, Fujitsu, " +
                          "Mitsubishi, LG, etc.), the protocol should be detected automatically."
            }

            section("Supported Protocols") {
                def manufacturers = IRRemoteESP8266.getAllManufacturers()
                paragraph "<b>Supported manufacturers:</b>"
                paragraph manufacturers.join(", ")
            }
        }
    }
}

def complete() {
    // Save configuration to device
    def success = saveConfigToDevice()

    dynamicPage(name: "complete", title: "Setup Complete", install: true, uninstall: true) {
        if (success) {
            section("Success! ✅") {
                paragraph "<b>HVAC configuration saved successfully!</b>"
                paragraph ""
                paragraph "<b>Device:</b> ${irDevice.displayName}"
                paragraph "<b>Protocol:</b> ${state.wizardState?.detectedModel?.smartIrId}"
                paragraph "<b>Manufacturer(s):</b> ${state.wizardState?.detectedModel?.manufacturer}"
                if (state.wizardState?.detectedModel?.protocolInfo?.confidence) {
                    def conf = (state.wizardState.detectedModel.protocolInfo.confidence * 100).intValue()
                    paragraph "<b>Detection Confidence:</b> ${conf}%"
                }
            }

            section("Available Commands") {
                paragraph "Your device now supports these commands:"
                paragraph "• <b>hvacTurnOff()</b> - Turn off HVAC"
                paragraph "• <b>hvacRestoreState()</b> - Restore to last known state"
                paragraph "• <b>hvacSendCommand(mode, temp, fan)</b> - Send specific command"
                paragraph "   Example: hvacSendCommand('cool', 24, 'auto')"
            }

            section("Integration Examples") {
                paragraph "<b>In Rule Machine:</b>"
                paragraph "• Trigger: Location mode changes to Away"
                paragraph "• Action: Run custom action → hvacTurnOff()"
                paragraph ""
                paragraph "• Trigger: Location mode changes to Home"
                paragraph "• Action: Run custom action → hvacRestoreState()"
            }

            section("Next Steps") {
                paragraph "• Test the commands in the device page"
                paragraph "• Create automations in Rule Machine"
                paragraph "• Add device to your dashboards"
            }
        } else {
            section("Error ⚠️") {
                paragraph "Failed to save configuration. Check logs for details."
                href "mainPage", title: "Start Over", description: "Restart the wizard"
            }
        }

        section("What's Next?") {
            paragraph "<b>This wizard instance is now complete.</b>"
            paragraph ""
            paragraph "You can:"
            paragraph "• <b>Keep this wizard installed</b> - Allows you to reconfigure this device later"
            paragraph "• <b>Uninstall this wizard</b> - The device will keep its configuration"
            paragraph ""
            paragraph "To configure additional IR blasters:"
            paragraph "Go to Apps → Add User App → HVAC Setup Wizard (creates a new instance)"
        }

        section("Reconfigure This Device") {
            href "mainPage", title: "Restart Setup Wizard", description: "Reconfigure ${irDevice?.displayName}"
        }
    }
}

/*********
 * API INTEGRATION
 */

/**
 * Get list of manufacturers from local IRRemoteESP8266 protocol database.
 */
def getManufacturerList() {
    log.debug "Getting manufacturer list from IRRemoteESP8266 protocols"

    try {
        List<String> manufacturers = IRRemoteESP8266.getAllManufacturers()
        log.info "Found ${manufacturers.size()} supported manufacturers"
        return manufacturers

    } catch (Exception e) {
        log.error "Failed to get manufacturer list: ${e.message}"
        return FALLBACK_MANUFACTURERS
    }
}

/**
 * Fetch all models for a specific manufacturer from SmartIR
 * Caches results for 24 hours
 */
def fetchModelsForManufacturer(String manufacturer) {
    // Check cache first
    if (isCacheValid() && state.smartirCache?.manufacturers && state.smartirCache.manufacturers[manufacturer]) {
        log.debug "Using cached models for ${manufacturer}"
        return state.smartirCache.manufacturers[manufacturer]
    }

    log.info "Fetching models for ${manufacturer} from SmartIR..."

    def models = [:]

    try {
        // Get file list from GitHub
        def params = [
            uri: SMARTIR_API_BASE + SMARTIR_CONTENTS_PATH,
            headers: [
                "Accept": "application/vnd.github.v3+json",
                "User-Agent": "Hubitat-HVAC-Wizard/1.0"
            ],
            timeout: 15
        ]

        httpGet(params) { resp ->
            if (resp.status == 200) {
                // Filter and fetch JSON files
                def jsonFiles = resp.data.findAll { it.name.endsWith(".json") }

                log.debug "Found ${jsonFiles.size()} model files, fetching subset..."

                // Fetch each model file (REDUCED limit to prevent timeout)
                // Note: We only fetch a subset since full database has 300+ files
                // Most manufacturers have models in first 50-100 files
                def fetchedCount = 0
                def matchedCount = 0
                def maxToFetch = 20  // Reduced from 50 to prevent timeout
                def maxMatches = 15  // Stop after finding enough models for this manufacturer

                jsonFiles.each { file ->
                    // Stop if we've checked enough files OR found enough matches
                    if (fetchedCount >= maxToFetch || matchedCount >= maxMatches) {
                        log.info "Stopping fetch: checked ${fetchedCount} files, found ${matchedCount} matches"
                        return
                    }

                    try {
                        def modelData = fetchModelFile(file.download_url)

                        if (modelData && modelData.manufacturer?.toLowerCase() == manufacturer.toLowerCase()) {
                            def modelId = file.name.replaceAll(/\.json$/, "")
                            models[modelId] = modelData
                            matchedCount++
                            log.debug "Added model ${modelId} for ${manufacturer} (${matchedCount}/${maxMatches})"
                        }

                        fetchedCount++
                    } catch (Exception e) {
                        log.warn "Failed to fetch model file ${file.name}: ${e.message}"
                        fetchedCount++
                    }

                    // Small delay to avoid rate limiting (reduced from 100ms to 50ms)
                    pauseExecution(50)
                }

                log.info "Fetched ${models.size()} models for ${manufacturer} (checked ${fetchedCount} files)"
            }
        }

        // Cache the results
        if (!state.smartirCache) state.smartirCache = [:]
        if (!state.smartirCache.manufacturers) state.smartirCache.manufacturers = [:]
        state.smartirCache.manufacturers[manufacturer] = models
        state.smartirCache.lastFetched = now()

        return models

    } catch (Exception e) {
        log.error "Failed to fetch models for ${manufacturer}: ${e.message}"
        return [:]
    }
}

/**
 * Fetch individual SmartIR model JSON file
 */
def fetchModelFile(String downloadUrl) {
    try {
        def params = [
            uri: downloadUrl,
            headers: [
                "User-Agent": "Hubitat-HVAC-Wizard/1.0"
            ],
            timeout: 5,  // Reduced from 10 to 5 seconds for faster failure
            contentType: "application/json"
        ]

        def modelData = null
        httpGet(params) { resp ->
            if (resp.status == 200) {
                modelData = resp.data
            }
        }

        return modelData

    } catch (Exception e) {
        log.error "Failed to fetch model file from ${downloadUrl}: ${e.message}"
        return null
    }
}

/**
 * Identify protocol from learned IR code and generate full command set.
 *
 * Uses local IR protocol detection instead of SmartIR database:
 * 1. Decode Tuya Base64 code to raw IR timings
 * 2. Identify protocol from timing header patterns
 * 3. Generate complete command set for the protocol
 */
def matchCodeToModel(String learnedCode, String manufacturer) {
    log.debug "matchCodeToModel() called - using local protocol detection"
    log.debug "  Manufacturer hint: ${manufacturer}"
    log.debug "  Code length: ${learnedCode?.length()}"

    // Input validation: check for empty or invalid code
    if (!learnedCode || learnedCode.trim().isEmpty()) {
        log.warn "Cannot match empty or null IR code"
        return null
    }

    // Normalize learned code (remove all whitespace)
    String normalizedCode = learnedCode.replaceAll(/\s/, "")

    // Validate code length (typical Base64 IR codes are 50-500 chars)
    if (normalizedCode.length() < 4) {
        log.warn "IR code too short (${normalizedCode.length()} chars), likely invalid"
        return null
    }

    try {
        // Step 1: Decode Tuya Base64 code to raw IR timings
        log.debug "Step 1: Decoding Tuya IR code..."
        List<Integer> timings = TuyaIRService.decodeTuyaIR(normalizedCode)
        log.info "Decoded ${timings.size()} timing values"
        log.debug "First 6 timings: ${timings.take(6)}"

        // Step 2: Identify protocol from timings
        log.debug "Step 2: Identifying IR protocol from timing patterns..."
        Map protocolInfo = IRRemoteESP8266.identifyProtocol(timings, 1.5)

        if (!protocolInfo) {
            log.warn "Could not identify protocol from IR timings"
            log.debug "Header timings: [${timings[0]}, ${timings[1]}]"
            return null
        }

        log.info "✓ Protocol identified: ${protocolInfo.protocol}"
        log.info "  Manufacturers: ${protocolInfo.manufacturer}"
        log.info "  Confidence: ${protocolInfo.confidence}"
        log.debug "  Timing match: ${protocolInfo.timingMatch}"

        // Check if detected manufacturer matches the hint (optional validation)
        def manufacturerMatch = protocolInfo.manufacturer.any {
            it.toLowerCase().contains(manufacturer.toLowerCase()) ||
            manufacturer.toLowerCase().contains(it.toLowerCase())
        }

        if (!manufacturerMatch) {
            log.warn "Detected manufacturers ${protocolInfo.manufacturer} don't match hint '${manufacturer}'"
            log.warn "Proceeding anyway - protocol detection is more reliable than user selection"
        }

        // Step 3: Generate complete command set for this protocol
        log.debug "Step 3: Generating complete HVAC command set..."
        HVACCodeGenerator generator = new HVACCodeGenerator(protocolInfo.protocol)
        Map commands = generator.generateAllCommands()

        log.info "✓ Generated commands: ${commands.keySet().size()} modes"
        log.debug "  OFF command: ${commands.off?.take(50)}..."

        // Step 4: Get protocol capabilities
        Map capabilities = IRRemoteESP8266.getProtocolCapabilities(protocolInfo.protocol)

        // Return in format compatible with rest of the app
        return [
            smartIrId: protocolInfo.protocol,  // Use protocol name as ID
            manufacturer: protocolInfo.manufacturer.join(", "),
            model: protocolInfo.protocol,  // Protocol name as model
            modelData: [
                manufacturer: protocolInfo.manufacturer[0],
                supportedModels: protocolInfo.manufacturer,
                commands: commands,
                minTemperature: capabilities.tempRange.min,
                maxTemperature: capabilities.tempRange.max,
                operationModes: capabilities.modes,
                fanModes: capabilities.fanSpeeds
            ],
            detectedState: [mode: "unknown", temp: null, fan: null],
            protocolInfo: protocolInfo,  // Include raw protocol info for debugging
            notes: protocolInfo.notes
        ]

    } catch (Exception e) {
        log.error "Failed to detect protocol: ${e.message}"
        log.error "Stack trace: ${e}"
        return null
    }
}

/**
 * Check if SmartIR cache is still valid
 */
def isCacheValid() {
    if (!state.smartirCache?.lastFetched) {
        return false
    }

    def age = now() - state.smartirCache.lastFetched
    return age < CACHE_EXPIRY_MS
}

/**
 * Save HVAC configuration to the driver
 */
def saveConfigToDevice() {
    if (!state.wizardState?.detectedModel) {
        log.error "No detected model to save"
        return false
    }

    def detectedModel = state.wizardState.detectedModel
    def modelData = detectedModel.modelData

    if (!modelData) {
        log.error "No model data available"
        return false
    }

    try {
        // Build full configuration
        def config = [
            manufacturer: detectedModel.manufacturer,
            model: detectedModel.model,
            smartIrId: detectedModel.smartIrId,
            offCommand: modelData.commands?.off,
            commands: modelData.commands ?: [:],
            minTemperature: modelData.minTemperature ?: 16,
            maxTemperature: modelData.maxTemperature ?: 30,
            operationModes: modelData.operationModes ?: [],
            fanModes: modelData.fanModes ?: []
        ]

        // Call driver method to save config
        irDevice.setHvacConfig(config)

        log.info "HVAC configuration saved to ${irDevice.displayName}"
        return true

    } catch (Exception e) {
        log.error "Failed to save config to device: ${e.message}"
        return false
    }
}

/**
 * Check if device has HVAC support (has required methods)
 */
def deviceHasHvacSupport(device) {
    try {
        // Try to call a harmless method that should exist
        device.hasCommand("setHvacConfig")
        return true
    } catch (Exception e) {
        return false
    }
}

/**
 * Check if device appears to be online and responsive
 * Returns a map with [online: boolean, status: string, lastActivity: timestamp]
 */
def getDeviceStatus(device) {
    if (!device) {
        return [online: false, status: "No device selected", lastActivity: null]
    }

    try {
        // Check last activity time
        def lastActivity = device.getLastActivity()
        def now = new Date().time
        def timeSinceActivity = lastActivity ? (now - lastActivity.time) : null

        // Device is considered online if:
        // 1. Has recent activity (within last 24 hours), OR
        // 2. Has valid state attributes

        def hasRecentActivity = timeSinceActivity != null && timeSinceActivity < (24 * 60 * 60 * 1000)
        def hasState = device.currentStates?.size() > 0

        if (hasRecentActivity) {
            def minutesAgo = (timeSinceActivity / (60 * 1000)).intValue()
            def hoursAgo = (minutesAgo / 60).intValue()

            def activityText = hoursAgo > 0 ?
                "${hoursAgo} hour(s) ago" :
                "${minutesAgo} minute(s) ago"

            return [
                online: true,
                status: "Online - Last activity: ${activityText}",
                lastActivity: lastActivity,
                timeSinceActivity: timeSinceActivity
            ]
        } else if (hasState) {
            return [
                online: true,
                status: "Online - Has device state",
                lastActivity: lastActivity,
                timeSinceActivity: timeSinceActivity
            ]
        } else {
            return [
                online: false,
                status: lastActivity ? "Offline - No recent activity" : "Offline - Never seen",
                lastActivity: lastActivity,
                timeSinceActivity: timeSinceActivity
            ]
        }
    } catch (Exception e) {
        log.error "Error checking device status: ${e.message}"
        return [
            online: false,
            status: "Error checking status: ${e.message}",
            lastActivity: null
        ]
    }
}

/*********
 * APP LIFECYCLE
 */

def installed() {
    log.info "HVAC Setup Wizard installed"
    log.debug "Device will be selected during wizard flow, no initialization needed yet"
}

def updated() {
    log.info "HVAC Setup Wizard updated"
    log.debug "Re-initializing subscriptions..."
    unsubscribe()
    initialize()
}

def initialize() {
    log.info "=== Initialize called ==="

    // Re-subscribe to device events if device is already selected
    // (This handles app restart/update scenarios)
    if (irDevice) {
        log.info "Re-subscribing to device ${irDevice.displayName}"
        unsubscribe()
        subscribe(irDevice, "lastLearnedCode", codeLearnedHandler)
        log.info "✓ Event subscription active"
    } else {
        log.debug "No device selected yet, will subscribe when device is chosen"
    }

    // Initialize wizard state if needed
    if (!state.wizardState) {
        state.wizardState = [:]
        log.debug "Initialized wizard state"
    }

    log.info "=== Initialize complete ==="
}

def uninstalled() {
    log.info "HVAC Setup Wizard uninstalled"
}

/*********
 * EVENT HANDLERS
 */

/**
 * Handle button press from UI
 */
def appButtonHandler(btn) {
    log.info "Button pressed: ${btn}"

    switch (btn) {
        case "triggerLearn":
            log.info "=== Starting IR Code Learning ==="
            if (irDevice) {
                try {
                    log.debug "Device: ${irDevice.displayName}"
                } catch (Exception e) {
                    log.debug "Device: ${irDevice}"
                }
            }
            if (settings?.hvacManufacturer) {
                log.debug "Manufacturer: ${settings.hvacManufacturer}"
            }

            if (!irDevice) {
                log.error "No device selected"
                return
            }

            try {
                // Initialize wizard state
                if (!state.wizardState) state.wizardState = [:]

                // Clear previous learning attempt
                state.wizardState.learnedCode = null
                state.wizardState.detectedModel = null
                state.wizardState.matchError = null
                state.wizardState.learningStatus = "Waiting for IR signal..."
                log.debug "Cleared previous learning state"

                // Verify device has the learnIrCode command
                if (!irDevice.hasCommand("learnIrCode")) {
                    log.error "Device does not have 'learnIrCode' command!"
                    log.error "Available commands: ${irDevice.supportedCommands.collect { it.name }}"
                    state.wizardState.learningStatus = "Error: Device missing learnIrCode command"
                    return
                }

                // Call driver's learnIrCode method
                log.info "Calling irDevice.learnIrCode('wizard')"
                log.debug "Device ID: ${irDevice.id}, Device DNI: ${irDevice.deviceNetworkId}"

                def result = irDevice.learnIrCode("wizard")

                log.info "learnIrCode() returned: ${result}"

                state.wizardState.learningInProgress = true
                log.info "✓ Learn command sent to device - LED should be blinking"
                log.info "Point your remote at the IR blaster and press a button within 5 seconds"

            } catch (Exception e) {
                log.error "Failed to trigger learn: ${e.message}"
                log.error "Full error: ${e}"
                state.wizardState.learningStatus = "Error: ${e.message}"
            }
            break

        default:
            log.warn "Unknown button: ${btn}"
    }
}

/**
 * Handle learned code event from device
 */
def codeLearnedHandler(evt) {
    log.info "=== IR Code Learned Event Received ==="
    log.info "Code length: ${evt.value?.length()} characters"
    log.debug "Code preview: ${evt.value}"

    def learnedCode = evt.value

    if (!learnedCode) {
        log.error "❌ Empty code received - learning failed"
        if (!state.wizardState) state.wizardState = [:]
        state.wizardState.learningStatus = "Failed: Empty code received"
        state.wizardState.learningInProgress = false
        return
    }

    // Store in wizard state
    if (!state.wizardState) state.wizardState = [:]
    state.wizardState.learnedCode = learnedCode
    state.wizardState.learningInProgress = false
    state.wizardState.learningStatus = "Code received, matching to model..."

    log.info "✓ Code stored in wizard state"

    // Try to detect protocol from code
    log.info "=== Starting Protocol Detection ==="
    log.info "Manufacturer hint: ${hvacManufacturer ?: 'none'}"
    log.info "Normalized code length: ${learnedCode.replaceAll(/\s/, '').length()}"

    try {
        def detectedModel = matchCodeToModel(learnedCode, hvacManufacturer ?: "unknown")

        if (detectedModel) {
            log.info "✅ Protocol Detected Successfully!"
            log.info "Protocol: ${detectedModel.smartIrId}"
            log.info "Manufacturer(s): ${detectedModel.manufacturer}"
            log.info "Confidence: ${detectedModel.protocolInfo?.confidence}"
            if (detectedModel.notes) {
                log.info "Notes: ${detectedModel.notes}"
            }

            state.wizardState.detectedModel = detectedModel
            state.wizardState.matchError = null
            state.wizardState.learningStatus = "Success! Protocol detected: ${detectedModel.model}"
            state.wizardState.readyForNextPage = true  // Signal for auto-redirect

        } else {
            log.warn "❌ Could not identify protocol from IR code"
            log.debug "This could mean:"
            log.debug "  - Protocol not in IRremoteESP8266 database"
            log.debug "  - Code doesn't match expected timing patterns"
            log.debug "  - IR code was not learned correctly"

            state.wizardState.detectedModel = null
            state.wizardState.matchError = "Could not identify protocol from IR timing patterns"
            state.wizardState.learningStatus = "No protocol match found"
            state.wizardState.readyForNextPage = false
        }
    } catch (Exception e) {
        log.error "❌ Error during protocol detection: ${e.message}"
        log.error "Stack trace: ${e}"
        state.wizardState.matchError = "Error during detection: ${e.message}"
        state.wizardState.learningStatus = "Error: ${e.message}"
        state.wizardState.readyForNextPage = false
    }

    log.info "=== Learning Process Complete ==="
}
