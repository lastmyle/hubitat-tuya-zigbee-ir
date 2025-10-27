# HVAC IR Remote Control - Architecture

## System Overview

```mermaid
graph TB
    subgraph "SETUP TIME - One-time configuration"
        INSTALLER[Installer]
        WIZARD[HVAC Setup Wizard App]
        GITHUB[GitHub SmartIR Database]

        INSTALLER -->|1. Runs wizard once| WIZARD
        WIZARD -->|2. Fetches IR codes| GITHUB
        GITHUB -->|3. Returns JSON| WIZARD
        WIZARD -->|4. Saves ALL codes locally| DRIVER_STATE[Driver State Storage]
    end

    subgraph "RUNTIME - Every command  sub-second"
        USER[User/Automation]
        DRIVER[Tuya IR Driver]
        STATE[Local State]
        IR_BLASTER[IR Blaster]
        HVAC[HVAC Unit]

        USER -->|hvacTurnOff| DRIVER
        DRIVER -->|Read offCommand <1ms| STATE
        STATE -->|Base64 IR code| DRIVER
        DRIVER -->|Zigbee 50-100ms| IR_BLASTER
        IR_BLASTER -->|IR signal 10-20ms| HVAC
    end

    DRIVER_STATE -.->|Persisted locally| STATE

    style WIZARD fill:#ccc,stroke:#999,stroke-dasharray: 5 5
    style GITHUB fill:#ccc,stroke:#999,stroke-dasharray: 5 5
    style DRIVER_STATE fill:#50C878,stroke:#2d5016,stroke-width:4px
    style STATE fill:#50C878,stroke:#2d5016,stroke-width:4px
    style DRIVER fill:#4A90E2,stroke:#1e5a8e,stroke-width:3px
```

## Performance Architecture

**CRITICAL REQUIREMENT: Sub-second OFF command**

The system uses a clear separation:

### Setup Time (One-time, can be slow)
- Wizard app fetches from GitHub API (500-2000ms)
- App matches learned IR code to database
- App saves **ALL IR codes** to driver local state (~50KB)
- **Total time**: 5-30 seconds (acceptable for one-time setup)

### Runtime (Every command, must be fast)
- Driver reads from local `state.hvacConfig.offCommand` (<1ms)
- Driver sends via Zigbee protocol (50-100ms)
- IR blaster emits signal (10-20ms)
- **Total time**: <200ms (well under 1 second requirement)

**Key Point**: After setup, NO network access required. ALL data is local.

See [PERFORMANCE.md](PERFORMANCE.md) for detailed performance analysis.

## Component Details

### 1. HVAC Setup Wizard App

**Purpose**: Multi-page configuration wizard for sparkies

**Responsibilities**:
- Present installer-friendly UI
- Fetch SmartIR database from GitHub
- Guide IR code learning process
- Match learned codes to HVAC models
- Write configuration to driver

**Key Pages**:
```mermaid
stateDiagram-v2
    [*] --> Welcome
    Welcome --> SelectDevice
    SelectDevice --> SelectManufacturer
    SelectManufacturer --> LearnCode
    LearnCode --> MatchModel
    MatchModel --> VerifyModel
    VerifyModel --> Complete
    VerifyModel --> LearnCode: Retry
    Complete --> [*]

    SelectManufacturer --> SelectDevice: Back
    LearnCode --> SelectManufacturer: Back
```

**State Storage**:
```groovy
state.manufacturer = "Panasonic"
state.learnedCode = "JgBQAAAB..."
state.detectedModel = {
    smartIrId: "1020",
    manufacturer: "Panasonic",
    model: "CS/CU-E9PKR",
    commands: { ... }
}
```

---

### 2. Tuya IR Remote Driver

**Purpose**: Device interface and IR protocol handler

**Responsibilities**:
- Zigbee communication with IR blaster
- IR code learning and transmission
- Store HVAC configuration
- Expose runtime commands
- Display readonly configuration attributes

**State Storage**:
```groovy
state.hvacConfig = [
    manufacturer: "Panasonic",
    model: "CS/CU-E9PKR",
    smartIrId: "1020",
    offCommand: "JgBQAAAB...",
    commands: {
        cool: {
            auto: {
                "16": "JgBQAAAB...",
                "17": "JgBQAAAB...",
                // ... 16-30°C
            },
            low: { ... },
            mid: { ... },
            high: { ... }
        },
        heat: { ... },
        fan_only: { ... }
    },
    currentState: [mode: "cool", temp: 24, fan: "auto"]
]
```

---

### 3. SmartIR Database

