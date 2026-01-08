/**
 * Hubitat Zigbee Driver for the Tuya Zigbee IR Remote Control Model ZS06 (also known as TS1201)
 *
 * This driver is based largely on the work already done to integrate this device with Zigbee2MQTT, aka zigbee-herdsman
 * https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/zosung.ts
 * https://github.com/Koenkk/zigbee-herdsman/blob/master/src/zcl/definition/cluster.ts#L5260-L5359
 *
 * Zigbee command payloads for the ZS06 seem to be largely hex encoded structs.
 * In this driver, this mapping is handled by the toPayload and toStruct functions which convert a Map of
 * struct data into a hex byte string according to a given struct layout definition.
 *
 * The learn and sendCode commands consist of a back-and-forth sequence of command messages between
 * the hub and the device. The names for these messages are not official and just guesses.
 * Here's an outline of the flow:
 *
 * learn sequence:
 *  1. hub sends 0xe004 0x00 (learn) with the JSON {"study":0} (as an ASCII hex byte string)
 *  2. device led illuminates, user sends IR code to the device using original remote
 *  3. device sends 0xed00 0x00 (start transmit) with a sequence value it generates + the code length
 *     - All subsequent messages generally include this same sequence value
 *  4. hub sends 0xed00 0x01 (start transmit ack)
 *  5. device sends 0xed00 0x0B (ACK) with 0x01 as the command being acked
 *  6. hub sends 0xed00 0x02 (code data request) with a position (initially 0)
 *  7. device sends 0xed00 0x03 (code data response) with a chunk of the code data and a crc checksum
 *  [repeat (5) and (6) until the received data length matches the length given in (3)]
 *  8. hub sends 0xed00 0x04 (done sending)
 *  9. device sends 0xed00 0x05 (done receiving)
 *  10. hub sets "lastLearnedCode" (base64 value),
 *      clears data associated with this sequence,
 *      and sends 0xe004 0x00 (learn) with the JSON {"study":1}
 *  11. device led turns off
 *
 * sendCode sequence:
 *  1. hub sends 0xed00 0x00 (start transmit) with a generated sequence value + the code length
 *     - All subsequent messages generally include this same sequence value
 *  2. device sends 0xed00 0x01 (start transmit ack)
 *     - We ignore this
 *  3. device sends 0xed00 0x02 (code data request) with a position (initially 0)
 *  4. hub sends 0xed00 0x03 (code data response) with a chunk of the code data and a crc checksum
 *  [repeat (3) and (4) until the device sends 0xed00 0x04 (done sendng)]
 *  5. device sends 0xed00 0x04 (done sending)
 *  6. hub sends 0xed00 0x05 (done receiving),
 *     clears data associated with this sequence
 *  7. device emits the IR code
 *
 * There are also various other "ACK" messages sent after each command.
 * In general, we do nothing in response to these (and the device doesn't appear to require we
 * send them in response to its messages).
 */

import groovy.transform.Field

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import java.util.concurrent.ConcurrentHashMap

// These BEGIN and END comments are so this section can be snipped out in unit tests.
// I'm not sure what's necessary to make this syntax work in standard Groovy
// BEGIN METADATA
metadata {
    definition(
        name: 'Maestro Tuya Zigbee IR Remote Control',
        namespace: 'hubitat.lastmyle.maestro',
        author: 'Lastmyle'
        ) {
        // Capability required for Maker API visibility
        capability 'Actuator'

        command 'learn', [
            [name: 'Code Name', type: 'STRING', description: 'Name for learned code (optional)']
        ]
        command 'sendCode', [
            [name: 'Code*', type: 'STRING', description: 'Name of learned code or raw Base64 bytes of code to send']
        ]

        // HVAC App Interface Methods (called by HVAC Setup Wizard app)
        command 'setHvacConfig', [
            [name: 'Config JSON*', type: 'JSON_OBJECT', description: 'DO NOT SET MANUALLY - Used by HVAC Setup Wizard to configure HVAC control. Stores ALL IR codes (~200+ commands) locally for instant operation without network access.']
        ]

        // HVAC Control Commands (for automations and manual control)
        command 'hvacTurnOff'
        command 'hvacSendCommand', [
            [name: 'Mode*', type: 'ENUM', constraints: ['cool', 'heat', 'dry', 'fan', 'auto'], description: 'Operation mode'],
            [name: 'Temperature*', type: 'ENUM', constraints: ['16', '17', '18', '19', '20', '21', '22', '23', '24', '25', '26', '27', '28', '29', '30'], description: 'Temperature in Celsius'],
            [name: 'Fan Speed*', type: 'ENUM', constraints: ['auto', 'quiet', 'low', 'medium', 'high'], description: 'Fan speed']
        ]
        command 'hvacSendCommandName', [
            [name: 'Command Name*', type: 'STRING', description: 'Command name (e.g., "24_cool_auto", "power_on")']
        ]
        command 'hvacRestoreState'

        // Readonly HVAC Configuration Attributes
        attribute 'lastLearnedCode', 'STRING'
        attribute 'hvacModel', 'STRING'
        attribute 'hvacConfigured', 'STRING'

        // Note, my case says ZS06, but this is what Device Get Info tells me the fingerprint is
        fingerprint profileId: '0104', inClusters: '0000,0004,0005,0003,ED00,E004,0006', outClusters: '0019,000A', manufacturer: '_TZ3290_7v1k4vufotpowp9z', model: 'TS1201', deviceJoinName: 'Tuya Zigbee IR Remote Control'
        }

    preferences {
        input name: 'logLevel', type: 'enum', title: 'Log Level', description: 'Override logging level. Default is INFO.<br>DEBUG level will reset to INFO after 30 minutes', options: ['DEBUG', 'INFO', 'WARN', 'ERROR'], required: true, defaultValue: 'INFO'
    }
}
// END METADATA

