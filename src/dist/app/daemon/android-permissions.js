"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ensureAndroidPermission = void 0;
const core_1 = require("@nativescript/core");
const logger_1 = require("./logger");
const isAndroid = () => typeof globalThis !== "undefined" && !!globalThis.android;
const ensureAndroidPermission = async (permission) => {
    if (!isAndroid())
        return false;
    const activity = core_1.Application.android.foregroundActivity || core_1.Application.android.startActivity;
    if (!activity)
        return false;
    // android.content.pm.PackageManager.PERMISSION_GRANTED == 0
    const granted = android.content.pm.PackageManager.PERMISSION_GRANTED;
    const ctx = activity;
    const checkSelf = androidx?.core?.content?.ContextCompat?.checkSelfPermission;
    const requestPerms = androidx?.core?.app?.ActivityCompat?.requestPermissions;
    if (!checkSelf || !requestPerms) {
        logger_1.log.warn("androidx ContextCompat/ActivityCompat unavailable; cannot request permission", permission);
        return false;
    }
    const current = checkSelf(ctx, permission);
    if (current === granted)
        return true;
    return await new Promise((resolve) => {
        const REQ = Math.floor(Math.random() * 10000) + 1000;
        const handler = (args) => {
            if (args.requestCode !== REQ)
                return;
            core_1.Application.android.off(core_1.Application.android.activityRequestPermissionsEvent, handler);
            const results = args.grantResults || [];
            resolve(results.length > 0 && results[0] === granted);
        };
        core_1.Application.android.on(core_1.Application.android.activityRequestPermissionsEvent, handler);
        requestPerms(activity, [permission], REQ);
    });
};
exports.ensureAndroidPermission = ensureAndroidPermission;
