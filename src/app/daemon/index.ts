import { Application } from "@nativescript/core";
import { Clipboard } from "@nativescript-use/nativescript-clipboard";

import { log, setLogLevel } from "./logger";
import { loadSettings } from "./settings";
import { connectSocket, type AutomataMessage } from "./socket";
import { encryptJson, decryptJson } from "./crypto";
import { createClipboardWatcher } from "./clipboard";
import { readContacts } from "./contacts";
import { readSmsInbox } from "./sms";

interface Daemon {
    start(): Promise<void>;
    stop(): Promise<void>;
}

export const createDaemon = (): Daemon => {
    let settings = loadSettings();
    setLogLevel(settings.logLevel);

    const clipboard = new Clipboard();

    let socketClient: ReturnType<typeof connectSocket> | null = null;
    let clipboardWatcher: ReturnType<typeof createClipboardWatcher> | null = null;
    let syncTimer: any = null;

    const buildPayload = async (data: any): Promise<any> => {
        if (!settings.encryptPayloads) return data;
        if (!settings.encryptionKey?.trim()) return data;
        return await encryptJson(data, settings.encryptionKey.trim());
    };

    const tryDecrypt = async (payload: any): Promise<any> => {
        if (typeof payload !== "string") return payload;
        if (!settings.encryptPayloads) return payload;
        if (!settings.encryptionKey?.trim()) return payload;
        try {
            return await decryptJson(payload, settings.encryptionKey.trim());
        } catch (e) {
            log.warn("decrypt failed", e);
            return payload;
        }
    };

    const send = async (msg: Omit<AutomataMessage, "from">) => {
        if (!socketClient) return;
        const full: AutomataMessage = {
            ...msg,
            from: settings.deviceId,
        };
        socketClient.send(full);
    };

    const onIncomingMessage = async (msg: any) => {
        if (!msg || typeof msg !== "object") return;
        if (msg.type === "clip" && msg.action === "setClipboard") {
            const inner = await tryDecrypt(msg.payload);
            const text = inner?.data ?? inner;
            if (typeof text === "string" && text.trim()) {
                await clipboardWatcher?.setTextSilently(text);
                log.info("applied clipboard from network", (msg.from || "").toString());
            }
        }
    };

    const start = async () => {
        log.info("daemon starting");

        // Reload settings on every start so UI changes can apply without reinstalling.
        settings = loadSettings();
        setLogLevel(settings.logLevel);

        socketClient = connectSocket(settings);
        socketClient.socket.on("message", onIncomingMessage);

        if (settings.clipboardSync) {
            clipboardWatcher = createClipboardWatcher(clipboard as any, async (text) => {
                const payload = await buildPayload({ ts: Date.now(), data: text });
                await send({ type: "clip", to: "broadcast", mode: "blind", action: "setClipboard", payload });
                log.info("sent clipboard", text.length);
            });
            clipboardWatcher.start();
        }

        // Periodic sync (contacts/SMS)
        const tick = async () => {
            try {
                if (settings.contactsSync) {
                    const contacts = await readContacts();
                    const payload = await buildPayload({ ts: Date.now(), contacts });
                    await send({ type: "sync", to: "server", mode: "blind", action: "contacts", payload });
                    log.info("synced contacts", contacts.length);
                }
                if (settings.smsSync) {
                    const sms = await readSmsInbox(50);
                    const payload = await buildPayload({ ts: Date.now(), sms });
                    await send({ type: "sync", to: "server", mode: "blind", action: "sms", payload });
                    log.info("synced sms", sms.length);
                }
            } catch (e) {
                log.warn("sync tick failed", e);
            }
        };

        if (settings.contactsSync || settings.smsSync) {
            await tick();
            syncTimer = setInterval(tick, Math.max(10, settings.syncIntervalSec) * 1000);
        }

        // Share intents
        if (settings.shareTarget) {
            const onIntent = async (intent: any) => {
                try {
                    if (!intent) return;
                    const action = intent.getAction?.();
                    const type = intent.getType?.();
                    if (action === (android as any).content.Intent.ACTION_SEND && type === "text/plain") {
                        const extra = intent.getStringExtra?.((android as any).content.Intent.EXTRA_TEXT);
                        if (extra && typeof extra === "string" && extra.trim()) {
                            await clipboardWatcher?.setTextSilently(extra);
                            const payload = await buildPayload({ ts: Date.now(), data: extra, source: "share-intent" });
                            await send({ type: "clip", to: "broadcast", mode: "blind", action: "setClipboard", payload });
                            log.info("handled share intent");
                        }
                    }
                } catch (e) {
                    log.warn("intent handling failed", e);
                }
            };

            Application.android.on(Application.android.activityNewIntentEvent, (args: any) => onIntent(args?.intent));
            const activity = Application.android.foregroundActivity || Application.android.startActivity;
            if (activity?.getIntent) await onIntent(activity.getIntent());
        }

        log.info("daemon started");
    };

    const stop = async () => {
        log.info("daemon stopping");
        try { clipboardWatcher?.stop(); } catch { /* noop */ }
        clipboardWatcher = null;
        try { socketClient?.close(); } catch { /* noop */ }
        socketClient = null;
        if (syncTimer) { clearInterval(syncTimer); syncTimer = null; }
        log.info("daemon stopped");
    };

    return { start, stop };
};


