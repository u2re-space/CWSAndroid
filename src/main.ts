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
    TextField,
    TextView,
} from "@nativescript/core";
import { restartDaemon, state } from "./app";
import { loadSettings, saveSettings } from "./daemon/settings";
import { Clipboard } from "@nativescript-use/nativescript-clipboard";

// NativeScript Android still needs a root view for the Activity.
// Keep the UI minimal while the daemon runs in the background.
declare const global: any;

const buildHomePage = () => {
    const page = new Page();

    const root = new GridLayout();
    root.padding = 16;
    root.rows = "auto, auto, auto, auto";
    root.columns = "*, *";

    const label = new Label();
    label.textWrap = true;
    label.text = "Automata daemon running…";
    label.marginBottom = 12;
    GridLayout.setColumnSpan(label, 2);
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
    GridLayout.setColumnSpan(status, 2);
    root.addChild(status);

    const settingsBtn = new Button();
    settingsBtn.text = "Outgoing";
    settingsBtn.on(Button.tapEvent, () => {
        page.frame?.navigate({ create: buildOutgoingSettingsPage });
    });
    GridLayout.setRow(settingsBtn, 2);
    GridLayout.setColumn(settingsBtn, 0);
    root.addChild(settingsBtn);

    const incomingBtn = new Button();
    incomingBtn.text = "Incoming";
    incomingBtn.on(Button.tapEvent, () => {
        page.frame?.navigate({ create: buildIncomingPage });
    });
    GridLayout.setRow(incomingBtn, 2);
    GridLayout.setColumn(incomingBtn, 1);
    root.addChild(incomingBtn);

    page.content = root;
    return page;
};

