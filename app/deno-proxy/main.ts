//#!/usr/bin/env -S deno run --allow-net --allow-env --allow-read --allow-write --unstable

/**
 * Client Deno Module for Backend Connection
 *
 * This module acts as a connection forwarder between NativeScript and the backend server.
 * It handles encrypted data/payload forwarding with proper authentication.
 *
 * Usage:
 *   deno run -A main.ts <serverURL> <serverPort> <localPort> [deviceId]
 *
 * Example:
 *   deno run -A main.ts ws://localhost:9000 9000 8080 device-001
 */

import { makeConnection } from "./connection.ts";
import { parseArgs } from "https://deno.land/std@0.208.0/cli/parse_args.ts";

interface Config {
    serverURL: string;
    serverPort: number;
    localPort: number;
    deviceId: string;
}

function parseConfig(args: string[]): Config {
    const parsed = parseArgs(args, {
        string: ["server-url", "server-port", "local-port", "device-id"],
        default: {
            "server-url": "ws://0.0.0.0:8081",
            "server-port": "8081",
            "local-port": "9000",
            "local-url": "ws://127.0.0.1:9000",
            "device-id": `device-${crypto.randomUUID().slice(0, 8)}`,
        },
    });

    // Support positional arguments for backward compatibility
    const serverURL = parsed["server-url"] || args[0] || "ws://0.0.0.0:8081";
    const serverPort = parseInt(parsed["server-port"] || args[1] || "8081", 10);
    const localPort = parseInt(parsed["local-port"] || args[2] || "9000", 10);
    const localURL = parsed["local-url"] || args[3] || "ws://127.0.0.1:9000";
    const deviceId = parsed["device-id"] || args[3] || `device-${crypto.randomUUID().slice(0, 8)}`;

    return {
        serverURL: serverURL.startsWith("ws://") || serverURL.startsWith("wss://")
            ? serverURL
            : `ws://${serverURL}`,
        serverPort,
        localPort,
        deviceId,
    };
}

async function main() {
    const args = Deno.args;
    const config = parseConfig(args);

    console.log(`[Deno Proxy] Starting connection forwarder...`);
    console.log(`[Deno Proxy] Device ID: ${config.deviceId}`);
    console.log(`[Deno Proxy] Backend Server: ${config.serverURL}:${config.serverPort}`);
    console.log(`[Deno Proxy] Local Port: ${config.localPort}`);

    try {
        const { nativeScriptClient, localWebSocketServer } = await makeConnection(
            config.serverURL,
            config.serverPort,
            config.localPort,
            config.localURL,
            config.deviceId
        );

        // Set up connection handlers
        nativeScriptClient.on("connect", () => {
            console.log(`[Deno Proxy] Connected to backend server`);
            nativeScriptClient.emit("hello", { id: config.deviceId });
        });

        nativeScriptClient.on("disconnect", () => {
            console.log(`[Deno Proxy] Disconnected from backend server`);
        });

        nativeScriptClient.on("error", (error) => {
            console.error(`[Deno Proxy] Backend connection error:`, error);
        });

        console.log(`[Deno Proxy] Local WebSocket server ready on port ${config.localPort}`);

        // Keep process alive
        console.log(`[Deno Proxy] Connection forwarder running. Press Ctrl+C to stop.`);

        // Handle graceful shutdown
        Deno.addSignalListener("SIGINT", () => {
            console.log(`[Deno Proxy] Shutting down...`);
            nativeScriptClient.close();
            localWebSocketServer.close();
            Deno.exit(0);
        });

    } catch (error) {
        console.error(`[Deno Proxy] Fatal error:`, error);
        Deno.exit(1);
    }
}

if (import.meta.main) {
    await main();
}
