package space.u2re.cws.daemon

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private const val PREF_NAME_PEER_ASSOCIATIONS = "peer_associations_v1"
private const val PREF_KEY_ASSOCIATIONS = "associations"

private fun peerAssociationPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_NAME_PEER_ASSOCIATIONS, Context.MODE_PRIVATE)

private val peerAssociationGson = Gson()
private val emptyAssociationsType = object : TypeToken<Map<String, String>>() {}.type

object PeerAssociationStore {
    private fun normalizePeerId(value: String): String = value.trim().lowercase()

    fun load(context: Context): Map<String, String> {
        return try {
            val raw = peerAssociationPrefs(context).getString(PREF_KEY_ASSOCIATIONS, null) ?: return emptyMap()
            val parsed = peerAssociationGson.fromJson(raw, emptyAssociationsType) as? Map<*, *> ?: return emptyMap()
            parsed.entries.associate { (key, value) ->
                normalizePeerId(key?.toString().orEmpty()) to (value?.toString()?.trim().orEmpty())
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
        peerAssociationPrefs(context).edit().putString(PREF_KEY_ASSOCIATIONS, peerAssociationGson.toJson(current)).apply()
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
            peerAssociationPrefs(context).edit().putString(PREF_KEY_ASSOCIATIONS, peerAssociationGson.toJson(current)).apply()
        }
    }
}
