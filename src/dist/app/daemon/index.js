"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createDaemon = void 0;
const core_1 = require("@nativescript/core");
const nativescript_clipboard_1 = require("@nativescript-use/nativescript-clipboard");
const logger_1 = require("./logger");
const settings_1 = require("./settings");
const socket_1 = require("./socket");
const crypto_1 = require("./crypto");
const clipboard_1 = require("./clipboard");
const contacts_1 = require("./contacts");
const sms_1 = require("./sms");
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
