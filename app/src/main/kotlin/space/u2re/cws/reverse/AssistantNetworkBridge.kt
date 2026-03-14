package space.u2re.cws.reverse

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.u2re.cws.daemon.Daemon
import space.u2re.cws.history.HistoryRepository
import space.u2re.cws.network.ServerV2Packet
import space.u2re.cws.network.ServerV2PacketCodec
import space.u2re.cws.settings.SettingsStore
import space.u2re.cws.settings.resolve

private const val BRIDGE_LOGGER = "ReverseAssistantBridge"

object AssistantNetworkBridge {
    suspend fun handleServerV2Packet(
        context: Context,
        packet: ServerV2Packet,
        config: ReverseGatewayConfig,
        callbacks: Daemon.SyncCallbacks? = null
    ): Boolean {
        if (packet.op.equals("result", ignoreCase = true)
            || packet.op.equals("resolve", ignoreCase = true)
            || packet.op.equals("error", ignoreCase = true)
        ) {
            if (HistoryRepository.handleRemotePacket(packet)) {
                return true
            }
        }
        return handleInbound(context, parseInboundPacket(packet), config, callbacks)
    }

    suspend fun handleReverseMessage(
        context: Context,
        messageType: String,
        rawPayload: String,
        config: ReverseGatewayConfig,
        callbacks: Daemon.SyncCallbacks? = null
    ): Boolean {
        val inbound = parseInboundMessage(rawPayload, messageType) ?: return false
        if (inbound.action == "result" || inbound.action == "resolve" || inbound.action == "error") {
            val packet = ServerV2PacketCodec.fromJsonObject(inbound.payload).copy(
                op = extractString(inbound.payload["op"]) ?: inbound.action,
                what = extractString(inbound.payload["what"]) ?: extractAction(inbound.payload, inbound.action)
            )
            if (HistoryRepository.handleRemotePacket(packet)) {
                return true
            }
        }
        return handleInbound(context, inbound, config, callbacks)
    }

    private suspend fun handleInbound(
        context: Context,
        inbound: ReverseInboundMessage,
        config: ReverseGatewayConfig,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = SettingsStore.load(context).resolve()
        if (!isAnyTargetMatch(inbound.targets, config.deviceId, settings, config.userId)) {
            Log.d(
                BRIDGE_LOGGER,
                "Skip reverse message: targets=${inbound.targets.joinToString(",")} deviceId=${config.deviceId} userId=${config.userId} aliases=${buildTargetAliases(config.deviceId, settings, config.userId).joinToString(",")}"
            )
            return@withContext false
        }

        return@withContext when (inbound.action) {
            "result", "resolve", "error", "hello", "token" -> true
            "feature" -> handleFeature(context, inbound.payload, settings, callbacks)
            "clipboard",
            "clipboard.set",
            "set_clipboard",
            "copy",
            "paste",
            "write_clipboard",
            "clipboard:update",
            "clipboard:write" -> ReverseClipboardHandler.handleDelivery(context, inbound.payload, settings, callbacks)
            "clipboard:read",
            "clipboard:get",
            "clipboard:isready" -> ReverseClipboardHandler.handleQuery(inbound.payload, inbound.action, callbacks)
            "sms",
            "send_sms",
            "sms.send",
            "sms-send",
            "send.sms",
            "sms:send" -> ReverseAuxiliaryHandler.handleSmsDelivery(inbound.payload, settings, callbacks)
            "sms:list",
            "sms:get" -> ReverseAuxiliaryHandler.handleSmsQuery(inbound.payload, callbacks)
            "speak",
            "notifications.speak",
            "notification.speak",
            "speak.notification",
            "notification:speak",
            "notifications:speak" -> ReverseAuxiliaryHandler.handleNotificationsDelivery(inbound.payload, settings, callbacks)
            "notifications:list",
            "notifications:get" -> ReverseAuxiliaryHandler.handleNotificationsQuery(inbound.payload, callbacks)
            "contacts:list",
            "contacts:get" -> ReverseAuxiliaryHandler.handleContactsQuery(inbound.payload, callbacks)
            "dispatch",
            "forward",
            "http",
            "network.dispatch",
            "network:dispatch",
            "http:dispatch",
            "request:dispatch" -> ReverseDispatchHandler.handle(
                context = context,
                payload = inbound.payload,
                settings = settings,
                namespace = inbound.namespace,
                action = inbound.action,
                callbacks = callbacks
            )
            else -> {
                val forwarded = inbound.payload.has("requests") || inbound.payload.has("to")
                if (forwarded) {
                    ReverseDispatchHandler.handle(
                        context = context,
                        payload = inbound.payload,
                        settings = settings,
                        namespace = inbound.namespace,
                        action = inbound.action,
                        callbacks = callbacks
                    )
                } else {
                    Log.d(BRIDGE_LOGGER, "Unhandled action=${inbound.action} message=${inbound.payload}")
                    false
                }
            }
        }
    }

    private suspend fun handleFeature(
        context: Context,
        payload: com.google.gson.JsonObject,
        settings: space.u2re.cws.settings.Settings,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        val featureName = extractString(payload["data"]?.let(::ensureObject)?.get("feature"))?.lowercase()
        val featurePayload = ensureObject(ensureObject(payload["data"])?.get("payload")) ?: com.google.gson.JsonObject()
        return when (featureName) {
            "sms" -> ReverseAuxiliaryHandler.handleSmsDelivery(featurePayload, settings, callbacks)
            "notifications.speak" -> ReverseAuxiliaryHandler.handleNotificationsDelivery(featurePayload, settings, callbacks)
            "clipboard" -> ReverseClipboardHandler.handleDelivery(context, featurePayload, settings, callbacks)
            else -> {
                Log.d(BRIDGE_LOGGER, "Unhandled feature=$featureName message=$payload")
                false
            }
        }
    }
}
