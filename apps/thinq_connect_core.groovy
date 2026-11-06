/**
 *
 *  LG ThinQ Connect Integration
 *
 *  Copyright 2026
 *
 *  Uses the official LG ThinQ Connect API with PAT token authentication
 *  Based on the pythinqconnect SDK and Home Assistant integration
 *
 */

import groovy.transform.Field
import java.text.SimpleDateFormat
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.KeyPair
import java.security.Signature
import java.util.concurrent.ConcurrentHashMap

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]
// Bump with any change that needs initialize() to run again (new schedules, subscriptions):
// a code push alone doesn't run updated(), so checkVersion() does it.
@Field static final String CODE_VERSION = "1.2.5"

definition(
    name: "ThinQ Connect Integration",
    namespace: "jonozzz",
    author: "Ionut Turturica",
    description: "Integrate LG ThinQ smart devices with Hubitat using official ThinQ Connect API.",
    menu: "Integrations",
    category: "My Apps",
    importUrl: "https://raw.githubusercontent.com/jonozzz/hubitat-thinqconnect/refs/heads/main/apps/thinq_connect_core.groovy",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    documentationLink: "https://smartsolution.developer.lge.com/en/apiManage/thinq_connect")

preferences {
    page(name: "prefMain")
    page(name: "prefAuth")
    page(name: "prefMqtt")
    page(name: "prefMQTTClient")
    page(name: "prefDevices")
}

@Field static def supportedDeviceTypes = [
    "DEVICE_WASHER",
    "DEVICE_DRYER", 
    "DEVICE_DISH_WASHER",
    "DEVICE_REFRIGERATOR",
    "DEVICE_OVEN",
    "DEVICE_COOKTOP",
    "DEVICE_WASHTOWER",
    "DEVICE_WASHTOWER_WASHER",
    "DEVICE_WASHTOWER_DRYER",
    "DEVICE_MICROWAVE_OVEN",
    "DEVICE_AIR_CONDITIONER",
    "DEVICE_AIR_PURIFIER",
    "DEVICE_STYLER",
    "DEVICE_DEHUMIDIFIER"
]

@Field static def countryNameMap = [
    "US": "United States",
    "CA": "Canada", 
    "KR": "South Korea",
    "JP": "Japan",
    "AU": "Australia",
    "GB": "United Kingdom",
    "DE": "Germany",
    "FR": "France",
    "IT": "Italy",
    "ES": "Spain",
    "NL": "Netherlands",
    "SE": "Sweden",
    "NO": "Norway",
    "DK": "Denmark",
    "FI": "Finland"
]

@Field static def regionMapping = [
    // KIC
    "AU": "kic", "BD": "kic", "CN": "kic", "HK": "kic", "ID": "kic", "IN": "kic", "JP": "kic", "KH": "kic", "KR": "kic", "LA": "kic",
    "LK": "kic", "MM": "kic", "MY": "kic", "NP": "kic", "NZ": "kic", "PH": "kic", "SG": "kic", "TH": "kic", "TW": "kic", "VN": "kic",

    // AIC
    "AG": "aic", "AR": "aic", "AW": "aic", "BB": "aic", "BO": "aic", "BR": "aic", "BS": "aic", "BZ": "aic", "CA": "aic", "CL": "aic",
    "CO": "aic", "CR": "aic", "CU": "aic", "DM": "aic", "DO": "aic", "EC": "aic", "GD": "aic", "GT": "aic", "GY": "aic", "HN": "aic",
    "HT": "aic", "JM": "aic", "KN": "aic", "LC": "aic", "MX": "aic", "NI": "aic", "PA": "aic", "PE": "aic", "PR": "aic", "PY": "aic",
    "SR": "aic", "SV": "aic", "TT": "aic", "US": "aic", "UY": "aic", "VC": "aic", "VE": "aic",

    // EIC
    "AE": "eic", "AF": "eic", "AL": "eic", "AM": "eic", "AO": "eic", "AT": "eic", "AZ": "eic", "BA": "eic", "BE": "eic", "BF": "eic",
    "BG": "eic", "BH": "eic", "BJ": "eic", "BY": "eic", "CD": "eic", "CF": "eic", "CG": "eic", "CH": "eic", "CI": "eic", "CM": "eic",
    "CV": "eic", "CY": "eic", "CZ": "eic", "DE": "eic", "DJ": "eic", "DK": "eic", "DZ": "eic", "EE": "eic", "EG": "eic", "ES": "eic",
    "ET": "eic", "FI": "eic", "FR": "eic", "GA": "eic", "GB": "eic", "GE": "eic", "GH": "eic", "GM": "eic", "GN": "eic", "GQ": "eic",
    "GR": "eic", "HR": "eic", "HU": "eic", "IE": "eic", "IL": "eic", "IQ": "eic", "IR": "eic", "IS": "eic", "IT": "eic", "JO": "eic",
    "KE": "eic", "KG": "eic", "KW": "eic", "KZ": "eic", "LB": "eic", "LR": "eic", "LT": "eic", "LU": "eic", "LV": "eic", "LY": "eic",
    "MA": "eic", "MD": "eic", "ME": "eic", "MK": "eic", "ML": "eic", "MR": "eic", "MT": "eic", "MU": "eic", "MW": "eic", "NE": "eic",
    "NG": "eic", "NL": "eic", "NO": "eic", "OM": "eic", "PK": "eic", "PL": "eic", "PS": "eic", "PT": "eic", "QA": "eic", "RO": "eic",
    "RS": "eic", "RU": "eic", "RW": "eic", "SA": "eic", "SD": "eic", "SE": "eic", "SI": "eic", "SK": "eic", "SL": "eic", "SN": "eic",
    "SO": "eic", "ST": "eic", "SY": "eic", "TD": "eic", "TG": "eic", "TN": "eic", "TR": "eic", "TZ": "eic", "UA": "eic", "UG": "eic",
    "UZ": "eic", "XK": "eic", "YE": "eic", "ZA": "eic", "ZM": "eic"
]