/*
 * Semi-persistent data
 * We don't need this permanently in state, but we do need it between message executions so just @Field doesn't work
 */
/* deviceId -> seq -> { buffer: List<byte> } */
@Field static final Map<String, Map<Integer, Map>> SEND_BUFFERS = new ConcurrentHashMap()
def sendBuffers() { return SEND_BUFFERS.computeIfAbsent(device.id, { k -> new HashMap<>() }); }
/* deviceId -> seq -> { expectedBufferLength: int, buffer: List<byte> } */
@Field static final Map<String, Map<Integer, Map>> RECEIVE_BUFFERS = new ConcurrentHashMap()
def receiveBuffers() { return RECEIVE_BUFFERS.computeIfAbsent(device.id, { k -> new HashMap<>() }); }
/* deviceId -> Stack<string|null> */
@Field static final Map<String, List<Integer>> PENDING_LEARN_CODE_NAMES = new ConcurrentHashMap()
def pendingLearnCodeNames() { return PENDING_LEARN_CODE_NAMES.computeIfAbsent(device.id, { k -> new LinkedList<>() }); }
/* deviceId -> Stack<seq> */
@Field static final Map<String, List<Integer>> PENDING_RECEIVE_SEQS = new ConcurrentHashMap()
def pendingReceiveSeqs() { return PENDING_RECEIVE_SEQS.computeIfAbsent(device.id, { k -> new LinkedList<>() }); }

/*********
 * ACTIONS
 */

def installed() {
    info 'installed()'
}

def updated() {
    info 'updated()'
    switch (logLevel) {
    case 'DEBUG':
            debug 'log level is DEBUG. Will reset to INFO after 30 minutes'
            runIn(1800, 'resetLogLevel')
            break
    case 'INFO': info 'log level is INFO'; break;
    case 'WARN': warn 'log level is WARN'; break;
    case 'ERROR': error 'log level is ERROR'; break;
    default: error "Unexpected logLevel: ${logLevel}"
    }
}

def configure() {
    info 'configure()'
}

def learn(final String optionalCodeName) {
    info "learn(${optionalCodeName})"
    pendingLearnCodeNames().push(optionalCodeName)
    sendLearn(true)
}

def sendCode(final String codeNameOrBase64CodeInput) {
    info "sendCode(${codeNameOrBase64CodeInput})"

    String learnedCode = null
    if (state.learnedCodes != null) {
        learnedCode = state.learnedCodes[codeNameOrBase64CodeInput]
    }

    final String base64Code
    if (learnedCode != null) {
        base64Code = learnedCode
    } else {
        // Remove all whitespace since we added newlines to the lastLearnedCode attribute + the hubitat HTML might add extra spaces
        base64Code = codeNameOrBase64CodeInput.replaceAll('\\s', '')
    }

    // JSON format copied from zigbee-herdsman-converters
    // Unclear if any of this can be tweaked to get different behavior
    final String jsonToSend = "{\"key_num\":1,\"delay\":300,\"key1\":{\"num\":1,\"freq\":38000,\"type\":1,\"key_code\":\"${base64Code}\"}}"
    debug "JSON to send: ${jsonToSend}"

    def seq = nextSeq()
    sendBuffers()[seq] = [
        buffer: jsonToSend.bytes as List
    ]
    sendStartTransmit(seq, jsonToSend.bytes.length)
}


