<#
.SYNOPSIS
  NEKKORA-FRAMEWORK-E2E-005 — executa UM pacote de automação pela INTERFACE do Nekkora Agent empacotado (o .exe do ZIP
  de laboratório, sem Java no PATH, Studio fechado, Platform desligada), sem alterar o Agent.

.DESCRIPTION
  1. Confere o SHA-256 do ZIP do Agent, extrai numa pasta nova e abre o executável com NEKKORA_AGENT_DATA_DIR
     descartável, PATH = C:\Windows\System32;C:\Windows e nome de máquina fictício.
  2. Pelo TECLADO, como uma pessoa: Alt+E (Execução local); Alt+P (pasta do pacote, digitada no seletor); Tab até
     "Aprovar este pacote…", Espaço, Enter (confirma); Alt+Y (Python do ambiente); Alt+E (entrada, se o pacote pede);
     Alt+A (pasta de saída); Alt+I (Iniciar execução). Cada passo espera o registro correspondente do próprio Agent
     (agent.log: EXEC_PACKAGE_INSPECTED, EXEC_PACKAGE_APPROVED, EXEC_PYTHON_CHECKED, EXEC_STARTED).
  3. Acompanha o estado (estado.json lido com compartilhamento total) até o fim; captura a aba Execução e a aba
     Atividade (Alt+T) DURANTE e DEPOIS; opcionalmente cancela pelo botão (Alt+C... via "Cancelar execução").
  4. Copia, SANITIZADOS, estado.json, eventos.jsonl e atividade.jsonl da execução, e resume em resultado.json.
  Este roteiro envia teclas e traz janelas para a frente: não use o computador durante a execução.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Package,
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][string]$Zip,
    [string]$InputFile,
    [string]$Label = 'execucao',
    [int]$TimeoutSeconds = 600,
    [int]$CancelAfterSeconds = 0,
    [string]$ExpectRefusal,
    # COMPAT-001: o Agent deve recusar o Python escolhido (ex.: AGT-EXE-009, nekkora_rpa de outra versão)
    [string]$ExpectPythonRefusal,
    [int]$KillAgentAfterSeconds = 0,
    # medição (AGENT-LEAN-001): repete a execução N vezes na MESMA instância do Agent (Alt+I de novo, mesmo pacote
    # aprovado), fica -IdleAfterSeconds ocioso com a aba Atividade aberta e, com -GcLog, grava o log do coletor da JVM
    # do Agent (JAVA_TOOL_OPTIONS -Xlog:gc, só para esta abertura) em <Out>\gc.log
    [int]$Repeat = 1,
    [int]$IdleAfterSeconds = 0,
    [switch]$GcLog,
    # padrão: o repositório do Agent ao lado deste (..\Nekkora-Agent), como no ambiente de homologação
    [string]$AgentModule,
    # STUDIO-AGENT-LOCAL-001: roteiro ISO-R (tools/isolation/verificar-isolamento.ps1) — ocioso (atribuído) e durante a execução
    [string]$IsolationScript,
    # STUDIO-AGENT-LOCAL-001: com -KillAgentAfterSeconds, derruba também a automação (queda da máquina simulada, sem resumo)
    [switch]$KillAutomationToo
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
if (-not $AgentModule) { $AgentModule = Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))) 'Nekkora-Agent\tools\homologation' }
Import-Module (Join-Path $AgentModule 'NkHomolog.psm1') -Force
$wincap = Join-Path $AgentModule 'wincap.ps1'
New-Item -ItemType Directory -Force $Out, $OutputRoot | Out-Null
$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$What, [bool]$Ok) {
    $checks.Add([ordered]@{ check = $What; ok = $Ok })
    Write-Host ('{0} {1}' -f $(if ($Ok) { 'OK   ' } else { 'FALHA' }), $What)
}
function Send-Text([string]$Text) {
    # SendKeys: + ^ % ~ ( ) { } [ ] são especiais
    $escaped = [regex]::Replace($Text, '[+^%~(){}\[\]]', { param($m) '{' + $m.Value + '}' })
    [System.Windows.Forms.SendKeys]::SendWait($escaped)
}
function Read-Shared([string]$File) {
    # arquivos vivos do Agent: sempre com compartilhamento total (auditoria DESKTOP-001)
    $fs = [IO.File]::Open($File, 'Open', 'Read', [IO.FileShare]'ReadWrite, Delete')
    try { $r = New-Object IO.StreamReader($fs, [Text.Encoding]::UTF8); return $r.ReadToEnd() } finally { $fs.Dispose() }
}
function Log-Has([string]$Event) {
    $log = Get-ChildItem -Recurse -Filter 'agent.log' $script:data -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $log) { return $false }
    return (Read-Shared $log.FullName) -match $Event
}
function Log-Count([string]$Event) {
    $log = Get-ChildItem -Recurse -Filter 'agent.log' $script:data -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $log) { return 0 }
    return ([regex]::Matches((Read-Shared $log.FullName), $Event)).Count
}
function Focus([IntPtr]$h) { [NkUser32]::ForceForeground($h) | Out-Null; Start-Sleep -Milliseconds 250 }
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
function Find-Button([IntPtr]$h, [int[][]]$Colors, [int]$X0, [int]$X1, [int]$Y0, [int]$Y1, [switch]$FromRight) {
    # botões sem atalho de teclado: acha o botão pela COR do fundo na imagem da própria janela (sem coordenada fixa:
    # a área de preparação cresce quando o pacote pede entrada). Devolve o centro, em coordenadas da janela.
    Focus $h
    $r = [E5Win]::Bounds($h)
    $bmp = New-Object System.Drawing.Bitmap ([int]($r.R - $r.L)), ([int]($r.B - $r.T))
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen([int]$r.L, [int]$r.T, 0, 0, $bmp.Size)
    try {
        $X1 = [Math]::Min($X1, $bmp.Width - 1); $Y1 = [Math]::Min($Y1, $bmp.Height - 1)
        $match = { param($c) foreach ($k in $Colors) { if ([Math]::Abs($c.R - $k[0]) -le 6 -and [Math]::Abs($c.G - $k[1]) -le 6 -and [Math]::Abs($c.B - $k[2]) -le 6) { return $true } }; return $false }
        $xs = if ($FromRight) { $X1..$X0 } else { $X0..$X1 }
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
function Click-In([IntPtr]$h, [int]$X, [int]$Y) {
    # botão sem atalho de teclado ("Aprovar este pacote…"): clique real na posição dele dentro da janela (pixels
    # físicos, a mesma origem das capturas); o efeito é conferido pelo diálogo que ele abre
    Focus $h
    $r = [E5Win]::Bounds($h)
    [NkUser32]::Click($r.L + $X, $r.T + $Y, $false)
}
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
function Capture([string]$Name, [string]$Title = 'Execução local') {
    $h = Get-AgentWindow $script:jvm $Title
    if (Test-WindowShown $h) { Focus $h }
    try { & $wincap -ProcessId $script:jvm -Out (Join-Path $Out "$Name.png") -TitleLike $Title | Out-Null; $script:shots += "$Name.png" } catch { Write-Host "captura $Name indisponível" }
}
$script:shots = @()

# ---------- Agent empacotado ----------
$expected = ((Get-Content "$Zip.sha256") -split '\s+')[0]
$zipSha = (Get-FileHash -Algorithm SHA256 $Zip).Hash.ToLowerInvariant()
Check "SHA-256 do ZIP do Agent confere ($($zipSha.Substring(0,12))…)" ($zipSha -eq $expected)
$app = Join-Path $Out 'agent'
Expand-Archive -Path $Zip -DestinationPath $app -Force
$exe = (Get-ChildItem -Recurse -Filter 'Nekkora Agent.exe' $app | Select-Object -First 1).FullName
$script:data = Join-Path $Out 'dados-agent'
New-Item -ItemType Directory -Force $script:data | Out-Null
$studioOpen = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -like '*studio*' -or ($_.CommandLine -and $_.CommandLine -like '*nekkora-studio*') }).Count -gt 0
Check "Studio fechado (nenhum processo do Studio)" (-not $studioOpen)
$env0 = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME; DATA = $env:NEKKORA_AGENT_DATA_DIR; HOST = $env:COMPUTERNAME }
$env:NEKKORA_AGENT_DATA_DIR = $script:data; $env:PATH = 'C:\Windows\System32;C:\Windows'; $env:COMPUTERNAME = 'LAB-AGENT-01'
Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
$pipes0 = @(); try { $pipes0 = @([System.IO.Directory]::GetFiles('\\.\pipe\') | ForEach-Object { $_.Substring(9) }) } catch { }
$pipesFile = Join-Path $Out "$Label-pipes-antes.txt"; Set-Content -LiteralPath $pipesFile -Value $pipes0 -Encoding UTF8
$t0 = Get-Date
if ($GcLog) { $env:JAVA_TOOL_OPTIONS = '-Xlog:gc,gc+heap+exit:file=gc.log:uptime' }
try { $sp = @{ FilePath = $exe; PassThru = $true }; if ($GcLog) { $sp.WorkingDirectory = $Out }; $launcher = Start-Process @sp; $null = $launcher.Handle }
finally {
    $env:PATH = $env0.PATH; $env:COMPUTERNAME = $env0.HOST
    if ($GcLog) { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
    if ($env0.JAVA_HOME) { $env:JAVA_HOME = $env0.JAVA_HOME }
    if ($env0.DATA) { $env:NEKKORA_AGENT_DATA_DIR = $env0.DATA } else { Remove-Item Env:NEKKORA_AGENT_DATA_DIR -ErrorAction SilentlyContinue }
}
$w = Wait-Until {
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) {
        $h = Get-AgentWindow $q.ProcessId
        if (Test-WindowShown $h) { return [pscustomobject]@{ Jvm = [int]$q.ProcessId; Handle = $h } }
    }
} 60 'janela do Agent'
$script:jvm = $w.Jvm
Check ("Agent empacotado abriu sem Java no PATH em {0:N1} s" -f ((Get-Date) - $t0).TotalSeconds) ($null -ne $w)
if ($IsolationScript) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $IsolationScript -ProcessId $script:jvm -DataDir $script:data -Seconds 15 `
        -Out (Join-Path $Out "$Label-isolamento-ocioso") -Quiet -PipesBeforeFile $pipesFile
    Check "ISO-R com o Agent aberto e ocioso (15 s): nenhuma porta TCP/UDP, nenhum AF_UNIX, nenhum pipe atribuído ao Agent" ($LASTEXITCODE -eq 0)
}

try {
    # ---------- preparação pela janela ----------
    Focus $w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    $dlg = Wait-Until { $h = Get-AgentWindow $script:jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
    Start-Sleep -Milliseconds 800
    Choose-Path $dlg '%p' 'Pasta do pacote da automação' $Package
    Wait-Until { Log-Has 'EXEC_PACKAGE_INSPECTED' } 30 'pacote inspecionado' | Out-Null
    Check "pacote escolhido pelo seletor e inspecionado pelo Agent" (Log-Has 'EXEC_PACKAGE_INSPECTED')
    Start-Sleep -Milliseconds 600
    Capture "$Label-01-pacote-escolhido"
    if ($ExpectRefusal) {
        # o Agent deve RECUSAR o pacote na inspeção (ex.: AGT-EXE-006, segredos não suportados): nada é aprovável
        Check "pacote recusado pelo Agent com $ExpectRefusal" (Log-Has "EXEC_PACKAGE_INSPECTED.*$ExpectRefusal")
        Check "nenhuma aprovação nem execução registradas" (-not (Log-Has 'EXEC_PACKAGE_APPROVED') -and -not (Log-Has 'EXEC_STARTED'))
        $result = [ordered]@{ schema = 'nekkora-e2e-005/agent-run'; version = '1.0.0'; label = $Label; agent_zip_sha256 = $zipSha
            package_manifest_sha256 = (Get-FileHash -Algorithm SHA256 (@('manifest.json', 'nekkora-agent-lab.json' | ForEach-Object { Join-Path $Package $_ } | Where-Object { Test-Path $_ })[0])).Hash.ToLowerInvariant()
            state = 'REFUSED_AT_INSPECTION'; code = $ExpectRefusal; screenshots = $script:shots; checks = $checks }
        $result | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $Out "$Label-resultado.json")
        return
    }
    # "Aprovar este pacote…": o 1º botão (de cima para baixo) na coluna dele, abaixo do cartão de preparação
    $pos = Find-Button $dlg @(@(109, 60, 82), @(118, 71, 92)) 185 330 450 860
    if (-not $pos) { throw 'botão Aprovar este pacote… não encontrado' }
    Click-In $dlg $pos[0] $pos[1]
    $confirm = Wait-Until { $h = Get-AgentWindow $script:jvm 'Aprovar este pacote?'; if (Test-WindowShown $h) { $h } } 20 'confirmação da aprovação'
    Start-Sleep -Milliseconds 500
    Capture "$Label-02-confirmacao-aprovacao" 'Aprovar este pacote?'
    # botão primário "Aprovar" (o mais à direita do rodapé da confirmação)
    $pos = Find-Button $confirm @(, @(240, 163, 184)) 200 420 120 400 -FromRight  # vírgula: lista de UMA cor
    if (-not $pos) { throw 'botão Aprovar da confirmação não encontrado' }
    Click-In $confirm $pos[0] $pos[1]
    Wait-Until { Log-Has 'EXEC_PACKAGE_APPROVED' } 20 'aprovação registrada' | Out-Null
    Check "pacote APROVADO pela pessoa (confirmação explícita)" (Log-Has 'EXEC_PACKAGE_APPROVED')
    Choose-Path $dlg '%y' 'Python do ambiente do projeto' $Python
    Wait-Until { Log-Has 'EXEC_PYTHON_CHECKED' } 60 'Python conferido' | Out-Null
    Check "Python do ambiente conferido pelo Agent" (Log-Has 'EXEC_PYTHON_CHECKED')
    if ($ExpectPythonRefusal) {
        if ($InputFile) { Choose-Path $dlg '%e' 'Entrada da automação' $InputFile; Start-Sleep -Seconds 1 }
        Choose-Path $dlg '%a' 'Pasta de saída (uma subpasta por execução)' $OutputRoot
        Start-Sleep -Seconds 1
        Capture "$Label-03-python-recusado"
        Focus $dlg
        [System.Windows.Forms.SendKeys]::SendWait('%i')
        Start-Sleep -Seconds 5
        $logText = Read-Shared (Get-ChildItem -Recurse -Filter 'agent.log' $script:data | Select-Object -First 1).FullName
        $fw = [regex]::Match($logText, 'EXEC_PYTHON_CHECKED[^
]*framework=([0-9.]+|none)').Groups[1].Value
        Check "Agent leu a versão do Framework no Python escolhido: $fw" ($fw -ne '')
        Check "Iniciar (Alt+I) não lançou nada: nenhum EXEC_STARTED" (-not (Log-Has 'EXEC_STARTED'))
        Check "nenhuma pasta de execução criada" (-not (Get-ChildItem (Join-Path $script:data 'execucoes') -Directory -ErrorAction SilentlyContinue))
        $result = [ordered]@{ schema = 'nekkora-e2e-005/agent-run'; version = '1.0.0'; label = $Label; agent_zip_sha256 = $zipSha
            package_manifest_sha256 = (Get-FileHash -Algorithm SHA256 (Join-Path $Package 'manifest.json')).Hash.ToLowerInvariant()
            state = 'NOT_STARTED_PYTHON_REFUSED'; expected_code = $ExpectPythonRefusal; framework_in_python = $fw; screenshots = $script:shots; checks = $checks }
        $result | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $Out "$Label-resultado.json")
        return
    }
    if ($InputFile) {
        Choose-Path $dlg '%e' 'Entrada da automação' $InputFile
        Start-Sleep -Seconds 1
    }
    Choose-Path $dlg '%a' 'Pasta de saída (uma subpasta por execução)' $OutputRoot
    Start-Sleep -Seconds 1
    Capture "$Label-03-pronto-para-iniciar"
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('%i')
    Wait-Until { Log-Has 'EXEC_STARTED' } 30 'execução iniciada' | Out-Null
    Check "execução iniciada pelo botão Iniciar (Alt+I)" (Log-Has 'EXEC_STARTED')
    $isoRun = $null
    if ($IsolationScript) {
        $isoRun = Start-Process -FilePath powershell.exe -PassThru -WindowStyle Hidden -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            ('"' + $IsolationScript + '"'), '-ProcessId', $script:jvm, '-DataDir', ('"' + $script:data + '"'), '-Seconds', '12', '-Out',
            ('"' + (Join-Path $Out "$Label-isolamento-em-execucao") + '"'))
    }
    $runDir = Wait-Until { Get-ChildItem (Join-Path $script:data 'execucoes') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1 } 20 'pasta da execução'
    $estado = Join-Path $runDir.FullName 'estado.json'
    $cancelled = $false
    if ($KillAgentAfterSeconds -gt 0) {
        Start-Sleep -Seconds $KillAgentAfterSeconds
        Capture "$Label-04-durante-execucao"
        $before = Read-Shared $estado
        Check "execução em andamento antes de derrubar o Agent" ($before -match '"state"\s*:\s*"RUNNING"')
        Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
        Wait-Until { -not (Get-Process -Id $script:jvm -ErrorAction SilentlyContinue) } 30 'Agent derrubado' | Out-Null
        Check "Agent derrubado à força (Stop-Process) com a execução RUNNING" $true
        if ($KillAutomationToo) {
            foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine -like '*main.py*' -and $_.CommandLine -like "*$OutputRoot*" })) {
                & taskkill.exe /PID $q.ProcessId /T /F | Out-Null
            }
            Check "automação derrubada junto (taskkill /T /F): nenhum resultado final publicado" (-not (Test-Path (Join-Path (Join-Path $OutputRoot (Split-Path -Leaf $runDir.FullName)) 'execucao\summary.json')))
        }
        $t1 = Get-Date
        Wait-Until { @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine -like '*main.py*' -and $_.CommandLine -like "*$OutputRoot*" }).Count -eq 0 } 600 'automação terminou sem o Agent' | Out-Null
        Check ("automação continuou e terminou sem supervisão em {0:N0} s" -f ((Get-Date) - $t1).TotalSeconds) $true
        $mid = Read-Shared $estado
        Check "registro em disco continua RUNNING enquanto o Agent está fora" ($mid -match '"state"\s*:\s*"RUNNING"')
        $env:NEKKORA_AGENT_DATA_DIR = $script:data; $env:PATH = 'C:\Windows\System32;C:\Windows'; $env:COMPUTERNAME = 'LAB-AGENT-01'
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
        try { $l2 = Start-Process -FilePath $exe -PassThru; $null = $l2.Handle }
        finally {
            $env:PATH = $env0.PATH; $env:COMPUTERNAME = $env0.HOST
            if ($env0.JAVA_HOME) { $env:JAVA_HOME = $env0.JAVA_HOME }
            if ($env0.DATA) { $env:NEKKORA_AGENT_DATA_DIR = $env0.DATA } else { Remove-Item Env:NEKKORA_AGENT_DATA_DIR -ErrorAction SilentlyContinue }
        }
        $w = Wait-Until {
            foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) {
                $h = Get-AgentWindow $q.ProcessId
                if (Test-WindowShown $h) { return [pscustomobject]@{ Jvm = [int]$q.ProcessId; Handle = $h } }
            }
        } 60 'janela do Agent reaberto'
        $script:jvm = $w.Jvm
        $final = Wait-Until { $t = Read-Shared $estado; if ($t -match '"state"\s*:\s*"(SUCCEEDED|FAILED|CANCELLED|TIMED_OUT|BLOCKED|OUTCOME_UNKNOWN)"') { $Matches[1] } } 60 'reconciliação na reabertura'
        Focus $w.Handle
        [System.Windows.Forms.SendKeys]::SendWait('%e')
        $dlg = Wait-Until { $h = Get-AgentWindow $script:jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
        Start-Sleep -Seconds 2
    } elseif ($CancelAfterSeconds -le 0) {
        Start-Sleep -Seconds 3
        Capture "$Label-04-durante-execucao"
        Focus $dlg
        [System.Windows.Forms.SendKeys]::SendWait('%t')
        Start-Sleep -Seconds 2
        Capture "$Label-05-atividade-durante"
    } else {
        # cancelamento pela interface: Sair com a execução ativa → "Encerrar execução e sair" (Alt+E) encerra a árvore
        Start-Sleep -Seconds $CancelAfterSeconds
        Capture "$Label-04-durante-execucao"
        $before = Read-Shared $estado
        Check "execução ainda em andamento no momento do cancelamento" ($before -match '"state"\s*:\s*"RUNNING"')
        Focus $dlg
        [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
        Start-Sleep -Milliseconds 800
        Focus (Get-AgentWindow $script:jvm)
        [System.Windows.Forms.SendKeys]::SendWait('%s')
        $ask = Wait-Until { $h = Get-AgentWindow $script:jvm 'Sair do Nekkora Agent?'; if (Test-WindowShown $h) { $h } } 20 'confirmação de sair'
        Start-Sleep -Milliseconds 500
        Capture "$Label-05b-confirmar-encerrar" 'Sair do Nekkora Agent?'
        Focus $ask
        [System.Windows.Forms.SendKeys]::SendWait('%e')
        Wait-Until { -not (Get-Process -Id $script:jvm -ErrorAction SilentlyContinue) } 60 'Agent encerrou a execução e saiu' | Out-Null
        $cancelled = $true
        Check "cancelamento pela interface (Sair → Encerrar execução e sair)" $true
    }
    # espera o fim amostrando a memória (conjunto de trabalho) do processo Python da execução e do Agent
    $peakPy = 0; $peakAgent = 0; if ($KillAgentAfterSeconds -le 0) { $final = $null }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    # o fim é acompanhado pelo agent.log (só recebe acréscimos); o estado.json é lido UMA vez, depois do fim: no
    # Windows, abrir o estado.json enquanto o Agent o troca faz a troca falhar (achado F-AGT-01)
    while (-not $final -and (Get-Date) -lt $deadline) {
        if ((Log-Count 'EXEC_FINISHED|EXEC_BLOCKED') -ge 1 -or ($cancelled -and -not (Get-Process -Id $script:jvm -ErrorAction SilentlyContinue))) {
            $s = Read-Shared $estado
            if ($s -match '"state"\s*:\s*"(SUCCEEDED|FAILED|CANCELLED|TIMED_OUT|BLOCKED|OUTCOME_UNKNOWN)"') { $final = $Matches[1]; break }
        }
        $py = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine -like '*main.py*' -and $_.CommandLine -like "*$OutputRoot*" })
        # o processo pode terminar entre a listagem e a consulta (com StrictMode, ler a propriedade de $null aborta)
        $ws = ($py | ForEach-Object { $p = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue; if ($p) { $p.WorkingSet64 } } | Measure-Object -Sum).Sum
        if ($ws -gt $peakPy) { $peakPy = $ws }
        $agentProc = Get-Process -Id $script:jvm -ErrorAction SilentlyContinue
        if ($agentProc -and $agentProc.WorkingSet64 -gt $peakAgent) { $peakAgent = $agentProc.WorkingSet64 }
        Start-Sleep -Milliseconds 400
    }
    if (-not $final) { throw 'tempo esgotado: fim da execução' }
    if ($isoRun) {
        $isoRun.WaitForExit()
        $isoJson = Join-Path (Join-Path $Out "$Label-isolamento-em-execucao") 'isolamento.json'
        $iso = if (Test-Path $isoJson) { Get-Content -Raw $isoJson | ConvertFrom-Json } else { $null }
        Check "ISO-R durante a execução (Agent): nenhuma porta TCP/UDP do processo do Agent, nenhum AF_UNIX" ($null -ne $iso -and $iso.tcp.Count -eq 0 -and $iso.udp.Count -eq 0 -and $iso.af_unix_or_reparse.Count -eq 0)
    }
    Start-Sleep -Seconds 3
    if ($cancelled) {
        # reabre o Agent para mostrar o histórico da execução cancelada
        $env:NEKKORA_AGENT_DATA_DIR = $script:data; $env:PATH = 'C:\Windows\System32;C:\Windows'; $env:COMPUTERNAME = 'LAB-AGENT-01'
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
        try { $l2 = Start-Process -FilePath $exe -PassThru; $null = $l2.Handle }
        finally {
            $env:PATH = $env0.PATH; $env:COMPUTERNAME = $env0.HOST
            if ($env0.JAVA_HOME) { $env:JAVA_HOME = $env0.JAVA_HOME }
            if ($env0.DATA) { $env:NEKKORA_AGENT_DATA_DIR = $env0.DATA } else { Remove-Item Env:NEKKORA_AGENT_DATA_DIR -ErrorAction SilentlyContinue }
        }
        $w = Wait-Until {
            foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) {
                $h = Get-AgentWindow $q.ProcessId
                if (Test-WindowShown $h) { return [pscustomobject]@{ Jvm = [int]$q.ProcessId; Handle = $h } }
            }
        } 60 'janela do Agent reaberto'
        $script:jvm = $w.Jvm
        Focus $w.Handle
        [System.Windows.Forms.SendKeys]::SendWait('%e')
        $dlg = Wait-Until { $h = Get-AgentWindow $script:jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
        Start-Sleep -Seconds 2
    }
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('%t')
    Start-Sleep -Seconds 2
    Capture "$Label-06-atividade-fim"
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('%x')
    Start-Sleep -Seconds 1
    Capture "$Label-07-execucao-fim"
    Check "fim registrado pelo Agent: $final" ($null -ne $final)
    $estadoText = Read-Shared $estado
    $id = [regex]::Match($estadoText, '"id"\s*:\s*"(ex-[0-9a-f]+)"').Groups[1].Value
    $runOut = Join-Path $OutputRoot $id
    # ---------- evidências sanitizadas (sem usuário, máquina ou caminhos) ----------
    $user = [Environment]::UserName
    function Sanitize([string]$t) { return $t.Replace($Out, '<evidencias>').Replace($OutputRoot, '<saida>').Replace($Package, '<pacote>').Replace($Python, '<python>').Replace($user, '<usuario>').Replace($env:COMPUTERNAME, '<maquina>') }
    Set-Content -Encoding UTF8 (Join-Path $Out "$Label-estado.json") (Sanitize $estadoText)
    foreach ($f in 'eventos.jsonl', 'atividade.jsonl') {
        $p = Join-Path $runDir.FullName $f
        if (Test-Path $p) { Set-Content -Encoding UTF8 (Join-Path $Out "$Label-$f") (Sanitize (Read-Shared $p)) }
    }
    $summary = Join-Path $runOut 'execucao\summary.json'
    $result = [ordered]@{
        schema = 'nekkora-e2e-005/agent-run'; version = '1.0.0'; label = $Label
        agent_zip_sha256 = $zipSha
        package_manifest_sha256 = (Get-FileHash -Algorithm SHA256 (@('manifest.json', 'nekkora-agent-lab.json' | ForEach-Object { Join-Path $Package $_ } | Where-Object { Test-Path $_ })[0])).Hash.ToLowerInvariant()
        execution_id = $id; state = $final
        code = [regex]::Match($estadoText, '"code"\s*:\s*"([A-Z0-9-]+)"').Groups[1].Value
        exit_code = [regex]::Match($estadoText, '"exitCode"\s*:\s*(-?\d+)').Groups[1].Value
        framework_summary_status = $(if (Test-Path $summary) { (Get-Content -Raw $summary | ConvertFrom-Json).status } else { $null })
        python_peak_working_set_mb = [math]::Round($peakPy / 1MB, 1)
        agent_peak_working_set_mb = [math]::Round($peakAgent / 1MB, 1)
        framework_events_bytes = $(if (Test-Path (Join-Path $runOut 'execucao\events.jsonl')) { (Get-Item (Join-Path $runOut 'execucao\events.jsonl')).Length } else { 0 })
        agent_activity_bytes = $(if (Test-Path (Join-Path $runDir.FullName 'atividade.jsonl')) { (Get-Item (Join-Path $runDir.FullName 'atividade.jsonl')).Length } else { 0 })
        duration_seconds = $(try { [math]::Round(([datetime]::Parse([regex]::Match($estadoText, '"ended"\s*:\s*"([^"]+)"').Groups[1].Value) - [datetime]::Parse([regex]::Match($estadoText, '"started"\s*:\s*"([^"]+)"').Groups[1].Value)).TotalSeconds, 1) } catch { $null })
        run_output = (Sanitize $runOut)
        # estado.json 1.1.0 (STUDIO-AGENT-LOCAL-001)
        record_version = [regex]::Match($estadoText, '"version"\s*:\s*"([0-9.]+)"').Groups[1].Value
        origin = [regex]::Match($estadoText, '"origin"\s*:\s*\{"kind"\s*:\s*"([A-Z_]+)"').Groups[1].Value
        recovered = [regex]::Match($estadoText, '"recovered"\s*:\s*(true|false)').Groups[1].Value
        cancellation = [regex]::Match($estadoText, '"cancellation"\s*:\s*(\{[^}]*\})').Groups[1].Value
        progress = [regex]::Match($estadoText, '"progress"\s*:\s*(\{"available"[^\n]*\})').Groups[1].Value
        counts = [regex]::Match($estadoText, '"counts"\s*:\s*(\{[^}]*\})').Groups[1].Value
        screenshots = $script:shots
        checks = $checks
    }
    $result | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $Out "$Label-resultado.json")
    if (($Repeat -gt 1 -or $IdleAfterSeconds -gt 0) -and -not $cancelled) {
        # ---------- operação prolongada na mesma instância ----------
        $rep = New-Object System.Collections.Generic.List[object]
        $mem = { $ap = Get-Process -Id $script:jvm -ErrorAction SilentlyContinue; if ($ap) { $ap.Refresh(); @([math]::Round($ap.WorkingSet64 / 1MB, 1), [math]::Round($ap.PrivateMemorySize64 / 1MB, 1)) } else { @(0, 0) } }
        $m = & $mem
        $rep.Add([ordered]@{ run = 1; state = $final; duration_seconds = $result.duration_seconds; agent_peak_working_set_mb = $result.agent_peak_working_set_mb; working_set_after_mb = $m[0]; private_after_mb = $m[1] })
        for ($i = 2; $i -le $Repeat; $i++) {
            $known = @(Get-ChildItem (Join-Path $script:data 'execucoes') -Directory | ForEach-Object { $_.Name })
            Focus $dlg
            [System.Windows.Forms.SendKeys]::SendWait('%x')
            Start-Sleep -Milliseconds 600
            [System.Windows.Forms.SendKeys]::SendWait('%i')
            $nd = Wait-Until { Get-ChildItem (Join-Path $script:data 'execucoes') -Directory | Where-Object { $known -notcontains $_.Name } | Select-Object -First 1 } 30 "execução $i iniciada"
            $est = Join-Path $nd.FullName 'estado.json'
            $pk = 0; $pp = 0; $st = $null; $dl = (Get-Date).AddSeconds($TimeoutSeconds)
            while (-not $st -and (Get-Date) -lt $dl) {
                if ((Log-Count 'EXEC_FINISHED|EXEC_BLOCKED') -ge $i) {
                    $t = Read-Shared $est
                    $st = if ($t -match '"state"\s*:\s*"(SUCCEEDED|FAILED|CANCELLED|TIMED_OUT|BLOCKED|OUTCOME_UNKNOWN)"') { $Matches[1] } else { 'SEM_ESTADO_TERMINAL_EM_DISCO' }
                    break
                }
                $m = & $mem; if ($m[0] -gt $pk) { $pk = $m[0] }; if ($m[1] -gt $pp) { $pp = $m[1] }
                Start-Sleep -Milliseconds 400
            }
            Start-Sleep -Seconds 1
            $m = & $mem
            $dur = $(try { [math]::Round(([datetime]::Parse([regex]::Match($t, '"ended"\s*:\s*"([^"]+)"').Groups[1].Value) - [datetime]::Parse([regex]::Match($t, '"started"\s*:\s*"([^"]+)"').Groups[1].Value)).TotalSeconds, 1) } catch { $null })
            $rep.Add([ordered]@{ run = $i; state = $st; duration_seconds = $dur; agent_peak_working_set_mb = $pk; agent_peak_private_mb = $pp; working_set_after_mb = $m[0]; private_after_mb = $m[1] })
            Write-Host ("repetição {0}: {1} · {2} s · pico {3} MB · depois {4} MB (privada {5} MB)" -f $i, $st, $dur, $pk, $m[0], $m[1])
        }
        Check "repetições na mesma instância: $Repeat de $Repeat SUCCEEDED" (@($rep | Where-Object { $_.state -eq 'SUCCEEDED' }).Count -eq $Repeat)
        $idle = New-Object System.Collections.Generic.List[object]
        if ($IdleAfterSeconds -gt 0) {
            Focus $dlg
            [System.Windows.Forms.SendKeys]::SendWait('%t')
            $t1 = Get-Date
            while (((Get-Date) - $t1).TotalSeconds -lt $IdleAfterSeconds) {
                Start-Sleep -Seconds 10
                $m = & $mem; $idle.Add([ordered]@{ seconds = [int]((Get-Date) - $t1).TotalSeconds; working_set_mb = $m[0]; private_mb = $m[1] })
            }
        }
        [ordered]@{ schema = 'nekkora-e2e-005/agent-soak'; version = '1.0.0'; label = $Label; agent_zip_sha256 = $zipSha; repeat = $Repeat; runs = $rep; idle_after = $idle } |
            ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $Out "$Label-repeticoes.json")
    }
}
finally {
    # ---------- sair do Agent ----------
    try {
        $d = Get-AgentWindow $script:jvm 'Execução local'
        if (Test-WindowShown $d) { Focus $d; [System.Windows.Forms.SendKeys]::SendWait('{ESC}'); Start-Sleep -Milliseconds 800 }
        Focus (Get-AgentWindow $script:jvm)
        [System.Windows.Forms.SendKeys]::SendWait('%s')
        Wait-Until { -not (Get-Process -Id $script:jvm -ErrorAction SilentlyContinue) } 40 'Agent saiu' | Out-Null
    } catch { Get-Process -Id $script:jvm -ErrorAction SilentlyContinue | Stop-Process -Force }
}
$fails = @($checks | Where-Object { -not $_.ok }).Count
Write-Host ("RESULTADO {0}: {1} verificações, {2} falha(s) · {3}" -f $Label, $checks.Count, $fails, $Out)
exit $(if ($fails -eq 0) { 0 } else { 2 })
