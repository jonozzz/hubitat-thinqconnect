/**
 *  ThinQ Connect Refrigerator
 *
 *  Copyright 2026
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

metadata {
    definition(name: "ThinQ Connect Refrigerator", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_refrigerator.groovy") {
        capability "Sensor"
        capability "ContactSensor"
        capability "Initialize"
        capability "Refresh"

        attribute "doorState", "string"
        attribute "temperatureUnit", "string"
        attribute "targetTemperatureC", "number"
        attribute "targetTemperatureF", "number"
        attribute "powerSaveEnabled", "string"
        attribute "ecoFriendlyMode", "string"
        attribute "sabbathMode", "string"
        attribute "rapidFreeze", "string"
        attribute "expressMode", "string"
        attribute "expressFridge", "string"
        attribute "freshAirFilter", "string"
        attribute "usedTime", "number"
        attribute "waterFilterInfoUnit", "string"
        
        // Commands
        command "setTargetTemperatureC", ["number"]
        command "setTargetTemperatureF", ["number"]
        command "setRapidFreeze", ["string"]
        command "setExpressMode", ["string"]
        command "setExpressFridge", ["string"]
        command "setFreshAirFilter", ["string"]
    }

    preferences {
        section {
            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
            input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: false, required: false
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
    // If we still don't have a Map, bail out
    if (!(data instanceof Map)) return

    // Process door status
    if (data.doorStatus) {
        def doorEntry = null
        if (data.doorStatus instanceof List) {
            // A push carries only the changed locations: take MAIN, or the first entry only when entries carry no location
            doorEntry = selectLocationEntry(data.doorStatus, "MAIN")
        }
        else {
            doorEntry = data.doorStatus
        }
        if (doorEntry?.doorState) {
            def doorState = doorEntry.doorState
            sendEvent(name: "doorState", value: doorState)

            def contactValue = (doorState == "OPEN") ? "open" : "closed"
            sendEvent(name: "contact", value: contactValue, descriptionText: "${device.displayName} contact is ${contactValue}")

            if (logDescText) {
                log.info "${device.displayName} DoorState: ${doorState} | Contact: ${contactValue}"
            }
        }
    }

    // Process temperature information
    if (data.temperatureInUnits) {
        def tempEntry = null
        if (data.temperatureInUnits instanceof List) {
            tempEntry = selectLocationEntry(data.temperatureInUnits, "FRIDGE")
        }
        else {
            tempEntry = data.temperatureInUnits
        }

        if (tempEntry) {
            // Handle temperature unit
            if (tempEntry.unit) {
                sendEvent(name: "temperatureUnit", value: tempEntry.unit)
            }
            
            // Handle target temperatures
            if (tempEntry.targetTemperatureC != null) {
                sendEvent(name: "targetTemperatureC", value: tempEntry.targetTemperatureC)
            }
            if (tempEntry.targetTemperatureF != null) {
                sendEvent(name: "targetTemperatureF", value: tempEntry.targetTemperatureF)
            }
        }
    }

    // Process power save
    if (data.powerSave?.powerSaveEnabled != null) {
        def powerSave = data.powerSave.powerSaveEnabled ? "enabled" : "disabled"
        sendEvent(name: "powerSaveEnabled", value: powerSave)
    }

    // Process eco friendly mode
    if (data.ecoFriendly?.ecoFriendlyMode) {
        def ecoMode = cleanEnumValue(data.ecoFriendly.ecoFriendlyMode)
        sendEvent(name: "ecoFriendlyMode", value: ecoMode)
    }

    // Process sabbath mode
    if (data.sabbath?.sabbathMode != null) {
        def sabbathMode = data.sabbath.sabbathMode ? "enabled" : "disabled"
        sendEvent(name: "sabbathMode", value: sabbathMode)
    }

    // Process refrigeration features
    if (data.refrigeration?.rapidFreeze != null) {
        def rapidFreeze = data.refrigeration.rapidFreeze ? "enabled" : "disabled"
        sendEvent(name: "rapidFreeze", value: rapidFreeze)
    }
    if (data.refrigeration?.expressMode != null) {
        def expressMode = data.refrigeration.expressMode ? "enabled" : "disabled"
        sendEvent(name: "expressMode", value: expressMode)
    }
    if (data.refrigeration?.expressFridge != null) {
        def expressFridge = data.refrigeration.expressFridge ? "enabled" : "disabled"
        sendEvent(name: "expressFridge", value: expressFridge)
    }
    if (data.refrigeration?.freshAirFilter) {
        def freshAirFilter = cleanEnumValue(data.refrigeration.freshAirFilter)
        sendEvent(name: "freshAirFilter", value: freshAirFilter)
    }

    // Process water filter info
    if (data.waterFilterInfo?.usedTime != null) {
        sendEvent(name: "usedTime", value: data.waterFilterInfo.usedTime)
    }
    if (data.waterFilterInfo?.unit) {
        sendEvent(name: "waterFilterInfoUnit", value: data.waterFilterInfo.unit)
    }
}

private def selectLocationEntry(List resource, String locationName) {
    def entry = resource.find { it?.locationName == locationName }
    if (entry) return entry
    // Another location's entry is not ours; entries without location markers are taken as they come
    return resource.any { it?.locationName } ? null : resource[0]
}

def setTargetTemperatureC(temperature) {
    logger("debug", "setTargetTemperatureC(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "FRIDGE"  // Default location, in a real implementation this might need to be configurable
        ],
        temperatureInUnits: [
            targetTemperatureC: temperature,
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
            locationName: "FRIDGE"  // Default location, in a real implementation this might need to be configurable
        ],
        temperatureInUnits: [
            targetTemperatureF: temperature,
            unit: "F"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRapidFreeze(mode) {
    logger("debug", "setRapidFreeze(${mode})")
    def deviceId = getDeviceId()
    def command = [
        refrigeration: [
            rapidFreeze: mode == "enabled" ? true : false
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setExpressMode(mode) {
    logger("debug", "setExpressMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        refrigeration: [
            expressMode: mode == "enabled" ? true : false
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setExpressFridge(mode) {
    logger("debug", "setExpressFridge(${mode})")
    def deviceId = getDeviceId()
    def command = [
        refrigeration: [
            expressFridge: mode == "enabled" ? true : false
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setFreshAirFilter(mode) {
    logger("debug", "setFreshAirFilter(${mode})")
    def deviceId = getDeviceId()
    def command = [
        refrigeration: [
            freshAirFilter: mode
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
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
