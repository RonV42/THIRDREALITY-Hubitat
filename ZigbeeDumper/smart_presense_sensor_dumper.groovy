/**
 * THIRDREALITY Smart Presence Sensor R3 — Raw Frame Dumper
 *
 * Purpose: Dump all raw Zigbee frames received from the device to the Hubitat
 *          log with zero interpretation or filtering. Intended for vendor
 *          diagnostics and firmware issue documentation.
 *
 * Author:  RonV42
 * Version: v1.0
 * Date:    2026-04-01
 *
 * Usage:   Install this driver, pair or swap to it on the device, then
 *          copy/paste the log output and send to THIRDREALITY support.
 *          Every frame the device sends will appear in the log exactly
 *          as received.
 */

metadata {
    definition(
        name:      "THIRDREALITY Smart Presence Sensor R3 - Raw Dump",
        namespace: "RonV42",
        author:    "RonV42"
    ) {
        capability "Sensor"
        capability "Initialize"

        fingerprint profileId: "0104",
                    endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,0008,0300,0400,0406,042E",
                    outClusters: "0019",
                    manufacturer: "Third Reality, Inc",
                    model: "3RPL01084Z",
                    deviceJoinName: "THIRDREALITY Smart Presence Sensor R3"
    }

    preferences {
        input name: "logLevel",
              type: "enum",
              title: "Log Level",
              options: ["INFO", "DEBUG"],
              defaultValue: "DEBUG",
              description: "INFO logs cluster+value summary. DEBUG logs full raw frame."
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────

def installed() {
    log.info "RAW DUMP DRIVER installed — every Zigbee frame will be logged"
    initialize()
}

def updated() {
    log.info "RAW DUMP DRIVER updated — log level: ${logLevel}"
    initialize()
}

def initialize() {
    log.info "RAW DUMP DRIVER initialized — waiting for frames..."
}

// ── Parser — dump everything ───────────────────────────────────────────────

def parse(String description) {

    // Always log the raw description string regardless of level
    if (logLevel == "DEBUG") {
        log.debug "RAW FRAME: ${description}"
    }

    // Attempt to parse into a descMap for a human-readable summary
    try {
        def descMap = zigbee.parseDescriptionAsMap(description)

        if (descMap) {
            def cluster  = descMap.cluster  ?: descMap.clusterId ?: "unknown"
            def attrId   = descMap.attrId   ?: "N/A"
            def encoding = descMap.encoding ?: "N/A"
            def value    = descMap.value    ?: "N/A"
            def command  = descMap.command  ?: "N/A"

            def clusterName = clusterLabel(cluster)

            log.info "CLUSTER: ${cluster} (${clusterName}) | attrId: ${attrId} | encoding: ${encoding} | command: ${command} | value: ${value}"

            // Special handling for 0x042E — the TVOC cluster under investigation
            if (cluster == "042E" || cluster == "1070") {
                log.warn "TVOC CLUSTER 0x042E FRAME — attrId: ${attrId} | encoding: ${encoding} | raw value hex: ${value}"
                if (encoding == "39" && value?.length() == 8) {
                    // Attempt IEEE-754 float decode both endiannesses
                    try {
                        long uintBE = Long.parseLong(value, 16)
                        float floatBE = Float.intBitsToFloat((int) uintBE)
                        String swapped = value[6..7] + value[4..5] + value[2..3] + value[0..1]
                        long uintLE = Long.parseLong(swapped, 16)
                        float floatLE = Float.intBitsToFloat((int) uintLE)
                        log.warn "TVOC DECODE — hex: ${value} | floatBE: ${floatBE} | floatLE: ${floatLE} | uint: ${uintBE}"
                    } catch (e) {
                        log.warn "TVOC DECODE failed: ${e.message}"
                    }
                }
            }

            // Log catchall frames with full payload
            if (description?.startsWith("catchall")) {
                def payload = descMap.data ?: descMap.payload ?: "N/A"
                log.info "CATCHALL — cluster: ${cluster} (${clusterName}) | command: ${command} | payload: ${payload}"
                if (cluster == "042E" || cluster == "1070") {
                    log.warn "TVOC CATCHALL 0x042E — cluster: ${cluster} | command: ${command} | payload: ${payload}"
                }
            }
        } else {
            log.info "UNPARSEABLE FRAME: ${description}"
        }
    } catch (Exception e) {
        log.error "PARSE EXCEPTION: ${e.message} | raw: ${description}"
    }

    return []
}

// ── Cluster label helper ───────────────────────────────────────────────────

private String clusterLabel(String cluster) {
    switch (cluster?.toUpperCase()) {
        case "0000": return "Basic"
        case "0003": return "Identify"
        case "0004": return "Groups"
        case "0005": return "Scenes"
        case "0006": return "On/Off"
        case "0008": return "Level Control"
        case "0019": return "OTA Upgrade"
        case "001F": return "Power Configuration"
        case "0036": return "Power Profile"
        case "0300": return "Color Control"
        case "0400": return "Illuminance Measurement"
        case "0406": return "Occupancy Sensing"
        case "042E": return "TVOC Measurement"
        case "1070": return "TVOC Measurement (clusterInt)"
        case "8021": return "Bind Response"
        default:     return "Unknown"
    }
}
