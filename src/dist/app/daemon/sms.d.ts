export type SmsItem = {
    id: string;
    address: string;
    body: string;
    date: number;
    type: number;
};
/**
 * Note: Android 10+ heavily restricts SMS access unless the app is the default SMS app.
 * We keep this best-effort for debugging and older devices.
 */
export declare const readSmsInbox: (limit?: number) => Promise<SmsItem[]>;
