param(
    [int]$ProcId,
    [int]$X,
    [int]$Y,
    [int]$Wheels = -120,
    [string]$Out
)
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;using System.Runtime.InteropServices;
public static class SW6{
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc f,IntPtr l);
 public delegate bool EnumWindowsProc(IntPtr h,IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint f,uint x,uint y,int d,IntPtr e);
 [StructLayout(LayoutKind.Sequential)] public struct RECT{public int Left,Top,Right,Bottom;}
 public static IntPtr Found=IntPtr.Zero; public static uint Target;
 public static bool CB(IntPtr h,IntPtr l){uint p;GetWindowThreadProcessId(h,out p);if(p==Target&&IsWindowVisible(h)){RECT r;GetWindowRect(h,out r);if((r.Right-r.Left)>100&&(r.Bottom-r.Top)>100){Found=h;}}return true;}
 public static IntPtr Find(uint pid){Target=pid;Found=IntPtr.Zero;EnumWindows(CB,IntPtr.Zero);return Found;}
}
"@
$h=[SW6]::Find([uint32]$ProcId)
if($h -eq [IntPtr]::Zero){ "NO WINDOW"; exit 1 }
[void][SW6]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 300
[SW6]::SetCursorPos($X,$Y)
Start-Sleep -Milliseconds 150
[SW6]::mouse_event(0x0800,0,0,$Wheels,[IntPtr]::Zero)
Start-Sleep -Milliseconds 500
if($Out){
  $r=New-Object SW6+RECT; [void][SW6]::GetWindowRect($h,[ref]$r)
  $w=$r.Right-$r.Left; $ht=$r.Bottom-$r.Top
  $vs=[System.Windows.Forms.SystemInformation]::VirtualScreen
  $full=New-Object System.Drawing.Bitmap($vs.Width,$vs.Height)
  $g=[System.Drawing.Graphics]::FromImage($full)
  $g.CopyFromScreen(0,0,0,0,$full.Size)
  $crop=$full.Clone([System.Drawing.Rectangle]::new($r.Left,$r.Top,$w,$ht),$full.PixelFormat)
  $crop.Save($Out,[System.Drawing.Imaging.ImageFormat]::Png)
  "saved $Out"
}
