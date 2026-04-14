#!/usr/bin/env node
import { existsSync } from "node:fs";
import { resolve, join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDir = fileURLToPath(new URL(".", import.meta.url));
const androidRoot = resolve(scriptDir, "..");

const hasCrosswordPackage = (root) => existsSync(join(root, "package.json"));

const envRoot = String(process.env.CWS_CROSSWORD_ROOT || "").trim();
const candidates = [
    envRoot,
    resolve(androidRoot, "../../apps/CrossWord"),
    resolve(androidRoot, "../U2RE.space/apps/CrossWord"),
    resolve(androidRoot, "../CrossWord"),
    resolve(androidRoot, "../../CrossWord")
].filter(Boolean);

const crosswordRoot = candidates.find((candidate) => hasCrosswordPackage(candidate));

if (!crosswordRoot) {
    console.error(
        "[build:frontend] CrossWord project not found.\n" +
            "Set CWS_CROSSWORD_ROOT to a valid CrossWord directory containing package.json.\n" +
            "Tried:\n" +
            candidates.map((candidate) => `  - ${candidate}`).join("\n")
    );
    process.exit(1);
}

const npmCmd = process.platform === "win32" ? "npm.cmd" : "npm";
const result = spawnSync(npmCmd, ["run", "build:pwa"], {
    cwd: crosswordRoot,
    stdio: "inherit",
    shell: process.platform === "win32",
    env: { ...process.env }
});

if (result.status !== 0) {
    process.exit(result.status ?? 1);
}
