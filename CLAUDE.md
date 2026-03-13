# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Communication Style

Don't appease me. Be direct and technical. Focus on facts and solutions, not validation.

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

## Git Conventions

### Pull Request Conventions

- Always use `--repo lastmyle/hubitat-tuya-zigbee-ir` with `gh` commands (SSH alias `github.com-lastmyle`)
- **Feature PRs**: automerge with `--squash` into the integration branch
- **Release PRs**: automerge with `--merge` (no squash) to preserve commit history
- PR bots: Copilot (review comments), Claude bot (code review), CodeRabbit (summary)

### PR Review Bot Handling

**MANDATORY**: After creating a PR, monitor it for review comments from CodeRabbit, Claude bot, and Copilot. Handle them automatically:

1. **Fetch review comments**: `gh api repos/lastmyle/hubitat-tuya-zigbee-ir/pulls/<number>/comments` and review threads via GraphQL
2. **Address actionable feedback**: If a bot flags a real issue (bug, security, missing test), fix it with a follow-up commit
3. **Resolve conversations**: After addressing or determining a comment is not actionable, resolve the thread via GraphQL `resolveReviewThread` mutation
4. **Codecov failures**: If Codecov reports coverage drops or missing tests, write the missing tests and push a follow-up commit
5. **Do not leave unresolved threads** — all conversations must be resolved for merge to proceed