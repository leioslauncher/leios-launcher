# Remote helpers for scripted screenshots. Dot-source it:  . .\dev-tools\nav.ps1
# Injected keys bypass the ≡ panel's key filter, so these only drive Home and its menus.
$script:tv = Join-Path $PSScriptRoot '..\setup-kit\tv.ps1'
function K([string[]]$keys) { & $script:tv -Keys $keys | Out-Null }
function Shot([string]$path, [int]$waitMs = 900) { Start-Sleep -Milliseconds $waitMs; & $script:tv -Screenshot $path | Out-Null; "  $path" }
function GoHome { K HOME; Start-Sleep -Milliseconds 700; K HOME; Start-Sleep -Milliseconds 700 }

# Customize opens on the last section used; this always lands on the first row.
function OpenCustomize { GoHome; K DPAD_UP, DPAD_LEFT, DPAD_LEFT, DPAD_LEFT, DPAD_LEFT, DPAD_RIGHT, DPAD_RIGHT, DPAD_CENTER; Start-Sleep -Milliseconds 500; K DPAD_UP, DPAD_UP, DPAD_UP, DPAD_UP, DPAD_UP, DPAD_UP }

# 0 Dark, 1 Light, 2 Automatic. The screen rebuilds afterwards.
function SetAppearance([int]$i) {
    OpenCustomize; K DPAD_DOWN, DPAD_DOWN, DPAD_DOWN, DPAD_CENTER, DPAD_CENTER, DPAD_CENTER
    K DPAD_UP, DPAD_UP; for ($n = 0; $n -lt $i; $n++) { K DPAD_DOWN }; K DPAD_CENTER; Start-Sleep 3
}

# Background › Shapes › k (0 Bloom, 1 Orbit, 2 Prism, 3 Dunes), for the current look.
function SetShape([int]$k) {
    OpenCustomize; K DPAD_CENTER; Start-Sleep -Milliseconds 400
    K DPAD_UP, DPAD_UP, DPAD_UP, DPAD_DOWN, DPAD_CENTER; Start-Sleep -Milliseconds 400
    K DPAD_UP, DPAD_UP, DPAD_UP; for ($n = 0; $n -lt $k; $n++) { K DPAD_DOWN }
    K DPAD_CENTER; Start-Sleep 2; K BACK, BACK; GoHome
}

# Background › Slideshow from USB › every 30 seconds, for the current look.
function SetSlideshow30 {
    OpenCustomize; K DPAD_CENTER; Start-Sleep -Milliseconds 400
    K DPAD_DOWN, DPAD_DOWN, DPAD_DOWN, DPAD_CENTER; Start-Sleep -Milliseconds 400
    K DPAD_UP, DPAD_UP, DPAD_UP, DPAD_UP, DPAD_UP, DPAD_DOWN, DPAD_CENTER; Start-Sleep 2; K BACK, BACK; GoHome
}

function Prefs { & $script:tv -Cmd "run-as tv.leios.launcher cat shared_prefs/focus.xml | grep -E 'appearance|bg|sort|hidden'" }