**Source**: https://github.com/smartHomeHub/SmartIR/tree/master/codes/climate

**Structure**:
```json
{
  "manufacturer": "Panasonic",
  "supportedModels": ["CS/CU-E9PKR"],
  "supportedController": "Broadlink",
  "commandsEncoding": "Base64",
  "minTemperature": 16,
  "maxTemperature": 30,
  "precision": 1,
  "operationModes": ["heat", "cool", "fan_only"],
  "fanModes": ["low", "mid", "high", "auto"],
  "commands": {
    "off": "JgBQAAAB...",
    "heat": {
      "low": {
        "16": "JgBQAAAB...",
        "17": "JgBQAAAB..."
      }
    },
    "cool": { ... }
  }
}
```

---

## Data Flow Diagrams

### Setup Wizard Flow

```mermaid
sequenceDiagram
    participant Installer
    participant App
    participant GitHub
    participant Driver
    participant IR_Blaster
    participant Remote

    Installer->>App: Start Setup Wizard
    App->>App: Show Welcome Page

    Installer->>App: Select IR Blaster Device
    App->>Driver: Check device capabilities
    Driver-->>App: Device ready

    Installer->>App: Click "Next"
    App->>GitHub: GET /smartHomeHub/SmartIR/climate
    GitHub-->>App: List of manufacturer files
    App->>App: Parse manufacturers
    App->>Installer: Show manufacturer dropdown

    Installer->>App: Select "Panasonic"
    App->>GitHub: GET 1000.json, 1020.json, etc.
    GitHub-->>App: Panasonic model JSON files
    App->>App: Parse available models

    Installer->>App: Click "Learn Code"
    App->>Driver: learnIrCode("wizard")
    Driver->>IR_Blaster: Enter learn mode (LED on)
    IR_Blaster-->>Driver: Ready

    Installer->>Remote: Press button (Cool 24°C)
    Remote->>IR_Blaster: IR signal
    IR_Blaster->>Driver: Captured code
    Driver->>App: Event: lastLearnedCode

    App->>App: Match code against Panasonic models
    App->>App: Found match: 1020.json
    App->>App: Extract state from matched code
    App->>Installer: Show detected model & state

    Installer->>App: Confirm "Yes"
    App->>Driver: setHvacConfig(full_config)
    Driver->>Driver: Store config in state
    Driver->>App: Config saved
    App->>Installer: Setup Complete!
```

### Runtime Turn Off/Restore Flow

```mermaid
sequenceDiagram
    participant User
    participant RuleMachine
    participant Driver
    participant IR_Blaster
    participant HVAC

    Note over User,HVAC: Scenario: User leaves home

    User->>RuleMachine: Trigger "Leaving Home" rule
    RuleMachine->>Driver: hvacTurnOff()
    Driver->>Driver: Load state.hvacConfig.offCommand
    Driver->>Driver: sendCode(offCommand)
    Driver->>IR_Blaster: Zigbee: transmit IR code
    IR_Blaster->>HVAC: IR: OFF signal
    HVAC->>HVAC: Power off
    Driver->>Driver: Update currentState = "off"
    Driver->>User: Event: hvacCurrentState = "OFF"

    Note over User,HVAC: 2 hours later: User arrives home

    User->>RuleMachine: Trigger "Arriving Home" rule
    RuleMachine->>Driver: hvacRestoreState()
    Driver->>Driver: Load hvacLastOnCommand
    Driver->>Driver: sendCode(lastOnCommand)
    Driver->>IR_Blaster: Zigbee: transmit IR code
    IR_Blaster->>HVAC: IR: Cool 24°C Auto signal
    HVAC->>HVAC: Power on, Cool 24°C, Auto fan
    Driver->>Driver: Update currentState
    Driver->>User: Event: hvacCurrentState = "COOL 24°C Fan:AUTO"
```

### Manual Command Flow

```mermaid
sequenceDiagram
    participant User
    participant Dashboard
    participant Driver
    participant IR_Blaster
    participant HVAC

    User->>Dashboard: Click "Cool 22°C High"
    Dashboard->>Driver: hvacSendCommand("cool", 22, "high")
    Driver->>Driver: Lookup: commands["cool"]["high"]["22"]
    Driver->>Driver: Found: "JgBQAAAB..."
    Driver->>Driver: sendCode("JgBQAAAB...")
    Driver->>IR_Blaster: Zigbee: transmit IR code
    IR_Blaster->>HVAC: IR: Cool 22°C High signal
    HVAC->>HVAC: Set cool mode, 22°C, high fan
    Driver->>Driver: Update currentState & lastOnCommand
    Driver->>User: Event: hvacCurrentState = "COOL 22°C Fan:HIGH"
```

