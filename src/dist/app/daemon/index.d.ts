interface Daemon {
    start(): Promise<void>;
    stop(): Promise<void>;
}
export declare const createDaemon: () => Daemon;
export {};
