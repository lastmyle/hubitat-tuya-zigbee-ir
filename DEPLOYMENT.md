# Deployment Guide

This document explains how to deploy the Hubitat Tuya Zigbee IR Remote Control driver and HVAC Setup Wizard app to your Hubitat hub.

## Prerequisites

### First Time Setup

If you're developing or testing this driver, you'll need Groovy 2.4.x installed:

```bash
# Install Groovy via SDKMAN
make setup

# Activate SDKMAN in your shell
source ~/.sdkman/bin/sdkman-init.sh

# Or add to your ~/.bashrc or ~/.zshrc:
export SDKMAN_DIR="$HOME/.sdkman"
[[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]] && source "$SDKMAN_DIR/bin/sdkman-init.sh"
```

This will install:
- SDKMAN (Software Development Kit Manager)
- Groovy 2.4.19 (matches Docker test environment and Hubitat's Groovy 2.4.x)

## Quick Start

### Deployment Commands

```bash
# Validate syntax before deploying
make validate

# Deploy both driver and app (recommended)
make deploy

# Deploy only the driver
make deploy-driver

# Deploy only the app
make deploy-app
```

Each deployment command will:
1. Validate the Groovy syntax
2. Copy the code to your clipboard
3. Provide step-by-step instructions for manual deployment

### Manual Paste Steps

After running a deploy command, the code will be in your clipboard. Follow these steps:

#### For Driver Deployment

1. Open your Hubitat hub web interface (usually http://hubitat.local or http://192.168.x.x)
2. Navigate to **Drivers Code** → **New Driver**
3. Paste the code (Cmd+V / Ctrl+V)
4. Click **Save**

#### For App Deployment

1. Open your Hubitat hub web interface
2. Navigate to **Apps Code** → **New App**
3. Paste the code (Cmd+V / Ctrl+V)
4. Click **Save**

## Setup After Deployment

### 1. Add a Device

After deploying the driver:

1. Go to **Devices** → **Add Device**
2. Choose **Virtual**
3. Enter a device name (e.g., "Living Room IR Blaster")
4. Select **Lastmyle Tuya Zigbee IR Remote Control** as the driver
5. Click **Save Device**
6. Follow the Zigbee pairing instructions for your Tuya IR blaster

### 2. Run the HVAC Setup Wizard

After deploying the app:

1. Go to **Apps** → **Add User App**
2. Select **HVAC Setup Wizard**
3. Follow the wizard steps:
   - Select your IR blaster device
   - Choose your HVAC manufacturer (optional hint for faster detection)
   - Learn an IR code from your physical remote
   - Verify the detected model and protocol
   - Complete setup

### 3. Test the Integration

Once configured, test the HVAC commands in the device page:

```groovy
// Try these commands:
hvacTurnOff()
hvacSendCommand("cool", 24, "auto")
hvacRestoreState()
```

## Development Workflow

```bash
# 1. Make code changes
vim driver.groovy

# 2. Run tests
make test

# 3. Validate syntax
make validate

# 4. Deploy to test hub
make deploy

# 5. Test on real hardware
# (Open Hubitat device page and test commands)
```

## Troubleshooting

### Syntax Validation Fails

If `make validate` reports errors:

1. Make sure you're in the project root directory
2. Check that Groovy is installed: `groovy -version`
3. Review the error message for specific syntax issues
4. Run `make setup` if Groovy is not installed

### Device Not Detected

If the wizard can't detect your HVAC model:

1. Try learning different buttons (OFF command often works best)
2. Ensure the IR blaster LED lights up during learning
3. Check device logs: **Devices** → select device → **Logs**
4. The wizard uses local IRremoteESP8266 protocol detection (no internet required)
5. Supported protocols: Fujitsu, Daikin, Panasonic, Mitsubishi, LG, Samsung, Gree, and 20+ more

### Commands Not Working

If HVAC commands don't work after setup:

1. Check the device logs: **Devices** → select device → **Logs**
2. Verify the configuration was saved: check `hvacConfigured` attribute
3. Make sure the IR blaster has line-of-sight to the HVAC unit
4. Try re-running the setup wizard

### Protocol Detection Issues

The wizard uses local protocol detection based on IR timing patterns:

- **No internet required** - All protocol databases are embedded in the app
- **Manufacturer hint optional** - Detection works without selecting manufacturer
- **Confidence scores** - Higher scores indicate better matches
- **20+ protocols supported** - IRremoteESP8266 database included

## Available Make Commands

Run `make help` to see all available commands:

```bash
make help          # Show all available commands
make setup         # Install Groovy 2.4.x via SDKMAN
make test          # Run all tests
make validate      # Validate code syntax
make deploy        # Deploy both driver and app
make deploy-driver # Deploy only the driver
make deploy-app    # Deploy only the app
```

## Environment Variables

```bash
# Optional: Set your hub address for reference
export HUBITAT_HOST=192.168.1.100

# Or add to your ~/.bashrc or ~/.zshrc
echo 'export HUBITAT_HOST=192.168.1.100' >> ~/.bashrc
```

Note: Manual deployment via clipboard is the most reliable method for Hubitat. There is no official API for automated code uploads.

## See Also

- [README.md](README.md) - Project overview and usage examples
- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture and design
- [CODE_REVIEW.md](CODE_REVIEW.md) - Expert code review and recommendations
- [PERFORMANCE.md](PERFORMANCE.md) - Performance analysis and optimization
