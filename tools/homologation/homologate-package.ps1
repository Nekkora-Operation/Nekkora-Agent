<#
.SYNOPSIS
  Homologação do pacote Windows do Nekkora Agent em desktop real (executável empacotado, sem Java no PATH).

.DESCRIPTION
  Extrai o ZIP em uma pasta nova FORA do repositório, confere o SHA-256 e executa os cenários abaixo com
  NEKKORA_AGENT_DATA_DIR descartável, PATH reduzido a C:\Windows e sem JAVA_HOME:
    A14 distribuição isolada (módulos carregados só do pacote; nenhum java.exe do sistema)
    A02 primeiro início (janela, ícone, estados, sem console)
    A03 fechar e restaurar com clique real no ícone da bandeja (N ciclos) + saída pelo menu da bandeja
    A04 sair e reabrir (processos encerrados, trava liberada, configuração preservada byte a byte)
    A05 inicializações simultâneas (R rodadas): um único proprietário; a segunda mostra aviso e sai com código 3
    A06 queda (TerminateProcess da JVM e do lançador) e novo início
    A07 --sem-bandeja: fechar minimiza; Alt+S (Sair) encerra
    A09 sem COMPUTERNAME: "Não disponível"
    A10 Studio = C:\Windows\System32\charmap.exe acionado pelo teclado (sem argumentos, sem shell);
        Platform = endereço local recebido por um TcpListener (opcional: -SemNavegador)
    A13 métricas: tempo até a janela, memória e CPU com a janela oculta (60 s)
  Capturas: somente o retângulo das janelas do Agent. Resultados: resultados.json e resultados.md em -Out.

  Este roteiro move o mouse, clica na bandeja e envia teclas: não use o computador durante a execução.
#>
[CmdletBinding()]
param(
    [string]$Zip,
    [string]$Out = (Join-Path ([IO.Path]::GetTempPath()) ("nekkora-agent-homolog-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))),
    [int]$Cycles = 10,
    [int]$Rounds = 5,
    [int]$MetricRuns = 5,
    [int]$IdleSeconds = 60,
    [switch]$SemNavegador
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
# PowerShell 5.1: $PSScriptRoot não existe nos valores padrão de param()
if (-not $Zip) { $Zip = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'target\dist\nekkora-agent-0.1.0-windows-x64-lab.zip' }
Import-Module (Join-Path $PSScriptRoot 'NkHomolog.psm1') -Force
$wincap = Join-Path $PSScriptRoot 'wincap.ps1'

New-Item -ItemType Directory -Force $Out | Out-Null
$results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$Id, [string]$Status, [string]$Detail, [string[]]$Evidence = @()) {
    $results.Add([pscustomobject]@{ id = $Id; status = $Status; detalhe = $Detail; evidencias = $Evidence })
    Write-Host ("[{0}] {1} - {2}" -f $Id, $Status, $Detail)
}

# ---------- preparação ----------
$sha = (Get-FileHash -Algorithm SHA256 $Zip).Hash.ToLowerInvariant()
$expected = ((Get-Content "$Zip.sha256") -split '\s+')[0]
if ($sha -ne $expected) { throw "SHA-256 do ZIP não confere ($sha <> $expected)" }
$pkgRoot = Join-Path $Out 'pacote'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($Zip, $pkgRoot)
$appDir = Join-Path $pkgRoot 'Nekkora Agent'
$exe = Join-Path $appDir 'Nekkora Agent.exe'
$capDir = Join-Path $Out 'capturas'
New-Item -ItemType Directory -Force $capDir | Out-Null

$prefKey = 'HKCU:\Software\JavaSoft\Prefs\com\nekkora\ui\theme'
function Get-StudioThemePref { if (Test-Path $prefKey) { (Get-ItemProperty $prefKey | Out-String).Trim() } else { '<ausente>' } }
$prefBefore = Get-StudioThemePref
$agentPrefBefore = Test-Path 'HKCU:\Software\JavaSoft\Prefs\com\nekkora\agent'

$env0 = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME; COMPUTERNAME = $env:COMPUTERNAME; DATA = $env:NEKKORA_AGENT_DATA_DIR }

function Start-Agent([string]$DataDir, [string[]]$Arguments = @(), [string]$ComputerName = 'LAB-AGENT-01') {
    $env:NEKKORA_AGENT_DATA_DIR = $DataDir
    $env:PATH = 'C:\Windows\System32;C:\Windows'
    Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    if ($ComputerName) { $env:COMPUTERNAME = $ComputerName } else { Remove-Item Env:COMPUTERNAME -ErrorAction SilentlyContinue }
    try {
        if ($Arguments.Count -gt 0) { $proc = Start-Process -FilePath $exe -ArgumentList $Arguments -PassThru }
        else { $proc = Start-Process -FilePath $exe -PassThru }
        $null = $proc.Handle # PowerShell 5.1: sem acessar o handle cedo, ExitCode fica vazio
        return $proc
    } finally {
        $env:PATH = $env0.PATH
        if ($env0.JAVA_HOME) { $env:JAVA_HOME = $env0.JAVA_HOME }
        $env:COMPUTERNAME = $env0.COMPUTERNAME
        if ($env0.DATA) { $env:NEKKORA_AGENT_DATA_DIR = $env0.DATA } else { Remove-Item Env:NEKKORA_AGENT_DATA_DIR -ErrorAction SilentlyContinue }
    }
}

function Get-PackageProcesses {
    @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })
}