@Field static def caCertUrl = "https://www.amazontrust.com/repository/AmazonRootCA1.pem"
@Field static final String PAT_URL = "https://connect-pat.lgthinq.com"
// Decrypted secrets, in memory only: decrypt() costs 350-830 ms. Key "<app id>:<name>",
// value [ciphertext, plaintext] so a replaced secret misses.
@Field static final ConcurrentHashMap<String, List<String>> SECRET_CACHE = new ConcurrentHashMap<>()

// LG ThinQ Connect error codes worth explaining (from LG's thinqconnect SDK, ThinQAPIErrorCodes)
@Field static final Map LG_ERRORS = [
    "1222": "The appliance isn't connected to LG's cloud. Check that it shows online in the LG ThinQ app (Wi-Fi icon lit); DNS filters such as Pi-hole lists can block lgthinq.com names.",
    "1218": "The token is invalid. Create a new one on the LG token page.",
    "1302": "The token wasn't found. Create a new one on the LG token page.",
    "1304": "LG's terms aren't accepted for this account. Open the LG token page, sign in and accept them.",
    "1306": "Too many API calls. LG blocks the token for a while; try again later.",
    "1307": "This country isn't supported for the token. Pick the country of your LG account.",
    "1308": "The token lacks control permission for this action.",
    "1305": "LG doesn't allow this call for the token: check its scopes on LG's token page.",
    "1309": "LG doesn't allow this call for the token: check its scopes on LG's token page."
]

def prefMain() {
    if (state.client_id == null)
        state.client_id = "thinq-open-" + (UUID.randomUUID().toString().replaceAll(/-/,""))

    String caError = null
    if (state.caCert == null) {
        try {
            httpGet([
                uri: caCertUrl,
                textParser: true,
                timeout: 15
            ]) {
                resp ->
                    state.caCert = resp.data.text
            }
        } catch (Exception e) {
            caError = e.message
            logger("warn", "Could not download Amazon's root certificate from ${caCertUrl}: ${e.message}")
        }
    }

    return dynamicPage(name: "prefMain", title: "ThinQ Connect Setup", nextPage: "prefAuth", uninstall: false, install: false) {
        section {
            input "logLevel", "enum", title: "Log Level", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
            paragraph "This integration uses LG's official ThinQ Connect API. Setup takes three steps:"
            paragraph "<b>1. Create a personal access token</b> on LG's token page, signed in with the LG account your appliances are in (the one in the LG ThinQ app). " +
                      "Under ADD NEW TOKEN, give it a name and tick the scopes: <i>Permission to view all devices</i> and <i>Permission to view all device statuses</i>. " +
                      "Add <i>All device control rights</i> only if you want to start or stop appliances from Hubitat. Copy the token: LG shows it once."
            href name: "patLink", title: "Open LG's token page", description: PAT_URL, style: "external", url: PAT_URL
            paragraph "<b>2. Paste the token and pick your country</b> on the next page; it tests the connection."
            paragraph "<b>3. Live updates:</b> the hub creates the key and certificate request it needs for LG's push service. Nothing to install on a computer."
            paragraph "Appliances must be online in the LG ThinQ app (Wi-Fi icon lit) for Hubitat to read them."
            if (caError) {
                paragraph "❌ The hub couldn't download Amazon's root certificate (${caCertUrl}), which live updates need: ${caError}. " +
                          "Check that a DNS filter doesn't block amazontrust.com, then open this page again."
            }
        }
    }
}

def prefAuth() {
    captureSecrets()
    return dynamicPage(name: "prefAuth", title: "Authentication Setup", nextPage: "prefMqtt", uninstall: false, install: false) {
        section("ThinQ Connect API") {
            String stored = maskToken(patValue())
            input "patToken", "password",
                title: stored ? "Personal access token (stored ${stored}, enter only to change it)" : "Personal access token",
                description: "From LG's token page", required: false
            input "countryCode", "enum", title: "Country", options: getCountryOptions(), required: true, submitOnChange: true
            
            if (countryCode) {
                state.region = regionMapping[countryCode] ?: "aic"
                state.apiBase = "https://api-${state.region}.lgthinq.com"
                paragraph "API Region: ${state.region}"
                paragraph "API Base URL: ${state.apiBase}"
            }
        }
        
        section("Test Connection") {
            if (patValue() && countryCode) {
                def testResult = testApiConnection()
                if (testResult.success) {
                    paragraph "✅ Connection successful! Found ${testResult.deviceCount} devices."
                } else {
                    paragraph "❌ Connection failed: ${testResult.error}"
                    href name: "patLinkRetry", title: "Open LG's token page", description: PAT_URL, style: "external", url: PAT_URL
                }
            } else {
                paragraph "Enter PAT token and country to test connection"
            }
        }
    }
}

