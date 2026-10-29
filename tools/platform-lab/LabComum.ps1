<#
.SYNOPSIS
  AGENT-PLATFORM-002 — apoio comum das jornadas do laboratório (carregado com ". LabComum.ps1" depois de $Id e $Python).

.DESCRIPTION
  O mesmo apoio do jornada.ps1 da AGENT-PLATFORM-001 (que continua como foi homologado, sem alteração): janelas e teclado do
  Agent empacotado, leitura do log, docker compose do laboratório (com a sub-rede própria, quando declarada) e a cópia de
  laboratório do Agent que confia SÓ no certificado do laboratório (validação TLS normal).
#>
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $root 'tools\homologation\NkHomolog.psm1') -Force
$wincap = Join-Path $root 'tools\homologation\wincap.ps1'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$Lab = Join-Path $PSScriptRoot "saidas\$Id"
$Seg = Join-Path $env:USERPROFILE ".nekkora\agent-lab\$Id"
$Out = Join-Path $Lab ($Prefixo + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
New-Item -ItemType Directory -Force $Out | Out-Null
$checks = New-Object System.Collections.Generic.List[object]
$timeline = New-Object System.Collections.Generic.List[string]
function Check([string]$What, [bool]$Ok) {
    $checks.Add([ordered]@{ check = $What; ok = $Ok })
    Write-Host ('{0} {1}' -f $(if ($Ok) { 'OK   ' } else { 'FALHA' }), $What)
}
function Note([string]$Label, [string]$What) {
    $line = '{0} {1} {2}' -f (Get-Date).ToUniversalTime().ToString('HH:mm:ss.fff'), $Label, $What
    $timeline.Add($line); Write-Host $line
}
if (-not ('E5Win' -as [type])) {
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class E5Win {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int a, out RECT r, int s);
  public static RECT Bounds(IntPtr h) { RECT r; DwmGetWindowAttribute(h, 9, out r, Marshal.SizeOf(typeof(RECT))); return r; }
}
"@
}
function Focus([IntPtr]$h) { [NkUser32]::ForceForeground($h) | Out-Null; Start-Sleep -Milliseconds 250 }
function Send-Text([string]$Text) {
    $escaped = [regex]::Replace($Text, '[+^%~(){}\[\]]', { param($m) '{' + $m.Value + '}' })
    [System.Windows.Forms.SendKeys]::SendWait($escaped)
}
function Read-Shared([string]$File) {
    $fs = [IO.File]::Open($File, 'Open', 'Read', [IO.FileShare]'ReadWrite, Delete')
    try { $r = New-Object IO.StreamReader($fs, [Text.Encoding]::UTF8); return $r.ReadToEnd() } finally { $fs.Dispose() }
}
function Log-Text {
    $log = Get-ChildItem -Recurse -Filter 'agent.log' $script:data -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $log) { return '' }
    return Read-Shared $log.FullName
}
function Log-Count([string]$Pattern) { return ([regex]::Matches((Log-Text), $Pattern)).Count }
function Find-Button([IntPtr]$h, [int[][]]$Colors, [int]$X0, [int]$X1, [int]$Y0, [int]$Y1, [switch]$FromRight) {
    Focus $h
    $r = [E5Win]::Bounds($h)
    $bmp = New-Object System.Drawing.Bitmap ([int]($r.R - $r.L)), ([int]($r.B - $r.T))
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen([int]$r.L, [int]$r.T, 0, 0, $bmp.Size)
    try {
        $X1 = [Math]::Min($X1, $bmp.Width - 1); $Y1 = [Math]::Min($Y1, $bmp.Height - 1)
        $match = { param($c) foreach ($k in $Colors) { if ([Math]::Abs($c.R - $k[0]) -le 6 -and [Math]::Abs($c.G - $k[1]) -le 6 -and [Math]::Abs($c.B - $k[2]) -le 6) { return $true } }; return $false }
        if ($FromRight) {
            foreach ($x in ($X1..$X0 | Where-Object { $_ % 2 -eq 0 })) {
                $ys = @($Y0..$Y1 | Where-Object { & $match $bmp.GetPixel($x, $_) })
                if ($ys.Count -ge 6) { return @(($x - 30), [int](($ys[0] + $ys[-1]) / 2)) }
            }
        } else {
            foreach ($y in $Y0..$Y1) {
                $hits = @($X0..$X1 | Where-Object { $_ % 4 -eq 0 } | Where-Object { & $match $bmp.GetPixel($_, $y) })
                if ($hits.Count -ge 8) { return @([int](($hits[0] + $hits[-1]) / 2), ($y + 12)) }
            }
        }
        return $null
    } finally { $g.Dispose(); $bmp.Dispose() }
}
function Click-In([IntPtr]$h, [int]$X, [int]$Y) { Focus $h; $r = [E5Win]::Bounds($h); [NkUser32]::Click($r.L + $X, $r.T + $Y, $false) }
function Choose-Path([IntPtr]$Dialog, [string]$Mnemonic, [string]$ChooserTitle, [string]$PathText) {
    Focus $Dialog
    [System.Windows.Forms.SendKeys]::SendWait($Mnemonic)
    $ch = Wait-Until { $h = Get-AgentWindow $script:jvm $ChooserTitle; if (Test-WindowShown $h) { $h } } 20 "seletor '$ChooserTitle'"
    Focus $ch
    Start-Sleep -Milliseconds 400
    [System.Windows.Forms.SendKeys]::SendWait('^a')
    Send-Text $PathText
    Start-Sleep -Milliseconds 300
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    Wait-Until { -not (Test-WindowShown (Get-AgentWindow $script:jvm $ChooserTitle)) } 20 "seletor '$ChooserTitle' fechado" | Out-Null
}
function Capture([string]$Name, [string]$Title = 'Nekkora Agent') {
    $h = Get-AgentWindow $script:jvm $Title
    if (Test-WindowShown $h) { Focus $h }
    try { & $wincap -ProcessId $script:jvm -Out (Join-Path $Out "$Name.png") -TitleLike $Title | Out-Null } catch { Write-Host "captura $Name indisponível" }
}
# docker compose do laboratório (variáveis só neste processo; nada impresso)
$secrets = @{}
foreach ($l in [IO.File]::ReadAllLines((Join-Path $Seg 'segredos.env'))) { $i = $l.IndexOf('='); if ($i -gt 0) { $secrets[$l.Substring(0, $i)] = $l.Substring($i + 1) } }
foreach ($k in $secrets.Keys) { [Environment]::SetEnvironmentVariable($k, $secrets[$k], 'Process') }
$apiRepo = $secrets['LAB_API_REPO'] -replace '^/([a-zA-Z])/', '$1:/' # /c/... (Git Bash) → C:/...
$composeArgs = @('compose', '-f', (Join-Path $apiRepo 'api\e2e\compose.e2e.yml'), '-f', (Join-Path $PSScriptRoot 'compose.lab.yml'))
if ($secrets.ContainsKey('LAB_COMPOSE_EXTRA') -and $secrets['LAB_COMPOSE_EXTRA']) { $composeArgs += @('-f', (Join-Path $PSScriptRoot $secrets['LAB_COMPOSE_EXTRA'])) }
# comandos nativos: a saída de erro do docker não interrompe o roteiro (PS 5.1); o código de saída é o que vale
function Dc([string[]]$a) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $null = & docker @composeArgs @a 2>&1; return $LASTEXITCODE } finally { $ErrorActionPreference = $old }
}
function Sql([string]$q) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $r = $q | & docker @composeArgs exec -T postgres psql -X -q -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora 2>$null
        return ($r -join "`n").Trim()
    } finally { $ErrorActionPreference = $old }
}

