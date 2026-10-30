# Monkey random input on Home in chunks, waking the projector before each one: Monkey's random
# keys sometimes include the power button, which would end a single long run.
#   .\dev-tools\qa-monkey.ps1 -Chunks 10 -Events 500
# Prints per-chunk results and the totals of crashes and ANRs for tv.leios.launcher.
param([int]$Chunks = 10, [int]$Events = 500, [int]$Seed = 1000)
$adb = Join-Path (Split-Path $PSScriptRoot) 'platform-tools\adb.exe'
if (-not $env:TV_HOST) { throw 'Set $env:TV_HOST to the TV''s IP address first.' }
$s = "$($env:TV_HOST):$(if ($env:TV_PORT) { $env:TV_PORT } else { '5555' })"
$log = Join-Path (Split-Path $PSScriptRoot) 'logs'
New-Item -ItemType Directory -Force $log | Out-Null
$crashes = 0; $anrs = 0; $sent = 0
for ($c = 1; $c -le $Chunks; $c++) {
    & $adb disconnect $s *> $null
    & $adb connect $s *> $null
    & $adb -s $s shell 'input keyevent KEYCODE_WAKEUP; sleep 2; input keyevent KEYCODE_HOME; logcat -c; logcat -b crash -c' 2>$null
    $out = & $adb -s $s shell "monkey -p tv.leios.launcher -c android.intent.category.HOME --throttle 100 --pct-syskeys 0 --pct-anyevent 0 -s $($Seed + $c) -v $Events" 2>&1
    $done = ($out | Select-String 'Events injected: (\d+)').Matches | ForEach-Object { [int]$_.Groups[1].Value }
    $sent += [int]($done | Select-Object -Last 1)
    $crash = & $adb -s $s shell 'logcat -b crash -d' 2>$null
    $main = & $adb -s $s shell 'logcat -d' 2>$null
    $c1 = ($crash | Select-String 'Process: tv.leios.launcher').Count
    $a1 = ($main | Select-String 'ANR in tv.leios.launcher').Count
    $crashes += $c1; $anrs += $a1
    if ($c1 -gt 0) { $crash | Set-Content -Encoding utf8 (Join-Path $log "monkey-crash-$c.txt") }
    "chunk $c  seed $($Seed + $c)  injected $([int]($done | Select-Object -Last 1))  crashes $c1  ANRs $a1"
}
"TOTAL injected $sent  crashes $crashes  ANRs $anrs"
