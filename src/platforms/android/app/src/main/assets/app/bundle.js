(() => {
var exports = {};
exports.id = "bundle";
exports.ids = ["bundle"];
exports.modules = {

/***/ "./app sync recursive \\.(xml%7Cjs%7C(?<%21\\.d\\.)ts%7Cs?css)$":
/***/ ((module, __unused_webpack_exports, __webpack_require__) => {

var map = {
	"./app.ts": "./app/app.ts",
	"./daemon/android-permissions.ts": "./app/daemon/android-permissions.ts",
	"./daemon/clipboard.ts": "./app/daemon/clipboard.ts",
	"./daemon/contacts.ts": "./app/daemon/contacts.ts",
	"./daemon/crypto.ts": "./app/daemon/crypto.ts",
	"./daemon/index.ts": "./app/daemon/index.ts",
	"./daemon/logger.ts": "./app/daemon/logger.ts",
	"./daemon/settings.ts": "./app/daemon/settings.ts",
	"./daemon/sms.ts": "./app/daemon/sms.ts",
	"./daemon/socket.ts": "./app/daemon/socket.ts",
	"./file-utils.ts": "./app/file-utils.ts",
	"./host-daemon.ts": "./app/host-daemon.ts",
	"./main.ts": "./app/main.ts"
};


function webpackContext(req) {
	var id = webpackContextResolve(req);
	return __webpack_require__(id);
}
function webpackContextResolve(req) {
	if(!__webpack_require__.o(map, req)) {
		var e = new Error("Cannot find module '" + req + "'");
		e.code = 'MODULE_NOT_FOUND';
		throw e;
	}
	return map[req];
}
webpackContext.keys = function webpackContextKeys() {
	return Object.keys(map);
};
webpackContext.resolve = webpackContextResolve;
module.exports = webpackContext;
webpackContext.id = "./app sync recursive \\.(xml%7Cjs%7C(?<%21\\.d\\.)ts%7Cs?css)$";

/***/ }),

/***/ "./app/app.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.state = exports.restartDaemon = void 0;
const core_1 = __webpack_require__("./node_modules/@nativescript/core/index.js");
const daemon_1 = __webpack_require__("./app/daemon/index.ts");
const settings_1 = __webpack_require__("./app/daemon/settings.ts");
const logger_1 = __webpack_require__("./app/daemon/logger.ts");
const state = new core_1.Observable();
exports.state = state;
state.set("status", "starting");
const daemon = (0, daemon_1.createDaemon)();
core_1.Application.on(core_1.Application.launchEvent, async () => {
    const settings = (0, settings_1.loadSettings)();
    (0, logger_1.setLogLevel)(settings.logLevel);
    logger_1.log.info("app launch");
    try {
        await daemon.start();
        state.set("status", "running");
    }
    catch (e) {
        logger_1.log.error("daemon start failed", e);
        state.set("status", "error");
    }
});
core_1.Application.on(core_1.Application.exitEvent, async () => {
    logger_1.log.info("app exit");
    await daemon.stop();
});
const restartDaemon = async () => {
    state.set("status", "restarting");
    try {
        await daemon.stop();
        await daemon.start();
        state.set("status", "running");
    }
    catch (e) {
        logger_1.log.error("daemon restart failed", e);
        state.set("status", "error");
    }
};
exports.restartDaemon = restartDaemon;

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/android-permissions.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.ensureAndroidPermission = void 0;
const core_1 = __webpack_require__("./node_modules/@nativescript/core/index.js");
const logger_1 = __webpack_require__("./app/daemon/logger.ts");
const isAndroid = () => typeof globalThis !== "undefined" && !!globalThis.android;
const ensureAndroidPermission = async (permission) => {
    if (!isAndroid())
        return false;
    const activity = core_1.Application.android.foregroundActivity || core_1.Application.android.startActivity;
    if (!activity)
        return false;
    // android.content.pm.PackageManager.PERMISSION_GRANTED == 0
    const granted = android.content.pm.PackageManager.PERMISSION_GRANTED;
    const ctx = activity;
    const checkSelf = androidx?.core?.content?.ContextCompat?.checkSelfPermission;
    const requestPerms = androidx?.core?.app?.ActivityCompat?.requestPermissions;
    if (!checkSelf || !requestPerms) {
        logger_1.log.warn("androidx ContextCompat/ActivityCompat unavailable; cannot request permission", permission);
        return false;
    }
    const current = checkSelf(ctx, permission);
    if (current === granted)
        return true;
    return await new Promise((resolve) => {
        const REQ = Math.floor(Math.random() * 10000) + 1000;
        const handler = (args) => {
            if (args.requestCode !== REQ)
                return;
            core_1.Application.android.off(core_1.Application.android.activityRequestPermissionsEvent, handler);
            const results = args.grantResults || [];
            resolve(results.length > 0 && results[0] === granted);
        };
        core_1.Application.android.on(core_1.Application.android.activityRequestPermissionsEvent, handler);
        requestPerms(activity, [permission], REQ);
    });
};
exports.ensureAndroidPermission = ensureAndroidPermission;

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/clipboard.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.createClipboardWatcher = void 0;
const logger_1 = __webpack_require__("./app/daemon/logger.ts");
const createClipboardWatcher = (clipboard, onChange) => {
    let timer = null;
    let last = "";
    let suppressNext = false;
    const poll = async () => {
        try {
            const text = (await clipboard.read()) || "";
            if (suppressNext) {
                suppressNext = false;
                last = text;
                return;
            }
            if (text !== last) {
                last = text;
                if (text.trim())
                    onChange(text);
            }
        }
        catch (e) {
            logger_1.log.warn("clipboard poll failed", e);
        }
    };
    const start = () => {
        if (timer)
            return;
        timer = setInterval(poll, 750);
        poll();
    };
    const stop = () => {
        if (!timer)
            return;
        clearInterval(timer);
        timer = null;
    };
    const setTextSilently = async (text) => {
        suppressNext = true;
        await clipboard.copy(text);
        last = text;
    };
    return { start, stop, setTextSilently };
};
exports.createClipboardWatcher = createClipboardWatcher;

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/contacts.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.readContacts = void 0;
const core_1 = __webpack_require__("./node_modules/@nativescript/core/index.js");
const android_permissions_1 = __webpack_require__("./app/daemon/android-permissions.ts");
const logger_1 = __webpack_require__("./app/daemon/logger.ts");
const isAndroid = () => typeof globalThis !== "undefined" && !!globalThis.android;
const readContacts = async () => {
    if (!isAndroid())
        return [];
    const ok = await (0, android_permissions_1.ensureAndroidPermission)(android.Manifest.permission.READ_CONTACTS);
    if (!ok)
        return [];
    const activity = core_1.Application.android.foregroundActivity || core_1.Application.android.startActivity;
    if (!activity)
        return [];
    const resolver = activity.getContentResolver();
    const Contacts = android.provider.ContactsContract;
    const results = [];
    try {
        const cursor = resolver.query(Contacts.Contacts.CONTENT_URI, [], "", [], "");
        if (!cursor)
            return [];
        const idIdx = cursor.getColumnIndex(Contacts.Contacts._ID);
        const nameIdx = cursor.getColumnIndex(Contacts.Contacts.DISPLAY_NAME);
        const hasPhoneIdx = cursor.getColumnIndex(Contacts.Contacts.HAS_PHONE_NUMBER);
        while (cursor.moveToNext()) {
            const id = String(cursor.getString(idIdx));
            const name = String(cursor.getString(nameIdx) || "");
            const hasPhone = cursor.getInt(hasPhoneIdx) > 0;
            const phones = [];
            if (hasPhone) {
                const phoneCursor = resolver.query(Contacts.CommonDataKinds.Phone.CONTENT_URI, [], `${Contacts.CommonDataKinds.Phone.CONTACT_ID} = ?`, [id], "");
                if (phoneCursor) {
                    const numIdx = phoneCursor.getColumnIndex(Contacts.CommonDataKinds.Phone.NUMBER);
                    while (phoneCursor.moveToNext()) {
                        const num = String(phoneCursor.getString(numIdx) || "").trim();
                        if (num)
                            phones.push(num);
                    }
                    phoneCursor.close();
                }
            }
            const emails = [];
            const emailCursor = resolver.query(Contacts.CommonDataKinds.Email.CONTENT_URI, [], `${Contacts.CommonDataKinds.Email.CONTACT_ID} = ?`, [id], "");
            if (emailCursor) {
                const emailIdx = emailCursor.getColumnIndex(Contacts.CommonDataKinds.Email.DATA);
                while (emailCursor.moveToNext()) {
                    const e = String(emailCursor.getString(emailIdx) || "").trim();
                    if (e)
                        emails.push(e);
                }
                emailCursor.close();
            }
            results.push({ id, name, phones, emails });
        }
        cursor.close();
        return results;
    }
    catch (e) {
        logger_1.log.warn("readContacts failed", e);
        return results;
    }
};
exports.readContacts = readContacts;

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/crypto.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.decryptJson = exports.encryptJson = void 0;
const logger_1 = __webpack_require__("./app/daemon/logger.ts");
const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();
const b64encode = (bytes) => {
    // NativeScript on Android typically has global Buffer (Node polyfill), but not guaranteed.
    const anyGlobal = globalThis;
    if (anyGlobal.Buffer) {
        return anyGlobal.Buffer.from(bytes).toString("base64");
    }
    let s = "";
    for (let i = 0; i < bytes.length; i++)
        s += String.fromCharCode(bytes[i]);
    // @ts-ignore
    return typeof btoa === "function" ? btoa(s) : "";
};
const b64decode = (b64) => {
    const anyGlobal = globalThis;
    if (anyGlobal.Buffer) {
        return new Uint8Array(anyGlobal.Buffer.from(b64, "base64"));
    }
    // @ts-ignore
    const bin = typeof atob === "function" ? atob(b64) : "";
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++)
        out[i] = bin.charCodeAt(i);
    return out;
};
const sha256 = async (data) => {
    // @ts-ignore
    if (globalThis.crypto?.subtle?.digest)
        return await globalThis.crypto.subtle.digest("SHA-256", data);
    throw new Error("WebCrypto digest unavailable");
};
const importAesKey = async (keyMaterial) => {
    const raw = textEncoder.encode(keyMaterial);
    const hash = await sha256(raw);
    // @ts-ignore
    return await globalThis.crypto.subtle.importKey("raw", hash, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
};
const encryptJson = async (obj, keyMaterial) => {
    try {
        // @ts-ignore
        if (!globalThis.crypto?.subtle)
            throw new Error("WebCrypto subtle unavailable");
        const key = await importAesKey(keyMaterial);
        const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
        const pt = textEncoder.encode(JSON.stringify(obj));
        // @ts-ignore
        const ct = new Uint8Array(await globalThis.crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, pt));
        const payload = { v: 1, alg: "AES-256-GCM", ivB64: b64encode(iv), ctB64: b64encode(ct) };
        return b64encode(textEncoder.encode(JSON.stringify(payload)));
    }
    catch (e) {
        logger_1.log.warn("encryptJson failed, falling back to plaintext", e);
        return b64encode(textEncoder.encode(JSON.stringify({ v: 0, alg: "PLAINTEXT", data: obj })));
    }
};
exports.encryptJson = encryptJson;
const decryptJson = async (payloadB64, keyMaterial) => {
    const outer = JSON.parse(textDecoder.decode(b64decode(payloadB64)));
    if (outer?.v === 0 && outer?.alg === "PLAINTEXT")
        return outer.data;
    // @ts-ignore
    if (!globalThis.crypto?.subtle)
        throw new Error("WebCrypto subtle unavailable");
    const key = await importAesKey(keyMaterial);
    const iv = b64decode(outer.ivB64);
    const ct = b64decode(outer.ctB64);
    // @ts-ignore
    const pt = new Uint8Array(await globalThis.crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ct));
    return JSON.parse(textDecoder.decode(pt));
};
exports.decryptJson = decryptJson;

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/index.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.createDaemon = void 0;
const core_1 = __webpack_require__("./node_modules/@nativescript/core/index.js");
const nativescript_clipboard_1 = __webpack_require__("./node_modules/@nativescript-use/nativescript-clipboard/index.android.js");
const logger_1 = __webpack_require__("./app/daemon/logger.ts");
const settings_1 = __webpack_require__("./app/daemon/settings.ts");
const socket_1 = __webpack_require__("./app/daemon/socket.ts");
const crypto_1 = __webpack_require__("./app/daemon/crypto.ts");
const clipboard_1 = __webpack_require__("./app/daemon/clipboard.ts");
const contacts_1 = __webpack_require__("./app/daemon/contacts.ts");
const sms_1 = __webpack_require__("./app/daemon/sms.ts");
const createDaemon = () => {
    let settings = (0, settings_1.loadSettings)();
    (0, logger_1.setLogLevel)(settings.logLevel);
    const clipboard = new nativescript_clipboard_1.Clipboard();
    let socketClient = null;
    let clipboardWatcher = null;
    let syncTimer = null;
    const buildPayload = async (data) => {
        if (!settings.encryptPayloads)
            return data;
        if (!settings.encryptionKey?.trim())
            return data;
        return await (0, crypto_1.encryptJson)(data, settings.encryptionKey.trim());
    };
    const tryDecrypt = async (payload) => {
        if (typeof payload !== "string")
            return payload;
        if (!settings.encryptPayloads)
            return payload;
        if (!settings.encryptionKey?.trim())
            return payload;
        try {
            return await (0, crypto_1.decryptJson)(payload, settings.encryptionKey.trim());
        }
        catch (e) {
            logger_1.log.warn("decrypt failed", e);
            return payload;
        }
    };
    const send = async (msg) => {
        if (!socketClient)
            return;
        const full = {
            ...msg,
            from: settings.deviceId,
        };
        socketClient.send(full);
    };
    const onIncomingMessage = async (msg) => {
        if (!msg || typeof msg !== "object")
            return;
        if (msg.type === "clip" && msg.action === "setClipboard") {
            const inner = await tryDecrypt(msg.payload);
            const text = inner?.data ?? inner;
            if (typeof text === "string" && text.trim()) {
                await clipboardWatcher?.setTextSilently(text);
                logger_1.log.info("applied clipboard from network", (msg.from || "").toString());
            }
        }
    };
    const start = async () => {
        logger_1.log.info("daemon starting");
        // Reload settings on every start so UI changes can apply without reinstalling.
        settings = (0, settings_1.loadSettings)();
        (0, logger_1.setLogLevel)(settings.logLevel);
        socketClient = (0, socket_1.connectSocket)(settings);
        socketClient.socket.on("message", onIncomingMessage);
        if (settings.clipboardSync) {
            clipboardWatcher = (0, clipboard_1.createClipboardWatcher)(clipboard, async (text) => {
                const payload = await buildPayload({ ts: Date.now(), data: text });
                await send({ type: "clip", to: "broadcast", mode: "blind", action: "setClipboard", payload });
                logger_1.log.info("sent clipboard", text.length);
            });
            clipboardWatcher.start();
        }
        // Periodic sync (contacts/SMS)
        const tick = async () => {
            try {
                if (settings.contactsSync) {
                    const contacts = await (0, contacts_1.readContacts)();
                    const payload = await buildPayload({ ts: Date.now(), contacts });
                    await send({ type: "sync", to: "server", mode: "blind", action: "contacts", payload });
                    logger_1.log.info("synced contacts", contacts.length);
                }
                if (settings.smsSync) {
                    const sms = await (0, sms_1.readSmsInbox)(50);
                    const payload = await buildPayload({ ts: Date.now(), sms });
                    await send({ type: "sync", to: "server", mode: "blind", action: "sms", payload });
                    logger_1.log.info("synced sms", sms.length);
                }
            }
            catch (e) {
                logger_1.log.warn("sync tick failed", e);
            }
        };
        if (settings.contactsSync || settings.smsSync) {
            await tick();
            syncTimer = setInterval(tick, Math.max(10, settings.syncIntervalSec) * 1000);
        }
        // Share intents
        if (settings.shareTarget) {
            const onIntent = async (intent) => {
                try {
                    if (!intent)
                        return;
                    const action = intent.getAction?.();
                    const type = intent.getType?.();
                    if (action === android.content.Intent.ACTION_SEND && type === "text/plain") {
                        const extra = intent.getStringExtra?.(android.content.Intent.EXTRA_TEXT);
                        if (extra && typeof extra === "string" && extra.trim()) {
                            await clipboardWatcher?.setTextSilently(extra);
                            const payload = await buildPayload({ ts: Date.now(), data: extra, source: "share-intent" });
                            await send({ type: "clip", to: "broadcast", mode: "blind", action: "setClipboard", payload });
                            logger_1.log.info("handled share intent");
                        }
                    }
                }
                catch (e) {
                    logger_1.log.warn("intent handling failed", e);
                }
            };
            core_1.Application.android.on(core_1.Application.android.activityNewIntentEvent, (args) => onIntent(args?.intent));
            const activity = core_1.Application.android.foregroundActivity || core_1.Application.android.startActivity;
            if (activity?.getIntent)
                await onIntent(activity.getIntent());
        }
        logger_1.log.info("daemon started");
    };
    const stop = async () => {
        logger_1.log.info("daemon stopping");
        try {
            clipboardWatcher?.stop();
        }
        catch { /* noop */ }
        clipboardWatcher = null;
        try {
            socketClient?.close();
        }
        catch { /* noop */ }
        socketClient = null;
        if (syncTimer) {
            clearInterval(syncTimer);
            syncTimer = null;
        }
        logger_1.log.info("daemon stopped");
    };
    return { start, stop };
};
exports.createDaemon = createDaemon;

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/logger.ts":
/***/ ((module, exports) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.log = exports.setLogLevel = void 0;
const levelOrder = { debug: 10, info: 20, warn: 30, error: 40 };
let currentLevel = "debug";
const setLogLevel = (level) => {
    currentLevel = level;
};
exports.setLogLevel = setLogLevel;
const shouldLog = (level) => levelOrder[level] >= levelOrder[currentLevel];
exports.log = {
    debug: (...args) => shouldLog("debug") && console.log("[automata:debug]", ...args),
    info: (...args) => shouldLog("info") && console.log("[automata:info]", ...args),
    warn: (...args) => shouldLog("warn") && console.warn("[automata:warn]", ...args),
    error: (...args) => shouldLog("error") && console.error("[automata:error]", ...args),
};

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/settings.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.saveSettings = exports.loadSettings = exports.defaultSettings = void 0;
const core_1 = __webpack_require__("./node_modules/@nativescript/core/index.js");
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

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/sms.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.readSmsInbox = void 0;
const core_1 = __webpack_require__("./node_modules/@nativescript/core/index.js");
const android_permissions_1 = __webpack_require__("./app/daemon/android-permissions.ts");
const logger_1 = __webpack_require__("./app/daemon/logger.ts");
const isAndroid = () => typeof globalThis !== "undefined" && !!globalThis.android;
/**
 * Note: Android 10+ heavily restricts SMS access unless the app is the default SMS app.
 * We keep this best-effort for debugging and older devices.
 */
const readSmsInbox = async (limit = 50) => {
    if (!isAndroid())
        return [];
    const ok = await (0, android_permissions_1.ensureAndroidPermission)(android.Manifest.permission.READ_SMS);
    if (!ok)
        return [];
    const activity = core_1.Application.android.foregroundActivity || core_1.Application.android.startActivity;
    if (!activity)
        return [];
    const resolver = activity.getContentResolver();
    const uri = android.net.Uri.parse("content://sms");
    const results = [];
    try {
        const cursor = resolver.query(uri, [], "", [], "date DESC");
        if (!cursor)
            return [];
        const idIdx = cursor.getColumnIndex("_id");
        const addrIdx = cursor.getColumnIndex("address");
        const bodyIdx = cursor.getColumnIndex("body");
        const dateIdx = cursor.getColumnIndex("date");
        const typeIdx = cursor.getColumnIndex("type");
        while (cursor.moveToNext() && results.length < limit) {
            results.push({
                id: String(cursor.getString(idIdx)),
                address: String(cursor.getString(addrIdx) || ""),
                body: String(cursor.getString(bodyIdx) || ""),
                date: Number(cursor.getLong(dateIdx) || 0),
                type: Number(cursor.getInt(typeIdx) || 0),
            });
        }
        cursor.close();
        return results;
    }
    catch (e) {
        logger_1.log.warn("readSmsInbox failed", e);
        return results;
    }
};
exports.readSmsInbox = readSmsInbox;

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/daemon/socket.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.connectSocket = void 0;
// IMPORTANT: Use an exported browser bundle so NativeScript webpack doesn't pull Node transports (`ws`).
const socket_io_js_1 = __webpack_require__("./node_modules/socket.io-client/dist/socket.io.js");
const logger_1 = __webpack_require__("./app/daemon/logger.ts");
const normalizeEndpoint = (endpointHttp) => {
    const trimmed = (endpointHttp || "").trim();
    if (!trimmed)
        return "http://192.168.0.200:8080";
    if (/^https?:\/\//i.test(trimmed))
        return trimmed;
    return `http://${trimmed}`;
};
const connectSocket = (settings) => {
    const endpoint = normalizeEndpoint(settings.endpointHttp);
    const socket = (0, socket_io_js_1.io)(endpoint, {
        transports: ["websocket", "polling"],
        reconnection: true,
        reconnectionDelay: 1000,
        reconnectionAttempts: Infinity,
    });
    socket.on("connect", () => {
        logger_1.log.info("socket connected", socket.id, endpoint);
        socket.emit("hello", { id: settings.deviceId, userId: settings.userId, userKey: settings.userKey });
    });
    socket.on("disconnect", (reason) => logger_1.log.warn("socket disconnected", reason));
    socket.on("connect_error", (err) => logger_1.log.warn("socket connect_error", err?.message || err));
    socket.on("error", (err) => logger_1.log.warn("socket error", err));
    const send = (msg) => {
        socket.emit("message", msg);
    };
    const close = () => socket.close();
    return { socket, send, close };
};
exports.connectSocket = connectSocket;

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/file-utils.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

/**
 * File Utilities for NativeScript
 *
 * Provides functions to copy files from app assets to external storage
 */
Object.defineProperty(exports, "__esModule", ({ value: true }));
exports.copyDenoProxyFiles = copyDenoProxyFiles;
exports.denoProxyFilesExist = denoProxyFilesExist;
exports.getDenoProxyPath = getDenoProxyPath;
const core_1 = __webpack_require__("./node_modules/@nativescript/core/index.js");
/**
 * Copy deno-proxy files from assets to external storage
 *
 * Target: /storage/emulated/0/Android/data/com.u2re.automata/files/deno-proxy/
 *
 * @returns Promise that resolves when files are copied
 */
async function copyDenoProxyFiles() {
    try {
        // Get the app's external files directory
        // On Android, this should be: /storage/emulated/0/Android/data/com.u2re.automata/files/
        const externalFilesDir = getExternalFilesDirectory();
        const denoProxyDir = core_1.path.join(externalFilesDir, "deno-proxy");
        // Create deno-proxy directory if it doesn't exist
        if (!core_1.File.exists(denoProxyDir)) {
            try {
                // Get parent directory and folder name
                const lastSlash = denoProxyDir.lastIndexOf("/");
                if (lastSlash > 0) {
                    const parentDir = denoProxyDir.substring(0, lastSlash);
                    const folderName = denoProxyDir.substring(lastSlash + 1);
                    const parentFolder = core_1.Folder.fromPath(parentDir);
                    parentFolder.getFolder(folderName);
                }
            }
            catch (error) {
                console.warn(`[FileUtils] Could not create directory ${denoProxyDir}, continuing anyway:`, error);
            }
        }
        // Files to copy from assets
        const filesToCopy = [
            "main.ts",
            "connection.ts",
            "crypto-utils.ts",
            "deno.json"
        ];
        // Copy each file from assets to external storage
        for (const fileName of filesToCopy) {
            try {
                // Read from assets (assets are bundled in the app)
                // In NativeScript, assets are accessed via the app's internal assets
                // We need to use Android's AssetManager to read assets
                const assetContent = await readAssetFile(fileName);
                if (assetContent) {
                    const targetFile = core_1.File.fromPath(core_1.path.join(denoProxyDir, fileName));
                    await targetFile.writeText(assetContent);
                    console.log(`[FileUtils] Copied ${fileName} to ${denoProxyDir}`);
                }
                else {
                    console.warn(`[FileUtils] Could not read asset file: ${fileName}`);
                }
            }
            catch (error) {
                console.error(`[FileUtils] Error copying ${fileName}:`, error);
            }
        }
        console.log(`[FileUtils] Deno-proxy files copied to ${denoProxyDir}`);
    }
    catch (error) {
        console.error(`[FileUtils] Error copying deno-proxy files:`, error);
        throw error;
    }
}
/**
 * Read a file from Android assets
 *
 * @param fileName - Name of the file to read from assets
 * @returns Promise that resolves to file content as string, or null if not found
 */
async function readAssetFile(fileName) {
    try {
        // Use Android's AssetManager to read from assets
        const context = global.__native?.android?.currentContext ||
            global.__native?.android?.app?.context;
        if (!context) {
            console.warn("[FileUtils] Android context not available");
            return null;
        }
        const assetManager = context.getAssets();
        const inputStream = assetManager.open(`deno-proxy/${fileName}`);
        // Read the entire file
        const buffer = new ArrayBuffer(1024);
        let content = "";
        let bytesRead;
        while ((bytesRead = inputStream.read(buffer)) > 0) {
            const chunk = new Uint8Array(buffer, 0, bytesRead);
            content += String.fromCharCode.apply(null, Array.from(chunk));
        }
        inputStream.close();
        return content;
    }
    catch (error) {
        console.error(`[FileUtils] Error reading asset file ${fileName}:`, error);
        return null;
    }
}
/**
 * Check if deno-proxy files exist in external storage
 *
 * @returns Promise that resolves to true if all files exist, false otherwise
 */
async function denoProxyFilesExist() {
    try {
        const externalFilesDir = getExternalFilesDirectory();
        const denoProxyDir = core_1.path.join(externalFilesDir, "deno-proxy");
        const requiredFiles = [
            "main.ts",
            "connection.ts",
            "crypto-utils.ts",
            "deno.json"
        ];
        for (const fileName of requiredFiles) {
            const filePath = core_1.path.join(denoProxyDir, fileName);
            if (!core_1.File.exists(filePath)) {
                return false;
            }
        }
        return true;
    }
    catch (error) {
        console.error(`[FileUtils] Error checking deno-proxy files:`, error);
        return false;
    }
}
/**
 * Get the Android external files directory path
 *
 * @returns Path to external files directory
 */
function getExternalFilesDirectory() {
    try {
        // Use Android's getExternalFilesDir() to get the exact path
        const context = global.__native?.android?.currentContext ||
            global.__native?.android?.app?.context;
        if (context) {
            const externalFilesDir = context.getExternalFilesDir(null);
            if (externalFilesDir) {
                return externalFilesDir.getAbsolutePath();
            }
        }
        // Fallback to knownFolders if context is not available
        const externalFilesDir = core_1.knownFolders.externalDocuments();
        return externalFilesDir.path;
    }
    catch (error) {
        console.error("[FileUtils] Error getting external files directory:", error);
        // Fallback
        const externalFilesDir = core_1.knownFolders.externalDocuments();
        return externalFilesDir.path;
    }
}
/**
 * Get the path to the deno-proxy directory in external storage
 *
 * @returns Path to deno-proxy directory
 */
function getDenoProxyPath() {
    const externalFilesDir = getExternalFilesDirectory();
    return core_1.path.join(externalFilesDir, "deno-proxy");
}

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/host-daemon.ts":
/***/ ((module, exports) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));

/* NATIVESCRIPT-HOT-LOADER */
if(module.hot?.accept) {
	module.hot.accept()
}

/***/ }),