/*********
 * HVAC APP INTERFACE METHODS
 */

/**
 * Set HVAC configuration from the setup wizard app
 * Called by: HVAC Setup Wizard app (ONE-TIME during setup)
 *
 * IMPORTANT: This stores ALL IR codes locally in driver state for instant runtime access.
 * After this one-time setup, the driver requires NO network access for HVAC control.
 *
 * @param configJson Map containing:
 *   - model: String (e.g., "CS/CU-xxxx Series")
 *   - commands: Array of ALL IR codes (including off) (local storage ~50KB)
 */
def setHvacConfig(final Map configJson) {
    info "setHvacConfig(${configJson?.model})"

    if (!configJson || !configJson.commands) {
        error 'Invalid config: missing required fields'
        return
    }

    // Store FULL configuration locally in driver state
    // This enables sub-second runtime commands with NO network dependency
    state.hvacConfig = [
        model: configJson.model,
        commands: configJson.commands ?: [],        // ← All 200+ commands stored locally (including off)
        currentState: [mode: 'off', temp: null, fan: null]
    ]

    // Update readonly attributes for display
    doSendEvent(name: 'hvacModel', value: configJson.model ?: 'Unknown')
    doSendEvent(name: 'hvacConfigured', value: 'Yes')

    info 'HVAC configuration saved successfully'
}


/**
 * Get current HVAC configuration
 * Called by: HVAC Setup Wizard app
 * @return Map of current configuration
 */
Map getHvacConfig() {
    return state.hvacConfig
}


/*********
 * HVAC CONTROL COMMANDS
 */

/**
 * Turn off the HVAC unit
 * Sends the OFF IR code stored during configuration
 */
def hvacTurnOff() {
    info 'hvacTurnOff()'

    if (!state.hvacConfig) {
        error 'HVAC not configured - run HVAC Setup Wizard first'
        return
    }

    // Commands are stored as array: [{name: "power_off", tuya_code: "..."}, ...]
    def commands = state.hvacConfig.commands
    if (!commands || !(commands instanceof List)) {
        error 'No commands configured or invalid format'
        return
    }

    // Find power_off command
    def offCmd = commands.find { it.name?.toLowerCase() == 'power_off' }
    if (!offCmd || !offCmd.tuya_code) {
        error 'No OFF command found in configuration'
        return
    }

    info 'Sending HVAC OFF command'
    sendCode(offCmd.tuya_code)

    // Update current state
    state.hvacConfig.currentState = [mode: 'off', temp: null, fan: null]
}

/**
 * Send HVAC command by name
 * @param commandName The command name (e.g., "24_cool_auto", "power_on", "power_off")
 */
def hvacSendCommandName(String commandName) {
    info "hvacSendCommandName(${commandName})"

    if (!state.hvacConfig) {
        error 'HVAC not configured - run HVAC Setup Wizard first'
        return
    }

    // Commands are stored as array: [{name: "24_cool_auto", tuya_code: "..."}, ...]
    def commands = state.hvacConfig.commands
    if (!commands || !(commands instanceof List)) {
        error 'No commands configured or invalid format'
        return
    }

    // Find the command by name
    def cmd = commands.find { it.name?.toLowerCase() == commandName.toLowerCase() }

    if (!cmd || !cmd.tuya_code) {
        error "Command '${commandName}' not found in configuration"
        return
    }

    // Send the command
    info "Sending HVAC command: ${cmd.name}"
    sendCode(cmd.tuya_code)

    // Parse command name to update state (e.g., "24_cool_auto" -> temp=24, mode=cool, fan=auto)
    def parts = commandName.split('_')
    if (parts.size() == 3 && parts[0].isNumber()) {
        // Regular command: "24_cool_auto"
        Integer temp = parts[0].toInteger()
        String mode = parts[1]
        String fan = parts[2]
        state.hvacConfig.currentState = [mode: mode, temp: temp, fan: fan]
        info "✓ Command sent: ${mode} ${temp}°C ${fan}"
    } else {
        // Special command: "power_on", "power_off", etc.
        if (commandName.toLowerCase() in ['power_off', 'off']) {
            state.hvacConfig.currentState = [mode: 'off', temp: null, fan: null]
        }
        info "✓ Command sent: ${commandName}"
    }
}

/**
 * Send a specific HVAC command
 * @param mode Operation mode (cool, heat, dry, fan, auto, power_on, power_off)
 * @param temp Temperature in Celsius (String when called from UI/rules, null for special commands)
 * @param fan Fan speed (auto, quiet, low, medium, high) (null for special commands)
 */
