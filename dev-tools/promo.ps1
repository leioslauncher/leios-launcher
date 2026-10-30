# Turns raw projector screenshots (shots\promo\raw) into postable 1920x1080 images (shots\promo\out):
# headline, framed screenshot with a soft shadow, personal details painted out.
#   .\dev-tools\promo.ps1
. (Join-Path $PSScriptRoot 'promo-lib.ps1')
$out = Join-Path $root 'shots\promo\out'

function Canvas([string]$title, [string]$sub) {
    $c = Backdrop 1920 1080
    $c[1].DrawString($title, (Font 64 'Bold'), [Drawing.Brushes]::White, 236, 52)
    $c[1].DrawString($sub, (Font 30), (New-Object Drawing.SolidBrush $valueInk), 240, 138)
    $c
}

$single = @(
    @('hero-bloom.png', '01-home.png', 'Your projector, decluttered.', 'A clean home screen for Google TV projectors.'),
    @('panel-orbit.png', '02-quick-settings.png', 'Quick settings, in any app.', 'Press the menu button for volume, brightness, inputs and sleep without leaving what you are watching.'),
    @('customize-v2.png', '03-customize.png', 'Make it yours.', 'Backgrounds, layout, apps, look, projector tools, locks and backups, all from the remote.'),
    @('options-prism.png', '04-app-options.png', 'Hold OK for app options.', 'Open, move, hide or close any app right from Home.'),
    @('arrange-dunes.png', '05-arrange.png', 'Your apps, your order.', 'Move any app anywhere, or sort them A to Z or by how often you use them.'),
    @('projector-tools.png', '08-projector-tools.png', 'Projector tools in one place.', 'Picture, brightness, keystone, sleep timer, test patterns and what happens when it wakes.'),
    @('screensaver.png', '09-screensaver.png', 'A calm screensaver.', "A dim clock and date instead of Google's photo screensaver.")
)
foreach ($s in $single) {
    $shot = Prepared $s[0]
    $c = Canvas $s[2] $s[3]
    Frame $c[1] $shot 240 222 1440 810
    SavePng $c[0] $out $s[1]
    $c[1].Dispose(); $c[0].Dispose(); $shot.Dispose()
}

$split = SplitShot
$c = Canvas 'Light by day, dark by night.' 'Automatic appearance switches at sunrise and sunset.'
Frame $c[1] $split 240 222 1440 810
SavePng $c[0] $out '06-light-dark.png'
$c[1].Dispose(); $c[0].Dispose(); $split.Dispose()

$c = Canvas 'New shape backgrounds.' 'Bloom, Orbit, Prism and Dunes, each in a light and a dark version.'
ShapesGrid $c[1] 240 222 1440 | Out-Null
SavePng $c[0] $out '07-backgrounds.png'
$c[1].Dispose(); $c[0].Dispose()
