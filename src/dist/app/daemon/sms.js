"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.readSmsInbox = void 0;
const core_1 = require("@nativescript/core");
const android_permissions_1 = require("./android-permissions");
const logger_1 = require("./logger");
const isAndroid = () => typeof globalThis !== "undefined" && !!globalThis.android;
/**
 * Note: Android 10+ heavily restricts SMS access unless the app is the default SMS app.
 * We keep this best-effort for debugging and older devices.
 */
const readSmsInbox = async (limit = 50) => {
    if (!isAndroid())
        return [];
    const ok = await (0, android_permissions_1.ensureAndroidPermission)(android.Manifest.permission.READ_SMS);
    if (!ok)
        return [];
    const activity = core_1.Application.android.foregroundActivity || core_1.Application.android.startActivity;
    if (!activity)
        return [];
    const resolver = activity.getContentResolver();
    const uri = android.net.Uri.parse("content://sms");
    const results = [];
    try {
        const cursor = resolver.query(uri, [], "", [], "date DESC");
        if (!cursor)
            return [];
        const idIdx = cursor.getColumnIndex("_id");
        const addrIdx = cursor.getColumnIndex("address");
        const bodyIdx = cursor.getColumnIndex("body");
        const dateIdx = cursor.getColumnIndex("date");
        const typeIdx = cursor.getColumnIndex("type");
        while (cursor.moveToNext() && results.length < limit) {
            results.push({
                id: String(cursor.getString(idIdx)),
                address: String(cursor.getString(addrIdx) || ""),
                body: String(cursor.getString(bodyIdx) || ""),
                date: Number(cursor.getLong(dateIdx) || 0),
                type: Number(cursor.getInt(typeIdx) || 0),
            });
        }
        cursor.close();
        return results;
    }
    catch (e) {
        logger_1.log.warn("readSmsInbox failed", e);
        return results;
    }
};
exports.readSmsInbox = readSmsInbox;
