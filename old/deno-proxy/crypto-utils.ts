/**
 * Cryptographic Utilities for Payload Encryption/Decryption
 *
 * Provides AES-256-GCM encryption with RSA signature verification
 * for secure message forwarding between clients and server.
 */

// Use Deno's Node.js compatibility layer for crypto
import crypto from "node:crypto";
import { Buffer } from "node:buffer";

/**
 * AES-256-GCM ключ.
 * В реале возьмите из переменной окружения CLIPBOARD_MASTER_KEY.
 */
const AES_KEY = crypto
    .createHash("sha256")
    .update(Deno.env.get("CLIPBOARD_MASTER_KEY") || "some-very-secret-key")
    .digest(); // 32 bytes

/**
 * RSA приватный ключ клиента (PEM-строка).
 * На сервере приватник не нужен, там только PUBLIC_KEYS.
 *
 * На клиенте задайте:
 *   export PRIV_KEY_PEM="$(cat client-private.pem)"
 */
const PRIVATE_KEY = Deno.env.get("PRIV_KEY_PEM") || null;

/**
 * Публичные ключи устройств:
 *   deviceId -> PEM
 * На сервере: заполнить все устройства.
 * На клиентах: можно держать свои и, при желании, чужие для доп.проверок.
 *
 * Для примера для одного устройства:
 *   export PUBKEY_pc1="$(cat pc1-public.pem)"
 *   export PUBKEY_phone1="$(cat phone1-public.pem)"
 */
const PUBLIC_KEYS = {};
for (const [envName, value] of Object.entries(Deno.env.toObject())) {
    if (envName.startsWith("PUBKEY_")) {
        const deviceId = envName.slice("PUBKEY_".length); // PUBKEY_pc1 -> pc1
        PUBLIC_KEYS[deviceId] = value;
    }
}

/**
 * Шифрование JSON-объекта (innerObj) -> Buffer cipherBlock (iv|ciphertext|authTag)
 */
function encryptPayload(innerObj: any): Buffer {
    const iv = crypto.randomBytes(12); // 12 bytes for GCM
    const plaintext = Buffer.from(JSON.stringify(innerObj), "utf8");

    const cipher = crypto.createCipheriv("aes-256-gcm", AES_KEY, iv);
    const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    const authTag = cipher.getAuthTag();

    // Structure: iv|encrypted|authTag
    return Buffer.concat([iv, encrypted, authTag]);
}

/**
 * Расшифровка cipherBlock (Buffer) -> innerObj
 */
function decryptPayload(cipherBlock: Buffer): any {
    const iv = cipherBlock.slice(0, 12);
    const authTag = cipherBlock.slice(cipherBlock.length - 16);
    const encrypted = cipherBlock.slice(12, cipherBlock.length - 16);

    const decipher = crypto.createDecipheriv("aes-256-gcm", AES_KEY, iv);
    decipher.setAuthTag(authTag);
    const decrypted = Buffer.concat([decipher.update(encrypted), decipher.final()]);
    return JSON.parse(decrypted.toString("utf8"));
}

/**
 * Подпись блока cipherBlock приватным ключом устройства.
 */
function signCipher(deviceId: string, cipherBlock: Buffer): Buffer {
    if (!PRIVATE_KEY) {
        throw new Error("No PRIVATE_KEY_PEM configured in environment");
    }
    const signer = crypto.createSign("sha256");
    signer.update(cipherBlock);
    signer.end();
    return signer.sign(PRIVATE_KEY);
}

/**
 * Проверка подписи cipherBlock по публичному ключу устройства.
 */
function verifyCipher(deviceId: string, cipherBlock: Buffer, signature: Buffer): boolean {
    const pub = PUBLIC_KEYS[deviceId];
    if (!pub) {
        console.warn("No public key for deviceId:", deviceId);
        return false;
    }
    const verifier = crypto.createVerify("sha256");
    verifier.update(cipherBlock);
    verifier.end();
    return verifier.verify(pub, signature);
}

/**
 * Упаковка innerObj в payload (строка base64).
 * 1) innerObj -> AES-GCM шифр (cipherBlock)
 * 2) cipherBlock -> подпись
 * 3) {from, cipher, sig} -> JSON -> base64
 */
function makePayload(deviceId: string, innerObj: any) {
    const cipherBlock = encryptPayload(innerObj);
    const sig = signCipher(deviceId, cipherBlock);

    const outer = {
        from: deviceId,
        cipher: cipherBlock.toString("base64"),
        sig: sig.toString("base64"),
    };
    const json = JSON.stringify(outer);
    return Buffer.from(json, "utf8").toString("base64");
}

/**
 * parsePayload:
 *   payloadB64 -> outer JSON -> проверка подписи -> decrypt -> {from, inner}
 */
function parsePayload(payloadB64: string) {
    const outerJson = Buffer.from(payloadB64, "base64").toString("utf8");
    const outer = JSON.parse(outerJson); // {from, cipher, sig}

    const deviceId = outer.from;
    const cipherBlock = Buffer.from(outer.cipher, "base64");
    const sig = Buffer.from(outer.sig, "base64");

    const ok = verifyCipher(deviceId, cipherBlock, sig);
    if (!ok) {
        throw new Error("Signature verify failed for deviceId=" + deviceId);
    }

    const inner = decryptPayload(cipherBlock);
    return { from: deviceId, inner };
}

/**
 * Проверить подпись, не расшифровывая содержимое.
 * Используется сервером в режиме blind.
 */
function verifyWithoutDecrypt(payloadB64: string): boolean {
    const outerJson = Buffer.from(payloadB64, "base64").toString("utf8");
    const outer = JSON.parse(outerJson);
    const deviceId = outer.from;
    const cipherBlock = Buffer.from(outer.cipher, "base64");
    const sig = Buffer.from(outer.sig, "base64");
    return verifyCipher(deviceId, cipherBlock, sig);
}

export { makePayload, parsePayload, verifyWithoutDecrypt };
