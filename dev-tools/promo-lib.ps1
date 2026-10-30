# Shared by promo.ps1 (16:9) and promo-reddit.ps1 (4:5). Dot-source it.
# Loads raw projector screenshots (shots\promo\raw), paints out personal details, and draws frames.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot
$raw = Join-Path $root 'shots\promo\raw'
$pfc = New-Object Drawing.Text.PrivateFontCollection
$pfc.AddFontFile((Join-Path $root 'launcher\app\src\main\assets\fonts\Outfit.ttf'))
$outfit = $pfc.Families[0]
function Font([float]$px, [string]$style = 'Regular') { New-Object Drawing.Font($outfit, $px, [Drawing.FontStyle]::$style, [Drawing.GraphicsUnit]::Pixel) }
function Load([string]$name) { $b = [Drawing.Bitmap]::FromFile((Join-Path $raw $name)); $c = New-Object Drawing.Bitmap $b; $b.Dispose(); $c }
function Gfx($bm) { $g = [Drawing.Graphics]::FromImage($bm); $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAliasGridFit'; $g.PixelOffsetMode = 'HighQuality'; $g }
function Brush([int]$a, [int]$r, [int]$g, [int]$b) { New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb($a, $r, $g, $b)) }

# Paints over text inside a pill or panel row: a left-to-right blend of the colours just outside
# the text, so translucent surfaces over gradients still look continuous. Then writes new text.
function Replace($bm, [int]$x0, [int]$y0, [int]$x1, [int]$y1, [string]$text, [Drawing.Color]$ink, [float]$px, [bool]$right = $false) {
    for ($y = $y0; $y -le $y1; $y++) {
        $l = $bm.GetPixel($x0 - 3, $y); $r = $bm.GetPixel($x1 + 3, $y)
        for ($x = $x0; $x -le $x1; $x++) {
            $t = ($x - $x0) / [double]($x1 - $x0)
            $bm.SetPixel($x, $y, [Drawing.Color]::FromArgb(255,
                [int]($l.R + ($r.R - $l.R) * $t), [int]($l.G + ($r.G - $l.G) * $t), [int]($l.B + ($r.B - $l.B) * $t)))
        }
    }
    if (-not $text) { return }
    $g = Gfx $bm; $f = Font $px 'Regular'
    $size = $g.MeasureString($text, $f)
    $tx = if ($right) { $x1 - $size.Width + 6 } else { $x0 }
    $ty = ($y0 + $y1) / 2 - $size.Height / 2 + 3
    $brush = New-Object Drawing.SolidBrush $ink
    # Drawn twice, a hair apart: the variable font loads at Regular, the launcher uses Medium.
    $g.DrawString($text, $f, $brush, $tx, $ty); $g.DrawString($text, $f, $brush, $tx + 0.7, $ty)
    $g.Dispose()
}

function RoundRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, 2 * $r, 2 * $r, 180, 90); $p.AddArc($x + $w - 2 * $r, $y, 2 * $r, 2 * $r, 270, 90)
    $p.AddArc($x + $w - 2 * $r, $y + $h - 2 * $r, 2 * $r, 2 * $r, 0, 90); $p.AddArc($x, $y + $h - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $p.CloseFigure(); $p
}

# Draws a screenshot (or any image) framed: soft shadow, rounded corners, hairline border.
function Frame($g, $img, [float]$x, [float]$y, [float]$w, [float]$h, [float]$r = 22) {
    for ($i = 12; $i -ge 1; $i--) {
        $sp = RoundRect ($x - $i * 2) ($y - $i * 2 + 18) ($w + $i * 4) ($h + $i * 4) ($r + $i * 2)
        $g.FillPath((Brush 9 0 0 0), $sp)
    }
    $clip = RoundRect $x $y $w $h $r
    $g.SetClip($clip); $g.DrawImage($img, $x, $y, $w, $h); $g.ResetClip()
    $g.DrawPath((New-Object Drawing.Pen ([Drawing.Color]::FromArgb(40, 255, 255, 255), 1.5)), $clip)
}

# Deep blue-black backdrop with a faint accent glow at the top right.
function Backdrop([int]$w, [int]$h) {
    $bm = New-Object Drawing.Bitmap $w, $h
    $g = Gfx $bm
    $bg = New-Object Drawing.Drawing2D.LinearGradientBrush((New-Object Drawing.Point 0, 0), (New-Object Drawing.Point 0, $h),
        [Drawing.Color]::FromArgb(255, 16, 20, 36), [Drawing.Color]::FromArgb(255, 7, 8, 14))
    $g.FillRectangle($bg, 0, 0, $w, $h)
    $glow = New-Object Drawing.Drawing2D.GraphicsPath; $glow.AddEllipse($w * 0.57, -$h * 0.46, $w * 0.68, $h * 0.93)
    $pg = New-Object Drawing.Drawing2D.PathGradientBrush $glow
    $pg.CenterColor = [Drawing.Color]::FromArgb(70, 74, 140, 255); $pg.SurroundColors = @([Drawing.Color]::FromArgb(0, 74, 140, 255))
    $g.FillPath($pg, $glow)
    @($bm, $g)
}

