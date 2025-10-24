/**
 * HVAC Setup Wizard App
 *
 * Multi-page configuration wizard for Tuya Zigbee IR Remote Controls
 * Integrates with SmartIR database to identify and configure HVAC models
 */

definition(
    name: "HVAC Setup Wizard",
    namespace: "hubitat.anasta.si",
    author: "Lastmyle",
    description: "Configure HVAC IR remotes with automatic model detection",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "selectDevice")
    page(name: "selectManufacturer")
    page(name: "learnCode")
    page(name: "verifyModel")
    page(name: "complete")
}

/*********
 * CONSTANTS
 */

// Cache expiry: 24 hours in milliseconds
@Field static final long CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000

// SmartIR GitHub URLs
@Field static final String SMARTIR_API_BASE = "https://api.github.com/repos/smartHomeHub/SmartIR"
@Field static final String SMARTIR_CONTENTS_PATH = "/contents/codes/climate"
@Field static final String SMARTIR_RAW_BASE = "https://raw.githubusercontent.com/smartHomeHub/SmartIR/master/codes/climate"

// Hardcoded manufacturers as fallback
@Field static final List<String> FALLBACK_MANUFACTURERS = [
    "Carrier", "Daikin", "Fujitsu", "Gree", "LG", "Midea",
    "Mitsubishi", "Panasonic", "Samsung", "Toshiba"
]

/*********
 * PAGES
 */

def mainPage() {
    dynamicPage(name: "mainPage", title: "HVAC Setup Wizard", uninstall: true, install: false, nextPage: "selectDevice") {
        section("Welcome") {
            paragraph "This wizard will help you configure your HVAC IR remote control using the SmartIR database."
            paragraph "You will need:\n• The physical HVAC remote\n• Access to the IR blaster device"
        }

        section("How It Works") {
            paragraph "The wizard will:\n" +
                      "1. Select your IR blaster device\n" +
                      "2. Choose your HVAC manufacturer\n" +
                      "3. Learn an IR code from your remote\n" +
                      "4. Automatically detect your HVAC model\n" +
                      "5. Configure the device for complete HVAC control"
        }

        section("SmartIR Integration") {
            paragraph "This wizard uses the SmartIR database (github.com/smartHomeHub/SmartIR) " +
                      "which contains IR codes for thousands of HVAC models."
        }
    }
}

def selectDevice() {
    dynamicPage(name: "selectDevice", title: "Select IR Blaster", install: false, nextPage: "selectManufacturer") {
        section("Device Selection") {
            input "irDevice", "capability.pushableButton",
                  title: "Select Tuya IR Remote Device",
                  required: true,
                  multiple: false,
                  submitOnChange: true
        }

        if (irDevice) {
            section("Selected Device") {
                paragraph "Device: ${irDevice.displayName}"
                paragraph "Status: Ready for configuration"

                // Check if device has required methods
                if (!deviceHasHvacSupport(irDevice)) {
                    paragraph "<span style='color:red'>⚠️ Warning: This device may not support HVAC configuration. " +
                              "Make sure you're using the Lastmyle Tuya Zigbee IR Remote Control driver.</span>"
                }
            }
        }
    }
}

def selectManufacturer() {
    // Fetch manufacturers from SmartIR API (or use cache)
    def manufacturers = getManufacturerList()

    dynamicPage(name: "selectManufacturer", title: "Select Manufacturer", install: false, nextPage: "learnCode") {
        section("HVAC Manufacturer") {
            if (manufacturers) {
                input "hvacManufacturer", "enum",
                      title: "Select your HVAC brand",
                      options: manufacturers,
                      required: true,
                      submitOnChange: true
            } else {
                paragraph "⚠️ Unable to load manufacturers from SmartIR database."
                paragraph "Using fallback manufacturer list..."
                input "hvacManufacturer", "enum",
                      title: "Select your HVAC brand",
                      options: FALLBACK_MANUFACTURERS,
                      required: true,
                      submitOnChange: true
            }
        }

        if (hvacManufacturer) {
            section("Loading Models") {
                paragraph "Fetching models for ${hvacManufacturer}..."

                // Pre-fetch models for this manufacturer
                def models = fetchModelsForManufacturer(hvacManufacturer)

                if (models && models.size() > 0) {
                    paragraph "✓ Found ${models.size()} model(s) for ${hvacManufacturer}"
                } else {
                    paragraph "⚠️ Could not load models. You may still proceed, " +
                              "but automatic detection may not work."
                }
            }
        }
    }
}

