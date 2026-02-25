import { Application } from "@nativescript/core";
import { log } from "./logger";
import { ensureAndroidPermission } from "./android-permissions";

declare const global: any;
declare const java: any;
declare const fi: any;
declare const NativeClass: any;

type HttpServerHandle = {
    start: () => void;
    stop: () => void;
};

type DispatchRequest = {
    url: string;
    method?: string; // default POST
    headers?: Record<string, string>;
    body?: string;
    unencrypted?: boolean; // ignored on-device, kept for compatibility with MacroDroid configs
};

const isAndroid = () => typeof globalThis !== "undefined" && !!(globalThis as any).android;

const readBodyViaNano = (session: any): string => {
    // Correct way in NanoHTTPD: parseBody stores the raw body under "postData".
    // Reading from getInputStream() can block (HTTP/1.1 keep-alive), causing hub timeouts.
    const files = new java.util.HashMap();
    try {
        session.parseBody(files);
        const v = files.get("postData");
        return v ? String(v) : "";
    } catch (e) {
        // parseBody throws for empty bodies on some methods; treat as empty.
        return "";
    }
};

const safeStatus = (code: number) => {
    const NanoHTTPD = (fi as any).iki.elonen.NanoHTTPD;
    const st = NanoHTTPD?.Response?.Status?.lookup?.(code);
    return st || NanoHTTPD.Response.Status.INTERNAL_ERROR;
};

const json = (status: number, obj: any) => {
    const NanoHTTPD = (fi as any).iki.elonen.NanoHTTPD;
    const body = JSON.stringify(obj);
    const resp = NanoHTTPD.newFixedLengthResponse(
        safeStatus(status),
        "application/json; charset=utf-8",
        body
    );
    resp.addHeader("Access-Control-Allow-Origin", "*");
    resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    resp.addHeader("Access-Control-Allow-Headers", "*");
    return resp;
};

const text = (status: number, body: string) => {
    const NanoHTTPD = (fi as any).iki.elonen.NanoHTTPD;
    const resp = NanoHTTPD.newFixedLengthResponse(
        safeStatus(status),
        "text/plain; charset=utf-8",
        body
    );
    resp.addHeader("Access-Control-Allow-Origin", "*");
    resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    resp.addHeader("Access-Control-Allow-Headers", "*");
    return resp;
};

const getHeader = (headers: any, name: string): string | null => {
    if (!headers) return null;
    const lower = name.toLowerCase();
    // NanoHTTPD headers is java.util.Map<String,String> or JS object
    try {
        if (typeof headers.get === "function") {
            const v = headers.get(lower) || headers.get(name) || null;
            return v ? String(v) : null;
        }
    } catch {
        // ignore
    }
    const keys = Object.keys(headers);
    for (const k of keys) {
        if (k.toLowerCase() === lower) return String((headers as any)[k]);
    }
    return null;
};

const isAuthorized = (headers: any, token: string): boolean => {
    const secret = (token || "").trim();
    if (!secret) return true;

    const x = getHeader(headers, "x-auth-token") || getHeader(headers, "x-auth_token");
    if (x && x === secret) return true;
    const auth = getHeader(headers, "authorization");
    if (auth) {
        const m = auth.match(/^Bearer\s+(.+)$/i);
        if (m && m[1] === secret) return true;
    }
    return false;
};

