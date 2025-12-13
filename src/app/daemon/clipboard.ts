import { log } from "./logger";

type ClipboardApi = {
    read: () => Promise<string>;
    copy: (text: string) => Promise<void>;
};

export type ClipboardWatcher = {
    start: () => void;
    stop: () => void;
    setTextSilently: (text: string) => Promise<void>;
};

export const createClipboardWatcher = (clipboard: ClipboardApi, onChange: (text: string) => void): ClipboardWatcher => {
    let timer: any = null;
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
                if (text.trim()) onChange(text);
            }
        } catch (e) {
            log.warn("clipboard poll failed", e);
        }
    };

    const start = () => {
        if (timer) return;
        timer = setInterval(poll, 750);
        poll();
    };

    const stop = () => {
        if (!timer) return;
        clearInterval(timer);
        timer = null;
    };

    const setTextSilently = async (text: string) => {
        suppressNext = true;
        await clipboard.copy(text);
        last = text;
    };

    return { start, stop, setTextSilently };
};


