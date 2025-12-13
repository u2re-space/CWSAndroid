"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.readContacts = void 0;
const core_1 = require("@nativescript/core");
const android_permissions_1 = require("./android-permissions");
const logger_1 = require("./logger");
const isAndroid = () => typeof globalThis !== "undefined" && !!globalThis.android;
const readContacts = async () => {
    if (!isAndroid())
        return [];
    const ok = await (0, android_permissions_1.ensureAndroidPermission)(android.Manifest.permission.READ_CONTACTS);
    if (!ok)
        return [];
    const activity = core_1.Application.android.foregroundActivity || core_1.Application.android.startActivity;
    if (!activity)
        return [];
    const resolver = activity.getContentResolver();
    const Contacts = android.provider.ContactsContract;
    const results = [];
    try {
        const cursor = resolver.query(Contacts.Contacts.CONTENT_URI, [], "", [], "");
        if (!cursor)
            return [];
        const idIdx = cursor.getColumnIndex(Contacts.Contacts._ID);
        const nameIdx = cursor.getColumnIndex(Contacts.Contacts.DISPLAY_NAME);
        const hasPhoneIdx = cursor.getColumnIndex(Contacts.Contacts.HAS_PHONE_NUMBER);
        while (cursor.moveToNext()) {
            const id = String(cursor.getString(idIdx));
            const name = String(cursor.getString(nameIdx) || "");
            const hasPhone = cursor.getInt(hasPhoneIdx) > 0;
            const phones = [];
            if (hasPhone) {
                const phoneCursor = resolver.query(Contacts.CommonDataKinds.Phone.CONTENT_URI, [], `${Contacts.CommonDataKinds.Phone.CONTACT_ID} = ?`, [id], "");
                if (phoneCursor) {
                    const numIdx = phoneCursor.getColumnIndex(Contacts.CommonDataKinds.Phone.NUMBER);
                    while (phoneCursor.moveToNext()) {
                        const num = String(phoneCursor.getString(numIdx) || "").trim();
                        if (num)
                            phones.push(num);
                    }
                    phoneCursor.close();
                }
            }
            const emails = [];
            const emailCursor = resolver.query(Contacts.CommonDataKinds.Email.CONTENT_URI, [], `${Contacts.CommonDataKinds.Email.CONTACT_ID} = ?`, [id], "");
            if (emailCursor) {
                const emailIdx = emailCursor.getColumnIndex(Contacts.CommonDataKinds.Email.DATA);
                while (emailCursor.moveToNext()) {
                    const e = String(emailCursor.getString(emailIdx) || "").trim();
                    if (e)
                        emails.push(e);
                }
                emailCursor.close();
            }
            results.push({ id, name, phones, emails });
        }
        cursor.close();
        return results;
    }
    catch (e) {
        logger_1.log.warn("readContacts failed", e);
        return results;
    }
};
exports.readContacts = readContacts;