/***/ "./app/main.ts":
/***/ ((module, exports, __webpack_require__) => {

"use strict";

Object.defineProperty(exports, "__esModule", ({ value: true }));
const core_1 = __webpack_require__("./node_modules/@nativescript/core/index.js");
const app_1 = __webpack_require__("./app/app.ts");
const settings_1 = __webpack_require__("./app/daemon/settings.ts");
const buildHomePage = () => {
    const page = new core_1.Page();
    const root = new core_1.GridLayout();
    root.padding = 16;
    root.rows = "auto, auto, auto";
    const label = new core_1.Label();
    label.textWrap = true;
    label.text = "Automata daemon running…";
    label.marginBottom = 12;
    root.addChild(label);
    const status = new core_1.Label();
    status.textWrap = true;
    status.marginBottom = 12;
    status.text = `Status: ${app_1.state.get("status")}`;
    const onStateChange = () => {
        status.text = `Status: ${app_1.state.get("status")}`;
    };
    app_1.state.on(core_1.Observable.propertyChangeEvent, onStateChange);
    page.on(core_1.Page.unloadedEvent, () => {
        app_1.state.off(core_1.Observable.propertyChangeEvent, onStateChange);
    });
    core_1.GridLayout.setRow(status, 1);
    root.addChild(status);
    const settingsBtn = new core_1.Button();
    settingsBtn.text = "Settings";
    settingsBtn.on(core_1.Button.tapEvent, () => {
        page.frame?.navigate({ create: buildSettingsPage });
    });
    core_1.GridLayout.setRow(settingsBtn, 2);
    root.addChild(settingsBtn);
    page.content = root;
    return page;
};
const buildSettingsPage = () => {
    const page = new core_1.Page();
    // `actionBarTitle` isn't supported across all NativeScript versions; set title via ActionBar.
    if (page.actionBar)
        page.actionBar.title = "Settings";
    const scroll = new core_1.ScrollView();
    const root = new core_1.StackLayout();
    root.padding = 16;
    const title = new core_1.Label();
    title.text = "Connection";
    title.fontSize = 18;
    title.fontWeight = "bold";
    root.addChild(title);
    const settings = (0, settings_1.loadSettings)();
    const endpointLabel = new core_1.Label();
    endpointLabel.text = "Server URL (http(s)://host:port)";
    root.addChild(endpointLabel);
    const endpoint = new core_1.TextField();
    endpoint.hint = "http://192.168.0.200:8080";
    endpoint.text = settings.endpointHttp;
    endpoint.autocorrect = false;
    endpoint.autocapitalizationType = "none";
    root.addChild(endpoint);
    const authTitle = new core_1.Label();
    authTitle.text = "Auth (optional)";
    authTitle.fontSize = 18;
    authTitle.fontWeight = "bold";
    authTitle.marginTop = 8;
    root.addChild(authTitle);
    const userIdLabel = new core_1.Label();
    userIdLabel.text = "User ID";
    root.addChild(userIdLabel);
    const userId = new core_1.TextField();
    userId.hint = "e.g. alice";
    userId.text = settings.userId;
    userId.autocorrect = false;
    userId.autocapitalizationType = "none";
    root.addChild(userId);
    const userKeyLabel = new core_1.Label();
    userKeyLabel.text = "User Key / Token";
    root.addChild(userKeyLabel);
    const userKey = new core_1.TextField();
    userKey.hint = "secret";
    userKey.text = settings.userKey;
    userKey.secure = true;
    userKey.autocorrect = false;
    userKey.autocapitalizationType = "none";
    root.addChild(userKey);
    const cryptoTitle = new core_1.Label();
    cryptoTitle.text = "Encryption (optional)";
    cryptoTitle.fontSize = 18;
    cryptoTitle.fontWeight = "bold";
    cryptoTitle.marginTop = 8;
    root.addChild(cryptoTitle);
    const encryptRow = new core_1.GridLayout();
    encryptRow.columns = "*, auto";
    const encryptLabel = new core_1.Label();
    encryptLabel.text = "Encrypt payloads";
    core_1.GridLayout.setColumn(encryptLabel, 0);
    encryptRow.addChild(encryptLabel);
    const encryptSwitch = new core_1.Switch();
    encryptSwitch.checked = !!settings.encryptPayloads;
    core_1.GridLayout.setColumn(encryptSwitch, 1);
    encryptRow.addChild(encryptSwitch);
    root.addChild(encryptRow);
    const encryptionKeyLabel = new core_1.Label();
    encryptionKeyLabel.text = "Encryption Key";
    root.addChild(encryptionKeyLabel);
    const encryptionKey = new core_1.TextField();
    encryptionKey.hint = "symmetric key material";
    encryptionKey.text = settings.encryptionKey;
    encryptionKey.secure = true;
    encryptionKey.autocorrect = false;
    encryptionKey.autocapitalizationType = "none";
    root.addChild(encryptionKey);
    const status = new core_1.Label();
    status.text = "";
    status.color = new core_1.Color("#666666");
    status.marginTop = 8;
    root.addChild(status);
    const save = new core_1.Button();
    save.text = "Save & Reconnect";
    save.on(core_1.Button.tapEvent, async () => {
        const ep = (endpoint.text || "").trim();
        if (!ep) {
            status.text = "Server URL is required.";
            return;
        }
        // Persist settings (avoid logging secrets).
        (0, settings_1.saveSettings)({
            endpointHttp: ep,
            userId: (userId.text || "").trim(),
            userKey: (userKey.text || "").trim(),
            encryptPayloads: !!encryptSwitch.checked,
            encryptionKey: (encryptionKey.text || "").trim(),
        });
        status.text = "Saved. Reconnecting…";
        await (0, app_1.restartDaemon)();
        status.text = "Saved. Connection restarted.";
    });
    root.addChild(save);
    const close = new core_1.Button();
    close.text = "Close";
    close.on(core_1.Button.tapEvent, () => page.frame?.goBack());
    root.addChild(close);
    scroll.content = root;
    page.content = scroll;
    return page;
};
if (!global.__automataAppStarted) {
    global.__automataAppStarted = true;
    core_1.Application.run({
        create: () => {
            // Use a root Frame so navigation works reliably (Page-only roots often have no `page.frame`).
            const frame = new core_1.Frame();
            frame.navigate({ create: buildHomePage, clearHistory: true });
            return frame;
        },
    });
}


if (true) {
    let hash = __webpack_require__.h();
    let hmrBootEmittedSymbol = Symbol.for('HMRBootEmitted');
    let originalLiveSyncSymbol = Symbol.for('OriginalLiveSync');
    let hmrRuntimeLastLiveSyncSymbol = Symbol.for('HMRRuntimeLastLiveSync');
    const logVerbose = (title, ...info) => {
        if (false) // removed by dead control flow
{}
    };
    const setStatus = (hash, status, message, ...info) => {
        // format is important - CLI expects this exact format
        console.log(`[HMR][${hash}] ${status} | ${message}`);
        if (info === null || info === void 0 ? void 0 : info.length) {
            logVerbose('Additional Info', info);
        }
        // return true if operation was successful
        return status === 'success';
    };
    const applyOptions = {
        ignoreUnaccepted: false,
        ignoreDeclined: false,
        ignoreErrored: false,
        onDeclined(info) {
            setStatus(hash, 'failure', 'A module has been declined.', info);
        },
        onUnaccepted(info) {
            setStatus(hash, 'failure', 'A module has not been accepted.', info);
        },
        onAccepted(info) {
            // console.log('accepted', info)
            logVerbose('Module Accepted', info);
        },
        onDisposed(info) {
            // console.log('disposed', info)
            logVerbose('Module Disposed', info);
        },
        onErrored(info) {
            setStatus(hash, 'failure', 'A module has errored.', info);
        },
    };
    // Important: Keep as function and not fat arrow; at the moment hermes does not support them
    const checkAndApply = async function () {
        hash = __webpack_require__.h();
        const modules = await module.hot.check().catch((error) => {
            return setStatus(hash, 'failure', 'Failed to check.', error.message || error.stack);
        });
        if (!modules) {
            logVerbose('No modules to apply.');
            return false;
        }
        const appliedModules = await module.hot
            .apply(applyOptions)
            .catch((error) => {
            return setStatus(hash, 'failure', 'Failed to apply.', error.message || error.stack);
        });
        if (!appliedModules) {
            logVerbose('No modules applied.');
            return false;
        }
        return setStatus(hash, 'success', 'Successfully applied update.');
    };
    const requireExists = (path) => {
        try {
            global['require'](path);
            return true;
        }
        catch (err) {
            return false;
        }
    };
    const hasUpdate = () => {
        // Prefer platform-agnostic JS hot-update manifests; fall back to JSON
        // if needed. On iOS, the .hot-update.js files are present under the
        // app folder (see platforms/ios <app>/bundle.*.hot-update.js), while on
        // Android the JSON manifests are used by HMR. Checking JS first keeps
        // behavior correct for iOS without regressing Android.
        const candidates = [
            `~/bundle.${__webpack_require__.h()}.hot-update.js`,
            `~/runtime.${__webpack_require__.h()}.hot-update.js`,
            `~/bundle.${__webpack_require__.h()}.hot-update.json`,
            `~/runtime.${__webpack_require__.h()}.hot-update.json`,
        ];
        return candidates.some((path) => requireExists(path));
    };
    if (global.__onLiveSync !== global[hmrRuntimeLastLiveSyncSymbol]) {
        // we store the original liveSync here in case this code runs again
        // which happens when you module.hot.accept() the main file
        global[originalLiveSyncSymbol] = global.__onLiveSync;
    }
    global[hmrRuntimeLastLiveSyncSymbol] = async function () {
        logVerbose('LiveSync');
        if (!hasUpdate()) {
            return false;
        }
        if (!(await checkAndApply())) {
            return false;
        }
        await global[originalLiveSyncSymbol]();
    };
    global.__onLiveSync = global[hmrRuntimeLastLiveSyncSymbol];
    if (!global[hmrBootEmittedSymbol]) {
        global[hmrBootEmittedSymbol] = true;
        setStatus(hash, 'boot', 'HMR Enabled - waiting for changes...');
    }
}


/***/ }),

