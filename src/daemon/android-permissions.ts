import { Application } from "@nativescript/core";
import { log } from "./logger";

const isAndroid = () => typeof globalThis !== "undefined" && !!(globalThis as any).android;

export const ensureAndroidPermission = async (permission: string): Promise<boolean> => {
    if (!isAndroid()) return false;

    const activity = Application.android.foregroundActivity || Application.android.startActivity;
    if (!activity) return false;

    // android.content.pm.PackageManager.PERMISSION_GRANTED == 0
    const granted = (android as any).content.pm.PackageManager.PERMISSION_GRANTED;
    const ctx = activity;
    const checkSelf = (androidx as any)?.core?.content?.ContextCompat?.checkSelfPermission;
    const requestPerms = (androidx as any)?.core?.app?.ActivityCompat?.requestPermissions;
    if (!checkSelf || !requestPerms) {
        log.warn("androidx ContextCompat/ActivityCompat unavailable; cannot request permission", permission);
        return false;
    }

    const current = checkSelf(ctx, permission);
    if (current === granted) return true;

    return await new Promise<boolean>((resolve) => {
        const REQ = Math.floor(Math.random() * 10_000) + 1000;

        const handler = (args: any) => {
            if (args.requestCode !== REQ) return;
            Application.android.off(Application.android.activityRequestPermissionsEvent, handler);
            const results = args.grantResults || [];
            resolve(results.length > 0 && results[0] === granted);
        };

        Application.android.on(Application.android.activityRequestPermissionsEvent, handler);
        requestPerms(activity, [permission], REQ);
    });
};


