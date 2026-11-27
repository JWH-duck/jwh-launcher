param(
    [string]$Src,
    [string]$Dst,
    [int]$Scale = 2
)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile($Src)
$dw = $src.Width * $Scale; $dh = $src.Height * $Scale
$dst = [System.Drawing.Bitmap]::new($dw, $dh)
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.DrawImage($src, 0, 0, $dst.Width, $dst.Height)
$g.Dispose()
$dst.Save($Dst, [System.Drawing.Imaging.ImageFormat]::Png)
$dst.Dispose(); $src.Dispose()
"SAVED $Dst $($dst.Width)x$($dst.Height)"
