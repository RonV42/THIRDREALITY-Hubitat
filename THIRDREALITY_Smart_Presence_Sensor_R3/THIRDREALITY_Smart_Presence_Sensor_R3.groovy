/**
 *  THIRDREALITY Smart Presence Sensor R3 (3RPL01084Z)
 *
 *  v0.2 - Added device health watchdog (healthStatus attribute, deviceHealthCheck(),
 *          initialize()), fixed namespace/author, stamped lastDeviceActivityMs on
 *          every successful parse so the watchdog has a real heartbeat to track.
 *
 *  v0.3 - Fixed syntheticMotionClear() — device unoccupied report now starts the
 *          motionClearSeconds countdown rather than being ignored or passing through
 *          immediately. Occupied report cancels any pending timer.
 *        - Fixed AirQualityIndex unit — now reports as "ppb".
 *        - Added lastSeen attribute — timestamp updated on every successful parse.
 *        - Fixed illuminance deadband logic — changed AND to OR on delta/time
 *          conditions so a significant lux change fires immediately regardless of
 *          elapsed time, and a slow drift still reports after the minimum interval.
 *        - Fixed TVOC deadband logic — same AND to OR fix for consistency.
 *
 *  Combines:
 *   - RGB status light control
 *   - mmWave occupancy/presence reporting (as MotionSensor + custom occupancy attribute)
 *   - illuminance reporting
 *   - Air Quality Index reporting from cluster 0x042E
 *   - Air Quality status derived from the device instruction-sheet thresholds
 *   - Air Quality Index deadband / minimum report interval filtering
 *   - Optional driver-side adjustable motion/presence clear timeout
 *   - Air Quality Index is treated as a whole-number value, since observed reports appear integral
 *   - Device health watchdog: marks healthStatus offline if no Zigbee activity within threshold
 *
 *  Notes:
 *   - The presence side is exposed as MotionSensor because Hubitat apps and RM generally
 *     work better with active/inactive than with PresenceSensor present/not present.
 *   - Air Quality cluster 0x042E is decoded as a float/integer value and is now reported directly
 *     using the sensor's own threshold scheme from the printed instruction sheet:
 *       0-500 good, 501-1000 ventilate, 1001-3000 warning, >3000 danger.
 *   - healthStatus transitions: unknown (fresh install/reboot) -> online (first parse) ->
 *     offline (watchdog fires with no activity) -> online (activity resumes).
 *   - motionClearSeconds behavior: 0 = clear immediately on device unoccupied report.
 *     Any value > 0 = device unoccupied report starts the countdown; timer firing clears
 *     motion/occupancy. A new occupied report cancels the pending timer.
 */

import groovy.transform.Field

@Field static final Integer CLUSTER_ON_OFF       = 0x0006
@Field static final Integer CLUSTER_LEVEL        = 0x0008
@Field static final Integer CLUSTER_COLOR        = 0x0300
@Field static final Integer CLUSTER_ILLUMINANCE  = 0x0400
@Field static final Integer CLUSTER_OCCUPANCY    = 0x0406
@Field static final Integer CLUSTER_TVOC         = 0x042E