# JVM (processo filho do lançador jpackage) com a janela principal
function Wait-AgentWindow([int]$LauncherPid, [int]$Timeout = 30) {
    Wait-Until {
        foreach ($p in Get-PackageProcesses) {
            if ($p.ParentProcessId -eq $LauncherPid -or $p.ProcessId -eq $LauncherPid) {
                $h = Get-AgentWindow $p.ProcessId
                if (Test-WindowShown $h) { return [pscustomobject]@{ Jvm = [int]$p.ProcessId; Handle = $h } }
            }
        }
    } $Timeout 'janela do Agent'
}

function Read-Log([string]$DataDir) {
    $f = Join-Path $DataDir 'logs\agent.log'
    if (Test-Path $f) { return (Get-Content -Raw $f) } else { return '' }
}

function Stop-AllAgents { Get-PackageProcesses | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Start-Sleep -Milliseconds 300 }

function Capture([int]$ProcessId, [string]$Name, [string]$Title = '*') {
    Start-Sleep -Milliseconds 400
    $file = Join-Path $capDir "$Name.png"
    & powershell -NoProfile -ExecutionPolicy Bypass -File $wincap -ProcessId $ProcessId -Out $file -TitleLike $Title | Out-Null
    return $file
}

function Focus-AgentWindow([IntPtr]$Handle) {
    # clique real na barra de título (área neutra) para dar o foco do teclado à janela.
    # O estouro da bandeja, se aberto, "come" o primeiro clique de fora para se fechar: Esc antes e um
    # segundo clique depois do intervalo de duplo clique (para não maximizar).
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
    Start-Sleep -Milliseconds 400
    if (-not [NkUser32]::ForceForeground($Handle)) {
        $rect = [System.Windows.Automation.AutomationElement]::FromHandle($Handle).Current.BoundingRectangle
        [NkUser32]::Click([int]($rect.X + 140), [int]($rect.Y + 16), $false)
    }
    Start-Sleep -Milliseconds 400
    if ([NkUser32]::GetForegroundWindow() -ne $Handle) { Write-Warning 'janela do Agent não ficou em primeiro plano' }
}

$script:exitPaths = @()
# Sair pelo menu do ícone (clique direito real + ↑ + Enter). Se o Explorer não entregar o clique sintético,
# sai pelo botão "Sair" da janela (Alt+S) e registra o caminho usado.
function Exit-ViaTray([int]$Jvm) {
    Invoke-TrayIcon -Right | Out-Null
    Start-Sleep -Milliseconds 700
    [System.Windows.Forms.SendKeys]::SendWait('{UP}')
    Start-Sleep -Milliseconds 150
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    try {
        Wait-Until { -not (Get-Process -Id $Jvm -ErrorAction SilentlyContinue) } 6 'saída pelo menu da bandeja' | Out-Null
        $script:exitPaths += 'menu'
        return
    } catch { }
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
    $h = Get-AgentWindow $Jvm
    if (-not (Test-WindowShown $h)) {
        Restore-ViaTray { Test-WindowShown (Get-AgentWindow $Jvm) } | Out-Null
        $h = Get-AgentWindow $Jvm
    }
    Focus-AgentWindow $h
    [System.Windows.Forms.SendKeys]::SendWait('%s')
    Wait-Until { -not (Get-Process -Id $Jvm -ErrorAction SilentlyContinue) } 15 'saída por Alt+S' | Out-Null
    $script:exitPaths += 'alt+s'
}

