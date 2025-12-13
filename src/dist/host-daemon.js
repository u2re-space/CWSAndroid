"use strict";
/**
 * NativeScript Background Daemon
 *
 * This daemon runs in the background and provides:
 * - Clipboard access and monitoring
 * - Android/Platform API integration
 * - Connection to Deno module (via Termux or similar)
 * - Connection to backend server through Deno proxy
 *
 * The daemon communicates with:
 * 1. Local Deno module (via WebSocket) - for encryption/forwarding
 * 2. Backend server (through Deno proxy) - for synchronization
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.runProcess = runProcess;
exports.useDenoModule = useDenoModule;
exports.makeWebSocketServer = makeWebSocketServer;
exports.readClipboard = readClipboard;
exports.writeClipboard = writeClipboard;
exports.doClipboardCopy = doClipboardCopy;
exports.doCommand = doCommand;
exports.startDaemon = startDaemon;
exports.stopDaemon = stopDaemon;
const nativescript_childprocess_1 = require("nativescript-childprocess");
const nativescript_betterwebsockets_1 = require("nativescript-betterwebsockets");
// Clipboard API - platform specific implementation
// For NativeScript, use the clipboard module
// Note: This may need to be adjusted based on your NativeScript version
let clipboard;
try {
    clipboard = require("@nativescript/core").Clipboard;
}
catch (e) {
    // Fallback for non-NativeScript environments (testing)
    clipboard = {
        getText: () => "",
        setText: (text) => console.log(`[Clipboard Mock] Set: ${text}`),
    };
}
// Process and connection management
const associatedProcesses = new Map();
const processIdentifiers = new Map();
let denoProcess = null;
let backendConnection = null;
/**
 * Run a process with the given command and arguments
 */
function runProcess(command, ...args) {
    return nativescript_childprocess_1.ChildProcess.run(command, args);
}
/**
 * Initialize and start the Deno module
 *
 * @param serverURL - Backend server URL
 * @param modulePath - Path to Deno connection module (default: connection.ts in deno-proxy)
 * @param localPort - Local port for WebSocket communication (default: 8080)
 * @returns The spawned Deno process
 */
function useDenoModule(serverURL, modulePath = "../deno-proxy/main.ts", localPort = 8080) {
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
    const server = new nativescript_betterwebsockets_1.WebSocketServer(localPort);
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
        if (clipboard && typeof clipboard.getText === "function") {
            const text = clipboard.getText();
            return text || "";
        }
        return "";
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
        if (clipboard && typeof clipboard.setText === "function") {
            clipboard.setText(text);
            console.log(`[Clipboard] Text written to clipboard`);
        }
        else {
            console.warn(`[Clipboard] setText not available`);
        }
    }
    catch (error) {
        console.error(`[Clipboard] Error writing to clipboard:`, error);
        throw error;
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
    // Start Deno module process
    denoProcess = useDenoModule(config.backendServerURL, config.denoModulePath, config.denoLocalPort);
    console.log(`[Daemon] Deno module process started`);
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
if (require.main === module) {
    const config = {
        denoModulePath: "../deno-proxy/main.ts",
        denoLocalPort: 8080,
        backendServerURL: process.env.BACKEND_SERVER_URL || "ws://localhost:9000",
        deviceId: process.env.DEVICE_ID || `device-${crypto.randomUUID().slice(0, 8)}`,
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
}
