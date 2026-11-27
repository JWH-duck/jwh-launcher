param(
    [int]$ProcId,
    [int]$X,
    [int]$Y
)
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;using System.Runtime.InteropServices;
public static class CW{
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc f,IntPtr l);
 public delegate bool EnumWindowsProc(IntPtr h,IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint f,uint x,uint y,uint d,IntPtr e);
 [StructLayout(LayoutKind.Sequential)] public struct RECT{public int Left,Top,Right,Bottom;}
 public static IntPtr Found=IntPtr.Zero; public static uint Target;
 public static bool CB(IntPtr h,IntPtr l){uint p;GetWindowThreadProcessId(h,out p);if(p==Target&&IsWindowVisible(h)){RECT r;GetWindowRect(h,out r);if((r.Right-r.Left)>100&&(r.Bottom-r.Top)>100){Found=h;}}return true;}
 public static IntPtr Find(uint pid){Target=pid;Found=IntPtr.Zero;EnumWindows(CB,IntPtr.Zero);return Found;}
}
"@
$h=[CW]::Find([uint32]$ProcId)
if($h -eq [IntPtr]::Zero){ "NO WINDOW"; exit 1 }
[void][CW]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 400
$r=New-Object CW+RECT
[void][CW]::GetWindowRect($h,[ref]$r)
$sx=$r.Left+$X; $sy=$r.Top+$Y
"click screen $sx,$sy (win $X,$Y rect $($r.Left),$($r.Top))"
[void][CW]::SetCursorPos($sx,$sy)
Start-Sleep -Milliseconds 150
[CW]::mouse_event(0x0002,0,0,0,[IntPtr]::Zero)
[CW]::mouse_event(0x0004,0,0,0,[IntPtr]::Zero)
Start-Sleep -Milliseconds 300