Stop-AllAgents
# ícones fantasmas de execuções anteriores encerradas à força (o Windows só os remove quando o mouse passa)
$ghostsAtStart = Clear-GhostTrayIcons
if ($ghostsAtStart -ne 0) { throw "há $ghostsAtStart ícone(s) 'Nekkora Agent' na bandeja antes de começar" }
[System.Windows.Forms.SendKeys]::SendWait('{ESC}')
$machine = Get-CimInstance Win32_ComputerSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$os = Get-CimInstance Win32_OperatingSystem
$display = Get-CimInstance Win32_VideoController | Select-Object -First 1
$envInfo = [ordered]@{
    sistema = "$($os.Caption) $($os.Version) build $($os.BuildNumber) ($($os.OSArchitecture))"
    processador = "$($cpu.Name.Trim()) ($($cpu.NumberOfCores) núcleos, $($cpu.NumberOfLogicalProcessors) lógicos)"
    memoriaGiB = [math]::Round($machine.TotalPhysicalMemory / 1GB, 1)
    tela = "$($display.CurrentHorizontalResolution)x$($display.CurrentVerticalResolution)"
    escalaWindows = $(try { "$([math]::Round((Get-ItemProperty 'HKCU:\Control Panel\Desktop\WindowMetrics' -Name AppliedDPI).AppliedDPI / 96 * 100))%" } catch { 'não lida' })
    zip = (Split-Path -Leaf $Zip)
    zipSha256 = $sha
}

# ---------- A14 + A02 ----------
$d = Join-Path $Out 'dados-a02'
$javaOnPath = $null
$env:PATH = 'C:\Windows\System32;C:\Windows'; try { $javaOnPath = (Get-Command java -ErrorAction SilentlyContinue) } finally { $env:PATH = $env0.PATH }
$sw = [Diagnostics.Stopwatch]::StartNew()
$p = Start-Agent $d
$w = Wait-AgentWindow $p.Id
$sw.Stop()
Start-Sleep -Seconds 2
$procs = @(Get-PackageProcesses)
$mods = (Get-Process -Id $w.Jvm).Modules | ForEach-Object { $_.FileName }
$foreign = @($mods | Where-Object { $_ -notlike "$appDir\*" -and $_ -notlike "$env:windir\*" -and $_ -notlike 'C:\Windows\*' })
$jdkMods = @($mods | Where-Object { $_ -like '*\jdk-*' -or $_ -like '*Microsoft\jdk*' -or $_ -like '*Nekkora-Studio*' -or $_ -like '*.design-system*' })
$consoles = @(Get-CimInstance Win32_Process | Where-Object { ($_.ParentProcessId -in @($procs.ProcessId)) -and $_.Name -in 'conhost.exe', 'cmd.exe', 'java.exe', 'javaw.exe' })
$cap = Capture $w.Jvm 'A02-A14-pacote-primeiro-inicio' 'Nekkora Agent'
$tray = @(Find-TrayButtons)
$log = Read-Log $d
$ok14 = ($null -eq $javaOnPath) -and ($jdkMods.Count -eq 0) -and ($foreign.Count -eq 0)
Add-Result 'A14' ($(if ($ok14) { 'APROVADO' } else { 'FALHOU' })) ("java no PATH reduzido: " + $(if ($javaOnPath) { $javaOnPath.Source } else { 'nenhum' }) + "; JAVA_HOME removido; $($mods.Count) módulos carregados, fora do pacote/Windows: $($foreign.Count); do JDK/Studio/.design-system: $($jdkMods.Count)") @($cap)
$okStates = $log -match 'LIFE_READY startup_ms=(\d+)'
$startupMs = if ($okStates) { [int]$Matches[1] } else { -1 }
$ok02 = ($procs.Count -eq 2) -and ($consoles.Count -eq 0) -and ($log -match 'TRAY_INSTALLED')
Add-Result 'A02' ($(if ($ok02) { 'APROVADO' } else { 'FALHOU' })) ("janela em $($sw.ElapsedMilliseconds) ms (externo), startup_ms=$startupMs (log); processos: $($procs.Count) (lançador + JVM); consoles: $($consoles.Count); ícone na bandeja: $(if ($tray.Count -gt 0) { 'visível' } else { 'no estouro (Mostrar ícones ocultos)' })") @($cap)