def getCountryOptions() {
    def options = [:]

    // Show all supported countries from region mapping.
    // Use friendly names where defined, otherwise show ISO code.
    regionMapping.keySet().sort().each { code ->
        options[code] = countryNameMap[code] ?: code
    }

    return options
}

def prefMqtt() {
    captureSecrets()
    return dynamicPage(name: "prefMqtt", title: "MQTT Configuration", nextPage: "prefMQTTClient", uninstall: false, install: false) {
        section("MQTT Server") {
            if (patValue() && countryCode) {
                def routeResult = getMqttServerFromApi()
                if (routeResult.success) {
                    paragraph "✅ MQTT Server: ${routeResult.mqttServer}"
                    state.mqttServer = routeResult.mqttServer
                } else {
                    paragraph "❌ Failed to get MQTT server: ${routeResult.error}"
                }
            } else {
                paragraph "Complete authentication setup first to get MQTT server details"
            }
        }
        
        section("Certificate") {
            if (!csrData) {
                if (!state.generatedCsr || !state.mqttKeyEnc || state.customKey) {
                    try {
                        Map c = generateMqttCredentials()
                        state.generatedCsr = c.csr
                        storeSecret("mqttKey", "mqttKeyEnc", c.privateKey)
                        state.customKey = false
                    } catch (Exception e) {
                        logger("error", "Key generation failed: ${e}")
                    }
                }
                paragraph state.generatedCsr ?
                    "✅ The hub created a private key and certificate request for LG's push service. Click Next to register them with LG." :
                    "❌ The hub couldn't create a key; paste your own below."
            } else if (state.customKey) {
                paragraph "Using the certificate request below and the private key pasted with it (stored encrypted)."
            } else {
                paragraph "❌ A pasted certificate request needs its private key: paste it below."
            }
        }
        section("Advanced: use your own key", hideable: true, hidden: !csrData) {
            paragraph "Leave the request empty to use the key the hub created. To use your own, make both with openssl (see the README) and paste them. The key is stored encrypted and its field is cleared."
            input "csrData", "textarea", title: "CSR (Certificate Signing Request, PEM)", required: false
            input "privateKey", "textarea", title: state.customKey ? "Private Key (stored, paste only to replace it)" : "Private Key (PEM)", required: false
        }
    }
}

// The pasted request wins; otherwise the one the hub generated on the MQTT page. The private
// key that goes with either is in state.mqttKeyEnc.
String effectiveCsr() { return csrData ?: state.generatedCsr }
// A pasted request must come with its own key; never pair it with the hub-generated one, or LG
// certifies a key the hub doesn't hold and MQTT fails without a visible cause.
String effectiveKey() {
    if (csrData && !state.customKey) return null
    return secretValue("mqttKey", "mqttKeyEnc")
}

def prefMQTTClient() {
    captureSecrets()
    // A new key always comes with a new request (it holds the public key), so the request alone
    // tells whether the certificate must be issued again; no hash of the private key is kept.
    def pk_sig = calculateMD5(effectiveCsr() ?: "")
    return dynamicPage(name: "prefMQTTClient", title: "MQTT Client Registration", nextPage: "prefDevices", uninstall: false, install: false) {
        section {
            if(!state.clientCertificate || !state.mqttSubscriptions || state.pk_sig != pk_sig)
                if (state.certFailedAt && now() - (state.certFailedAt as long) < 60000L) {
                    // A reload right after a failure would ask LG for another certificate at once
                    paragraph "⏳ LG refused a certificate less than a minute ago. Wait a minute, then go Back and Next to retry."
                } else if (csrData && !state.customKey) {
                    paragraph "❌ The pasted certificate request needs its private key: go Back and paste both, or clear the request to use the hub's own key."
                } else if (patValue() && countryCode && state.mqttServer && effectiveCsr() && effectiveKey()) {
                    def mqttResult = setupMqttClient()
                    if (mqttResult.success) {
                        paragraph "✅ MQTT client registered and certificate issued successfully"
                        paragraph "📋 Subscriptions: ${mqttResult.subscriptions?.join(', ')}"
                        state.mqttSubscriptions = mqttResult.subscriptions
                        state.clientCertificate = mqttResult.certificate
                        state.pk_sig = pk_sig
                        state.remove("certFailedAt")
                    } else {
                        state.certFailedAt = now()
                        // paragraph "Try again in 1-2 minutes"
                        paragraph "❌ MQTT setup failed: ${mqttResult.error}. LG limits how often it issues certificates: wait a minute, then go Back and Next to retry."
                    }
                } else {
                    paragraph "Complete all fields above to set up MQTT"
                }
            else
                paragraph "✅ MQTT client already registered"
        }
    }
}

