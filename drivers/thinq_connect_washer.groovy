/**
 *  ThinQ Connect Washer
 *
 *  Copyright 2025
 *
 *  Uses official LG ThinQ Connect API
 */

import groovy.transform.Field
import groovy.json.JsonSlurper
import java.math.RoundingMode

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]
// Bump with any change that needs initialize() to run again (new schedules, MQTT handling):
// a code push alone doesn't run updated(), so checkVersion() does it.
@Field static final String CODE_VERSION = "1.2.5"
// LG ThinQ Connect error codes meaning the appliance or model lacks a feature (LG SDK ThinQAPIErrorCodes)
@Field static final List<String> NOT_SUPPORTED_CODES = ["1219", "1220", "1221", "2101", "2201", "2202"]

metadata {
    definition(name: "ThinQ Connect Washer", namespace: "jonozzz", author: "Ionut Turturica",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_washer.groovy") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"
        capability "EnergyMeter"

        attribute "energyToday", "number"
        attribute "energyYesterday", "number"
        attribute "energyThisMonth", "number"
        attribute "energyLastMonth", "number"
        attribute "runTime", "number"
        attribute "runTimeDisplay", "string"
        attribute "remainingTime", "number"
        attribute "remainingTimeDisplay", "string"
        attribute "totalTime", "number"
        attribute "totalTimeDisplay", "string"
        attribute "finishTimeDisplay", "string"
        attribute "currentState", "string"
        attribute "notification", "string"
        attribute "error", "string"
        attribute "operationMode", "string"
        attribute "remoteControlEnabled", "string"
        attribute "cycleCount", "number"
        attribute "detergentSetting", "string"
        
        // Timer attributes
        attribute "relativeHourToStart", "number"
        attribute "relativeMinuteToStart", "number"
        attribute "relativeHourToStop", "number"
        attribute "relativeMinuteToStop", "number"
        
        // Commands
        command "refreshEnergy"
        command "start"
        command "stop"
        command "powerOff"
        command "setDelayStart", ["number"]
    }

    preferences {
        section {
            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
            input name: "energyRefreshMinutes", type: "enum", title: "Energy usage refresh", description: "How often to read energy usage from LG (LG updates it once a day)", options: ["0": "Disabled", "60": "Every hour", "180": "Every 3 hours", "720": "Every 12 hours"], defaultValue: "180", required: false
            input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: false, required: false
            input name: "switchControlsWasher", title: "Switch on/off starts and stops the washer",
                  description: "Off: the switch only reports whether a cycle is running, so an 'all off' rule or a voice assistant can't stop a wash. The Start and Stop commands work either way.",
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

    if (getDataValue("master") == "true") {
        if (interfaces.mqtt.isConnected())
            interfaces.mqtt.disconnect()

        mqttConnectUntilSuccessful()
    }
    
    refresh()
    scheduleEnergyRefresh()
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

def parse(message) {
    checkVersion()
    def topic = interfaces.mqtt.parseMessage(message)
    def payload = new JsonSlurper().parseText(topic.payload)
    logger("trace", "parse(${payload})")

    parent.processMqttMessage(this, payload)
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

// LG's own notifications (pushCode from /push), e.g. washing_is_complete. Every one is an event,
// even when it repeats the last one, so a rule fires for each load.
void processNotification(String code) {
    String value = code.toLowerCase()
    sendEvent(name: "notification", value: value, isStateChange: true, descriptionText: "${device.displayName} LG notification: ${value}")
    if (logDescText) log.info "${device.displayName} LG notification: ${value}"
}

def processStateData(data) {
    logger("debug", "processStateData(${data})")
    checkVersion()
    if (!data) return

    // Normalize payload - API may return a Map or a List with one Map
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    // Process current state
    if (data.runState?.currentState) {
        def currentState = data.runState.currentState
        def previousState = device.currentValue("currentState")
        sendEvent(name: "currentState", value: currentState)

        // switch = a cycle is under way, not merely powered on
        def switchState = isRunningState(currentState) ? 'on' : 'off'
        sendEvent(name: "switch", value: switchState)

        if (logDescText) {
            log.info "${device.displayName} CurrentState: ${currentState}, Switch: ${switchState}"
        }

        // Pushes carry no timestamp and can arrive out of order (RUNNING, then a late INITIAL),
        // and the one at a state change often has a partial timer. Pull the full state shortly
        // after each change so a stale or partial push doesn't stand.
        if (currentState != previousState) runIn(5, "refresh")
    }

    // Process operation mode
    if (data.operation?.washerOperationMode) {
        def opMode = cleanEnumValue(data.operation.washerOperationMode)
        sendEvent(name: "operationMode", value: opMode)
    }

    // Process remote control
    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def remoteEnabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: remoteEnabled)
    }

    // Process timer information. A message carries only the timer fields that changed (often
    // none), so keep the last value of each field and recompute only when the timer is present:
    // a missing field is "unchanged", not 0, and 0 is a real value.
    if (data.timer) {
        ["remainHour", "remainMinute", "totalHour", "totalMinute"].each { k ->
            if (data.timer[k] != null) updateDataValue(k, data.timer[k].toString())
        }
        int remainingTime = storedTimer("remainHour") * 3600 + storedTimer("remainMinute") * 60
        int totalTime = storedTimer("totalHour") * 3600 + storedTimer("totalMinute") * 60
        int runTime = Math.max(totalTime - remainingTime, 0)

        sendEvent(name: "remainingTime", value: remainingTime, unit: "seconds")
        sendEvent(name: "remainingTimeDisplay", value: convertSecondsToTime(remainingTime))
        sendEvent(name: "totalTime", value: totalTime, unit: "seconds")
        sendEvent(name: "totalTimeDisplay", value: convertSecondsToTime(totalTime))
        sendEvent(name: "runTime", value: runTime, unit: "seconds")
        sendEvent(name: "runTimeDisplay", value: convertSecondsToTime(runTime))

        logger("debug", "remainingTime: ${remainingTime}")

        updateFinishTime("finishTimeDisplay", remainingTime, data.runState?.currentState ?: device.currentValue("currentState"))
    }

    // Process delay timer
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

    // Process cycle count
    if (data.cycle?.cycleCount != null) {
        sendEvent(name: "cycleCount", value: data.cycle.cycleCount)
    }

    // Process detergent setting
    if (data.detergent?.detergentSetting) {
        def detergent = cleanEnumValue(data.detergent.detergentSetting)
        sendEvent(name: "detergentSetting", value: detergent)
    }

    // Process error state
    if (data.error) {
        def errorState = cleanEnumValue(data.error)
        sendEvent(name: "error", value: errorState)
    }
}

def start() {
    logger("debug", "start()")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            washerOperationMode: "START"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def stop() {
    logger("debug", "stop()")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            washerOperationMode: "STOP"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def powerOff() {
    logger("debug", "powerOff()")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "MAIN"
        ],
        operation: [
            washerOperationMode: "POWER_OFF"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def on() {
    if (!switchControlsWasher) {
        logger("warn", "on() ignored: 'Switch on/off starts and stops the washer' is off; use the Start command")
        return
    }
    start()
}

def off() {
    if (!switchControlsWasher) {
        logger("warn", "off() ignored: 'Switch on/off starts and stops the washer' is off; use the Stop command")
        return
    }
    stop()
}

def setDelayStart(hours) {
    logger("debug", "setDelayStart(${hours})")
    def deviceId = getDeviceId()
    def command = [
        location: [
            locationName: "MAIN"
        ],
        timer: [
            relativeHourToStart: hours
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

// Powered on but idle, paused, finished, waiting for a delayed start or in error: not running.
// Anything else (DETECTING, RUNNING, RINSING, SPINNING, ...) is part of a cycle.
boolean isRunningState(state) {
    return state != null && !(state.toString() ==~ /(?i)(power_off|initial|pause|end|error|reserved|sleep)/)
}

int storedTimer(String key) {
    def v = getDataValue(key)
    return v?.isInteger() ? v.toInteger() : 0
}

// ---- Energy usage (LG's /devices/energy endpoints, as in the air conditioner driver) ----
// LG aggregates usage once a day (Home Assistant: "updated each morning"), so a few reads a
// day are enough; the minute is picked once per install so hubs don't all call at once.
private void scheduleEnergyRefresh() {
    unschedule("refreshEnergy")
    Integer minutes = (energyRefreshMinutes ?: "180") as Integer
    if (minutes <= 0 || state.energyNotSupported) return   // a manual refreshEnergy still asks LG
    if (state.energyMinute == null) state.energyMinute = new Random().nextInt(60)
    int hours = Math.max(minutes.intdiv(60), 1)
    schedule("${new Random().nextInt(60)} ${state.energyMinute} */${hours} ? * *", "refreshEnergy")
    runIn(10, "refreshEnergy")
}

def refreshEnergy() {
    logger("debug", "refreshEnergy()")
    def deviceId = getDeviceId()
    if (!state.energyProperty) {
        Map r = parent.getEnergyProfileResult(deviceId)
        if (!r?.ok) {
            String code = r?.error?.code
            // LG's "not supported" codes are permanent for this appliance: stop asking. Anything
            // else (1309 when the token lacks the energy scope, an outage) may clear: keep trying.
            if (code in NOT_SUPPORTED_CODES) {
                logger("info", "LG doesn't provide energy usage for this appliance (LG error ${code}); energy refresh is off")
                state.energyNotSupported = true
                unschedule("refreshEnergy")
            } else {
                logger("warn", "Couldn't read the energy profile from LG (${code ? 'LG error ' + code : 'no answer'}); trying again at the next refresh")
            }
            return
        }
        state.remove("energyNotSupported")
        def profile = r.data
        def properties = (profile?.result ?: profile)?.property
        if (properties) state.energyProperty = properties[0].toString()
    }
    if (!state.energyProperty) {
        logger("info", "Energy usage is not available for this appliance")
        unschedule("refreshEnergy")
        return
    }

    TimeZone tz = location.timeZone
    Date now = new Date()
    Calendar lastMonthCal = Calendar.getInstance(tz)
    lastMonthCal.setTime(now)
    lastMonthCal.add(Calendar.MONTH, -1)

    String today = now.format("yyyyMMdd", tz)
    String yesterday = (now - 1).format("yyyyMMdd", tz)
    String thisMonth = now.format("yyyyMM", tz)
    String lastMonth = lastMonthCal.getTime().format("yyyyMM", tz)

    Map daily = energyUsageByDate(deviceId, "DAILY", yesterday, today)
    Map monthly = energyUsageByDate(deviceId, "MONTHLY", lastMonth, thisMonth)

    sendEnergyEvent("energyToday", daily[today])
    sendEnergyEvent("energyYesterday", daily[yesterday])
    sendEnergyEvent("energyThisMonth", monthly[thisMonth])
    sendEnergyEvent("energyLastMonth", monthly[lastMonth])
    sendEnergyEvent("energy", monthly[thisMonth])
}

private Map energyUsageByDate(deviceId, String period, String startDate, String endDate) {
    def response = parent.getEnergyUsage(deviceId, state.energyProperty, period, startDate, endDate)
    def dataList = (response?.result ?: response)?.dataList ?: []
    return dataList.collectEntries { [(it.usedDate?.toString()): it[state.energyProperty]] }
}

private void sendEnergyEvent(String name, wh) {
    if (wh == null) return
    BigDecimal kwh = (wh as BigDecimal).divide(1000G, 3, RoundingMode.HALF_UP)
    sendEvent(name: name, value: kwh, unit: "kWh")
}

// The old version stripped everything up to the last underscore, so NORMAL_WASH became "Wash"
// and TEMPERATURE_LEVEL_EXTRA_LOW became "Low", and distinct values could collide.
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
