/**
 * Connection Forwarder Module
 *
 * Creates two WebSocket connections:
 * - One for NativeScript (internal, unencrypted)
 * - Another for backend server (external, encrypted)
 *
 * This module forwards messages between NativeScript and backend server,
 * applying encryption/decryption as needed.
 */

import { io as createSocketIOClient, Socket as SocketIOClient } from "npm:socket.io-client@^4.7.5";
import { WebSocket } from "npm:websocket@^1.0.35";
import { parsePayload, makePayload } from "./crypto-utils.ts";

/**
 * Proxy messages between SocketIO client (NativeScript) and WebSocket (Backend Server)
 *
 * @param socketA - SocketIO client (internal, from NativeScript, unencrypted)
 * @param socketB - WebSocket client (external, to backend server, encrypted)
 * @param deviceId - Device identifier for encryption/authentication
 */
export function proxyAtoB(
  socketA: SocketIOClient,
  socketB: WebSocket,
  deviceId: string
): void {
  // Forward messages from NativeScript (A) to Backend Server (B) with encryption
  socketA.on("message", (msg: any) => {
    try {
      const message = typeof msg === "string" ? JSON.parse(msg) : msg;
      const encryptedPayload = makePayload(deviceId, message);
      socketB.send(encryptedPayload);
    } catch (error) {
      console.error("[Proxy] Error encrypting message from NativeScript:", error);
    }
  });

  // Forward messages from Backend Server (B) to NativeScript (A) with decryption
  socketB.on("message", (data: string | Buffer) => {
    try {
      const payload = typeof data === "string" ? data : data.toString();
      const { inner } = parsePayload(payload);
      socketA.emit("message", inner);
    } catch (error) {
      console.error("[Proxy] Error decrypting message from backend:", error);
    }
  });

  // Error handling
  socketA.on("error", (error: Error) => {
    console.error("[Proxy] SocketIO (NativeScript) error:", error);
    try {
      socketB.send(JSON.stringify({
        type: "error",
        message: `SocketIO error: ${error.message}`,
      }));
    } catch (e) {
      console.error("[Proxy] Failed to forward error to WebSocket:", e);
    }
  });

  socketB.on("error", (error: Error) => {
    console.error("[Proxy] WebSocket (Backend) error:", error);
    try {
      socketA.emit("error", {
        type: "error",
        message: `WebSocket error: ${error.message}`,
      });
    } catch (e) {
      console.error("[Proxy] Failed to forward error to SocketIO:", e);
    }
  });

  // Close handling
  socketB.on("close", () => {
    console.log("[Proxy] WebSocket (Backend) closed");
    socketA.emit("close", {
      type: "close",
      message: "Backend connection closed",
    });
  });

  socketA.on("disconnect", () => {
    console.log("[Proxy] SocketIO (NativeScript) disconnected");
    if (socketB.readyState === WebSocket.OPEN) {
      socketB.close();
    }
  });
}

/**
 * Create and configure connection between NativeScript and Backend Server
 *
 * @param serverURL - Backend server WebSocket URL (e.g., "ws://localhost:9000")
 * @param serverPort - Backend server port (for SocketIO connection)
 * @param localPort - Local port for NativeScript WebSocket server
 * @param deviceId - Device identifier (defaults to "ns-ep" if not provided)
 * @returns Object containing SocketIO client and WebSocket server
 */
export async function makeConnection(
  serverURL: string,
  serverPort: number,
  localPort: number,
  deviceId: string = "ns-ep"
): Promise<{ nativeScriptClient: SocketIOClient; localWebSocketServer: any }> {
  // Create SocketIO client for backend server (external connection)
  const socketIOUrl = serverURL.replace(/^ws/, "http").replace(/^wss/, "https");
  const nativeScriptClient = createSocketIOClient(socketIOUrl, {
    transports: ["websocket"],
    reconnection: true,
    reconnectionDelay: 1000,
    reconnectionAttempts: Infinity,
  });

  // Create WebSocket server for NativeScript to connect to (internal connection)
  // Using Deno's standard WebSocket server
  const localWebSocketServer = await Deno.listen({ port: localPort, hostname: "127.0.0.1" });
  console.log(`[Connection] WebSocket server listening on ws://127.0.0.1:${localPort}`);

  // Handle incoming WebSocket connections from NativeScript
  (async () => {
    for await (const conn of localWebSocketServer) {
      handleNativeScriptConnection(conn, nativeScriptClient, deviceId);
    }
  })();

  return { nativeScriptClient, localWebSocketServer };
}

/**
 * Handle a WebSocket connection from NativeScript
 */
async function handleNativeScriptConnection(
  conn: Deno.Conn,
  backendClient: SocketIOClient,
  deviceId: string
): Promise<void> {
  try {
    // Upgrade to WebSocket
    const { socket, response } = Deno.upgradeWebSocket(conn);

    console.log(`[Connection] NativeScript WebSocket connected`);

    // Forward messages from NativeScript to Backend (with encryption)
    socket.onmessage = (event) => {
      try {
        const message = typeof event.data === "string" ? JSON.parse(event.data) : event.data;
        // NativeScript sends plain messages, encrypt them for backend
        // Backend expects: { type, from, to, mode, action, payload: "BASE64(...)" }
        const encryptedPayload = makePayload(deviceId, {
          ts: Date.now(),
          data: message.data || message,
          action: message.action,
        });

        const backendMessage = {
          type: message.type || "clip",
          from: deviceId,
          to: message.to || "broadcast",
          mode: message.mode || "blind",
          action: message.action || "setClipboard",
          payload: encryptedPayload,
        };

        backendClient.emit("message", backendMessage);
      } catch (error) {
        console.error(`[Connection] Error encrypting and forwarding message to backend:`, error);
      }
    };

    // Forward messages from Backend to NativeScript (with decryption)
    backendClient.on("message", (msg: any) => {
      try {
        // Backend sends messages with encrypted payload
        // Decrypt and send plain message to NativeScript
        if (msg.payload) {
          const { inner } = parsePayload(msg.payload);
          const plainMessage = {
            type: msg.type,
            from: msg.from,
            to: msg.to,
            action: msg.action,
            data: inner.data,
            ts: inner.ts,
          };
          socket.send(JSON.stringify(plainMessage));
        } else {
          // If no payload, forward as-is
          socket.send(JSON.stringify(msg));
        }
      } catch (error) {
        console.error(`[Connection] Error decrypting and forwarding message to NativeScript:`, error);
      }
    });

    socket.onerror = (error) => {
      console.error(`[Connection] NativeScript WebSocket error:`, error);
    };

    socket.onclose = () => {
      console.log(`[Connection] NativeScript WebSocket closed`);
    };
  } catch (error) {
    console.error(`[Connection] Error handling NativeScript connection:`, error);
    conn.close();
  }
}

/**
 * Main entry point for connection setup
 *
 * @param args - Configuration arguments
 * @returns Promise resolving to connections
 */
export async function MAIN(args: {
  serverURL: string;
  port: number;
  localPort: number;
  deviceId?: string;
}): Promise<{ nativeScriptClient: SocketIOClient; localWebSocketServer: any }> {
  return await makeConnection(
    args.serverURL,
    args.port,
    args.localPort,
    args.deviceId || "ns-ep"
  );
}
