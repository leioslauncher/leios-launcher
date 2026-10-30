# Ten minutes (or -Minutes) of ordinary remote use on Home, for the "memory after use" number:
# moving through the dock and rows, opening each Customize section and a submenu, then Back.
# It never presses OK on an app or a setting, so nothing is opened or changed.
#   .\dev-tools\qa-soak.ps1 -Minutes 10
param([int]$Minutes = 10)
. (Join-Path $PSScriptRoot 'nav.ps1')
$end = (Get-Date).AddMinutes($Minutes)
$round = 0
while ((Get-Date) -lt $end) {
    $round++
    GoHome
    K DPAD_RIGHT, DPAD_RIGHT, DPAD_RIGHT, DPAD_LEFT, DPAD_LEFT, DPAD_LEFT
    K DPAD_DOWN, DPAD_RIGHT, DPAD_RIGHT, DPAD_DOWN, DPAD_LEFT, DPAD_UP, DPAD_UP, BACK
    OpenCustomize
    # Each section: in (Right), down a few rows, Back out, next section.
    for ($s = 0; $s -lt 7; $s++) {
        K DPAD_RIGHT
        Start-Sleep -Milliseconds 300
        K DPAD_DOWN, DPAD_DOWN, DPAD_DOWN, DPAD_UP, DPAD_UP, DPAD_UP
        K BACK
        Start-Sleep -Milliseconds 200
        K DPAD_DOWN
    }
    K BACK, BACK
    K MENU
    Start-Sleep -Milliseconds 400
    K DPAD_DOWN, DPAD_DOWN, DPAD_DOWN, DPAD_UP, DPAD_UP, DPAD_UP
    K MENU
}
"rounds: $round"