def hvacSendCommand(String mode, String temp, String fan) {
    info "hvacSendCommand(mode=${mode}, temp=${temp}, fan=${fan})"

    // Build command name from parameters
    String commandName
    if (mode in ['power_on', 'power_off', 'off']) {
        // Special commands that don't have temp/fan parameters
        commandName = mode
    } else {
        // Regular commands: "24_cool_auto"
        Integer tempInt = temp.toInteger()
        commandName = "${tempInt}_${mode}_${fan}"
    }

    // Delegate to hvacSendCommandName
    hvacSendCommandName(commandName)
}

/**
 * Restore the last known HVAC state
 * If unit was off, does nothing
 */
def hvacRestoreState() {
    info 'hvacRestoreState()'

    if (!state.hvacConfig) {
        error 'HVAC not configured - run HVAC Setup Wizard first'
        return
    }

    def currentState = state.hvacConfig.currentState
    if (!currentState || currentState.mode == 'off') {
        info 'Last state was OFF or unknown - nothing to restore'
        return
    }

    info "Restoring state: ${currentState.mode} ${currentState.temp}°C ${currentState.fan}"
    hvacSendCommand(currentState.mode, currentState.temp, currentState.fan)
}


/*********
 * MESSAGES
 */

def parse(final String description) {
    final def descMap = zigbee.parseDescriptionAsMap(description)

    switch (descMap.clusterInt) {
    case LEARN_CLUSTER:
            switch (Integer.parseInt(descMap.command, 16)) {
        case LEARN_CLUSTER_LEARN:
                    debug "received ${LEARN_CLUSTER_LEARN} (learn): ${descMap.data}"
                    break
        case LEARN_CLUSTER_ACK:
                    debug "received ${LEARN_CLUSTER_ACK} (learn ack): ${descMap.data}"
                    break
        default:
            debug "received unknown message: ${descMap.command} (cluster ${descMap.clusterInt})"
            }
            break
    case TRANSMIT_CLUSTER:
            switch (Integer.parseInt(descMap.command, 16)) {
        case TRANSMIT_CLUSTER_START_TRANSMIT:
                    debug "received ${TRANSMIT_CLUSTER_START_TRANSMIT} (start transmit): ${descMap.data}"
                    handleStartTransmit(parseStartTransmit(descMap.data))
                    break
        case TRANSMIT_CLUSTER_START_TRANSMIT_ACK:
                    debug "received ${TRANSMIT_CLUSTER_START_TRANSMIT_ACK} (start transmit ack): ${descMap.data}"
                    // I think this is just an ACK of the recieved initial msg 0
                    // There's nothing do to here
                    break
        case TRANSMIT_CLUSTER_CODE_DATA_REQUEST:
                    debug "received ${TRANSMIT_CLUSTER_CODE_DATA_REQUEST} (code data request): ${descMap.data}"
                    handleCodeDataRequest(parseCodeDataRequest(descMap.data))
                    break
        case TRANSMIT_CLUSTER_CODE_DATA_RESPONSE:
                    debug "received ${TRANSMIT_CLUSTER_CODE_DATA_RESPONSE} (code data response):: ${descMap.data}"
                    handleCodeDataResponse(parseCodeDataResponse(descMap.data))
                    break
        case TRANSMIT_CLUSTER_DONE_SENDING:
                    debug "received ${TRANSMIT_CLUSTER_DONE_SENDING} (done sending):: ${descMap.data}"
                    handleDoneSending(parseDoneSending(descMap.data))
                    break
        case TRANSMIT_CLUSTER_DONE_RECEIVING:
                    debug "received ${TRANSMIT_CLUSTER_DONE_RECEIVING} (done receiving): ${descMap.data}"
                    handleDoneReceiving(parseDoneReceiving(descMap.data))
                    break
        case TRANSMIT_CLUSTER_ACK:
                    debug "received ${TRANSMIT_CLUSTER_ACK} (ack): ${descMap.data}"
                    handleAck(parseAck(descMap.data))
                    break
        default:
            debug "received unknown message: ${descMap.command} (cluster ${descMap.clusterInt})"
            }
            break
    default:
        warn "received unknown message from unknown cluster: 0x${descMap.command} (cluster 0x${Integer.toHexString(descMap.clusterInt)}). Ignoring"
            debug "descMap = ${descMap}"
            break
    }
}

/*
 * Learn command cluster
 */
@Field static final int LEARN_CLUSTER = 0xe004

