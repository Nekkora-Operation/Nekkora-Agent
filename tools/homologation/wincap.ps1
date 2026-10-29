<#
.SYNOPSIS
  Captura somente o retângulo das janelas visíveis de um processo (sem o restante da área de trabalho).
  Uso: wincap.ps1 -ProcessId 1234 -Out janela.png [-TitleLike 'Nekkora*']
#>
param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$TitleLike = '*'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if (-not ('NkWin' -as [type])) {
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Collections.Generic;
public static class NkWin {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int a, out RECT r, int s);
  public static List<IntPtr> Windows(uint pid) {
    var list = new List<IntPtr>();
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); if (p == pid && IsWindowVisible(h)) list.Add(h); return true; }, IntPtr.Zero);
    return list;
  }
  public static string Title(IntPtr h) { var s = new StringBuilder(512); GetWindowText(h, s, 512); return s.ToString(); }
  public static RECT Bounds(IntPtr h) { RECT r; DwmGetWindowAttribute(h, 9, out r, Marshal.SizeOf(typeof(RECT))); return r; }
}
'@
}
[NkWin]::SetProcessDPIAware() | Out-Null
$wins = [NkWin]::Windows([uint32]$ProcessId) | Where-Object { [NkWin]::Title($_) -like $TitleLike }
if (-not $wins) { throw "nenhuma janela visível do processo $ProcessId com título '$TitleLike'" }
$rects = $wins | ForEach-Object { [NkWin]::Bounds($_) } | Where-Object { ($_.R - $_.L) -gt 40 -and ($_.B - $_.T) -gt 40 }
$l = ($rects | Measure-Object -Property L -Minimum).Minimum
$t = ($rects | Measure-Object -Property T -Minimum).Minimum
$r = ($rects | Measure-Object -Property R -Maximum).Maximum
$b = ($rects | Measure-Object -Property B -Maximum).Maximum
$bmp = New-Object System.Drawing.Bitmap ([int]($r - $l)), ([int]($b - $t))
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen([int]$l, [int]$t, 0, 0, $bmp.Size)
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
"{0}x{1} em ({2},{3}) -> {4}" -f ($r - $l), ($b - $t), $l, $t, $Out
