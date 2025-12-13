// IMPORTANT: Use an exported browser bundle so NativeScript webpack doesn't pull Node transports (`ws`).
import { io } from "socket.io-client/dist/socket.io.js";
import { log } from "./logger";
import type { AutomataSettings } from "./settings";

export type AutomataMessage = {
    type: "clip" | "sync" | "command" | string;
    from: string;
    to: string;
    mode?: "blind" | "inspect" | "plain";
    action?: string;
    payload?: any;
};

export type SocketClient = {
    socket: any;
    send: (msg: AutomataMessage) => void;
    close: () => void;
};

const normalizeEndpoint = (endpointHttp: string): string => {
    const trimmed = (endpointHttp || "").trim();
    if (!trimmed) return "http://192.168.0.200:8080";
    if (/^https?:\/\//i.test(trimmed)) return trimmed;
    return `http://${trimmed}`;
};

export const connectSocket = (settings: AutomataSettings): SocketClient => {
    const endpoint = normalizeEndpoint(settings.endpointHttp);
    const socket = io(endpoint, {
        transports: ["websocket", "polling"],
        reconnection: true,
        reconnectionDelay: 1000,
        reconnectionAttempts: Infinity,
    });

    socket.on("connect", () => {
        log.info("socket connected", socket.id, endpoint);
        socket.emit("hello", { id: settings.deviceId, userId: settings.userId, userKey: settings.userKey });
    });

    socket.on("disconnect", (reason: any) => log.warn("socket disconnected", reason));
    socket.on("connect_error", (err: any) => log.warn("socket connect_error", err?.message || err));
    socket.on("error", (err: any) => log.warn("socket error", err));

    const send = (msg: AutomataMessage) => {
        socket.emit("message", msg);
    };

    const close = () => socket.close();

    return { socket, send, close };
};


