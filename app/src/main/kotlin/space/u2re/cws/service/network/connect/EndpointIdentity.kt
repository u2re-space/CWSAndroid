package space.u2re.cws.network

import java.net.URI

/**
 * Routing-id helpers shared across Android network config and transport code.
 *
 * This object decides how user/device/host inputs are normalized into the
 * endpoint/node ids used by packet routing and bridge diagnostics.
 */
object EndpointIdentity {
    private val NODE_PREFIX_RE = Regex("^(?:l-|h-|p-|l_|h_|p_)", RegexOption.IGNORE_CASE)
    private val TYPE_PREFIX_RE = Regex("^(?:device:|local-device:|id:|client:|peer:)", RegexOption.IGNORE_CASE)
    private val IPV4_RE = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?$")

    /** Detect wildcard/broadcast targets that should not be treated as concrete peer ids. */
    fun isBroadcast(value: String?): Boolean {
        val normalized = normalize(value)
        return normalized == "broadcast" || normalized == "all" || normalized == "*"
    }

    /** Basic lowercase/trim normalization used by all routing helpers. */
    fun normalize(value: String?): String = value?.trim()?.lowercase().orEmpty()

    /** Remove type/node prefixes from one target so equivalent ids compare consistently. */
    fun canonical(value: String?): String {
        var normalized = normalize(value)
        if (normalized.isBlank()) return ""
        normalized = TYPE_PREFIX_RE.replace(normalized, "").trim()
        normalized = NODE_PREFIX_RE.replace(normalized, "").trim()
        return normalized
    }

    /**
     * Convert one user/device/host value into the preferred route target.
     *
     * NOTE: IPv4 values are promoted to `l-<ip>` targets so Android matches the
     * same LAN-oriented conventions used by the CWSP coordinator.
     */
    fun bestRouteTarget(value: String?): String {
        val normalized = normalize(value)
        if (normalized.isBlank()) return ""
        if (isBroadcast(normalized)) return ""
        if (normalized.startsWith("l-") || normalized.startsWith("h-") || normalized.startsWith("p-")) {
            return normalized
        }
        val canonical = canonical(normalized)
        if (canonical.isBlank()) return normalized
        return if (IPV4_RE.matches(canonical)) "l-$canonical" else canonical
    }

    /** Detect values that are already explicit HTTP(S) URLs instead of logical node ids. */
    fun isExplicitHttpUrl(value: String?): Boolean {
        val normalized = normalize(value)
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    /** Best-effort heuristic for deciding whether a raw value is a logical node target. */
    fun isLikelyNodeTarget(value: String?): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank() || isBroadcast(normalized)) return false
        if (isExplicitHttpUrl(normalized)) return false
        if (TYPE_PREFIX_RE.containsMatchIn(normalized)) return true
        if (NODE_PREFIX_RE.containsMatchIn(normalized)) return true
        if (IPV4_RE.matches(normalized)) return true
        if (normalized.contains("/") || normalized.contains("?") || normalized.contains("#")) return false
        return !normalized.contains("://")
    }

    /** Extract the most useful source/peer id from either a routing target or an explicit URL. */
    fun sourceIdFromTargetOrUrl(value: String?): String {
        val normalized = normalize(value)
        if (normalized.isBlank()) return ""
        if (isExplicitHttpUrl(normalized)) {
            val host = runCatching { URI(normalized).host?.trim() }.getOrNull().orEmpty()
            if (host.isNotBlank()) {
                return bestRouteTarget(host)
            }
        }
        return bestRouteTarget(normalized)
    }

    /** Build the alias set used by wire identity and routing fallbacks. */
    fun aliases(value: String?): Set<String> {
        val normalized = normalize(value)
        if (normalized.isBlank()) return emptySet()
        val canonical = canonical(normalized)
        if (canonical.isBlank()) return setOf(normalized)

        val out = linkedSetOf<String>()
        out.add(normalized)
        out.add(canonical)
        out.add("l-$canonical")
        out.add("h-$canonical")
        out.add("p-$canonical")
        out.add("l_$canonical")
        out.add("h_$canonical")
        out.add("p_$canonical")
        out.add(canonical.substringBefore(":"))
        return out.filter { it.isNotBlank() }.toSet()
    }
}