def learnCode() {
    dynamicPage(name: "learnCode", title: "Learn IR Code", install: false, nextPage: "verifyModel") {
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

            // Check if code was learned
            if (state.wizardState?.learnedCode) {
                paragraph "✅ <b>Code learned successfully!</b>"
                paragraph "Code preview: ${state.wizardState.learnedCode.take(50)}..."

                if (state.wizardState.matchError) {
                    paragraph "⚠️ <b>Detection Status:</b> ${state.wizardState.matchError}"
                    paragraph "You can still proceed and manually verify the model."
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
    }
}

def verifyModel() {
    // Get detection results from state
    def detectedModel = state.wizardState?.detectedModel

    dynamicPage(name: "verifyModel", title: "Verify Model", install: false) {
        if (detectedModel) {
            section("Model Detected! ✅") {
                paragraph "<b>Successfully identified your HVAC model!</b>"
                paragraph ""
                paragraph "<b>Manufacturer:</b> ${detectedModel.manufacturer}"
                paragraph "<b>Model:</b> ${detectedModel.model}"
                paragraph "<b>SmartIR ID:</b> ${detectedModel.smartIrId}"
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
                    href "selectManufacturer", title: "Change Manufacturer", description: "Go back to manufacturer selection"
                }

                if (confirmModel == true) {
                    href "complete", title: "Complete Setup", description: "Save configuration to device"
                }
            }
        } else {
            section("Model Not Detected ⚠️") {
                paragraph "<b>Could not automatically identify HVAC model</b>"
                paragraph ""
                paragraph "This could mean:"
                paragraph "• Model not in SmartIR database"
                paragraph "• IR code was not learned correctly"
                paragraph "• Manufacturer selection was incorrect"
                paragraph "• SmartIR API is unavailable"
            }

            section("What To Do") {
                paragraph "You have these options:"
                href "selectManufacturer", title: "Try Different Manufacturer", description: "Go back to manufacturer selection"
                href "learnCode", title: "Learn Code Again", description: "Retry learning with a different button"
                paragraph ""
                paragraph "Or continue with manual configuration:"
                input "manualModelId", "text",
                      title: "Manual SmartIR Model ID (advanced)",
                      description: "Enter SmartIR model ID if you know it (e.g., 1020)",
                      required: false,
                      submitOnChange: true

                if (manualModelId) {
                    paragraph "Manual mode not yet implemented in this version."
                    paragraph "Please try automatic detection or contact support."
                }
            }

            section("Need Help?") {
                paragraph "• Check SmartIR database: github.com/smartHomeHub/SmartIR/tree/master/codes/climate"
                paragraph "• Find your manufacturer and model"
                paragraph "• Note the file number (e.g., 1020.json)"
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
                paragraph "<b>Manufacturer:</b> ${hvacManufacturer}"
                paragraph "<b>Model:</b> ${state.wizardState?.detectedModel?.model}"
                paragraph "<b>SmartIR ID:</b> ${state.wizardState?.detectedModel?.smartIrId}"
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

        section("Re-run Setup") {
            href "mainPage", title: "Configure Another Device", description: "Start wizard again"
        }
    }
}

/*********
 * API INTEGRATION
 */

/**
 * Get list of manufacturers from SmartIR database
 * Uses cache if available and valid (< 24 hours old)
 */
def getManufacturerList() {
    // Check cache first
    if (isCacheValid() && state.smartirCache?.manufacturers) {
        log.debug "Using cached manufacturer list"
        return state.smartirCache.manufacturers.keySet().sort()
    }

    log.info "Fetching manufacturer list from SmartIR GitHub..."

    try {
        def params = [
            uri: SMARTIR_API_BASE + SMARTIR_CONTENTS_PATH,
            headers: [
                "Accept": "application/vnd.github.v3+json",
                "User-Agent": "Hubitat-HVAC-Wizard/1.0"
            ],
            timeout: 15
        ]

        def manufacturers = []
        httpGet(params) { resp ->
            if (resp.status == 200) {
                log.debug "Received ${resp.data.size()} files from GitHub"

                // Parse manufacturer from each JSON file
                resp.data.each { file ->
                    if (file.name.endsWith(".json") && file.download_url) {
                        // We'll need to fetch each file to get manufacturer name
                        // For now, just extract from filename pattern
                        // Format is typically: 1020.json, 1021.json, etc.
                        // We'll fetch and parse on demand per manufacturer
                    }
                }

                // Since we can't easily get manufacturers without fetching all files,
                // return the hardcoded list for now
                // TODO: Implement a mapping file or fetch popular manufacturers
                manufacturers = FALLBACK_MANUFACTURERS
            }
        }

        return manufacturers

    } catch (Exception e) {
        log.error "Failed to fetch manufacturer list: ${e.message}"
        return FALLBACK_MANUFACTURERS
    }
}

/**
 * Fetch all models for a specific manufacturer from SmartIR
 * Caches results for 24 hours
 */
def fetchModelsForManufacturer(String manufacturer) {
    // Check cache first
    if (isCacheValid() && state.smartirCache?.manufacturers?[manufacturer]) {
        log.debug "Using cached models for ${manufacturer}"
        return state.smartirCache.manufacturers[manufacturer]
    }

    log.info "Fetching models for ${manufacturer} from SmartIR..."

    def models = [:]

    try {
        // Get file list from GitHub
        def params = [
            uri: SMARTIR_API_BASE + SMARTIR_CONTENTS_PATH,
            headers: [
                "Accept": "application/vnd.github.v3+json",
                "User-Agent": "Hubitat-HVAC-Wizard/1.0"
            ],
            timeout: 15
        ]

        httpGet(params) { resp ->
            if (resp.status == 200) {
                // Filter and fetch JSON files
                def jsonFiles = resp.data.findAll { it.name.endsWith(".json") }

                log.debug "Found ${jsonFiles.size()} model files, fetching each..."

                // Fetch each model file (limit to prevent timeout)
                def fetchedCount = 0
                def maxToFetch = 50  // Limit to prevent timeout

                jsonFiles.each { file ->
                    if (fetchedCount >= maxToFetch) {
                        log.warn "Reached fetch limit (${maxToFetch}), stopping"
                        return
                    }

                    try {
                        def modelData = fetchModelFile(file.download_url)

                        if (modelData && modelData.manufacturer?.toLowerCase() == manufacturer.toLowerCase()) {
                            def modelId = file.name.replaceAll(/\.json$/, "")
                            models[modelId] = modelData
                            fetchedCount++
                            log.debug "Added model ${modelId} for ${manufacturer}"
                        }
                    } catch (Exception e) {
                        log.warn "Failed to fetch model file ${file.name}: ${e.message}"
                    }

                    // Add small delay to avoid rate limiting
                    pauseExecution(100)
                }

                log.info "Fetched ${models.size()} models for ${manufacturer}"
            }
        }

        // Cache the results
        if (!state.smartirCache) state.smartirCache = [:]
        if (!state.smartirCache.manufacturers) state.smartirCache.manufacturers = [:]
        state.smartirCache.manufacturers[manufacturer] = models
        state.smartirCache.lastFetched = now()

        return models

    } catch (Exception e) {
        log.error "Failed to fetch models for ${manufacturer}: ${e.message}"
        return [:]
    }
}

/**
 * Fetch individual SmartIR model JSON file
 */
def fetchModelFile(String downloadUrl) {
    try {
        def params = [
            uri: downloadUrl,
            headers: [
                "User-Agent": "Hubitat-HVAC-Wizard/1.0"
            ],
            timeout: 10,
            contentType: "application/json"
        ]

        def modelData = null
        httpGet(params) { resp ->
            if (resp.status == 200) {
                modelData = resp.data
            }
        }

        return modelData

    } catch (Exception e) {
        log.error "Failed to fetch model file from ${downloadUrl}: ${e.message}"
        return null
    }
}

/**
 * Match learned IR code against all cached models for the selected manufacturer
 */
def matchCodeToModel(String learnedCode, String manufacturer) {
    def models = state.smartirCache?.manufacturers?[manufacturer]

    if (!models || models.size() == 0) {
        log.warn "No models cached for manufacturer: ${manufacturer}"
        return null
    }

    log.info "Matching code against ${models.size()} models for ${manufacturer}"

    // Normalize learned code (remove all whitespace)
    String normalizedCode = learnedCode.replaceAll(/\s/, "")

    // Search through all models
    for (modelEntry in models) {
        def modelId = modelEntry.key
        def modelData = modelEntry.value

        log.debug "Checking model ${modelId}..."

        // Check OFF command first (common case)
        if (modelData.commands?.off) {
            def offCode = modelData.commands.off.replaceAll(/\s/, "")
            if (normalizedCode == offCode) {
                log.info "Match found: ${modelId} - OFF command"
                return [
                    smartIrId: modelId,
                    manufacturer: modelData.manufacturer,
                    model: modelData.supportedModels?.join(", ") ?: "Unknown",
                    modelData: modelData,
                    detectedState: [mode: "off", temp: null, fan: null]
                ]
            }
        }

        // Check all mode/fan/temp combinations
        def commands = modelData.commands
        if (!commands) continue

        for (mode in commands.keySet()) {
            if (mode == "off") continue  // Already checked

            def modeCommands = commands[mode]
            if (!(modeCommands instanceof Map)) continue

            for (fanSpeed in modeCommands.keySet()) {
                def fanCommands = modeCommands[fanSpeed]
                if (!(fanCommands instanceof Map)) continue

                for (temp in fanCommands.keySet()) {
                    def irCode = fanCommands[temp]
                    if (!irCode) continue

                    def normalizedIrCode = irCode.toString().replaceAll(/\s/, "")
                    if (normalizedCode == normalizedIrCode) {
                        log.info "Match found: ${modelId} - ${mode}/${fanSpeed}/${temp}"
                        return [
                            smartIrId: modelId,
                            manufacturer: modelData.manufacturer,
                            model: modelData.supportedModels?.join(", ") ?: "Unknown",
                            modelData: modelData,
                            detectedState: [
                                mode: mode,
                                temp: temp.toString().isInteger() ? temp.toInteger() : null,
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

/**
 * Check if SmartIR cache is still valid
 */
def isCacheValid() {
    if (!state.smartirCache?.lastFetched) {
        return false
    }

    def age = now() - state.smartirCache.lastFetched
    return age < CACHE_EXPIRY_MS
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
            manufacturer: detectedModel.manufacturer,
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

/*********
 * APP LIFECYCLE
 */

def installed() {
    log.info "HVAC Setup Wizard installed"
    initialize()
}

def updated() {
    log.info "HVAC Setup Wizard updated"
    unsubscribe()
    initialize()
}

def initialize() {
    // Subscribe to device events if device is selected
    if (irDevice) {
        subscribe(irDevice, "lastLearnedCode", codeLearnedHandler)
    }

    // Initialize wizard state if needed
    if (!state.wizardState) {
        state.wizardState = [:]
    }
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
            log.info "Triggering IR code learn..."

            if (!irDevice) {
                log.error "No device selected"
                return
            }

            try {
                // Call driver's learnIrCode method
                irDevice.learnIrCode("wizard")
                log.info "Learn command sent to device"

                // Update wizard state
                state.wizardState.learningInProgress = true

            } catch (Exception e) {
                log.error "Failed to trigger learn: ${e.message}"
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
    log.info "Code learned event received: ${evt.value?.take(50)}..."

    def learnedCode = evt.value

    if (!learnedCode) {
        log.warn "Empty code received"
        return
    }

    // Store in wizard state
    if (!state.wizardState) state.wizardState = [:]
    state.wizardState.learnedCode = learnedCode
    state.wizardState.learningInProgress = false

    // Try to match code to model
    if (hvacManufacturer) {
        log.info "Attempting to match code to ${hvacManufacturer} models..."

        try {
            def detectedModel = matchCodeToModel(learnedCode, hvacManufacturer)

            if (detectedModel) {
                log.info "Model detected: ${detectedModel.smartIrId}"
                state.wizardState.detectedModel = detectedModel
                state.wizardState.matchError = null
            } else {
                log.warn "No model match found"
                state.wizardState.detectedModel = null
                state.wizardState.matchError = "No matching model found in SmartIR database"
            }
        } catch (Exception e) {
            log.error "Error during code matching: ${e.message}"
            state.wizardState.matchError = "Error during matching: ${e.message}"
        }
    } else {
        log.warn "No manufacturer selected, cannot match code"
        state.wizardState.matchError = "No manufacturer selected"
    }
}
