param([int]$ProcId)
Add-Type @"
using System;using System.Runtime.InteropServices;using System.Text;
public static class DW{
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc f,IntPtr l);
 public delegate bool EnumWindowsProc(IntPtr h,IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);
 [StructLayout(LayoutKind.Sequential)] public struct RECT{public int Left,Top,Right,Bottom;}
 public static uint Target;
 public static bool CB(IntPtr h,IntPtr l){uint p;GetWindowThreadProcessId(h,out p);if(p==Target){RECT r;GetWindowRect(h,out r);var sb=new StringBuilder(256);GetWindowText(h,sb,256);Console.WriteLine($"h={h} vis={IsWindowVisible(h)} rect={r.Left},{r.Top} {r.Right-r.Left}x{r.Bottom-r.Top} title='{sb}'");}return true;}
 public static void Run(uint pid){Target=pid;EnumWindows(CB,IntPtr.Zero);}
}
"@
[DW]::Run([uint32]$ProcId)
