type ClipboardApi = {
    read: () => Promise<string>;
    copy: (text: string) => Promise<void>;
};
export type ClipboardWatcher = {
    start: () => void;
    stop: () => void;
    setTextSilently: (text: string) => Promise<void>;
};
export declare const createClipboardWatcher: (clipboard: ClipboardApi, onChange: (text: string) => void) => ClipboardWatcher;
export {};
