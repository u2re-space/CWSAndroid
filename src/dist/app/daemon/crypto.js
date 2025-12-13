"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.decryptJson = exports.encryptJson = void 0;
const logger_1 = require("./logger");
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
