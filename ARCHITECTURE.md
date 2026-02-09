# Architecture

## System Overview

The system has two distinct phases: a one-time setup phase that uses a cloud API, and a runtime phase that operates entirely locally.

```mermaid
graph TB
    subgraph "SETUP PHASE - One-time configuration"
        REMOTE[Physical HVAC Remote]
        WIZARD[HVAC Setup Wizard<br/>app.groovy]
        MAESTRO[Maestro API<br/>maestro-tuya-ir.vercel.app]

        REMOTE -->|IR signal| IR1[IR Blaster]
        IR1 -->|Zigbee| DRIVER1[Driver]
        DRIVER1 -->|lastLearnedCode event| WIZARD
        WIZARD -->|POST /api/identify| MAESTRO
        MAESTRO -->|Protocol + 200 commands| WIZARD
        WIZARD -->|setHvacConfig| DRIVER1
    end

    subgraph "RUNTIME PHASE - Every command, no network"
        USER[User / Rule Machine]
        DRIVER2[Driver<br/>driver.groovy]
        STATE[Local State<br/>~50KB IR codes]
        IR2[IR Blaster]
        HVAC[HVAC Unit]

        USER -->|hvacSendCommand| DRIVER2
        DRIVER2 -->|Lookup &lt;1ms| STATE
        STATE -->|Base64 IR code| DRIVER2
        DRIVER2 -->|Zigbee 50-100ms| IR2
        IR2 -->|38kHz IR| HVAC
    end

    DRIVER1 -.->|Config persisted locally| STATE
```

## Components

### Driver (`driver.groovy`)

The device driver handles two responsibilities:

1. **Zigbee IR Protocol** - Low-level communication with the Tuya IR blaster using a multi-step binary protocol over Zigbee clusters 0xE004 (learn mode) and 0xED00 (data transfer).

2. **HVAC Command Execution** - Looks up pre-stored IR codes by mode/temperature/fan and transmits them. All commands execute from local state with zero network calls.

### App (`app.groovy`)

A multi-page wizard that configures the driver. Three paths to identify the HVAC protocol:

1. **Manufacturer Selection** - Pick a brand from a dropdown, generate commands from known good codes
2. **IR Code Learning** - Learn a code from the physical remote, auto-detect protocol via API
3. **Manual Code Entry** - Paste a Tuya Base64 IR code for protocol detection

### Maestro API (`maestro-tuya-ir`)