# ---------- A03 ----------
$restoreOk = 0
$viaMouse = 0
$viaInvoke = 0
for ($i = 1; $i -le $Cycles; $i++) {
    Close-Window (Get-AgentWindow $w.Jvm)
    Wait-Until { -not (Test-WindowShown (Get-AgentWindow $w.Jvm)) } 10 "oculta $i" | Out-Null
    if (-not (Get-Process -Id $w.Jvm -ErrorAction SilentlyContinue)) { break }
    $via = Restore-ViaTray { Test-WindowShown (Get-AgentWindow $w.Jvm) }
    if ($via -eq 'mouse') { $viaMouse++ } else { $viaInvoke++ }
    $restoreOk++
}
$trayCountOverflow = Get-TrayIconCount
$cap3 = Capture $w.Jvm 'A03-restaurada-pela-bandeja' 'Nekkora Agent'
$log = Read-Log $d
$hidden = ([regex]::Matches($log, 'LIFE_WINDOW_HIDDEN to=tray')).Count
$restored = ([regex]::Matches($log, 'LIFE_WINDOW_RESTORED')).Count
$ok03 = ($restoreOk -eq $Cycles) -and ($trayCountOverflow -eq 1) -and (@(Get-PackageProcesses).Count -eq 2)
Add-Result 'A03' ($(if ($ok03) { 'APROVADO' } else { 'FALHOU' })) ("$restoreOk/$Cycles ciclos fechar (WM_CLOSE) → ícone da bandeja → janela de volta (clique real do mouse: $viaMouse; Invoke da UI Automation quando o Explorer não entregou o clique sintético: $viaInvoke); ícones do Agent na bandeja: $trayCountOverflow; log: $hidden ocultações, $restored restaurações; processos: $(@(Get-PackageProcesses).Count)") @($cap3)

# ---------- A04 ----------
$cfgDir = Join-Path $d 'config'
$cfg = Join-Path $cfgDir 'agent-config.json'
Exit-ViaTray $w.Jvm
Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 'processos encerrados' | Out-Null
$afterExit = @(Get-PackageProcesses).Count
$trayAfter = Get-TrayIconCount
$lockExists = Test-Path (Join-Path $d 'agent.lock')
$exitLog = Read-Log $d
# configuração escrita entre execuções (tema claro) deve sobreviver a sair/reabrir
New-Item -ItemType Directory -Force $cfgDir | Out-Null
$cfgText = "{`n  `"format`": `"nekkora-agent/config`",`n  `"version`": 1,`n  `"theme`": `"porcelain-light`",`n  `"shortcuts`": {`n    `"platformUrl`": null,`n    `"studioExecutable`": null`n  }`n}`n"
[IO.File]::WriteAllText($cfg, $cfgText, (New-Object Text.UTF8Encoding $false))
$h1 = (Get-FileHash $cfg).Hash
$p = Start-Agent $d
$w = Wait-AgentWindow $p.Id
$cap4 = Capture $w.Jvm 'A04-reaberto-config-preservada' 'Nekkora Agent'
Exit-ViaTray $w.Jvm
Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 'processos encerrados' | Out-Null
$h2 = (Get-FileHash $cfg).Hash
$starts = ([regex]::Matches((Read-Log $d), 'LIFE_START')).Count
$ok04 = ($afterExit -eq 0) -and ($trayAfter -eq 0) -and $lockExists -and ($exitLog -match 'LIFE_EXIT status=0') -and ($h1 -eq $h2) -and ($starts -eq 2)
Add-Result 'A04' ($(if ($ok04) { 'APROVADO' } else { 'FALHOU' })) ("Sair pelo menu da bandeja: processos restantes $afterExit, ícone visível $trayAfter, LIFE_EXIT registrado; reabriu com a trava (arquivo mantido: $lockExists); config igual antes/depois: $($h1 -eq $h2) (tema claro aplicado na captura)") @($cap4)