metadata {
    definition(name: "THIRDREALITY Smart Presence Sensor R3", namespace: "RonV42", author: "RonV42") {
        capability "Actuator"
        capability "Sensor"
        capability "Light"
        capability "Switch"
        capability "Switch Level"
        capability "ChangeLevel"
        capability "ColorControl"
        capability "MotionSensor"
        capability "IlluminanceMeasurement"
        capability "Refresh"
        capability "Configuration"
        capability "Initialize"

        attribute "occupancy",       "enum",   ["occupied", "clear"]
        attribute "AirQualityIndex", "number"
        attribute "AirQuality",      "enum",   ["good", "ventilate", "warning", "danger"]
        attribute "colorName",       "string"
        attribute "healthStatus",    "enum",   ["unknown", "online", "offline"]
        attribute "lastSeen",        "string"

        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0003,0004,0005,0006,0008,0012,0300,0400,0406,042E,1000",
            outClusters: "0019",
            manufacturer: "Third Reality, Inc",
            model: "3RPL01084Z",
            deviceJoinName: "THIRDREALITY Smart Presence Sensor R3"
    }

    preferences {
        input name: "levelTransitionTime", type: "enum", title: "Level transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]],
            defaultValue: 1000
        input name: "startLevelChangeRate", type: "enum", title: "Start level change rate (default: Fast)",
            options: [[25:"Slow"], [50:"Medium"], [100:"Fast"]],
            defaultValue: 100
        input name: "onTransitionTime", type: "enum", title: "On transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]],
            defaultValue: 1000
        input name: "offTransitionTime", type: "enum", title: "Off transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]],
            defaultValue: 1000
        input name: "rgbTransitionTime", type: "enum", title: "RGB transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]],
            defaultValue: 1000
        input name: "minimumLevel", type: "number", title: "Minimum level (default: 5%)",
            description: "Requested levels above 0% but below this minimum are raised to this value", defaultValue: 5, range: "0..100"
        input name: "colorStaging", type: "bool", title: "Enable color pre-staging when light is off", defaultValue: false
        input name: "hiRezHue", type: "bool", title: "Use hue in degrees (0-360) instead of percent", defaultValue: false
        input name: "autoRefreshMinutes", type: "enum", title: "Automatic refresh interval",
            options: [[0:"Disabled"], [1:"Every 1 minute"], [5:"Every 5 minutes"], [10:"Every 10 minutes"], [15:"Every 15 minutes"], [30:"Every 30 minutes"]],
            defaultValue: 0
        input name: "illuminanceMinDeltaLux", type: "number", title: "Illuminance deadband (Lux)",
            description: "Report if lux changes by at least this amount OR the minimum interval has elapsed", defaultValue: 3, range: "0..1000"
        input name: "illuminanceMinSeconds", type: "number", title: "Minimum seconds between illuminance reports",
            description: "Report if minimum interval has elapsed OR lux changed by at least the deadband amount", defaultValue: 30, range: "0..3600"
        input name: "tvocMinDelta", type: "number", title: "Air Quality Index deadband",
            description: "Report if AQI changes by at least this amount OR the minimum interval has elapsed (band change always reports)", defaultValue: 2, range: "0..10000"
        input name: "tvocMinSeconds", type: "number", title: "Minimum seconds between Air Quality Index reports",
            description: "Report if minimum interval has elapsed OR AQI changed by at least the deadband amount (band change always reports)", defaultValue: 30, range: "0..3600"
        input name: "motionClearSeconds", type: "number", title: "Motion/presence clear delay (seconds)",
            description: "0 = clear immediately on device unoccupied report. Any value > 0 = device unoccupied report starts this countdown before clearing.", defaultValue: 0, range: "0..3600"
        input name: "healthCheckMinutes", type: "number", title: "Health watchdog timeout (minutes)",
            description: "Mark device offline if no Zigbee activity is received within this window. 0 = disabled.", defaultValue: 15, range: "0..60"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

// ── Lifecycle ─────────────────────────────────────────────────────────────────

def installed() {
    log.info "installed..."
    sendEvent(name: "motion",       value: "inactive")
    sendEvent(name: "occupancy",    value: "clear")
    sendEvent(name: "healthStatus", value: "unknown")
    sendEvent(name: "lastSeen",     value: "never")
    unschedule("syntheticMotionClear")
    scheduleAutoRefresh()
    scheduleHealthCheck()
}

def initialize() {
    log.info "initialize..."
    sendEvent(name: "motion",       value: "inactive")
    sendEvent(name: "occupancy",    value: "clear")
    sendEvent(name: "healthStatus", value: "unknown")
    sendEvent(name: "lastSeen",     value: "never")
    state.lastDeviceActivityMs = null
    unschedule()
    scheduleAutoRefresh()
    scheduleHealthCheck()
    configure()
}

def updated() {
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    log.warn "motion/presence clear delay is: ${safeToInt(settings.motionClearSeconds, 0)} second(s)"
    log.warn "health watchdog timeout is: ${safeToInt(settings.healthCheckMinutes, 15)} minute(s)"
    if (logEnable) runIn(1800, "logsOff")
    if (safeToInt(settings.motionClearSeconds, 0) <= 0) unschedule("syntheticMotionClear")
    scheduleAutoRefresh()
    scheduleHealthCheck()
}

def logsOff() {
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ── Scheduling helpers ────────────────────────────────────────────────────────

private void scheduleAutoRefresh() {
    unschedule("refresh")
    Integer mins = safeToInt(settings.autoRefreshMinutes, 0)
    switch (mins) {
        case 1:  runEvery1Minute("refresh");   break
        case 5:  runEvery5Minutes("refresh");  break
        case 10: runEvery10Minutes("refresh"); break
        case 15: runEvery15Minutes("refresh"); break
        case 30: runEvery30Minutes("refresh"); break
        default: break
    }
}

private void scheduleHealthCheck() {
    unschedule("deviceHealthCheck")
    Integer mins = safeToInt(settings.healthCheckMinutes, 15)
    if (mins <= 0) {
        if (logEnable) log.debug "Health watchdog disabled"
        return
    }
    runIn(mins * 60, "deviceHealthCheck")
    if (logEnable) log.debug "Health watchdog armed for ${mins} minute(s)"
}

// ── Health watchdog ───────────────────────────────────────────────────────────

def deviceHealthCheck() {
    Integer mins = safeToInt(settings.healthCheckMinutes, 15)
    if (mins <= 0) return

    Long lastActivityMs = state.lastDeviceActivityMs != null ? (state.lastDeviceActivityMs as Long) : 0L
    Long thresholdMs    = mins * 60000L
    Long elapsedMs      = lastActivityMs > 0L ? (now() - lastActivityMs) : Long.MAX_VALUE

    if (elapsedMs >= thresholdMs) {
        if (device.currentValue("healthStatus") != "offline") {
            log.warn "${device.displayName} health watchdog: no Zigbee activity for ${mins} minute(s) — marking offline"
            sendEvent(name: "healthStatus", value: "offline",
                      descriptionText: "${device.displayName} is offline (no activity for ${mins} min)")
        }
        if (logEnable) log.debug "Health watchdog firing targeted poke refresh"
        sendHubCommand(new hubitat.device.HubMultiAction(
            [readAttrCmd(CLUSTER_OCCUPANCY, 0x0000), "delay 500", readAttrCmd(CLUSTER_TVOC, 0x0000)],
            hubitat.device.Protocol.ZIGBEE
        ))
    }

    // Rearm regardless — if poke comes back, parse() will flip us online
    runIn(mins * 60, "deviceHealthCheck")
}

// ── Parse ─────────────────────────────────────────────────────────────────────

def parse(String description) {
    if (logEnable) log.debug "parse description: ${description}"
    if (!description || description.startsWith("catchall")) return

    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (logEnable) log.debug "descMap: ${descMap}"
    if (!descMap?.clusterInt) return

    // Heartbeat — any valid cluster parse counts as device activity
    state.lastDeviceActivityMs = now()
    String nowStr = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    sendEventIfChanged("lastSeen", nowStr, null, null)
    if (device.currentValue("healthStatus") != "online") {
        sendEventIfChanged("healthStatus", "online", "${device.displayName} is online", null)
    }
    scheduleHealthCheck()

    String descriptionText
    String name
    Object value
    String unit

    switch (descMap.clusterInt as Integer) {
        case CLUSTER_ON_OFF:
            if (descMap.attrInt == 0 && descMap.value != null) {
                value = hexToInt(descMap.value, 0) == 1 ? "on" : "off"
                name = "switch"
                descriptionText = (device.currentValue(name) == value) ?
                    "${device.displayName} is ${value}" :
                    "${device.displayName} was turned ${value}"
            }
            break

        case CLUSTER_LEVEL:
            if (descMap.attrInt == 0 && descMap.value != null) {
                value = Math.round(hexToInt(descMap.value, 0) * 100 / 254.0)
                if ((value as Integer) > 0) state.lastNonZeroLevel = (value as Integer)
                unit = "%"
                name = "level"
                Integer current = safeToInt(device.currentValue(name), -1)
                descriptionText = (current == (value as Integer)) ?
                    "${device.displayName} is ${value}${unit}" :
                    "${device.displayName} was set to ${value}${unit}"
            }
            break

        case CLUSTER_COLOR:
            return parseColorCluster(descMap)

        case CLUSTER_ILLUMINANCE:
            if (descMap.attrInt == 0 && descMap.value != null) {
                Integer raw = hexToInt(descMap.value, 0)
                Integer lux = raw > 0 ? Math.round(Math.pow(10, ((raw - 1) / 10000.0d))) : 0
                handleIlluminanceReport(lux)
            }
            return

        case CLUSTER_OCCUPANCY:
            if (descMap.attrInt == 0 && descMap.value != null) {
                Integer raw = hexToInt(descMap.value, 0)
                Boolean occupied = (raw & 0x01) == 0x01
                handleOccupancyReport(occupied)
            }
            return

        case CLUSTER_TVOC:
            if (descMap.attrInt == 0 && descMap.value != null) {
                BigDecimal tvocValue = parseTvocValue(descMap)
                String rawString = "enc=${descMap.encoding ?: '??'} hex=${descMap.value}"
                if (logEnable) log.debug buildTvocDebugLine(descMap, tvocValue)
                if (tvocValue != null) {
                    Integer roundedValue = tvocValue.setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()
                    handleTvocReport(roundedValue)
                } else if (logEnable) {
                    log.debug "Unable to parse Air Quality Index value from ${rawString}"
                }
            }
            break

        default:
            if (logEnable) log.debug "ignoring ${descMap.clusterId}:${descMap.attrId}"
            return
    }

    if (descriptionText && name) {
        sendEventIfChanged(name, value, descriptionText, unit)
    }
}

// ── Color cluster ─────────────────────────────────────────────────────────────

private Object parseColorCluster(Map descMap) {
    String descriptionText
    String name
    Object value
    String unit
    Integer rawValue = hexToInt(descMap.value, 0)

    switch (descMap.attrInt as Integer) {
        case 0: // hue
            if (hiRezHue) {
                value = Math.round(rawValue * 360 / 254)
                if ((value as Integer) == 361) value = 360
                unit = "°"
            } else {
                value = Math.round(rawValue / 254 * 100)
                unit = "%"
            }
            name = "hue"
            state.lastHue = descMap.value
            descriptionText = (safeToInt(device.currentValue(name), -999) == (value as Integer)) ?
                "${device.displayName} ${name} is ${value}${unit}" :
                "${device.displayName} ${name} was set to ${value}${unit}"
            break

        case 1: // saturation
            value = Math.round(rawValue / 254 * 100)
            unit = "%"
            name = "saturation"
            state.lastSaturation = descMap.value
            descriptionText = (safeToInt(device.currentValue(name), -999) == (value as Integer)) ?
                "${device.displayName} ${name} is ${value}${unit}" :
                "${device.displayName} ${name} was set to ${value}${unit}"
            break

        case 8: // color mode
            value = rawValue == 2 ? "CT" : "RGB"
            name = "colorMode"
            descriptionText = "${device.displayName} ${name} is ${value}"
            break

        default:
            if (logEnable) log.debug "0x0300:${descMap.attrId}:${rawValue}"
            return
    }

    sendEventIfChanged(name, value, descriptionText, unit)
    if (name in ["hue", "saturation"]) {
        setGenericColorName()
    }
}

// ── Occupancy ─────────────────────────────────────────────────────────────────

private void handleOccupancyReport(Boolean occupied) {
    Integer clearSeconds = Math.max(0, safeToInt(settings.motionClearSeconds, 0))

    if (occupied) {
        // Cancel any pending clear timer — room is occupied again
        unschedule("syntheticMotionClear")
        sendEventIfChanged("motion", "active", "${device.displayName} motion is active", null)
        sendEventIfChanged("occupancy", "occupied", "${device.displayName} occupancy is occupied", null)
        if (logEnable) log.debug "Occupancy active — cleared any pending motion clear timer"
    } else {
        // Device reports unoccupied
        if (clearSeconds > 0) {
            // Start the countdown from this moment — do NOT clear yet
            runIn(clearSeconds, "syntheticMotionClear")
            if (logEnable) log.debug "Device unoccupied — starting ${clearSeconds}s clear countdown"
        } else {
            // No delay configured — pass device clear through immediately
            sendEventIfChanged("motion", "inactive", "${device.displayName} motion is inactive", null)
            sendEventIfChanged("occupancy", "clear", "${device.displayName} occupancy is clear", null)
        }
    }
}

def syntheticMotionClear() {
    // Timer fired — clear motion and occupancy unconditionally.
    // A new occupied report would have cancelled this timer before it could fire.
    Integer clearSeconds = Math.max(0, safeToInt(settings.motionClearSeconds, 0))
    sendEventIfChanged("motion", "inactive", "${device.displayName} motion is inactive", null)
    sendEventIfChanged("occupancy", "clear", "${device.displayName} occupancy is clear", null)
    if (txtEnable) log.info "${device.displayName} motion/presence cleared after ${clearSeconds}s delay"
}

// ── TVOC ──────────────────────────────────────────────────────────────────────

private void handleTvocReport(Integer tvoc) {
    Integer minDelta   = Math.max(0, safeToInt(settings.tvocMinDelta, 2))
    Integer minSeconds = Math.max(0, safeToInt(settings.tvocMinSeconds, 30))
    Long nowMs         = now()
    Integer lastReportedTvoc = state.lastReportedTvoc != null ? safeToInt(state.lastReportedTvoc, tvoc) : null
    Long lastReportMs        = state.lastTvocReportMs != null ? (state.lastTvocReportMs as Long) : 0L
    String status        = classifyTvoc(tvoc)
    String currentStatus = device.currentValue("AirQuality")?.toString()

    Boolean shouldSendValue = false
    if (lastReportedTvoc == null) {
        // First report ever — always send
        shouldSendValue = true
    } else if (currentStatus != status) {
        // Band change — always send regardless of delta or time
        shouldSendValue = true
    } else {
        Integer delta  = Math.abs(tvoc - lastReportedTvoc)
        Long elapsedMs = nowMs - lastReportMs
        // OR logic: significant change fires immediately; minimum interval fires regardless of delta
        if (delta >= minDelta || elapsedMs >= (minSeconds * 1000L)) {
            shouldSendValue = true
        }
    }

    sendEventIfChanged("AirQuality", status, "${device.displayName} Air Quality is ${status}", null)

    if (shouldSendValue) {
        state.lastReportedTvoc = tvoc
        state.lastTvocReportMs = nowMs
        sendEventIfChanged("AirQualityIndex", tvoc, "${device.displayName} Air Quality Index is ${tvoc} ppb (${status})", "ppb")
    } else if (logEnable) {
        log.debug "Filtered Air Quality Index report: ${tvoc} ppb (${status})"
    }
}

// ── Illuminance ───────────────────────────────────────────────────────────────

private void handleIlluminanceReport(Integer lux) {
    Integer minDelta   = Math.max(0, safeToInt(settings.illuminanceMinDeltaLux, 3))
    Integer minSeconds = Math.max(0, safeToInt(settings.illuminanceMinSeconds, 30))
    Long nowMs         = now()
    Integer lastReportedLux = state.lastReportedIlluminanceLux != null ? safeToInt(state.lastReportedIlluminanceLux, lux) : null
    Long lastReportMs       = state.lastIlluminanceReportMs != null ? (state.lastIlluminanceReportMs as Long) : 0L

    Boolean shouldSend = false
    if (lastReportedLux == null) {
        // First report ever — always send
        shouldSend = true
    } else {
        Integer delta  = Math.abs(lux - lastReportedLux)
        Long elapsedMs = nowMs - lastReportMs
        // OR logic: significant change fires immediately; minimum interval fires regardless of delta
        if (delta >= minDelta || elapsedMs >= (minSeconds * 1000L)) {
            shouldSend = true
        }
    }

    if (shouldSend) {
        state.lastReportedIlluminanceLux = lux
        state.lastIlluminanceReportMs    = nowMs
        sendEventIfChanged("illuminance", lux, "${device.displayName} illuminance is ${lux} Lux", "Lux")
    } else if (logEnable) {
        log.debug "Filtered illuminance report: ${lux} Lux"
    }
}

// ── TVOC decode helpers ───────────────────────────────────────────────────────

private String buildTvocDebugLine(Map descMap, BigDecimal tvocValue) {
    String hex       = (descMap?.value ?: "").toString()
    Integer encoding = hexToInt(descMap?.encoding, -1)
    Float fBig       = decodeFloatBigEndian(hex)
    Float fLittle    = decodeFloatLittleEndian(hex)
    Long unsignedVal = null
    Long signedVal   = null
    try { unsignedVal = parseUnsignedHex(hex) } catch (ignored) { }
    try { signedVal   = parseSignedHex(hex, Math.max(1, (hex?.length() ?: 0) / 2)) } catch (ignored) { }
    String status = tvocValue != null ? classifyTvoc(tvocValue.setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()) : null
    return "Air Quality Index 0x042E debug: enc=${String.format('0x%02X', encoding)} hex=${hex} unsigned=${unsignedVal} signed=${signedVal} floatBE=${fBig} floatLE=${fLittle} decoded=${tvocValue} status=${status}"
}

private BigDecimal parseTvocValue(Map descMap) {
    Integer encoding = hexToInt(descMap.encoding, -1)
    String hex       = descMap.value ?: ""
    if (!hex) return null

    switch (encoding) {
        case 0x39: // single-precision float; likely ppm
            Float f1     = decodeFloatBigEndian(hex)
            Float f2     = decodeFloatLittleEndian(hex)
            Float chosen = chooseReasonableFloat(f1, f2)
            if (chosen == null) return null
            return BigDecimal.valueOf(chosen as Double)

        case 0x20: // uint8
        case 0x21: // uint16
        case 0x22: // uint24
        case 0x23: // uint32
            return BigDecimal.valueOf(parseUnsignedHex(hex))

        case 0x28: // int8
        case 0x29: // int16
        case 0x2A: // int24
        case 0x2B: // int32
            return BigDecimal.valueOf(parseSignedHex(hex, hex.length() / 2))

        default:
            try {
                return BigDecimal.valueOf(parseUnsignedHex(hex))
            } catch (ignored) {
                return null
            }
    }
}

private static String classifyTvoc(Integer value) {
    if (value == null) return null
    if (value <= 500)  return "good"
    if (value <= 1000) return "ventilate"
    if (value <= 3000) return "warning"
    return "danger"
}

private static Float chooseReasonableFloat(Float a, Float b) {
    List<Float> candidates = [a, b].findAll { it != null && !it.isNaN() && !it.isInfinite() && it >= 0.0f && it < 10000.0f }
    if (!candidates) return null
    // Prefer a practical non-subnormal value; cluster 0x042E reports on this device are arriving as
    // IEEE-754 values like 41.0 / 42.0 / 60.0, while the opposite endianness decodes to tiny near-zero noise.
    List<Float> practical = candidates.findAll { it >= 0.001f }
    if (practical) return practical.max()
    return candidates.max()
}

private static Float decodeFloatBigEndian(String hex) {
    try {
        int bits = (int) Long.parseLong(hex, 16)
        return Float.intBitsToFloat(bits)
    } catch (ignored) { return null }
}

private static Float decodeFloatLittleEndian(String hex) {
    try {
        int bits = (int) Long.parseLong(hex, 16)
        return Float.intBitsToFloat(Integer.reverseBytes(bits))
    } catch (ignored) { return null }
}

private static Long parseUnsignedHex(String hex) {
    return Long.parseLong(hex, 16)
}

private static Long parseSignedHex(String hex, Integer bytes) {
    long unsigned  = Long.parseLong(hex, 16)
    long signBit   = 1L << ((bytes * 8) - 1)
    long fullRange = 1L << (bytes * 8)
    return (unsigned & signBit) ? (unsigned - fullRange) : unsigned
}

// ── Color name ────────────────────────────────────────────────────────────────

private void setGenericColorName() {
    Integer hue = safeToInt(device.currentValue("hue"), 0)
    Integer sat = safeToInt(device.currentValue("saturation"), 100)
    if (!hiRezHue) hue = Math.round(hue * 3.6)
    String colorName

    switch (hue) {
        case 0..15:    colorName = "Red";        break
        case 16..45:   colorName = "Orange";     break
        case 46..75:   colorName = "Yellow";     break
        case 76..105:  colorName = "Chartreuse"; break
        case 106..135: colorName = "Green";      break
        case 136..165: colorName = "Spring";     break
        case 166..195: colorName = "Cyan";       break
        case 196..225: colorName = "Azure";      break
        case 226..255: colorName = "Blue";       break
        case 256..285: colorName = "Violet";     break
        case 286..315: colorName = "Magenta";    break
        case 316..345: colorName = "Rose";       break
        default:       colorName = "Red";        break
    }
    if (sat == 0) colorName = "White"
    sendEventIfChanged("colorName", colorName, "${device.displayName} color is ${colorName}", null)
}

// ── Light control commands ────────────────────────────────────────────────────

def on() {
    if (logEnable) log.debug "on()"
    Integer transitionMs = getConfiguredTransitionMs("onTransitionTime", 1000)
    Integer targetLevel  = resolveOnLevelPercent()
    Integer zigbeeLevel  = scalePercentToZigbeeLevel(targetLevel)
    Integer transition   = transitionMsToTenths(transitionMs)
    Integer delayMs      = Math.max(400, transitionMs + 400)

    return [
        "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(transition)}}",
        "delay ${delayMs}",
        readAttrCmd(CLUSTER_ON_OFF, 0x0000),
        "delay 200",
        readAttrCmd(CLUSTER_LEVEL, 0x0000)
    ]
}

def off() {
    if (logEnable) log.debug "off()"
    Integer transitionMs = getConfiguredTransitionMs("offTransitionTime", 1000)
    Integer transition   = transitionMsToTenths(transitionMs)
    Integer delayMs      = Math.max(400, transitionMs + 400)

    return [
        "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x00 0x${intTo16bitUnsignedHexLE(transition)}}",
        "delay ${delayMs}",
        readAttrCmd(CLUSTER_ON_OFF, 0x0000),
        "delay 200",
        readAttrCmd(CLUSTER_LEVEL, 0x0000)
    ]
}

def startLevelChange(direction) {
    if (logEnable) log.debug "startLevelChange(${direction})"
    Integer upDown         = direction == "down" ? 1 : 0
    Integer unitsPerSecond = Math.max(1, safeToInt(settings.startLevelChangeRate, 100))
    return "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 1 { 0x${intTo8bitUnsignedHex(upDown)} 0x${intTo16bitUnsignedHexLE(unitsPerSecond)} }"
}

def stopLevelChange() {
    if (logEnable) log.debug "stopLevelChange()"
    return [
        "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 3 {}",
        "delay 200",
        readAttrCmd(CLUSTER_LEVEL, 0x0000)
    ]
}

def setLevel(value) {
    if (logEnable) log.debug "setLevel(${value})"
    return setLevel(value, getConfiguredTransitionSeconds("levelTransitionTime", 1.0G))
}

def setLevel(value, rate) {
    if (logEnable) log.debug "setLevel(${value}, ${rate})"
    Integer requestedLevel    = Math.max(0, Math.min(100, safeToInt(value, 0)))
    Integer level             = clampLevelPercent(requestedLevel)
    BigDecimal defaultRateSeconds = getConfiguredTransitionSeconds("levelTransitionTime", 1.0G)
    BigDecimal rateSeconds    = hasMeaningfulValue(rate) ? safeToBigDecimal(rate, defaultRateSeconds) : defaultRateSeconds
    if (rateSeconds <= 0) rateSeconds = defaultRateSeconds > 0 ? defaultRateSeconds : 1.0G
    Integer scaledRate  = Math.max(1, (rateSeconds * 10).toInteger())
    Integer zigbeeLevel = scalePercentToZigbeeLevel(level)
    Boolean isOn        = device.currentValue("switch") == "on"
    Integer delayMs     = Math.max(400, (rateSeconds * 1000).toInteger() + 400)

    if (level > 0) state.lastNonZeroLevel = level

    if (isOn) {
        return [
            "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(scaledRate)}}",
            "delay ${delayMs}",
            readAttrCmd(CLUSTER_LEVEL, 0x0000)
        ]
    } else {
        return [
            "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(scaledRate)}}",
            "delay ${delayMs}",
            readAttrCmd(CLUSTER_ON_OFF, 0x0000),
            "delay 200",
            readAttrCmd(CLUSTER_LEVEL, 0x0000)
        ]
    }
}

def setColor(Map value) {
    if (logEnable) log.debug "setColor(${value})"
    if (value?.hue == null || value?.saturation == null) return

    Integer hueInput   = safeToInt(value.hue, 0)
    Integer satInput   = safeToInt(value.saturation, 100)
    Integer levelInput = value.level != null ? clampLevelPercent(safeToInt(value.level, 100)) : null
    Integer requestedRateSeconds = value.rate != null ? safeToInt(value.rate, 0) : 0
    Integer rateMs     = requestedRateSeconds > 0 ? (requestedRateSeconds * 1000) : getConfiguredTransitionMs("rgbTransitionTime", 1000)
    Boolean isOn       = device.currentValue("switch") == "on"

    String hexHue = hiRezHue ?
        zigbee.convertToHexString(Math.round(hueInput / 360.0 * 254).toInteger(), 2) :
        zigbee.convertToHexString(Math.round(hueInput / 100.0 * 254).toInteger(), 2)
    String hexSat = zigbee.convertToHexString(Math.round(satInput / 100.0 * 254).toInteger(), 2)

    List<String> cmds = []
    Integer transition  = transitionMsToTenths(rateMs)
    Integer zigbeeLevel = levelInput != null ? scalePercentToZigbeeLevel(levelInput) : null

    if (levelInput != null && levelInput > 0) state.lastNonZeroLevel = levelInput

    if (isOn) {
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x06 {${hexHue} ${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"
        if (zigbeeLevel != null) {
            cmds << "delay 200"
            cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(transition)}}"
            cmds << "delay ${rateMs + 400}"
            cmds << readAttrCmd(CLUSTER_COLOR, 0x0000)
            cmds << "delay 200"
            cmds << readAttrCmd(CLUSTER_COLOR, 0x0001)
            cmds << "delay 200"
            cmds << readAttrCmd(CLUSTER_LEVEL, 0x0000)
        } else {
            cmds << "delay ${rateMs + 400}"
            cmds << readAttrCmd(CLUSTER_COLOR, 0x0000)
            cmds << "delay 200"
            cmds << readAttrCmd(CLUSTER_COLOR, 0x0001)
        }
    } else if (colorStaging) {
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x06 {${hexHue} ${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"
        cmds << "delay ${Math.max(200, rateMs + 200)}"
        cmds << readAttrCmd(CLUSTER_COLOR, 0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, 0x0001)
    } else if (zigbeeLevel != null) {
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x06 {${hexHue} ${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"
        cmds << "delay 200"
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(transition)}}"
        cmds << "delay ${rateMs + 400}"
        cmds << readAttrCmd(CLUSTER_ON_OFF, 0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_LEVEL, 0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, 0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, 0x0001)
    } else {
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x06 {${hexHue} ${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"
        cmds << "delay 200"
        cmds << on()
        cmds = cmds.flatten()
    }

    state.lastHue        = hexHue
    state.lastSaturation = hexSat
    return cmds
}

def setHue(value) {
    if (logEnable) log.debug "setHue(${value})"
    setColor([hue: value, saturation: safeToInt(device.currentValue("saturation"), 100), level: safeToInt(device.currentValue("level"), 100)])
}

def setSaturation(value) {
    if (logEnable) log.debug "setSaturation(${value})"
    setColor([hue: safeToInt(device.currentValue("hue"), 0), saturation: value, level: safeToInt(device.currentValue("level"), 100)])
}

// ── Refresh / Configure ───────────────────────────────────────────────────────

def refresh() {
    if (logEnable) log.debug "refresh()"
    return [
        readAttrCmd(CLUSTER_ON_OFF,      0x0000), "delay 200",
        readAttrCmd(CLUSTER_LEVEL,       0x0000), "delay 200",
        readAttrCmd(CLUSTER_COLOR,       0x0000), "delay 200",
        readAttrCmd(CLUSTER_COLOR,       0x0001), "delay 200",
        readAttrCmd(CLUSTER_ILLUMINANCE, 0x0000), "delay 200",
        readAttrCmd(CLUSTER_OCCUPANCY,   0x0000), "delay 200",
        readAttrCmd(CLUSTER_TVOC,        0x0000)
    ]
}

def configure() {
    log.warn "configure..."
    if (logEnable) runIn(1800, "logsOff")

    List<String> cmds = []

    cmds += [
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0006 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0008 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0300 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0400 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0406 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x042E {${device.zigbeeId}} {}", "delay 200"
    ]

    cmds += zigbee.configureReporting(CLUSTER_ON_OFF,      0x0000, 0x10, 0,  3600, null)
    cmds += zigbee.configureReporting(CLUSTER_LEVEL,       0x0000, 0x20, 1,  3600, 1)
    cmds += zigbee.configureReporting(CLUSTER_COLOR,       0x0000, 0x20, 1,  3600, 1)
    cmds += zigbee.configureReporting(CLUSTER_COLOR,       0x0001, 0x20, 1,  3600, 1)
    cmds += zigbee.configureReporting(CLUSTER_ILLUMINANCE, 0x0000, 0x21, 30, 300,  50)
    cmds += zigbee.configureReporting(CLUSTER_OCCUPANCY,   0x0000, 0x18, 0,  3600, 1)

    // 0x042E binding confirmed; configureReporting deferred until payload type/scaling
    // is validated from live device captures.

    scheduleHealthCheck()
    cmds += refresh()
    return cmds
}

// ── Private helpers ───────────────────────────────────────────────────────────

private Integer getConfiguredTransitionMs(String settingName, Integer defaultMs = 1000) {
    Object configured = settings?.get(settingName)
    Integer ms = safeToInt(configured, defaultMs)
    return ms > 0 ? ms : defaultMs
}

private BigDecimal getConfiguredTransitionSeconds(String settingName, BigDecimal defaultSeconds = 1.0G) {
    Integer ms = getConfiguredTransitionMs(settingName, (defaultSeconds * 1000).toInteger())
    return BigDecimal.valueOf(ms / 1000.0d)
}

private Integer transitionMsToTenths(Integer ms) {
    Integer safeMs = Math.max(0, ms ?: 0)
    return Math.max(0, (int) Math.round(safeMs / 100.0d))
}

private Integer getMinimumLevelPercent() {
    Integer minLevel = safeToInt(settings.minimumLevel, 5)
    return Math.max(0, Math.min(100, minLevel))
}

private Integer clampLevelPercent(Integer level) {
    Integer safeLevel = Math.max(0, Math.min(100, level ?: 0))
    if (safeLevel == 0) return 0
    Integer minLevel = getMinimumLevelPercent()
    return Math.max(minLevel, safeLevel)
}

private Integer resolveOnLevelPercent() {
    Integer currentLevel    = safeToInt(device.currentValue("level"), 0)
    Integer rememberedLevel = safeToInt(state.lastNonZeroLevel, 100)
    Integer candidate = currentLevel > 0 ? currentLevel : (rememberedLevel > 0 ? rememberedLevel : 100)
    return clampLevelPercent(candidate)
}

private Integer scalePercentToZigbeeLevel(Integer levelPercent) {
    Integer safeLevel = Math.max(0, Math.min(100, levelPercent ?: 0))
    return Math.round(safeLevel * 254 / 100.0d)
}

private Boolean hasMeaningfulValue(Object value) {
    if (value == null) return false
    if (value instanceof String) return value.toString().trim() != ""
    return true
}

private String readAttrCmd(Integer cluster, Integer attrId) {
    return "he rattr 0x${device.deviceNetworkId} 0x${device.endpointId} 0x${zigbee.convertToHexString(cluster, 4)} 0x${zigbee.convertToHexString(attrId, 4)} {}"
}

private Boolean sendEventIfChanged(String name, Object value, String descriptionText = null, String unit = null) {
    if (name == null) return false
    String current  = device.currentValue(name)?.toString()
    String incoming = value?.toString()
    if (current == incoming) return false

    Map evt = [name: name, value: value]
    if (descriptionText != null) evt.descriptionText = descriptionText
    if (unit != null) evt.unit = unit
    sendEvent(evt)
    if (txtEnable && descriptionText) log.info descriptionText
    return true
}

private static Integer hexToInt(Object value, Integer defaultValue = 0) {
    try {
        if (value == null) return defaultValue
        String s = value.toString().trim()
        if (!s) return defaultValue
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
        return Integer.parseInt(s, 16)
    } catch (ignored) {
        return defaultValue
    }
}

private static Integer safeToInt(Object value, Integer defaultValue = 0) {
    try {
        if (value == null) return defaultValue
        if (value instanceof Number) return ((Number) value).intValue()
        String s = value.toString().trim()
        if (!s) return defaultValue
        if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16)
        if (s ==~ /[0-9A-Fa-f]+/ && s.length() > 1 && !(s ==~ /\d+/)) return Integer.parseInt(s, 16)
        return Integer.parseInt(s)
    } catch (ignored) {
        return defaultValue
    }
}

private static BigDecimal safeToBigDecimal(Object value, BigDecimal defaultValue = 0G) {
    try {
        if (value == null) return defaultValue
        if (value instanceof BigDecimal) return (BigDecimal) value
        if (value instanceof Number) return new BigDecimal(value.toString())
        String s = value.toString().trim()
        if (!s) return defaultValue
        return new BigDecimal(s)
    } catch (ignored) {
        return defaultValue
    }
}

private String intTo8bitUnsignedHex(Object value) {
    return zigbee.convertToHexString(safeToInt(value, 0) & 0xFF, 2)
}

private String intTo16bitUnsignedHex(Object value) {
    return zigbee.convertToHexString(safeToInt(value, 0) & 0xFFFF, 4)
}

private String intTo16bitUnsignedHexLE(Object value) {
    String hex = intTo16bitUnsignedHex(value)
    return hex.substring(2, 4) + hex.substring(0, 2)
}
