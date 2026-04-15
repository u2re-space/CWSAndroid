#!/usr/bin/env node
import { existsSync } from "node:fs";
import { resolve, join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDir = fileURLToPath(new URL(".", import.meta.url));
const androidRoot = resolve(scriptDir, "..");

const hasDiagScript = (root) => existsSync(join(root, "scripts", "auto-diagnose-links.mjs"));

const envRoot = String(process.env.CWS_CWSP_ROOT || "").trim();
const candidates = [
    envRoot,
    resolve(androidRoot, "../cwsp"),
    resolve(androidRoot, "../U2RE.space/runtime/cwsp"),
    resolve(androidRoot, "../../runtime/cwsp"),
    resolve(androidRoot, "../../U2RE.space/runtime/cwsp")
].filter(Boolean);

const cwspRoot = candidates.find((candidate) => hasDiagScript(candidate));
if (!cwspRoot) {
    console.error(
        "[diag:remote] CWSP root not found.\n" +
            "Set CWS_CWSP_ROOT to a valid runtime/cwsp directory.\n" +
            "Tried:\n" +
            candidates.map((candidate) => `  - ${candidate}`).join("\n")
    );
    process.exit(1);
}

const passthroughArgs = process.argv.slice(2);
const nodeResult = spawnSync("node", [join(cwspRoot, "scripts", "auto-diagnose-links.mjs"), ...passthroughArgs], {
    cwd: cwspRoot,
    stdio: "inherit",
    shell: process.platform === "win32",
    env: { ...process.env }
});

process.exit(nodeResult.status ?? 1);

