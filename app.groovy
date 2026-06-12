/**
 * HVAC Setup Wizard App (v3 API)
 *
 * Multi-page configuration wizard for Tuya Zigbee IR Remote Controls.
 *
 * Uses Maestro v3 (SmartIR-backed) endpoints:
 *   GET /api/v3/manufacturers
 *   GET /api/v3/manufacturers/{name}/devices
 *   GET /api/v3/devices/{id}
 *
 * v3 serves codes recorded from real hardware (no algorithmic generation,
 * no protocol identification from a learned code). The user picks brand
 * and model. The learn step is kept only to verify the IR blaster is wired
 * and pointing at the unit.
 */


import groovy.transform.Field

/*********
 * HUBITAT APP CODE
 */

definition(
    name: "Maestro HVAC Setup Wizard",
    namespace: "hubitat.lastmyle.maestro",
    author: "Lastmyle",
    description: "Configure HVAC IR remotes from the SmartIR code library",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "selectDevice")
    page(name: "selectManufacturer")
    page(name: "selectModel")
    page(name: "learnCode")
    page(name: "verifyModel")
    page(name: "complete")
}

/*********
 * CONSTANTS
 */

@Field static final String MAESTRO_API_URL = "https://maestro-tuya-ir.vercel.app"

/*********
 * PAGES
 */

def mainPage() {
    def isConfigured = irDevice && state.wizardState?.detectedModel

    if (isConfigured) {
        dynamicPage(name: "mainPage", title: "Maestro HVAC Configuration", uninstall: true, install: true) {
            section("Current Configuration") {
                paragraph "<b>Device:</b> ${irDevice.displayName}"
                def model = state.wizardState.detectedModel
                if (model) {
                    paragraph "<b>Manufacturer:</b> ${model.manufacturer}"
                    paragraph "<b>Model:</b> ${model.model}"
                    paragraph "<b>SmartIR device ID:</b> ${model.deviceId}"
                }
            }

            section("Test Commands") {
                paragraph "Send test commands to verify your HVAC configuration:"
                paragraph ""

                input "testPowerOn", "button", title: "Power ON", width: 6
                input "testPowerOff", "button", title: "Power OFF", width: 6
                paragraph ""

                paragraph "<b>16°C Cool:</b>"
                input "testCool16Quiet", "button", title: "16°C Cool Quiet", width: 4
                input "testCool16Auto", "button", title: "16°C Cool Auto", width: 4
                input "testCool16High", "button", title: "16°C Cool High", width: 4
                paragraph ""

                paragraph "<b>24°C Cool:</b>"
                input "testCool24Quiet", "button", title: "24°C Cool Quiet", width: 4
                input "testCool24Auto", "button", title: "24°C Cool Auto", width: 4
                input "testCool24High", "button", title: "24°C Cool High", width: 4
                paragraph ""

                paragraph "<b>30°C Heat:</b>"
                input "testHeat30Quiet", "button", title: "30°C Heat Quiet", width: 4
                input "testHeat30Auto", "button", title: "30°C Heat Auto", width: 4
                input "testHeat30High", "button", title: "30°C Heat High", width: 4
            }

            section("Reconfigure") {
                paragraph "Train a different remote or pick a different model:"
                input "reconfigureNow", "button", title: "Reconfigure Device"
            }

            section("Settings") {
                input "debugLogging", "bool",
                      title: "Enable Debug Logging",
                      description: "Show detailed debug information in logs and UI",
                      defaultValue: false,
                      required: false
            }
        }
    } else {
        dynamicPage(name: "mainPage", title: "Maestro Tuya Zigbee HVAC Setup Wizard", uninstall: true, install: false, nextPage: "selectDevice") {
            section("Welcome") {
                paragraph "This wizard configures your HVAC IR remote control by picking the brand and model from the SmartIR code library."
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
                          "2. Pick your HVAC manufacturer\n" +
                          "3. Pick the model\n" +
                          "4. Download the recorded IR command set\n" +
                          "5. Learn one code from your remote to confirm the blaster is wired up\n" +
                          "6. Save the configuration to the device"
            }

            section("Settings") {
                input "debugLogging", "bool",
                      title: "Enable Debug Logging",
                      description: "Show detailed debug information in logs and UI",
                      defaultValue: true,
                      required: false
            }
        }
    }
}