/**
 * 0x00 Learn
 */
@Field static final int LEARN_CLUSTER_LEARN = 0x00

String newLearnMessage(final boolean learn) {
    return command(
        LEARN_CLUSTER,
        LEARN_CLUSTER_LEARN,
        toPayload("{\"study\":${learn ? 0 : 1}}".bytes)
    )
}

def sendLearn(final boolean learn) {
    final def cmd = newLearnMessage(learn)
    info "=== Sending LEARN command to device (learn=${learn}) ==="
    info "Zigbee command: ${cmd}"
    debug "sending (learn(${learn})): ${cmd}"
    doSendHubCommand(cmd)
    info "✓ Learn command sent to Zigbee radio - device LED should ${learn ? 'start' : 'stop'} blinking"
}

/**
 * 0x0B ACK
 */
@Field static final int LEARN_CLUSTER_ACK = 0x0B

/*
 * Transmit command cluster
 */

@Field static final int TRANSMIT_CLUSTER = 0xed00

/**
 * 0x0B ACK
 */
@Field static final int TRANSMIT_CLUSTER_ACK = 0x0B
@Field static final def ACK_PAYLOAD_FORMAT = [
    [ name: 'cmd',    type: 'uint16' ],
]

Map parseAck(final List<String> payload) {
    return toStruct(ACK_PAYLOAD_FORMAT, payload)
}

String newAckMessage(final int cmd) {
    return command(
        TRANSMIT_CLUSTER,
        TRANSMIT_CLUSTER_ACK,
        toPayload(ACK_PAYLOAD_FORMAT, [ cmd: cmd ])
    )
}

def handleAck(final Map message) {
    switch (message.cmd) {
    case TRANSMIT_CLUSTER_START_TRANSMIT_ACK:
            // This is the only ack we care about
            // zigbee-herdsman-converters seems to handle this by just delaying this by a fixed time after
            // sending 0x00, but I think this is better
            sendCodeDataRequest(pendingReceiveSeqs().pop(), 0)
            break
    }
}

/**
 * 0x00 Start Transmit
 */
@Field static final int TRANSMIT_CLUSTER_START_TRANSMIT = 0x00
@Field static final def START_TRANSMIT_PAYLOAD_FORMAT = [
    [ name: 'seq',    type: 'uint16' ],
    [ name: 'length', type: 'uint32' ],
    [ name: 'unk1',   type: 'uint32' ],
    [ name: 'unk2',   type: 'uint16' ], // Cluster Id?
    [ name: 'unk3',   type: 'uint8' ],
    [ name: 'cmd',    type: 'uint8' ],
    [ name: 'unk4',   type: 'uint16' ],
]

def newStartTransmitMessage(final int seq, final int length) {
    return command(
        TRANSMIT_CLUSTER,
        TRANSMIT_CLUSTER_START_TRANSMIT,
        toPayload(
            START_TRANSMIT_PAYLOAD_FORMAT,
            [
                seq: seq,
                length: length,
                unk1: 0,
                unk2: LEARN_CLUSTER, // This seems to be what this is set to for some reason
                unk3: 0x01,
                cmd:  0x02,
                unk4: 0,
            ]
        )
    )
}

def sendStartTransmit(final int seq, final int length) {
    final def cmd = newStartTransmitMessage(seq, length)
    debug "sending (start transmit): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseStartTransmit(final List<String> payload) {
    return toStruct(START_TRANSMIT_PAYLOAD_FORMAT, payload)
}

def handleStartTransmit(final Map message) {
    pendingReceiveSeqs().push(message.seq)
    receiveBuffers()[message.seq] = [
        expectedBufferLength: message.length,
        buffer: []
    ]
    sendStartTransmitAck(message)
}

/**
 * 0x01 Start Transmit ACK
 * ??? I don't actually know what this is for, but it needs to happen before 0x02.
 * The body seems to just be the same as 0x00 with an extra zero byte at the beginning
 */
@Field static final int TRANSMIT_CLUSTER_START_TRANSMIT_ACK = 0x01
@Field static final def START_TRANSMIT_ACK_PAYLOAD_FORMAT = [
    [ name: 'zero',   type: 'uint8' ],
    [ name: 'seq',    type: 'uint16' ],
    [ name: 'length', type: 'uint32' ],
    [ name: 'unk1',   type: 'uint32' ],
    [ name: 'unk2',   type: 'uint16' ], // Cluster Id?
    [ name: 'unk3',   type: 'uint8' ],
    [ name: 'cmd',    type: 'uint8' ],
    [ name: 'unk4',   type: 'uint16' ],
]

