import { log } from "./logger";

declare const java: any;
declare const okhttp3: any;
declare const javax: any;
declare const NativeClass: any;
declare const Interfaces: any;
declare const global: any;

export type HttpResult = {
    ok: boolean;
    status: number;
    body: string;
};

const isAndroid = () => typeof globalThis !== "undefined" && !!(globalThis as any).android;

const getOkHttpClient = (allowInsecureTls: boolean, timeoutMs: number) => {
    const builder = new okhttp3.OkHttpClient.Builder();
    builder.connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    builder.readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    builder.writeTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

    if (allowInsecureTls) {
        try {
            // NativeScript requires Java interfaces implemented via @Interfaces on java.lang.Object,
            // not plain JS object literals (those can crash OkHttp with NPE).
            const X509TM = javax.net.ssl.X509TrustManager;
            const HostVerifier = javax.net.ssl.HostnameVerifier;

            @NativeClass()
            @Interfaces([X509TM])
            class TrustAllManager extends java.lang.Object {
                constructor() {
                    super();
                    return global.__native(this);
                }
                getAcceptedIssuers() {
                    // Return empty X509Certificate[]
                    return Array.create("java.security.cert.X509Certificate", 0);
                }
                checkClientTrusted(_chain: any, _authType: any) {}
                checkServerTrusted(_chain: any, _authType: any) {}
            }

            @NativeClass()
            @Interfaces([HostVerifier])
            class TrustAllHostnameVerifier extends java.lang.Object {
                constructor() {
                    super();
                    return global.__native(this);
                }
                verify(_hostname: any, _session: any) {
                    return true;
                }
            }

            const trustAll = new TrustAllManager();
            const sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init([], [trustAll], new java.security.SecureRandom());
            const sslSocketFactory = sc.getSocketFactory();

            // OkHttp requires both SSLSocketFactory and X509TrustManager
            builder.sslSocketFactory(sslSocketFactory, trustAll);
            builder.hostnameVerifier(new TrustAllHostnameVerifier());
        } catch (e) {
            log.warn("allowInsecureTls setup failed", e);
        }
    }

    return builder.build();
};

export const postJson = async (url: string, json: any, allowInsecureTls: boolean, timeoutMs = 8000): Promise<HttpResult> => {
    const body = JSON.stringify(json ?? {});
    return await postText(url, body, { "Content-Type": "application/json; charset=utf-8" }, allowInsecureTls, timeoutMs);
};

export const postText = async (
    url: string,
    body: string,
    headers: Record<string, string>,
    allowInsecureTls: boolean,
    timeoutMs = 8000
): Promise<HttpResult> => {
    // Non-Android: fall back to fetch (can't bypass TLS).
    if (!isAndroid()) {
        const resp = await fetch(url, { method: "POST", headers, body });
        const text = await resp.text().catch(() => "");
        return { ok: resp.ok, status: resp.status, body: text };
    }

    // Android: use OkHttp async (network on OkHttp threads; avoids NetworkOnMainThreadException).
    return await new Promise<HttpResult>((resolve, reject) => {
        try {
            const client = getOkHttpClient(allowInsecureTls, timeoutMs);
            const mediaType = okhttp3.MediaType.parse(headers?.["Content-Type"] || headers?.["content-type"] || "text/plain; charset=utf-8");
            const reqBody = okhttp3.RequestBody.create(mediaType, new java.lang.String(body || ""));
            const builder = new okhttp3.Request.Builder().url(url).post(reqBody);
            for (const k of Object.keys(headers || {})) {
                builder.header(k, String((headers as any)[k]));
            }
            const call = client.newCall(builder.build());
            call.enqueue(
                new okhttp3.Callback({
                    onFailure: (_call: any, e: any) => reject(e),
                    onResponse: (_call: any, resp: any) => {
                        try {
                            const status = resp.code();
                            const ok = status >= 200 && status < 300;
                            const bodyObj = resp.body ? resp.body() : null;
                            const respBody = bodyObj && bodyObj.string ? bodyObj.string() : "";
                            resp.close();
                            resolve({ ok, status, body: String(respBody) });
                        } catch (e) {
                            try { resp?.close?.(); } catch {}
                            reject(e);
                        }
                    },
                })
            );
        } catch (e) {
            reject(e);
        }
    });
};


