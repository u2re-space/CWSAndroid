"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.connectSocket = void 0;
// IMPORTANT: Use an exported browser bundle so NativeScript webpack doesn't pull Node transports (`ws`).
const socket_io_js_1 = require("socket.io-client/dist/socket.io.js");
const logger_1 = require("./logger");
const normalizeEndpoint = (endpointHttp) => {
    const trimmed = (endpointHttp || "").trim();
    if (!trimmed)
        return "http://192.168.0.200:8080";
    if (/^https?:\/\//i.test(trimmed))
        return trimmed;
    return `http://${trimmed}`;
};
const connectSocket = (settings) => {
    const endpoint = normalizeEndpoint(settings.endpointHttp);
    const socket = (0, socket_io_js_1.io)(endpoint, {
        transports: ["websocket", "polling"],
        reconnection: true,
        reconnectionDelay: 1000,
        reconnectionAttempts: Infinity,
    });
    socket.on("connect", () => {
        logger_1.log.info("socket connected", socket.id, endpoint);
        socket.emit("hello", { id: settings.deviceId, userId: settings.userId, userKey: settings.userKey });
    });
    socket.on("disconnect", (reason) => logger_1.log.warn("socket disconnected", reason));
    socket.on("connect_error", (err) => logger_1.log.warn("socket connect_error", err?.message || err));
    socket.on("error", (err) => logger_1.log.warn("socket error", err));
    const send = (msg) => {
        socket.emit("message", msg);
    };
    const close = () => socket.close();
    return { socket, send, close };
};
exports.connectSocket = connectSocket;
