/**
 *  ThinQ Connect Dryer
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
    definition(name: "ThinQ Connect Dryer", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_dryer.groovy") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "ContactSensor"
        capability "Refresh"

        attribute "runTime", "number"
        attribute "runTimeDisplay", "string"
        attribute "remainingTime", "number"
        attribute "remainingTimeDisplay", "string"
        attribute "totalTime", "number"
        attribute "totalTimeDisplay", "string"
        attribute "finishTimeDisplay", "string"
        attribute "currentState", "string"
        attribute "error", "string"
        attribute "operationMode", "string"
        attribute "remoteControlEnabled", "string"
        
        // Timer attributes
        attribute "relativeHourToStop", "number"
        attribute "relativeMinuteToStop", "number"
        
        // Commands
        command "start"
        command "stop"
        command "powerOff"
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
        logger("info", "This device is the MQTT master - initializing MQTT connection")
        if (interfaces.mqtt.isConnected()) {
            logger("debug", "MQTT already connected, disconnecting first")
            interfaces.mqtt.disconnect()
        }

        mqttConnectUntilSuccessful()
    } else {
        logger("info", "This device is not the MQTT master - skipping MQTT initialization")
    }
    
    refresh()
}

def refresh() {
    logger("debug", "refresh()")
    def deviceId = getDeviceId()
    def status = parent.getDeviceState(deviceId)
    
    // Check if this is a WashTower component
    def component = getDataValue("component")
    if (component == "dryer" && status != null) {
        // Wrap the dryer state for processing
        def dryerState = [dryer: status.dryer]
        processStateData(dryerState)
    } else if (status != null) {
        // Regular dryer device
        processStateData(status)
    }
}

def mqttConnectUntilSuccessful() {
    logger("debug", "mqttConnectUntilSuccessful()")

    try {
        def mqtt = parent.retrieveMqttDetails()
        
        logger("info", "Connecting to MQTT server: ${mqtt.server}")
        logger("debug", "MQTT subscriptions: ${mqtt.subscriptions}")

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
            logger("info", "Subscribing to topic: ${sub}")
            interfaces.mqtt.subscribe(sub)
        }
        
        logger("info", "MQTT connection successful")
        return true
    }
    catch (e) {
        logger("error", "Lost connection to MQTT, retrying in 15 seconds: ${e}")
        runIn(15, "mqttConnectUntilSuccessful")
        return false
    }
}

def parse(message) {
    def topic = interfaces.mqtt.parseMessage(message)
    def payload = new JsonSlurper().parseText(topic.payload)
    logger("info", "MQTT message received on topic: ${topic.topic}")
    logger("debug", "MQTT payload: ${payload}")

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
    
    if (!data) return
    
    // Extract dryer data if it's wrapped in a dryer key
    def dryerData = data.dryer ?: data
    
    if (!dryerData) return

    // Process current state
    if (dryerData.runState?.currentState) {
        def currentState = dryerData.runState.currentState
        sendEvent(name: "currentState", value: currentState)
        
        def switchState = (currentState =~ /(?i)power_off|pause/ ? 'off' : 'on')
        sendEvent(name: "switch", value: switchState)
        
        if (logDescText) {
            log.info "${device.displayName} CurrentState: ${currentState}, Switch: ${switchState}"
        }
    }

    // Process operation mode
    if (dryerData.operation?.dryerOperationMode) {
        def opMode = cleanEnumValue(dryerData.operation.dryerOperationMode)
        sendEvent(name: "operationMode", value: opMode)
    }

    // Process remote control
    if (dryerData.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = dryerData.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    // Store timer values as strings for persistence
    if (dryerData.timer?.remainHour != null) {
        updateDataValue("remainHour", dryerData.timer.remainHour.toString())
    }

    if (dryerData.timer?.remainMinute != null) {
        updateDataValue("remainMinute", dryerData.timer.remainMinute.toString())
    }

    if (dryerData.timer?.totalHour != null) {
        updateDataValue("totalHour", dryerData.timer.totalHour.toString())
    }

    if (dryerData.timer?.totalMinute != null) {
        updateDataValue("totalMinute", dryerData.timer.totalMinute.toString())
    }

    // Process timer information with safe integer conversion
    def remainHour = safeToInteger(dryerData.timer?.remainHour, getDataValue("remainHour"), 0)
    def remainMinute = safeToInteger(dryerData.timer?.remainMinute, getDataValue("remainMinute"), 0)
    def totalHour = safeToInteger(dryerData.timer?.totalHour, getDataValue("totalHour"), 0)
    def totalMinute = safeToInteger(dryerData.timer?.totalMinute, getDataValue("totalMinute"), 0)

    def remainingTime = (remainHour * 3600) + (remainMinute * 60)
    def totalTime = (totalHour * 3600) + (totalMinute * 60)
    def runTime = totalTime - remainingTime

    sendEvent(name: "remainingTime", value: remainingTime, unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: convertSecondsToTime(remainingTime))
    sendEvent(name: "totalTime", value: totalTime, unit: "seconds")
    sendEvent(name: "totalTimeDisplay", value: convertSecondsToTime(totalTime))
    sendEvent(name: "runTime", value: runTime, unit: "seconds")
    sendEvent(name: "runTimeDisplay", value: convertSecondsToTime(runTime))

    logger("debug", "remainingTime: ${remainingTime}")

    // Calculate finish time
    if (remainingTime > 0) {
        Date currentTime = new Date()
        use(groovy.time.TimeCategory) {
            currentTime = currentTime + (remainingTime as int).seconds
        }
        def finishTimeDisplay = currentTime.format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone)
        sendEvent(name: "finishTimeDisplay", value: finishTimeDisplay)
    } else {
        sendEvent(name: "finishTimeDisplay", value: "N/A")
    }

    // Process delay timer
    if (dryerData.timer?.relativeHourToStop != null) {
        sendEvent(name: "relativeHourToStop", value: dryerData.timer.relativeHourToStop)
    }
    if (dryerData.timer?.relativeMinuteToStop != null) {
        sendEvent(name: "relativeMinuteToStop", value: dryerData.timer.relativeMinuteToStop)
    }

    // Process error state
    if (dryerData.error) {
        def errorState = cleanEnumValue(dryerData.error)
        sendEvent(name: "error", value: errorState)
    }
}

def start() {
    logger("debug", "start()")
    def deviceId = getDeviceId()
    def component = getDataValue("component")
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            dryerOperationMode: "START"
        ]
    ]
    parent.sendDeviceCommand(deviceId, component, command)
}

def stop() {
    logger("debug", "stop()")
    def deviceId = getDeviceId()
    def component = getDataValue("component")
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            dryerOperationMode: "STOP"
        ]
    ]
    parent.sendDeviceCommand(deviceId, component, command)
}

def powerOff() {
    logger("debug", "powerOff()")
    def deviceId = getDeviceId()
    def component = getDataValue("component")
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            dryerOperationMode: "POWER_OFF"
        ]
    ]
    parent.sendDeviceCommand(deviceId, component, command)
}

def on() {
    start()
}

def off() {
    stop()
}

def getDeviceId() {
    def dni = device.deviceNetworkId.replace("thinqconnect:", "")
    // For WashTower components, extract the parent device ID
    if (dni.contains(":")) {
        return dni.split(":")[0]
    }
    return dni
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

def convertSecondsToTime(int sec) {
    if (sec <= 0) return "00:00"
    
    long hours = sec / 3600
    long minutes = (sec % 3600) / 60
    
    return String.format("%02d:%02d", hours, minutes)
}

/**
 * Safely convert a value to integer with fallbacks
 * @param value Primary value to convert
 * @param fallbackString Fallback string value to parse
 * @param defaultValue Default value if all conversions fail
 * @return Integer value
 */
def safeToInteger(value, fallbackString, defaultValue) {
    // Try primary value first
    if (value != null) {
        if (value instanceof Integer) return value
        if (value instanceof String) {
            try {
                return value.toInteger()
            } catch (Exception e) {
                // Fall through to next option
            }
        }
        // If it's a number type, convert to int
        try {
            return value as Integer
        } catch (Exception e) {
            // Fall through to next option
        }
    }
    
    // Try fallback string
    if (fallbackString != null) {
        try {
            return fallbackString.toInteger()
        } catch (Exception e) {
            // Fall through to default
        }
    }
    
    // Return default
    return defaultValue
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
