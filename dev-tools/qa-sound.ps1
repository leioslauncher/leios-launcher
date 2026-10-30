# Checks Home's sounds on a debug build, which logs every sound that plays.
#   .\dev-tools\qa-sound.ps1 -What presses   sounds from single and fast presses
#   .\dev-tools\qa-sound.ps1 -What away      nothing plays or stays loaded while another app is in front
param([ValidateSet('presses', 'away')][string]$What)
$adb = Join-Path (Split-Path $PSScriptRoot) 'platform-tools\adb.exe'
if (-not $env:TV_HOST) { throw 'Set $env:TV_HOST to the TV''s IP address first.' }
$s = "$($env:TV_HOST):$(if ($env:TV_PORT) { $env:TV_PORT } else { '5555' })"
. (Join-Path $PSScriptRoot 'nav.ps1')

# Debug builds log every sound that really plays.
function Played { (& $adb -s $s shell 'logcat -d -s Sounds:D' | Select-String ' play ').Count }
function ClearLog { & $adb -s $s shell 'logcat -c' }
function Player { $h = (& $adb -s $s shell 'pidof tv.leios.launcher').Trim(); $m = (& $adb -s $s shell 'dumpsys audio') | Select-String "type:android.media.SoundPool u/pid:\d+/$h " | Select-Object -First 1; [bool]$m }

function Keys([string]$key, [int]$n) {
    $list = ((1..$n) | ForEach-Object { "KEYCODE_$key" }) -join ' '
    & $adb -s $s shell "input keyevent $list"
}

switch ($What) {
    'presses' {
        GoHome; Start-Sleep 1
        ClearLog
        foreach ($i in 1..4) { K DPAD_RIGHT; Start-Sleep -Milliseconds 400 }
        foreach ($i in 1..4) { K DPAD_LEFT; Start-Sleep -Milliseconds 400 }
        "8 single moves: $(Played) sounds"; ClearLog
        $list = ((1..20) | ForEach-Object { 'KEYCODE_DPAD_RIGHT KEYCODE_DPAD_LEFT' }) -join ' '
        $t = Measure-Command { & $adb -s $s shell "input keyevent $list" }
        Start-Sleep 1
        "40 fast moves in $([int]$t.TotalMilliseconds) ms: $(Played) sounds"
    }
    'away' {
        GoHome; Start-Sleep 1
        "on Home, samples loaded: $(Player)"
        & $adb -s $s shell 'am start -n com.android.tv.settings/.MainSettings > /dev/null'
        Start-Sleep 2
        "in Settings, samples loaded: $(Player)"; ClearLog
        Keys 'DPAD_DOWN' 6; Keys 'DPAD_UP' 6
        Start-Sleep 1
        "sounds from Home while Settings in front: $(Played)"
        GoHome
    }
}