def prefDevices() {
    captureSecrets()
    if (!patValue() || !countryCode) {
        return returnErrorPage("Please complete authentication setup first", "prefAuth")
    }
    
    def devices = getDevices()
    if (devices == null) {
        return returnErrorPage("Unable to retrieve devices. Please check your PAT token and try again.", "prefAuth")
    }

    def deviceList = [:]
    state.foundDevices = []
    
    devices.each { device ->
        logger("debug", "${device}")

        if (supportedDeviceTypes.contains(device.deviceInfo.deviceType)) {
            deviceList << ["${device.deviceId}": "${device.deviceInfo.alias} (${device.deviceInfo.deviceType})"]
            state.foundDevices << [
                id: device.deviceId,
                name: device.deviceInfo.alias,
                type: device.deviceInfo.deviceType,
                modelName: device.deviceInfo.modelName,
                reportable: device.deviceInforeportable
            ]
        }
    }

    return dynamicPage(name: "prefDevices", title: "Device Selection", uninstall: false, install: true) {
        section {
            if (deviceList.size() > 0) {
                input "selectedDevices", "enum", title: "Select Devices", required: true, options: deviceList, multiple: true
                paragraph "Found ${deviceList.size()} supported devices"
            } else {
                paragraph "No supported devices found. Supported types: ${supportedDeviceTypes.join(', ')}"
            }
        }
        List orphans = orphanedDevices()
        if (orphans) {
            section("Devices no longer selected") {
                paragraph "Kept so the rules and dashboards using them don't lose them, but they stop getting updates: ${orphans*.displayName.join(', ')}. " +
                          "Select them again above, or delete them."
                input "deleteOrphans", "button", title: "Delete these devices"
            }
        }
    }
}

def returnErrorPage(message, nextPage) {
    return dynamicPage(name: "prefError", title: "Error Occurred", nextPage: nextPage, uninstall: false, install: false) {
        section {
            paragraph message
        }
    }
}

def installed() {
    logger("debug", "installed()")
    captureSecrets()
    initialize()
}

def updated() {
    logger("debug", "updated()")
    captureSecrets()
    unschedule()
    initialize()
}

def uninstalled() {
    logger("debug", "uninstalled()")
    // Leave nothing subscribed on LG's side (Home Assistant unsubscribes the same way).
    // The client registration stays: LG's SDK deletes it with DELETE /client and the registration
    // body, and Hubitat's httpDelete/asynchttpDelete send no body.
    for (deviceId in (selectedDevices ?: [])) {
        try {
            apiRequest("DELETE", "/event/${deviceId}/unsubscribe")
        } catch (Exception e) {
            logger("warn", "Could not unsubscribe ${deviceId}: ${e}")
        }
    }
    String prefix = "${app.id}:"
    SECRET_CACHE.keySet().removeIf { String k -> k.startsWith(prefix) }
    unschedule()
    for (d in getChildDevices()) {
        deleteChildDevice(d.deviceNetworkId)
    }
}

def initialize() {
    logger("debug", "initialize()")
    checkVersion(false)

    // Daily, at a minute picked once per install so hubs don't all call LG at the same time:
    // re-read every appliance (a backstop for missed pushes) and renew the event subscriptions,
    // as LG's Home Assistant integration does (MQTT_SUBSCRIPTION_INTERVAL = 1 day). Scheduled
    // first, so a failure below can't leave the app without it (updated() unscheduled everything).
    if (state.maintMinute == null) state.maintMinute = new Random().nextInt(60)
    schedule("${new Random().nextInt(60)} ${state.maintMinute} 3 * * ?", "dailyMaintenance")
    state.remove("offlineDevices")   // replaced by one offline_<id> key per appliance

    try {
        reportOrphanedDevices()
    } catch (Exception e) {
        logger("error", "Checking for devices no longer selected failed: ${e}")
    }

    // One appliance failing (a driver not installed, a bad status) must not stop the others
    for (deviceId in selectedDevices) {
        try {
            def deviceDetails = state.foundDevices.find { it.id == deviceId }
            if (!deviceDetails) continue

            def driverName = getDriverName(deviceDetails.type)
            if (!driverName) continue

            def childDevice = getChildDevice("thinqconnect:${deviceDetails.id}")
            if (childDevice == null) {
                childDevice = addChildDevice("jonozzz", driverName, "thinqconnect:${deviceDetails.id}", 1234,
                    ["name": deviceDetails.name, isComponent: false])

                // Set the first device as master for MQTT
                if (!findMasterDevice()) {
                    childDevice.updateDataValue("master", "true")
                } else {
                    childDevice.updateDataValue("master", "false")
                }
            }

            // Initialize device with current status
            getDeviceStatus(deviceDetails, childDevice)
        } catch (Exception e) {
            logger("error", "Setting up appliance ${deviceId} failed: ${e}")
        }
    }

    try {
        registerPushNotifications()
    } catch (Exception e) {
        logger("error", "Subscribing to events failed: ${e}")
    }
    try {
        ensureMqttHolder()
    } catch (Exception e) {
        logger("error", "Choosing the MQTT holder failed: ${e}")
    }
}

