## Emulator debug (first time)

### Prereqs
- Android Studio installed (or at least Android SDK + Emulator)
- `ANDROID_HOME` set (run `ns doctor` to verify)

### 1) Start an emulator (AVD)
List available AVDs:

```powershell
cd C:\Projects\Automata-JS\client\native-script
npm run android:emu:list
```

If you already have one (you do: `Medium_Phone_API_36.1`), start it from Android Studio’s **Device Manager**,
or run the emulator manually:

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Medium_Phone_API_36.1
```

### 2) Verify the emulator is visible

```powershell
adb devices
ns devices android
```

You should see an entry like `emulator-5554`.

### Troubleshooting: `adb.exe: device offline` (common on first boot)
Your emulator log shows **two different adb paths** being used (SDK adb + `D:\Tools\adb\adb.exe`). That often causes
the “offline” loop.

1) Check which `adb` is being used:

```powershell
npm run android:adb:where
```

2) Reset adb:

```powershell
npm run android:adb:reset
```

3) If you still see `device offline`:
- **Close** any Android Studio / Device Manager windows
- Ensure `ANDROID_HOME\platform-tools` is **first** in your PATH (and temporarily remove `D:\Tools\adb` from PATH)
- Cold boot the emulator once (Android Studio → Device Manager → ▼ → **Cold Boot Now**)

### 3) Run the app on the emulator

```powershell
npm run android:emu:run
```

### 4) Debug (inspector)
Run in debug mode with the inspector enabled:

```powershell
npm run android:emu:debug
```

Then open Chrome and go to:
- `chrome://inspect/#devices`

You should see the NativeScript app listed. Click **inspect** to open DevTools.

### 5) Logs (when debugging startup issues)

```powershell
npm run android:log
```


