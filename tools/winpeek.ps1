param(
    [int]$ProcId,
    [switch]$Foreground,
    [switch]$Restore
)

Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class WinPeek {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int n);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

    public static List<IntPtr> Found = new List<IntPtr>();
    public static uint TargetPid;

    public static bool Callback(IntPtr hWnd, IntPtr lParam) {
        uint pid; GetWindowThreadProcessId(hWnd, out pid);
        if (pid == TargetPid && IsWindowVisible(hWnd)) Found.Add(hWnd);
        return true;
    }

    public static List<IntPtr> Enum(uint pid) {
        TargetPid = pid; Found = new List<IntPtr>();
        EnumWindows(Callback, IntPtr.Zero);
        return Found;
    }

    public static string Title(IntPtr h) {
        StringBuilder sb = new StringBuilder(512);
        GetWindowText(h, sb, sb.Capacity);
        return sb.ToString();
    }
}
"@

$wins = [WinPeek]::Enum([uint32]$ProcId)
foreach ($h in $wins) {
    $title = [WinPeek]::Title($h)
    $wr = New-Object WinPeek+RECT
    $cr = New-Object WinPeek+RECT
    [void][WinPeek]::GetWindowRect($h, [ref]$wr)
    [void][WinPeek]::GetClientRect($h, [ref]$cr)
    $w = $wr.Right - $wr.Left; $ht = $wr.Bottom - $wr.Top
    if ($Restore) { [void][WinPeek]::ShowWindow($h, 9) }
    if ($Foreground) { [void][WinPeek]::SetForegroundWindow($h) }
    "{0} | title='{1}' | win {2},{3} {4}x{5} | client {6}x{7}" -f `
        $h.ToInt64(), $title, $wr.Left, $wr.Top, $w, $ht, ($cr.Right-$cr.Left), ($cr.Bottom-$cr.Top)
}