// A code push doesn't run updated(). The first MQTT message, daily maintenance or button
// press after one does, as a Done would.
void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logger("info", "Code version ${CODE_VERSION} (was ${state.version ?: 'none'})" + (reinit ? "; re-initializing" : ""))
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void dailyMaintenance() {
    logger("debug", "dailyMaintenance()")
    checkVersion()
    captureSecrets()
    for (Closure step in [{ refreshDevices() }, { registerPushNotifications() }, { ensureMqttHolder() }]) {
        try {
            step()
        } catch (Exception e) {
            logger("error", "Daily maintenance step failed: ${e}")
        }
    }
}

// Exactly one child holds the MQTT connection. If that child was deleted, hand the job to
// another one, or every device loses its pushes.
void ensureMqttHolder() {
    if (findMasterDevice()) return
    def holder = getChildDevices().find { it.hasCapability("Initialize") }
    if (!holder) return
    logger("info", "${holder.displayName} now holds the MQTT connection")
    holder.updateDataValue("master", "true")
    holder.initialize()
}

def getDriverName(deviceType) {
    switch (deviceType) {
        case "DEVICE_WASHTOWER":
            return "ThinQ Connect WashTower"
        case "DEVICE_WASHER":
        case "DEVICE_WASHTOWER_WASHER":
            return "ThinQ Connect Washer"
        case "DEVICE_DRYER":
        case "DEVICE_WASHTOWER_DRYER":
            return "ThinQ Connect Dryer"
        case "DEVICE_DISH_WASHER":
            return "ThinQ Connect Dishwasher"
        case "DEVICE_REFRIGERATOR":
            return "ThinQ Connect Refrigerator"
        case "DEVICE_OVEN":
            return "ThinQ Connect Oven"
        case "DEVICE_COOKTOP":
            return "ThinQ Connect Cooktop"
        case "DEVICE_MICROWAVE_OVEN":
            return "ThinQ Connect Microwave Oven"
        case "DEVICE_AIR_CONDITIONER":
            return "ThinQ Connect Air Conditioner"
        case "DEVICE_AIR_PURIFIER": 
            return "ThinQ Connect Air Purifier"
        case "DEVICE_STYLER": 
            return "ThinQ Connect Styler"
        case "DEVICE_DEHUMIDIFIER":
            return "ThinQ Connect Dehumidifier"
        default:
            return null
    }
}

def testApiConnection() {
    logger("debug", "testApiConnection()")
    
    try {
        def devices = apiGet("/devices")
        if (devices != null) {
            return [success: true, deviceCount: devices.size()]
        } else {
            return [success: false, error: describeApiError(state.lastApiError) ?: "No response from API"]
        }
    } catch (Exception e) {
        logger("error", "API connection test failed: ${e}")
        return [success: false, error: e.message]
    }
}

def getMqttServerFromApi() {
    logger("debug", "getMqttServerFromApi()")
    
    try {
        def routeData = apiGet("/route")
        if (routeData != null && routeData.mqttServer) {
            // Clean up the MQTT server URL (remove protocol prefix and extract hostname)
            def mqttServer = routeData.mqttServer.replace("mqtts://", "").split(":")[0]
            return [success: true, mqttServer: "ssl://${mqttServer}:8883"]
        } else {
            return [success: false, error: "No MQTT server in route response"]
        }
    } catch (Exception e) {
        logger("error", "Failed to get MQTT server from API: ${e}")
        return [success: false, error: e.message]
    }
}

def setupMqttClient() {
    logger("debug", "setupMqttClient()")
    
    try {
        // Step 1: Register MQTT client (returns null on success)
        def clientBody = [
            type: "MQTT",
            "service-code": "SVC202",
            "device-type": "607",
            allowExist: true
        ]
        
        def clientResult = apiPost("/client", clientBody)
        // Note: Client registration returns null/empty on success
        logger("debug", "Client registration result: ${clientResult}")
        
        // Ensure csrData has proper PEM headers
        def csr = effectiveCsr()
        def formattedCsrData = csr
        if (csr && !csr.contains("-----BEGIN")) {   // e.g. BEGIN NEW CERTIFICATE REQUEST keeps its own header
            // Remove any existing whitespace/newlines
            def csrContent = csr.trim()
            // Add PEM headers
            formattedCsrData = "-----BEGIN CERTIFICATE REQUEST-----\n" + csrContent + "\n-----END CERTIFICATE REQUEST-----"
        }
        
        // Step 2: Issue certificate with CSR to get certificate and subscriptions
        def certBody = [
            "service-code": "SVC202",
            "csr": formattedCsrData
        ]
        
        def certResult = apiPost("/client/certificate", certBody)?.result
        if (certResult != null) {
            def certificate = certResult.certificatePem
            def subscriptions = certResult.subscriptions
            
            if (certificate && subscriptions) {
                return [
                    success: true, 
                    certificate: certificate,
                    subscriptions: subscriptions
                ]
            } else {
                return [success: false, error: "Missing certificate or subscriptions in response"]
            }
        } else {
            return [success: false, error: describeApiError(state.lastApiError) ?: "Failed to issue certificate"]
        }
    } catch (Exception e) {
        logger("error", "MQTT setup failed: ${e}")
        return [success: false, error: e.message]
    }
}

def getDevices() {
    logger("debug", "getDevices()")
    return apiGet("/devices")
}