A separate FastAPI service deployed on Vercel that handles IR protocol detection using IRremoteESP8266. See [maestro-tuya-ir](https://github.com/lastmyle/maestro-tuya-ir). The app calls three endpoints:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/manufacturers` | GET | List brands with known good codes |
| `/api/identify` | POST | Detect protocol from a Tuya Base64 IR code |
| `/api/generate-from-manufacturer` | POST | Generate full command set from known codes |

## Wizard Flow

```mermaid
stateDiagram-v2
    [*] --> Welcome
    Welcome --> SelectDevice
    SelectDevice --> SelectManufacturer

    SelectManufacturer --> VerifyModel: Manufacturer selected +<br/>Generate Commands
    SelectManufacturer --> LearnCode: Learn IR Code Manually
    SelectManufacturer --> VerifyModel: Paste code +<br/>Identify Protocol

    LearnCode --> VerifyModel: Code learned +<br/>Protocol detected
    LearnCode --> LearnCode: Retry

    VerifyModel --> Complete: User confirms
    VerifyModel --> LearnCode: Try again

    Complete --> [*]
```

## Zigbee IR Protocol

The driver implements Tuya's proprietary IR protocol, based on the zigbee-herdsman implementation. Data is chunked into 55-byte segments with CRC validation at each step.

### Learn Sequence (11 steps)

```mermaid
sequenceDiagram
    participant D as Driver
    participant B as IR Blaster
    participant R as Physical Remote

    D->>B: Enter learn mode (cluster 0xE004)
    B-->>D: ACK
    Note over B: LED turns on
    R->>B: IR signal from remote
    B->>D: Data transfer start (cluster 0xED00)
    D-->>B: ACK
    loop Chunked transfer
        B->>D: Data chunk (55 bytes + CRC)
        D-->>B: ACK
    end
    B->>D: Transfer complete
    D-->>B: ACK
    D->>D: Store learned code
```

### Send Sequence (7 steps)

```mermaid
sequenceDiagram
    participant D as Driver
    participant B as IR Blaster
    participant H as HVAC Unit

    D->>B: Start send (cluster 0xED00)
    B-->>D: Ready
    loop Chunked transfer
        D->>B: Data chunk (55 bytes + CRC)
        B-->>D: ACK
    end
    D->>B: Transfer complete
    B-->>D: ACK
    B->>H: IR signal (38kHz carrier)
```

### Message Format

IR codes are wrapped in a JSON envelope before chunked transmission:

```json
{
  "key_num": 1,
  "delay": 300,
  "key1": {
    "num": 1,
    "freq": 38000,
    "type": 1,
    "key_code": "<Base64 IR code>"
  }
}
```

The JSON is then hex-encoded and split into 55-byte chunks, each validated with a simple byte-sum CRC (mod 256).

## Data Flow: Setup via Manufacturer Selection

```mermaid
sequenceDiagram
    participant U as User
    participant A as App (Hubitat)
    participant M as Maestro API
    participant D as Driver (Hubitat)

    U->>A: Open wizard, select device
    A->>M: GET /api/manufacturers
    M-->>A: ["Panasonic", "Daikin", ...]
    A->>U: Show manufacturer dropdown

    U->>A: Select "Panasonic"
    U->>A: Click "Generate Commands"
    A->>M: POST /api/generate-from-manufacturer<br/>{manufacturer: "Panasonic"}
    M-->>A: {protocol, commands[], confidence}

    A->>U: Show detected protocol
    U->>A: Confirm
    A->>D: setHvacConfig(config)
    D->>D: Store 200+ IR codes in local state
    A->>U: Setup complete
```

## Data Flow: Setup via IR Learning

```mermaid
sequenceDiagram
    participant U as User
    participant A as App (Hubitat)
    participant D as Driver (Hubitat)
    participant B as IR Blaster
    participant R as Physical Remote
    participant M as Maestro API

    U->>A: Click "Learn IR Code Manually"
    A->>D: learn("wizard")
    D->>B: Enter learn mode
    Note over B: LED on, waiting for IR

    U->>R: Press button on remote
    R->>B: IR signal
    B->>D: Learned code via Zigbee
    D->>A: Event: lastLearnedCode

    A->>M: POST /api/identify<br/>{tuya_code: "Base64..."}
    M-->>A: {protocol: "FUJITSU_AC", commands[], confidence: 0.95}

    A->>U: Protocol detected, confirm?
    U->>A: Yes
    A->>D: setHvacConfig(config)
```

## Data Flow: Runtime Command

```mermaid
sequenceDiagram
    participant U as User / Rule Machine
    participant D as Driver
    participant S as Local State
    participant B as IR Blaster
    participant H as HVAC Unit

    U->>D: hvacSendCommand("cool", 24, "auto")
    D->>S: Lookup "24_cool_auto"
    S-->>D: Base64 IR code
    D->>D: Wrap in JSON envelope
    D->>D: Hex-encode + chunk (55 bytes)
    D->>B: Send via Zigbee (0xED00)
    B->>H: IR signal
    D->>D: Update currentState
    D->>U: Event: hvacMode=cool, hvacTemperature=24
```

## State Storage

### Driver State

```groovy
state.hvacConfig = [
    model: "FUJITSU_AC",
    commands: [
        [name: "power_off", tuya_code: "Base64..."],
        [name: "16_cool_auto", tuya_code: "Base64..."],
        [name: "24_heat_high", tuya_code: "Base64..."],
        // ~200 commands total
    ],
    minTemperature: 16,
    maxTemperature: 30,
    operationModes: ["cool", "heat", "dry", "fan", "auto"],
    fanModes: ["auto", "quiet", "low", "medium", "high"],
    currentState: [mode: "cool", temp: 24, fan: "auto"]
]

// General IR remote codes (non-HVAC)
state.learnedCodes = [
    "PowerToggle": "Base64...",
    "VolumeUp": "Base64..."
]
state.buttonMappings = [
    "1": "PowerToggle",
    "2": "VolumeUp"
]
```

### Semi-Persistent Buffers

The Zigbee protocol requires tracking state across multiple asynchronous message exchanges. The driver uses `@Field static ConcurrentHashMap` for this:

```
SEND_BUFFERS[deviceId][seqNum]    → {buffer: List<byte>}
RECEIVE_BUFFERS[deviceId][seqNum] → {expectedLength, buffer: List<byte>}
PENDING_LEARN_CODE_NAMES          → Stack of code names awaiting save
PENDING_RECEIVE_SEQS              → Stack of sequence IDs for receive ops
```

These survive between Hubitat method invocations but not hub reboots - which is fine since learn/send operations complete in seconds.

## Testing Architecture

```mermaid
graph LR
    subgraph "Test Infrastructure"
        RUNNER[all.groovy<br/>JUnit Runner]
        DFACADE[HubitatDriverFacade<br/>Mocks Hubitat Driver APIs]
        AFACADE[HubitatAppFacade<br/>Mocks Hubitat App APIs]
    end

    subgraph "Test Suites"
        MSG[MessageTests<br/>Protocol parsing]
        UTIL[UtilsTests<br/>CRC, encoding]
        E2E[EndToEndTests<br/>Full learn/send]
        HVAC[HvacWizardTests<br/>Wizard flow]
        EVT[HvacEventHandlingTests<br/>Event subscriptions]
        SVC[ServiceTests<br/>Protocol detection]
    end

    RUNNER --> MSG
    RUNNER --> UTIL
    RUNNER --> E2E
    RUNNER --> HVAC
    RUNNER --> EVT
    RUNNER --> SVC

    MSG --> DFACADE
    UTIL --> DFACADE
    E2E --> DFACADE
    HVAC --> AFACADE
    EVT --> AFACADE
    SVC --> AFACADE
```

The test facades parse the actual `driver.groovy` and `app.groovy` files, evaluate them in a `GroovyShell`, and inject mocks for Hubitat-specific APIs (`log`, `device`, `zigbee`, `state`, `httpGet`, `httpPost`, etc.). This lets tests run against the real code without a Hubitat hub.

## CI/CD

Tests run on every push to `main` via GitHub Actions using a `groovy:2.4-jre` Docker image to match the Hubitat platform's Groovy version exactly.

Deployment is manual: `make deploy` copies driver and app code to clipboard for pasting into the Hubitat web UI. There is no API-based deployment path for Hubitat.
