# Performance numbers for QA_REPORT.md, measured the same way before and after.
#   .\dev-tools\qa-measure.ps1 -What start      cold and warm start of Home (3 runs each)
#   .\dev-tools\qa-measure.ps1 -What frames     focus moves on Home: janky frames, percentiles
#   .\dev-tools\qa-measure.ps1 -What memory     PSS / Java / native / graphics
# Cold start kills Home's process the way a crash would (never `am force-stop`, which would turn
# the ≡ helper off); Android starts it again and rebinds the helper.
param([ValidateSet('start', 'frames', 'memory')][string]$What)
$tv = Join-Path $PSScriptRoot '..\setup-kit\tv.ps1'
$adb = Join-Path (Split-Path $PSScriptRoot) 'platform-tools\adb.exe'
if (-not $env:TV_HOST) { throw 'Set $env:TV_HOST to the TV''s IP address first.' }
$serial = "$($env:TV_HOST):$(if ($env:TV_PORT) { $env:TV_PORT } else { '5555' })"
. (Join-Path $PSScriptRoot 'nav.ps1')
function Sh([string]$cmd) { & $adb -s $serial shell $cmd }

function HomeStart([switch]$Cold) {
    # Something else in front first, so Home is really started, not just brought forward.
    Sh 'am start -W -n com.android.tv.settings/.MainSettings >/dev/null' | Out-Null
    Start-Sleep 2
    $pidBefore = Sh 'pidof tv.leios.launcher'
    if ($Cold) {
        Sh 'run-as tv.leios.launcher sh -c ''kill $(pidof tv.leios.launcher)''' | Out-Null
        Start-Sleep 3
    }
    Sh 'logcat -c' | Out-Null
    $out = Sh 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME'
    $total = ($out | Select-String 'TotalTime: (\d+)').Matches.Groups[1].Value
    Start-Sleep 4
    $drawn = (Sh "logcat -d | grep -F 'Fully drawn tv.leios.launcher' | tail -1") -replace '.*: \+', ''
    $pidAfter = Sh 'pidof tv.leios.launcher'
    [pscustomobject]@{ Kind = $(if ($Cold) { 'cold' } else { 'warm' }); NewProcess = $pidBefore -ne $pidAfter
        TotalTime = [int]$total; FullyDrawn = $drawn }
}

switch ($What) {
    'start' {
        & $tv -Cmd 'logcat -c' | Out-Null
        1..3 | ForEach-Object { HomeStart -Cold }
        1..3 | ForEach-Object { HomeStart }
        GoHome
    }
    'frames' {
        GoHome
        Start-Sleep 2
        & $tv -Cmd 'dumpsys gfxinfo tv.leios.launcher reset >/dev/null' | Out-Null
        foreach ($i in 1..3) { K DPAD_RIGHT, DPAD_RIGHT, DPAD_RIGHT, DPAD_RIGHT, DPAD_LEFT, DPAD_LEFT, DPAD_LEFT, DPAD_LEFT, DPAD_DOWN, DPAD_UP }
        & $tv -Cmd "dumpsys gfxinfo tv.leios.launcher | grep -E 'Total frames|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile'"
    }
    'memory' {
        & $tv -Cmd "dumpsys meminfo tv.leios.launcher | grep -E 'TOTAL PSS|Java Heap:|Native Heap:|Graphics:|TOTAL:' | head -6"
    }
}

