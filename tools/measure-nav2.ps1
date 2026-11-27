param([string]$Src)
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap($Src)
$w=$bmp.Width; $h=$bmp.Height

$rowBright=New-Object int[] $h
for($y=0;$y -lt $h;$y++){
  $c=0
  for($x=50;$x -le 107;$x++){
    $p=$bmp.GetPixel($x,$y)
    $lum=($p.R+$p.G+$p.B)/3
    if($lum -gt 120){$c++}
  }
  $rowBright[$y]=$c
}
$clusters=New-Object System.Collections.ArrayList
$in=$false; $s=0
for($y=0;$y -lt $h;$y++){
  if($rowBright[$y] -gt 0){ if(-not $in){$in=$true;$s=$y} }
  else { if($in){$in=$false; [void]$clusters.Add(@($s, ($y-1)))} }
}
if($in){[void]$clusters.Add(@($s, ($h-1)))}

"条宽(x51..107)=57"
"图标簇数=$($clusters.Count)"
foreach($c in $clusters){
  $y0=$c[0]; $y1=$c[1]; $xmin=999;$xmax=-1
  for($y=$y0;$y -le $y1;$y++){
    for($x=50;$x -le 107;$x++){
      $p=$bmp.GetPixel($x,$y)
      $lum=($p.R+$p.G+$p.B)/3
      if($lum -gt 120){ if($x -lt $xmin){$xmin=$x}; if($x -gt $xmax){$xmax=$x} }
    }
  }
  if($xmax -ge 0){
    $iw=$xmax-$xmin+1
    $ratio=[math]::Round($iw/57,2)
    "y $y0..$y1 h=$($y1-$y0+1) x $xmin..$xmax 宽=$iw 占比=$ratio"
  }
}
$bmp.Dispose()