def getDeviceProfile(deviceId) {
    logger("debug", "getDeviceProfile(${deviceId})")
    return apiGet("/devices/${deviceId}/profile")
}

def getDeviceStatus(deviceDetails, childDevice) {
    logger("debug", "getDeviceStatus(${deviceDetails.id})")

    def status = getDeviceState(deviceDetails.id)

    if (status != null && childDevice != null) {
        childDevice.processStateData(status)
    }
    return status
}

// Every status read goes through here (the drivers' refresh too). An appliance LG reports as
// not connected (1222) is remembered; when it answers again its event subscription is renewed,
// since one requested while it was offline may not have taken (seen 2026-11-02). One state key
// per appliance, so two refreshes at once don't overwrite each other's flag.
def getDeviceState(deviceId) {
    String id = deviceId.toString()
    Map r = apiRequest("GET", "/devices/${id}/state")
    String flag = "offline_${id}"
    if (!r.ok && r.error?.code == "1222") {
        if (!state[flag]) state[flag] = now()
    } else if (r.ok && state[flag]) {
        state.remove(flag)
        logger("info", "Appliance ${id} is back online; renewing its event subscription")
        subscribeEvents(id)
    }
    return r.data
}

void subscribeEvents(String deviceId) {
    apiRequest("POST", "/event/${deviceId}/subscribe", [expire: [unit: "HOUR", timer: 4464]])
}

def getEnergyProfile(deviceId) {
    logger("debug", "getEnergyProfile(${deviceId})")
    return apiGet("/devices/energy/${deviceId}/profile")
}

// period: DAILY (dates yyyyMMdd) or MONTHLY (dates yyyyMM). Usage is in Wh.
def getEnergyUsage(deviceId, String property, String period, String startDate, String endDate) {
    logger("debug", "getEnergyUsage(${deviceId}, ${property}, ${period}, ${startDate}, ${endDate})")
    return apiGet("/devices/energy/${deviceId}/usage?property=${property}&period=${period}&startDate=${startDate}&endDate=${endDate}")
}

def registerPushNotifications() {
    logger("debug", "registerPushNotifications()")
    
    for (deviceId in selectedDevices) {
        try {
            //apiPost("/push/${deviceId}/subscribe", [:])
            subscribeEvents(deviceId.toString())
        } catch (Exception e) {
            logger("warn", "Failed to register notifications for device ${deviceId}: ${e}")
        }
    }
}

def refreshDevices() {
    logger("debug", "refreshDevices()")
    
    for (deviceId in selectedDevices) {
        try {
            def deviceDetails = state.foundDevices.find { it.id == deviceId }
            def childDevice = getChildDevice("thinqconnect:${deviceId}")
            if (deviceDetails && childDevice) {
                getDeviceStatus(deviceDetails, childDevice)
            }
        } catch (Exception e) {
            logger("error", "Refreshing appliance ${deviceId} failed: ${e}")
        }
    }
}

// One request, returning its own outcome ([ok, data, error]) so concurrent callers (each child's
// refresh goes through here) can't read each other's error; state.lastApiError only feeds the
// setup pages. Locals, not the script binding, hold the result.
Map apiRequest(String method, String endpoint, Map body = null, Map extraHeaders = null) {
    logger("debug", "api${method}(${endpoint}${body != null ? ', ' + body : ''})")
    Map headers = getApiHeaders() + (extraHeaders ?: [:])
    Map params = [uri: "${state.apiBase}${endpoint}".toString(), headers: headers, requestContentType: "application/json", timeout: 30]
    if (body != null) params.body = body
    Map out = [ok: false, data: null, error: null]
    try {
        Closure handle = { resp ->
            if (resp.status == 200) {
                out.ok = true
                out.data = resp.data?.response
            } else {
                out.error = [status: resp.status, text: "HTTP ${resp.status}"]
            }
        }
        if (method == "GET") httpGet(params, handle)
        else if (method == "POST") httpPost(params, handle)
        else if (method == "DELETE") httpDelete(params, handle)
        logger("debug", "api${method}(${endpoint}) -> ${out.ok ? out.data : describeApiError(out.error)}")
    } catch (Exception e) {
        out.error = recordApiError("${method} ${endpoint}", e)
    }
    if (out.ok) state.remove("lastApiError")
    return out
}

def apiGet(endpoint) {
    return apiRequest("GET", endpoint.toString()).data
}

def apiPost(endpoint, body) {
    return apiRequest("POST", endpoint.toString(), body as Map).data
}

// LG puts its reason in the body ({"error": {"code": "1222", "message": "..."}}) whatever the
// HTTP status, so keep it: the status alone (416, 400) doesn't say what went wrong.
Map recordApiError(String what, Exception e) {
    def status = null
    def body = null
    try {
        status = e.response?.status
        body = e.response?.data
    } catch (ignored) { }
    def err = (body instanceof Map) ? body.error : null
    state.lastApiError = [status: status, code: err?.code?.toString(), message: err?.message?.toString(), text: e.message]
    // 1207 = already subscribed: the daily renewal hits it, and it means the subscription holds
    logger(state.lastApiError.code == "1207" ? "debug" : "warn", "API ${what} failed: ${describeApiError(state.lastApiError)}")
    return state.lastApiError
}

