package space.u2re.cws.network

object EndpointIdentity {
    private val NODE_PREFIX_RE = Regex("^(?:l-|h-|p-|l_|h_|p_)", RegexOption.IGNORE_CASE)
    private val TYPE_PREFIX_RE = Regex("^(?:device:|local-device:|id:|client:|peer:)", RegexOption.IGNORE_CASE)
    private val IPV4_RE = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?$")

    fun isBroadcast(value: String?): Boolean {
        val normalized = normalize(value)
        return normalized == "broadcast" || normalized == "all" || normalized == "*"
    }

    fun normalize(value: String?): String = value?.trim()?.lowercase().orEmpty()

    fun canonical(value: String?): String {
        var normalized = normalize(value)
        if (normalized.isBlank()) return ""
        normalized = TYPE_PREFIX_RE.replace(normalized, "").trim()
        normalized = NODE_PREFIX_RE.replace(normalized, "").trim()
        return normalized
    }

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
