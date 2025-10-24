# HVAC Setup Wizard - Implementation Plan

## Executive Summary

This plan outlines the complete implementation of an HVAC Setup Wizard system for Hubitat's Tuya Zigbee IR Remote Control. The system consists of:

1. **HVAC Setup Wizard App** - Multi-page configuration interface
2. **Enhanced Driver** - Already has HVAC interface methods
3. **SmartIR Integration** - Automatic model detection from learned IR codes

## System Architecture

```mermaid
graph TB
    subgraph "Hubitat Platform"
        APP[HVAC Setup Wizard App]
        DRIVER[Tuya IR Driver]
        STATE_APP[App State]
        STATE_DRIVER[Driver State]
    end

    subgraph "External Services"
        GITHUB[GitHub API]
        SMARTIR[SmartIR Database]
    end

    subgraph "Physical Devices"
        IR_BLASTER[IR Blaster]
        HVAC[HVAC Unit]
        REMOTE[Physical Remote]
    end

    APP -->|1. Fetch manufacturers| GITHUB
    GITHUB -->|2. Return file list| SMARTIR
    APP -->|3. Fetch model JSON| SMARTIR
    SMARTIR -->|4. Return IR codes| APP
    APP -->|5. learnIrCode| DRIVER
    DRIVER -->|6. Zigbee learn| IR_BLASTER
    REMOTE -->|7. IR signal| IR_BLASTER
    IR_BLASTER -->|8. Learned code| DRIVER
    DRIVER -->|9. lastLearnedCode event| APP
    APP -->|10. Match code to model| STATE_APP
    APP -->|11. setHvacConfig| DRIVER
    DRIVER -->|12. Store config| STATE_DRIVER
    DRIVER -->|13. Send IR commands| IR_BLASTER
    IR_BLASTER -->|14. IR signals| HVAC

    style APP fill:#4A90E2
    style DRIVER fill:#50C878
    style SMARTIR fill:#FFB84D
```

## Wizard Flow

```mermaid
stateDiagram-v2
    [*] --> Welcome
    Welcome --> SelectDevice: Next
    SelectDevice --> SelectManufacturer: Device selected
    SelectManufacturer --> FetchModels: Manufacturer selected
    FetchModels --> LearnCode: Models loaded
    LearnCode --> Learning: Start learning
    Learning --> CodeLearned: IR code received
    CodeLearned --> Matching: Auto match
    Matching --> VerifyModel: Match found
    Matching --> ManualEntry: No match
    VerifyModel --> SaveConfig: Confirmed
    VerifyModel --> LearnCode: Retry
    ManualEntry --> SaveConfig: Manual selection
    SaveConfig --> Complete: Config saved
    Complete --> [*]

    note right of FetchModels
        GET github.com/smartHomeHub/SmartIR
        Cache for 24 hours
    end note

    note right of Matching
        Compare learned code
        against all SmartIR models
    end note
```

## Sequence Diagram: Complete Setup Flow

```mermaid
sequenceDiagram
    participant User
    participant App
    participant GitHub
    participant Driver
    participant IRBlaster
    participant Remote

    User->>App: Open HVAC Setup Wizard
    App->>App: Show welcome page

    User->>App: Select IR Blaster device
    App->>Driver: Check device capabilities

    User->>App: Click "Next"
    App->>GitHub: GET /smartHomeHub/SmartIR/contents/codes/climate
    GitHub-->>App: JSON array of model files
    App->>App: Extract manufacturers from filenames
    App->>User: Show manufacturer dropdown

    User->>App: Select "Panasonic"
    App->>App: Filter files for Panasonic (1020.json, 1021.json, etc.)

    loop For each Panasonic model file
        App->>GitHub: GET raw.githubusercontent.com/.../1020.json
        GitHub-->>App: SmartIR JSON with IR codes
        App->>App: Store in state.smartirCache
    end

    App->>User: Show "Learn Code" page

    User->>App: Click "Learn Code"
    App->>Driver: learnIrCode("wizard")
    Driver->>IRBlaster: Enter learn mode (LED on)

    User->>Remote: Press button (e.g., Cool 24°C)
    Remote->>IRBlaster: IR signal
    IRBlaster->>Driver: Captured code
    Driver->>Driver: Store in lastLearnedCode attribute
    Driver->>App: Event: lastLearnedCode changed

    App->>App: Get learned code from event
    App->>App: Match against all cached Panasonic models

    loop For each model in cache
        loop For each command in model.commands
            App->>App: Compare learned code == command code
        end
    end

    App->>App: Found match: 1020.json, cool mode, 24°C, auto fan
    App->>User: Show detected model and state

    User->>App: Confirm "Yes, this is correct"

    App->>App: Build full config from SmartIR data
    App->>Driver: setHvacConfig(full_model_config)
    Driver->>Driver: Store in state.hvacConfig
    Driver->>App: Config saved successfully

    App->>User: Show "Setup Complete" page
```

