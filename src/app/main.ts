import {
    Application,
    Button,
    Color,
    Frame,
    GridLayout,
    Label,
    Observable,
    Page,
    ScrollView,
    StackLayout,
    Switch,
    TextField,
} from "@nativescript/core";
import { restartDaemon, state } from "./app";
import { loadSettings, saveSettings } from "./daemon/settings";

// NativeScript Android still needs a root view for the Activity.
// Keep the UI minimal while the daemon runs in the background.
declare const global: any;

const buildHomePage = () => {
    const page = new Page();

    const root = new GridLayout();
    root.padding = 16;
    root.rows = "auto, auto, auto";

    const label = new Label();
    label.textWrap = true;
    label.text = "Automata daemon running…";
    label.marginBottom = 12;
    root.addChild(label);

    const status = new Label();
    status.textWrap = true;
    status.marginBottom = 12;
    status.text = `Status: ${state.get("status")}`;
    const onStateChange = () => {
        status.text = `Status: ${state.get("status")}`;
    };
    state.on(Observable.propertyChangeEvent, onStateChange);
    page.on(Page.unloadedEvent, () => {
        state.off(Observable.propertyChangeEvent, onStateChange);
    });
    GridLayout.setRow(status, 1);
    root.addChild(status);

    const settingsBtn = new Button();
    settingsBtn.text = "Settings";
    settingsBtn.on(Button.tapEvent, () => {
        page.frame?.navigate({ create: buildSettingsPage });
    });
    GridLayout.setRow(settingsBtn, 2);
    root.addChild(settingsBtn);

    page.content = root;
    return page;
};

const buildSettingsPage = () => {
    const page = new Page();
    // `actionBarTitle` isn't supported across all NativeScript versions; set title via ActionBar.
    if (page.actionBar) page.actionBar.title = "Settings";

    const scroll = new ScrollView();
    const root = new StackLayout();
    root.padding = 16;

    const title = new Label();
    title.text = "Connection";
    title.fontSize = 18;
    title.fontWeight = "bold";
    root.addChild(title);

    const settings = loadSettings();

    const endpointLabel = new Label();
    endpointLabel.text = "Server URL (http(s)://host:port)";
    root.addChild(endpointLabel);
    const endpoint = new TextField();
    endpoint.hint = "http://192.168.0.200:8080";
    endpoint.text = settings.endpointHttp;
    endpoint.autocorrect = false;
    endpoint.autocapitalizationType = "none";
    root.addChild(endpoint);

    const authTitle = new Label();
    authTitle.text = "Auth (optional)";
    authTitle.fontSize = 18;
    authTitle.fontWeight = "bold";
    authTitle.marginTop = 8;
    root.addChild(authTitle);

    const userIdLabel = new Label();
    userIdLabel.text = "User ID";
    root.addChild(userIdLabel);
    const userId = new TextField();
    userId.hint = "e.g. alice";
    userId.text = settings.userId;
    userId.autocorrect = false;
    userId.autocapitalizationType = "none";
    root.addChild(userId);

    const userKeyLabel = new Label();
    userKeyLabel.text = "User Key / Token";
    root.addChild(userKeyLabel);
    const userKey = new TextField();
    userKey.hint = "secret";
    userKey.text = settings.userKey;
    userKey.secure = true;
    userKey.autocorrect = false;
    userKey.autocapitalizationType = "none";
    root.addChild(userKey);

    const cryptoTitle = new Label();
    cryptoTitle.text = "Encryption (optional)";
    cryptoTitle.fontSize = 18;
    cryptoTitle.fontWeight = "bold";
    cryptoTitle.marginTop = 8;
    root.addChild(cryptoTitle);

    const encryptRow = new GridLayout();
    encryptRow.columns = "*, auto";
    const encryptLabel = new Label();
    encryptLabel.text = "Encrypt payloads";
    GridLayout.setColumn(encryptLabel, 0);
    encryptRow.addChild(encryptLabel);
    const encryptSwitch = new Switch();
    encryptSwitch.checked = !!settings.encryptPayloads;
    GridLayout.setColumn(encryptSwitch, 1);
    encryptRow.addChild(encryptSwitch);
    root.addChild(encryptRow);

    const encryptionKeyLabel = new Label();
    encryptionKeyLabel.text = "Encryption Key";
    root.addChild(encryptionKeyLabel);
    const encryptionKey = new TextField();
    encryptionKey.hint = "symmetric key material";
    encryptionKey.text = settings.encryptionKey;
    encryptionKey.secure = true;
    encryptionKey.autocorrect = false;
    encryptionKey.autocapitalizationType = "none";
    root.addChild(encryptionKey);

    const status = new Label();
    status.text = "";
    status.color = new Color("#666666");
    status.marginTop = 8;
    root.addChild(status);

    const save = new Button();
    save.text = "Save & Reconnect";
    save.on(Button.tapEvent, async () => {
        const ep = (endpoint.text || "").trim();
        if (!ep) {
            status.text = "Server URL is required.";
            return;
        }

        // Persist settings (avoid logging secrets).
        saveSettings({
            endpointHttp: ep,
            userId: (userId.text || "").trim(),
            userKey: (userKey.text || "").trim(),
            encryptPayloads: !!encryptSwitch.checked,
            encryptionKey: (encryptionKey.text || "").trim(),
        });

        status.text = "Saved. Reconnecting…";
        await restartDaemon();
        status.text = "Saved. Connection restarted.";
    });
    root.addChild(save);

    const close = new Button();
    close.text = "Close";
    close.on(Button.tapEvent, () => page.frame?.goBack());
    root.addChild(close);

    scroll.content = root;
    page.content = scroll;
    return page;
};

if (!global.__automataAppStarted) {
    global.__automataAppStarted = true;

    Application.run({
        create: () => {
            // Use a root Frame so navigation works reliably (Page-only roots often have no `page.frame`).
            const frame = new Frame();
            frame.navigate({ create: buildHomePage, clearHistory: true });
            return frame;
        },
    });
}


