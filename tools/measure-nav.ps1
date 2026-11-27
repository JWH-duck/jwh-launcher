param([string]$Src)
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap($Src)
$w = $bmp.Width; $h = $bmp.Height
"Size: ${w}x${h}"

# 逐列统计亮像素(图标白线)数量；亮线判定 R,G,B 均 >=170
$colBright = New-Object int[] $w
for ($x=0; $x -lt $w; $x++) {
    $c=0
    for ($y=0; $y -lt $h; $y++) {
        $p=$bmp.GetPixel($x,$y)
        if ($p.R -ge 170 -and $p.G -ge 170 -and $p.B -ge 170) { $c++ }
    }
    $colBright[$x]=$c
}
"--- per-column bright count ---"
for ($x=0; $x -lt $w; $x++) { "{0}:{1}" -f $x,$colBright[$x] }

# 导航深色条边界：扫描一行(取 y=h/2)，记录每列 RGB
$ym=[int]($h/2)
"--- row y=$ym RGB ---"
for ($x=0; $x -lt $w; $x++) {
    $p=$bmp.GetPixel($x,$ym)
    "{0}: {1},{2},{3}" -f $x,$p.R,$p.G,$p.B
}
$bmp.Dispose()