function SavePng($bm, [string]$dir, [string]$name) {
    New-Item -ItemType Directory -Force $dir | Out-Null
    $p = Join-Path $dir $name; $bm.Save($p, [Drawing.Imaging.ImageFormat]::Png); "  $p"
}

# Personal details: the Wi-Fi pill (all Home shots), and per-shot extras.
$darkInk = [Drawing.Color]::FromArgb(255, 242, 244, 248)
$lightInk = [Drawing.Color]::FromArgb(255, 17, 20, 26)
$valueInk = [Drawing.Color]::FromArgb(255, 184, 190, 202)
# $pill: 'full' (top bar visible), 'panel' (the ≡ panel covers the pill from x 1040), 'none'.
function Clean([string]$name, [bool]$light = $false, [string]$pill = 'full') {
    $bm = Load $name
    $ink = $(if ($light) { $lightInk } else { $darkInk })
    if ($pill -eq 'full') { Replace $bm 966 72 1214 128 'Wi-Fi' $ink 34 }
    if ($pill -eq 'panel') {
        Replace $bm 966 72 1038 128 '' $ink 34
        # The panel is 96% opaque, so the name ghosts through it: flatten everything in that strip
        # that's close to the panel colour, leaving the panel's own (much brighter) text alone.
        $bg = $bm.GetPixel(1075, 138); $bgLum = $bg.R * 0.3 + $bg.G * 0.59 + $bg.B * 0.11
        for ($y = 70; $y -le 130; $y++) { for ($x = 1040; $x -le 1220; $x++) {
            $p = $bm.GetPixel($x, $y)
            if (($p.R * 0.3 + $p.G * 0.59 + $p.B * 0.11) -lt $bgLum + 22) { $bm.SetPixel($x, $y, $bg) }
        } }
    }
    $bm
}

# A raw shot with everything personal painted out, ready to frame.
function Prepared([string]$name) {
    $mode = switch ($name) { 'panel-orbit.png' { 'panel' } 'arrange-dunes.png' { 'none' } 'screensaver.png' { 'none' } default { 'full' } }
    $bm = Clean $name ($name -like 'light-*') $mode
    if ($name -eq 'panel-orbit.png') { Replace $bm 1500 508 1726 546 'Connected' $valueInk 34 $true }
    if ($name -eq 'customize-prism.png') { Replace $bm 1560 582 1726 620 'On' $valueInk 34 $true }
    $bm
}

# Home in dark (left half) and light (right half).
function SplitShot {
    $dark = Prepared 'hero-bloom.png'; $light = Prepared 'light-bloom.png'
    $split = New-Object Drawing.Bitmap 1920, 1080
    $sg = Gfx $split
    $sg.DrawImage($dark, 0, 0, 1920, 1080)
    $sg.SetClip((New-Object Drawing.Rectangle 960, 0, 960, 1080)); $sg.DrawImage($light, 0, 0, 1920, 1080); $sg.ResetClip()
    $sg.FillRectangle((Brush 200 255 255 255), 958, 0, 4, 1080)
    $sg.Dispose(); $dark.Dispose(); $light.Dispose()
    $split
}

# The four shape backgrounds in a 2x2 grid inside (x, y, w), each with its name on a chip.
function ShapesGrid($g, [float]$x, [float]$y, [float]$w, [float]$gap = 24) {
    $tw = ($w - $gap) / 2; $th = $tw * 9 / 16
    $names = 'Bloom', 'Orbit', 'Prism', 'Dunes'
    for ($i = 0; $i -lt 4; $i++) {
        $img = Prepared "dark-shape-$i.png"
        $tx = $x + ($i % 2) * ($tw + $gap); $ty = $y + [math]::Floor($i / 2) * ($th + $gap)
        Frame $g $img $tx $ty $tw $th 18
        $f = Font ([math]::Max(22, $tw / 27)) 'Bold'
        $sz = $g.MeasureString($names[$i], $f)
        $chip = RoundRect ($tx + 16) ($ty + $th - $sz.Height - 30) ($sz.Width + 28) ($sz.Height + 14) (($sz.Height + 14) / 2)
        $g.FillPath((Brush 170 10 12 20), $chip)
        $g.DrawString($names[$i], $f, [Drawing.Brushes]::White, $tx + 30, $ty + $th - $sz.Height - 23)
        $img.Dispose()
    }
    $y + 2 * $th + $gap
}
