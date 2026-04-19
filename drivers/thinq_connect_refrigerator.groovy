/**
 *  ThinQ Connect Refrigerator
 *
 *  Copyright 2025
 *
 *  Uses official LG ThinQ Connect API
 *
 *  Community bug-fixes and extensions (2026-04-18):
 *    Fix A — doorStatus list parsing: LG API returns doorStatus as a list of
 *             location objects, not a flat map. Prior code threw ClassCastException
 *             before any attributes could populate. Now finds MAIN location by name.
 *    Fix B — ContactSensor capability added for rule-engine door-open/close triggers.
 *    Fix C — temperatureInUnits list parsing: emits per-location fridge/freezer
 *             attributes and preserves backward-compat flat attrs from FRIDGE location.
 *    Fix D — freshAirFilterReplaceNeeded derived boolean attribute.
 *    Fix E — Per-location set-temperature commands (setFridgeTargetTemperatureC/F,
 *             setFreezerTargetTemperatureC/F). Existing setTargetTemperatureC/F
 *             preserved as FRIDGE aliases for backward compatibility.
 *    Fix F — data unwrap: tolerate both List-wrapped and flat-Map API responses.
 *    Fix G — Robustness NITs: doorStatus falls back to first entry when no MAIN
 *             location found; freshAirFilterReplaceNeeded uses contains("REPLACE")
 *             for future variant tolerance; unknown temperatureInUnits locations
 *             emit a debug log instead of silently skipping.
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Refrigerator", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_refrigerator.groovy") {
        capability "Sensor"
        capability "Initialize"
        capability "Refresh"
        capability "ContactSensor"  // Fix B: door open/close for rule-engine triggers

        attribute "doorState", "string"
        attribute "contact", "enum", ["open", "closed"]  // Fix B: ContactSensor standard attr
        attribute "temperatureUnit", "string"
        attribute "targetTemperatureC", "number"
        attribute "targetTemperatureF", "number"
        // Fix C: per-location temperature attributes
        attribute "fridgeTargetTemperatureC", "number"
        attribute "fridgeTargetTemperatureF", "number"
        attribute "fridgeTemperatureUnit", "string"
        attribute "freezerTargetTemperatureC", "number"
        attribute "freezerTargetTemperatureF", "number"
        attribute "freezerTemperatureUnit", "string"
        attribute "powerSaveEnabled", "string"
        attribute "ecoFriendlyMode", "string"
        attribute "sabbathMode", "string"
        attribute "rapidFreeze", "string"
        attribute "expressMode", "string"
        attribute "expressFridge", "string"
        attribute "freshAirFilter", "string"
        attribute "freshAirFilterReplaceNeeded", "enum", ["true", "false"]  // Fix D
        attribute "usedTime", "number"
        attribute "waterFilterInfoUnit", "string"

        // Commands
        command "setTargetTemperatureC", ["number"]   // alias for setFridgeTargetTemperatureC
        command "setTargetTemperatureF", ["number"]   // alias for setFridgeTargetTemperatureF
        command "setFridgeTargetTemperatureC", ["number"]   // Fix E
        command "setFridgeTargetTemperatureF", ["number"]   // Fix E
        command "setFreezerTargetTemperatureC", ["number"]  // Fix E
        command "setFreezerTargetTemperatureF", ["number"]  // Fix E
        command "setRapidFreeze", ["string"]
        command "setExpressMode", ["string"]
        command "setExpressFridge", ["string"]
        command "setFreshAirFilter", ["string"]
        // DIAGNOSTIC: dump raw API state to logs for driver development
        command "dumpRawState"
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

    if (getDataValue("master") == "true") {
        if (interfaces.mqtt.isConnected())
            interfaces.mqtt.disconnect()

        mqttConnectUntilSuccessful()
    }
    
    refresh()
}

def refresh() {
    logger("debug", "refresh()")
    def status = parent.getDeviceState(getDeviceId())
    processStateData(status)
}

// DIAGNOSTIC: dump raw API responses to info-level logs for driver development.
// Captures both the per-device profile (static schema) and current state (live values).
def dumpRawState() {
    log.info "==== DUMP RAW STATE BEGIN: ${device.displayName} ===="
    try {
        def deviceId = getDeviceId()
        log.info "deviceId=${deviceId}"
        try {
            def profile = parent.getDeviceProfile(deviceId)
            def profileJson = groovy.json.JsonOutput.toJson(profile)
            // log in chunks to avoid truncation in Hubitat logs (typical cap ~1024 chars)
            int chunkSize = 900
            log.info "PROFILE length=${profileJson?.length() ?: 0}"
            if (profileJson) {
                for (int i = 0; i < profileJson.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, profileJson.length())
                    log.info "PROFILE[${i}]: ${profileJson.substring(i, end)}"
                }
            }
        } catch (e) {
            log.warn "getDeviceProfile failed: ${e}"
        }
        try {
            def state = parent.getDeviceState(deviceId)
            def stateJson = groovy.json.JsonOutput.toJson(state)
            int chunkSize = 900
            log.info "STATE length=${stateJson?.length() ?: 0}"
            if (stateJson) {
                for (int i = 0; i < stateJson.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, stateJson.length())
                    log.info "STATE[${i}]: ${stateJson.substring(i, end)}"
                }
            }
        } catch (e) {
            log.warn "getDeviceState failed: ${e}"
        }
    } catch (e) {
        log.error "dumpRawState failed: ${e}"
    }
    log.info "==== DUMP RAW STATE END ===="
}

def mqttConnectUntilSuccessful() {
    logger("debug", "mqttConnectUntilSuccessful()")

    try {
        def mqtt = parent.retrieveMqttDetails()

        interfaces.mqtt.connect(mqtt.server,
                                mqtt.clientId,
                                null,
                                null,
                                tlsVersion: "1.2",
                                privateKey: mqtt.privateKey,
                                caCertificate: mqtt.caCertificate,
                                clientCertificate: mqtt.certificate,
                                cleanSession: true,
                                ignoreSSLIssues: true)
        pauseExecution(3000)
        for (sub in mqtt.subscriptions) {
            interfaces.mqtt.subscribe(sub)
        }
        return true
    }
    catch (e) {
        logger("warn", "Lost connection to MQTT, retrying in 15 seconds ${e}")
        runIn(15, "mqttConnectUntilSuccessful")
        return false
    }
}

def parse(message) {
    def topic = interfaces.mqtt.parseMessage(message)
    def payload = new JsonSlurper().parseText(topic.payload)
    logger("trace", "parse(${payload})")

    parent.processMqttMessage(this, payload)
}

def mqttClientStatus(String message) {
    logger("debug", "mqttClientStatus(${message})")

    if (message.startsWith("Error:")) {
        logger("error", "MQTT Error: ${message}")

        try {
            interfaces.mqtt.disconnect()
        }
        catch (e) {
        }
        mqttConnectUntilSuccessful()
    }
}

def processStateData(data) {
    logger("debug", "processStateData(${data})")
    // Fix F: some API call paths return a list wrapper (e.g. historical MQTT messages),
    // others return a flat Map (e.g. getDeviceState for Refrigerator on LRMVS2806S).
    // Handle both without throwing.
    if (data instanceof List) {
        if (data.size() == 0) return
        data = data[0]
    }

    if (!data) return

    // Fix A: doorStatus is a list of location objects, not a flat map.
    // Fix G NIT 1: fall back to first entry if no MAIN location found (e.g. French-door
    // models with only DOOR_IN_DOOR). Future enhancement: emit per-door attrs for all entries.
    if (data.doorStatus instanceof List) {
        def mainDoor = data.doorStatus.find { it.locationName == "MAIN" } ?: data.doorStatus[0]
        if (mainDoor?.doorState) {
            def doorStateVal = mainDoor.doorState
            sendEvent(name: "doorState", value: doorStateVal)
            sendEvent(name: "contact", value: doorStateVal == "OPEN" ? "open" : "closed")  // Fix B
            if (logDescText) {
                log.info "${device.displayName} DoorState: ${doorStateVal}"
            }
        }
    } else if (data.doorStatus?.doorState) {
        // Fallback: handle flat-map form (older firmware / other models)
        def doorStateVal = data.doorStatus.doorState
        sendEvent(name: "doorState", value: doorStateVal)
        sendEvent(name: "contact", value: doorStateVal == "OPEN" ? "open" : "closed")  // Fix B
        if (logDescText) {
            log.info "${device.displayName} DoorState: ${doorStateVal}"
        }
    }

    // Fix C: temperatureInUnits is a list of location objects, not a flat map.
    // Emits per-location fridge/freezer attrs; also populates flat attrs from FRIDGE for
    // backward compatibility with existing rules/dashboards.
    if (data.temperatureInUnits instanceof List) {
        data.temperatureInUnits.each { loc ->
            def prefix = (loc.locationName == "FRIDGE") ? "fridge" :
                         (loc.locationName == "FREEZER") ? "freezer" : null
            if (prefix == null) {
                logger("debug", "Unknown temperature location: ${loc.locationName}")
                return
            }
            if (loc.targetTemperatureC != null) sendEvent(name: "${prefix}TargetTemperatureC", value: loc.targetTemperatureC)
            if (loc.targetTemperatureF != null) sendEvent(name: "${prefix}TargetTemperatureF", value: loc.targetTemperatureF)
            if (loc.unit != null) sendEvent(name: "${prefix}TemperatureUnit", value: loc.unit)
            // Backward compat: flat attrs mirror FRIDGE location
            if (prefix == "fridge") {
                if (loc.targetTemperatureC != null) sendEvent(name: "targetTemperatureC", value: loc.targetTemperatureC)
                if (loc.targetTemperatureF != null) sendEvent(name: "targetTemperatureF", value: loc.targetTemperatureF)
                if (loc.unit != null) sendEvent(name: "temperatureUnit", value: loc.unit)
            }
        }
    } else if (data.temperatureInUnits) {
        // Fallback: flat-map form (older firmware / other models)
        if (data.temperatureInUnits.unit) sendEvent(name: "temperatureUnit", value: data.temperatureInUnits.unit)
        if (data.temperatureInUnits.targetTemperatureC != null) sendEvent(name: "targetTemperatureC", value: data.temperatureInUnits.targetTemperatureC)
        if (data.temperatureInUnits.targetTemperatureF != null) sendEvent(name: "targetTemperatureF", value: data.temperatureInUnits.targetTemperatureF)
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
        // Fix D/G: REPLACE is a read-only status meaning "filter needs replacement".
        // Use contains("REPLACE") to tolerate future variants like "REPLACE_NEEDED".
        sendEvent(name: "freshAirFilterReplaceNeeded",
                  value: (data.refrigeration.freshAirFilter?.toString()?.contains("REPLACE")) ? "true" : "false")
    }

    // Process water filter info
    if (data.waterFilterInfo?.usedTime != null) {
        sendEvent(name: "usedTime", value: data.waterFilterInfo.usedTime)
    }
    if (data.waterFilterInfo?.unit) {
        sendEvent(name: "waterFilterInfoUnit", value: data.waterFilterInfo.unit)
    }
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
            locationName: "FRIDGE"  // Backward-compat alias for setFridgeTargetTemperatureF
        ],
        temperatureInUnits: [
            targetTemperatureF: temperature,
            unit: "F"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

// Fix E: per-location temperature set commands
def setFridgeTargetTemperatureC(temperature) {
    logger("debug", "setFridgeTargetTemperatureC(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [locationName: "FRIDGE"],
        temperatureInUnits: [targetTemperatureC: temperature, unit: "C"]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setFridgeTargetTemperatureF(temperature) {
    logger("debug", "setFridgeTargetTemperatureF(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [locationName: "FRIDGE"],
        temperatureInUnits: [targetTemperatureF: temperature, unit: "F"]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setFreezerTargetTemperatureC(temperature) {
    logger("debug", "setFreezerTargetTemperatureC(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [locationName: "FREEZER"],
        temperatureInUnits: [targetTemperatureC: temperature, unit: "C"]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setFreezerTargetTemperatureF(temperature) {
    logger("debug", "setFreezerTargetTemperatureF(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        location: [locationName: "FREEZER"],
        temperatureInUnits: [targetTemperatureF: temperature, unit: "F"]
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

def getDeviceDetails() {
    def deviceId = getDeviceId()
    return parent.state.foundDevices.find { it.id == deviceId }
}

def cleanEnumValue(value) {
    if (value == null) return ""
    
    // Convert enum values to readable format
    return value.toString()
        .replaceAll(/^[A-Z_]+_/, "")  // Remove prefix
        .replaceAll(/_/, " ")         // Replace underscores with spaces
        .toLowerCase()                // Convert to lowercase
        .split(' ')                   // Split into words
        .collect { it.capitalize() }  // Capitalize each word
        .join(' ')                    // Join back together
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