## Data Structures

### App State

```groovy
state.smartirCache = [
    lastFetched: 1234567890,  // Timestamp
    manufacturers: [
        "Panasonic": [
            "1020": [
                manufacturer: "Panasonic",
                supportedModels: ["CS/CU-E9PKR", "CS/CU-E12PKR"],
                operationModes: ["heat", "cool", "fan_only"],
                fanModes: ["low", "mid", "high", "auto"],
                minTemperature: 16,
                maxTemperature: 30,
                commands: [
                    off: "JgBQAAAB...",
                    heat: [
                        low: [
                            "16": "JgBQAAAB...",
                            "17": "JgBQAAAB...",
                            // ... 16-30
                        ],
                        mid: [ ... ],
                        high: [ ... ],
                        auto: [ ... ]
                    ],
                    cool: [ ... ],
                    fan_only: [ ... ]
                ]
            ],
            "1021": [ ... ]
        ],
        "Daikin": [ ... ]
    ]
]

state.wizardState = [
    currentPage: "learnCode",
    selectedManufacturer: "Panasonic",
    learnedCode: "JgBQAAAB...",
    detectedModel: [
        smartIrId: "1020",
        manufacturer: "Panasonic",
        model: "CS/CU-E9PKR",
        state: [mode: "cool", temp: 24, fan: "auto"]
    ]
]
```

### Driver State (Already Implemented)

```groovy
state.hvacConfig = [
    manufacturer: "Panasonic",
    model: "CS/CU-E9PKR",
    smartIrId: "1020",
    offCommand: "JgBQAAAB...",
    commands: [
        cool: [
            auto: [
                "16": "JgBQAAAB...",
                "17": "JgBQAAAB...",
                // ... 16-30
            ],
            low: [ ... ],
            mid: [ ... ],
            high: [ ... ]
        ],
        heat: [ ... ],
        fan_only: [ ... ]
    ],
    currentState: [mode: "cool", temp: 24, fan: "auto"]
]
```

## Implementation Tasks

### 1. SmartIR API Integration

#### 1.1 Fetch Manufacturer List
```groovy
def fetchManufacturerList() {
    try {
        def params = [
            uri: "https://api.github.com",
            path: "/repos/smartHomeHub/SmartIR/contents/codes/climate",
            headers: [
                "Accept": "application/vnd.github.v3+json",
                "User-Agent": "Hubitat-HVAC-Wizard"
            ],
            timeout: 10
        ]

        httpGet(params) { resp ->
            if (resp.status == 200) {
                def manufacturers = []
                resp.data.each { file ->
                    if (file.name.endsWith(".json")) {
                        // Extract manufacturer from filename or first fetch
                        manufacturers.add(file.name.replaceAll(/\.json$/, ""))
                    }
                }
                return manufacturers.unique().sort()
            }
        }
    } catch (e) {
        log.error "Failed to fetch manufacturers: ${e.message}"
        // Return hardcoded list as fallback
        return getHardcodedManufacturers()
    }
}
```

#### 1.2 Fetch Models for Manufacturer
```groovy
def fetchModelsForManufacturer(String manufacturer) {
    // Check cache first
    if (isCacheValid() && state.smartirCache?.manufacturers?[manufacturer]) {
        log.debug "Using cached models for ${manufacturer}"
        return state.smartirCache.manufacturers[manufacturer]
    }

    def models = [:]

    try {
        // Get file list
        def params = [
            uri: "https://api.github.com",
            path: "/repos/smartHomeHub/SmartIR/contents/codes/climate",
            headers: [
                "Accept": "application/vnd.github.v3+json",
                "User-Agent": "Hubitat-HVAC-Wizard"
            ]
        ]

        httpGet(params) { resp ->
            // Filter files for this manufacturer
            def manufacturerFiles = resp.data.findAll { file ->
                file.name.endsWith(".json")
            }

            // Fetch each model file
            manufacturerFiles.each { file ->
                def modelId = file.name.replaceAll(/\.json$/, "")
                def modelData = fetchModelFile(file.download_url)

                // Only include if manufacturer matches
                if (modelData?.manufacturer?.toLowerCase() == manufacturer.toLowerCase()) {
                    models[modelId] = modelData
                }
            }
        }

        // Cache the results
        if (!state.smartirCache) state.smartirCache = [:]
        if (!state.smartirCache.manufacturers) state.smartirCache.manufacturers = [:]
        state.smartirCache.manufacturers[manufacturer] = models
        state.smartirCache.lastFetched = now()

        return models

    } catch (e) {
        log.error "Failed to fetch models for ${manufacturer}: ${e.message}"
        return [:]
    }
}
```

