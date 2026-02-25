import { Application } from "@nativescript/core";
import { ensureAndroidPermission } from "./android-permissions";
import { log } from "./logger";

export type ContactItem = {
    id: string;
    name: string;
    phones: string[];
    emails: string[];
};

const isAndroid = () => typeof globalThis !== "undefined" && !!(globalThis as any).android;

export const readContacts = async (): Promise<ContactItem[]> => {
    if (!isAndroid()) return [];

    const ok = await ensureAndroidPermission((android as any).Manifest.permission.READ_CONTACTS);
    if (!ok) return [];

    const activity = Application.android.foregroundActivity || Application.android.startActivity;
    if (!activity) return [];

    const resolver = activity.getContentResolver();
    const Contacts = (android as any).provider.ContactsContract;
    const results: ContactItem[] = [];

    try {
        const cursor = resolver.query(
            Contacts.Contacts.CONTENT_URI,
            [],
            "",
            [],
            ""
        );

        if (!cursor) return [];

        const idIdx = cursor.getColumnIndex(Contacts.Contacts._ID);
        const nameIdx = cursor.getColumnIndex(Contacts.Contacts.DISPLAY_NAME);
        const hasPhoneIdx = cursor.getColumnIndex(Contacts.Contacts.HAS_PHONE_NUMBER);

        while (cursor.moveToNext()) {
            const id = String(cursor.getString(idIdx));
            const name = String(cursor.getString(nameIdx) || "");
            const hasPhone = cursor.getInt(hasPhoneIdx) > 0;

            const phones: string[] = [];
            if (hasPhone) {
                const phoneCursor = resolver.query(
                    Contacts.CommonDataKinds.Phone.CONTENT_URI,
                    [],
                    `${Contacts.CommonDataKinds.Phone.CONTACT_ID} = ?`,
                    [id],
                    ""
                );
                if (phoneCursor) {
                    const numIdx = phoneCursor.getColumnIndex(Contacts.CommonDataKinds.Phone.NUMBER);
                    while (phoneCursor.moveToNext()) {
                        const num = String(phoneCursor.getString(numIdx) || "").trim();
                        if (num) phones.push(num);
                    }
                    phoneCursor.close();
                }
            }

            const emails: string[] = [];
            const emailCursor = resolver.query(
                Contacts.CommonDataKinds.Email.CONTENT_URI,
                [],
                `${Contacts.CommonDataKinds.Email.CONTACT_ID} = ?`,
                [id],
                ""
            );
            if (emailCursor) {
                const emailIdx = emailCursor.getColumnIndex(Contacts.CommonDataKinds.Email.DATA);
                while (emailCursor.moveToNext()) {
                    const e = String(emailCursor.getString(emailIdx) || "").trim();
                    if (e) emails.push(e);
                }
                emailCursor.close();
            }

            results.push({ id, name, phones, emails });
        }

        cursor.close();
        return results;
    } catch (e) {
        log.warn("readContacts failed", e);
        return results;
    }
};


