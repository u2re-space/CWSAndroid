package space.u2re.service.screen

import kotlinx.serialization.Serializable

@Serializable
object SettingsRoute

enum class SettingsTab(val title: String) {
    GENERAL("General"),
    ACCESS("Access"),
    HUB("Hub"),
    PEERS("Peers"),
    CONTROL_CENTER("Control Center")
}
