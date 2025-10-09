/**
 *  ThinQ Connect Air Conditioner
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
    definition(name: "ThinQ Connect Air Conditioner", namespace: "jonozzz", author: "Ionut Turturica") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatCoolingSetpoint"

        attribute "currentState", "string"
        attribute "currentJobMode", "string"
        attribute "airConOperationMode", "string"
        attribute "airCleanOperationMode", "string"
        attribute "currentTemperature", "number"
        attribute "targetTemperature", "number"
        attribute "minTargetTemperature", "number"
        attribute "maxTargetTemperature", "number"
        attribute "heatTargetTemperature", "number"
        attribute "coolTargetTemperature", "number"
        attribute "temperatureUnit", "string"
        attribute "twoSetEnabled", "string"
        attribute "windStrength", "string"
        attribute "windStep", "number"
        attribute "rotateUpDown", "string"
        attribute "rotateLeftRight", "string"
        attribute "light", "string"
        attribute "powerSaveEnabled", "string"
        attribute "airQualityMonitoringEnabled", "string"
        attribute "pm1", "number"
        attribute "pm2", "number"
        attribute "pm10", "number"
        attribute "odorLevel", "string"
        attribute "humidity", "number"
        attribute "totalPollutionLevel", "string"
        attribute "filterRemainPercent", "number"
        attribute "filterUsedTime", "number"
        attribute "filterLifetime", "number"
        attribute "error", "string"
        attribute "remoteControlEnabled", "string"
        
        // Timer attributes
        attribute "relativeHourToStart", "number"
        attribute "relativeMinuteToStart", "number"
        attribute "relativeHourToStop", "number"
        attribute "relativeMinuteToStop", "number"
        attribute "absoluteHourToStart", "number"
        attribute "absoluteMinuteToStart", "number"
        attribute "absoluteHourToStop", "number"
        attribute "absoluteMinuteToStop", "number"
        
        // Sleep timer attributes
        attribute "sleepRelativeHourToStop", "number"
        attribute "sleepRelativeMinuteToStop", "number"
        
        // Commands
        command "start"
        command "stop"
        command "powerOff"
        command "getDeviceProfile"
        command "setAirConOperationMode", ["string"]
        command "setAirCleanOperationMode", ["string"]
        command "setTargetTemperature", ["number"]
        command "setHeatTargetTemperature", ["number"]
        command "setCoolTargetTemperature", ["number"]
        command "setWindStrength", ["string"]
        command "setWindStep", ["number"]
        command "setRotateUpDown", ["string"]
        command "setRotateLeftRight", ["string"]
        command "setLight", ["string"]
        command "setPowerSave", ["string"]
        command "setTwoSetEnabled", ["string"]
        command "setDelayStart", ["number"]
        command "setAbsoluteStart", ["number", "number"]
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

    // Process job mode
    if (data.airConJobMode?.currentJobMode) {
        def jobMode = cleanEnumValue(data.airConJobMode.currentJobMode)
        sendEvent(name: "currentJobMode", value: jobMode)
    }

    // Process operation modes
    if (data.operation?.airConOperationMode) {
        def opMode = cleanEnumValue(data.operation.airConOperationMode)
        sendEvent(name: "airConOperationMode", value: opMode)
    }

    if (data.operation?.airCleanOperationMode) {
        def cleanMode = cleanEnumValue(data.operation.airCleanOperationMode)
        sendEvent(name: "airCleanOperationMode", value: cleanMode)
    }

    // Process remote control
    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    // Process temperature information
    if (data.temperature?.currentTemperatureC != null) {
        def currentTemp = data.temperature.currentTemperatureC
        sendEvent(name: "currentTemperature", value: currentTemp, unit: "C")
        sendEvent(name: "temperature", value: currentTemp, unit: "C")
    } else if (data.temperature?.currentTemperatureF != null) {
        def currentTemp = data.temperature.currentTemperatureF
        sendEvent(name: "currentTemperature", value: currentTemp, unit: "F")
        sendEvent(name: "temperature", value: currentTemp, unit: "F")
    }

    if (data.temperature?.targetTemperatureC != null) {
        def targetTemp = data.temperature.targetTemperatureC
        sendEvent(name: "targetTemperature", value: targetTemp, unit: "C")
        sendEvent(name: "coolingSetpoint", value: targetTemp, unit: "C")
    } else if (data.temperature?.targetTemperatureF != null) {
        def targetTemp = data.temperature.targetTemperatureF
        sendEvent(name: "targetTemperature", value: targetTemp, unit: "F")
        sendEvent(name: "coolingSetpoint", value: targetTemp, unit: "F")
    }

    if (data.temperature?.minTargetTemperatureC != null) {
        sendEvent(name: "minTargetTemperature", value: data.temperature.minTargetTemperatureC)
    } else if (data.temperature?.minTargetTemperatureF != null) {
        sendEvent(name: "minTargetTemperature", value: data.temperature.minTargetTemperatureF)
    }

    if (data.temperature?.maxTargetTemperatureC != null) {
        sendEvent(name: "maxTargetTemperature", value: data.temperature.maxTargetTemperatureC)
    } else if (data.temperature?.maxTargetTemperatureF != null) {
        sendEvent(name: "maxTargetTemperature", value: data.temperature.maxTargetTemperatureF)
    }

    if (data.temperature?.unit) {
        sendEvent(name: "temperatureUnit", value: data.temperature.unit)
    }

    // Process two set temperature
    if (data.twoSetTemperature?.twoSetEnabled != null) {
        def twoSet = data.twoSetTemperature.twoSetEnabled ? "enabled" : "disabled"
        sendEvent(name: "twoSetEnabled", value: twoSet)
    }

    if (data.twoSetTemperatureInUnits?.heatTargetTemperatureC != null) {
        sendEvent(name: "heatTargetTemperature", value: data.twoSetTemperatureInUnits.heatTargetTemperatureC)
        sendEvent(name: "heatingSetpoint", value: data.twoSetTemperatureInUnits.heatTargetTemperatureC)
    } else if (data.twoSetTemperatureInUnits?.heatTargetTemperatureF != null) {
        sendEvent(name: "heatTargetTemperature", value: data.twoSetTemperatureInUnits.heatTargetTemperatureF)
        sendEvent(name: "heatingSetpoint", value: data.twoSetTemperatureInUnits.heatTargetTemperatureF)
    }

    if (data.twoSetTemperatureInUnits?.coolTargetTemperatureC != null) {
        sendEvent(name: "coolTargetTemperature", value: data.twoSetTemperatureInUnits.coolTargetTemperatureC)
        sendEvent(name: "coolingSetpoint", value: data.twoSetTemperatureInUnits.coolTargetTemperatureC)
    } else if (data.twoSetTemperatureInUnits?.coolTargetTemperatureF != null) {
        sendEvent(name: "coolTargetTemperature", value: data.twoSetTemperatureInUnits.coolTargetTemperatureF)
        sendEvent(name: "coolingSetpoint", value: data.twoSetTemperatureInUnits.coolTargetTemperatureF)
    }

    // Process airflow information
    if (data.airFlow?.windStrength) {
        def windStrength = cleanEnumValue(data.airFlow.windStrength)
        sendEvent(name: "windStrength", value: windStrength)
    }

    if (data.airFlow?.windStep != null) {
        sendEvent(name: "windStep", value: data.airFlow.windStep)
    }

    // Process wind direction
    if (data.windDirection?.rotateUpDown != null) {
        def rotateUpDown = data.windDirection.rotateUpDown ? "enabled" : "disabled"
        sendEvent(name: "rotateUpDown", value: rotateUpDown)
    }

    if (data.windDirection?.rotateLeftRight != null) {
        def rotateLeftRight = data.windDirection.rotateLeftRight ? "enabled" : "disabled"
        sendEvent(name: "rotateLeftRight", value: rotateLeftRight)
    }

    // Process display light
    if (data.display?.light != null) {
        def light = data.display.light ? "on" : "off"
        sendEvent(name: "light", value: light)
    }

    // Process power save
    if (data.powerSave?.powerSaveEnabled != null) {
        def powerSave = data.powerSave.powerSaveEnabled ? "enabled" : "disabled"
        sendEvent(name: "powerSaveEnabled", value: powerSave)
    }

    // Process timer information
    if (data.timer?.relativeHourToStart != null) {
        sendEvent(name: "relativeHourToStart", value: data.timer.relativeHourToStart)
    }
    if (data.timer?.relativeMinuteToStart != null) {
        sendEvent(name: "relativeMinuteToStart", value: data.timer.relativeMinuteToStart)
    }
    if (data.timer?.relativeHourToStop != null) {
        sendEvent(name: "relativeHourToStop", value: data.timer.relativeHourToStop)
    }
    if (data.timer?.relativeMinuteToStop != null) {
        sendEvent(name: "relativeMinuteToStop", value: data.timer.relativeMinuteToStop)
    }
    if (data.timer?.absoluteHourToStart != null) {
        sendEvent(name: "absoluteHourToStart", value: data.timer.absoluteHourToStart)
    }
    if (data.timer?.absoluteMinuteToStart != null) {
        sendEvent(name: "absoluteMinuteToStart", value: data.timer.absoluteMinuteToStart)
    }
    if (data.timer?.absoluteHourToStop != null) {
        sendEvent(name: "absoluteHourToStop", value: data.timer.absoluteHourToStop)
    }
    if (data.timer?.absoluteMinuteToStop != null) {
        sendEvent(name: "absoluteMinuteToStop", value: data.timer.absoluteMinuteToStop)
    }

    // Process sleep timer
    if (data.sleepTimer?.relativeHourToStop != null) {
        sendEvent(name: "sleepRelativeHourToStop", value: data.sleepTimer.relativeHourToStop)
    }
    if (data.sleepTimer?.relativeMinuteToStop != null) {
        sendEvent(name: "sleepRelativeMinuteToStop", value: data.sleepTimer.relativeMinuteToStop)
    }

    // Process air quality sensor data
    if (data.airQualitySensor?.monitoringEnabled != null) {
        def monitoring = data.airQualitySensor.monitoringEnabled ? "enabled" : "disabled"
        sendEvent(name: "airQualityMonitoringEnabled", value: monitoring)
    }

    if (data.airQualitySensor?.PM1 != null) {
        sendEvent(name: "pm1", value: data.airQualitySensor.PM1)
    }

    if (data.airQualitySensor?.PM2 != null) {
        sendEvent(name: "pm2", value: data.airQualitySensor.PM2)
    }

    if (data.airQualitySensor?.PM10 != null) {
        sendEvent(name: "pm10", value: data.airQualitySensor.PM10)
    }

    if (data.airQualitySensor?.odorLevel) {
        def odorLevel = cleanEnumValue(data.airQualitySensor.odorLevel)
        sendEvent(name: "odorLevel", value: odorLevel)
    }

    if (data.airQualitySensor?.humidity != null) {
        sendEvent(name: "humidity", value: data.airQualitySensor.humidity, unit: "%")
    }

    if (data.airQualitySensor?.totalPollutionLevel) {
        def pollutionLevel = cleanEnumValue(data.airQualitySensor.totalPollutionLevel)
        sendEvent(name: "totalPollutionLevel", value: pollutionLevel)
    }

    // Process filter information
    if (data.filterInfo?.filterRemainPercent != null) {
        sendEvent(name: "filterRemainPercent", value: data.filterInfo.filterRemainPercent, unit: "%")
    }

    if (data.filterInfo?.usedTime != null) {
        sendEvent(name: "filterUsedTime", value: data.filterInfo.usedTime, unit: "hours")
    }

    if (data.filterInfo?.filterLifetime != null) {
        sendEvent(name: "filterLifetime", value: data.filterInfo.filterLifetime, unit: "hours")
    }

    // Process error state
    if (data.error) {
        def errorState = cleanEnumValue(data.error)
        sendEvent(name: "error", value: errorState)
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
        operation: [
            airConOperationMode: "START"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def stop() {
    logger("debug", "stop()")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            airConOperationMode: "STOP"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def powerOff() {
    logger("debug", "powerOff()")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            airConOperationMode: "POWER_OFF"
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

def setAirConOperationMode(mode) {
    logger("debug", "setAirConOperationMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            airConOperationMode: mode
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setAirCleanOperationMode(mode) {
    logger("debug", "setAirCleanOperationMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            airCleanOperationMode: mode
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setTargetTemperature(temperature) {
    logger("debug", "setTargetTemperature(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        temperature: [
            targetTemperatureC: temperature
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setHeatTargetTemperature(temperature) {
    logger("debug", "setHeatTargetTemperature(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        twoSetTemperatureInUnits: [
            heatTargetTemperatureC: temperature
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setCoolTargetTemperature(temperature) {
    logger("debug", "setCoolTargetTemperature(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        twoSetTemperatureInUnits: [
            coolTargetTemperatureC: temperature
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setWindStrength(strength) {
    logger("debug", "setWindStrength(${strength})")
    def deviceId = getDeviceId()
    def command = [
        airFlow: [
            windStrength: strength
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setWindStep(step) {
    logger("debug", "setWindStep(${step})")
    def deviceId = getDeviceId()
    def command = [
        airFlow: [
            windStep: step
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRotateUpDown(enabled) {
    logger("debug", "setRotateUpDown(${enabled})")
    def deviceId = getDeviceId()
    def command = [
        windDirection: [
            rotateUpDown: enabled == "enabled" || enabled == true
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRotateLeftRight(enabled) {
    logger("debug", "setRotateLeftRight(${enabled})")
    def deviceId = getDeviceId()
    def command = [
        windDirection: [
            rotateLeftRight: enabled == "enabled" || enabled == true
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setLight(state) {
    logger("debug", "setLight(${state})")
    def deviceId = getDeviceId()
    def command = [
        display: [
            light: state == "on" || state == true
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setPowerSave(enabled) {
    logger("debug", "setPowerSave(${enabled})")
    def deviceId = getDeviceId()
    def command = [
        powerSave: [
            powerSaveEnabled: enabled == "enabled" || enabled == true
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setTwoSetEnabled(enabled) {
    logger("debug", "setTwoSetEnabled(${enabled})")
    def deviceId = getDeviceId()
    def command = [
        twoSetTemperature: [
            twoSetEnabled: enabled == "enabled" || enabled == true
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setDelayStart(hours) {
    logger("debug", "setDelayStart(${hours})")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            relativeHourToStart: hours
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setAbsoluteStart(hour, minute) {
    logger("debug", "setAbsoluteStart(${hour}, ${minute})")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            absoluteHourToStart: hour,
            absoluteMinuteToStart: minute
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

def convertSecondsToTime(int sec) {
    if (sec <= 0) return "00:00"
    
    long hours = sec / 3600
    long minutes = (sec % 3600) / 60
    
    return String.format("%02d:%02d", hours, minutes)
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
