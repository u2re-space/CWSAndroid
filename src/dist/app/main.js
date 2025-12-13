"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@nativescript/core");
const app_1 = require("./app");
const settings_1 = require("./daemon/settings");
const buildHomePage = () => {
    const page = new core_1.Page();
    const root = new core_1.GridLayout();
    root.padding = 16;
    root.rows = "auto, auto, auto";
    const label = new core_1.Label();
    label.textWrap = true;
    label.text = "Automata daemon running…";
    label.marginBottom = 12;
    root.addChild(label);
    const status = new core_1.Label();
    status.textWrap = true;
    status.marginBottom = 12;
    status.text = `Status: ${app_1.state.get("status")}`;
    const onStateChange = () => {
        status.text = `Status: ${app_1.state.get("status")}`;
    };
    app_1.state.on(core_1.Observable.propertyChangeEvent, onStateChange);
    page.on(core_1.Page.unloadedEvent, () => {
        app_1.state.off(core_1.Observable.propertyChangeEvent, onStateChange);
    });
    core_1.GridLayout.setRow(status, 1);
    root.addChild(status);
    const settingsBtn = new core_1.Button();
    settingsBtn.text = "Settings";
    settingsBtn.on(core_1.Button.tapEvent, () => {
        page.frame?.navigate({ create: buildSettingsPage });
    });
    core_1.GridLayout.setRow(settingsBtn, 2);
    root.addChild(settingsBtn);
    page.content = root;
    return page;
};
const buildSettingsPage = () => {
    const page = new core_1.Page();
    // `actionBarTitle` isn't supported across all NativeScript versions; set title via ActionBar.
    if (page.actionBar)
        page.actionBar.title = "Settings";
    const scroll = new core_1.ScrollView();
    const root = new core_1.StackLayout();
    root.padding = 16;
    const title = new core_1.Label();
    title.text = "Connection";
    title.fontSize = 18;
    title.fontWeight = "bold";
    root.addChild(title);
    const settings = (0, settings_1.loadSettings)();
    const endpointLabel = new core_1.Label();
    endpointLabel.text = "Server URL (http(s)://host:port)";
    root.addChild(endpointLabel);
    const endpoint = new core_1.TextField();
    endpoint.hint = "http://192.168.0.200:8080";
    endpoint.text = settings.endpointHttp;
    endpoint.autocorrect = false;
    endpoint.autocapitalizationType = "none";
    root.addChild(endpoint);
    const authTitle = new core_1.Label();
    authTitle.text = "Auth (optional)";
    authTitle.fontSize = 18;
    authTitle.fontWeight = "bold";
    authTitle.marginTop = 8;
    root.addChild(authTitle);
    const userIdLabel = new core_1.Label();
    userIdLabel.text = "User ID";
    root.addChild(userIdLabel);
    const userId = new core_1.TextField();
    userId.hint = "e.g. alice";
    userId.text = settings.userId;
    userId.autocorrect = false;
    userId.autocapitalizationType = "none";
    root.addChild(userId);
    const userKeyLabel = new core_1.Label();
    userKeyLabel.text = "User Key / Token";
    root.addChild(userKeyLabel);
    const userKey = new core_1.TextField();
    userKey.hint = "secret";
    userKey.text = settings.userKey;
    userKey.secure = true;
    userKey.autocorrect = false;
    userKey.autocapitalizationType = "none";
    root.addChild(userKey);
    const cryptoTitle = new core_1.Label();
    cryptoTitle.text = "Encryption (optional)";
    cryptoTitle.fontSize = 18;
    cryptoTitle.fontWeight = "bold";
    cryptoTitle.marginTop = 8;
    root.addChild(cryptoTitle);
    const encryptRow = new core_1.GridLayout();
    encryptRow.columns = "*, auto";
    const encryptLabel = new core_1.Label();
    encryptLabel.text = "Encrypt payloads";
    core_1.GridLayout.setColumn(encryptLabel, 0);
    encryptRow.addChild(encryptLabel);
    const encryptSwitch = new core_1.Switch();
    encryptSwitch.checked = !!settings.encryptPayloads;
    core_1.GridLayout.setColumn(encryptSwitch, 1);
    encryptRow.addChild(encryptSwitch);
    root.addChild(encryptRow);
    const encryptionKeyLabel = new core_1.Label();
    encryptionKeyLabel.text = "Encryption Key";
    root.addChild(encryptionKeyLabel);
    const encryptionKey = new core_1.TextField();
    encryptionKey.hint = "symmetric key material";
    encryptionKey.text = settings.encryptionKey;
    encryptionKey.secure = true;
    encryptionKey.autocorrect = false;
    encryptionKey.autocapitalizationType = "none";
    root.addChild(encryptionKey);
    const status = new core_1.Label();
    status.text = "";
    status.color = new core_1.Color("#666666");
    status.marginTop = 8;
    root.addChild(status);
    const save = new core_1.Button();
    save.text = "Save & Reconnect";
    save.on(core_1.Button.tapEvent, async () => {
        const ep = (endpoint.text || "").trim();
        if (!ep) {
            status.text = "Server URL is required.";
            return;
        }
        // Persist settings (avoid logging secrets).
        (0, settings_1.saveSettings)({
            endpointHttp: ep,
            userId: (userId.text || "").trim(),
            userKey: (userKey.text || "").trim(),
            encryptPayloads: !!encryptSwitch.checked,
            encryptionKey: (encryptionKey.text || "").trim(),
        });
        status.text = "Saved. Reconnecting…";
        await (0, app_1.restartDaemon)();
        status.text = "Saved. Connection restarted.";
    });
    root.addChild(save);
    const close = new core_1.Button();
    close.text = "Close";
    close.on(core_1.Button.tapEvent, () => page.frame?.goBack());
    root.addChild(close);
    scroll.content = root;
    page.content = scroll;
    return page;
};
if (!global.__automataAppStarted) {
    global.__automataAppStarted = true;
    core_1.Application.run({
        create: () => {
            // Use a root Frame so navigation works reliably (Page-only roots often have no `page.frame`).
            const frame = new core_1.Frame();
            frame.navigate({ create: buildHomePage, clearHistory: true });
            return frame;
        },
    });
}