String newStartTransmitAckMessage(final int seq, final int length) {
    return command(
        TRANSMIT_CLUSTER,
        TRANSMIT_CLUSTER_START_TRANSMIT_ACK,
        toPayload(
            START_TRANSMIT_ACK_PAYLOAD_FORMAT,
            [
                zero: 0,
                seq: seq,
                length: length,
                unk1: 0,
                unk2: LEARN_CLUSTER, // This seems to be what this is set to for some reason
                unk3: 0x01,
                cmd:  0x02,
                unk4: 0,
            ]
        )
    )
}

void sendStartTransmitAck(final Map message) {
    final def cmd = newStartTransmitAckMessage(message.seq, message.length)
    debug "sending (start transmit ack): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseStartTransmitAck(final List<String> payload) {
    return toStruct(START_TRANSMIT_ACK_PAYLOAD_FORMAT, payload)
}

/**
 * 0x02 Code Data Request
 */
@Field static final int TRANSMIT_CLUSTER_CODE_DATA_REQUEST = 0x02
@Field static final def CODE_DATA_REQUEST_PAYLOAD_FORMAT = [
    [ name: 'seq',      type: 'uint16' ],
    [ name: 'position', type: 'uint32' ],
    [ name: 'maxlen',   type: 'uint8' ],
]

String newCodeDataRequestMessage(final int seq, final int position) {
    return command(
        TRANSMIT_CLUSTER,
        TRANSMIT_CLUSTER_CODE_DATA_REQUEST,
        toPayload(
            CODE_DATA_REQUEST_PAYLOAD_FORMAT,
            [
                seq: seq,
                position: position,
                maxlen: 0x38, // Limits? Unknown, this default copied from zigbee-herdsman-converters
            ]
        )
    )
}

void sendCodeDataRequest(final int seq, final int position) {
    final def cmd = newCodeDataRequestMessage(seq, position)
    debug "sending (code data request): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseCodeDataRequest(final List<String> payload) {
    return toStruct(CODE_DATA_REQUEST_PAYLOAD_FORMAT, payload)
}

def handleCodeDataRequest(final Map message) {
    final int position = message.position
    final List<Byte> buffer = sendBuffers()[message.seq].buffer
    // Apparently 55 bytes at a time. TODO: experiment, should this be maxlen bytes?
    final byte[] part = buffer.subList(position, Math.min(position + 55, buffer.size())) as byte[]
    final int crc = checksum(part)

    sendCodeDataResponse(
        message.seq,
        position,
        part,
        crc
    )
}

/**
 * 0x03 Code Data Respoonse
 */
@Field static final int TRANSMIT_CLUSTER_CODE_DATA_RESPONSE = 0x03
@Field static final def CODE_DATA_RESPONSE_PAYLOAD_FORMAT = [
    [ name: 'zero',       type: 'uint8' ],
    [ name: 'seq',        type: 'uint16' ],
    [ name: 'position',   type: 'uint32' ],
    [ name: 'msgpart',    type: 'octetStr' ],
    [ name: 'msgpartcrc', type: 'uint8'],
]

String newCodeDataResponseMessage(final int seq, final int position, final byte[] data, final int crc) {
    return command(
        TRANSMIT_CLUSTER,
        TRANSMIT_CLUSTER_CODE_DATA_RESPONSE,
        toPayload(
            CODE_DATA_RESPONSE_PAYLOAD_FORMAT,
            [
                zero: 0,
                seq: seq,
                position: position,
                msgpart: data,
                msgpartcrc: crc
            ]
        )
    )
}

void sendCodeDataResponse(final int seq, final int position, final byte[] data, final int crc) {
    final def cmd = newCodeDataResponseMessage(seq, position, data, crc)
    debug "sending (code data response, position: ${position}) ${cmd}"
    doSendHubCommand(cmd)
}

Map parseCodeDataResponse(final List<String> payload) {
    return toStruct(CODE_DATA_RESPONSE_PAYLOAD_FORMAT, payload)
}

def handleCodeDataResponse(final Map message) {
    final Map seqData = receiveBuffers()[message.seq]
    if (seqData == null) {
        log.error "Unexpected seq: ${message.seq}"
        return
    }

    final List<Byte> buffer = seqData.buffer

    final int position = message.position
    if (position != buffer.size) {
        log.error "Position mismatch! expected: ${buffer.size} was: ${position}"
        return
    }

    final int actualCrc = checksum(message.msgpart)
    final int expectedCrc = message.msgpartcrc
    if (actualCrc != expectedCrc) {
        log.error "CRC mismatch! expected: ${expectedCrc} was: ${actualCrc}"
        return
    }

    buffer.addAll(message.msgpart)

    if (buffer.size < seqData.expectedBufferLength) {
        sendCodeDataRequest(message.seq, buffer.size)
    } else {
        sendDoneSending(message.seq)
    }
}

