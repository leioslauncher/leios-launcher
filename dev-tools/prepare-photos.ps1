# Turn photos into 1920x1080 slideshow backgrounds for the projector. Originals are never changed.
#   .\dev-tools\prepare-photos.ps1 -Source H:\ -Dest .\slideshow-photos
# Landscape: cover-cropped to 16:9, crop placed slightly above centre (keeps heads in frame).
# Portrait/square: the whole photo centred over a blurred, darkened copy of itself.
param(
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$Dest,
    [int]$Width = 1920,
    [int]$Height = 1080,
    [int]$Quality = 88
)
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Dest | Out-Null

$codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq 'image/jpeg'
$enc = New-Object Drawing.Imaging.EncoderParameters 1
$enc.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality), ([long]$Quality)

function New-Canvas([int]$w, [int]$h) {
    $bm = New-Object Drawing.Bitmap $w, $h, ([Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [Drawing.Graphics]::FromImage($bm)
    $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
    return $bm, $g
}

# Scale to cover the target box; $yBias 0..1 picks which part of the overflow is kept (0.5 = centre).
function Draw-Cover($g, $img, [int]$w, [int]$h, [double]$yBias) {
    $s = [Math]::Max($w / $img.Width, $h / $img.Height)
    $dw = $img.Width * $s; $dh = $img.Height * $s
    $g.DrawImage($img, [single](($w - $dw) / 2), [single](($h - $dh) * $yBias), [single]$dw, [single]$dh)
}

$files = Get-ChildItem $Source -File | Where-Object { $_.Extension -match '^\.(jpe?g|png)$' }
$i = 0
foreach ($f in $files) {
    $i++
    $img = [Drawing.Image]::FromFile($f.FullName)
    try {
        # Apply the camera's rotation flag for real, so nothing shows up sideways.
        if ($img.PropertyIdList -contains 0x0112) {
            switch ([BitConverter]::ToUInt16($img.GetPropertyItem(0x0112).Value, 0)) {
                3 { $img.RotateFlip('Rotate180FlipNone') }
                6 { $img.RotateFlip('Rotate90FlipNone') }
                8 { $img.RotateFlip('Rotate270FlipNone') }
            }
        }
        $out, $g = New-Canvas $Width $Height
        if ($img.Width -gt $img.Height * 1.05) {
            Draw-Cover $g $img $Width $Height 0.4
        } else {
            # Blurred fill: shrink to a tiny cover image, then stretch it back up smoothly.
            $tiny, $tg = New-Canvas 48 27
            Draw-Cover $tg $img 48 27 0.5
            $tg.Dispose()
            $g.DrawImage($tiny, -40, -40, $Width + 80, $Height + 80)
            $tiny.Dispose()
            $shade = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(110, 0, 0, 0))
            $g.FillRectangle($shade, 0, 0, $Width, $Height)
            $shade.Dispose()
            # The whole photo, fitted to the height, with a soft shadow behind it.
            $s = [Math]::Min($Width / $img.Width, $Height / $img.Height)
            $dw = [int]($img.Width * $s); $dh = [int]($img.Height * $s)
            $x = [int](($Width - $dw) / 2); $y = [int](($Height - $dh) / 2)
            for ($k = 1; $k -le 6; $k++) {
                $b = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(18, 0, 0, 0))
                $g.FillRectangle($b, $x - $k * 4, $y - $k * 2, $dw + $k * 8, $dh + $k * 4)
                $b.Dispose()
            }
            $g.DrawImage($img, $x, $y, $dw, $dh)
        }
        $g.Dispose()
        $out.Save((Join-Path $Dest ([IO.Path]::GetFileNameWithoutExtension($f.Name) + '.jpg')), $codec, $enc)
        $out.Dispose()
    } finally {
        $img.Dispose()
    }
    if ($i % 25 -eq 0) { "$i / $($files.Count)" }
}
"done: $i photos -> $Dest"
