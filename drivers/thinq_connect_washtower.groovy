/**
 *  ThinQ Connect WashTower
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
    definition(name: "ThinQ Connect WashTower", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_washtower.groovy") {
        capability "Sensor"
        capability "Initialize"
        capability "Refresh"

        // Washer attributes
        attribute "washerRunTime", "number"
        attribute "washerRunTimeDisplay", "string"
        attribute "washerRemainingTime", "number"
        attribute "washerRemainingTimeDisplay", "string"
        attribute "washerTotalTime", "number"
        attribute "washerTotalTimeDisplay", "string"
        attribute "washerFinishTimeDisplay", "string"
        attribute "washerCurrentState", "string"
        attribute "washerError", "string"
        attribute "washerOperationMode", "string"
        attribute "washerRemoteControlEnabled", "string"
        attribute "washerCycleCount", "number"
        attribute "washerDetergentSetting", "string"
        attribute "washerRelativeHourToStart", "number"
        attribute "washerRelativeMinuteToStart", "number"
        attribute "washerRelativeHourToStop", "number"
        attribute "washerRelativeMinuteToStop", "number"

        // Dryer attributes
        attribute "dryerRunTime", "number"
        attribute "dryerRunTimeDisplay", "string"
        attribute "dryerRemainingTime", "number"
        attribute "dryerRemainingTimeDisplay", "string"
        attribute "dryerTotalTime", "number"
        attribute "dryerTotalTimeDisplay", "string"
        attribute "dryerFinishTimeDisplay", "string"
        attribute "dryerCurrentState", "string"
        attribute "dryerError", "string"
        attribute "dryerOperationMode", "string"
        attribute "dryerRemoteControlEnabled", "string"
        attribute "dryerCycleCount", "number"
        attribute "dryerDryLevel", "string"
        attribute "dryerTemperatureLevel", "string"
        attribute "dryerRelativeHourToStart", "number"
        attribute "dryerRelativeMinuteToStart", "number"
        attribute "dryerRelativeHourToStop", "number"
        attribute "dryerRelativeMinuteToStop", "number"

        // Commands
        command "getDeviceProfile"

        command "startWasher"
        command "stopWasher"
        command "powerOffWasher"
        command "setWasherDelayStart", ["number"]

        command "startDryer"
        command "stopDryer"
        command "powerOffDryer"
        command "setDryerDelayStart", ["number"]

        command "startBoth"
        command "stopBoth"
        command "powerOffBoth"
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
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.disconnect()
        }
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

    def washerState = normalizeSection(data.washer)
    def dryerState = normalizeSection(data.dryer)

    // Fallback for unexpected flat payloads
    if (!washerState && !dryerState) {
        if (data.operation?.washerOperationMode || data.detergent || data.cycle) {
            washerState = data
        }
        if (data.operation?.dryerOperationMode || data.dryLevel || data.temperature) {
            dryerState = data
        }
    }

    if (washerState) {
        processWasherState(washerState)
    }
    if (dryerState) {
        processDryerState(dryerState)
    }
}

private def normalizeSection(section) {
    if (!section) return null

    if (section instanceof List) {
        if (section.isEmpty()) return null
        section = section.find {
            it?.location?.locationName in ["MAIN", "WASHER", "DRYER"] ||
            it?.locationName in ["MAIN", "WASHER", "DRYER"]
        } ?: section[0]
    }

    return (section instanceof Map) ? section : null
}

private def processWasherState(data) {
    if (data.runState?.currentState) {
        def currentState = data.runState.currentState
        def previousState = device.currentValue("washerCurrentState")
        sendEvent(name: "washerCurrentState", value: currentState)
        // A push at a state change often carries a partial timer: pull the full state shortly after.
        if (currentState != previousState) runIn(5, "refresh")
    }

    if (data.operation?.washerOperationMode) {
        def opMode = cleanEnumValue(data.operation.washerOperationMode)
        sendEvent(name: "washerOperationMode", value: opMode)
    }

    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "washerRemoteControlEnabled", value: remoteEnabled)
    }

    // A message carries only the timer fields that changed (often none), so keep the last value
    // of each field and recompute only when the timer is present: a missing field is "unchanged".
    if (data.timer) {
        ["remainHour", "remainMinute", "totalHour", "totalMinute"].each { k ->
            if (data.timer[k] != null) updateDataValue("washer" + k.capitalize(), data.timer[k].toString())
        }
        int remainingTime = storedTimer("washerRemainHour") * 3600 + storedTimer("washerRemainMinute") * 60
        int totalTime = storedTimer("washerTotalHour") * 3600 + storedTimer("washerTotalMinute") * 60
        int runTime = Math.max(totalTime - remainingTime, 0)

        sendEvent(name: "washerRemainingTime", value: remainingTime, unit: "seconds")
        sendEvent(name: "washerRemainingTimeDisplay", value: convertSecondsToTime(remainingTime))
        sendEvent(name: "washerTotalTime", value: totalTime, unit: "seconds")
        sendEvent(name: "washerTotalTimeDisplay", value: convertSecondsToTime(totalTime))
        sendEvent(name: "washerRunTime", value: runTime, unit: "seconds")
        sendEvent(name: "washerRunTimeDisplay", value: convertSecondsToTime(runTime))

        updateFinishTime("washerFinishTimeDisplay", remainingTime, data.runState?.currentState ?: device.currentValue("washerCurrentState"))
    }

    if (data.timer?.relativeHourToStart != null) {
        sendEvent(name: "washerRelativeHourToStart", value: data.timer.relativeHourToStart)
    }
    if (data.timer?.relativeMinuteToStart != null) {
        sendEvent(name: "washerRelativeMinuteToStart", value: data.timer.relativeMinuteToStart)
    }
    if (data.timer?.relativeHourToStop != null) {
        sendEvent(name: "washerRelativeHourToStop", value: data.timer.relativeHourToStop)
    }
    if (data.timer?.relativeMinuteToStop != null) {
        sendEvent(name: "washerRelativeMinuteToStop", value: data.timer.relativeMinuteToStop)
    }

    if (data.cycle?.cycleCount != null) {
        sendEvent(name: "washerCycleCount", value: data.cycle.cycleCount)
    }

    if (data.detergent?.detergentSetting) {
        def detergent = cleanEnumValue(data.detergent.detergentSetting)
        sendEvent(name: "washerDetergentSetting", value: detergent)
    }

    if (data.error) {
        def errorState = cleanEnumValue(data.error)
        sendEvent(name: "washerError", value: errorState)
    }
}

private def processDryerState(data) {
    if (data.runState?.currentState) {
        def currentState = data.runState.currentState
        def previousState = device.currentValue("dryerCurrentState")
        sendEvent(name: "dryerCurrentState", value: currentState)
        // A push at a state change often carries a partial timer: pull the full state shortly after.
        if (currentState != previousState) runIn(5, "refresh")
    }

    if (data.operation?.dryerOperationMode) {
        def opMode = cleanEnumValue(data.operation.dryerOperationMode)
        sendEvent(name: "dryerOperationMode", value: opMode)
    }

    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "dryerRemoteControlEnabled", value: remoteEnabled)
    }

    // A message carries only the timer fields that changed (often none), so keep the last value
    // of each field and recompute only when the timer is present: a missing field is "unchanged".
    if (data.timer) {
        ["remainHour", "remainMinute", "totalHour", "totalMinute"].each { k ->
            if (data.timer[k] != null) updateDataValue("dryer" + k.capitalize(), data.timer[k].toString())
        }
        int remainingTime = storedTimer("dryerRemainHour") * 3600 + storedTimer("dryerRemainMinute") * 60
        int totalTime = storedTimer("dryerTotalHour") * 3600 + storedTimer("dryerTotalMinute") * 60
        int runTime = Math.max(totalTime - remainingTime, 0)

        sendEvent(name: "dryerRemainingTime", value: remainingTime, unit: "seconds")
        sendEvent(name: "dryerRemainingTimeDisplay", value: convertSecondsToTime(remainingTime))
        sendEvent(name: "dryerTotalTime", value: totalTime, unit: "seconds")
        sendEvent(name: "dryerTotalTimeDisplay", value: convertSecondsToTime(totalTime))
        sendEvent(name: "dryerRunTime", value: runTime, unit: "seconds")
        sendEvent(name: "dryerRunTimeDisplay", value: convertSecondsToTime(runTime))

        updateFinishTime("dryerFinishTimeDisplay", remainingTime, data.runState?.currentState ?: device.currentValue("dryerCurrentState"))
    }

    if (data.timer?.relativeHourToStart != null) {
        sendEvent(name: "dryerRelativeHourToStart", value: data.timer.relativeHourToStart)
    }
    if (data.timer?.relativeMinuteToStart != null) {
        sendEvent(name: "dryerRelativeMinuteToStart", value: data.timer.relativeMinuteToStart)
    }
    if (data.timer?.relativeHourToStop != null) {
        sendEvent(name: "dryerRelativeHourToStop", value: data.timer.relativeHourToStop)
    }
    if (data.timer?.relativeMinuteToStop != null) {
        sendEvent(name: "dryerRelativeMinuteToStop", value: data.timer.relativeMinuteToStop)
    }

    if (data.cycle?.cycleCount != null) {
        sendEvent(name: "dryerCycleCount", value: data.cycle.cycleCount)
    }

    if (data.dryLevel?.dryLevel) {
        def dryLevelValue = cleanEnumValue(data.dryLevel.dryLevel)
        sendEvent(name: "dryerDryLevel", value: dryLevelValue)
    }

    if (data.temperature?.temperatureLevel) {
        def tempLevel = cleanEnumValue(data.temperature.temperatureLevel)
        sendEvent(name: "dryerTemperatureLevel", value: tempLevel)
    }

    if (data.error) {
        def errorState = cleanEnumValue(data.error)
        sendEvent(name: "dryerError", value: errorState)
    }
}

def getDeviceProfile() {
    logger("debug", "getDeviceProfile()")
    parent.getDeviceProfile(getDeviceId())
}

def startWasher() {
    logger("debug", "startWasher()")
    sendWashtowerCommand([washer: [operation: [washerOperationMode: "START"]]])
}

def stopWasher() {
    logger("debug", "stopWasher()")
    sendWashtowerCommand([washer: [operation: [washerOperationMode: "STOP"]]])
}

def powerOffWasher() {
    logger("debug", "powerOffWasher()")
    sendWashtowerCommand([washer: [operation: [washerOperationMode: "POWER_OFF"]]])
}

def setWasherDelayStart(hours) {
    logger("debug", "setWasherDelayStart(${hours})")
    sendWashtowerCommand([washer: [timer: [relativeHourToStart: hours]]])
}

def startDryer() {
    logger("debug", "startDryer()")
    sendWashtowerCommand([dryer: [operation: [dryerOperationMode: "START"]]])
}

def stopDryer() {
    logger("debug", "stopDryer()")
    sendWashtowerCommand([dryer: [operation: [dryerOperationMode: "STOP"]]])
}

def powerOffDryer() {
    logger("debug", "powerOffDryer()")
    sendWashtowerCommand([dryer: [operation: [dryerOperationMode: "POWER_OFF"]]])
}

def setDryerDelayStart(hours) {
    logger("debug", "setDryerDelayStart(${hours})")
    sendWashtowerCommand([dryer: [timer: [relativeHourToStart: hours]]])
}

def startBoth() {
    logger("debug", "startBoth()")
    startWasher()
    startDryer()
}

def stopBoth() {
    logger("debug", "stopBoth()")
    stopWasher()
    stopDryer()
}

def powerOffBoth() {
    logger("debug", "powerOffBoth()")
    powerOffWasher()
    powerOffDryer()
}

private def sendWashtowerCommand(command) {
    def deviceId = getDeviceId()
    parent.sendDeviceCommand(deviceId, command)
}

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

int storedTimer(String key) {
    def v = getDataValue(key)
    return v?.isInteger() ? v.toInteger() : 0
}

def cleanEnumValue(value) {
    if (value == null) return ""
    return value.toString()
        .toLowerCase()
        .split('_')
        .collect { it.capitalize() }
        .join(' ')
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

// finishTime = now + remaining, so every message moves it by a few seconds and each refresh of an
// idle appliance logged a new "now". Send it only while a cycle runs (an idle appliance can keep a
// stale remaining minute), and only when it moves by more than 90 s; it keeps the last cycle's time.
private void updateFinishTime(String attr, int remainingTime, runState) {
    if (remainingTime <= 0 || !isRunningState(runState)) return
    long finish = now() + remainingTime * 1000L
    String current = device.currentValue(attr)
    if (current) {
        try {
            if (Math.abs(finish - Date.parse("yyyy-MM-dd'T'HH:mm:ssZ", current).time) < 90000L) return
        } catch (Exception ignored) { }   // an unparseable old value is replaced
    }
    sendEvent(name: attr, value: new Date(finish).format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone))
}

// Powered on but idle, paused, finished, waiting for a delayed start or in error: not running.
boolean isRunningState(state) {
    return state != null && !(state.toString() ==~ /(?i)(power_off|initial|pause|end|error|reserved|sleep)/)
}
