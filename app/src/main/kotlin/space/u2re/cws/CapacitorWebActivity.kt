package space.u2re.cws

import android.os.Bundle
import android.webkit.WebSettings
import com.getcapacitor.BridgeActivity

/**
 * Embedded CWSP web UI (Capacitor `dist/capacitor` assets), alongside the Compose shell.
 * App origin is `https://` while LAN cwsp is usually `http://host:8080` — allow mixed content so
 * Socket.IO can use `ws:` to the HTTP endpoint (see AirPad transport in CrossWord).
 *
 * [BridgeActivity] may not expose [bridge]/WebView synchronously in [onCreate]; re-apply after layout
 * and on resume so `ws:` to LAN is not blocked as mixed content.
 */
class CapacitorWebActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleApplyMixedContentMode()
    }

    override fun onResume() {
        super.onResume()
        scheduleApplyMixedContentMode()
    }

    private fun scheduleApplyMixedContentMode() {
        val decor = window?.decorView
        if (decor != null) {
            decor.post { applyMixedContentMode() }
        } else {
            applyMixedContentMode()
        }
    }

    private fun applyMixedContentMode() {
        try {
            bridge?.getWebView()?.settings?.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        } catch (_: Throwable) {
            /* bridge/webView timing differs by Capacitor version */
        }
    }
}
