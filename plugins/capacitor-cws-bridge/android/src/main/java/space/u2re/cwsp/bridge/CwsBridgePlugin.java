package space.u2re.cwsp.bridge;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified Capacitor bridge: TypeScript ({@code registerPlugin('CwsBridge')}) ↔ Android.
 * CWSAndroid (cwsp flavor) loads the same Web assets and Capacitor runtime; extend this class
 * there to forward {@link #invoke} to Kotlin/Compose if needed.
 */
@CapacitorPlugin(name = "CwsBridge")
public class CwsBridgePlugin extends Plugin {
    private static final String PREF_NAME = "settings_v1";
    private static final String PREF_KEY = "settings_v1";
    private static final String CHANNEL_SETTINGS_GET = "settings:get";
    private static final String CHANNEL_SETTINGS_PATCH = "settings:patch";
    private static final String ENVELOPE_KEY = "envelope";
    private static final String ENVELOPE_PAYLOAD_KEY = "payload";
    private static final String ENVELOPE_DATA_KEY = "data";
    private static final String ENVELOPE_PATH_KEY = "path";
    private static final String ENVELOPE_UUID_KEY = "uuid";
    private static final String ENVELOPE_SRC_KEY = "srcChannel";
    private static final String ENVELOPE_DST_KEY = "dstChannel";

    public static final String EVENT_NATIVE_MESSAGE = "nativeMessage";

    @PluginMethod
    public void getShellInfo(PluginCall call) {
        JSObject o = new JSObject();
        o.put("shell", "capacitor-webview");
        o.put("bridge", "cws-bridge");
        o.put("native", true);
        o.put("platform", "android");
        call.resolve(o);
    }

    /**
     * Generic invoke: {@code channel} names the Kotlin/native route; {@code payload} is opaque JSON.
     * Default implementation acknowledges; override or replace in CWSAndroid to reach Compose.
     */
    @PluginMethod
    public void invoke(PluginCall call) {
        String explicitChannel = call.getString("channel", "default");
        JSObject payload = call.getObject("payload", new JSObject());
        JSObject explicitEnvelope = call.getObject(ENVELOPE_KEY, null);
        JSONObject envelope = explicitEnvelope != null
            ? explicitEnvelope
            : (payload != null ? payload.optJSONObject(ENVELOPE_KEY) : null);
        String channel = resolveInvokeChannel(explicitChannel, envelope, payload);
        JSObject invokePayload = resolveInvokePayload(payload, envelope);
        if (CHANNEL_SETTINGS_GET.equals(channel)) {
            call.resolve(handleSettingsGet(channel, envelope));
            return;
        }
        if (CHANNEL_SETTINGS_PATCH.equals(channel)) {
            call.resolve(handleSettingsPatch(channel, invokePayload, envelope));
            return;
        }
        JSObject result = new JSObject();
        result.put("ok", true);
        result.put("channel", channel);
        result.put("echo", invokePayload != null ? invokePayload : new JSObject());
        result.put("envelope", buildBridgeEnvelope("response", channel, invokePayload, envelope));
        call.resolve(result);
    }

    /**
     * Optional: call from Kotlin to deliver events to JS ({@code addListener('nativeMessage', ...)}).
     */
    public void emitToWeb(JSObject data) {
        notifyListeners(EVENT_NATIVE_MESSAGE, data, true);
    }

    private JSObject handleSettingsGet(String channel, JSONObject requestEnvelope) {
        JSONObject nativeSettings = readNativeSettingsJson();
        JSObject result = new JSObject();
        result.put("ok", true);
        result.put("channel", channel);
        result.put("echo", new JSObject());
        result.put("nativeSettings", nativeSettings.toString());
        result.put("appSettings", buildAppSettings(nativeSettings));
        result.put("envelope", buildBridgeEnvelope("response", channel, new JSObject(), requestEnvelope));
        return result;
    }

    private JSObject handleSettingsPatch(String channel, JSObject payload, JSONObject requestEnvelope) {
        JSONObject nativeSettings = readNativeSettingsJson();
        JSONObject patch = new JSONObject();
        if (payload != null) {
            Object appSettings = payload.opt("appSettings");
            if (appSettings instanceof JSONObject) {
                patch = buildNativePatch((JSONObject) appSettings);
            }
        }
        mergeInto(nativeSettings, patch);
        writeNativeSettingsJson(nativeSettings);

        JSObject result = new JSObject();
        result.put("ok", true);
        result.put("channel", channel);
        result.put("echo", payload != null ? payload : new JSObject());
        result.put("nativeSettings", nativeSettings.toString());
        result.put("appSettings", buildAppSettings(nativeSettings));
        result.put("envelope", buildBridgeEnvelope("response", channel, payload != null ? payload : new JSObject(), requestEnvelope));
        return result;
    }

    private String resolveInvokeChannel(String explicitChannel, JSONObject envelope, JSObject payload) {
        String direct = explicitChannel == null ? "" : explicitChannel.trim();
        if (!direct.isEmpty() && !"default".equals(direct)) {
            return direct;
        }
        if (payload != null) {
            String payloadChannel = payload.optString("channel", "").trim();
            if (!payloadChannel.isEmpty()) return payloadChannel;
        }
        if (envelope != null) {
            JSONArray path = envelope.optJSONArray(ENVELOPE_PATH_KEY);
            if (path != null && path.length() > 0) {
                String fromPath = path.optString(path.length() - 1, "").trim();
                if (!fromPath.isEmpty()) return fromPath;
            }
        }
        return "default";
    }

    private JSObject resolveInvokePayload(JSObject payload, JSONObject envelope) {
        if (payload == null) return new JSObject();
        if (envelope == null) return payload;
        Object envelopePayload = envelope.opt(ENVELOPE_PAYLOAD_KEY);
        if (envelopePayload instanceof JSONObject) {
            return jsObjectFromJson((JSONObject) envelopePayload);
        }
        Object envelopeData = envelope.opt(ENVELOPE_DATA_KEY);
        if (envelopeData instanceof JSONObject) {
            return jsObjectFromJson((JSONObject) envelopeData);
        }
        return payload;
    }

    private JSObject buildBridgeEnvelope(String type, String channel, JSObject payload, JSONObject requestEnvelope) {
        JSObject envelope = new JSObject();
        safePut(envelope, "purpose", new JSONArray().put("invoke"));
        safePut(envelope, "protocol", "service");
        safePut(envelope, "type", type);
        safePut(envelope, "op", "invoke");
        safePut(envelope, "path", new JSONArray().put("cws-bridge").put(channel));
        safePut(envelope, "srcChannel", "native");
        safePut(envelope, "dstChannel", "webview");
        safePut(envelope, "payload", payload != null ? payload : new JSObject());
        safePut(envelope, "data", payload != null ? payload : new JSObject());
        safePut(envelope, "timestamp", System.currentTimeMillis());
        safePut(envelope, "uuid", resolveEnvelopeUuid(requestEnvelope));
        return envelope;
    }

    private String resolveEnvelopeUuid(JSONObject requestEnvelope) {
        if (requestEnvelope != null) {
            String incoming = requestEnvelope.optString(ENVELOPE_UUID_KEY, "").trim();
            if (!incoming.isEmpty()) return incoming;
        }
        return "bridge-" + System.currentTimeMillis();
    }

    private JSONObject readNativeSettingsJson() {
        String raw = getContext()
            .getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
            .getString(PREF_KEY, "");
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private void writeNativeSettingsJson(JSONObject settings) {
        getContext()
            .getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, settings.toString())
            .apply();
    }

    private JSObject buildAppSettings(JSONObject nativeSettings) {
        JSObject root = new JSObject();
        JSObject core = new JSObject();
        JSObject shell = new JSObject();
        JSObject network = new JSObject();
        JSObject socket = new JSObject();
        JSObject interop = new JSObject();

        String endpointUrl = firstNonBlank(
            nativeSettings.optString("hubDispatchUrl", ""),
            nativeSettings.optString("apiEndpoint", "")
        );
        String userId = nativeSettings.optString("hubClientId", "");
        String userKey = firstNonBlank(
            nativeSettings.optString("hubToken", ""),
            nativeSettings.optString("authToken", "")
        );

        core.put("mode", "endpoint");
        core.put("endpointUrl", endpointUrl);
        core.put("userId", userId);
        core.put("userKey", userKey);
        core.put("allowInsecureTls", nativeSettings.optBoolean("allowInsecureTls", false));
        network.put("listenPortHttps", nativeSettings.optInt("listenPortHttps", 8443));
        network.put("listenPortHttp", nativeSettings.optInt("listenPortHttp", 8080));
        network.put("bridgeEnabled", true);
        network.put("reconnectMs", nativeSettings.optInt("syncIntervalSec", 3) * 1000);

        JSONArray destinations = nativeSettings.optJSONArray("destinations");
        if (destinations != null && destinations.length() > 0) {
            JSObject ops = new JSObject();
            ops.put("syncTargets", destinations);
            core.put("ops", ops);
            network.put("destinations", destinations);
        }

        socket.put("protocol", nativeSettings.optString("cwsSocketProtocol", "auto"));
        socket.put("routeTarget", nativeSettings.optString("cwsSocketRouteTarget", ""));
        socket.put("transportMode", nativeSettings.optString("cwsSocketTransportMode", "plaintext"));
        socket.put("transportSecret", nativeSettings.optString("cwsSocketTransportSecret", ""));
        socket.put("signingSecret", nativeSettings.optString("cwsSocketSigningSecret", ""));

        interop.put("ipcProtocol", nativeSettings.optString("cwsInteropProtocol", "uniform"));
        interop.put("platformInterop", nativeSettings.optBoolean("cwsInteropEnabled", true));
        interop.put("preferNativeIpc", nativeSettings.optBoolean("cwsInteropPreferNativeIpc", true));
        interop.put("preferNativeWebsocket", nativeSettings.optBoolean("cwsInteropPreferNativeWebsocket", true));
        core.put("network", network);
        core.put("socket", socket);
        core.put("interop", interop);

        shell.put("enableRemoteClipboardBridge", nativeSettings.optBoolean("clipboardSync", true));
        shell.put("enableNativeSms", nativeSettings.optBoolean("smsSync", false));
        shell.put("enableNativeContacts", nativeSettings.optBoolean("contactsSync", false));
        shell.put("preferNativeWebsocket", true);
        shell.put("maintainHubSocketConnection", false);

        root.put("core", core);
        root.put("shell", shell);
        return root;
    }

    private JSONObject buildNativePatch(JSONObject appSettings) {
        JSONObject patch = new JSONObject();
        JSONObject core = appSettings.optJSONObject("core");
        JSONObject shell = appSettings.optJSONObject("shell");
        if (core != null) {
            putIfPresentString(core, "endpointUrl", patch, "hubDispatchUrl");
            putIfPresentString(core, "endpointUrl", patch, "apiEndpoint");
            putIfPresentString(core, "userId", patch, "hubClientId");
            if (core.has("userKey")) {
                String userKey = core.optString("userKey", "").trim();
                if (!userKey.isEmpty()) {
                    safePut(patch, "hubToken", userKey);
                    safePut(patch, "authToken", userKey);
                }
            }
            if (core.has("allowInsecureTls")) {
                safePut(patch, "allowInsecureTls", core.optBoolean("allowInsecureTls", false));
            }
            JSONObject network = core.optJSONObject("network");
            if (network != null) {
                if (network.has("listenPortHttps")) {
                    safePut(patch, "listenPortHttps", network.optInt("listenPortHttps", 8443));
                }
                if (network.has("listenPortHttp")) {
                    safePut(patch, "listenPortHttp", network.optInt("listenPortHttp", 8080));
                }
                if (network.has("reconnectMs")) {
                    safePut(patch, "syncIntervalSec", Math.max(1, network.optInt("reconnectMs", 3000) / 1000));
                }
                if (network.has("destinations")) {
                    List<String> destinations = extractStringList(network.opt("destinations"));
                    if (!destinations.isEmpty()) {
                        safePut(patch, "destinations", new JSONArray(destinations));
                    }
                }
            }
            JSONObject socket = core.optJSONObject("socket");
            if (socket != null) {
                putIfPresentString(socket, "protocol", patch, "cwsSocketProtocol");
                putIfPresentString(socket, "routeTarget", patch, "cwsSocketRouteTarget");
                putIfPresentString(socket, "transportMode", patch, "cwsSocketTransportMode");
                putIfPresentString(socket, "transportSecret", patch, "cwsSocketTransportSecret");
                putIfPresentString(socket, "signingSecret", patch, "cwsSocketSigningSecret");
            }
            JSONObject interop = core.optJSONObject("interop");
            if (interop != null) {
                putIfPresentString(interop, "ipcProtocol", patch, "cwsInteropProtocol");
                if (interop.has("platformInterop")) {
                    safePut(patch, "cwsInteropEnabled", interop.optBoolean("platformInterop", true));
                }
                if (interop.has("preferNativeIpc")) {
                    safePut(patch, "cwsInteropPreferNativeIpc", interop.optBoolean("preferNativeIpc", true));
                }
                if (interop.has("preferNativeWebsocket")) {
                    safePut(patch, "cwsInteropPreferNativeWebsocket", interop.optBoolean("preferNativeWebsocket", true));
                }
            }
            JSONObject ops = core.optJSONObject("ops");
            if (ops != null && ops.has("syncTargets")) {
                List<String> targets = extractStringList(ops.opt("syncTargets"));
                if (!targets.isEmpty()) {
                    safePut(patch, "destinations", new JSONArray(targets));
                }
            }
        }
        if (shell != null) {
            if (shell.has("enableRemoteClipboardBridge")) {
                safePut(patch, "clipboardSync", shell.optBoolean("enableRemoteClipboardBridge", true));
            }
            if (shell.has("enableNativeSms")) {
                safePut(patch, "smsSync", shell.optBoolean("enableNativeSms", false));
            }
            if (shell.has("enableNativeContacts")) {
                safePut(patch, "contactsSync", shell.optBoolean("enableNativeContacts", false));
            }
        }
        return patch;
    }

    private void putIfPresentString(JSONObject from, String fromKey, JSONObject to, String toKey) {
        if (!from.has(fromKey)) return;
        String value = from.optString(fromKey, "").trim();
        if (!value.isEmpty()) {
            safePut(to, toKey, value);
        }
    }

    private List<String> extractStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i += 1) {
                String entry = array.optString(i, "").trim();
                if (!entry.isEmpty() && !out.contains(entry)) out.add(entry);
            }
            return out;
        }
        if (value instanceof String) {
            String raw = ((String) value).trim();
            if (raw.isEmpty()) return out;
            String[] parts = raw.split("[,;\\n]");
            for (String part : parts) {
                String entry = part.trim();
                if (!entry.isEmpty() && !out.contains(entry)) out.add(entry);
            }
        }
        return out;
    }

    private void mergeInto(JSONObject base, JSONObject patch) {
        JSONArray names = patch.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i += 1) {
            String key = names.optString(i, "");
            if (key.isEmpty()) continue;
            safePut(base, key, patch.opt(key));
        }
    }

    private void safePut(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException ignored) {
            // ignore malformed values and preserve best-effort sync
        }
    }

    private void safePut(JSObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception ignored) {
            // ignore malformed values and preserve best-effort sync
        }
    }

    private JSObject jsObjectFromJson(JSONObject source) {
        try {
            return new JSObject(source.toString());
        } catch (JSONException ignored) {
            return new JSObject();
        }
    }

    private String firstNonBlank(String left, String right) {
        String a = left == null ? "" : left.trim();
        if (!a.isEmpty()) return a;
        String b = right == null ? "" : right.trim();
        return b;
    }
}
