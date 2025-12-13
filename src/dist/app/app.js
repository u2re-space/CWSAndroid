"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.state = exports.restartDaemon = void 0;
const core_1 = require("@nativescript/core");
const daemon_1 = require("./daemon");
const settings_1 = require("./daemon/settings");
const logger_1 = require("./daemon/logger");
const state = new core_1.Observable();
exports.state = state;
state.set("status", "starting");
const daemon = (0, daemon_1.createDaemon)();
core_1.Application.on(core_1.Application.launchEvent, async () => {
    const settings = (0, settings_1.loadSettings)();
    (0, logger_1.setLogLevel)(settings.logLevel);
    logger_1.log.info("app launch");
    try {
        await daemon.start();
        state.set("status", "running");
    }
    catch (e) {
        logger_1.log.error("daemon start failed", e);
        state.set("status", "error");
    }
});
core_1.Application.on(core_1.Application.exitEvent, async () => {
    logger_1.log.info("app exit");
    await daemon.stop();
});
const restartDaemon = async () => {
    state.set("status", "restarting");
    try {
        await daemon.stop();
        await daemon.start();
        state.set("status", "running");
    }
    catch (e) {
        logger_1.log.error("daemon restart failed", e);
        state.set("status", "error");
    }
};
exports.restartDaemon = restartDaemon;
