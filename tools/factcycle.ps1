param(
    [int]$ProcId,
    [int]$Clicks = 10,
    [int]$X = 1269,
    [int]$Y = 757
)

Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class FC {
  public delegate bool EP(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EP f, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y, uint d, IntPtr e);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, Ri, B; }
  public static List<IntPtr> Found = new List<IntPtr>();
  public static uint Target;
  public static bool CB(IntPtr h, IntPtr l) {
    uint p; GetWindowThreadProcessId(h, out p);
    if (p == Target && IsWindowVisible(h)) Found.Add(h);
    return true;
  }
  public static string Snap(uint pid) {
    Target = pid; Found = new List<IntPtr>();
    EnumWindows(CB, IntPtr.Zero);
    foreach (IntPtr h in Found) {
      StringBuilder sb = new StringBuilder(256);
      GetWindowText(h, sb, 256);
      if (sb.ToString().Contains("JWH")) {
        RECT r; GetWindowRect(h, out r);
        return string.Format("{0}x{1} @{2},{3}", (r.Ri - r.L), (r.B - r.T), r.L, r.T);
      }
    }
    return "NONE";
  }
}
"@

"BASE  $([FC]::Snap([uint32]$ProcId))"
for ($i = 1; $i -le $Clicks; $i++) {
    [FC]::SetCursorPos($X, $Y)
    Start-Sleep -Milliseconds 120
    [FC]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
    [FC]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 850
    "BATCH $i  $([FC]::Snap([uint32]$ProcId))"
}