/**
 * 0x04 Done Sending
 */
@Field static final int TRANSMIT_CLUSTER_DONE_SENDING = 0x04
@Field static final def DONE_SENDING_PAYLOAD_FORMAT = [
    [ name: 'zero1', type: 'uint8' ],
    [ name: 'seq',   type: 'uint16' ],
    [ name: 'zero2', type: 'uint16' ],
]

String newDoneSendingMessage(final int seq) {
    return command(
        TRANSMIT_CLUSTER,
        TRANSMIT_CLUSTER_DONE_SENDING,
        toPayload(
            DONE_SENDING_PAYLOAD_FORMAT,
            [
                zero1: 0,
                seq: seq,
                zero2: 0
            ]
        )
    )
}

def sendDoneSending(final int seq) {
    final def cmd = newDoneSendingMessage(seq)
    debug "sending (done sending) ${cmd}"
    doSendHubCommand(cmd)
}

Map parseDoneSending(final List<String> payload) {
    return toStruct(DONE_SENDING_PAYLOAD_FORMAT, payload)
}

def handleDoneSending(final Map message) {
    info 'code fully sent'
    sendBuffers().remove(message.seq)
    sendDoneReceiving(message.seq)
}

/**
 * 0x05 Done Receiving
 */
@Field static final int TRANSMIT_CLUSTER_DONE_RECEIVING = 0x05
@Field static final def DONE_RECEIVING_PAYLOAD_FORMAT = [
    [ name: 'seq',        type: 'uint16' ],
    [ name: 'zero',       type: 'uint16' ],
]

String newDoneReceivingMessage(final int seq) {
    return command(
        TRANSMIT_CLUSTER,
        TRANSMIT_CLUSTER_DONE_RECEIVING,
        toPayload(
            DONE_RECEIVING_PAYLOAD_FORMAT,
            [
                seq: seq,
                zero: 0
            ]
        )
    )
}

def sendDoneReceiving(final int seq) {
    final def cmd = newDoneReceivingMessage(seq)
    debug "sending (done receiving): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseDoneReceiving(final List<String> payload) {
    return toStruct(DONE_RECEIVING_PAYLOAD_FORMAT, payload)
}

def handleDoneReceiving(final Map message) {
    final Map seqData = receiveBuffers().remove(message.seq)
    final String code = encodeBase64(seqData.buffer.toArray() as byte[])
    info "learned code: ${code}"

    doSendEvent(name: 'lastLearnedCode', value: code, descriptionText: "${device} lastLearnedCode is ${code}".toString())

    final String optionalCodeName = pendingLearnCodeNames().pop()
    if (optionalCodeName != null) {
        final Map learnedCodes = state.computeIfAbsent('learnedCodes', { k -> new HashMap() })
        learnedCodes[optionalCodeName] = code
    }

    sendLearn(false)
}

/*************
 * BASIC UTILS
 */

/**
 * Format a byte[] as a string of space-separated hex bytes,
 * used for the payload of most commands.
 */
String toPayload(final byte[] bytes) {
    return bytes.collect({ b -> String.format('%02X', b) }).join(' ')
}

/**
 * Parse a string of space separated hex bytes (the payload of most messages)
 * as a byte[]
 */
byte[] toBytes(final List<String> payload) {
    return payload.collect({ x -> Integer.parseInt(x, 16) as byte }) as byte[]
}

/**
 * Format a struct as a string of space-separated hex bytes.
 * @param format   a description of the struct's byte layout
 * @param payload  a struct to format
 */
String toPayload(final List<Map> format, final Map<String, Object> payload) {
    final def output = new ByteArrayOutputStream()
    for (def entry in format) {
        def value = payload[entry.name]
        switch (entry.type) {
        case 'uint8': writeIntegerLe(output, value, 1); break
        case 'uint16': writeIntegerLe(output, value, 2); break
        case 'uint24': writeIntegerLe(output, value, 3); break
        case 'uint32': writeIntegerLe(output, value, 4); break
        case 'octetStr':
                writeIntegerLe(output, value.length, 1)
                output.write(value, 0, value.length)
                break
        default: throw new RuntimeException("Unknown type: ${entry.type} (name: ${entry.name})")
        }
    }
    return toPayload(output.toByteArray())
}