# ---------- A05 ----------
$okRounds = 0
$noticeCap = $null
$codes = @()
for ($r = 1; $r -le $Rounds; $r++) {
    $dr = Join-Path $Out "dados-a05-$r"
    $pa = Start-Agent $dr
    $pb = Start-Agent $dr
    $script:owner = $null
    $script:notice = $null
    Wait-Until {
        foreach ($q in Get-PackageProcesses) {
            if ($null -eq $script:owner -and (Test-WindowShown (Get-AgentWindow $q.ProcessId))) { $script:owner = $q }
            if ($null -eq $script:notice -and (Test-WindowShown (Get-AgentWindow $q.ProcessId 'O Nekkora Agent já está aberto'))) { $script:notice = $q }
        }
        ($null -ne $script:owner) -and ($null -ne $script:notice)
    } 40 "rodada ${r}: proprietário e aviso" | Out-Null
    $owner = $script:owner; $notice = $script:notice
    if ($r -eq 1) { $noticeCap = Capture $notice.ProcessId 'A05-segunda-instancia-aviso' 'O Nekkora Agent*' }
    $launcherOfNotice = [int]$notice.ParentProcessId
    $lp = @($pa, $pb) | Where-Object { $_.Id -eq $launcherOfNotice } | Select-Object -First 1
    Close-Window (Get-AgentWindow $notice.ProcessId 'O Nekkora Agent já está aberto')
    Wait-Until { -not (Get-Process -Id $notice.ProcessId -ErrorAction SilentlyContinue) } 15 'segunda instância saiu' | Out-Null
    if ($lp) { $lp.WaitForExit(10000) | Out-Null; $codes += $lp.ExitCode }
    $remaining = @(Get-PackageProcesses)
    $logR = Read-Log $dr
    $oneStart = ([regex]::Matches($logR, 'LIFE_START')).Count -eq 1
    $cfgUntouched = -not (Test-Path (Join-Path $dr 'config\agent-config.json'))
    if (($remaining.Count -eq 2) -and $oneStart -and $cfgUntouched) { $okRounds++ }
    Exit-ViaTray ([int]$owner.ProcessId)
    Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 'fim da rodada' | Out-Null
}
$ok05 = ($okRounds -eq $Rounds) -and (@($codes | Where-Object { $_ -ne 3 }).Count -eq 0)
Add-Result 'A05' ($(if ($ok05) { 'APROVADO' } else { 'FALHOU' })) ("$okRounds/$Rounds rodadas com duas aberturas simultâneas: um único proprietário (1 LIFE_START, nenhuma configuração criada pela segunda), a outra mostrou o aviso e saiu; códigos de saída da segunda: $($codes -join ',')") @($noticeCap)

# ---------- A06 ----------
$d6 = Join-Path $Out 'dados-a06'
$a06 = @()
foreach ($mode in 'jvm', 'lancador', 'jvm') {
    $p = Start-Agent $d6
    $w = Wait-AgentWindow $p.Id
    if ($mode -eq 'jvm') { Stop-Process -Id $w.Jvm -Force } else { Stop-Process -Id $p.Id -Force }
    Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 "processos mortos ($mode)" | Out-Null
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $p2 = Start-Agent $d6
    $w2 = Wait-AgentWindow $p2.Id
    $sw.Stop()
    $a06 += "$mode→reaberto em $($sw.ElapsedMilliseconds) ms"
    Stop-Process -Id $w2.Jvm -Force
    Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 'limpeza' | Out-Null
}
$log6 = Read-Log $d6
$ghosts = Clear-GhostTrayIcons
[System.Windows.Forms.SendKeys]::SendWait('{ESC}')
$ok06 = ([regex]::Matches($log6, 'LOCK_ACQUIRED')).Count -eq 6 -and (Test-Path (Join-Path $d6 'agent.lock')) -and ($ghosts -eq 0)
Add-Result 'A06' ($(if ($ok06) { 'APROVADO' } else { 'FALHOU' })) ("TerminateProcess (sem saída ordenada) e novo início: $($a06 -join '; '); trava adquirida em todos os 6 inícios, arquivo da trava mantido; ícones restantes após passar o mouse na bandeja: $ghosts (o Windows mantém o ícone de processo morto até o mouse passar)") @()