#### 1.3 Fetch Individual Model File
```groovy
def fetchModelFile(String downloadUrl) {
    try {
        def params = [
            uri: downloadUrl,
            headers: [
                "User-Agent": "Hubitat-HVAC-Wizard"
            ],
            timeout: 10
        ]

        httpGet(params) { resp ->
            if (resp.status == 200) {
                return resp.data  // Already parsed as JSON by Hubitat
            }
        }
    } catch (e) {
        log.error "Failed to fetch model file: ${e.message}"
        return null
    }
}
```

### 2. Code Matching Algorithm

```groovy
def matchCodeToModel(String learnedCode, String manufacturer) {
    def models = state.smartirCache?.manufacturers?[manufacturer]

    if (!models) {
        log.warn "No models cached for manufacturer: ${manufacturer}"
        return null
    }

    // Normalize learned code (remove whitespace)
    String normalizedCode = learnedCode.replaceAll(/\s/, "")

    // Search through all models
    for (modelEntry in models) {
        def modelId = modelEntry.key
        def modelData = modelEntry.value

        // Check OFF command first (common shortcut)
        if (normalizedCode == modelData.commands?.off?.replaceAll(/\s/, "")) {
            return [
                smartIrId: modelId,
                manufacturer: modelData.manufacturer,
                model: modelData.supportedModels?.join(", "),
                modelData: modelData,
                detectedState: [mode: "off", temp: null, fan: null]
            ]
        }

        // Check all mode/fan/temp combinations
        for (mode in modelData.commands?.keySet()) {
            if (mode == "off") continue  // Already checked

            def modeCommands = modelData.commands[mode]
            if (!(modeCommands instanceof Map)) continue

            for (fanSpeed in modeCommands.keySet()) {
                def fanCommands = modeCommands[fanSpeed]
                if (!(fanCommands instanceof Map)) continue

                for (temp in fanCommands.keySet()) {
                    def irCode = fanCommands[temp]
                    if (normalizedCode == irCode?.replaceAll(/\s/, "")) {
                        return [
                            smartIrId: modelId,
                            manufacturer: modelData.manufacturer,
                            model: modelData.supportedModels?.join(", "),
                            modelData: modelData,
                            detectedState: [
                                mode: mode,
                                temp: temp.toInteger(),
                                fan: fanSpeed
                            ]
                        ]
                    }
                }
            }
        }
    }

    log.warn "No match found for learned code in ${manufacturer} models"
    return null
}
```

### 3. Wizard App Pages

Already outlined in hvac-setup-app.groovy, needs implementation of:
- SmartIR integration calls
- Event subscription handling
- Proper button handling
- Error handling and retry logic

### 4. Testing Infrastructure

Create `tst/HubitatAppFacade.groovy` to test apps similar to driver facade.

## Success Criteria

- [ ] App fetches manufacturers from GitHub successfully
- [ ] App fetches and caches SmartIR models
- [ ] Code matching algorithm finds correct model (>90% accuracy)
- [ ] Wizard navigates through all pages correctly
- [ ] Configuration is saved to driver successfully
- [ ] Runtime commands work after configuration
- [ ] All tests pass
- [ ] Cache invalidation works correctly (24 hour expiry)
- [ ] Offline fallback to hardcoded manufacturers works

## Next Steps

1. ✅ Create this implementation plan
2. Implement SmartIR API integration functions
3. Implement code matching algorithm
4. Fix wizard app pages and navigation
5. Create app testing infrastructure
6. Add comprehensive tests
7. Run full test suite and fix issues
8. Documentation and README updates
