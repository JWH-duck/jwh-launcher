param(
    [int]$ProcId,
    [string]$Out
)
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;using System.Runtime.InteropServices;
public static class SW5{
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc f,IntPtr l);
 public delegate bool EnumWindowsProc(IntPtr h,IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h,int n);
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int cx,int cy,uint flags);
 [StructLayout(LayoutKind.Sequential)] public struct RECT{public int Left,Top,Right,Bottom;}
 public static IntPtr Found=IntPtr.Zero; public static uint Target;
 public static bool CB(IntPtr h,IntPtr l){uint p;GetWindowThreadProcessId(h,out p);if(p==Target&&IsWindowVisible(h)){RECT r;GetWindowRect(h,out r);if((r.Right-r.Left)>100&&(r.Bottom-r.Top)>100){Found=h;}}return true;}
 public static IntPtr Find(uint pid){Target=pid;Found=IntPtr.Zero;EnumWindows(CB,IntPtr.Zero);return Found;}
}
"@
$h=[SW5]::Find([uint32]$ProcId)
if($h -eq [IntPtr]::Zero){ "NO WINDOW"; exit 1 }
[void][SW5]::ShowWindow($h,9)
$HWND_TOP=[IntPtr]::Zero
$HWND_TOPMOST=[IntPtr](-1)
$HWND_NOTOPMOST=[IntPtr](-2)
[void][SW5]::SetWindowPos($h,$HWND_TOPMOST,0,0,0,0,0x0003)
Start-Sleep -Milliseconds 200
[void][SW5]::SetWindowPos($h,$HWND_NOTOPMOST,0,0,0,0,0x0003)
[void][SW5]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 900
$r=New-Object SW5+RECT
[void][SW5]::GetWindowRect($h,[ref]$r)
$w=$r.Right-$r.Left; $ht=$r.Bottom-$r.Top
"rect $($r.Left),$($r.Top) ${w}x${ht}"
$vs=[System.Windows.Forms.SystemInformation]::VirtualScreen
$full=New-Object System.Drawing.Bitmap($vs.Width,$vs.Height)
$g=[System.Drawing.Graphics]::FromImage($full)
$g.CopyFromScreen(0,0,0,0,$full.Size)
$crop=$full.Clone([System.Drawing.Rectangle]::new($r.Left,$r.Top,$w,$ht),$full.PixelFormat)
$crop.Save($Out,[System.Drawing.Imaging.ImageFormat]::Png)
"saved $Out"
