export type LogLevel = "debug" | "info" | "warn" | "error";

const levelOrder: Record<LogLevel, number> = { debug: 10, info: 20, warn: 30, error: 40 };
let currentLevel: LogLevel = "debug";

export const setLogLevel = (level: LogLevel) => {
    currentLevel = level;
};

const shouldLog = (level: LogLevel) => levelOrder[level] >= levelOrder[currentLevel];

export const log = {
    debug: (...args: any[]) => shouldLog("debug") && console.log("[automata:debug]", ...args),
    info: (...args: any[]) => shouldLog("info") && console.log("[automata:info]", ...args),
    warn: (...args: any[]) => shouldLog("warn") && console.warn("[automata:warn]", ...args),
    error: (...args: any[]) => shouldLog("error") && console.error("[automata:error]", ...args),
};