def selectDevice() {
    dynamicPage(name: "selectDevice", title: "Select IR Blaster", install: false, nextPage: "selectManufacturer") {
        section("Device Selection") {
            input name: "irDevice",
                  type: "device.MaestroTuyaZigbeeIRRemoteControl",
                  title: "Select Tuya IR Remote Device",
                  required: true,
                  multiple: false,
                  submitOnChange: true
        }

        if (irDevice) {
            log.info "Device selected: ${irDevice.displayName}"
            unsubscribe()
            subscribe(irDevice, "lastLearnedCode", "codeLearnedHandler")
            log.info "Subscribed to lastLearnedCode event from ${irDevice.displayName}"

            section("Device Selected") {
                paragraph "Device: ${irDevice.displayName}"
                paragraph "✅ Ready to continue"

                if (!deviceHasHvacSupport(irDevice)) {
                    paragraph "<hr>"
                    paragraph "⚠️ <b style='color:orange'>Warning:</b> This device may not support HVAC configuration."
                    paragraph "Make sure you're using the <b>Lastmyle Tuya Zigbee IR Remote Control</b> driver."
                }
            }
        }
    }
}

def selectManufacturer() {
    if (!state.wizardState?.v3?.manufacturers) {
        fetchManufacturers()
    }

    def hasSelection = settings.selectedManufacturer && state.wizardState?.v3?.manufacturers?.contains(settings.selectedManufacturer)

    dynamicPage(name: "selectManufacturer", title: "Select Manufacturer", install: false, nextPage: hasSelection ? "selectModel" : null) {
        section("Select Your HVAC Manufacturer") {
            paragraph "Choose your HVAC brand. The list comes from the SmartIR code library."
            paragraph ""

            def manufacturers = state.wizardState?.v3?.manufacturers ?: ["Loading..."]

            input "selectedManufacturer", "enum",
                  options: manufacturers,
                  title: "HVAC Manufacturer",
                  required: false,
                  submitOnChange: true

            input "refreshManufacturers", "button", title: "Refresh List"

            if (hasSelection) {
                def count = state.wizardState?.v3?.manufacturerCounts?.get(settings.selectedManufacturer)
                paragraph "<hr>"
                paragraph "<b>Selected:</b> ${settings.selectedManufacturer}" + (count ? " (${count} models)" : "")
                paragraph "<b>Click 'Next' below to pick a model...</b>"
            }
        }
    }
}

def selectModel() {
    // Clear cached devices when manufacturer changes
    def cachedFor = state.wizardState?.v3?.devicesFor
    if (cachedFor != settings.selectedManufacturer) {
        state.wizardState?.v3?.devices = null
        state.wizardState?.v3?.devicesFor = settings.selectedManufacturer
    }

    if (!state.wizardState?.v3?.devices) {
        fetchDevicesForManufacturer(settings.selectedManufacturer)
    }

    def modelOptions = state.wizardState?.v3?.modelOptions ?: [:]
    def hasSelection = settings.selectedDeviceId && state.wizardState?.v3?.detectedModel

    dynamicPage(name: "selectModel", title: "Select Model", install: false, nextPage: hasSelection ? "learnCode" : null) {
        section("Select Your HVAC Model") {
            paragraph "<b>Manufacturer:</b> ${settings.selectedManufacturer}"
            paragraph ""

            if (!modelOptions) {
                paragraph "Loading models..."
            } else {
                input "selectedDeviceId", "enum",
                      options: modelOptions,
                      title: "Model",
                      required: false,
                      submitOnChange: true
            }

            if (settings.selectedDeviceId && !state.wizardState?.v3?.detectedModel) {
                paragraph ""
                input "loadCommandsBtn", "button", title: "Load Commands for Selected Model"
            }

            if (state.wizardState?.v3?.loadStatus) {
                paragraph "<hr>"
                paragraph "<b>Status:</b> ${state.wizardState.v3.loadStatus}"
            }

            if (hasSelection) {
                def m = state.wizardState.v3.detectedModel
                paragraph "<hr>"
                paragraph "✅ <b style='color: green;'>Commands loaded</b>"
                paragraph "<b>Model:</b> ${m.model}"
                paragraph "<b>Operation modes:</b> ${m.modelData.operationModes?.join(', ')}"
                paragraph "<b>Fan modes:</b> ${m.modelData.fanModes?.join(', ')}"
                paragraph "<b>Temperature range:</b> ${m.modelData.minTemperature}°C - ${m.modelData.maxTemperature}°C"
                paragraph "<b>Command count:</b> ${m.modelData.commands?.size()}"
                paragraph ""
                paragraph "<b>Click 'Next' below to learn one code to verify the IR blaster...</b>"
            }
        }

        section("Change Manufacturer") {
            href "selectManufacturer", title: "Back to Manufacturer", description: "Pick a different brand"
        }
    }
}

