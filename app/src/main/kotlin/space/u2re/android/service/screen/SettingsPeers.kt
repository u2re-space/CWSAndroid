package space.u2re.service.screen

import space.u2re.service.daemon.normalizeHubDispatchUrl
import java.net.NetworkInterface

data class DiscoveredTarget(
    val kind: String,
    val label: String,
    val value: String
)

fun buildDiscoveredTargets(
    destinationText: String,
    localIps: List<String>,
    hubDispatchUrl: String
): List<DiscoveredTarget> {
    val items = mutableListOf<DiscoveredTarget>()
    val seen = mutableSetOf<String>()

    fun add(kind: String, label: String, value: String) {
        val key = "$kind|$value"
        if (seen.add(key)) {
            items.add(DiscoveredTarget(kind = kind, label = label, value = value))
        }
    }

    val normalizedHub = normalizeHubDispatchUrl(hubDispatchUrl) ?: hubDispatchUrl.trim()
    if (normalizedHub.isNotBlank()) {
        add("hub", normalizedHub, normalizedHub)
    }

    destinationText
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { raw ->
            val lower = raw.lowercase()
            val kind = when {
                lower.startsWith("hub:") -> "hub"
                lower.startsWith("server:") -> "server"
                lower.startsWith("proxy:") -> "proxy"
                lower.startsWith("tunnel:") -> "tunnel"
                lower.startsWith("device:") -> "device"
                "://" in raw -> "url"
                else -> "ip/device"
            }
            add(kind, raw, raw)
        }

    localIps.forEach { ip ->
        add("local-device", ip, "device:$ip")
    }

    return items
}

fun loadLocalIpAddresses(): List<String> {
    return try {
        val list = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (host.contains(":")) continue
                list.add(host)
            }
        }
        list.distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }
}
