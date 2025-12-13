import type { AutomataSettings } from "./settings";
export type AutomataMessage = {
    type: "clip" | "sync" | "command" | string;
    from: string;
    to: string;
    mode?: "blind" | "inspect" | "plain";
    action?: string;
    payload?: any;
};
export type SocketClient = {
    socket: any;
    send: (msg: AutomataMessage) => void;
    close: () => void;
};
export declare const connectSocket: (settings: AutomataSettings) => SocketClient;