def learnCode() {
    def autoRedirect = state.wizardState?.readyForNextPage == true

    dynamicPage(name: "learnCode", title: "Verify IR Blaster", install: false, nextPage: autoRedirect ? "verifyModel" : null, refreshInterval: state.wizardState?.learningInProgress ? 2 : 0) {
        section("Instructions") {
            paragraph "This step learns one IR code from your remote so you can confirm the blaster is wired up and pointing at the unit. The learned code is not used to identify your protocol — that's already set by the model you picked."
            paragraph ""
            paragraph "<b>Step 1:</b> Click 'Learn IR Code' below"
            paragraph "<b>Step 2:</b> Wait for the IR blaster LED to light up"
            paragraph "<b>Step 3:</b> Press any button on your physical HVAC remote"
            paragraph ""
            paragraph "<i>If you'd rather skip this step, click 'Next' to go straight to the verify page.</i>"
        }

        section("Action") {
            input "triggerLearn", "button", title: "Learn IR Code"
            input "skipLearn", "button", title: "Skip and Continue"

            if (state.wizardState?.learningStatus) {
                paragraph "<hr>"
                paragraph "<b>Status:</b> ${state.wizardState.learningStatus}"
            }

            if (state.wizardState?.learningInProgress == true) {
                paragraph "⏳ <b style='color: orange;'>Learning in progress...</b>"
                paragraph "Point your HVAC remote at the IR blaster and press a button now!"
                paragraph "<i>Page will auto-refresh every 2 seconds...</i>"
            }

            if (state.wizardState?.learnedCode) {
                paragraph "✅ <b style='color: green;'>Code received from blaster — wiring confirmed</b>"
                paragraph "Code length: ${state.wizardState.learnedCode.length()} characters"
                if (settings.debugLogging) {
                    paragraph "Code preview: <code>${state.wizardState.learnedCode.take(120)}...</code>"
                }
                paragraph "<hr>"
                paragraph "<b>Click 'Next' below to verify and complete setup...</b>"
            }
        }

        section("Troubleshooting") {
            paragraph "• Make sure IR blaster LED lights up when learning"
            paragraph "• Hold remote 6-12 inches from IR blaster"
            paragraph "• Press and release remote button quickly"
        }
    }
}

def verifyModel() {
    def detectedModel = state.wizardState?.v3?.detectedModel

    dynamicPage(name: "verifyModel", title: "Verify Selection", install: false) {
        if (detectedModel) {
            section("Selected Model") {
                paragraph "<b>Manufacturer:</b> ${detectedModel.manufacturer}"
                paragraph "<b>Model:</b> ${detectedModel.model}"
                paragraph "<b>SmartIR device ID:</b> ${detectedModel.deviceId}"
                if (detectedModel.modelData?.supportedModels) {
                    paragraph "<b>Supported variants:</b> ${detectedModel.modelData.supportedModels.join(', ')}"
                }
            }

            section("Supported Features") {
                def modelData = detectedModel.modelData
                if (modelData) {
                    paragraph "<b>Operation modes:</b> ${modelData.operationModes?.join(', ')}"
                    paragraph "<b>Fan modes:</b> ${modelData.fanModes?.join(', ')}"
                    paragraph "<b>Temperature range:</b> ${modelData.minTemperature}°C - ${modelData.maxTemperature}°C"
                    paragraph "<b>Command count:</b> ${modelData.commands?.size()}"
                }
            }

            if (state.wizardState?.learnedCode) {
                section("IR Blaster Check") {
                    paragraph "✅ Learned a ${state.wizardState.learnedCode.length()}-character code from the blaster — wiring confirmed."
                }
            }

            section("Confirm") {
                paragraph "Save this configuration to your device?"
                input "confirmModel", "bool",
                      title: "Yes, this is correct",
                      defaultValue: false,
                      submitOnChange: true

                if (confirmModel == false) {
                    href "selectModel", title: "Pick a Different Model", description: "Go back to model selection"
                }

                if (confirmModel == true) {
                    href "complete", title: "Complete Setup", description: "Save configuration to device"
                }
            }
        } else {
            section("No Model Selected ⚠️") {
                paragraph "<b>No model selected yet.</b>"
                href "selectManufacturer", title: "Pick Manufacturer", description: "Start the picker"
            }
        }
    }
}

