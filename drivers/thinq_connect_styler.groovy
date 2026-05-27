/**
 *  ThinQ Connect Styler
 *  jonozzz hubitat-thinqconnect 프레임워크 기반 (thinq_connect_core.groovy 연동)
 *
 *  API 스펙:
 *  - operation.stylerOperationMode: START | STOP | POWER_OFF (write only)
 *  - runState.currentState: PREHEAT | COOLING | STEAM | RESERVED | INITIAL | ERROR |
 *                            STAY | PAUSE | DRYING | POWER_OFF | STERILIZE | NIGHT_DRY |
 *                            RUNNING | COMPLETE | FOTA | END_COOLING | PRESTEAM | DIAGNOSIS
 *  - timer.remainHour / timer.remainMinute (read only)
 *  - remoteControlEnable.remoteControlEnabled (read only, boolean)
 *  - error: e.g. NEED_WATER_REPLENISHMENT
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Styler", namespace: "jonozzz", author: "Custom",
               importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/drivers/thinq_connect_styler.groovy") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"   // ★ master 장치 MQTT 연결에 필수
        capability "Refresh"

        attribute "currentState",          "string"  // RUNNING, DRYING, COMPLETE 등
        attribute "remainingTime",         "number"  // 남은 시간(초)
        attribute "remainingTimeDisplay",  "string"  // 남은 시간 표시 (HH:MM)
        attribute "finishTimeDisplay",     "string"  // 완료 예상 시각
        attribute "remoteControlEnabled",  "string"  // enabled / disabled
        attribute "error",                 "string"  // 에러 코드

        command "start"
        command "stop"
        command "powerOff"
    }

    preferences {
        input name: "logLevel",    title: "Log Level",            type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
        input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: false, required: false
    }
}

// ── 라이프사이클 ─────────────────────────────────────────────────────────────

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

// ── MQTT ─────────────────────────────────────────────────────────────────────

def mqttConnectUntilSuccessful() {
    logger("debug", "mqttConnectUntilSuccessful()")
    try {
        def mqtt = parent.retrieveMqttDetails()
        interfaces.mqtt.connect(
            mqtt.server,
            mqtt.clientId,
            null, null,
            tlsVersion: "1.2",
            privateKey: mqtt.privateKey,
            caCertificate: mqtt.caCertificate,
            clientCertificate: mqtt.certificate,
            cleanSession: true,
            ignoreSSLIssues: true
        )
        pauseExecution(3000)
        for (sub in mqtt.subscriptions) {
            interfaces.mqtt.subscribe(sub)
        }
        return true
    } catch (e) {
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
        try { interfaces.mqtt.disconnect() } catch (e) {}
        mqttConnectUntilSuccessful()
    }
}

// ── Refresh ───────────────────────────────────────────────────────────────────

def refresh() {
    logger("debug", "refresh()")
    def status = parent.getDeviceState(getDeviceId())
    processStateData(status)
}

// ── Switch / Commands ─────────────────────────────────────────────────────────

def on()  { start() }
def off() { stop() }

def start() {
    logger("debug", "start()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ stylerOperationMode: "START" ]
    ])
    // 낙관적 업데이트
    sendEvent(name: "switch",       value: "on")
    sendEvent(name: "currentState", value: "RUNNING")
}

def stop() {
    logger("debug", "stop()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ stylerOperationMode: "STOP" ]
    ])
    sendEvent(name: "switch",       value: "off")
    sendEvent(name: "currentState", value: "PAUSE")
}

def powerOff() {
    logger("debug", "powerOff()")
    parent.sendDeviceCommand(getDeviceId(), [
        operation: [ stylerOperationMode: "POWER_OFF" ]
    ])
    sendEvent(name: "switch",       value: "off")
    sendEvent(name: "currentState", value: "POWER_OFF")
}

// ── 상태 파싱 ──────────────────────────────────────────────────────────────────

// 스타일러 "off" 상태에 해당하는 currentState 목록
@Field static final Set OFF_STATES = ["INITIAL", "POWER_OFF", "ERROR"] as Set

def processStateData(data) {
    logger("debug", "processStateData(${data})")

    if (!data) return
    // washer 드라이버와 동일하게 API가 List로 반환하는 경우 처리
    if (data instanceof List) {
        if (data.isEmpty()) return
        data = data[0]
    }
    if (!(data instanceof Map)) return

    // 1. 작동 상태 & switch 도출
    if (data.runState?.currentState != null) {
        def currentState = data.runState.currentState
        sendEvent(name: "currentState", value: currentState)

        def isOff = OFF_STATES.contains(currentState)
        def switchVal = isOff ? "off" : "on"
        sendEvent(name: "switch", value: switchVal)

        if (logDescText) log.info "${device.displayName} State: ${currentState}, Switch: ${switchVal}"
    }

    // 2. 원격 제어 활성화 여부
    if (data.remoteControlEnable?.remoteControlEnabled != null) {
        def enabled = data.remoteControlEnable.remoteControlEnabled ? "enabled" : "disabled"
        sendEvent(name: "remoteControlEnabled", value: enabled)
    }

    // 3. 남은 시간
    def remainHour   = (data.timer?.remainHour   ?: 0) as int
    def remainMinute = (data.timer?.remainMinute ?: 0) as int
    def remainingSec = (remainHour * 3600) + (remainMinute * 60)

    sendEvent(name: "remainingTime",        value: remainingSec, unit: "seconds")
    sendEvent(name: "remainingTimeDisplay", value: convertSecondsToTime(remainingSec))

    // 4. 완료 예상 시각
    if (remainingSec > 0) {
        Date finishTime = new Date()
        use(groovy.time.TimeCategory) {
            finishTime = finishTime + remainingSec.seconds
        }
        sendEvent(name: "finishTimeDisplay",
                  value: finishTime.format("yyyy-MM-dd'T'HH:mm:ssZ", location.timeZone))
    } else {
        sendEvent(name: "finishTimeDisplay", value: "--")
    }

    // 5. Error state — map known error codes to readable messages
    if (data.error) {
        def errorMessage = convertErrorCode(data.error)
        sendEvent(name: "error", value: errorMessage)
    if (logDescText) log.warn "${device.displayName} Error: ${errorMessage}"
    } else {
        sendEvent(name: "error", value: "none")
    }

}

// ── 헬퍼 ──────────────────────────────────────────────────────────────────────

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

def cleanEnumValue(value) {
    if (value == null) return ""
    return value.toString()
        .replaceAll(/_/, " ")
        .toLowerCase()
        .split(' ')
        .collect { it.capitalize() }
        .join(' ')
}

def convertSecondsToTime(int sec) {
    if (sec <= 0) return "00:00"
    long hours   = sec / 3600
    long minutes = (sec % 3600) / 60
    return String.format("%02d:%02d", hours, minutes)
}

// Convert LG API error codes to readable messages
def convertErrorCode(code) {
    def errorMap = [
        "NEED_WATER_REPLENISHMENT" : "Empty Water Tank",
        "NO_ERROR"                 : "none"
    ]
    return errorMap[code] ?: cleanEnumValue(code)
}


private logger(level, msg) {
    if (level && msg) {
        Integer levelIdx    = LOG_LEVELS.indexOf(level)
        Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
        if (setLevelIdx < 0) setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
        if (levelIdx <= setLevelIdx) {
            log."${level}" "${device.displayName} ${msg}"
        }
    }
}
