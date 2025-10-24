# Air Conditioner Auto-Detection and Control Implementation Plan

## Executive Summary

This plan outlines the implementation of an intelligent air conditioner detection system for the Hubitat Tuya Zigbee IR Remote Control driver. The system will automatically identify AC units by progressively testing IR commands and matching response patterns, then provide pre-configured control interfaces for identified models.

## Goals

1. **Automatic AC Detection**: Identify air conditioner make/model through progressive IR command testing
2. **Pre-configured Commands**: Eliminate manual IR learning for common AC models
3. **User-Friendly Interface**: Provide thermostat-like controls instead of button mappings
4. **Extensible Database**: Easy addition of new AC models and protocols

## Technical Architecture

### 1. IR Protocol Database Structure

```groovy
// Location: /database/aircon_protocols.groovy
@Field static final Map<String, AirconProtocol> AIRCON_PROTOCOLS = [
    "daikin": new AirconProtocol(
        manufacturer: "Daikin",
        identificationSequence: [
            "power_toggle",  // First attempt
            "temp_21c",       // If power works, try setting temp
            "mode_cool"       // Confirm with mode change
        ],
        commands: [
            power_on: "base64_ir_code_here",
            power_off: "base64_ir_code_here",
            temp_16c: "base64_ir_code_here",
            temp_17c: "base64_ir_code_here",
            // ... temperatures 16-30°C
            mode_cool: "base64_ir_code_here",
            mode_heat: "base64_ir_code_here",
            mode_dry: "base64_ir_code_here",
            mode_fan: "base64_ir_code_here",
            mode_auto: "base64_ir_code_here",
            fan_auto: "base64_ir_code_here",
            fan_low: "base64_ir_code_here",
            fan_medium: "base64_ir_code_here",
            fan_high: "base64_ir_code_here",
            swing_on: "base64_ir_code_here",
            swing_off: "base64_ir_code_here"
        ],
        protocol_type: "state_based", // or "toggle_based"
        timing: [
            command_delay: 500,  // ms between commands
            response_timeout: 3000  // ms to wait for user confirmation
        ]
    ),
    "mitsubishi_electric": new AirconProtocol(
        // Similar structure
    ),
    "panasonic": new AirconProtocol(
        // Similar structure
    ),
    // Add more manufacturers
]
```

### 2. Detection Algorithm

```groovy
class AirconDetector {
    enum DetectionState {
        IDLE,
        TESTING_POWER,
        TESTING_SECONDARY,
        TESTING_TERTIARY,
        CONFIRMED,
        FAILED
    }
    
    def startDetection() {
        // Phase 1: Test power commands from each protocol
        state.detectionPhase = "power"
        state.testedProtocols = []
        state.candidateProtocols = []
        
        // Try each protocol's power command
        AIRCON_PROTOCOLS.each { protocolId, protocol ->
            sendTestCommand(protocolId, protocol.commands.power_on)
            pauseForUserFeedback()
        }
    }
    
    def handleUserFeedback(Boolean worked) {
        switch(state.detectionPhase) {
            case "power":
                if (worked) {
                    // Move to next phase with this protocol
                    state.candidateProtocols.add(state.currentProtocol)
                    if (state.candidateProtocols.size() == 1) {
                        // Only one candidate, move to secondary test
                        testSecondaryCommand()
                    } else {
                        // Multiple candidates, need more testing
                        testNextPowerCommand()
                    }
                } else {
                    testNextPowerCommand()
                }
                break
                
            case "secondary":
                if (worked) {
                    // High confidence match
                    confirmProtocol(state.currentProtocol)
                } else {
                    // Try next candidate
                    state.candidateProtocols.remove(state.currentProtocol)
                    testNextCandidate()
                }
                break
        }
    }
}
```

### 3. Enhanced Driver Commands

```groovy
// New commands to add to metadata
command "startAirconDetection"
command "confirmDetectionStep", [[name: "Worked", type: "ENUM", options: ["Yes", "No", "Partially"]]]
command "setAirconTemperature", [[name: "Temperature", type: "NUMBER", range: "16..30"]]
command "setAirconMode", [[name: "Mode", type: "ENUM", options: ["cool", "heat", "dry", "fan", "auto"]]]
command "setAirconFanSpeed", [[name: "Speed", type: "ENUM", options: ["auto", "low", "medium", "high"]]]
command "toggleAirconPower"
command "importAirconDatabase", [[name: "Database URL", type: "STRING"]]
```

### 4. User Interface Flow

#### Detection Wizard
1. User initiates: "Start Aircon Detection"
2. System: "Point the IR blaster at your AC. Testing Daikin power command..."
3. System sends IR command
4. System: "Did the AC respond? (turned on/off or beeped?)"
5. User: "Yes" / "No" / "Partially"
6. System progresses through detection algorithm
7. Upon successful detection: "Detected: Daikin Model XYZ. AC controls now available."

#### Control Interface
Once detected, expose virtual thermostat-like controls:
- Temperature slider (16-30°C)
- Mode selector (Cool/Heat/Dry/Fan/Auto)
- Fan speed selector
- Swing control
- Power toggle

