# vbc-pack (Vibe Coding Pack) — IOClientAndroid

This pack is a small set of **Windows (PowerShell)** + **Android (Termux)** helper scripts to make NativeScript + ADB + logging workflows “one-command”.

## What this supports

- **AI prompting (Termux)**: bundle device info + recent logs into your clipboard to paste into Cursor/AI.
- **Wireless ADB helper (Windows + Termux)**:
  - Windows: `adb pair`, `adb connect` helpers.
  - Windows: install latest built APK via `adb install -r`.
  - Termux: connect using clipboard/shared text (for workflows where you keep the `ip:port` on-phone).
- **SSH Termux → Windows**: helper script + a Windows setup script (optional).
- **VSCode/Cursor**: `.vscode/tasks.json` (adb/devices/logcat/debug) + improved `.vscode/launch.json`.

> Repo app id: `com.u2re.ioclient`

---

## VSCode/Cursor quick workflow

- **Tasks** (Cmd/Ctrl+Shift+P → “Tasks: Run Task”):
  - `Android: Emulator debug (inspect)`
  - `Android: Logcat (NativeScript/JS/Chromium)`
  - `Android: ADB reset (kill/start)`
  - `Android: Install latest APK (adb install -r)`

- **Debug** (Run and Debug sidebar):
  - `NativeScript: Debug Android (Emulator, Inspect)`

When debugging, open Chrome:

- `chrome://inspect/#devices`

---

## Windows: Wireless ADB (pair / connect)

### Pair

1) On Android: Developer options → **Wireless debugging** → **Pair device with pairing code**
2) On Windows:

```powershell
pwsh -NoProfile -File .\vbc-pack\windows\vbc-adb-pair.ps1
```

### Connect (after pairing)

```powershell
pwsh -NoProfile -File .\vbc-pack\windows\vbc-adb-connect.ps1
```

Tips:
- If you have multiple `adb.exe` on PATH, run:
  - `npm run android:adb:where`
- If devices go “offline”, run:
  - `npm run android:adb:reset`

---

## Termux: install prereqs

In Termux:

```sh
pkg update
pkg install -y termux-api openssh android-tools coreutils grep sed awk
```

Then copy scripts from `vbc-pack/termux/` onto the phone (or `git clone` the repo) and make them executable:

```sh
chmod +x vbc-pack/termux/*.sh
```

---

## Termux: “share target” → adb connect

Android share often works best via **URL open**, so this pack supports an `adb://` scheme.

1) Install the URL opener (Termux uses this to handle “Open with Termux”):

```sh
mkdir -p ~/.termux
cp -f vbc-pack/termux/termux-url-opener ~/.termux/termux-url-opener
chmod +x ~/.termux/termux-url-opener
termux-reload-settings
```

2) Share (or open) something like:

- `adb://192.168.1.50:39345`

It will run:

- `adb connect 192.168.1.50:39345`

If you prefer clipboard:

```sh
vbc-pack/termux/vbc-adb-connect-from-clipboard.sh
```

---

## Termux: AI prompt bundle (copy logs/info to clipboard)

```sh
vbc-pack/termux/vbc-ai-prompt.sh
```

This copies a ready-to-paste block into your clipboard (device info + recent `logcat` snippet).

---

## SSH Termux → Windows

### Optional: setup Windows OpenSSH server (admin)

```powershell
pwsh -NoProfile -File .\vbc-pack\windows\vbc-ssh-server-setup.ps1
```

### Connect from Termux

```sh
vbc-pack/termux/vbc-ssh-to-windows.sh <windowsUser> <windowsHostOrIP>
```