# ---------- A07 ----------
$d7 = Join-Path $Out 'dados-a07'
$p = Start-Agent $d7 @('--sem-bandeja')
$w = Wait-AgentWindow $p.Id
$cap7 = Capture $w.Jvm 'A07-pacote-sem-bandeja' 'Nekkora Agent'
$trayNone = (Get-TrayIconCount) -eq 0
Close-Window $w.Handle
$iconic = Wait-Until { [NkUser32]::IsIconic((Get-AgentWindow $w.Jvm)) } 10 'minimizada'
$alive = [bool](Get-Process -Id $w.Jvm -ErrorAction SilentlyContinue)
[NkUser32]::ShowWindow((Get-AgentWindow $w.Jvm), 9) | Out-Null  # SW_RESTORE (como clicar na barra de tarefas)
Wait-Until { -not [NkUser32]::IsIconic((Get-AgentWindow $w.Jvm)) } 10 'restaurada' | Out-Null
Focus-AgentWindow (Get-AgentWindow $w.Jvm)
[System.Windows.Forms.SendKeys]::SendWait('%s')
$exited = $true
try { Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 'Alt+S' | Out-Null } catch { $exited = $false; Stop-AllAgents }
$log7 = Read-Log $d7
$ok07 = $trayNone -and $alive -and $exited -and ($log7 -match 'TRAY_UNAVAILABLE code=AGT-TRAY-003') -and ($log7 -match 'LIFE_EXIT status=0')
Add-Result 'A07' ($(if ($ok07) { 'APROVADO' } else { 'FALHOU' })) ("--sem-bandeja: nenhum ícone; fechar → minimizada com processo vivo ($alive); Alt+S (Sair) pelo teclado encerrou: $exited. Falha real de instalação da bandeja: simulada no teste automatizado") @($cap7)

# ---------- A09 ----------
$d9 = Join-Path $Out 'dados-a09'
$p = Start-Agent $d9 @() $null
$w = Wait-AgentWindow $p.Id
$cap9 = Capture $w.Jvm 'A09-pacote-sem-nome-de-maquina' 'Nekkora Agent'
$log9 = Read-Log $d9
Stop-Process -Id $w.Jvm -Force; Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 'fim A09' | Out-Null
Add-Result 'A09' ($(if ($log9 -match 'MACHINE_NAME_UNAVAILABLE code=AGT-HOST-001') { 'APROVADO' } else { 'FALHOU' })) "COMPUTERNAME removido do ambiente do processo: código AGT-HOST-001 no log; a captura mostra 'Não disponível'" @($cap9)

