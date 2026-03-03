package space.u2re.cws.screen

import kotlinx.serialization.Serializable

@Serializable
object SettingsRoute

enum class SettingsTab(val title: String) {
    GENERAL("General"),
    ACCESS("Access"),
    GATEWAY("Gateway"),
    SERVER("Server"),
    CONTROL_CENTER("Control Center")
}