---

## API Integration

### GitHub API Calls

```mermaid
sequenceDiagram
    participant App
    participant GitHub_API
    participant Raw_Content

    App->>GitHub_API: GET /repos/smartHomeHub/SmartIR/contents/codes/climate
    GitHub_API-->>App: JSON array of files

    Note over App: Extract manufacturer from each file

    App->>App: User selects "Panasonic"
    App->>App: Filter files for Panasonic

    loop For each Panasonic file (1000.json, 1020.json, etc.)
        App->>Raw_Content: GET raw.githubusercontent.com/.../1020.json
        Raw_Content-->>App: SmartIR JSON
        App->>App: Parse and store model data
    end

    Note over App: All Panasonic models loaded
```

### Code Matching Algorithm

```mermaid
flowchart TD
    START([Learned IR Code]) --> LOAD[Load all manufacturer models]
    LOAD --> CHECKOFF{Check against<br/>OFF commands}
    CHECKOFF -->|Match| FOUNDOFF[Found: OFF state]
    CHECKOFF -->|No match| LOOPMODES[Loop through modes]

    LOOPMODES --> LOOPFAN[Loop through fan speeds]
    LOOPFAN --> LOOPTEMP[Loop through temps 16-30]
    LOOPTEMP --> COMPARE{Code matches?}
    COMPARE -->|Yes| EXTRACT[Extract mode/temp/fan]
    COMPARE -->|No| NEXTTEMP[Next temperature]
    NEXTTEMP --> LOOPTEMP

    EXTRACT --> FOUND[Found: Model + State]
    FOUNDOFF --> FOUND
    FOUND --> RETURN([Return model & state])

    LOOPTEMP --> NEXTFAN{More fans?}
    NEXTFAN -->|Yes| LOOPFAN
    NEXTFAN -->|No| NEXTMODE{More modes?}
    NEXTMODE -->|Yes| LOOPMODES
    NEXTMODE -->|No| NOTFOUND[Not Found]
    NOTFOUND --> RETURN

    style FOUND fill:#50C878
    style NOTFOUND fill:#E85D75
```

---

## Device State Machine

```mermaid
stateDiagram-v2
    [*] --> NotConfigured
    NotConfigured --> Configured: App calls setHvacConfig()
    Configured --> Off: hvacTurnOff()
    Off --> Cool: hvacSendCommand(cool,...)
    Off --> Heat: hvacSendCommand(heat,...)
    Off --> FanOnly: hvacSendCommand(fan_only,...)
    Off --> LastState: hvacRestoreState()

    Cool --> Off: hvacTurnOff()
    Heat --> Off: hvacTurnOff()
    FanOnly --> Off: hvacTurnOff()

    Cool --> Cool: hvacSendCommand(cool,...)
    Heat --> Heat: hvacSendCommand(heat,...)
    FanOnly --> FanOnly: hvacSendCommand(fan_only,...)

    Cool --> Heat: hvacSendCommand(heat,...)
    Heat --> Cool: hvacSendCommand(cool,...)

    Configured --> NotConfigured: clearHvacConfig()

    note right of NotConfigured
        hvacConfigured = "No"
        No commands available
    end note

    note right of Configured
        hvacConfigured = "Yes"
        All commands available
    end note

    note right of Cool
        hvacCurrentState = "COOL 24°C Fan:AUTO"
        hvacLastOnCommand = stored
    end note
```

---

## Storage Architecture

### Driver State

```mermaid
erDiagram
    DRIVER ||--o{ HVAC_CONFIG : stores
    DRIVER ||--o{ LEARNED_CODES : stores
    DRIVER ||--o{ MAPPED_BUTTONS : stores

    HVAC_CONFIG {
        string manufacturer
        string model
        string smartIrId
        string offCommand
        map commands
        map currentState
    }

    LEARNED_CODES {
        string codeName
        string base64Code
    }

    MAPPED_BUTTONS {
        int buttonNumber
        string codeName
    }

    HVAC_CONFIG ||--|| COMMANDS : contains
    COMMANDS {
        map cool
        map heat
        map fan_only
    }

    COMMANDS ||--o{ MODE_COMMANDS : contains
    MODE_COMMANDS {
        map auto
        map low
        map mid
        map high
    }

    MODE_COMMANDS ||--o{ TEMP_COMMANDS : contains
    TEMP_COMMANDS {
        string temp_16
        string temp_17
        string temp_30
    }
```

### App State