# ---------- A10 ----------
$d10 = Join-Path $Out 'dados-a10'
$port = Get-Random -Minimum 47000 -Maximum 48999
$url = "http://127.0.0.1:$port/nekkora-agent-homologacao"
New-Item -ItemType Directory -Force (Join-Path $d10 'config') | Out-Null
$cfg10 = "{`n  `"format`": `"nekkora-agent/config`",`n  `"version`": 1,`n  `"theme`": `"sakura-dark`",`n  `"shortcuts`": {`n    `"platformUrl`": `"$url`",`n    `"studioExecutable`": `"C:\\Windows\\System32\\charmap.exe`"`n  }`n}`n"
[IO.File]::WriteAllText((Join-Path $d10 'config\agent-config.json'), $cfg10, (New-Object Text.UTF8Encoding $false))
$charmapBefore = @(Get-Process charmap -ErrorAction SilentlyContinue).Count
$p = Start-Agent $d10
$w = Wait-AgentWindow $p.Id
Start-Sleep -Seconds 3
$autoOpened = @(Get-Process charmap -ErrorAction SilentlyContinue).Count - $charmapBefore
$cap10 = Capture $w.Jvm 'A10-pacote-atalhos-configurados' 'Nekkora Agent'
Focus-AgentWindow $w.Handle
[System.Windows.Forms.SendKeys]::SendWait(' ')  # foco inicial: "Abrir Studio"
$cm = Wait-Until { Get-CimInstance Win32_Process -Filter "Name='charmap.exe'" | Where-Object { $_.ParentProcessId -eq $w.Jvm } } 15 'charmap iniciado pelo Agent'
$cmLine = @($cm)[0].CommandLine
$browserDetail = 'não exercitado (-SemNavegador)'
if (-not $SemNavegador) {
    $listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback, $port)
    $listener.Start()
    try {
        # o Mapa de Caracteres aberto pelo atalho fica por cima: minimiza (sem fechar) para o clique de foco cair no Agent
        foreach ($ch in [NkUser32]::Windows([uint32]@($cm)[0].ProcessId, $true)) { [NkUser32]::ShowWindow($ch, 6) | Out-Null }
        Start-Sleep -Milliseconds 400
        Focus-AgentWindow $w.Handle
        [System.Windows.Forms.SendKeys]::SendWait('%p')  # Alt+P = "Abrir Platform"
        $accept = $listener.AcceptTcpClientAsync()
        if ($accept.Wait(20000)) {
            $client = $accept.Result
            $reader = New-Object IO.StreamReader($client.GetStream())
            $requestLine = $reader.ReadLine()
            $writer = New-Object IO.StreamWriter($client.GetStream())
            $writer.Write("HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nConnection: close`r`n`r`n<!doctype html><title>Nekkora Agent</title><p>Homologação do Nekkora Agent: endereço recebido. Pode fechar esta aba.</p>")
            $writer.Flush(); $client.Close()
            $browserDetail = "navegador padrão pediu '$requestLine'"
        } else { $browserDetail = 'navegador não fez a requisição em 20 s' }
    } finally { $listener.Stop() }
}
$cmRunning = @($cm)[0].ProcessId
Exit-ViaTray $w.Jvm
Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 'saída A10' | Out-Null
# o filho iniciado pelo Agent não pode herdar a trava: com o charmap ainda aberto, o Agent reabre
$charmapAlive = [bool](Get-Process -Id $cmRunning -ErrorAction SilentlyContinue)
$p = Start-Agent $d10
$w = Wait-AgentWindow $p.Id
$reopened = $true
Exit-ViaTray $w.Jvm
Stop-Process -Id $cmRunning -Force -ErrorAction SilentlyContinue
$log10 = Read-Log $d10
$ok10 = ($autoOpened -eq 0) -and ($cmLine.Trim().Trim('"') -eq 'C:\Windows\System32\charmap.exe') -and $charmapAlive -and $reopened -and ($log10 -notmatch '127\.0\.0\.1|charmap')
if (-not $SemNavegador) { $ok10 = $ok10 -and ($browserDetail -like "*GET /nekkora-agent-homologacao *") }
Add-Result 'A10' ($(if ($ok10) { 'APROVADO' } else { 'FALHOU' })) ("nada abriu sozinho ($autoOpened); Espaço em 'Abrir Studio' → linha de comando do filho: $cmLine (pai = JVM do Agent); $browserDetail; com o filho ainda aberto o Agent saiu e reabriu (sem herdar a trava); destinos ausentes do log") @($cap10)

