"use strict";
/**
 * LEGACY FILE (kept for reference)
 *
 * The daemon has been refactored into `app/daemon/*` and bootstrapped via `app/main.ts`.
 * This old file relied on spawning a Deno proxy process and is no longer the default entrypoint.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.runProcess = runProcess;
exports.useDenoModule = useDenoModule;
exports.makeWebSocketServer = makeWebSocketServer;
exports.readClipboard = readClipboard;
exports.writeClipboard = writeClipboard;
exports.doClipboardCopy = doClipboardCopy;
exports.doCommand = doCommand;
exports.monitorClipboard = monitorClipboard;
exports.startDaemon = startDaemon;
exports.stopDaemon = stopDaemon;
const module_1 = require("module");
const nodeRequire = typeof require !== "undefined"
    ? require
    : (0, module_1.createRequire)?.(process.cwd() + "/");
// Resolve local file-utils with Node/TS fallback.
function resolveFileUtils() {
    try {
        return nodeRequire?.("./file-utils");
    }
    catch (err) {
        console.warn("[file-utils] Module not found; using stub. Deno proxy file operations disabled in this environment.", err?.message || err);
        return {
            async copyDenoProxyFiles() {
                console.warn("[file-utils] Stub: copyDenoProxyFiles called");
            },
            async denoProxyFilesExist() {
                console.warn("[file-utils] Stub: denoProxyFilesExist called");
                return true;
            },
            getDenoProxyPath() {
                console.warn("[file-utils] Stub: getDenoProxyPath called");
                return "";
            },
        };
    }
}
// Fallback loader for @nativescript-use/nativescript-clipboard so Node-based
// debugging works even though the plugin ships platform-specific entrypoints.
function resolveClipboard() {
    try {
        const mod = nodeRequire?.("@nativescript-use/nativescript-clipboard");
        return mod.Clipboard || mod.default?.Clipboard || mod;
    }
    catch (err) {
        try {
            const mod = nodeRequire?.("@nativescript-use/nativescript-clipboard/index.android");
            return mod.Clipboard || mod.default?.Clipboard || mod;
        }
        catch (androidErr) {
            console.warn("[Clipboard] Native module not found; using stub. Clipboard features are disabled in this environment.", androidErr?.message || androidErr);
            return class ClipboardStub {
                async read() {
                    console.warn("[Clipboard] Stub: read called");
                    return "";
                }
                async copy(_text) {
                    console.warn("[Clipboard] Stub: copy called");
                }
                onCopy(_cb) {
                    console.warn("[Clipboard] Stub: onCopy registered");
                }
            };
        }
    }
}
// Fallback loader for path utilities; prefer Node's path to avoid loading
// NativeScript runtime modules when debugging in plain Node/ESM.
function resolvePathModule() {
    try {
        return nodeRequire?.("path");
    }
    catch (err) {
        try {
            const mod = nodeRequire?.("@nativescript/core");
            return mod.path || mod.default?.path || mod;
        }
        catch (nsErr) {
            console.warn("[path] Module not found; using stub. Path resolution disabled in this environment.", nsErr?.message || nsErr);
            return {
                join: (...parts) => parts.join("/"),
            };
        }
    }
}
// Fallback loader for nativescript-betterwebsockets so Node-based debugging works
// even though the plugin only ships Android-specific entrypoints.
function resolveWebSocketClasses() {
    try {
        const mod = nodeRequire?.("nativescript-betterwebsockets");
        return {
            WebSocketServer: mod.WebSocketServer || mod.default?.WebSocketServer,
            WebSocketClient: mod.WebSocketClient || mod.default?.WebSocketClient,
        };
    }
    catch (err) {
        try {
            const mod = nodeRequire?.("nativescript-betterwebsockets/betterwebsockets.android");
            return {
                WebSocketServer: mod.WebSocketServer || mod.default?.WebSocketServer,
                WebSocketClient: mod.WebSocketClient || mod.default?.WebSocketClient,
            };
        }
        catch (androidErr) {
            console.warn("[WebSocket] Native module not found; using stubs. WebSocket features are disabled in this environment.", androidErr?.message || androidErr);
            // Return stub classes for debugging
            return {
                WebSocketServer: class WebSocketServerStub {
                    constructor(port) {
                        console.warn(`[WebSocketServer] Stub: would listen on port ${port}`);
                    }
                    on(event, callback) {
                        console.warn(`[WebSocketServer] Stub: would handle event '${event}'`);
                    }
                    close() {
                        console.warn(`[WebSocketServer] Stub: would close`);
                    }
                },
                WebSocketClient: class WebSocketClientStub {
                    constructor(uri) {
                        console.warn(`[WebSocketClient] Stub: would connect to ${uri}`);
                    }
                    on(event, callback) {
                        console.warn(`[WebSocketClient] Stub: would handle event '${event}'`);
                    }
                },
            };
        }
    }
}
const { WebSocketServer, WebSocketClient } = resolveWebSocketClasses();
const Clipboard = resolveClipboard();
const { copyDenoProxyFiles, getDenoProxyPath, denoProxyFilesExist } = resolveFileUtils();
const nsPath = resolvePathModule();
function resolveChildProcess() {
    try {
        const mod = nodeRequire?.("nativescript-childprocess");
        return mod.ChildProcess || mod.default || mod;
    }
    catch (err) {
        try {
            const mod = nodeRequire?.("nativescript-childprocess/index.android");
            return mod.ChildProcess || mod.default || mod;
        }
        catch (androidErr) {
            console.warn("[ChildProcess] Native module not found; using stub. Process features are disabled in this environment.", androidErr?.message || androidErr);
            return class ChildProcessStub {
                static run() {
                    throw new Error("nativescript-childprocess unavailable in this environment");
                }
            };
        }
    }
}
const ChildProcess = resolveChildProcess();
const clipboard = new Clipboard();
// Process and connection management
const associatedProcesses = new Map();
const processIdentifiers = new Map();
let denoProcess = null;
let backendConnection = null;
/**
 * Run a process with the given command and arguments
 */
