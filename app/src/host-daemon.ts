/**
 * Legacy host daemon (disabled)
 *
 * NativeScript webpack scans the app folder and will attempt to parse/bundle this file even if it is
 * excluded from tsconfig.json. The previous implementation depended on Node-only modules and
 * caused Android builds to fail.
 *
 * The daemon entrypoint is: app/main.ts -> app/app.ts -> app/daemon/*
 */
export {};


