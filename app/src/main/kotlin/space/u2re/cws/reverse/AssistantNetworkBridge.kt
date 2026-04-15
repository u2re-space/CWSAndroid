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

/**
 * Inbound message bridge from transport packets into Android-side features.
 *
 * It accepts both v2 packets and older reverse messages, filters out packets
 * not meant for this device, suppresses self-echo loops, and dispatches the
 * surviving actions to clipboard, SMS, notifications, contacts, or HTTP relay.
 */
object AssistantNetworkBridge {
    private fun isSelfEchoSource(
        inbound: ReverseInboundMessage,
        localDeviceId: String,
        settings: space.u2re.cws.settings.Settings,
        userId: String?
    ): Boolean {
        val aliases = buildTargetAliases(localDeviceId, settings, userId)
        val source = listOf(
            extractString(inbound.payload["byId"]),
            extractString(inbound.payload["from"]),
            extractString(inbound.payload["sender"])
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        if (source.isBlank()) return false
        val sourceAliases = space.u2re.cws.network.EndpointIdentity.aliases(source)
        return sourceAliases.any { aliases.contains(it) }
    }

    /** Handle one canonical v2 packet arriving from the active transport runtime. */
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

    /** Handle one legacy reverse-bridge message and normalize it into the same inbound flow. */
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

    /**
     * Main inbound router after packet/message normalization.
     *
     * WHY: all target checks and self-echo suppression happen before feature
     * handlers so clipboard/dispatch loops do not escape into app logic.
     */
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
        if (isSelfEchoSource(inbound, config.deviceId, settings, config.userId)) {
            Log.d(
                BRIDGE_LOGGER,
                "Skip reverse self-echo action=${inbound.action} source=${extractString(inbound.payload["byId"]) ?: extractString(inbound.payload["from"]) ?: "-"}"
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
            "clipboard:write",
            "clipboard:delivery",
            "airpad:clipboard:write",
            "airpad:clipboard:delivery" -> ReverseClipboardHandler.handleDelivery(context, inbound.payload, settings, callbacks)
            "clipboard:read",
            "clipboard:get",
            "clipboard:isready",
            "clipboard:ask",
            "airpad:clipboard:read",
            "airpad:clipboard:isready" -> ReverseClipboardHandler.handleQuery(inbound.payload, inbound.action, callbacks)
            "sms",
            "send_sms",
            "sms.send",
            "sms-send",
            "send.sms",
            "sms:send",
            "sms:delivery" -> ReverseAuxiliaryHandler.handleSmsDelivery(inbound.payload, settings, callbacks)
            "sms:list",
            "sms:get",
            "sms:ask" -> ReverseAuxiliaryHandler.handleSmsQuery(inbound.payload, callbacks)
            "speak",
            "notifications.speak",
            "notification.speak",
            "speak.notification",
            "notification:speak",
            "notifications:speak",
            "notification:delivery" -> ReverseAuxiliaryHandler.handleNotificationsDelivery(inbound.payload, settings, callbacks)
            "notifications:list",
            "notifications:get",
            "notification:ask" -> ReverseAuxiliaryHandler.handleNotificationsQuery(inbound.payload, callbacks)
            "contacts:list",
            "contacts:get",
            "contact:ask" -> ReverseAuxiliaryHandler.handleContactsQuery(inbound.payload, callbacks)
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

    /** Handle the generic `feature` wrapper used by some legacy bridge payloads. */
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