def complete() {
    def success = saveConfigToDevice()

    dynamicPage(name: "complete", title: "Setup Complete", install: true, uninstall: true) {
        if (success) {
            section("Success! ✅") {
                paragraph "<b>HVAC configuration saved successfully!</b>"
                paragraph ""
                paragraph "<b>Device:</b> ${irDevice.displayName}"
                def m = state.wizardState?.v3?.detectedModel
                paragraph "<b>Manufacturer:</b> ${m?.manufacturer}"
                paragraph "<b>Model:</b> ${m?.model}"
            }

            section("Test Commands") {
                paragraph "Test your HVAC configuration:"
                paragraph ""

                input "testPowerOn", "button", title: "Power ON", width: 6
                input "testPowerOff", "button", title: "Power OFF", width: 6
                paragraph ""

                paragraph "<b>16°C Cool:</b>"
                input "testCool16Quiet", "button", title: "16°C Cool Quiet", width: 4
                input "testCool16Auto", "button", title: "16°C Cool Auto", width: 4
                input "testCool16High", "button", title: "16°C Cool High", width: 4
                paragraph ""

                paragraph "<b>24°C Cool:</b>"
                input "testCool24Quiet", "button", title: "24°C Cool Quiet", width: 4
                input "testCool24Auto", "button", title: "24°C Cool Auto", width: 4
                input "testCool24High", "button", title: "24°C Cool High", width: 4
                paragraph ""

                paragraph "<b>30°C Heat:</b>"
                input "testHeat30Quiet", "button", title: "30°C Heat Quiet", width: 4
                input "testHeat30Auto", "button", title: "30°C Heat Auto", width: 4
                input "testHeat30High", "button", title: "30°C Heat High", width: 4
            }
        } else {
            section("Error ⚠️") {
                paragraph "Failed to save configuration. Check logs for details."
                href "mainPage", title: "Start Over", description: "Restart the wizard"
            }
        }
    }
}

/*********
 * API INTEGRATION (v3)
 */

/**
 * GET /api/v3/manufacturers
 * Populates state.wizardState.v3.manufacturers and manufacturerCounts.
 */
def fetchManufacturers() {
    log.info "Fetching manufacturers from Maestro API v3..."

    try {
        def params = [
            uri: MAESTRO_API_URL + "/api/v3/manufacturers",
            headers: [
                "User-Agent": "Hubitat-HVAC-Wizard/3.0"
            ],
            timeout: 30
        ]

        httpGet(params) { resp ->
            if (resp.status == 200) {
                def list = resp.data?.manufacturers ?: []
                log.info "Retrieved ${list.size()} manufacturers"

                if (!state.wizardState) state.wizardState = [:]
                if (!state.wizardState.v3) state.wizardState.v3 = [:]
                state.wizardState.v3.manufacturers = list.collect { it.name }
                state.wizardState.v3.manufacturerCounts = list.collectEntries { [(it.name): it.device_count] }
                return state.wizardState.v3.manufacturers
            } else {
                log.error "API returned status ${resp.status}"
                return []
            }
        }
    } catch (Exception e) {
        log.error "Failed to fetch manufacturers: ${e.message}"
        if (!state.wizardState) state.wizardState = [:]
        if (!state.wizardState.v3) state.wizardState.v3 = [:]
        state.wizardState.v3.manufacturers = ["Error loading manufacturers"]
        state.wizardState.v3.manufacturerCounts = [:]
        return []
    }
}

/**
 * GET /api/v3/manufacturers/{name}/devices
 * Populates state.wizardState.v3.devices and modelOptions (id -> label).
 */
