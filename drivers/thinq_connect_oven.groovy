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
    if (data.runState?.currentState) {
        def currentState = data.runState.currentState
        sendEvent(name: "currentState", value: currentState)
        
        def switchState = (currentState =~ /(?i)power_off|pause/ ? 'off' : 'on')
        sendEvent(name: "switch", value: switchState)
        
        if (logDescText) {
            log.info "${device.displayName} CurrentState: ${currentState}, Switch: ${switchState}"
        }
    }

    // Process operation mode
    if (data.operation?.ovenOperationMode) {
        def opMode = cleanEnumValue(data.operation.ovenOperationMode)
        sendEvent(name: "ovenOperationMode", value: opMode)
    }

    // Process cook mode
    if (data.cook?.cookMode) {
        def cookMode = cleanEnumValue(data.cook.cookMode)
        sendEvent(name: "cookMode", value: cookMode)
    }

    // Process remote control
    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    // Process temperature
    if (data.temperature) {
        // Handle temperature unit
        if (data.temperature.unit) {
            sendEvent(name: "temperatureUnit", value: data.temperature.unit)
        }
        
        // Handle target temperatures
        if (data.temperature.targetTemperature != null) {
            def tempValue = data.temperature.targetTemperature
            def tempUnit = data.temperature.unit ?: "C"
            
            if (tempUnit == "C") {
                sendEvent(name: "targetTemperatureC", value: tempValue)
            } else if (tempUnit == "F") {
                sendEvent(name: "targetTemperatureF", value: tempValue)
            }
        }
    }

    // Process timer information
    if (data.timer?.remainHour != null) {
        sendEvent(name: "remainHour", value: data.timer.remainHour)
    }
    if (data.timer?.remainMinute != null) {
        sendEvent(name: "remainMinute", value: data.timer.remainMinute)
    }
    if (data.timer?.remainSecond != null) {
        sendEvent(name: "remainSecond", value: data.timer.remainSecond)
    }
    if (data.timer?.targetHour != null) {
        sendEvent(name: "targetHour", value: data.timer.targetHour)
    }
    if (data.timer?.targetMinute != null) {
        sendEvent(name: "targetMinute", value: data.timer.targetMinute)
    }
    if (data.timer?.targetSecond != null) {
        sendEvent(name: "targetSecond", value: data.timer.targetSecond)
    }
    if (data.timer?.timerHour != null) {
        sendEvent(name: "timerHour", value: data.timer.timerHour)
    }
    if (data.timer?.timerMinute != null) {
        sendEvent(name: "timerMinute", value: data.timer.timerMinute)
    }
    if (data.timer?.timerSecond != null) {
        sendEvent(name: "timerSecond", value: data.timer.timerSecond)
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
            locationName: "OVEN"
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
            locationName: "OVEN"
        ],
        operation: [
            ovenOperationMode: "STOP"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def on() {
    start()
}

def off() {
    stop()
}

def setOvenOperationMode(mode) {
    logger("debug", "setOvenOperationMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "OVEN"
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
            locationName: "OVEN"
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
            locationName: "OVEN"
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
            locationName: "OVEN"
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
            locationName: "OVEN"
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
            locationName: "OVEN"
        ],
        timer: [
            timerHour: hour,
            timerMinute: minute
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
