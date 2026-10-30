# Video background test on a debug build: records a clip, makes it the background, measures, explains.
#   .\dev-tools\qa-video.ps1 -Setup       make the clip and switch the background to it
#   .\dev-tools\qa-video.ps1 -Measure     focus-move frames plus what the system is doing
#   .\dev-tools\qa-video.ps1 -Restore <focus.xml>   put settings back and delete the clip
param([switch]$Setup, [switch]$Measure, [string]$Restore)
$here = Split-Path $PSScriptRoot
$adb = Join-Path $here 'platform-tools\adb.exe'
if (-not $env:TV_HOST) { throw 'Set $env:TV_HOST to the TV''s IP address first.' }
$d = "$($env:TV_HOST):$(if ($env:TV_PORT) { $env:TV_PORT } else { '5555' })"

function Push-Prefs([string]$content) {
    $f = Join-Path $here 'logs\f.xml'
    [IO.File]::WriteAllText($f, $content)
    $sh = Join-Path $here 'logs\qa-put.sh'
    [IO.File]::WriteAllText($sh, ("chmod 644 /data/local/tmp/f.xml`n" +
        "run-as tv.leios.launcher sh -c 'kill `$(pidof tv.leios.launcher)'`n" +
        "run-as tv.leios.launcher cp /data/local/tmp/f.xml shared_prefs/focus.xml`n" +
        "rm /data/local/tmp/f.xml`n" +
        "am start -a android.intent.action.MAIN -c android.intent.category.HOME > /dev/null`n"))
    & $adb -s $d push $f /data/local/tmp/f.xml | Out-Null
    & $adb -s $d push $sh /data/local/tmp/qa-put.sh | Out-Null
    & $adb -s $d shell 'sh /data/local/tmp/qa-put.sh; rm /data/local/tmp/qa-put.sh'
}

if ($Setup) {
    & $adb -s $d shell 'mkdir -p /sdcard/Movies; screenrecord --time-limit 6 --size 1280x720 /sdcard/Movies/qa-clip.mp4; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/qa-clip.mp4 >/dev/null; sleep 3' | Out-Null
    $id = ((& $adb -s $d shell 'content query --uri content://media/external/video/media --projection _id') | Select-String '_id=(\d+)').Matches[0].Groups[1].Value
    $s = (& $adb -s $d shell 'run-as tv.leios.launcher cat shared_prefs/focus.xml') -join "`n"
    $s = [regex]::Replace($s, '<string name="bg">[^<]*</string>', '<string name="bg">video</string>')
    $s = [regex]::Replace($s, '<string name="bg_light">[^<]*</string>', '<string name="bg_light">video</string>')
    $s = [regex]::Replace($s, '(name="bg_video">)[^<]*(<)', "`${1}content://media/external/video/media/$id`$2")
    Push-Prefs $s
    Start-Sleep 10
    "video background on, clip id $id"
}
if ($Measure) {
    & (Join-Path $PSScriptRoot 'qa-measure.ps1') -What frames | Select-String 'Total|Janky|50th|90th'
    $sh = Join-Path $here 'logs\qa-why.sh'
    [IO.File]::WriteAllText($sh, ("dumpsys gfxinfo tv.leios.launcher | grep -E 'Number (Missed|High|Slow|Frame)' `n" +
        "echo ---; dumpsys SurfaceFlinger > /data/local/tmp/sf.txt; grep -nE 'Display .*\(active\)|refresh-rate|Display Modes|GPU composition|HWC|h/w composer|composition' /data/local/tmp/sf.txt | head -12`n" +
        "echo ---; grep -E 'SurfaceView|tv.leios.launcher|^ *\(.*\) ' /data/local/tmp/sf.txt | cut -c1-110 | tail -14; rm /data/local/tmp/sf.txt`n" +
        "echo ---; top -H -b -n 1 -m 10 | tail -11 | cut -c1-90`n"))
    & $adb -s $d push $sh /data/local/tmp/qa-why.sh | Out-Null
    & $adb -s $d shell 'sh /data/local/tmp/qa-why.sh; rm /data/local/tmp/qa-why.sh'
}
if ($Restore) {
    Push-Prefs ([IO.File]::ReadAllText($Restore))
    & $adb -s $d shell "rm -f /sdcard/Movies/qa-clip.mp4; content delete --uri content://media/external/file --where `"_data LIKE '%qa-clip%'`""
    'settings restored, clip deleted'
}