def fetchDevicesForManufacturer(String manufacturer) {
    log.info "Fetching devices for ${manufacturer}..."

    try {
        def encoded = java.net.URLEncoder.encode(manufacturer, "UTF-8")
        def params = [
            uri: MAESTRO_API_URL + "/api/v3/manufacturers/" + encoded + "/devices",
            headers: [
                "User-Agent": "Hubitat-HVAC-Wizard/3.0"
            ],
            timeout: 30
        ]

        httpGet(params) { resp ->
            if (resp.status == 200) {
                def devices = resp.data?.devices ?: []
                log.info "Retrieved ${devices.size()} devices for ${manufacturer}"

                if (!state.wizardState) state.wizardState = [:]
                if (!state.wizardState.v3) state.wizardState.v3 = [:]

                // Hubitat enum options: Map<value, displayLabel>. Value must be a string.
                def options = [:]
                devices.each { d ->
                    def models = d.supported_models ?: []
                    def label = models ? models.join(' / ') : "Device ${d.id}"
                    options[d.id.toString()] = "${label} (id ${d.id})"
                }

                state.wizardState.v3.devices = devices
                state.wizardState.v3.modelOptions = options
                return devices
            } else {
                log.error "API returned status ${resp.status}"
                return []
            }
        }
    } catch (Exception e) {
        log.error "Failed to fetch devices: ${e.message}"
        if (!state.wizardState) state.wizardState = [:]
        if (!state.wizardState.v3) state.wizardState.v3 = [:]
        state.wizardState.v3.devices = []
        state.wizardState.v3.modelOptions = [:]
        return []
    }
}

/**
 * GET /api/v3/devices/{id}
 * Fetches the full command set for a device and transforms it into the
 * shape the driver expects.
 */
def fetchDeviceCommands(Integer deviceId) {
    log.info "Fetching commands for device id ${deviceId}..."

    if (!deviceId) {
        log.error "No device id supplied"
        return null
    }

    try {
        def params = [
            uri: MAESTRO_API_URL + "/api/v3/devices/" + deviceId,
            headers: [
                "User-Agent": "Hubitat-HVAC-Wizard/3.0"
            ],
            timeout: 30
        ]

        def result = null
        httpGet(params) { resp ->
            if (resp.status == 200) {
                result = resp.data
                log.info "Retrieved ${result?.commands?.size()} commands for device ${deviceId}"
            } else {
                log.error "API returned status ${resp.status}"
            }
        }

        if (!result) {
            return null
        }

        def models = result.supported_models ?: []
        def primaryModel = models ? models[0] : "device-${deviceId}"

        return [
            deviceId: deviceId,
            manufacturer: result.manufacturer,
            model: primaryModel,
            modelData: [
                supportedModels: models,
                commands: transformV3Commands(result.commands ?: []),
                minTemperature: result.min_temperature ?: 16,
                maxTemperature: result.max_temperature ?: 30,
                operationModes: result.operation_modes ?: [],
                fanModes: result.fan_modes ?: [],
                swingModes: result.swing_modes ?: []
            ]
        ]

    } catch (Exception e) {
        log.error "Failed to fetch device commands: ${e.message}"
        return null
    }
}

/**
 * Translate v3 CommandInfo entries to the {name, tuya_code} shape the
 * driver consumes.
 *
 * v3 name format: MODE[_FAN][_TEMP][_SWING_X] (e.g. "OFF", "COOL_AUTO_22").
 * Driver expects: "power_off" / "{temp}_{mode}_{fan}" lowercase.
 *
 * Where a device has multiple swing variants for the same mode/fan/temp,
 * the first one wins.
 */
List<Map> transformV3Commands(List rawCommands) {
    def out = []
    def seen = new HashSet<String>()

    rawCommands.each { c ->
        String mode = (c.mode ?: "").toString().toLowerCase()
        String fan = c.fan ? c.fan.toString().toLowerCase() : null
        Integer temp = c.temperature != null ? (c.temperature as Number).intValue() : null
        String tuya = c.tuya_code

        if (!tuya) return

        String driverName
        if (mode == "off") {
            driverName = "power_off"
        } else if (temp != null && fan) {
            driverName = "${temp}_${mode}_${fan}"
        } else if (fan) {
            driverName = "${mode}_${fan}"
        } else {
            driverName = mode
        }

        if (seen.add(driverName)) {
            out << [name: driverName, tuya_code: tuya]
        }
    }

    return out
}

/**
 * Save HVAC configuration to the driver
 */
