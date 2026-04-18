package space.u2re.cws.ui.screen

import kotlinx.serialization.Serializable

@Serializable
object SettingsRoute

enum class SettingsTab(val title: String) {
    GENERAL("General"),
    ENDPOINT("Endpoint"),
    CLIENT("Client"),
    ACCESS("Access"),
    SERVER("Server"),
    CONTROL_CENTER("Control Center")
}