String describeApiError(Map err) {
    if (!err) return null
    String s = err.code ? "LG error ${err.code}${err.message ? ': ' + err.message : ''}" : (err.text ?: "HTTP ${err.status}")
    if (err.status && err.code) s += " (HTTP ${err.status})"
    String hint = err.code ? LG_ERRORS[err.code] : null
    return hint ? "${s}. ${hint}" : s
}

def getApiHeaders() {
    return [
        "Authorization": "Bearer ${patValue()}",
        "x-country": countryCode,
        "x-message-id": generateMessageId(),
        "x-client-id": state.client_id,
        "x-api-key": "v6GFvkweNo7DK7yD3ylIZ9w52aKBU0eJ7wLXkSR3",
        "x-service-phase": "OP"
    ]
}

def generateMessageId() {
    byte[] uuidBytes = UUID.randomUUID()
        .toString()
        .replaceAll(/-/, "")
        .decodeHex()
    return uuidBytes.encodeBase64()
        .toString()
        .replace("+", "-")
        .replace("/", "_")
        .replace("=", "")
}

def findMasterDevice() {
    logger("debug", "findMasterDevice()")
    return getChildDevices().find {
        it.hasCapability("Initialize") && it.getDataValue("master") == "true"
    }
}

// Devices whose appliance is no longer selected. They are kept, not deleted: rules, dashboards
// and labels may use them, and an empty selection is more likely lost settings than a request
// to delete every appliance. The device selection page lists them with a button to delete them.
List orphanedDevices() {
    Set<String> selected = (selectedDevices ?: []).collect { "thinqconnect:${it}".toString() } as Set
    return getChildDevices().findAll { !selected.contains(it.deviceNetworkId) }
}

void reportOrphanedDevices() {
    List orphans = orphanedDevices()
    if (orphans) logger("warn", "Kept devices no longer selected: ${orphans*.displayName.join(', ')}. Delete them from the device selection page.")
}

void deleteOrphanedDevices() {
    for (d in orphanedDevices()) {
        logger("info", "Deleting ${d.displayName}")
        deleteChildDevice(d.deviceNetworkId)
    }
    ensureMqttHolder()   // the holder may have been one of them
}

void appButtonHandler(String btn) {
    checkVersion()
    if (btn == "deleteOrphans") deleteOrphanedDevices()
}

def retrieveMqttDetails() {
    logger("debug", "retrieveMqttDetails()")
    
    // Ensure privateKey has proper PEM headers
    def key = effectiveKey()
    def formattedPrivateKey = key
    if (key && !key.contains("-----BEGIN")) {   // PKCS#1 (BEGIN RSA PRIVATE KEY) already has its own header
        // Remove any existing whitespace/newlines
        def keyContent = key.trim()
        // Add PEM headers
        formattedPrivateKey = "-----BEGIN PRIVATE KEY-----\n" + keyContent + "\n-----END PRIVATE KEY-----"
    }
    
    return [
        server: state.mqttServer,
        subscriptions: state.mqttSubscriptions ?: [],
        certificate: state.clientCertificate,
        privateKey: formattedPrivateKey,
        caCertificate: state.caCert,
        clientId: state.client_id
    ]
}

def processMqttMessage(dev, payload) {
    logger("debug", "processMqttMessage(${dev}, ${payload})")
    checkVersion()

    switch (payload.pushType) {
        case "DEVICE_STATUS":
            def targetDevice = getChildDevice("thinqconnect:" + payload.deviceId)
            if (targetDevice) {
                targetDevice.processStateData(payload.report)
            }
            break
        default:
            logger("debug", "Unknown MQTT message type: ${payload.pushType}")
    }
}

// A command the user runs fails loudly. LG's SDK sends x-conditional-control on every control
// call (thinq_api.async_post_device_control).
def sendDeviceCommand(deviceId, command) {
    logger("debug", "sendDeviceCommand(${deviceId}, ${command})")
    Map r = apiRequest("POST", "/devices/${deviceId}/control", command as Map, ["x-conditional-control": "true"])
    if (!r.ok) {
        def child = getChildDevice("thinqconnect:${deviceId}")
        logger("error", "Command ${command} to ${child?.displayName ?: deviceId} failed: ${describeApiError(r.error)}")
    }
    return r.data
}

// ---- Secrets ----------------------------------------------------------------------------
// The token and the MQTT private key are stored only with encrypt() in state; a typed or pasted
// value is moved there and its setting removed on the next page, save or upgrade. Values from
// earlier versions (plain settings, a plain generated key in state) migrate the same way.

void captureSecrets() {
    if (settings.patToken) {
        storeSecret("pat", "patEnc", settings.patToken.toString().trim())
        app.removeSetting("patToken")
    }
    if (settings.privateKey) {
        storeSecret("mqttKey", "mqttKeyEnc", settings.privateKey.toString())
        state.customKey = true
        app.removeSetting("privateKey")
    }
    if (state.generatedKey) {
        storeSecret("mqttKey", "mqttKeyEnc", state.generatedKey.toString())
        state.remove("generatedKey")
    }
}