```mermaid
erDiagram
    APP ||--o{ WIZARD_STATE : stores
    APP ||--o{ SMARTIR_CACHE : stores

    WIZARD_STATE {
        string currentPage
        string selectedManufacturer
        string learnedCode
        map detectedModel
        map detectedState
    }

    SMARTIR_CACHE {
        string manufacturer
        array modelFiles
        map modelData
    }

    SMARTIR_CACHE ||--o{ MODEL_FILE : contains
    MODEL_FILE {
        string smartIrId
        string manufacturer
        array supportedModels
        map commands
        array operationModes
        array fanModes
        int minTemperature
        int maxTemperature
    }
```

---

## Error Handling

```mermaid
flowchart TD
    START([User Action]) --> TRY{Try Operation}

    TRY -->|Success| SUCCESS[Execute Command]
    TRY -->|API Error| APIERR[GitHub API Failed]
    TRY -->|Config Error| CONFERR[HVAC Not Configured]
    TRY -->|Match Error| MATCHERR[Code Not Found in DB]
    TRY -->|IR Error| IRERR[IR Learning Failed]

    APIERR --> FALLBACK1{Cached Data?}
    FALLBACK1 -->|Yes| USECACHE[Use Cached Models]
    FALLBACK1 -->|No| SHOWLIST[Show Hardcoded List]
    USECACHE --> SUCCESS
    SHOWLIST --> SUCCESS

    CONFERR --> PROMPT1[Show Error Message]
    PROMPT1 --> SUGGEST1[Suggest: Run Setup Wizard]

    MATCHERR --> PROMPT2[Show Error Message]
    PROMPT2 --> SUGGEST2[Options: Retry / Manual Entry]

    IRERR --> PROMPT3[Show Error Message]
    PROMPT3 --> SUGGEST3[Check: LED / Distance / Battery]

    SUCCESS --> LOG[Log Success]
    SUGGEST1 --> LOG
    SUGGEST2 --> LOG
    SUGGEST3 --> LOG
    LOG --> END([Complete])

    style SUCCESS fill:#50C878
    style APIERR fill:#FFB84D
    style CONFERR fill:#E85D75
    style MATCHERR fill:#E85D75
    style IRERR fill:#E85D75
```

---

## Security & Permissions

```mermaid
flowchart LR
    subgraph "Public Access"
        GITHUB[GitHub API<br/>No Auth Required]
        SMARTIR[SmartIR Repo<br/>Public]
    end

    subgraph "Hubitat Platform"
        APP[Setup Wizard App]
        DRIVER[IR Remote Driver]
        STATE[Device State Storage]
    end

    subgraph "Network Access"
        HTTPS[HTTPS Only]
        LOCAL[Local Network<br/>Zigbee]
    end

    GITHUB -->|Public API| HTTPS
    HTTPS --> APP
    APP -->|Write Config| DRIVER
    DRIVER -->|Store| STATE
    DRIVER -->|Zigbee| LOCAL
    LOCAL --> IRBLASTER[IR Blaster]

    style GITHUB fill:#4A90E2
    style STATE fill:#FFB84D
    style HTTPS fill:#50C878
```

---

## Performance Considerations

### App Initialization
- **First load**: Fetch manufacturer list from GitHub (~500ms)
- **Caching**: Store manufacturer list for 24 hours
- **Lazy loading**: Only fetch specific manufacturer models when selected

### Code Matching
- **Worst case**: ~4800 comparisons (4 modes × 4 fans × 30 temps)
- **Optimization**: Check OFF command first
- **Early exit**: Return immediately on first match

### State Storage
- **Driver state**: ~50-200KB per device (full command tree)
- **App state**: ~5-10KB (temporary wizard state)
- **Cleanup**: Clear wizard state after completion

---

## Deployment

```mermaid
flowchart TD
    DEV[Developer] -->|1. Upload Driver| HUBITAT[Hubitat Hub]
    DEV -->|2. Upload App| HUBITAT

    HUBITAT -->|3. Pair| IRBLASTER[Pair IR Blaster<br/>via Zigbee]

    INSTALLER[Installer] -->|4. Install App| HUBITAT
    INSTALLER -->|5. Run Wizard| APP[HVAC Setup Wizard]

    APP -->|6. Configure| DRIVER[Driver Instance]
    DRIVER -->|7. Link| IRBLASTER

    USER[End User] -->|8. Use| DASHBOARD[Dashboard]
    USER -->|9. Automate| RULES[Rule Machine]

    DASHBOARD --> DRIVER
    RULES --> DRIVER
    DRIVER --> IRBLASTER

    style DEV fill:#4A90E2
    style INSTALLER fill:#FFB84D
    style USER fill:#50C878
```