def saveConfigToDevice() {
    def detected = state.wizardState?.v3?.detectedModel
    if (!detected) {
        log.error "No selected model to save"
        return false
    }

    def modelData = detected.modelData
    if (!modelData) {
        log.error "No model data available"
        return false
    }

    try {
        def config = [
            model: detected.model,
            brand: detected.manufacturer,
            commands: modelData.commands ?: [],
            minTemperature: modelData.minTemperature ?: 16,
            maxTemperature: modelData.maxTemperature ?: 30,
            operationModes: modelData.operationModes ?: [],
            fanModes: modelData.fanModes ?: []
        ]

        irDevice.setHvacConfig(config)
        log.info "HVAC configuration saved to ${irDevice.displayName}"

        // Mirror to the legacy detectedModel slot so mainPage status renders consistently
        state.wizardState.detectedModel = detected

        def newLabel = "Maestro HVAC: ${irDevice.displayName}"
        app.updateLabel(newLabel)
        log.info "App renamed to: ${newLabel}"

        return true

    } catch (Exception e) {
        log.error "Failed to save config to device: ${e.message}"
        return false
    }
}

def deviceHasHvacSupport(device) {
    try {
        device.hasCommand("setHvacConfig")
        return true
    } catch (Exception e) {
        return false
    }
}

def getDeviceStatus(device) {
    if (!device) {
        return [online: false, status: "No device selected", lastActivity: null]
    }

    try {
        def lastActivity = device.getLastActivity()
        def now = new Date().time
        def timeSinceActivity = lastActivity ? (now - lastActivity.time) : null

        def hasRecentActivity = timeSinceActivity != null && timeSinceActivity < (24 * 60 * 60 * 1000)
        def hasState = device.currentStates?.size() > 0

        if (hasRecentActivity) {
            def minutesAgo = (timeSinceActivity / (60 * 1000)).intValue()
            def hoursAgo = (minutesAgo / 60).intValue()
            def activityText = hoursAgo > 0 ? "${hoursAgo} hour(s) ago" : "${minutesAgo} minute(s) ago"
            return [online: true, status: "Online - Last activity: ${activityText}", lastActivity: lastActivity, timeSinceActivity: timeSinceActivity]
        } else if (hasState) {
            return [online: true, status: "Online - Has device state", lastActivity: lastActivity, timeSinceActivity: timeSinceActivity]
        } else {
            return [online: false, status: lastActivity ? "Offline - No recent activity" : "Offline - Never seen", lastActivity: lastActivity, timeSinceActivity: timeSinceActivity]
        }
    } catch (Exception e) {
        log.error "Error checking device status: ${e.message}"
        return [online: false, status: "Error checking status: ${e.message}", lastActivity: null]
    }
}

/*********
 * APP LIFECYCLE
 */

def installed() {
    log.info "HVAC Setup Wizard installed"
}

def updated() {
    log.info "HVAC Setup Wizard updated"
    unsubscribe()
    initialize()

    if (irDevice && !app.label?.contains(irDevice.displayName)) {
        def newLabel = "Maestro HVAC: ${irDevice.displayName}"
        app.updateLabel(newLabel)
        log.info "App renamed to: ${newLabel}"
    }
}

def initialize() {
    log.info "=== Initialize called ==="

    if (irDevice) {
        log.info "Re-subscribing to device ${irDevice.displayName}"
        unsubscribe()
        subscribe(irDevice, "lastLearnedCode", "codeLearnedHandler")
        log.info "Event subscription active"
    } else {
        log.debug "No device selected yet, will subscribe when device is chosen"
    }

    if (!state.wizardState) {
        state.wizardState = [:]
    }
    if (!state.wizardState.v3) {
        state.wizardState.v3 = [:]
    }

    log.info "=== Initialize complete ==="
}

def uninstalled() {
    log.info "HVAC Setup Wizard uninstalled"
}

/*********
 * EVENT HANDLERS
 */