/**
 * Parse a struct from a string of space-separated hex bytes
 * @param format   a description of the struct's byte layout
 * @param payload  a string of space-separate hex bytes
 */
Map toStruct(final List<Map> format, final List<String> payload) {
    final def input = new ByteArrayInputStream(toBytes(payload))
    final def result = [:]
    for (def entry in format) {
        switch (entry.type) {
        case 'uint8':  result[entry.name] = readIntegerLe(input, 1); break
        case 'uint16': result[entry.name] = readIntegerLe(input, 2); break
        case 'uint24': result[entry.name] = readIntegerLe(input, 3); break
        case 'uint32': result[entry.name] = readIntegerLe(input, 4); break
        case 'octetStr':
                final int length = readIntegerLe(input, 1)
                result[entry.name] = new byte[length]
                input.read(result[entry.name], 0, length)
                break
        default: throw new RuntimeException("Unknown type: ${entry.type} (name: ${entry.name})")
        }
    }
    return result
}

/**
 * Write an integer in twos complement little endian byte order to the given
 * output stream, taking up the number of bytes given
 */
def writeIntegerLe(final ByteArrayOutputStream out, int value, final int numBytes) {
    for (int p = 0; p < numBytes; p++) {
        final int digit1 = value % 16
        value = value.intdiv(16)
        final int digit2 = value % 16
        out.write(digit2 * 16 + digit1)
        value = value.intdiv(16)
    }
}

/**
 * Read `numBytes` bytes from the input stream as an integer in twos complement litle endian order
 */
def readIntegerLe(final ByteArrayInputStream input, final int numBytes) {
    int value = 0
    int pos = 1
    for (int i = 0; i < numBytes; i++) {
        value += input.read() * pos
        pos *= 0x100
    }
    return value
}

/**
 * @return the next value in a sequence, persisted in the driver state
 */
def nextSeq() {
    return state.nextSeq = ((state.nextSeq ?: 0) + 1) % 0x10000
}

/**
 * Checksum used to ensure the code parts are assembled correctly
 * @return the sum of all bytes in the byte array, mod 256
 *  (yes, this is a terrible CRC as the order could be completely wrong and still get the right value)
 */
def checksum(final byte[] byteArray) {
    // Java/Groovy bytes are signed, Byte.toUnsignedInt gets us the right integer value
    return byteArray.inject(0, { acc, val -> acc + Byte.toUnsignedInt(val) }) % 0x100
}

/**
 * Logging helpers
 * Why does Hubitat's LogWrapper even have these separate methods if this isn't built in??
 */
def error(msg) {
    log.error(msg)
}
def warn(msg) {
    if (logLevel == 'WARN' || logLevel == 'INFO' || logLevel == 'DEBUG') {
        log.warn(msg)
    }
}
def info(msg) {
    if (logLevel == 'INFO' || logLevel == 'DEBUG') {
        log.info(msg)
    }
}
def debug(msg) {
    if (logLevel == 'DEBUG') {
        log.debug(msg)
    }
}
def resetLogLevel() {
    info 'logLevel auto reset to INFO'
    device.updateSetting('logLevel', [value:'INFO', type:'enum'])
}

/*************
 * MOCKING STUBS
 */

/**
 * Determine if hub commands should be mocked (based on the presence of variables from the unit tests)
 */
def mockHubCommands() {
    try {
        return sentCommands != null
    } catch (ex) {
        return false
    }
}

/**
 * Mocking facade for sendHubCommand
 */
def doSendHubCommand(cmd) {
    if (mockHubCommands()) {
        sentCommands.add(cmd)
    } else {
        sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
    }
}

/**
 * Mocking facade for sendEvent
 */
def doSendEvent(final Map event) {
    if (mockHubCommands()) {
        sentEvents.add(event)
    } else {
        sendEvent(event)
    }
}

/**
 * Alternative to direct org.apache.commons.codec.binary.Base64 usage
 * so we don't have to have that dependency in tests
 */
def encodeBase64(final byte[] bytes) {
    try {
        return org.apache.commons.codec.binary.Base64.encodeBase64String(bytes)
    } catch (ex) {
        // Fallback for tests
        return encodeToString(bytes)
    }
}

/**
 * Alternative to zigbee.command so we don't have to stub that
 */
String command(final int clusterId, final int commandId, final String payload) {
    return "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x${Integer.toHexString(clusterId)} 0x${Integer.toHexString(commandId)} {${payload}}"
//return zigbee.command(clusterId, commandId, payload)[0]
}
