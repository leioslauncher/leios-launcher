# Setup kit (optional)

These PowerShell scripts change a TV over ADB. **The launcher doesn't need them**, apart from `install.ps1` for side-loading. They were tested only on one JMGO N3 4K (K970AN) running Android 14.

Read the warnings in the [main README](../README.md#before-you-start) first. Everything here is provided as is, at your own risk.

Windows blocks scripts by default. Allow them for one PowerShell window with `powershell -ExecutionPolicy Bypass`, then set your TV's address. Keep network debugging on only while you use these:

```powershell
$env:TV_HOST = '192.0.2.10'
```

| File | What it does |
|---|---|
| `install.ps1` | Installs a built APK, grants its permissions and makes it Home |
| `tv.ps1` | Runs a shell command, presses keys or takes a screenshot |
| `disable.ps1` | Disables packages and logs them. It refuses core Android and known projector packages |
| `restore.ps1` | Re-enables what you disabled, resets Private DNS and the HDR setting, and sets Google TV's home again |
| `adb-closed.ps1` | Checks that network ADB is really closed |
| `disabled-packages.example.txt` | What was disabled on one device. **An example only: review every line for your own** |
| `PROMPT.md` | A prompt for doing this kind of setup with Claude Code |

## Debloating

Packages are disabled, never uninstalled. Disabling the wrong one can break a feature, and on some devices disabling the setup wizard can delete a saved Wi-Fi network.

`disable.ps1` writes `disabled-packages.txt` in this folder. `restore.ps1` reads it. That file is ignored by git, because it's specific to you.

## HDR dark-scene dimming (JMGO only)

On the tested projector, the laser dimmed after a near-black picture. This setting turned that off, after a reboot:

```powershell
.\setup-kit\tv.ps1 -Cmd 'settings put system black_current_enable 0'
```

`restore.ps1` undoes it. Don't use it on other brands.
