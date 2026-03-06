# vbc-pack (Vibe Coding Pack) — IOClientAndroid

This pack is a small set of **Windows (PowerShell)** + **Android (Termux)** helper scripts to make ADB + logging workflows “one-command”.

## What this supports

- **AI prompting (Termux)**: bundle device info + recent logs into your clipboard to paste into Cursor/AI.
- **Wireless ADB helper (Windows + Termux)**:
  - Windows: `adb pair`, `adb connect` helpers.
  - Windows: install latest built APK via `adb install -r`.
  - Termux: connect using clipboard/shared text (for workflows where you keep the `ip:port` on-phone).
- **SSH Termux → Windows**: helper script + a Windows setup script (optional).
- **VSCode/Cursor**: `.vscode/tasks.json` (adb/devices/logcat) + `.vscode/launch.json`.

> Repo app id: `space.u2re.cws`

---

## VSCode/Cursor quick workflow

- **Tasks** (Cmd/Ctrl+Shift+P → “Tasks: Run Task”):
  - `Android: Logcat (Android runtime)`
  - `Android: ADB reset (kill/start)`
  - `Android: Install latest APK (adb install -r)`

-- **Debug**: use Android Studio or standard ADB-based workflows.

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

### Pair (QR code)

If you prefer **“Pair device with QR code”**, you can generate and use the pairing QR from Windows terminal:

```powershell
pwsh -NoProfile -File .\vbc-pack\windows\vbc-make-qr.ps1 -AdbQr
```

This will try (in order):

- `adb-wireless pair` (install: `cargo install adb-wireless`)
- `adb-wifi` / `python -m adb_wifi` (install: `python -m pip install adb-wifi-py`)

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

### Optional: route ADB connect through Windows (via SSH)

If you want the **ADB connection to happen on Windows** (i.e. run `adb connect ...` on `U2RE@192.168.0.120`), enable:

- `VBC_ADB_SSH_ENABLE=1`

Optional overrides:

- `VBC_ADB_SSH_TARGET` (default `U2RE@192.168.0.120`)
- `VBC_ADB_SSH_PORT` (default `22`)

Then:

- Opening `adb://<ip:port>` will run `adb connect <ip:port>` **on Windows via SSH**
- You can also force it per-link using: `adbssh://<ip:port>`

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

It also includes a **Windows SSH / cursor-agent** connection hint block (defaults to `U2RE@192.168.0.120:22` and `C:\Projects\IOClientAndroid`). You can override what it prints by setting (in Termux):

- `VBC_WIN_SSH_USER`, `VBC_WIN_SSH_HOST`, `VBC_WIN_SSH_PORT`
- `VBC_WIN_WORKDIR` (Windows path to the folder you opened in Cursor/VS Code)

---

## SSH Termux → Windows

### Optional: setup Windows OpenSSH server (admin)

```powershell
pwsh -NoProfile -File .\vbc-pack\windows\vbc-ssh-server-setup.ps1
```

### Connect from Termux

```sh
# Default (configured for this repo/workflow):
vbc-pack/termux/vbc-ssh-to-windows.sh

# Override just the host (keeps default user/port):
vbc-pack/termux/vbc-ssh-to-windows.sh 192.168.0.120

# Explicit:
vbc-pack/termux/vbc-ssh-to-windows.sh U2RE 192.168.0.120 22

# Single-arg form:
vbc-pack/termux/vbc-ssh-to-windows.sh U2RE@192.168.0.120:22
```

It also supports environment overrides:

- `VBC_SSH_USER`, `VBC_SSH_HOST`, `VBC_SSH_PORT`

If you installed `~/.termux/termux-url-opener`, you can also “share/open” an SSH URL like:

- `ssh://U2RE@192.168.0.120:22`


