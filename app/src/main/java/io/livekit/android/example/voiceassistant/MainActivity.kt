package io.livekit.android.example.voiceassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.livekit.android.LiveKit
import io.livekit.android.example.voiceassistant.screen.ConnectRoute
import io.livekit.android.example.voiceassistant.screen.ConnectScreen
import io.livekit.android.example.voiceassistant.screen.AutomataSettingsRoute
import io.livekit.android.example.voiceassistant.screen.AutomataSettingsScreen
import io.livekit.android.example.voiceassistant.screen.VoiceAssistantRoute
import io.livekit.android.example.voiceassistant.screen.VoiceAssistantScreen
import io.livekit.android.example.voiceassistant.reverse.ReverseGatewayClient
import io.livekit.android.example.voiceassistant.reverse.ReverseGatewayConfigProvider
import io.livekit.android.example.voiceassistant.daemon.AutomataDaemonController
import io.livekit.android.example.voiceassistant.ui.theme.LiveKitVoiceAssistantExampleTheme
import io.livekit.android.example.voiceassistant.viewmodel.VoiceAssistantViewModel
import io.livekit.android.util.LoggingLevel

class MainActivity : ComponentActivity() {
    private var reverseGateway: ReverseGatewayClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveKit.loggingLevel = LoggingLevel.DEBUG
        reverseGateway = ReverseGatewayClient(ReverseGatewayConfigProvider.load(application))
        reverseGateway?.start()
        AutomataDaemonController.start(application, this)

        setContent {
            val navController = rememberNavController()
            LiveKitVoiceAssistantExampleTheme(dynamicColor = false) {
                Scaffold { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {

                        // Set up NavHost for the app
                        NavHost(navController, startDestination = ConnectRoute) {
                            composable<ConnectRoute> {
                                ConnectScreen(
                                    navigateToVoiceAssistant = { voiceAssistantRoute ->
                                    runOnUiThread {
                                        navController.navigate(voiceAssistantRoute)
                                    }
                                    },
                                    navigateToAutomataSettings = {
                                        runOnUiThread {
                                            navController.navigate(AutomataSettingsRoute)
                                        }
                                    }
                                )
                            }
                            composable<AutomataSettingsRoute> {
                                AutomataSettingsScreen(
                                    navigateBack = { runOnUiThread { navController.navigateUp() } }
                                )
                            }

                            composable<VoiceAssistantRoute> {
                                val viewModel = viewModel<VoiceAssistantViewModel>()
                                VoiceAssistantScreen(
                                    viewModel = viewModel,
                                    onEndCall = {
                                        runOnUiThread { navController.navigateUp() }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        reverseGateway?.stop()
        AutomataDaemonController.stop()
        super.onDestroy()
    }
}
