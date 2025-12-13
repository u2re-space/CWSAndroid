"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.log = exports.setLogLevel = void 0;
const levelOrder = { debug: 10, info: 20, warn: 30, error: 40 };
let currentLevel = "debug";
const setLogLevel = (level) => {
    currentLevel = level;
};
exports.setLogLevel = setLogLevel;
const shouldLog = (level) => levelOrder[level] >= levelOrder[currentLevel];
exports.log = {
    debug: (...args) => shouldLog("debug") && console.log("[automata:debug]", ...args),
    info: (...args) => shouldLog("info") && console.log("[automata:info]", ...args),
    warn: (...args) => shouldLog("warn") && console.warn("[automata:warn]", ...args),
    error: (...args) => shouldLog("error") && console.error("[automata:error]", ...args),
};
