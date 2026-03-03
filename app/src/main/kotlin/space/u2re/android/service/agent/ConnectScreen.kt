package space.u2re.service.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.u2re.service.R
import space.u2re.service.daemon.SettingsStore
import space.u2re.service.daemon.resolve
import space.u2re.service.hardcodedToken
import space.u2re.service.hardcodedUrl
import space.u2re.service.sandboxID
import kotlinx.serialization.Serializable

@Serializable
object ConnectRoute

@Composable
fun ConnectScreen(
    navigateToVoiceAssistant: (VoiceAssistantRoute) -> Unit,
    navigateToSettings: () -> Unit,
    navigateToLocalResponses: (ResponsesAssistantRoute) -> Unit
) {
    val context = LocalContext.current
    val daemonSettings = remember { SettingsStore.load(context.applicationContext).resolve() }
    val hasAiConfig = daemonSettings.apiEndpoint.isNotBlank() && daemonSettings.apiKey.isNotBlank()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp).fillMaxWidth(0.96f)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                    Image(painter = painterResource(R.drawable.connect_icon), contentDescription = "Connect icon")

                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = buildAnnotatedString {
                            append("Start a call to chat with your voice agent. Need help getting set up?\nCheck out the ")
                            withLink(
                                LinkAnnotation.Url(
                                    "https://docs.livekit.io/agents/start/voice-ai/",
                                    TextLinkStyles(
                                        style = SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    )
                                )
                            ) {
                                append("Voice AI quickstart.")
                            }
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.8f),
                        color = MaterialTheme.colorScheme.onSurface
                    )

            var hasError by rememberSaveable { mutableStateOf(false) }
            var isConnecting by remember { mutableStateOf(false) }

            Spacer(Modifier.size(8.dp))

            AnimatedVisibility(hasError) {
                Text(
                    text = "Error connecting. Make sure your agent is properly configured and try again.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
            }

            Spacer(Modifier.size(24.dp))

            val buttonColors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
            Button(
                colors = buttonColors,
                shape = RoundedCornerShape(20),
                onClick = {
                    // Token source details from TokenExt.kt
                    val route = VoiceAssistantRoute(
                        sandboxId = sandboxID,
                        hardcodedUrl = hardcodedUrl,
                        hardcodedToken = hardcodedToken,
                        apiEndpoint = daemonSettings.apiEndpoint,
                        apiKey = daemonSettings.apiKey,
                        aiAllowInsecureTls = daemonSettings.allowInsecureTls
                    )
                    navigateToVoiceAssistant(route)
                }
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(isConnecting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Spacer(Modifier.size(8.dp))
                        }
                    }
                    Text(
                        text = if (isConnecting) "CONNECTING" else "START CALL",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                        )
                    )
                }
            }

            Spacer(Modifier.size(12.dp))
            Button(
                colors = buttonColors,
                shape = RoundedCornerShape(20),
                onClick = navigateToSettings
            ) {
                Text("Settings")
            }
            Spacer(Modifier.size(12.dp))
            Button(
                colors = buttonColors,
                shape = RoundedCornerShape(20),
                enabled = hasAiConfig,
                onClick = {
                    if (!hasAiConfig) {
                        return@Button
                    }
                    navigateToLocalResponses(
                        ResponsesAssistantRoute(
                            apiEndpoint = daemonSettings.apiEndpoint,
                            apiKey = daemonSettings.apiKey,
                            aiAllowInsecureTls = daemonSettings.allowInsecureTls
                        )
                    )
                }
            ) {
                Text("START LOCAL AI")
            }
                }
            }
        }
    }
}