function runProcess(command, ...args) {
    return ChildProcess.run(command, args);
}
/**
 * Initialize and start the Deno module
 *
 * @param serverURL - Backend server URL
 * @param modulePath - Path to Deno connection module (default: connection.ts in deno-proxy)
 * @param localPort - Local port for WebSocket communication (default: 9000)
 * @returns The spawned Deno process
 */
function useDenoModule(serverURL, modulePath = "../deno-proxy/main.ts", localPort = 9000) {
    const specialId = crypto.randomUUID();
    const process = runProcess("deno", "run", "-A", "--allow-net", "--allow-env", "--allow-read", "--allow-write", "--unstable", modulePath, serverURL, "9000", localPort.toString(), specialId);
    processIdentifiers.set(process, specialId);
    process.stdout?.on("data", (data) => {
        console.log(`[Deno Process] ${data.toString()}`);
    });
    process.stderr?.on("data", (data) => {
        console.error(`[Deno Process Error] ${data.toString()}`);
    });
    process.on("exit", (code) => {
        console.log(`[Deno Process] Exited with code ${code}`);
        processIdentifiers.delete(process);
        associatedProcesses.delete(process);
    });
    return process;
}
/**
 * Create a local WebSocket server for Deno module communication
 *
 * @param localPort - Port to listen on
 * @returns WebSocket server instance
 */
function makeWebSocketServer(localPort) {
    const server = new WebSocketServer(localPort);
    backendConnection = server;
    server.on("connection", (ws) => {
        console.log(`[WebSocket Server] New connection from ${ws.remoteAddress}`);
        ws.on("message", (message) => {
            const msg = typeof message === "string" ? JSON.parse(message) : message;
            if (msg.type == "connect") {
                const process = [...processIdentifiers.entries()]?.find(([_, id]) => id === msg.payload.id)?.[0];
                if (process) {
                    associatedProcesses.set(process, ws);
                }
            }
            else if (msg.type == "disconnect") {
                const process = [...processIdentifiers.entries()]?.find(([_, id]) => id === msg.payload.id)?.[0];
                if (process) {
                    associatedProcesses.delete(process);
                }
            }
        });
    });
    server.on("error", (error) => {
        console.error(`[WebSocket Server] Server error:`, error);
    });
    return server;
}
/**
 * Read clipboard content
 *
 * @returns Clipboard text content
 */
async function readClipboard() {
    try {
        // NativeScript clipboard API
        const text = await clipboard.read();
        return text || "";
    }
    catch (error) {
        console.error(`[Clipboard] Error reading clipboard:`, error);
        return "";
    }
}
/**
 * Write text to clipboard
 *
 * @param text - Text to write to clipboard
 */
