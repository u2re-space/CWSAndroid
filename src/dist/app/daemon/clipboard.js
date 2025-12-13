"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createClipboardWatcher = void 0;
const logger_1 = require("./logger");
const createClipboardWatcher = (clipboard, onChange) => {
    let timer = null;
    let last = "";
    let suppressNext = false;
    const poll = async () => {
        try {
            const text = (await clipboard.read()) || "";
            if (suppressNext) {
                suppressNext = false;
                last = text;
                return;
            }
            if (text !== last) {
                last = text;
                if (text.trim())
                    onChange(text);
            }
        }
        catch (e) {
            logger_1.log.warn("clipboard poll failed", e);
        }
    };
    const start = () => {
        if (timer)
            return;
        timer = setInterval(poll, 750);
        poll();
    };
    const stop = () => {
        if (!timer)
            return;
        clearInterval(timer);
        timer = null;
    };
    const setTextSilently = async (text) => {
        suppressNext = true;
        await clipboard.copy(text);
        last = text;
    };
    return { start, stop, setTextSilently };
};
exports.createClipboardWatcher = createClipboardWatcher;
