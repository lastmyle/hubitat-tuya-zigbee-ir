# Maestro Tuya Zigbee IR Remote Control

A Hubitat driver and companion setup wizard for Tuya Zigbee IR blasters (Model ZS06/TS1201). Turns a cheap Zigbee IR blaster into a full HVAC controller with automatic protocol detection, or a general-purpose IR remote for TVs, fans, and other IR-controlled devices.

## What It Does

- **HVAC Control**: Automatic protocol detection via the [Maestro API](https://github.com/lastmyle/maestro-tuya-ir) identifies your air conditioner's IR protocol and generates a complete command set (mode, temperature, fan speed). After one-time setup, all commands run locally with zero cloud dependency.
- **General IR Remote**: Learn any IR code from a physical remote and replay it via Hubitat automations. Map learned codes to virtual buttons for Rule Machine integration.
- **Zigbee Protocol**: Implements the full Tuya IR learning and sending protocol over Zigbee clusters 0xE004 and 0xED00, including chunked data transfer with CRC validation.

## Components

| File | Type | Purpose |
|------|------|---------|
| `driver.groovy` | Hubitat Device Driver | Zigbee communication, IR learn/send, HVAC command execution |
| `app.groovy` | Hubitat App | HVAC setup wizard with manufacturer selection and protocol detection |

## Quick Start

### HVAC Setup (Recommended)

1. Install both `driver.groovy` (as a Driver) and `app.groovy` (as an App) on your Hubitat hub
2. Pair your Tuya IR blaster via Zigbee - the driver auto-assigns via fingerprint matching
3. Open the **Maestro HVAC Setup Wizard** app
4. Select your IR blaster device
5. Pick your HVAC manufacturer from the dropdown, or learn an IR code from your physical remote
6. The wizard detects the protocol and generates all commands automatically
7. Confirm and save - your HVAC is now controllable from Hubitat

### General IR Remote

1. Install `driver.groovy` on your Hubitat hub
2. Pair your Tuya IR blaster
3. Use the **Learn** command with a code name (e.g. `PowerToggle`)
4. Point your physical remote at the IR blaster and press the button
5. Use **Map Button** to assign the learned code to a button number
6. Trigger via Rule Machine: "Push Button 1 on {Device}"

### Example: TV Power Toggle

1. Learn command: name it `PowerToggle`
2. Map Button: button `1` to `PowerToggle`
3. Create a Virtual Switch "Bedroom TV"
4. Rule Machine: when "Bedroom TV changed" → Push Button 1 on Tuya IR Blaster
5. Expose the virtual switch to Google Home / Alexa

![Rule Machine example](https://github.com/user-attachments/assets/42d39ca8-9441-4b54-8e9a-0f5f728d5610)

## Development

### Prerequisites

- macOS, Linux, or WSL
- Docker (for tests) or Groovy 2.4.19 via SDKMAN

### Commands

```bash
make setup     # Install Groovy 2.4.19 via SDKMAN
make test      # Run tests in Docker (preferred)
make validate  # Check syntax
make deploy    # Copy driver + app to clipboard for pasting into Hubitat
```

Tests run automatically on push to `main` via GitHub Actions.

### Deployment

Hubitat doesn't support API-based code uploads. Deployment copies the file to your clipboard for manual paste into the Hubitat web UI:

- **Driver**: Drivers Code > New Driver > Paste > Save
- **App**: Apps Code > New App > Paste > Save

See [DEPLOYMENT.md](DEPLOYMENT.md) for details.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture with diagrams
- [SECURITY.md](SECURITY.md) - Security model and threat analysis
- [DEPLOYMENT.md](DEPLOYMENT.md) - Deployment instructions
- [HVAC_SETUP_README.md](HVAC_SETUP_README.md) - Detailed HVAC setup guide

## Supported Devices

**IR Blasters:**
- Tuya ZS06 / TS1201 (manufacturer `_TZ3290_7v1k4vufotpowp9z`)
- Tuya TS1201 variant (manufacturer `_TZ3290_4axevryg`)

**HVAC Protocols** (via Maestro API):
Panasonic, Daikin, Fujitsu, Mitsubishi, LG, Gree, Hitachi, Samsung, and others supported by IRremoteESP8266.

## License

MIT
