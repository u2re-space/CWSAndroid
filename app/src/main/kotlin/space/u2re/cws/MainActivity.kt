package space.u2re.cws

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.livekit.android.LiveKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import space.u2re.cws.ui.screen.ConnectRoute
import space.u2re.cws.ui.screen.ConnectScreen
import space.u2re.cws.ui.screen.SettingsRoute
import space.u2re.cws.ui.screen.SettingsScreen
import space.u2re.cws.ui.screen.VoiceAssistantRoute
import space.u2re.cws.ui.screen.VoiceAssistantScreen
import space.u2re.cws.ui.screen.ResponsesAssistantRoute
import space.u2re.cws.ui.screen.ResponsesAssistantScreen
import space.u2re.cws.runtime.AppRuntimeCoordinator
import space.u2re.cws.ui.theme.LiveKitVoiceAssistantExampleTheme
import space.u2re.cws.ui.viewmodel.VoiceAssistantViewModel
import io.livekit.android.util.LoggingLevel

class MainActivity : ComponentActivity() {
    private companion object {
        const val REQUEST_POST_NOTIFICATIONS = 7010
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveKit.loggingLevel = LoggingLevel.DEBUG
        val settings = AppRuntimeCoordinator.loadSettings(application)
        
        if (settings.runDaemonForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (notificationPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS
                )
            }
        }
        AppRuntimeCoordinator.startFromMainEntry(application, this)

        setContent {
            val navController = rememberNavController()
            val startDest: Any = if (intent?.action == android.content.Intent.ACTION_APPLICATION_PREFERENCES) {
                SettingsRoute
            } else {
                ConnectRoute
            }
                LiveKitVoiceAssistantExampleTheme(dynamicColor = false) {
                Scaffold { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {

                        // Set up NavHost for the app
                        NavHost(navController, startDestination = startDest) {
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
                                    navigateToSettings = {
                                        runOnUiThread {
                                            navController.navigate(SettingsRoute)
                                        }
                                    }
                                )
                            }
                            composable<SettingsRoute> {
                                SettingsScreen(
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
        if (isFinishing && !isChangingConfigurations) {
            AppRuntimeCoordinator.stopUiOwnedRuntime(this)
        }
        super.onDestroy()
    }
}
