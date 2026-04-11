package space.u2re.cwsp.bridge;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Unified Capacitor bridge: TypeScript ({@code registerPlugin('CwsBridge')}) ↔ Android.
 * CWSAndroid (cwsp flavor) loads the same Web assets and Capacitor runtime; extend this class
 * there to forward {@link #invoke} to Kotlin/Compose if needed.
 */
@CapacitorPlugin(name = "CwsBridge")
public class CwsBridgePlugin extends Plugin {

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
        String channel = call.getString("channel", "default");
        JSObject payload = call.getObject("payload", new JSObject());
        JSObject result = new JSObject();
        result.put("ok", true);
        result.put("channel", channel);
        result.put("echo", payload != null ? payload : new JSObject());
        call.resolve(result);
    }

    /**
     * Optional: call from Kotlin to deliver events to JS ({@code addListener('nativeMessage', ...)}).
     */
    public void emitToWeb(JSObject data) {
        notifyListeners(EVENT_NATIVE_MESSAGE, data, true);
    }
}
