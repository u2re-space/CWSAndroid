import { Application } from "@nativescript/core";
import { log } from "./logger";

declare const global: any;
declare const NativeClass: any;
declare const Interfaces: any;

type ClipboardApi = {
    read: () => Promise<string>;
    copy: (text: string) => Promise<void>;
};

export type ClipboardWatcher = {
    start: () => void;
    stop: () => void;
    setTextSilently: (text: string) => Promise<void>;
    setTextSilentlySync: (text: string) => void;
};

export const createClipboardWatcher = (clipboard: ClipboardApi, onChange: (text: string) => void): ClipboardWatcher => {
    let timer: any = null;
    let last = "";
    let suppressNext = false;
    let androidListener: any = null;
    let androidClipboard: any = null;

    const isAndroid = () => typeof globalThis !== "undefined" && !!(globalThis as any).android;

    const handle = async (text: string) => {
        const t = (text || "").toString();
        if (suppressNext) {
            suppressNext = false;
            last = t;
            return;
        }
        if (t !== last) {
            last = t;
            if (t.trim()) onChange(t);
        }
    };

    const poll = async () => {
        try {
            const text = (await clipboard.read()) || "";
            await handle(text);
        } catch (e) {
            log.warn("clipboard poll failed", e);
        }
    };

    const start = () => {
        if (timer) return;
        // Prefer native ClipboardManager listener on Android (more efficient than polling).
        if (isAndroid()) {
            try {
                const activity = Application.android.foregroundActivity || Application.android.startActivity;
                if (activity) {
                    const ctx = activity.getApplicationContext();
                    androidClipboard = ctx.getSystemService((android as any).content.Context.CLIPBOARD_SERVICE);
                    const Listener = (android as any).content.ClipboardManager.OnPrimaryClipChangedListener;

                    // IMPORTANT:
                    // Don't "extend the interface" (it causes com.tns.gen.* proxy class issues).
                    // Implement it via Interfaces() on a java.lang.Object subclass.
                    @NativeClass()
                    @Interfaces([Listener])
                    class ClipListenerImpl extends (java as any).lang.Object {
                        constructor() {
                            super();
                            return global.__native(this);
                        }
                        onPrimaryClipChanged() {
                            poll();
                        }
                    }

                    androidListener = new ClipListenerImpl();
                    androidClipboard.addPrimaryClipChangedListener(androidListener);
                    log.info("clipboard listener attached");
                }
            } catch (e) {
                log.warn("clipboard listener attach failed; falling back to polling", e);
                androidListener = null;
                androidClipboard = null;
            }
        }

        // Fallback polling (also helps on devices/OS versions where listener doesn't fire in background).
        timer = setInterval(poll, 900);
        poll();
    };

    const stop = () => {
        if (!timer) return;
        clearInterval(timer);
        timer = null;
        try {
            if (androidClipboard && androidListener) {
                androidClipboard.removePrimaryClipChangedListener(androidListener);
                log.info("clipboard listener detached");
            }
        } catch {
            // noop
        }
        androidListener = null;
        androidClipboard = null;
    };

    const setTextSilently = async (text: string) => {
        suppressNext = true;
        const t = (text || "").toString();
        // Prefer native clipboard API on Android. Do NOT require UI thread: this avoids stalls/ANRs in background.
        if (isAndroid()) {
            try {
                const activity = Application.android.foregroundActivity || Application.android.startActivity;
                const ctx = activity?.getApplicationContext?.() || activity;
                if (ctx) {
                    const cm = ctx.getSystemService((android as any).content.Context.CLIPBOARD_SERVICE);
                    const clip = (android as any).content.ClipData.newPlainText("clipboard", t);
                    cm.setPrimaryClip(clip);
                }
            } catch (e) {
                log.warn("native clipboard set failed; falling back to plugin", e);
                await clipboard.copy(t);
            }
        } else {
            await clipboard.copy(t);
        }
        last = t;
    };

    const setTextSilentlySync = (text: string) => {
        suppressNext = true;
        const t = (text || "").toString();
        if (isAndroid()) {
            try {
                const activity = Application.android.foregroundActivity || Application.android.startActivity;
                const ctx = activity?.getApplicationContext?.() || activity;
                if (ctx) {
                    const cm = ctx.getSystemService((android as any).content.Context.CLIPBOARD_SERVICE);
                    const clip = (android as any).content.ClipData.newPlainText("clipboard", t);
                    cm.setPrimaryClip(clip);
                }
            } catch (e) {
                log.warn("native clipboard set failed (sync)", e);
            }
        }
        last = t;
    };

    return { start, stop, setTextSilently, setTextSilentlySync };
};


