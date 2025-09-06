/**
 *  ThinQ Connect Cooktop
 *
 *  Copyright 2025
 *
 *  Uses official LG ThinQ Connect API
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Cooktop", namespace: "jonozzz", author: "Ionut Turturica") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"

        attribute "currentState", "string"
        attribute "powerLevel", "number"
        attribute "remoteControlEnabled", "string"
        
        // Timer attributes
        attribute "remainHour", "number"
        attribute "remainMinute", "number"
        
        // Commands
        command "setPowerLevel", ["number"]
        command "setRemainHour", ["number"]
        command "setRemainMinute", ["number"]
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
    data = data[0]

    if (!data) return

    // Process current state
    if (data.cookingZone?.currentState) {
        def currentState = data.cookingZone.currentState
        sendEvent(name: "currentState", value: currentState)
        
        def switchState = (currentState =~ /(?i)power_off|pause/ ? 'off' : 'on')
        sendEvent(name: "switch", value: switchState)
        
        if (logDescText) {
            log.info "${device.displayName} CurrentState: ${currentState}, Switch: ${switchState}"
        }
    }

    // Process power level
    if (data.power?.powerLevel != null) {
        sendEvent(name: "powerLevel", value: data.power.powerLevel)
    }

    // Process remote control
    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    // Process timer information
    if (data.timer?.remainHour != null) {
        sendEvent(name: "remainHour", value: data.timer.remainHour)
    }
    if (data.timer?.remainMinute != null) {
        sendEvent(name: "remainMinute", value: data.timer.remainMinute)
    }
}

def on() {
    logger("debug", "on()")
    // Cooktop doesn't have a simple on command, so we'll leave this empty
}

def off() {
    logger("debug", "off()")
    // Cooktop doesn't have a simple off command, so we'll leave this empty
}

def setPowerLevel(level) {
    logger("debug", "setPowerLevel(${level})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "CENTER"  // Default location, in a real implementation this might need to be configurable
        ],
        power: [
            powerLevel: level
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRemainHour(hours) {
    logger("debug", "setRemainHour(${hours})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "CENTER"  // Default location, in a real implementation this might need to be configurable
        ],
        timer: [
            remainHour: hours
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRemainMinute(minutes) {
    logger("debug", "setRemainMinute(${minutes})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "CENTER"  // Default location, in a real implementation this might need to be configurable
        ],
        timer: [
            remainMinute: minutes
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