/***/ "~/package.json":
/***/ ((module) => {

"use strict";
module.exports = require("~/package.json");

/***/ })

};
;

// load runtime
var __webpack_require__ = require("./runtime.js");
__webpack_require__.C(exports);
var __webpack_exec__ = (moduleId) => (__webpack_require__(__webpack_require__.s = moduleId))
var __webpack_exports__ = __webpack_require__.X(0, ["vendor"], () => (__webpack_exec__("./node_modules/@nativescript/core/globals/index.js"), __webpack_exec__("./node_modules/@nativescript/webpack/dist/stubs/virtual-entry-typescript.js"), __webpack_exec__("./node_modules/@nativescript/core/bundle-entry-points.js"), __webpack_exec__("./app/main.ts"), __webpack_exec__("./node_modules/@nativescript/core/ui/frame/index.android.js"), __webpack_exec__("./node_modules/@nativescript/core/ui/frame/activity.android.js")));
var __webpack_export_target__ = exports;
for(var __webpack_i__ in __webpack_exports__) __webpack_export_target__[__webpack_i__] = __webpack_exports__[__webpack_i__];
if(__webpack_exports__.__esModule) Object.defineProperty(__webpack_export_target__, "__esModule", { value: true });

})();
//# sourceMappingURL=bundle.js.map