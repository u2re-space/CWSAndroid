import { Application, Observable } from "@nativescript/core";
import { createDaemon } from "./daemon";
import { loadSettings } from "./daemon/settings";
import { log, setLogLevel } from "./daemon/logger";

const state = new Observable();
state.set("status", "starting");

const daemon = createDaemon();

Application.on(Application.launchEvent, async () => {
    const settings = loadSettings();
    setLogLevel(settings.logLevel);
    log.info("app launch");
    try {
        await daemon.start();
        state.set("status", "running");
    } catch (e) {
        log.error("daemon start failed", e);
        state.set("status", "error");
    }
});

Application.on(Application.exitEvent, async () => {
    log.info("app exit");
    await daemon.stop();
});

export const restartDaemon = async () => {
    state.set("status", "restarting");
    try {
        await daemon.stop();
        await daemon.start();
        state.set("status", "running");
    } catch (e) {
        log.error("daemon restart failed", e);
        state.set("status", "error");
    }
};

export { state };


