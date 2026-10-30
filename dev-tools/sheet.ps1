# Contact sheets: 2x2 grids of screenshots at half size, labelled, for quick review.
#   .\dev-tools\sheet.ps1 -Dir shots\audit\light
param([string]$Dir, [int]$PerSheet = 4)
Add-Type -AssemblyName System.Drawing
$files = Get-ChildItem $Dir -Filter '*.png' | Where-Object { $_.Name -notlike 'sheet-*' } | Sort-Object Name
$font = New-Object Drawing.Font('Segoe UI', 16, [Drawing.FontStyle]::Bold)
for ($s = 0; $s * $PerSheet -lt $files.Count; $s++) {
    $sheet = New-Object Drawing.Bitmap 1920, 1080
    $g = [Drawing.Graphics]::FromImage($sheet)
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.Clear([Drawing.Color]::Magenta)
    $batch = $files | Select-Object -Skip ($s * $PerSheet) -First $PerSheet
    $i = 0
    foreach ($f in $batch) {
        $img = [Drawing.Image]::FromFile($f.FullName)
        $x = ($i % 2) * 960; $y = [math]::Floor($i / 2) * 540
        $g.DrawImage($img, $x, $y, 960, 540)
        $g.FillRectangle([Drawing.Brushes]::Black, $x, $y, 260, 30)
        $g.DrawString($f.BaseName, $font, [Drawing.Brushes]::Yellow, $x + 4, $y + 2)
        $img.Dispose(); $i++
    }
    $out = Join-Path $Dir ("sheet-{0}.png" -f ($s + 1))
    $sheet.Save($out); $g.Dispose(); $sheet.Dispose()
    $out
}