const buildOutgoingSettingsPage = () => {
    const page = new Page();
    // `actionBarTitle` isn't supported across all NativeScript versions; set title via ActionBar.
    if (page.actionBar) page.actionBar.title = "Outgoing";

    const scroll = new ScrollView();
    const root = new StackLayout();
    root.padding = 16;

    const title = new Label();
    title.text = "Clipboard broadcast";
    title.fontSize = 18;
    title.fontWeight = "bold";
    root.addChild(title);

    const settings = loadSettings();

    const destinationsLabel = new Label();
    destinationsLabel.text = "Destination URLs (one per line; optional)";
    destinationsLabel.marginTop = 4;
    root.addChild(destinationsLabel);
    const destinations = new TextView();
    destinations.hint = "http://100.90.155.65:8080/clipboard\nhttp://100.119.87.49:8080/clipboard";
    destinations.text = (settings.destinations || []).join("\n");
    destinations.autocorrect = false;
    destinations.autocapitalizationType = "none";
    destinations.minHeight = 80;
    root.addChild(destinations);

    const hubTitle = new Label();
    hubTitle.text = "Hub (optional)";
    hubTitle.fontSize = 18;
    hubTitle.fontWeight = "bold";
    hubTitle.marginTop = 8;
    root.addChild(hubTitle);

    const hubLabel = new Label();
    hubLabel.text = "Hub dispatch URL (POST /core/ops/http/dispatch). If set, broadcast goes via hub instead of direct-to-peers.";
    hubLabel.textWrap = true;
    root.addChild(hubLabel);
    const hubUrl = new TextField();
    hubUrl.hint = "http://192.168.0.200:8080/core/ops/http/dispatch";
    hubUrl.text = settings.hubDispatchUrl;
    hubUrl.autocorrect = false;
    hubUrl.autocapitalizationType = "none";
    root.addChild(hubUrl);

    const status = new Label();
    status.text = "";
    status.color = new Color("#666666");
    status.marginTop = 8;
    root.addChild(status);

    const testHub = new Button();
    testHub.text = "Test Hub";
    testHub.on(Button.tapEvent, async () => {
        const url = (hubUrl.text || "").trim();
        if (!url) {
            status.text = "Set Hub dispatch URL first.";
            return;
        }
        status.text = "Testing hub…";
        try {
            const resp = await fetch(url, {
                method: "POST",
                headers: { "Content-Type": "application/json; charset=utf-8" },
                body: JSON.stringify({ requests: [] }),
            });
            const body = await resp.text().catch(() => "");
            status.text = `Hub response: ${resp.status}\n${body.slice(0, 200)}`;
        } catch (e: any) {
            status.text = `Hub error: ${e?.message || String(e)}`;
        }
    });
    root.addChild(testHub);

    const save = new Button();
    save.text = "Save & Reconnect";
    save.on(Button.tapEvent, async () => {
        // Persist settings (avoid logging secrets).
        const destLines = (destinations.text || "")
            .split(/\r?\n/g)
            .map((s) => (s || "").trim())
            .filter(Boolean);
        saveSettings({
            destinations: destLines,
            hubDispatchUrl: (hubUrl.text || "").trim(),
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

const getLocalIpAddresses = (): string[] => {
    try {
        const out: string[] = [];
        const en = (java as any).net.NetworkInterface.getNetworkInterfaces();
        while (en.hasMoreElements()) {
            const ni = en.nextElement();
            const addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                const addr = addrs.nextElement();
                if (addr.isLoopbackAddress()) continue;
                const host = addr.getHostAddress();
                if (!host) continue;
                // Skip IPv6 link-local noise
                if (host.includes("%")) continue;
                out.push(String(host));
            }
        }
        // de-dup
        return Array.from(new Set(out)).sort();
    } catch {
        return [];
    }
};

const buildIncomingPage = () => {
    const page = new Page();
    if (page.actionBar) page.actionBar.title = "Incoming";

    const scroll = new ScrollView();
    const root = new StackLayout();
    root.padding = 16;

    const settings = loadSettings();

    const title = new Label();
    title.text = "Local server (accepts /clipboard, /sms, /core/ops/http/dispatch)";
    title.fontSize = 18;
    title.fontWeight = "bold";
    title.textWrap = true;
    root.addChild(title);

    const portHttpLabel = new Label();
    portHttpLabel.text = "HTTP port";
    portHttpLabel.marginTop = 8;
    root.addChild(portHttpLabel);
    const portHttp = new TextField();
    portHttp.hint = "8080";
    portHttp.keyboardType = "number";
    portHttp.text = String(settings.listenPortHttp || 8080);
    root.addChild(portHttp);

    const portHttpsLabel = new Label();
    portHttpsLabel.text = "Port 8443 (plain HTTP unless you add TLS later)";
    portHttpsLabel.marginTop = 6;
    root.addChild(portHttpsLabel);
    const portHttps = new TextField();
    portHttps.hint = "8443";
    portHttps.keyboardType = "number";
    portHttps.text = String(settings.listenPortHttps || 8443);
    root.addChild(portHttps);

    const tokenLabel = new Label();
    tokenLabel.text = "Auth token (optional; checked on inbound requests)";
    tokenLabel.marginTop = 8;
    root.addChild(tokenLabel);
    const token = new TextField();
    token.hint = "secret";
    token.text = settings.authToken;
    token.secure = true;
    token.autocorrect = false;
    token.autocapitalizationType = "none";
    root.addChild(token);

    const tlsTitle = new Label();
    tlsTitle.text = "TLS (optional)";
    tlsTitle.fontSize = 18;
    tlsTitle.fontWeight = "bold";
    tlsTitle.marginTop = 12;
    root.addChild(tlsTitle);

    const tlsNote = new Label();
    tlsNote.text = "If enabled, port 8443 will use TLS using a PKCS12 keystore packaged in Android assets. MacroDroid must trust the certificate (self-signed usually won't).";
    tlsNote.textWrap = true;
    root.addChild(tlsNote);

    const tlsEnabled = new TextField();
    tlsEnabled.hint = "TLS enabled? (true/false)";
    tlsEnabled.text = String(!!settings.tlsEnabled);
    tlsEnabled.autocorrect = false;
    tlsEnabled.autocapitalizationType = "none";
    root.addChild(tlsEnabled);

    const ksPathLabel = new Label();
    ksPathLabel.text = "Keystore asset path (e.g. tls/server.p12)";
    ksPathLabel.marginTop = 6;
    root.addChild(ksPathLabel);
    const ksPath = new TextField();
    ksPath.hint = "tls/server.p12";
    ksPath.text = settings.tlsKeystoreAssetPath;
    ksPath.autocorrect = false;
    ksPath.autocapitalizationType = "none";
    root.addChild(ksPath);

    const ksPassLabel = new Label();
    ksPassLabel.text = "Keystore password";
    root.addChild(ksPassLabel);
    const ksPass = new TextField();
    ksPass.hint = "password";
    ksPass.secure = true;
    ksPass.text = settings.tlsKeystorePassword;
    ksPass.autocorrect = false;
    ksPass.autocapitalizationType = "none";
    root.addChild(ksPass);

    const urlsTitle = new Label();
    urlsTitle.text = "Your device URLs (share these; avoids hardcoding 192.168.x.x)";
    urlsTitle.fontSize = 18;
    urlsTitle.fontWeight = "bold";
    urlsTitle.marginTop = 12;
    root.addChild(urlsTitle);

    const ips = getLocalIpAddresses();
    const clip = new Clipboard();
    if (ips.length === 0) {
        const none = new Label();
        none.text = "No IP addresses detected yet (connect to Wi‑Fi/Tailscale and reopen this page).";
        none.textWrap = true;
        root.addChild(none);
    } else {
        for (const ip of ips) {
            const baseHttp = `http://${ip}:${Number(portHttp.text || settings.listenPortHttp || 8080)}`;
            const base8443 = `http://${ip}:${Number(portHttps.text || settings.listenPortHttps || 8443)}`;
            const block = new StackLayout();
            block.marginTop = 8;

            const l = new Label();
            l.text = `${ip}\n- ${baseHttp}/clipboard\n- ${baseHttp}/sms\n- ${baseHttp}/core/ops/http/dispatch\n- ${base8443}/core/ops/http/dispatch`;
            l.textWrap = true;
            block.addChild(l);

            const copyBtn = new Button();
            copyBtn.text = "Copy base URL";
            copyBtn.on(Button.tapEvent, async () => {
                await clip.copy(baseHttp);
            });
            block.addChild(copyBtn);

            root.addChild(block);
        }
    }

    const status = new Label();
    status.text = "";
    status.color = new Color("#666666");
    status.marginTop = 8;
    root.addChild(status);

    const save = new Button();
    save.text = "Save & Restart server";
    save.on(Button.tapEvent, async () => {
        const nextHttp = Number((portHttp.text || "").trim() || "0");
        const nextHttps = Number((portHttps.text || "").trim() || "0");
        if (!nextHttp || !nextHttps) {
            status.text = "Ports are required.";
            return;
        }
        saveSettings({
            listenPortHttp: nextHttp,
            listenPortHttps: nextHttps,
            authToken: (token.text || "").trim(),
            tlsEnabled: String(tlsEnabled.text || "").trim().toLowerCase() === "true",
            tlsKeystoreAssetPath: (ksPath.text || "").trim(),
            tlsKeystorePassword: (ksPass.text || "").trim(),
        });
        status.text = "Saved. Restarting…";
        await restartDaemon();
        status.text = "Saved. Server restarted.";
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


