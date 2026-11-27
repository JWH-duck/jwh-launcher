param([string]$Dir)
Add-Type -AssemblyName System.Drawing
Get-ChildItem $Dir -Filter 'nav_*.png' | ForEach-Object {
  $bmp = New-Object System.Drawing.Bitmap($_.FullName)
  $w=$bmp.Width; $h=$bmp.Height
  $xmin=999;$xmax=-1;$ymin=999;$ymax=-1
  for($y=0;$y -lt $h;$y++){
    for($x=0;$x -lt $w;$x++){
      $p=$bmp.GetPixel($x,$y)
      if($p.A -gt 12){
        if($x -lt $xmin){$xmin=$x}; if($x -gt $xmax){$xmax=$x}
        if($y -lt $ymin){$ymin=$y}; if($y -gt $ymax){$ymax=$y}
      }
    }
  }
  if($xmax -ge 0){
    $iw=$xmax-$xmin+1; $ih=$ymax-$ymin+1
    $rw=[math]::Round($iw/$w,2); $rh=[math]::Round($ih/$h,2)
    "{0}  canvas {1}x{2}  glyph {3}x{4}  fillW={5} fillH={6}" -f $_.Name,$w,$h,$iw,$ih,$rw,$rh
  } else { "$($_.Name) empty" }
  $bmp.Dispose()
}
