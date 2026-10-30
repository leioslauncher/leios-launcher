# Install a built launcher on the TV and make it Home. Build it first: see the README.
#   $env:TV_HOST = '192.0.2.10'
#   .\setup-kit\install.ps1
#   .\setup-kit\install.ps1 -Apk launcher\app\build\outputs\apk\release\app-release.apk
# Needs network ADB on the TV (Developer options > USB debugging on).
param([string]$Apk = (Join-Path $PSScriptRoot '..\launcher\app\build\outputs\apk\debug\app-debug.apk'))
$ErrorActionPreference = 'Stop'
if (-not $env:TV_HOST) { throw 'Set $env:TV_HOST to the TV''s IP address first.' }
if (-not (Test-Path $Apk)) { throw "No APK at $Apk. Build it first." }
$tv = Join-Path $PSScriptRoot 'tv.ps1'
$adb = Join-Path $PSScriptRoot '..\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$serial = "$($env:TV_HOST):$(if ($env:TV_PORT) { $env:TV_PORT } else { '5555' })"
& $tv -Cmd 'true' | Out-Null
# adb install can hang on Android 14 waiting for an on-screen prompt, so push + pm install.
& $adb -s $serial push $Apk /data/local/tmp/leios.apk | Out-Null
& $tv -Cmd 'pm install -r -g /data/local/tmp/leios.apk'
& $tv -Cmd 'rm /data/local/tmp/leios.apk'
& $tv -Cmd 'pm grant tv.leios.launcher android.permission.ACCESS_FINE_LOCATION'
& $tv -Cmd 'pm grant tv.leios.launcher android.permission.READ_MEDIA_IMAGES'
& $tv -Cmd 'pm grant tv.leios.launcher android.permission.READ_MEDIA_VIDEO'
# Only the first install needs to claim Home; after that, leave the assignment alone.
$currentHome = (& $tv -Cmd 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1)
if ($currentHome -notmatch 'tv\.leios\.launcher/') { & $tv -Cmd 'cmd package set-home-activity tv.leios.launcher/.MainActivity' }
& $tv -Cmd 'am start -a android.intent.action.MAIN -c android.intent.category.HOME' | Out-Null
Start-Sleep 2
& $tv -Cmd "dumpsys activity activities | grep -m1 mFocusedApp"
