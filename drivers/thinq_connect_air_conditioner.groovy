/**
 *  ThinQ Connect Air Conditioner
 *
 *  Copyright 2026
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
        capability "Thermostat"

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
        // attribute "remoteControlEnabled", "string" // Not exposed by profile
        
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
        // command "powerOff"
        command "getDeviceProfile"
        command "setAirConOperationMode", ["string"]
        command "setAirCleanOperationMode", ["string"]
        command "setAirConJobMode", [[name:"Set AirConJobMode", type: "ENUM", description: "Select AirCon Job Mode", constraints: ["COOL", "HEAT", "AUTO", "AIR_CLEAN", "ENERGY_SAVING", "AIR_DRY", "FAN"]]]
        command "setTargetTemperature", ["number"]
        command "setHeatTargetTemperature", ["number"]
        command "setCoolTargetTemperature", ["number"]
        command "setWindStrength", [[name:"Set Wind Strength", type: "ENUM", description: "Select Wind Strength", constraints: ["LOW", "MID", "HIGH", "POWER", "AUTO"]]]
        command "setWindStep", ["number"]
        command "setRotateUpDown", ["string"]
        command "setRotateLeftRight", ["string"]
        command "setLight", ["string"]
        command "setPowerSave", ["string"]
        command "setTwoSetEnabled", ["string"]
        command "setDelayStart", [[name:"Set Delay Start", type: "NUMBER", description: "Select Delay Start in minutes"]]
        command "setDelayStop", [[name:"Set Delay Stop", type: "NUMBER", description: "Select Delay Stop in minutes"]]
        command "unsetStopTimer"
        command "unsetStartTimer"
        command "setAbsoluteStart", ["number", "number"]
    }

    preferences {
        section {
            input name: 'isFahrenheit', type: 'bool', title: '<b>Fahrenheit</b>', description: '<i>Use fahrenheit degrees</i>', defaultValue: true
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

    // Normalize payload – API may return a Map or a List with a single Map entry
    if (!data) return
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    // Process current state
    def currentState = null
    if (data.runState?.currentState) {
        currentState = data.runState.currentState
        sendEvent(name: "currentState", value: currentState)
    }

    // Process job mode
    def currentJobModeRaw = null
    if (data.airConJobMode?.currentJobMode) {
        currentJobModeRaw = data.airConJobMode.currentJobMode
        def jobMode = cleanEnumValue(currentJobModeRaw)
        sendEvent(name: "currentJobMode", value: jobMode)
    }

    // Process operation modes
    def airConOpModeRaw = null
    if (data.operation?.airConOperationMode) {
        airConOpModeRaw = data.operation.airConOperationMode
        def opMode = cleanEnumValue(airConOpModeRaw)
        sendEvent(name: "airConOperationMode", value: opMode)
    }

    // Derive switch state from runState or operation mode
    def isPoweredOff = false
    if (currentState || airConOpModeRaw) {
        def modeText = currentState ?: airConOpModeRaw ?: ""
        isPoweredOff = (modeText =~ /(?i)power_off|off/)
        def switchState = isPoweredOff ? 'off' : 'on'
        sendEvent(name: "switch", value: switchState)
        if (logDescText && currentState) {
            log.info "${device.displayName} CurrentState: ${currentState}, Switch: ${switchState}"
        }
    }

    // Derive thermostatMode from job mode + power state
    def thermostatMode = lgJobModeToThermostatMode(currentJobModeRaw, isPoweredOff)
    sendEvent(name: "thermostatMode", value: thermostatMode)

    // Derive thermostatOperatingState
    def operatingState = isPoweredOff ? "idle" : lgJobModeToOperatingState(currentJobModeRaw)
    sendEvent(name: "thermostatOperatingState", value: operatingState)

    if (data.operation?.airCleanOperationMode) {
        def cleanMode = cleanEnumValue(data.operation.airCleanOperationMode)
        sendEvent(name: "airCleanOperationMode", value: cleanMode)
    }

    // Remote control not exposed by profile; leave disabled

    // Process temperature information (temperatureInUnits can be a list by unit)
    if (data.temperatureInUnits) {
        def tempEntry = null
        if (data.temperatureInUnits instanceof List) {
            def preferredUnit = isFahrenheit ? "F" : "C"
            tempEntry = data.temperatureInUnits.find { it.unit == preferredUnit } ?: data.temperatureInUnits[0]
        }
        else {
            tempEntry = data.temperatureInUnits
        }

        if (tempEntry) {
            if (tempEntry.unit) {
                sendEvent(name: "temperatureUnit", value: tempEntry.unit)
            }

            if (tempEntry.currentTemperature != null) {
                sendEvent(name: "currentTemperature", value: tempEntry.currentTemperature, unit: tempEntry.unit)
                sendEvent(name: "temperature", value: tempEntry.currentTemperature, unit: tempEntry.unit)
            }

            if (tempEntry.targetTemperature != null) {
                sendEvent(name: "targetTemperature", value: tempEntry.targetTemperature, unit: tempEntry.unit)
                sendEvent(name: "thermostatSetpoint", value: tempEntry.targetTemperature, unit: tempEntry.unit)
                sendEvent(name: "coolingSetpoint", value: tempEntry.targetTemperature, unit: tempEntry.unit)
            }

            if (tempEntry.minTargetTemperature != null) {
                sendEvent(name: "minTargetTemperature", value: tempEntry.minTargetTemperature)
            }

            if (tempEntry.maxTargetTemperature != null) {
                sendEvent(name: "maxTargetTemperature", value: tempEntry.maxTargetTemperature)
            }

            if (tempEntry.heatTargetTemperature != null) {
                sendEvent(name: "heatTargetTemperature", value: tempEntry.heatTargetTemperature, unit: tempEntry.unit)
            }

            if (tempEntry.coolTargetTemperature != null) {
                sendEvent(name: "coolTargetTemperature", value: tempEntry.coolTargetTemperature, unit: tempEntry.unit)
            }

            if (tempEntry.autoTargetTemperature != null) {
                sendEvent(name: "autoTargetTemperature", value: tempEntry.autoTargetTemperature, unit: tempEntry.unit)
            }
        }
    }

    // Process two set temperature
    if (data.twoSetTemperature?.twoSetEnabled != null) {
        def twoSet = data.twoSetTemperature.twoSetEnabled ? "enabled" : "disabled"
        sendEvent(name: "twoSetEnabled", value: twoSet)
    }

    if (data.twoSetTemperatureInUnits) {
        def twoSetEntry = null
        if (data.twoSetTemperatureInUnits instanceof List) {
            def preferredUnit = isFahrenheit ? "F" : "C"
            twoSetEntry = data.twoSetTemperatureInUnits.find { it.unit == preferredUnit } ?: data.twoSetTemperatureInUnits[0]
        }
        else {
            twoSetEntry = data.twoSetTemperatureInUnits
        }

        if (twoSetEntry?.heatTargetTemperature != null) {
            sendEvent(name: "heatTargetTemperature", value: twoSetEntry.heatTargetTemperature, unit: twoSetEntry.unit)
            sendEvent(name: "heatingSetpoint", value: twoSetEntry.heatTargetTemperature, unit: twoSetEntry.unit)
        }

        if (twoSetEntry?.coolTargetTemperature != null) {
            sendEvent(name: "coolTargetTemperature", value: twoSetEntry.coolTargetTemperature, unit: twoSetEntry.unit)
            sendEvent(name: "coolingSetpoint", value: twoSetEntry.coolTargetTemperature, unit: twoSetEntry.unit)
        }
    } else {
        // Single-set: sync heatingSetpoint from heatTargetTemperature if already set
        def heatVal = device.currentValue("heatTargetTemperature")
        if (heatVal != null) {
            sendEvent(name: "heatingSetpoint", value: heatVal)
        }
    }
    }

    // Process airflow information
    if (data.airFlow) {
        def windKey = data.airFlow.windStrengthDetail != null ? "windStrengthDetail" : (data.airFlow.windStrength != null ? "windStrength" : null)
        if (windKey) {
            updateDataValue("windStrengthKey", windKey)
        }

        def wind = data.airFlow.windStrength ?: data.airFlow.windStrengthDetail
        if (wind) {
            def windStrength = cleanEnumValue(wind)
            sendEvent(name: "windStrength", value: windStrength)
            // Map to thermostatFanMode
            sendEvent(name: "thermostatFanMode", value: lgWindStrengthToFanMode(wind))
        }
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

    if (data.display?.light != null) {
        def light = data.display.light ? "on" : "off"
        sendEvent(name: "light", value: light)
    }

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
            airConOperationMode: "POWER_ON"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def stop() {
    logger("debug", "stop()")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            airConOperationMode: "POWER_OFF"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

// def powerOff() {
//     logger("debug", "powerOff()")
//     def deviceId = getDeviceId()
//     def command = [
//         operation: [
//             airConOperationMode: "POWER_OFF"
//         ]
//     ]
//     parent.sendDeviceCommand(deviceId, command)
// }

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

def setAirConJobMode(mode) {
    logger("debug", "setAirConJobMode(${mode})")
    def deviceId = getDeviceId()
    def command = [
        airConJobMode: [
            currentJobMode: mode
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
        temperatureInUnits: [
            targetTemperature: temperature,
            unit: isFahrenheit ? "F" : "C"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setHeatTargetTemperature(temperature) {
    logger("debug", "setHeatTargetTemperature(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        temperatureInUnits: [
            heatTargetTemperature: temperature,
            unit: isFahrenheit ? "F" : "C"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setCoolTargetTemperature(temperature) {
    logger("debug", "setCoolTargetTemperature(${temperature})")
    def deviceId = getDeviceId()
    def command = [
        temperatureInUnits: [
            coolTargetTemperature: temperature,
            unit: isFahrenheit ? "F" : "C"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setCoolingSetpoint(temperature) {
    logger("debug", "setCoolingSetpoint(${temperature})")
    // Thermostat cooling setpoint maps to single-set target temperature
    setTargetTemperature(temperature)
}

def setHeatingSetpoint(temperature) {
    logger("debug", "setHeatingSetpoint(${temperature})")
    setHeatTargetTemperature(temperature)
}

def setThermostatMode(mode) {
    logger("debug", "setThermostatMode(${mode})")
    switch (mode) {
        case "off":
            stop()
            break
        case "heat":
            start()
            setAirConJobMode("HEAT")
            break
        case "cool":
            start()
            setAirConJobMode("COOL")
            break
        case "auto":
            start()
            setAirConJobMode("AUTO")
            break
        case "emergency heat":
            start()
            setAirConJobMode("HEAT")
            break
        default:
            logger("warn", "setThermostatMode: unknown mode '${mode}'")
    }
}

def heat() { setThermostatMode("heat") }
def cool() { setThermostatMode("cool") }
def auto() { setThermostatMode("auto") }
def emergencyHeat() { setThermostatMode("emergency heat") }

def setThermostatFanMode(fanMode) {
    logger("debug", "setThermostatFanMode(${fanMode})")
    def strength = hubFanModeToLgWindStrength(fanMode)
    if (strength) {
        setWindStrength(strength)
    }
}

def fanAuto()      { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("circulate") }
def fanOn()        { setThermostatFanMode("on") }

def setWindStrength(strength) {
    logger("debug", "setWindStrength(${strength})")
    def deviceId = getDeviceId()
    def windKey = getDataValue("windStrengthKey") ?: "windStrength"
    def command = [
        airFlow: [
            (windKey): strength
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
            rotateUpDown: toBooleanValue(enabled)
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setRotateLeftRight(enabled) {
    logger("debug", "setRotateLeftRight(${enabled})")
    def deviceId = getDeviceId()
    def command = [
        windDirection: [
            rotateLeftRight: toBooleanValue(enabled)
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setLight(state) {
    logger("debug", "setLight(${state})")
    def deviceId = getDeviceId()
    def command = [
        display: [
            light: state
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setPowerSave(enabled) {
    logger("debug", "setPowerSave(${enabled})")
    def deviceId = getDeviceId()
    def command = [
        powerSave: [
            powerSaveEnabled: toBooleanValue(enabled)
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setTwoSetEnabled(enabled) {
    logger("debug", "setTwoSetEnabled(${enabled})")
    def deviceId = getDeviceId()
    def command = [
        twoSetTemperature: [
            twoSetEnabled: toBooleanValue(enabled)
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setDelayStart(minutes) {
    logger("debug", "setDelayStart(${minutes})")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            relativeHourToStart: minutes.intdiv(60),
            relativeMinuteToStart: minutes % 60
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def setDelayStop(minutes) {
    logger("debug", "setDelayStop(${minutes})")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            relativeHourToStop: minutes.intdiv(60),
            relativeMinuteToStop: minutes % 60
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def unsetStopTimer() {
    logger("debug", "unsetStopTimer()")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            relativeHourToStop: 0,
            relativeMinuteToStop: 0
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def unsetStartTimer() {
    logger("debug", "unsetStartTimer()")
    def deviceId = getDeviceId()
    def command = [
        timer: [
            relativeHourToStart: 0,
            relativeMinuteToStart: 0
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

// ── Thermostat mode helpers ──────────────────────────────────────────────────

private String lgJobModeToThermostatMode(String jobMode, boolean poweredOff) {
    if (poweredOff || !jobMode) return "off"
    switch (jobMode.toUpperCase()) {
        case "HEAT":          return "heat"
        case "COOL":          return "cool"
        case "AUTO":          return "auto"
        case "AIR_DRY":       return "cool"   // closest Hubitat equivalent
        case "FAN":           return "auto"
        case "ENERGY_SAVING": return "cool"
        case "AIR_CLEAN":     return "auto"
        default:              return "auto"
    }
}

private String lgJobModeToOperatingState(String jobMode) {
    if (!jobMode) return "idle"
    switch (jobMode.toUpperCase()) {
        case "HEAT":          return "heating"
        case "COOL":          return "cooling"
        case "AUTO":          return "idle"   // unknown without sensor delta
        case "FAN":           return "fan only"
        case "AIR_DRY":       return "cooling"
        case "ENERGY_SAVING": return "cooling"
        case "AIR_CLEAN":     return "fan only"
        default:              return "idle"
    }
}

private String lgWindStrengthToFanMode(String wind) {
    if (!wind) return "auto"
    switch (wind.toUpperCase()) {
        case "AUTO":  return "auto"
        case "LOW":   return "circulate"
        case "MID":   return "on"
        case "HIGH":  return "on"
        case "POWER": return "on"
        default:      return "auto"
    }
}

private String hubFanModeToLgWindStrength(String fanMode) {
    switch (fanMode?.toLowerCase()) {
        case "auto":      return "AUTO"
        case "circulate": return "LOW"
        case "on":        return "MID"
        default:          return null
    }
}

// ─────────────────────────────────────────────────────────────────────────────

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

private Boolean toBooleanValue(value) {
    if (value instanceof Boolean) return value
    if (value == null) return false

    def normalized = value.toString().trim().toLowerCase()
    return normalized in ["true", "on", "enabled", "yes", "1", "set"]
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
