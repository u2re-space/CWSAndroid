import { ApplicationSettings } from "@nativescript/core";

export type AutomataSettings = {
    endpointHttp: string; // e.g. http://192.168.1.10:8081 (socket.io uses http(s))
    deviceId: string;
    userId: string;
    userKey: string;

    encryptPayloads: boolean;
    encryptionKey: string; // user-provided symmetric key (string; derived to AES key)

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
    // Default to LAN host (works from emulator/device); change if your server differs.
    endpointHttp: "http://192.168.0.200:8080",
    deviceId: randomId(),
    userId: "",
    userKey: "",

    encryptPayloads: false,
    encryptionKey: "",

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

        // One-time migrations:
        // - older builds defaulted to localhost/127.0.0.1 which won't work from emulator/device
        // - earlier LAN default used :8081; current default is :8080
        const ep = (merged.endpointHttp || "").trim();
        if (
            !ep ||
            ep === "http://127.0.0.1:8081" ||
            ep === "http://localhost:8081" ||
            ep === "http://127.0.0.1:8080" ||
            ep === "http://localhost:8080" ||
            ep === "http://192.168.0.200:8081"
        ) {
            merged.endpointHttp = defaultSettings().endpointHttp;
            ApplicationSettings.setString(KEY, JSON.stringify(merged));
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


