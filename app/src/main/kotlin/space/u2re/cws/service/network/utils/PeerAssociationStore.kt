package space.u2re.cws.daemon

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREF_NAME_PEER_ASSOCIATIONS = "peer_associations_v1"
private const val PREF_KEY_ASSOCIATIONS = "associations"

private fun peerAssociationPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_NAME_PEER_ASSOCIATIONS, Context.MODE_PRIVATE)

private val peerAssociationJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
private val peerAssociationSerializer = MapSerializer(String.serializer(), String.serializer())

object PeerAssociationStore {
    private fun normalizePeerId(value: String): String = value.trim().lowercase()

    fun load(context: Context): Map<String, String> {
        return try {
            val raw = peerAssociationPrefs(context).getString(PREF_KEY_ASSOCIATIONS, null) ?: return emptyMap()
            val parsed = peerAssociationJson.decodeFromString(peerAssociationSerializer, raw)
            parsed.entries.associate { (key, value) ->
                normalizePeerId(key) to value.trim()
            }.filterValues { it.isNotBlank() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun get(context: Context, peerId: String): String? {
        val associations = load(context)
        return associations[normalizePeerId(peerId)]
    }

    fun save(context: Context, peerId: String, address: String) {
        val normalizedId = normalizePeerId(peerId)
        val normalizedAddress = address.trim()
        if (normalizedId.isBlank() || normalizedAddress.isBlank()) return
        val current = load(context).toMutableMap()
        current[normalizedId] = normalizedAddress
        peerAssociationPrefs(context).edit().putString(PREF_KEY_ASSOCIATIONS, peerAssociationJson.encodeToString(peerAssociationSerializer, current)).apply()
    }

    fun saveMany(context: Context, associations: Map<String, String>) {
        if (associations.isEmpty()) return
        val current = load(context).toMutableMap()
        for ((key, value) in associations) {
            val normalizedId = normalizePeerId(key)
            val normalizedAddress = value.trim()
            if (normalizedId.isBlank() || normalizedAddress.isBlank()) continue
            current[normalizedId] = normalizedAddress
        }
        if (current.isNotEmpty()) {
            peerAssociationPrefs(context).edit().putString(PREF_KEY_ASSOCIATIONS, peerAssociationJson.encodeToString(peerAssociationSerializer, current)).apply()
        }
    }
}
