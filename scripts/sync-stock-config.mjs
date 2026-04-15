#!/usr/bin/env node
import { mkdir, copyFile, access } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const androidRoot = path.resolve(__dirname, "..");
const endpointRoot = path.resolve(androidRoot, "../cwsp/endpoint");
const endpointConfigDir = path.resolve(endpointRoot, "config");
const endpointHttpsDir = path.resolve(endpointRoot, "https");

const assetsConfigDir = path.resolve(androidRoot, "app/src/main/assets/stock/config");
const assetsHttpsDir = path.resolve(androidRoot, "app/src/main/assets/stock/https");

const configFiles = [
    "clients.json",
    "gateways.json",
    "network.json",
    "portable-core.json",
    "portable-endpoint.json",
    "portable.config.json",
    "portable.config.110.json",
    "portable.config.vds.json",
    "certificate.mjs"
];

const httpsFiles = [
    "rootCA.crt",
    "multi.crt",
    "multi.key",
    "server.cnf"
];

const exists = async (target) => {
    try {
        await access(target, fsConstants.F_OK);
        return true;
    } catch {
        return false;
    }
};

const ensureDir = async (dir) => {
    await mkdir(dir, { recursive: true });
};

const copyIfExists = async (src, dest) => {
    if (!(await exists(src))) return false;
    await copyFile(src, dest);
    return true;
};

const main = async () => {
    await ensureDir(assetsConfigDir);
    await ensureDir(assetsHttpsDir);

    for (const name of configFiles) {
        const src = path.resolve(endpointConfigDir, name);
        const dest = path.resolve(assetsConfigDir, name);
        const copied = await copyIfExists(src, dest);
        if (copied) console.log(`[sync-stock-config] config ${name}`);
    }

    for (const name of httpsFiles) {
        const local = path.resolve(endpointHttpsDir, "local", name);
        const direct = path.resolve(endpointHttpsDir, name);
        const dest = path.resolve(assetsHttpsDir, name);
        const copied = await copyIfExists(local, dest) || await copyIfExists(direct, dest);
        if (copied) console.log(`[sync-stock-config] https ${name}`);
    }
};

main().catch((error) => {
    console.error("[sync-stock-config]", error);
    process.exit(1);
});
