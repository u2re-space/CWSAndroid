export type LogLevel = "debug" | "info" | "warn" | "error";
export declare const setLogLevel: (level: LogLevel) => void;
export declare const log: {
    debug: (...args: any[]) => false | void;
    info: (...args: any[]) => false | void;
    warn: (...args: any[]) => false | void;
    error: (...args: any[]) => false | void;
};
