package space.u2re.service

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
import androidx.navigation.toRoute
import io.livekit.android.LiveKit
import space.u2re.service.screen.ConnectRoute
import space.u2re.service.screen.ConnectScreen
import space.u2re.service.screen.AutomataSettingsRoute
import space.u2re.service.screen.AutomataSettingsScreen
import space.u2re.service.screen.VoiceAssistantRoute
import space.u2re.service.screen.VoiceAssistantScreen
import space.u2re.service.screen.ResponsesAssistantRoute
import space.u2re.service.screen.ResponsesAssistantScreen
import space.u2re.service.reverse.ReverseGatewayClient
import space.u2re.service.reverse.ReverseGatewayConfigProvider
import space.u2re.service.daemon.AutomataDaemonController
import space.u2re.service.ui.theme.LiveKitVoiceAssistantExampleTheme
import space.u2re.service.viewmodel.VoiceAssistantViewModel
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
                                    navigateToLocalResponses = { localRoute ->
                                        runOnUiThread {
                                            navController.navigate(localRoute)
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

                            composable<ResponsesAssistantRoute> {
                                val route = it.toRoute<ResponsesAssistantRoute>()
                                ResponsesAssistantScreen(
                                    route = route,
                                    onClose = {
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
