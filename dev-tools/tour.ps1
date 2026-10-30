# Screenshot tour of every Home screen and menu, for before/after comparisons.
#   .\dev-tools\tour.ps1 -Out shots\audit\light
# Injected keys bypass the ≡ panel's key filter, so the panel can only be captured as it opens.
param([string]$Out = 'shots\tour')
$tv = Join-Path $PSScriptRoot '..\setup-kit\tv.ps1'
New-Item -ItemType Directory -Force $Out | Out-Null
function K([string[]]$keys) { & $tv -Keys $keys | Out-Null }
function S([string]$name) { Start-Sleep -Milliseconds 700; & $tv -Screenshot (Join-Path $Out "$name.png") | Out-Null; "  $name" }
function Home { K HOME; Start-Sleep 1; K HOME; Start-Sleep 1 }

Home; S '01-home-dock'
K DPAD_RIGHT; S '02-home-dock-2'
K DPAD_UP; S '03-top-bar'
K DPAD_LEFT, DPAD_LEFT, DPAD_LEFT, DPAD_LEFT; K DPAD_CENTER; S '04-inputs-menu'
K BACK; K DPAD_RIGHT, DPAD_RIGHT; K DPAD_CENTER; S '05-customize'
K DPAD_CENTER; S '06-background'
K BACK; K DPAD_DOWN; K DPAD_CENTER; S '07-dock-menu'
K BACK; K DPAD_DOWN; K DPAD_CENTER; S '08-apps-menu'
K DPAD_CENTER; S '09-hide-apps'
K BACK; K BACK; K DPAD_DOWN; K DPAD_CENTER; S '10-weather-menu'
K DPAD_DOWN; K DPAD_CENTER; Start-Sleep 1; S '10b-city-dialog'
K BACK; Start-Sleep 1; K BACK; Start-Sleep 1
K BACK; K DPAD_DOWN; K DPAD_CENTER; S '11-look-menu'
K DPAD_DOWN, DPAD_DOWN; K DPAD_CENTER; S '12-app-names-choice'
K BACK; K BACK; K DPAD_DOWN; K DPAD_CENTER; S '13-projector-menu'
K DPAD_DOWN, DPAD_DOWN, DPAD_DOWN; K DPAD_CENTER; S '14-sleep-menu'
K BACK; K BACK; K DPAD_DOWN; K DPAD_CENTER; S '15-reset-menu'
K BACK; K BACK
Home; K DPAD_DOWN; S '16-grid'
K DPAD_DOWN, DPAD_DOWN; S '17-grid-lower'
Home; & $tv -Cmd 'input keyevent --longpress KEYCODE_DPAD_CENTER' | Out-Null; S '18-quick-actions'
K BACK
Home; K MENU; Start-Sleep 1; S '19-panel'
& $tv -Cmd 'input keyevent KEYCODE_MENU' | Out-Null
Home
