export type AutomataSettings = {
    endpointHttp: string;
    deviceId: string;
    userId: string;
    userKey: string;
    encryptPayloads: boolean;
    encryptionKey: string;
    clipboardSync: boolean;
    contactsSync: boolean;
    smsSync: boolean;
    shareTarget: boolean;
    logLevel: "debug" | "info" | "warn" | "error";
    syncIntervalSec: number;
};
export declare const defaultSettings: () => AutomataSettings;
export declare const loadSettings: () => AutomataSettings;
export declare const saveSettings: (next: Partial<AutomataSettings>) => AutomataSettings;
