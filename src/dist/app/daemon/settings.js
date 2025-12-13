"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.saveSettings = exports.loadSettings = exports.defaultSettings = void 0;
const core_1 = require("@nativescript/core");
const KEY = "automata.settings.v1";
const randomId = () => `ns-${Math.random().toString(16).slice(2, 10)}`;
const defaultSettings = () => ({
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
exports.defaultSettings = defaultSettings;
const loadSettings = () => {
    try {
        const raw = core_1.ApplicationSettings.getString(KEY);
        if (!raw)
            return (0, exports.defaultSettings)();
        const parsed = JSON.parse(raw);
        const merged = { ...(0, exports.defaultSettings)(), ...(parsed || {}) };
        // One-time migrations:
        // - older builds defaulted to localhost/127.0.0.1 which won't work from emulator/device
        // - earlier LAN default used :8081; current default is :8080
        const ep = (merged.endpointHttp || "").trim();
        if (!ep ||
            ep === "http://127.0.0.1:8081" ||
            ep === "http://localhost:8081" ||
            ep === "http://127.0.0.1:8080" ||
            ep === "http://localhost:8080" ||
            ep === "http://192.168.0.200:8081") {
            merged.endpointHttp = (0, exports.defaultSettings)().endpointHttp;
            core_1.ApplicationSettings.setString(KEY, JSON.stringify(merged));
        }
        return merged;
    }
    catch {
        return (0, exports.defaultSettings)();
    }
};
exports.loadSettings = loadSettings;
const saveSettings = (next) => {
    const current = (0, exports.loadSettings)();
    const merged = { ...current, ...(next || {}) };
    core_1.ApplicationSettings.setString(KEY, JSON.stringify(merged));
    return merged;
};
exports.saveSettings = saveSettings;
