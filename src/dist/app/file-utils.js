"use strict";
/**
 * File Utilities for NativeScript
 *
 * Provides functions to copy files from app assets to external storage
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.copyDenoProxyFiles = copyDenoProxyFiles;
exports.denoProxyFilesExist = denoProxyFilesExist;
exports.getDenoProxyPath = getDenoProxyPath;
const core_1 = require("@nativescript/core");
/**
 * Copy deno-proxy files from assets to external storage
 *
 * Target: /storage/emulated/0/Android/data/com.u2re.automata/files/deno-proxy/
 *
 * @returns Promise that resolves when files are copied
 */
async function copyDenoProxyFiles() {
    try {
        // Get the app's external files directory
        // On Android, this should be: /storage/emulated/0/Android/data/com.u2re.automata/files/
        const externalFilesDir = getExternalFilesDirectory();
        const denoProxyDir = core_1.path.join(externalFilesDir, "deno-proxy");
        // Create deno-proxy directory if it doesn't exist
        if (!core_1.File.exists(denoProxyDir)) {
            try {
                // Get parent directory and folder name
                const lastSlash = denoProxyDir.lastIndexOf("/");
                if (lastSlash > 0) {
                    const parentDir = denoProxyDir.substring(0, lastSlash);
                    const folderName = denoProxyDir.substring(lastSlash + 1);
                    const parentFolder = core_1.Folder.fromPath(parentDir);
                    parentFolder.getFolder(folderName);
                }
            }
            catch (error) {
                console.warn(`[FileUtils] Could not create directory ${denoProxyDir}, continuing anyway:`, error);
            }
        }
        // Files to copy from assets
        const filesToCopy = [
            "main.ts",
            "connection.ts",
            "crypto-utils.ts",
            "deno.json"
        ];
        // Copy each file from assets to external storage
        for (const fileName of filesToCopy) {
            try {
                // Read from assets (assets are bundled in the app)
                // In NativeScript, assets are accessed via the app's internal assets
                // We need to use Android's AssetManager to read assets
                const assetContent = await readAssetFile(fileName);
                if (assetContent) {
                    const targetFile = core_1.File.fromPath(core_1.path.join(denoProxyDir, fileName));
                    await targetFile.writeText(assetContent);
                    console.log(`[FileUtils] Copied ${fileName} to ${denoProxyDir}`);
                }
                else {
                    console.warn(`[FileUtils] Could not read asset file: ${fileName}`);
                }
            }
            catch (error) {
                console.error(`[FileUtils] Error copying ${fileName}:`, error);
            }
        }
        console.log(`[FileUtils] Deno-proxy files copied to ${denoProxyDir}`);
    }
    catch (error) {
        console.error(`[FileUtils] Error copying deno-proxy files:`, error);
        throw error;
    }
}
/**
 * Read a file from Android assets
 *
 * @param fileName - Name of the file to read from assets
 * @returns Promise that resolves to file content as string, or null if not found
 */
async function readAssetFile(fileName) {
    try {
        // Use Android's AssetManager to read from assets
        const context = global.__native?.android?.currentContext ||
            global.__native?.android?.app?.context;
        if (!context) {
            console.warn("[FileUtils] Android context not available");
            return null;
        }
        const assetManager = context.getAssets();
        const inputStream = assetManager.open(`deno-proxy/${fileName}`);
        // Read the entire file
        const buffer = new ArrayBuffer(1024);
        let content = "";
        let bytesRead;
        while ((bytesRead = inputStream.read(buffer)) > 0) {
            const chunk = new Uint8Array(buffer, 0, bytesRead);
            content += String.fromCharCode.apply(null, Array.from(chunk));
        }
        inputStream.close();
        return content;
    }
    catch (error) {
        console.error(`[FileUtils] Error reading asset file ${fileName}:`, error);
        return null;
    }
}
/**
 * Check if deno-proxy files exist in external storage
 *
 * @returns Promise that resolves to true if all files exist, false otherwise
 */
async function denoProxyFilesExist() {
    try {
        const externalFilesDir = getExternalFilesDirectory();
        const denoProxyDir = core_1.path.join(externalFilesDir, "deno-proxy");
        const requiredFiles = [
            "main.ts",
            "connection.ts",
            "crypto-utils.ts",
            "deno.json"
        ];
        for (const fileName of requiredFiles) {
            const filePath = core_1.path.join(denoProxyDir, fileName);
            if (!core_1.File.exists(filePath)) {
                return false;
            }
        }
        return true;
    }
    catch (error) {
        console.error(`[FileUtils] Error checking deno-proxy files:`, error);
        return false;
    }
}
/**
 * Get the Android external files directory path
 *
 * @returns Path to external files directory
 */
function getExternalFilesDirectory() {
    try {
        // Use Android's getExternalFilesDir() to get the exact path
        const context = global.__native?.android?.currentContext ||
            global.__native?.android?.app?.context;
        if (context) {
            const externalFilesDir = context.getExternalFilesDir(null);
            if (externalFilesDir) {
                return externalFilesDir.getAbsolutePath();
            }
        }
        // Fallback to knownFolders if context is not available
        const externalFilesDir = core_1.knownFolders.externalDocuments();
        return externalFilesDir.path;
    }
    catch (error) {
        console.error("[FileUtils] Error getting external files directory:", error);
        // Fallback
        const externalFilesDir = core_1.knownFolders.externalDocuments();
        return externalFilesDir.path;
    }
}
/**
 * Get the path to the deno-proxy directory in external storage
 *
 * @returns Path to deno-proxy directory
 */
function getDenoProxyPath() {
    const externalFilesDir = getExternalFilesDirectory();
    return core_1.path.join(externalFilesDir, "deno-proxy");
}
