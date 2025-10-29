/**
 * HVAC Setup Wizard App
 *
 * Multi-page configuration wizard for Tuya Zigbee IR Remote Controls
 * Uses local IR protocol detection and code generation
 */


import groovy.transform.Field

/*********
 * HUBITAT APP CODE
 */

definition(
    name: "Maestro HVAC Setup Wizard",
    namespace: "hubitat.lastmyle.maestr",
    author: "Lastmyle",
    description: "Configure HVAC IR remotes with automatic model detection",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "selectDevice")
    page(name: "learnCode")
    page(name: "verifyModel")
    page(name: "complete")
}

/*********
 * CONSTANTS
 */

// Maestro API endpoint
@Field static final String MAESTRO_API_URL = "https://maestro-tuya-ir.vercel.app"

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
                      "2. Learn an IR code from your remote\n" +
                      "3. Automatically detect protocol using cloud API\n" +
                      "4. Generate complete command set for your HVAC\n" +
                      "5. Configure the device for complete HVAC control"
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
    dynamicPage(name: "selectDevice", title: "Select IR Blaster", install: false, nextPage: "learnCode", refreshInterval: irDevice && !getDeviceStatus(irDevice).online ? 5 : 0) {
        section("Device Selection") {
            input "irDevice", "device.MaestroTuyaZigbeeIRRemoteControl",
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
            subscribe(irDevice, "lastLearnedCode", "codeLearnedHandler")
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
                paragraph "Code preview: <code>${state.wizardState.learnedCode}</code>"

                // Show detection status
                if (state.wizardState.detectedModel) {
                    paragraph "<hr>"
                    paragraph "✅ <b style='color: green;'>Model Auto-Detected!</b>"
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
                paragraph "• Protocol not in Maestro API database"
                paragraph "• IR code was not learned correctly"
                paragraph "• Remote uses an uncommon or proprietary protocol"
            }

            section("What To Do") {
                paragraph "Try these steps:"
                href "learnCode", title: "Learn Code Again", description: "Retry with a different button (try Cool/24°C/Auto)"
                paragraph ""
                paragraph "If detection continues to fail, your device may use an uncommon protocol."
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
 * Identify protocol from learned IR code and generate full command set.
 *
 * Calls Maestro API to detect protocol and generate commands:
 * 1. Send Tuya Base64 code to API
 * 2. API returns protocol info and complete command set
 */
def matchCodeToModel(String learnedCode) {
    log.debug "matchCodeToModel() called - using Maestro API"
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
        log.info "Calling Maestro API to identify protocol..."

        // Call API to identify protocol from Tuya code
        def requestBody = [
            tuya_code: normalizedCode
        ]

        def params = [
            uri: MAESTRO_API_URL + "/api/identify",
            headers: [
                "Content-Type": "application/json",
                "User-Agent": "Hubitat-HVAC-Wizard/1.0"
            ],
            body: requestBody,
            timeout: 30,
            contentType: "application/json"
        ]

        def result = null
        httpPost(params) { resp ->
            if (resp.status == 200) {
                result = resp.data
                log.info "✓ API response received"
                log.debug "Response: ${result}"
            } else {
                log.error "API returned status ${resp.status}"
                return null
            }
        }

        if (!result) {
            log.warn "No result from API"
            return null
        }

        // Check if protocol was detected
        if (!result.protocol) {
            log.warn "API could not identify protocol"
            if (result.error) {
                log.warn "API error: ${result.error}"
            }
            return null
        }

        log.info "✓ Protocol identified: ${result.protocol}"
        if (result.confidence) {
            log.info "  Confidence: ${result.confidence}"
        }

        // Return in format compatible with rest of the app
        return [
            smartIrId: result.protocol,
            model: result.model ?: result.protocol,
            modelData: [
                supportedModels: result.supportedModels ?: [],
                commands: result.commands ?: [:],
                minTemperature: result.minTemperature ?: 16,
                maxTemperature: result.maxTemperature ?: 30,
                operationModes: result.operationModes ?: [],
                fanModes: result.fanModes ?: []
            ],
            detectedState: result.detectedState ?: [mode: "unknown", temp: null, fan: null],
            protocolInfo: [
                protocol: result.protocol,
                confidence: result.confidence
            ],
            notes: result.notes
        ]

    } catch (Exception e) {
        log.error "Failed to call Maestro API: ${e.message}"
        log.error "Stack trace: ${e}"
        return null
    }
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
        subscribe(irDevice, "lastLearnedCode", "codeLearnedHandler")
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

                // Verify device has the learn command
                if (!irDevice.hasCommand("learn")) {
                    log.error "Device does not have 'learn' command!"
                    log.error "Available commands: ${irDevice.supportedCommands.collect { it.name }}"
                    state.wizardState.learningStatus = "Error: Device missing learn command"
                    return
                }

                // Call driver's learn method
                log.info "Calling irDevice.learn('wizard')"
                log.debug "Device ID: ${irDevice.id}, Device DNI: ${irDevice.deviceNetworkId}"

                def result = irDevice.learn("wizard")

                log.info "learn() returned: ${result}"

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
    log.info "Normalized code length: ${learnedCode.replaceAll(/\s/, '').length()}"

    try {
        def detectedModel = matchCodeToModel(learnedCode)

        if (detectedModel) {
            log.info "✅ Protocol Detected Successfully!"
            log.info "Protocol: ${detectedModel.smartIrId}"
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
