import { Application } from "@nativescript/core";
import { ensureAndroidPermission } from "./android-permissions";
import { log } from "./logger";

export type SmsItem = {
    id: string;
    address: string;
    body: string;
    date: number;
    type: number;
};

const isAndroid = () => typeof globalThis !== "undefined" && !!(globalThis as any).android;

/**
 * Note: Android 10+ heavily restricts SMS access unless the app is the default SMS app.
 * We keep this best-effort for debugging and older devices.
 */
export const readSmsInbox = async (limit = 50): Promise<SmsItem[]> => {
    if (!isAndroid()) return [];

    const ok = await ensureAndroidPermission((android as any).Manifest.permission.READ_SMS);
    if (!ok) return [];

    const activity = Application.android.foregroundActivity || Application.android.startActivity;
    if (!activity) return [];

    const resolver = activity.getContentResolver();
    const uri = (android as any).net.Uri.parse("content://sms");
    const results: SmsItem[] = [];

    try {
        const cursor = resolver.query(uri, [], "", [], "date DESC");
        if (!cursor) return [];

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
    } catch (e) {
        log.warn("readSmsInbox failed", e);
        return results;
    }
};


