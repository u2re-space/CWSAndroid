import { Application } from "@nativescript/core";
import { Clipboard } from "@nativescript-use/nativescript-clipboard";

import { log, setLogLevel } from "./logger";
import { loadSettings } from "./settings";
import { createClipboardWatcher } from "./clipboard";
import { readContacts } from "./contacts";
import { readSmsInbox } from "./sms";
import { createLocalHttpServer, dispatchHttpRequests, sendSmsAndroid } from "./http-server";
import { postJson, postText } from "./http-client";

interface Daemon {
    start(): Promise<void>;
    stop(): Promise<void>;
}

export const createDaemon = (): Daemon => {
    let settings = loadSettings();
    setLogLevel(settings.logLevel);

    const clipboard = new Clipboard();

    let clipboardWatcher: ReturnType<typeof createClipboardWatcher> | null = null;
    let syncTimer: any = null;
    let httpServerHttp: ReturnType<typeof createLocalHttpServer> | null = null;
    let httpServerHttps: ReturnType<typeof createLocalHttpServer> | null = null;

    const setClipboardBestEffort = async (text: string) => {
        const t = (text || "").toString();
        if (!t) return;
        try {
            // Prefer Android native clipboard to avoid UI-thread/plugin issues.
            if (typeof globalThis !== "undefined" && !!(globalThis as any).android) {
                const activity = Application.android.foregroundActivity || Application.android.startActivity;
                const ctx = activity?.getApplicationContext?.();
                if (ctx) {
                    const cm = ctx.getSystemService((android as any).content.Context.CLIPBOARD_SERVICE);
                    const clip = (android as any).content.ClipData.newPlainText("clipboard", t);
                    cm?.setPrimaryClip?.(clip);
                    return;
                }
            }
        } catch {
            // fall through
        }
        try {
            await clipboard.copy(t);
        } catch {
            /* noop */
        }
    };

    const normalizeUrl = (raw: string, defaultPath: string): string | null => {
        const trimmed = (raw || "").trim();
        if (!trimmed) return null;

        // Allow host:port[/path] or full URL.
        // If user provides something like "192.168.0.200:8080/core/ops/http/dispatch",
        // we still treat it as HTTP by default.
        const withProto = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
        try {
            const u = new URL(withProto);

            // If user provided only base (no path or "/"), apply defaultPath.
            const path = (u.pathname || "").trim();
            const hasPath = path && path !== "/" && path !== "";
            if (!hasPath) {
                u.pathname = defaultPath.startsWith("/") ? defaultPath : `/${defaultPath}`;
            }

            return u.toString();
        } catch {
            return null;
        }
    };

    const postClipboardToPeers = async (text: string) => {
        const urls = (settings.destinations || [])
            .map((s) => normalizeUrl(s, "/clipboard"))
            .filter((u): u is string => !!u);
        if (urls.length === 0) return;

        const headers: Record<string, string> = {
            "Content-Type": "text/plain; charset=utf-8",
        };
        if (settings.authToken?.trim()) headers["x-auth-token"] = settings.authToken.trim();

        // Optional: send via hub dispatcher (MacroDroid-style /core/ops/http/dispatch).
        const hub = normalizeUrl(settings.hubDispatchUrl || "", "/core/ops/http/dispatch") || "";
        if (hub) {
            // Match MacroDroid schema exactly:
            // { "requests": [ { "url": "...", "body": "...", "unencrypted": true } ] }
            // (No method/headers fields; hub decides defaults.)
            const requests = urls.map((url) => ({ url, body: text, unencrypted: true }));
            try {
                const r = await postJson(hub, { requests }, !!settings.allowInsecureTls, 10000);
                if (!r.ok) {
                    log.warn("hub dispatch failed", hub, r.status, r.body);
                } else {
                    log.info("hub dispatch ok", hub, requests.length);
                }
            } catch (e) {
                const msg = (e as any)?.message || String(e);
                log.warn("hub dispatch error", hub, msg);
            }
            return;
        }

        await Promise.all(
            urls.map(async (url) => {
                try {
                    const r = await postText(url, text, headers, !!settings.allowInsecureTls, 10000);
                    if (!r.ok) {
                        log.warn("clipboard broadcast failed", url, r.status, r.body);
                    }
                } catch (e) {
                    log.warn("clipboard broadcast error", url, e);
                }
            })
        );
    };

    const start = async () => {
        log.info("daemon starting");

        // Reload settings on every start so UI changes can apply without reinstalling.
        settings = loadSettings();
        setLogLevel(settings.logLevel);

        // Start local HTTP listeners (for MacroDroid + peers).
        const serverCommon = {
            authToken: settings.authToken || "",
            tls: {
                enabled: !!settings.tlsEnabled,
                keystoreAssetPath: settings.tlsKeystoreAssetPath || "",
                keystoreType: settings.tlsKeystoreType || "PKCS12",
                keystorePassword: settings.tlsKeystorePassword || "",
            },
            setClipboardTextSync: (text: string) => {
                // Synchronous path for HTTP /clipboard response reliability (no async deadlocks).
                clipboardWatcher?.setTextSilentlySync(text);
            },
            setClipboardText: async (text: string) => {
                // Avoid feedback loop: update clipboard without triggering outgoing broadcast immediately.
                await clipboardWatcher?.setTextSilently(text);
            },
            dispatch: async (reqs: any[]) => dispatchHttpRequests(reqs),
            sendSms: async (number: string, content: string) => sendSmsAndroid(number, content),
        };

        httpServerHttp = createLocalHttpServer({
            port: settings.listenPortHttp,
            ...serverCommon,
            // never use TLS on the plain HTTP port
            tls: { enabled: false, keystoreAssetPath: "", keystoreType: "PKCS12", keystorePassword: "" },
        });
        httpServerHttps = createLocalHttpServer({
            port: settings.listenPortHttps,
            ...serverCommon,
        });
        httpServerHttp.start();
        httpServerHttps.start();

        if (settings.clipboardSync) {
            clipboardWatcher = createClipboardWatcher(clipboard as any, async (text) => {
                await postClipboardToPeers(text);
                log.info("broadcast clipboard", text.length, "to", (settings.destinations || []).length, "peers");
            });
            clipboardWatcher.start();
        }

        // Periodic sync (contacts/SMS)
        const tick = async () => {
            try {
                if (settings.contactsSync) {
                    const contacts = await readContacts();
                    log.info("synced contacts", contacts.length);
                }
                if (settings.smsSync) {
                    const sms = await readSmsInbox(50);
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
            const extractIntentText = (intent: any): string => {
                try {
                    const Intent = (android as any)?.content?.Intent;
                    const extraTextKey = Intent?.EXTRA_TEXT || "android.intent.extra.TEXT";
                    const extraProcessTextKey = Intent?.EXTRA_PROCESS_TEXT || "android.intent.extra.PROCESS_TEXT";

                    // Prefer explicit extras first.
                    const fromExtra =
                        intent.getStringExtra?.(extraTextKey) ||
                        intent.getStringExtra?.(extraProcessTextKey) ||
                        intent.getCharSequenceExtra?.(extraTextKey)?.toString?.() ||
                        intent.getCharSequenceExtra?.(extraProcessTextKey)?.toString?.();
                    if (typeof fromExtra === "string" && fromExtra.trim()) return fromExtra;

                    // Fallback: ClipData (some apps provide text here).
                    const cd = intent.getClipData?.();
                    if (cd && cd.getItemCount?.() > 0) {
                        const item = cd.getItemAt?.(0);
                        const txt = item?.coerceToText?.(Application.android.context)?.toString?.();
                        if (typeof txt === "string" && txt.trim()) return txt;
                    }
                } catch {
                    // ignore
                }
                return "";
            };

            const onIntent = async (intent: any) => {
                try {
                    if (!intent) return;
                    const action = intent.getAction?.();
                    const type = intent.getType?.();
                    const Intent = (android as any).content.Intent;
                    const isSend = action === Intent.ACTION_SEND && type === "text/plain";
                    const isProcessText = action === (Intent.ACTION_PROCESS_TEXT || "android.intent.action.PROCESS_TEXT") && type === "text/plain";
                    if (isSend || isProcessText) {
                        const text = extractIntentText(intent);
                        if (!text.trim()) return;

                        // Update local clipboard (best effort) then broadcast to peers.
                        if (clipboardWatcher) await clipboardWatcher.setTextSilently(text);
                        else await setClipboardBestEffort(text);
                        await postClipboardToPeers(text);
                        log.info("handled share/process-text intent broadcast");
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
        try { httpServerHttp?.stop(); } catch { /* noop */ }
        try { httpServerHttps?.stop(); } catch { /* noop */ }
        httpServerHttp = null;
        httpServerHttps = null;
        if (syncTimer) { clearInterval(syncTimer); syncTimer = null; }
        log.info("daemon stopped");
    };

    return { start, stop };
};


