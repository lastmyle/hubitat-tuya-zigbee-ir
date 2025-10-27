# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Hubitat driver for Tuya Zigbee IR Remote Controls (Model ZS06/TS1201). The driver enables learning and sending IR codes through a Zigbee-connected IR blaster device, integrating with Hubitat's rule engine and virtual devices.

## Development Commands

### Run Tests
```bash
# Run tests using Docker (preferred method)
make test

# Alternative: Direct Groovy execution (requires Groovy 2.4.16)
groovy -cp test test/all.groovy
```

### GitHub Actions
Tests run automatically on push to main branch via `.github/workflows/build.yml`

## Architecture

### Core Components

- **driver.groovy**: Main Hubitat driver implementing Zigbee communication protocol
  - Handles learn/send IR code sequences through hex-encoded struct messages
  - Implements PushableButton capability for Hubitat integration
  - Uses ConcurrentHashMap for managing send/receive buffers across message executions

### Communication Protocol

The driver implements a complex back-and-forth message sequence with the Tuya device:
- **Learn sequence**: 11-step protocol involving commands 0xe004 and 0xed00 clusters
- **Send sequence**: 7-step protocol for transmitting learned IR codes
- Messages use hex-encoded structs converted via `toPayload`/`toStruct` functions
- CRC checksums validate data chunks during transmission

### Testing Structure

- **test/all.groovy**: Test runner executing all test suites
- **test/HubitatDriverFacade.groovy**: Mock facade for Hubitat-specific APIs
- **test/MessageTests.groovy**: Protocol message handling tests
- **test/UtilsTests.groovy**: Utility function tests
- **test/EndToEndTests.groovy**: Full learn/send sequence tests

### Integration Approach

Since Hubitat lacks native IR code support, the driver uses a button mapping workaround:
1. IR codes are learned and stored by name
2. Virtual button numbers map to learned IR codes
3. Rule Machine triggers button pushes to send IR commands
4. Virtual devices represent controlled appliances

## Key Implementation Notes

- Driver based on Zigbee2MQTT/zigbee-herdsman implementation
- Uses @Field static Maps for semi-persistent data between message executions
- Zigbee cluster 0xED00 handles IR transmission protocol
- Zigbee cluster 0xE004 handles learn mode control
- Requires Groovy 2.4.x compatibility for Hubitat platform