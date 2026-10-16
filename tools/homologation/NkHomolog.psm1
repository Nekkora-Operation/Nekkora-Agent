# Funções de apoio da homologação do pacote (Windows). Sem dados pessoais: só janelas e ícones do Agent.
Set-StrictMode -Version 3.0
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Windows.Forms, System.Drawing
if (-not ('NkUser32' -as [type])) {
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Collections.Generic;
public static class NkUser32 {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public static List<IntPtr> Windows(uint pid, bool visibleOnly) {
    var list = new List<IntPtr>();
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); if (p == pid && (!visibleOnly || IsWindowVisible(h))) list.Add(h); return true; }, IntPtr.Zero);
    return list;
  }
  public static string Title(IntPtr h) { var s = new StringBuilder(512); GetWindowText(h, s, 512); return s.ToString(); }
  public static void Click(int x, int y, bool right) {
    SetCursorPos(x, y);
    if (right) { mouse_event(0x0008, 0, 0, 0, UIntPtr.Zero); mouse_event(0x0010, 0, 0, 0, UIntPtr.Zero); }
    else { mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero); mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero); }
  }
}
'@
}
[NkUser32]::SetProcessDPIAware() | Out-Null

function Wait-Until([scriptblock]$Condition, [int]$TimeoutSeconds = 30, [string]$What = 'condição') {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $r = & $Condition
        if ($r) { return $r }
        Start-Sleep -Milliseconds 100
    }
    throw "tempo esgotado: $What"
}

function Get-AgentWindow([int]$ProcessId, [string]$Title = 'Nekkora Agent') {
    foreach ($h in [NkUser32]::Windows([uint32]$ProcessId, $false)) {
        if ([NkUser32]::Title($h) -eq $Title) { return $h }
    }
    return [IntPtr]::Zero
}

function Test-WindowShown([IntPtr]$Handle) { return $Handle -ne [IntPtr]::Zero -and [NkUser32]::IsWindowVisible($Handle) }

function Close-Window([IntPtr]$Handle) { [NkUser32]::PostMessage($Handle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null }

# --- bandeja (UI Automation do Explorer) ---

function Find-TrayButtons([string]$Name = 'Nekkora Agent') {
    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $found = @()
    foreach ($cls in 'Shell_TrayWnd', 'TopLevelWindowForOverflowXamlIsland', 'NotifyIconOverflowWindow') {
        $wc = New-Object System.Windows.Automation.PropertyCondition ([System.Windows.Automation.AutomationElement]::ClassNameProperty), $cls
        foreach ($w in $root.FindAll([System.Windows.Automation.TreeScope]::Children, $wc)) {
            # o estouro fechado continua na árvore com coordenadas antigas: só janelas visíveis contam
            if (-not [NkUser32]::IsWindowVisible([IntPtr]$w.Current.NativeWindowHandle)) { continue }
            foreach ($e in $w.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
                # o Explorer prefixa o nome do ícone com espaço; compara sem espaços nas pontas
                if ($null -eq $e.Current.Name -or $e.Current.Name.Trim() -ne $Name -or $e.Current.IsOffscreen) { continue }
                $r = $e.Current.BoundingRectangle
                if (-not $r.IsEmpty -and $r.Width -gt 0) { $found += [pscustomobject]@{ Host = $cls; Element = $e; Rect = $r } }
            }
        }
    }
    return $found
}

function Open-TrayOverflow {
    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $wc = New-Object System.Windows.Automation.PropertyCondition ([System.Windows.Automation.AutomationElement]::ClassNameProperty), 'Shell_TrayWnd'
    $tray = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $wc)
    foreach ($e in $tray.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
        $n = $e.Current.Name
        $aid = $e.Current.AutomationId
        if ($aid -eq 'SystemTrayIcon' -or $n -like '*ícones ocultos*' -or $n -like '*hidden icons*') {
            $r = $e.Current.BoundingRectangle
            [NkUser32]::Click([int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2), $false)
            return $true
        }
    }
    return $false
}

# O Windows só remove o ícone de um processo encerrado à força quando o mouse passa sobre ele
# ("ícone fantasma"). Passa o mouse sobre cada ícone com o nome dado; devolve quantos sobraram.
function Clear-GhostTrayIcons([string]$Name = 'Nekkora Agent') {
    for ($pass = 0; $pass -lt 3; $pass++) {
        $b = @(Find-TrayButtons $Name)
        if ($b.Count -eq 0) {
            Open-TrayOverflow | Out-Null
            Start-Sleep -Milliseconds 700
            $b = @(Find-TrayButtons $Name)
        }
        foreach ($x in $b) {
            [NkUser32]::SetCursorPos([int]($x.Rect.X + $x.Rect.Width / 2), [int]($x.Rect.Y + $x.Rect.Height / 2)) | Out-Null
            Start-Sleep -Milliseconds 250
            [NkUser32]::SetCursorPos([int]($x.Rect.X + 2), [int]($x.Rect.Y + 2)) | Out-Null
            Start-Sleep -Milliseconds 150
        }
        Start-Sleep -Milliseconds 400
        $after = @(Find-TrayButtons $Name).Count
        if ($after -eq $b.Count) { return $after }
    }
    return @(Find-TrayButtons $Name).Count
}

# Clica (botão esquerdo ou direito) no ícone do Agent; abre o "Mostrar ícones ocultos" se preciso.
function Invoke-TrayIcon([switch]$Right, [string]$Name = 'Nekkora Agent') {
    $b = @(Find-TrayButtons $Name)
    $opened = $false
    if ($b.Count -eq 0) {
        $opened = Open-TrayOverflow
        Start-Sleep -Milliseconds 700
        $b = @(Find-TrayButtons $Name)
    }
    if ($b.Count -gt 1) {
        Clear-GhostTrayIcons $Name | Out-Null
        $b = @(Find-TrayButtons $Name)
    }
    if ($b.Count -eq 0) { throw "ícone '$Name' não encontrado na bandeja" }
    $r = $b[0].Rect
    [NkUser32]::Click([int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2), [bool]$Right)
    return [pscustomobject]@{ Host = $b[0].Host; Count = $b.Count; OverflowOpened = $opened }
}

Export-ModuleMember -Function *