$dist = Join-Path $root 'target\dist\Nekkora Agent'
$app = Join-Path $Lab 'agent-app'
if (Test-Path $app) { Remove-Item -Recurse -Force $app -ErrorAction Stop }
if (Test-Path $app) { throw 'a cópia anterior do Agent não pôde ser apagada (processo preso?): nada é homologado com binário velho' }
Copy-Item -Recurse $dist $app
$exe = Join-Path $app 'Nekkora Agent.exe'
$buildCommit = ([regex]::Match((Get-Content -Raw (Join-Path $app 'VERSOES.txt')), 'commit: ([0-9a-f]{40})')).Groups[1].Value
$distCommit = ([regex]::Match((Get-Content -Raw (Join-Path $dist 'VERSOES.txt')), 'commit: ([0-9a-f]{40})')).Groups[1].Value
if (-not $buildCommit -or $buildCommit -ne $distCommit) { throw 'a cópia de laboratório não é o build candidato' }
Note '[REAL]' "J1 Agent empacotado do commit $buildCommit"
$cfg = Join-Path $app 'app\Nekkora Agent.cfg'
$trust = Join-Path $Lab 'confianca-laboratorio.p12'
if (Test-Path $trust) { Remove-Item -Force $trust } # sempre do certificado atual do laboratório
if (-not (Test-Path $trust)) {
    $kt = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    & $kt -importcert -noprompt -alias laboratorio -file (Join-Path $Lab 'certificado-laboratorio.pem') -keystore $trust -storetype PKCS12 -storepass laboratorio | Out-Null
}
# só nesta cópia: a JVM do Agent confia no certificado do laboratório (armazenamento de confiança próprio, validação normal)
$cfgText = [IO.File]::ReadAllText($cfg)
$cfgText = $cfgText.Replace('[JavaOptions]', "[JavaOptions]`r`njava-options=-Djavax.net.ssl.trustStore=$trust`r`njava-options=-Djavax.net.ssl.trustStoreType=PKCS12`r`njava-options=-Djavax.net.ssl.trustStorePassword=laboratorio")
[IO.File]::WriteAllText($cfg, $cfgText)
$script:data = Join-Path $Lab 'agent-dados'
if (-not (Test-Path $script:data)) {
    New-Item -ItemType Directory -Force $script:data | Out-Null
    $origin = 'https://localhost:' + $secrets['E2E_PORTA']
    New-Item -ItemType Directory -Force (Join-Path $script:data 'config') | Out-Null
    [IO.File]::WriteAllText((Join-Path $script:data 'config\agent-config.json'), "{`n  `"format`": `"nekkora-agent/config`",`n  `"version`": 1,`n  `"theme`": `"sakura-dark`",`n  `"shortcuts`": {`n    `"platformUrl`": `"$origin`",`n    `"studioExecutable`": null`n  }`n}`n", (New-Object System.Text.UTF8Encoding $false))
}
$agentVersions = Get-Content -Raw (Join-Path $app 'VERSOES.txt')
function Start-Agent {
    $env0 = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME; HOST = $env:COMPUTERNAME }
    $env:NEKKORA_AGENT_DATA_DIR = $script:data; $env:PATH = 'C:\Windows\System32;C:\Windows'; $env:COMPUTERNAME = 'LAB-AGENT-01'
    Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    try { $p = Start-Process -FilePath $exe -PassThru; $null = $p.Handle }
    finally { $env:PATH = $env0.PATH; $env:COMPUTERNAME = $env0.HOST; $env:JAVA_HOME = $env0.JAVA_HOME; Remove-Item Env:NEKKORA_AGENT_DATA_DIR }
    $w = Wait-Until {
        foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) {
            $h = Get-AgentWindow $q.ProcessId
            if (Test-WindowShown $h) { return [pscustomobject]@{ Jvm = [int]$q.ProcessId; Handle = $h } }
        }
    } 60 'janela do Agent'
    $script:jvm = $w.Jvm
    return $w
}
