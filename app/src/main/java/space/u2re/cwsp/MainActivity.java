package space.u2re.cwsp;

import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

/**
 * Debug builds: allow Chrome/VS Code attach to the Capacitor WebView over ADB
 * ({@code adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>}), see
 * {@code runtime/cwsp/scripts/adb-forward-webview-debug.sh}.
 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }
}
