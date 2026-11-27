param(
    [string]$Src,
    [string]$Dst,
    [int]$X, [int]$Y, [int]$W, [int]$H,
    [int]$Scale = 3
)
Add-Type -AssemblyName System.Drawing
$srcImg = [System.Drawing.Image]::FromFile($Src)
$dw = $W * $Scale
$dh = $H * $Scale
[System.Drawing.Bitmap]$dstImg = New-Object System.Drawing.Bitmap($dw, $dh)
$g = [System.Drawing.Graphics]::FromImage($dstImg)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$srcRect = New-Object System.Drawing.Rectangle($X, $Y, $W, $H)
$dstRect = New-Object System.Drawing.Rectangle(0, 0, $dw, $dh)
$g.DrawImage($srcImg, $dstRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$dstImg.Save($Dst, [System.Drawing.Imaging.ImageFormat]::Png)
$dstImg.Dispose()
$srcImg.Dispose()
"SAVED $Dst"