export const createLocalHttpServer = (opts: {
    port: number;
    authToken: string;
    tls?: {
        enabled: boolean;
        keystoreAssetPath: string;
        keystoreType: string;
        keystorePassword: string;
    };
    setClipboardTextSync?: (text: string) => void;
    setClipboardText: (text: string) => Promise<void>;
    dispatch: (reqs: DispatchRequest[]) => Promise<any>;
    sendSms: (number: string, content: string) => Promise<void>;
}): HttpServerHandle => {
    let server: any | null = null;

    const start = () => {
        if (!isAndroid()) {
            log.warn("http server: not android");
            return;
        }
        if (server) return;

        const NanoHTTPD = (fi as any).iki.elonen.NanoHTTPD;

        const buildSslFactoryFromAssets = () => {
            const cfg = opts.tls;
            if (!cfg?.enabled) return null;
            const assetPath = (cfg.keystoreAssetPath || "").trim();
            const pass = (cfg.keystorePassword || "").toString();
            const type = (cfg.keystoreType || "PKCS12").toString();
            if (!assetPath) throw new Error("TLS enabled but tlsKeystoreAssetPath is empty");
            const activity = Application.android.foregroundActivity || Application.android.startActivity;
            if (!activity) throw new Error("No activity for assets");
            const ctx = activity.getApplicationContext();
            const ins = ctx.getAssets().open(assetPath);
            const ks = java.security.KeyStore.getInstance(type);
            const passChars = new java.lang.String(pass).toCharArray();
            ks.load(ins, passChars);
            ins.close();
            const kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, passChars);
            const tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            const sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new java.security.SecureRandom());
            return sc.getServerSocketFactory();
        };

        @NativeClass()
        class NsNanoServer extends NanoHTTPD {
            constructor(port: number) {
                super(port);
                return global.__native(this);
            }

            serve(session: any) {
                try {
                    const method = (session.getMethod?.()?.toString?.() || "").toUpperCase();
                    const uri = session.getUri?.()?.toString?.() || "/";
                    const headers = session.getHeaders?.();

                    // CORS preflight
                    if (method === "OPTIONS") return text(200, "OK");

                    if (uri === "/health") return json(200, { ok: true, port: opts.port });

                    if (method !== "POST") return text(405, "Method Not Allowed");

                    if (!isAuthorized(headers, opts.authToken)) return text(401, "Unauthorized");

                    // SPECIAL CASE: /clipboard must be synchronous, otherwise direct callers will time out
                    // (NanoHTTPD -> JS bridge + latch/promise can deadlock).
                    if (uri === "/clipboard") {
                        const body = readBodyViaNano(session);
                        const ct = (getHeader(headers, "content-type") || "").toLowerCase();
                        let textPayload = "";
                        if (ct.includes("application/json")) {
                            try {
                                const obj = JSON.parse(body || "{}");
                                if (obj && typeof obj.text === "string") textPayload = obj.text;
                            } catch {
                                // ignore
                            }
                        } else {
                            textPayload = body || "";
                        }
                        if (!textPayload || !String(textPayload).trim()) return text(400, "No text provided");
                        try {
                            (opts.setClipboardTextSync || (() => {}))(String(textPayload));
                            return json(200, { ok: true });
                        } catch (e: any) {
                            return json(500, { ok: false, error: String(e?.message || e) });
                        }
                    }

                    const respond = async () => {
                        const body = readBodyViaNano(session);

                        if (uri === "/core/ops/http/dispatch") {
                            let parsed: any = null;
                            try {
                                parsed = JSON.parse(body || "{}");
                            } catch {
                                return json(400, { ok: false, error: "Invalid JSON" });
                            }
                            const reqs = Array.isArray(parsed?.requests) ? parsed.requests : [];
                            const result = await opts.dispatch(reqs);
                            return json(200, { ok: true, result });
                        }

                        if (uri === "/sms") {
                            let parsed: any = null;
                            try {
                                parsed = JSON.parse(body || "{}");
                            } catch {
                                return json(400, { ok: false, error: "Invalid JSON" });
                            }
                            const number = String(parsed?.number || "");
                            const content = String(parsed?.content || "");
                            if (!number.trim() || !content.trim()) return json(400, { ok: false, error: "number/content required" });
                            await opts.sendSms(number, content);
                            return json(200, { ok: true });
                        }

                        return text(404, "Not Found");
                    };

                    // NanoHTTPD expects a Response synchronously; we block via a latch.
                    // This keeps implementation simple and avoids thread leaks.
                    const latch = new java.util.concurrent.CountDownLatch(1);
                    let resp: any = null;
                    let err: any = null;
                    respond()
                        .then((r) => {
                            resp = r;
                            latch.countDown();
                        })
                        .catch((e) => {
                            err = e;
                            latch.countDown();
                        });
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (err) {
                        log.warn("http handler error", uri, err);
                        return json(500, { ok: false, error: String(err?.message || err) });
                    }
                    // NanoHTTPD doesn't define all HTTP status codes (e.g. 504), so keep to common ones.
                    if (!resp) return json(503, { ok: false, error: "handler timeout" });
                    return resp;
                } catch (e) {
                    log.warn("http serve failed", e);
                    return json(500, { ok: false, error: String((e as any)?.message || e) });
                }
            }
        }

        server = new NsNanoServer(opts.port);
        try {
            const sslFactory = buildSslFactoryFromAssets();
            if (sslFactory) {
                server.makeSecure(sslFactory, null);
                log.info("http server TLS enabled", opts.port, opts.tls?.keystoreAssetPath);
            }
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            log.info("http server started", opts.port);
        } catch (e) {
            log.error("http server start failed", opts.port, e);
            server = null;
        }
    };

    const stop = () => {
        try {
            server?.stop?.();
        } catch {
            // noop
        }
        server = null;
    };

    return { start, stop };
};

export const dispatchHttpRequests = async (requests: DispatchRequest[]) => {
    const out: any[] = [];
    for (const r of requests || []) {
        try {
            const url = String(r?.url || "");
            if (!url) throw new Error("missing url");
            const method = String(r?.method || "POST").toUpperCase();
            const headers: Record<string, string> = { ...(r?.headers || {}) };
            const body = typeof r?.body === "string" ? r.body : "";
            if (!headers["Content-Type"] && !headers["content-type"]) headers["Content-Type"] = "text/plain; charset=utf-8";

            const resp = await fetch(url, { method, headers, body });
            const textBody = await resp.text().catch(() => "");
            out.push({ url, ok: resp.ok, status: resp.status, body: textBody });
        } catch (e: any) {
            out.push({ url: String(r?.url || ""), ok: false, error: String(e?.message || e) });
        }
    }
    return out;
};

export const sendSmsAndroid = async (number: string, content: string) => {
    if (!isAndroid()) return;
    const ok = await ensureAndroidPermission((android as any).Manifest.permission.SEND_SMS);
    if (!ok) throw new Error("SEND_SMS permission denied");
    const mgr = (android as any).telephony.SmsManager.getDefault();
    mgr.sendTextMessage(number, null, content, null, null);
};


