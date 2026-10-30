# Run a shell command on the TV, press keys, or take a screenshot.
#   .\setup-kit\tv.ps1 -Cmd 'getprop ro.build.version.sdk'
#   .\setup-kit\tv.ps1 -Keys DPAD_RIGHT,DPAD_DOWN -Screenshot shots\01.png
param(
    [string]$Cmd,
    [string[]]$Keys,
    [string]$Screenshot,
    [string]$TvHost = $env:TV_HOST,
    [string]$TvPort = $(if ($env:TV_PORT) { $env:TV_PORT } else { '5555' })
)
if (-not $TvHost) { throw 'Set $env:TV_HOST to the TV''s IP address first, or pass -TvHost.' }
$adb = Join-Path $PSScriptRoot '..\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$serial = "${TvHost}:${TvPort}"
if (-not ((& $adb devices) -match [regex]::Escape($serial))) { & $adb connect $serial | Out-Null }

if ($Cmd) { & $adb -s $serial shell $Cmd }
foreach ($k in $Keys) {
    & $adb -s $serial shell input keyevent "KEYCODE_$k"
    Start-Sleep -Milliseconds 400
}
if ($Screenshot) {
    if ($Keys) { Start-Sleep -Milliseconds 800 }
    $dir = Split-Path $Screenshot
    if ($dir) { New-Item -ItemType Directory -Force $dir | Out-Null }
    & $adb -s $serial shell screencap -p /data/local/tmp/cyt.png
    & $adb -s $serial pull /data/local/tmp/cyt.png $Screenshot | Out-Null
    & $adb -s $serial shell rm /data/local/tmp/cyt.png
    if (-not (Test-Path $Screenshot) -or (Get-Item $Screenshot).Length -eq 0) {
        Write-Error "Empty screenshot: protected content (Live TV/HDMI/Netflix). Check: dumpsys activity activities | grep topResumedActivity"
        exit 1
    }
    "saved $Screenshot ($((Get-Item $Screenshot).Length) bytes)"
}