async function writeClipboard(text) {
    try {
        await clipboard.copy(text);
        console.log(`[Clipboard] Text written to clipboard`);
    }
    catch (error) {
        console.error(`[Clipboard] Error writing to clipboard:`, error);
    }
}
/**
 * Create a clipboard copy message to send to backend
 *
 * @param whereToSend - Target device ID or "broadcast"
 * @returns Message object ready to send
 */
async function doClipboardCopy(whereToSend = "broadcast") {
    const text = await readClipboard();
    const message = {
        type: "clip",
        from: "ns-ep",
        to: whereToSend,
        mode: "blind",
        action: "setClipboard",
        payload: {
            ts: Date.now(),
            data: text,
        },
    };
    return message;
}
/**
 * Create a command message to send to backend
 *
 * @param command - Command name
 * @param data - Command data
 * @returns Message object ready to send
 */
function doCommand(command, data) {
    const message = {
        type: "command",
        from: "ns-ep",
        to: "server",
        mode: "blind",
        action: command,
        data,
    };
    return message;
}
/**
 * Monitor clipboard for changes
 */
function monitorClipboard() {
    clipboard.onCopy((text) => {
        console.log(`[Clipboard] Clipboard changed:`, text);
        doClipboardCopy(text);
    });
}
/**
 * Start the daemon with full configuration
 *
 * @param config - Daemon configuration
 */
async function startDaemon(config) {
    console.log(`[Daemon] Starting NativeScript background daemon...`);
    console.log(`[Daemon] Device ID: ${config.deviceId}`);
    console.log(`[Daemon] Backend Server: ${config.backendServerURL}`);
    console.log(`[Daemon] Deno Module: ${config.denoModulePath}`);
    console.log(`[Daemon] Local Port: ${config.denoLocalPort}`);
    // Ensure deno-proxy files are copied to external storage
    try {
        const filesExist = await denoProxyFilesExist();
        if (!filesExist) {
            console.log(`[Daemon] Copying deno-proxy files to external storage...`);
            await copyDenoProxyFiles();
        }
        else {
            console.log(`[Daemon] Deno-proxy files already exist in external storage`);
        }
    }
    catch (error) {
        console.error(`[Daemon] Error ensuring deno-proxy files exist:`, error);
        // Continue anyway - the files might already be there or the path might be different
    }
    // Start Deno module process
    denoProcess = useDenoModule(config.backendServerURL, config.denoModulePath, config.denoLocalPort);
    console.log(`[Daemon] Deno module process started`);
    // Run backend
    backendConnection = makeWebSocketServer(config.denoLocalPort);
    console.log(`[Daemon] Backend server started`);
    // Start clipboard monitoring
    monitorClipboard();
    console.log(`[Daemon] Clipboard monitoring started`);
}
/**
 * Stop the daemon and cleanup resources
 */
function stopDaemon() {
    console.log(`[Daemon] Stopping daemon...`);
    if (denoProcess) {
        denoProcess.closeAbruptly().then(() => {
            denoProcess = null;
        });
    }
    if (backendConnection) {
        backendConnection.close();
    }
    associatedProcesses.clear();
    processIdentifiers.clear();
    console.log(`[Daemon] Daemon stopped`);
}
// Main entry point
/*if ((nodeRequire as any)?.main === module) {*/
// Get the deno-proxy path dynamically
const denoProxyDir = getDenoProxyPath();
const denoModulePath = nsPath.join(denoProxyDir, "main.ts");
const config = {
    denoModulePath: denoModulePath,
    denoLocalPort: 8081,
    backendServerURL: process?.env?.BACKEND_SERVER_URL || "ws://localhost:9000",
    deviceId: process?.env?.DEVICE_ID || `device-${crypto.randomUUID().slice(0, 8)}`,
};
startDaemon(config).catch((error) => {
    console.error(`[Daemon] Fatal error:`, error);
    process.exit(1);
});
// Handle graceful shutdown
process.on("SIGINT", () => {
    console.log(`[Daemon] Received SIGINT, shutting down...`);
    stopDaemon();
    process.exit(0);
});
process.on("SIGTERM", () => {
    console.log(`[Daemon] Received SIGTERM, shutting down...`);
    stopDaemon();
    process.exit(0);
});
/*}
*/ 