def appButtonHandler(btn) {
    log.info "Button pressed: ${btn}"

    switch (btn) {
        case "refreshManufacturers":
            log.info "=== Refreshing Manufacturer List ==="
            if (state.wizardState?.v3) {
                state.wizardState.v3.manufacturers = null
                state.wizardState.v3.manufacturerCounts = null
            }
            fetchManufacturers()
            break

        case "loadCommandsBtn":
            log.info "=== Loading Commands for Selected Model ==="
            if (!settings.selectedDeviceId) {
                log.error "No model selected"
                return
            }
            try {
                if (!state.wizardState) state.wizardState = [:]
                if (!state.wizardState.v3) state.wizardState.v3 = [:]

                Integer deviceId = settings.selectedDeviceId.toInteger()
                state.wizardState.v3.loadStatus = "Loading commands for device ${deviceId}..."

                def detected = fetchDeviceCommands(deviceId)
                if (detected) {
                    log.info "Commands loaded for model ${detected.model}"
                    state.wizardState.v3.detectedModel = detected
                    state.wizardState.v3.loadStatus = "Loaded ${detected.modelData.commands?.size()} commands"
                } else {
                    log.error "Failed to load commands"
                    state.wizardState.v3.detectedModel = null
                    state.wizardState.v3.loadStatus = "Failed to load commands for device ${deviceId}"
                }
            } catch (Exception e) {
                log.error "Error loading commands: ${e.message}"
                state.wizardState.v3.loadStatus = "Error: ${e.message}"
            }
            break

        case "triggerLearn":
            log.info "=== Starting IR Code Learning (Verification) ==="
            if (!irDevice) {
                log.error "No device selected"
                return
            }

            try {
                if (!state.wizardState) state.wizardState = [:]
                state.wizardState.learnedCode = null
                state.wizardState.learningStatus = "Waiting for IR signal..."

                if (!irDevice.hasCommand("learn")) {
                    log.error "Device does not have 'learn' command!"
                    state.wizardState.learningStatus = "Error: Device missing learn command"
                    return
                }

                log.info "Calling irDevice.learn('wizard')"
                irDevice.learn("wizard")
                state.wizardState.learningInProgress = true
                log.info "Learn command sent - LED should be blinking"

            } catch (Exception e) {
                log.error "Failed to trigger learn: ${e.message}"
                state.wizardState.learningStatus = "Error: ${e.message}"
            }
            break

        case "skipLearn":
            log.info "=== Skipping Learn Step ==="
            if (!state.wizardState) state.wizardState = [:]
            state.wizardState.learningStatus = "Skipped"
            state.wizardState.learningInProgress = false
            state.wizardState.readyForNextPage = true
            break

        case "reconfigureNow":
            log.info "=== Starting Reconfiguration ==="
            state.wizardState = [v3: [:]]
            log.info "Wizard state cleared - ready to reconfigure"
            break

        // Test command buttons
        case "testPowerOn":
            sendTestCommand("power_on")
            break
        case "testPowerOff":
            sendTestCommand("power_off")
            break
        case "testCool16Quiet":
            sendTestCommand("16_cool_quiet")
            break
        case "testCool16Auto":
            sendTestCommand("16_cool_auto")
            break
        case "testCool16High":
            sendTestCommand("16_cool_high")
            break
        case "testCool24Quiet":
            sendTestCommand("24_cool_quiet")
            break
        case "testCool24Auto":
            sendTestCommand("24_cool_auto")
            break
        case "testCool24High":
            sendTestCommand("24_cool_high")
            break
        case "testHeat30Quiet":
            sendTestCommand("30_heat_quiet")
            break
        case "testHeat30Auto":
            sendTestCommand("30_heat_auto")
            break
        case "testHeat30High":
            sendTestCommand("30_heat_high")
            break

        default:
            log.warn "Unknown button: ${btn}"
    }
}

def sendTestCommand(String commandName) {
    log.info "Sending test command: ${commandName}"
    if (irDevice?.hasCommand("hvacSendCommandName")) {
        irDevice.hvacSendCommandName(commandName)
        log.info "${commandName} command sent"
    } else {
        log.error "Device does not have hvacSendCommandName command"
    }
}

/**
 * Handle learned code event from device (verification only — v3 picks the
 * protocol from the user's model choice, not from this code).
 */
def codeLearnedHandler(evt) {
    log.info "=== IR Code Learned Event Received ==="
    log.info "Code length: ${evt.value?.length()} characters"

    def learnedCode = evt.value

    if (!learnedCode) {
        log.error "Empty code received - learning failed"
        if (!state.wizardState) state.wizardState = [:]
        state.wizardState.learningStatus = "Failed: Empty code received"
        state.wizardState.learningInProgress = false
        return
    }

    if (!state.wizardState) state.wizardState = [:]
    state.wizardState.learnedCode = learnedCode
    state.wizardState.learningInProgress = false
    state.wizardState.learningStatus = "Code received - IR blaster wiring confirmed"
    state.wizardState.readyForNextPage = true

    log.info "Verification code stored (${learnedCode.length()} chars)"
}