void storeSecret(String name, String stateKey, String plain) {
    String enc = encrypt(plain)
    state[stateKey] = enc
    SECRET_CACHE.put("${app.id}:${name}".toString(), [enc, plain])
}

String secretValue(String name, String stateKey) {
    // An install upgraded by a code push alone still has its secrets in plain settings or state:
    // move them before the first read instead of waiting for a page or a save.
    if (!state[stateKey] && (settings.patToken || settings.privateKey || state.generatedKey)) captureSecrets()
    String enc = state[stateKey] as String
    String key = "${app.id}:${name}".toString()
    if (!enc) { SECRET_CACHE.remove(key); return null }
    List<String> hit = SECRET_CACHE.get(key)
    if (hit != null && hit[0] == enc) return hit[1]
    try {
        String plain = decrypt(enc)
        SECRET_CACHE.put(key, [enc, plain])
        return plain
    } catch (Exception e) {
        SECRET_CACHE.remove(key)
        logger("warn", "Could not decrypt the stored ${name == 'pat' ? 'token' : 'MQTT key'} (${e.message}); enter it again")
        return null
    }
}

String patValue() { return secretValue("pat", "patEnc") }

// Bullets and the last 4 characters, enough to match it against LG's token list.
String maskToken(String t) {
    if (!t) return null
    return "••••" + (t.length() > 4 ? t.substring(t.length() - 4) : "")
}

// ---- MQTT client credentials, made on the hub ----------------------------------------
// A 2048-bit RSA key and a PKCS#10 certificate request signed with SHA256withRSA, with the
// subject from csrconfig.txt: what the README's openssl recipe makes. The request is
// DER-encoded by hand, since the JDK has no public CSR builder.

Map generateMqttCredentials() {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA")
    g.initialize(2048)
    KeyPair kp = g.generateKeyPair()

    // Subject as in the repo's csrconfig.txt
    List<byte[]> rdns = [
        derRdn("2.5.4.3", "AWS IoT Certificate", 0x0C),  // CN, UTF8String
        derRdn("2.5.4.6", "US", 0x13),                   // C, PrintableString
        derRdn("2.5.4.8", "State", 0x0C),                // ST
        derRdn("2.5.4.7", "Locality", 0x0C),             // L
        derRdn("2.5.4.10", "Amazon", 0x0C)               // O
    ]
    byte[] info = derSeq([
        derTlv(0x02, [0] as byte[]),                     // version 0
        derSeq(rdns),                                    // subject
        kp.getPublic().getEncoded(),                     // SubjectPublicKeyInfo (X.509)
        derTlv(0xA0, new byte[0])                        // attributes [0], empty
    ])
    Signature s = Signature.getInstance("SHA256withRSA")
    s.initSign(kp.getPrivate())
    s.update(info)
    byte[] sig = s.sign()
    byte[] algId = derSeq([derOid("1.2.840.113549.1.1.11"), derTlv(0x05, new byte[0])])
    byte[] sigBits = new byte[sig.length + 1]
    for (int i = 0; i < sig.length; i++) sigBits[i + 1] = sig[i]  // leading 0: no unused bits
    byte[] csr = derSeq([info, algId, derTlv(0x03, sigBits)])

    return [csr: toPem("CERTIFICATE REQUEST", csr),
            privateKey: toPem("PRIVATE KEY", kp.getPrivate().getEncoded())]  // PKCS#8
}

String toPem(String label, byte[] der) {
    String b64 = der.encodeBase64().toString()
    StringBuilder sb = new StringBuilder("-----BEGIN ${label}-----\n")
    for (int i = 0; i < b64.length(); i += 64) sb.append(b64.substring(i, Math.min(i + 64, b64.length()))).append("\n")
    sb.append("-----END ${label}-----")
    return sb.toString()
}

byte[] derRdn(String oid, String value, int stringTag) {
    return derTlv(0x31, derSeq([derOid(oid), derTlv(stringTag, value.getBytes("UTF-8"))]))
}

byte[] derSeq(List<byte[]> parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    parts.each { out.write(it, 0, it.length) }
    return derTlv(0x30, out.toByteArray())
}

byte[] derOid(String dotted) {
    List<Integer> arcs = dotted.tokenize(".").collect { it as Integer }
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    out.write(arcs[0] * 40 + arcs[1])
    arcs.drop(2).each { int v ->
        List<Integer> b = [v & 0x7F]
        v = v >> 7
        while (v > 0) { b.add(0, (v & 0x7F) | 0x80); v = v >> 7 }
        b.each { out.write(it) }
    }
    return derTlv(0x06, out.toByteArray())
}

byte[] derTlv(int tag, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    out.write(tag)
    int n = value.length
    if (n < 0x80) {
        out.write(n)
    } else {
        List<Integer> len = []
        while (n > 0) { len.add(0, n & 0xFF); n = n >> 8 }
        out.write(0x80 | len.size())
        len.each { out.write(it) }
    }
    out.write(value, 0, value.length)
    return out.toByteArray()
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
            log."${level}" "${app.name} ${msg}"
        }
    }
}

def calculateMD5(String input) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    byte[] digest = md.digest(input.bytes)
    digest.collect { String.format("%02x", it) }.join()
}
