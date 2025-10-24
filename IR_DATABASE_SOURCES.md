# IR Command Database Sources

## Primary Sources for AC IR Commands

### 1. **IRDB - One of the largest IR databases**
- **URL**: https://github.com/probonopd/irdb
- **Format**: CSV files organized by brand/device
- **Coverage**: Thousands of devices including major AC brands
- **Path for ACs**: `/codes/Air Conditioner/`
- Contains brands like Daikin, Mitsubishi, Panasonic, LG, Samsung, etc.

### 2. **SmartIR - Home Assistant Component**
- **URL**: https://github.com/smartHomeHub/SmartIR
- **Format**: JSON files with detailed climate device codes  
- **Path**: `/codes/climate/`
- **Notable**: Well-structured with temperature ranges, modes, fan speeds
- Files named by manufacturer code (e.g., `1000.json` for Coolix)

### 3. **IRremoteESP8266 Library**
- **URL**: https://github.com/crankyoldgit/IRremoteESP8266
- **Format**: C++ source code with IR protocols
- **Path**: `/src/ir_*.cpp` files (e.g., `ir_Daikin.cpp`, `ir_Mitsubishi.cpp`)
- **Advantage**: Includes protocol documentation and timing details
- Contains encoding/decoding functions that show exact bit patterns

### 4. **Tasmota IR Remote Codes**
- **URL**: https://github.com/arendst/Tasmota/tree/development/tasmota/berry/modules
- **Secondary**: https://tasmota.github.io/docs/Codes-for-IR-Remotes/
- **Format**: Berry script and JSON
- Contains HVAC-specific implementations

### 5. **OpenMQTTGateway IR Database**
- **URL**: https://github.com/1technophile/OpenMQTTGateway/tree/development/lib/IRremoteESP8266
- **Format**: Integrated with IRremoteESP8266
- Good for protocol specifications

### 6. **Flipper Zero IR Database**
- **URL**: https://github.com/logickworkshop/Flipper-IRDB
- **Path**: `/ACs/` directory
- **Format**: `.ir` files with raw and parsed commands
- Growing community-contributed database

### 7. **LIRC Database (Linux Infrared Remote Control)**
- **URL**: https://sourceforge.net/p/lirc-remotes/code/ci/master/tree/remotes/
- **Format**: LIRC conf files
- **Path**: Look for brands under `/remotes/`
- One of the oldest and most comprehensive databases

## How to Extract Commands

### From IRDB (CSV format):
```csv
functionname,protocol,device,subdevice,function
POWER,NEC,32,223,223
TEMP_UP,NEC,32,223,23
TEMP_DOWN,NEC,32,223,24
```

### From SmartIR (JSON format):
```json
{
  "manufacturer": "Daikin",
  "supportedModels": ["ARC433A*", "ARC423A*"],
  "commandsEncoding": "Base64",
  "commands": {
    "off": "JgBGAJK5FDoVOhQ7FDoVOhQ7FRkVGRUZFRkVGRUZFRkVGRQ7FRkVOhU6FRkVGRUZFRkVGRUZFRkVGRUZFRkVGRUZFRkVGRQADQUAAA==",
    "cool": {
      "16": {
        "auto": "JgBGAJK5FDoVOhQ7FDoVOhQ7FRkVGRUZFRk..."
      }
    }
  }
}
```

### From IRremoteESP8266 (C++ format):
```cpp
// Example from ir_Daikin.cpp
const uint16_t kDaikinHdrMark = 5000;
const uint16_t kDaikinHdrSpace = 2145;
const uint16_t kDaikinBitMark = 433;
const uint16_t kDaikin2ZeroSpace = 433;
const uint16_t kDaikin2OneSpace = 1320;
```

## Conversion Tools

### 1. **IrScrutinizer**
- **URL**: https://github.com/bengtmartensson/IrScrutinizer
- Desktop application for analyzing, converting IR formats
- Can import/export multiple formats

### 2. **IR Protocol Analyzer**
- **URL**: https://github.com/AnalysIR/AnalysIR
- Commercial tool with free trial
- Excellent for reverse engineering unknown protocols

### 3. **Online Converters**
- Pronto to Raw: https://www.remotecentral.com/cgi-bin/codes/convert_pronto.cgi
- Raw to Pronto: Various online tools available

## Format Conversions

### Common IR Data Formats:
1. **Raw**: Array of timing values (microseconds)
   - Example: `[9000, 4500, 560, 560, 560, 1690, ...]`

2. **Pronto Hex**: Philips Pronto format
   - Example: `0000 006D 0022 0002 0157 00AC...`

3. **Base64**: Encoded binary data (common in Broadlink/Tuya)
   - Example: `JgBGAJK5FDoVOhQ7...`

4. **Hexadecimal**: Direct hex representation
   - Example: `0x20DF10EF`

### Conversion for Tuya/Hubitat:
The Tuya IR blaster expects Base64-encoded raw timing data. To convert:

1. If you have raw timings: Encode directly to Base64
2. If you have Pronto: Convert to raw first, then Base64
3. If you have protocol-specific (NEC/RC5/etc): Generate raw timings using protocol specs

## Recommended Approach

1. **Start with SmartIR**: Best organized for HVAC devices
2. **Cross-reference with IRremoteESP8266**: For protocol details
3. **Fill gaps with IRDB**: For less common brands
4. **Test with real devices**: Protocols can vary by region/model year

## Example Database Structure for Implementation

```groovy
// Converted from SmartIR's Daikin (1000.json)
@Field static final Map DAIKIN_COMMANDS = [
    power_on_cool_21c_auto: "JgBGAJK5FDoVOhQ7FDoVOhQ7...", // Base64
    power_on_heat_21c_auto: "JgBGAJK5FDoVOhQ7FDoVOhQ7...",
    // ... more commands
]

// Converted from IRDB
@Field static final Map MITSUBISHI_COMMANDS = [
    // CSV -> Raw timings -> Base64
    power_toggle: "JgBYAAABKpIVERUREjUSERU4...",
    // ... more commands
]
```

## Notes on AC Protocols

Most modern ACs use **state-based protocols** where each command contains:
- Power state (on/off)
- Temperature setting
- Mode (cool/heat/dry/fan/auto)
- Fan speed
- Swing position
- Additional features (quiet mode, powerful mode, etc.)

This means you need different codes for each combination, not just simple commands.

## Community Resources

- **Reddit r/homeautomation**: Often has IR code dumps
- **Home Assistant Community Forum**: IR codes sharing threads
- **Arduino Forum**: Protocol reverse engineering discussions
- **GitHub Gists**: Search for "AC IR codes" or specific brands