# ---------- A13 (métricas) ----------
$m = @()
for ($k = 1; $k -le $MetricRuns; $k++) {
    $dm = Join-Path $Out "dados-a13-$k"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $p = Start-Agent $dm
    $w = Wait-AgentWindow $p.Id
    $sw.Stop()
    Start-Sleep -Seconds 10
    $jvm = Get-Process -Id $w.Jvm; $lau = Get-Process -Id $p.Id
    $visibleWs = ($jvm.WorkingSet64 + $lau.WorkingSet64) / 1MB
    $visiblePriv = ($jvm.PrivateMemorySize64 + $lau.PrivateMemorySize64) / 1MB
    $ms = if ((Read-Log $dm) -match 'LIFE_READY startup_ms=(\d+)') { [int]$Matches[1] } else { -1 }
    $row = [ordered]@{ execucao = $k; janelaMsExterno = $sw.ElapsedMilliseconds; startupMsLog = $ms; wsVisivelMiB = [math]::Round($visibleWs, 1); privadaVisivelMiB = [math]::Round($visiblePriv, 1); threads = $jvm.Threads.Count }
    if ($k -eq 1) {
        Close-Window $w.Handle
        Wait-Until { -not (Test-WindowShown (Get-AgentWindow $w.Jvm)) } 10 'oculta' | Out-Null
        Start-Sleep -Seconds 5
        $jvm.Refresh(); $lau.Refresh()
        $c0 = $jvm.TotalProcessorTime.TotalMilliseconds + $lau.TotalProcessorTime.TotalMilliseconds
        $t0 = [Diagnostics.Stopwatch]::StartNew()
        Start-Sleep -Seconds $IdleSeconds
        $jvm.Refresh(); $lau.Refresh()
        $c1 = $jvm.TotalProcessorTime.TotalMilliseconds + $lau.TotalProcessorTime.TotalMilliseconds
        $row.ocioso = [ordered]@{ segundos = [math]::Round($t0.Elapsed.TotalSeconds, 1); cpuMs = [math]::Round($c1 - $c0, 1); cpuPctDeUmNucleo = [math]::Round(100 * ($c1 - $c0) / $t0.Elapsed.TotalMilliseconds, 3); wsOcultoMiB = [math]::Round(($jvm.WorkingSet64 + $lau.WorkingSet64) / 1MB, 1); privadaOcultoMiB = [math]::Round(($jvm.PrivateMemorySize64 + $lau.PrivateMemorySize64) / 1MB, 1) }
    }
    $m += [pscustomobject]$row
    Stop-Process -Id $w.Jvm -Force
    Wait-Until { @(Get-PackageProcesses).Count -eq 0 } 15 'fim métrica' | Out-Null
}
$idle = $m[0].ocioso
Add-Result 'A13' 'MEDIDO' ("janela (externo) " + (($m | ForEach-Object { $_.janelaMsExterno }) -join '/') + " ms; startup_ms (log) " + (($m | ForEach-Object { $_.startupMsLog }) -join '/') + "; WS visível " + (($m | ForEach-Object { $_.wsVisivelMiB }) -join '/') + " MiB; oculto $($idle.segundos) s: CPU $($idle.cpuMs) ms ($($idle.cpuPctDeUmNucleo)% de um núcleo), WS $($idle.wsOcultoMiB) MiB. Suspensão/retorno: não exercitado por este roteiro") @()

# ---------- preferências do Studio ----------
$prefAfter = Get-StudioThemePref
$agentPrefAfter = Test-Path 'HKCU:\Software\JavaSoft\Prefs\com\nekkora\agent'
Add-Result 'D-05' ($(if ($prefBefore -eq $prefAfter -and -not $agentPrefAfter -and -not $agentPrefBefore) { 'APROVADO' } else { 'FALHOU' })) "registro HKCU\Software\JavaSoft\Prefs\com\nekkora\ui\theme igual antes e depois de toda a homologação (inclui troca de tema por configuração); nenhum nó do Agent criado" @()

$menuExits = @($script:exitPaths | Where-Object { $_ -eq 'menu' }).Count
Add-Result 'SAIDAS' 'INFO' ("saídas ordenadas usadas pelo roteiro: $menuExits pelo menu da bandeja (clique direito real), $($script:exitPaths.Count - $menuExits) por Alt+S na janela quando o clique sintético não foi entregue") @()
Stop-AllAgents
Clear-GhostTrayIcons | Out-Null  # deixa a bandeja limpa dos processos encerrados à força nas métricas
[System.Windows.Forms.SendKeys]::SendWait('{ESC}')
$summary = [ordered]@{ ambiente = $envInfo; resultados = $results; metricas = $m; gerado = (Get-Date).ToString('s') }
$summary | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $Out 'resultados.json')
$md = @("# Homologação do pacote — $(Get-Date -Format 'yyyy-MM-dd HH:mm')", '', '| Item | Valor |', '|---|---|')
foreach ($k in $envInfo.Keys) { $md += "| $k | $($envInfo[$k]) |" }
$md += ''; $md += '| ID | Resultado | Detalhe |'; $md += '|---|---|---|'
foreach ($r in $results) { $md += "| $($r.id) | $($r.status) | $($r.detalhe.Replace('|', '/')) |" }
$md += ''; $md += '## Métricas (A13)'; $md += ''; $md += '```'; $md += ($m | ConvertTo-Json -Depth 4); $md += '```'
$md | Set-Content -Encoding UTF8 (Join-Path $Out 'resultados.md')
Write-Host "Resultados em $Out"
