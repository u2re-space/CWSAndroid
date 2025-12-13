/**
 * File Utilities for NativeScript
 *
 * Provides functions to copy files from app assets to external storage
 */
/**
 * Copy deno-proxy files from assets to external storage
 *
 * Target: /storage/emulated/0/Android/data/com.u2re.automata/files/deno-proxy/
 *
 * @returns Promise that resolves when files are copied
 */
export declare function copyDenoProxyFiles(): Promise<void>;
/**
 * Check if deno-proxy files exist in external storage
 *
 * @returns Promise that resolves to true if all files exist, false otherwise
 */
export declare function denoProxyFilesExist(): Promise<boolean>;
/**
 * Get the path to the deno-proxy directory in external storage
 *
 * @returns Path to deno-proxy directory
 */
export declare function getDenoProxyPath(): string;
