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
import { ChildProcess } from "nativescript-childprocess";
import { WebSocketServer } from 'nativescript-betterwebsockets';
interface DaemonConfig {
    denoModulePath: string;
    denoLocalPort: number;
    backendServerURL: string;
    deviceId: string;
}
/**
 * Run a process with the given command and arguments
 */
export declare function runProcess(command: string, ...args: string[]): any;
/**
 * Initialize and start the Deno module
 *
 * @param serverURL - Backend server URL
 * @param modulePath - Path to Deno connection module (default: connection.ts in deno-proxy)
 * @param localPort - Local port for WebSocket communication (default: 8080)
 * @returns The spawned Deno process
 */
export declare function useDenoModule(serverURL: string, modulePath?: string, localPort?: number): ChildProcess;
/**
 * Create a local WebSocket server for Deno module communication
 *
 * @param localPort - Port to listen on
 * @returns WebSocket server instance
 */
export declare function makeWebSocketServer(localPort: number): WebSocketServer;
/**
 * Read clipboard content
 *
 * @returns Clipboard text content
 */
export declare function readClipboard(): Promise<string>;
/**
 * Write text to clipboard
 *
 * @param text - Text to write to clipboard
 */
export declare function writeClipboard(text: string): Promise<void>;
/**
 * Create a clipboard copy message to send to backend
 *
 * @param whereToSend - Target device ID or "broadcast"
 * @returns Message object ready to send
 */
export declare function doClipboardCopy(whereToSend?: string): Promise<any>;
/**
 * Create a command message to send to backend
 *
 * @param command - Command name
 * @param data - Command data
 * @returns Message object ready to send
 */
export declare function doCommand(command: string, data: any): any;
/**
 * Start the daemon with full configuration
 *
 * @param config - Daemon configuration
 */
export declare function startDaemon(config: DaemonConfig): Promise<void>;
/**
 * Stop the daemon and cleanup resources
 */
export declare function stopDaemon(): void;
export {};
