/**
 * LEGACY FILE (kept for reference)
 *
 * The daemon has been refactored into `app/daemon/*` and bootstrapped via `app/main.ts`.
 * This old file relied on spawning a Deno proxy process and is no longer the default entrypoint.
 */
type NativeChildProcess = any;
type WebSocketServerType = any;
interface DaemonConfig {
    denoModulePath: string;
    denoLocalPort: number;
    backendServerURL: string;
    deviceId: string;
}
/**
 * Run a process with the given command and arguments
 */
export declare function runProcess(command: string, ...args: string[]): NativeChildProcess;
/**
 * Initialize and start the Deno module
 *
 * @param serverURL - Backend server URL
 * @param modulePath - Path to Deno connection module (default: connection.ts in deno-proxy)
 * @param localPort - Local port for WebSocket communication (default: 9000)
 * @returns The spawned Deno process
 */
export declare function useDenoModule(serverURL: string, modulePath?: string, localPort?: number): NativeChildProcess;
/**
 * Create a local WebSocket server for Deno module communication
 *
 * @param localPort - Port to listen on
 * @returns WebSocket server instance
 */
export declare function makeWebSocketServer(localPort: number): WebSocketServerType;
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
 * Monitor clipboard for changes
 */
export declare function monitorClipboard(): void;
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
