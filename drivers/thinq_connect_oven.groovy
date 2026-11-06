/**
 *  ThinQ Connect Oven
 *
 *  Copyright 2025
 *
 *  Uses official LG ThinQ Connect API
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]
// Bump with any change that needs initialize() to run again (new schedules, MQTT handling):
// a code push alone doesn't run updated(), so checkVersion() does it.
@Field static final String CODE_VERSION = "1.2.5"
@Field List<String> OVEN_LOCATIONS = ["OVEN", "UPPER", "LOWER"]

metadata {
    definition(name: "ThinQ Connect Oven", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_oven.groovy") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"

        attribute "currentState", "string"
        attribute "ovenOperationMode", "string"
        attribute "cookMode", "string"
        attribute "locationName", "string"
        attribute "remoteControlEnabled", "string"
        attribute "temperatureUnit", "string"
        attribute "targetTemperatureC", "number"
        attribute "targetTemperatureF", "number"
        
        // Timer attributes
        attribute "remainHour", "number"
        attribute "remainMinute", "number"
        attribute "remainSecond", "number"
        attribute "targetHour", "number"
        attribute "targetMinute", "number"
        attribute "targetSecond", "number"
        attribute "timerHour", "number"
        attribute "timerMinute", "number"
        attribute "timerSecond", "number"
        
        // Commands
        command "start"
        command "stop"
        command "setOvenOperationMode", ["string"]
        command "setCookMode", ["string"]
        command "setTargetTemperatureC", ["number"]
        command "setTargetTemperatureF", ["number"]
        command "setTargetTime", ["number", "number"]
        command "setTimer", ["number", "number"]
        command "getDeviceProfile"
    }

    preferences {
        section {
            input name: "ovenLocation", title: "Oven Location", type: "enum", options: OVEN_LOCATIONS, defaultValue: "OVEN", required: false
            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
            input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: false, required: false
            input name: "switchControlsOven", title: "Switch on/off starts and stops the oven",
                  description: "Off: the switch only reports state, so an 'all off' rule or a voice assistant can't stop a cycle. The Start and Stop commands work either way.",
                  type: "bool", defaultValue: false, required: false
        }
    }
}

def installed() {
    logger("debug", "installed()")
    initialize()
}

def updated() {
    logger("debug", "updated()")
    initialize()
}

def uninstalled() {
    logger("debug", "uninstalled()")
}

def initialize() {
    logger("debug", "initialize()")
    state.version = CODE_VERSION

    def locationName = getActiveLocation()
    updateDataValue("locationName", locationName)
    sendEvent(name: "locationName", value: locationName)

    if (getDataValue("master") == "true") {
        if (interfaces.mqtt.isConnected())
            interfaces.mqtt.disconnect()

        mqttConnectUntilSuccessful()
    }
    
    refresh()
}

// A code push doesn't run updated(). The first status or MQTT message after one does.
void checkVersion() {
    if (state.version == CODE_VERSION) return
    logger("info", "Code version ${CODE_VERSION} (was ${state.version ?: 'none'}); re-initializing")
    state.version = CODE_VERSION
    runIn(1, "updated")
}

def refresh() {
    logger("debug", "refresh()")
    def status = parent.getDeviceState(getDeviceId())
    processStateData(status)
}

def mqttConnectUntilSuccessful() {
    logger("debug", "mqttConnectUntilSuccessful()")

    try {
        // A retry can find a half-open connection (e.g. connect worked, subscribe failed)
        if (interfaces.mqtt.isConnected()) interfaces.mqtt.disconnect()

        def mqtt = parent.retrieveMqttDetails()

        interfaces.mqtt.connect(mqtt.server,
                                mqtt.clientId,
                                null,
                                null,
                                tlsVersion: "1.2",
                                privateKey: mqtt.privateKey,
                                caCertificate: mqtt.caCertificate,
                                clientCertificate: mqtt.certificate,
                                cleanSession: true)
        pauseExecution(3000)
        for (sub in mqtt.subscriptions) {
            interfaces.mqtt.subscribe(sub)
        }
        return true
    }
    catch (e) {
        scheduleMqttRetry("connect failed: ${e}")
        return false
    }
}

// Retry 15 s after a failure, doubling up to 10 min; a successful connection resets it.
// A broker that accepts and then drops the connection would otherwise loop every few seconds.
void scheduleMqttRetry(String why) {
    int n = (state.mqttRetries ?: 0) as int
    int delay = Math.min(15 * (1 << Math.min(n, 6)), 600)
    state.mqttRetries = n + 1
    // One drop usually heals on the first retry; only a run of failures is an error
    logger(n >= 3 ? "error" : "warn", "MQTT ${why}; retrying in ${delay} s")
    runIn(delay, "mqttConnectUntilSuccessful")
}

def parse(message) {
    checkVersion()
    def topic = interfaces.mqtt.parseMessage(message)
    def payload = new JsonSlurper().parseText(topic.payload)
    logger("trace", "parse(${payload})")

    parent.processMqttMessage(this, payload)
}

def mqttClientStatus(String message) {
    logger("debug", "mqttClientStatus(${message})")

    if (message.startsWith("Error:")) {
        try {
            interfaces.mqtt.disconnect()
        }
        catch (e) {
        }
        scheduleMqttRetry(message)
    } else if (message.contains("Connection succeeded")) {
        state.mqttRetries = 0
        unschedule("mqttConnectUntilSuccessful")   // a retry still pending would drop this connection
    }
}

def processStateData(data) {
    logger("debug", "processStateData(${data})")
    checkVersion()

    // REST answers a list for some appliances and a map for others; a failed REST call hands us null.
    if (data instanceof List) data = data ? data[0] : null
    if (!data) return
    if (!(data instanceof Map)) return

    def locationName = getActiveLocation()

    def runState = selectLocationEntry(data.runState, locationName)
    def operation = selectLocationEntry(data.operation, locationName)
    def cook = selectLocationEntry(data.cook, locationName)
    def remoteControl = selectLocationEntry(data.remoteControlEnable, locationName)
    def timer = selectLocationEntry(data.timer, locationName)
    def temperatureEntries = getLocationTemperatureEntries(data.temperature, locationName)

    // Process current state
    if (runState?.currentState) {
        def currentState = runState.currentState
        sendEvent(name: "currentState", value: currentState)
        
        def switchState = (currentState =~ /(?i)power_off|pause|off/ ? 'off' : 'on')
        sendEvent(name: "switch", value: switchState)
        
        if (logDescText) {
            log.info "${device.displayName} [${locationName}] CurrentState: ${currentState}, Switch: ${switchState}"
        }
    }

    // Process operation mode
    if (operation?.ovenOperationMode) {
        def opMode = cleanEnumValue(operation.ovenOperationMode)
        sendEvent(name: "ovenOperationMode", value: opMode)
    }

    // Process cook mode
    if (cook?.cookMode) {
        def cookMode = cleanEnumValue(cook.cookMode)
        sendEvent(name: "cookMode", value: cookMode)
    }

    // Process remote control
    if (remoteControl?.remoteControlEnabled != null) {
        def remoteEnabled = remoteControl.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    // Process temperature (SDK model supports entries by unit)
    if (temperatureEntries) {
        temperatureEntries.each { temp ->
            def tempUnit = temp?.unit?.toString()?.toUpperCase()
            def tempValue = temp?.targetTemperature

            if (tempValue != null && tempUnit == "C") {
                sendEvent(name: "targetTemperatureC", value: tempValue)
            }
            else if (tempValue != null && tempUnit == "F") {
                sendEvent(name: "targetTemperatureF", value: tempValue)
            }
        }

        def lastUnit = device.currentValue("temperatureUnit")?.toString()?.toUpperCase()
        def preferredUnit = temperatureEntries.find { it?.unit?.toString()?.toUpperCase() == lastUnit }?.unit ?:
            temperatureEntries.find { it?.unit?.toString()?.toUpperCase() == "C" }?.unit ?:
            temperatureEntries[0]?.unit
        if (preferredUnit) {
            sendEvent(name: "temperatureUnit", value: preferredUnit)
        }
    }

    // Process timer information
    if (timer?.remainHour != null) {
        sendEvent(name: "remainHour", value: timer.remainHour)
    }
    if (timer?.remainMinute != null) {
        sendEvent(name: "remainMinute", value: timer.remainMinute)
    }
    if (timer?.remainSecond != null) {
        sendEvent(name: "remainSecond", value: timer.remainSecond)
    }
    if (timer?.targetHour != null) {
        sendEvent(name: "targetHour", value: timer.targetHour)
    }
    if (timer?.targetMinute != null) {
        sendEvent(name: "targetMinute", value: timer.targetMinute)
    }
    if (timer?.targetSecond != null) {
        sendEvent(name: "targetSecond", value: timer.targetSecond)
    }
    if (timer?.timerHour != null) {
        sendEvent(name: "timerHour", value: timer.timerHour)
    }
    if (timer?.timerMinute != null) {
        sendEvent(name: "timerMinute", value: timer.timerMinute)
    }
    if (timer?.timerSecond != null) {
        sendEvent(name: "timerSecond", value: timer.timerSecond)
    }
}

def getDeviceProfile() {
    logger("debug", "getDeviceProfile()")
    parent.getDeviceProfile(getDeviceId())
}

def start() {
    logger("debug", "start()")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        operation: [
            ovenOperationMode: "START"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def stop() {
    logger("debug", "stop()")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        operation: [
            ovenOperationMode: "STOP"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def on() {
    if (!switchControlsOven) {
        logger("warn", "on() ignored: 'Switch on/off starts and stops the oven' is off; use the Start command")
        return
    }
    start()
}

def off() {
    if (!switchControlsOven) {
        logger("warn", "off() ignored: 'Switch on/off starts and stops the oven' is off; use the Stop command")
        return
    }
    stop()
}

def setOvenOperationMode(mode) {
    logger("debug", "setOvenOperationMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        operation: [
            ovenOperationMode: mode
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setCookMode(mode) {
    logger("debug", "setCookMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        operation: [
            ovenOperationMode: "START"
        ],
        cook: [
            cookMode: mode
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setTargetTemperatureC(temperature) {
    logger("debug", "setTargetTemperatureC(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        operation: [
            ovenOperationMode: "START"
        ],
        temperature: [
            targetTemperature: temperature,
            unit: "C"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setTargetTemperatureF(temperature) {
    logger("debug", "setTargetTemperatureF(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        operation: [
            ovenOperationMode: "START"
        ],
        temperature: [
            targetTemperature: temperature,
            unit: "F"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setTargetTime(hour, minute) {
    logger("debug", "setTargetTime(${hour}, ${minute})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        operation: [
            ovenOperationMode: "START"
        ],
        timer: [
            targetHour: hour,
            targetMinute: minute
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setTimer(hour, minute) {
    logger("debug", "setTimer(${hour}, ${minute})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: getActiveLocation()
        ],
        timer: [
            timerHour: hour,
            timerMinute: minute
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

private boolean isLocationConfigured() {
    return (ovenLocation ?: getDataValue("locationName")) as boolean
}

private def getActiveLocation() {
    def loc = ovenLocation ?: getDataValue("locationName") ?: "OVEN"
    return OVEN_LOCATIONS.contains(loc) ? loc : "OVEN"
}

private def selectLocationEntry(resource, String locationName) {
    if (!resource) return null

    if (resource instanceof List) {
        def entry = resource.find {
            it?.location?.locationName == locationName || it?.locationName == locationName
        }
        if (entry) return entry
        // A push carries only the changed locations: with a location configured, another
        // location's entry is not ours. Entries without location markers are taken as they come.
        boolean hasMarkers = resource.any { it?.location?.locationName || it?.locationName }
        return (!isLocationConfigured() || !hasMarkers) ? resource[0] : null
    }

    return (resource instanceof Map) ? resource : null
}

private def getLocationTemperatureEntries(resource, String locationName) {
    if (!resource) return null

    if (resource instanceof List) {
        // Prefer same location if location markers exist; otherwise keep all entries
        def locationEntries = resource.findAll {
            it?.location?.locationName == locationName || it?.locationName == locationName
        }
        if (locationEntries) {
            return locationEntries
        }

        // Another location's entries are not ours when a location is configured
        boolean hasMarkers = resource.any { it?.location?.locationName || it?.locationName }
        return (!isLocationConfigured() || !hasMarkers) ? resource : null
    }

    return (resource instanceof Map) ? [resource] : null
}

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

def cleanEnumValue(value) {
    if (value == null) return ""
    return value.toString()
        .toLowerCase()
        .split('_')
        .collect { it.capitalize() }
        .join(' ')
}

/**
* @param level Level to log at, see LOG_LEVELS for options
* @param msg Message to log
*/
private logger(level, msg) {
    if (level && msg) {
        Integer levelIdx = LOG_LEVELS.indexOf(level)
        Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
        if (setLevelIdx < 0) {
            setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
        }
        if (levelIdx <= setLevelIdx) {
            log."${level}" "${device.displayName} ${msg}"
        }
    }
}
