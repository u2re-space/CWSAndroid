import { ApplicationSettings } from "@nativescript/core";

export type AutomataSettings = {
    // Local listeners (used by MacroDroid / other devices)
    listenPortHttps: number; // default 8443 (we run plain HTTP on this port unless you add TLS)
    listenPortHttp: number;  // default 8080
    /**
     * Fan-out destinations (populated locally or pushed from backend).
     * Each entry may be a full URL (recommended) or a host:port/base URL.
     *
     * Examples:
     * - http://100.90.155.65:8080/clipboard
     * - http://100.90.155.65:8080
     */
    destinations: string[];
    deviceId: string;
    // Simple auth token shared across peers (maps to x-auth-token / Bearer in reference server)
    authToken: string;

    // TLS for local listener (optional). If enabled, the server on listenPortHttps will use TLS.
    tlsEnabled: boolean;
    // Keystore packaged in Android assets (e.g. "tls/server.p12" or "server.p12")
    tlsKeystoreAssetPath: string;
    // Usually "PKCS12" (".p12/.pfx") or "BKS"
    tlsKeystoreType: "PKCS12" | "BKS" | string;
    tlsKeystorePassword: string;

    /**
     * Optional hub dispatcher endpoint (MacroDroid-style).
     * If set, clipboard fanout is sent as POST {requests:[...]} to this URL instead of direct-to-peers.
     *
     * Example:
     * - http://192.168.0.200:8080/core/ops/http/dispatch
     */
    hubDispatchUrl: string;

    clipboardSync: boolean;
    contactsSync: boolean;
    smsSync: boolean;
    shareTarget: boolean;

    logLevel: "debug" | "info" | "warn" | "error";
    syncIntervalSec: number;
};

const KEY = "automata.settings.v1";

const randomId = () => `ns-${Math.random().toString(16).slice(2, 10)}`;

export const defaultSettings = (): AutomataSettings => ({
    listenPortHttps: 8443,
    listenPortHttp: 8080,
    destinations: [],
    deviceId: randomId(),
    authToken: "",
    tlsEnabled: false,
    tlsKeystoreAssetPath: "",
    tlsKeystoreType: "PKCS12",
    tlsKeystorePassword: "",
    hubDispatchUrl: "",

    clipboardSync: true,
    contactsSync: false,
    smsSync: false,
    shareTarget: true,

    logLevel: "debug",
    syncIntervalSec: 60,
});

export const loadSettings = (): AutomataSettings => {
    try {
        const raw = ApplicationSettings.getString(KEY);
        if (!raw) return defaultSettings();
        const parsed = JSON.parse(raw);
        const merged = { ...defaultSettings(), ...(parsed || {}) } as AutomataSettings;

        // Migrations: carry old "userKey" into authToken if present
        if (!(merged as any).authToken && typeof (merged as any).userKey === "string") {
            merged.authToken = ((merged as any).userKey || "").trim();
        }
        // Ensure ports are sane
        if (!merged.listenPortHttp || merged.listenPortHttp < 1) merged.listenPortHttp = defaultSettings().listenPortHttp;
        if (!merged.listenPortHttps || merged.listenPortHttps < 1) merged.listenPortHttps = defaultSettings().listenPortHttps;

        // Ensure TLS fields exist
        if (typeof (merged as any).tlsEnabled !== "boolean") merged.tlsEnabled = defaultSettings().tlsEnabled;
        if (typeof (merged as any).tlsKeystoreAssetPath !== "string") merged.tlsKeystoreAssetPath = defaultSettings().tlsKeystoreAssetPath;
        if (typeof (merged as any).tlsKeystoreType !== "string") merged.tlsKeystoreType = defaultSettings().tlsKeystoreType;
        if (typeof (merged as any).tlsKeystorePassword !== "string") merged.tlsKeystorePassword = defaultSettings().tlsKeystorePassword;

        // Ensure destinations is always a string[]
        if (!Array.isArray((merged as any).destinations)) {
            (merged as any).destinations = [];
            ApplicationSettings.setString(KEY, JSON.stringify(merged));
        } else {
            merged.destinations = (merged.destinations || [])
                .map((s) => (typeof s === "string" ? s.trim() : ""))
                .filter(Boolean);
        }

        return merged;
    } catch {
        return defaultSettings();
    }
};

export const saveSettings = (next: Partial<AutomataSettings>): AutomataSettings => {
    const current = loadSettings();
    const merged = { ...current, ...(next || {}) } as AutomataSettings;
    ApplicationSettings.setString(KEY, JSON.stringify(merged));
    return merged;
};