### 5. Database Management

#### Local Storage
```groovy
state.airconDatabase = [
    version: "1.0.0",
    lastUpdated: "2024-01-01",
    customProtocols: [:],  // User-added protocols
    overrides: [:]  // User modifications to built-in protocols
]
```

#### Remote Database Updates
```groovy
def updateAirconDatabase() {
    // Fetch latest database from GitHub repository
    def response = httpGet("https://raw.githubusercontent.com/user/repo/main/aircon_db.json")
    def remoteDb = new JsonSlurper().parseText(response.data)
    
    // Merge with local customizations
    mergeProtocolDatabases(state.airconDatabase, remoteDb)
}
```

### 6. Protocol Types

#### State-Based Protocols
Most modern ACs send complete state in each command:
- Every IR signal contains: power state + temperature + mode + fan speed
- Easier to implement and more reliable
- Examples: Daikin, Mitsubishi Electric, Panasonic

#### Toggle-Based Protocols
Older ACs use toggle commands:
- Power button toggles on/off state
- Temperature buttons increment/decrement
- Requires state tracking in driver
- Examples: Older LG, Samsung models

### 7. Implementation Phases

#### Phase 1: Core Detection Engine (Week 1-2)
- [ ] Implement detection state machine
- [ ] Add user feedback commands
- [ ] Create protocol data structure
- [ ] Build command testing framework

#### Phase 2: Initial Protocol Database (Week 3-4)
- [ ] Research and document 5 popular AC brands
- [ ] Capture IR codes for each brand
- [ ] Structure protocol database
- [ ] Test detection algorithm with real devices

#### Phase 3: Enhanced Controls (Week 5-6)
- [ ] Implement virtual thermostat interface
- [ ] Add temperature/mode/fan commands
- [ ] Create state tracking for toggle-based protocols
- [ ] Build command queuing system

#### Phase 4: Database Management (Week 7-8)
- [ ] Implement remote database fetching
- [ ] Add custom protocol support
- [ ] Create export/import functionality
- [ ] Build community submission system

#### Phase 5: Advanced Features (Week 9-10)
- [ ] Add scheduling capabilities
- [ ] Implement temperature sensor integration
- [ ] Create automation rules
- [ ] Add energy saving modes

## Testing Strategy

### Unit Tests
```groovy
// tst/AirconDetectorTests.groovy
class AirconDetectorTests {
    @Test
    void testDaikinDetection() {
        def detector = new AirconDetector()
        detector.startDetection()
        
        // Simulate user confirming power worked
        detector.handleUserFeedback(true)
        
        // Verify moves to secondary test
        assert detector.state.detectionPhase == "secondary"
    }
    
    @Test
    void testMultipleCandidateHandling() {
        // Test when multiple protocols respond to power
    }
}
```

### Integration Tests
- Test with real IR blasters and AC units
- Verify timing between commands
- Test detection accuracy across brands
- Validate state persistence

## Community Contribution

### Protocol Submission Format
```json
{
    "manufacturer": "NewBrand",
    "model": "AC2000",
    "contributor": "username",
    "tested": true,
    "commands": {
        "power_on": "base64_code",
        "temp_21c": "base64_code"
        // ... all commands
    },
    "notes": "Special timing requirements..."
}
```

### Validation Process
1. Community submits new protocol via GitHub PR
2. Automated tests verify JSON structure
3. Manual review of IR codes
4. Testing by 2+ community members
5. Merge into main database

## Success Metrics

- **Detection Accuracy**: >90% successful detection rate for supported models
- **Database Coverage**: Support for top 20 AC brands globally
- **User Experience**: <30 seconds average detection time
- **Community Growth**: 50+ contributed protocols in first year

## Risk Mitigation

### Technical Risks
- **IR Code Variations**: Same model may have different codes by region
  - Mitigation: Support multiple code sets per model
  
- **User Confusion**: Complex detection process
  - Mitigation: Clear UI guidance and progress indicators
  
- **State Synchronization**: Driver state vs actual AC state
  - Mitigation: Periodic re-sync commands, manual override options

### Legal Considerations
- Ensure all IR codes are reverse-engineered or publicly available
- Clear licensing for community contributions
- No proprietary protocol implementations

## Alternative Approaches Considered

1. **Machine Learning Detection**: Train model on IR patterns
   - Rejected: Too complex for Hubitat platform limitations

2. **Manual Protocol Selection**: User chooses from list
   - Rejected: Poor UX, requires technical knowledge

3. **Cloud-Based Detection**: Server processes IR codes
   - Rejected: Privacy concerns, latency issues

## Conclusion

This implementation plan provides a robust framework for automatic air conditioner detection and control. The progressive detection algorithm minimizes user interaction while maintaining high accuracy. The extensible database structure ensures long-term maintainability and community growth.

Next steps:
1. Review and approve plan
2. Set up development environment
3. Begin Phase 1 implementation
4. Establish community communication channels