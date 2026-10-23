<#
.SYNOPSIS
  G2 / A13 / OBS-G6 FÍSICOS: bloqueio real da tela e suspensão real do Windows com a titular presente.
  Tudo sintético (dados fictícios, sem rede, sem dados de clientes). O roteiro NUNCA bloqueia, suspende nem
  desbloqueia a máquina: só a pessoa faz isso, quando o roteiro pedir.

.DESCRIPTION
  Ao mesmo tempo, atravessando o MESMO bloqueio e a MESMA suspensão:
    P  Agent EMPACOTADO (executável, sem Java no PATH) — o único com ícone na bandeja — supervisionando uma execução
       ADOTADA que publica eventos REAIS do Framework (~6 min acordada). A13: mesmo processo (PIDs), um único ícone,
       nenhum novo LIFE_START, segunda abertura recusada com aviso; OBS-G6 na aba Atividade.
    S1 sucesso        execução lançada pelo botão Iniciar: efeito único, ~6 min, publica SUCCEEDED → SUCCEEDED
    S2 sem_protocolo  idem, sai 0 SEM resultado → OUTCOME_UNKNOWN (AGT-EXE-026): nada é presumido
    S3 falha          idem, publica FAILED e sai 1 → FAILED
  (S1–S3 rodam na janela real do Agent em processos Java próprios, SEM ícone na bandeja.)

  Instantâneos (só estado, códigos e contagens) antes / durante o bloqueio / depois do desbloqueio / depois da
  retomada / fim; capturas das janelas; evidências SANITIZADAS em <Out>\evidencias (sem usuário, máquina ou
  caminhos). O material bruto fica em <Out>\bruto (local; não versionar).

  Uso (PowerShell, na sessão da titular, máquina livre, "Não incomodar" ligado):
    .\fisica-g2-a13.ps1 -Python <python do venv do Framework> -TestClasspath <classpath de teste>
  Ensaio sem a pessoa (confere a automação; bloqueio/suspensão ficam NÃO EXERCIDOS):
    .\fisica-g2-a13.ps1 ... -Ensaio
#>
param(
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$TestClasspath,
    [string]$Zip,
    [string]$Out,
    [switch]$Ensaio,
    [switch]$SemPausa
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$script:exe = $null
$script:jobs = $null
$script:raw = $null
# qualquer erro inesperado reprova (nunca termina com 0 sem o resultado)
trap {
    Write-Host "ERRO DO ROTEIRO: $_"; Write-Host $_.ScriptStackTrace
    # encerra só o que este roteiro abriu (cópia do Agent extraída aqui e os processos Java dos cenários)
    if ($script:exe) { Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $script:exe } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } }
    if ($script:jobs) { foreach ($j in $script:jobs.Values) { if (-not $j.HasExited) { Stop-Process -Id $j.Id -Force -ErrorAction SilentlyContinue } } }
    # e os processos sintéticos (P semeado e laboratórios dos cenários), que sobreviveriam ~6 min: só os que gravam em <Out>\bruto
    if ($script:raw) {
        Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('--output') -and $_.CommandLine.IndexOf($script:raw, [StringComparison]::OrdinalIgnoreCase) -ge 0 } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    exit 2
}
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Zip) { $Zip = Join-Path $root 'target\dist\nekkora-agent-0.1.0-windows-x64-lab.zip' }
if (-not $Out) { $Out = Join-Path ([IO.Path]::GetTempPath()) ("nekkora-fisica-g2-a13-" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
Import-Module (Join-Path $PSScriptRoot 'NkHomolog.psm1') -Force
$wincap = Join-Path $PSScriptRoot 'wincap.ps1'
$raw = Join-Path $Out 'bruto'
$script:raw = $raw
$ev = Join-Path $Out 'evidencias'
New-Item -ItemType Directory -Force $raw, $ev | Out-Null
$results = New-Object System.Collections.Generic.List[string]
$fails = 0
function Check([string]$What, [bool]$Ok) {
    $line = ('{0} {1}' -f $(if ($Ok) { 'OK   ' } else { 'FALHA' }), $What)
    $results.Add($line); Write-Host $line
    if (-not $Ok) { $script:fails++ }
}
function Note([string]$What) { $results.Add("     $What"); Write-Host "     $What" }
# Arquivos que o Agent, o supervisor ou o Framework estão gravando são lidos SEM travá-los (leitura, escrita e exclusão
# compartilhadas): uma leitura com ReadAllLines/Get-Content recusaria o acréscimo do atividade.jsonl e a troca atômica do
# estado.json no mesmo instante (o ensaio da auditoria perdeu 4 linhas de atividade assim).
function Read-Shared([string]$File) {
    for ($k = 0; ; $k++) {
        try {
            $fs = New-Object IO.FileStream($File, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            try { return (New-Object IO.StreamReader($fs, [Text.Encoding]::UTF8)).ReadToEnd() } finally { $fs.Dispose() }
        } catch [IO.IOException] { if ($k -ge 5) { throw }; Start-Sleep -Milliseconds 100 }
    }
}
function Lines([string]$File) {
    if (-not (Test-Path $File)) { return @() }
    return @((Read-Shared $File) -split "`r?`n" | Where-Object { $_ -ne '' })
}
function Field([string]$Json, [string]$Name) {
    $m = [regex]::Match($Json, '"' + $Name + '"\s*:\s*"?([^",}]*)"?')
    if ($m.Success -and $m.Groups[1].Value -ne 'null') { return $m.Groups[1].Value } else { return $null }
}
# espera que vira verificação (sem abortar o roteiro): $null quando o tempo esgota
function Wait-Ok([scriptblock]$Condition, [int]$TimeoutSeconds, [string]$What) {
    try { return Wait-Until $Condition $TimeoutSeconds $What } catch { Note "tempo esgotado: $What"; return $null }
}
function Session-Locked {
    $sid = [Diagnostics.Process]::GetCurrentProcess().SessionId
    return [bool](@(Get-Process -Name LogonUI -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -eq $sid }).Count)
}

Write-Host "== G2/A13/OBS-G6 físicos $(if ($Ensaio) { '(ENSAIO: sem bloqueio/suspensão)' }) — saída: $Out"

# ---------- 0. pré-condições ----------
$expected = ((Get-Content "$Zip.sha256") -split '\s+')[0]
Check "SHA-256 do ZIP do Agent confere" ((Get-FileHash -Algorithm SHA256 $Zip).Hash.ToLowerInvariant() -eq $expected)
Check "nenhum Nekkora Agent aberto antes de começar" (@(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'").Count -eq 0)
$ghosts = Clear-GhostTrayIcons
Check "nenhum ícone do Nekkora Agent na bandeja antes de começar" ($ghosts -eq 0)
Check "Python do venv do Framework existe" (Test-Path $Python)
# caminho absoluto: o registro da execução guarda o interpretador e a adoção confere o processo por ele
if (Test-Path $Python) { $Python = (Resolve-Path $Python).Path }
$power = (& "$env:SystemRoot\System32\powercfg.exe" /a 2>&1) -join "`n"
$standby = $power.Split("`n") | Select-Object -First 3
Note ("energia (powercfg /a, início): " + (($standby | ForEach-Object { $_.Trim() }) -join ' | '))
if ($fails -gt 0) { Write-Host "Pré-condições não atendidas; nada foi iniciado."; exit 2 }

# ---------- 1. Agent empacotado com execução adotada (eventos do Framework) ----------
$app = Join-Path $raw 'agent'
Expand-Archive -Path $Zip -DestinationPath $app -Force
$script:exe = (Get-ChildItem -Recurse -Filter 'Nekkora Agent.exe' $app | Select-Object -First 1).FullName
$exe = $script:exe
$data = Join-Path $raw 'pacote-dados'
$saida = Join-Path $raw 'pacote-saida'
$pkg = Join-Path $raw 'pacote-formato-studio'
New-Item -ItemType Directory -Force $data, $saida | Out-Null
$cfg = 'slow:120:1000'  # modo:transações:ms por ação — 120 x 3 ações x 1 s ≈ 6 min acordada (sem aspas: o PS 5.1 as remove de argumentos)
Push-Location $root
try { $seed = & java -cp $TestClasspath com.nekkora.agent.ui.DesktopHomologation semear-obs (Join-Path $raw 'semear') $data $pkg $Python $saida $cfg 2>&1 }
finally { Pop-Location }
$m = [regex]::Match(($seed -join "`n"), 'SEMEADO (ex-[0-9a-f]{32}) pid=(\d+)')
Check "P: execução sintética com eventos do Framework em andamento (registro RUNNING; o Agent vai adotá-la)" $m.Success
if (-not $m.Success) { exit 2 }
$id = $m.Groups[1].Value
$procPid = [int]$m.Groups[2].Value
$runDir = Join-Path $data "execucoes\$id"
$estado = Join-Path $runDir 'estado.json'
$atividade = Join-Path $runDir 'atividade.jsonl'
$fwRaw = Join-Path $saida "$id\execucao\events.jsonl"

function Start-Agent {
    $env0 = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME; DATA = $env:NEKKORA_AGENT_DATA_DIR; HOST = $env:COMPUTERNAME }
    $env:NEKKORA_AGENT_DATA_DIR = $data; $env:PATH = 'C:\Windows\System32;C:\Windows'; $env:COMPUTERNAME = 'LAB-AGENT-01'
    Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    try { $p = Start-Process -FilePath $exe -PassThru; $null = $p.Handle }
    finally {
        $env:PATH = $env0.PATH; $env:COMPUTERNAME = $env0.HOST
        if ($env0.JAVA_HOME) { $env:JAVA_HOME = $env0.JAVA_HOME }
        if ($env0.DATA) { $env:NEKKORA_AGENT_DATA_DIR = $env0.DATA } else { Remove-Item Env:NEKKORA_AGENT_DATA_DIR }
    }
    return $p
}
function Agent-Processes { return @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe }) }
function Life-Starts { $t = ''; Get-ChildItem (Join-Path $data 'logs') -File -ErrorAction SilentlyContinue | ForEach-Object { $t += Read-Shared $_.FullName }; return ([regex]::Matches($t, 'LIFE_START')).Count }
# processos da execução (o python.exe do venv é um lançador: 2 processos com a mesma linha de comando)
function Run-Processes { return @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine -like '*--output*' -and $_.CommandLine -like "*$id*" }) }
$script:runPids = @()
function New-RunProcesses { return @(Run-Processes | Where-Object { $_.ProcessId -notin $script:runPids }) }
$pacoteEstados = Join-Path $raw 'estados-pacote.jsonl'
function Snapshot-Package([string]$Moment, [bool]$Tray) {
    $st = Read-Shared $estado
    $lines = @(Lines $atividade)
    $procs = @(Agent-Processes)
    $icons = -1  # não medido (tela bloqueada)
    if ($Tray) {
        # o ESC da contagem vai para a janela em foco: tem de ser a do Agent empacotado, nunca a de um cenário
        if ($script:w) { [NkUser32]::ForceForeground($script:w.Handle) | Out-Null; Start-Sleep -Milliseconds 300 }
        $icons = Get-TrayIconCount
        # a contagem fecha o estouro com ESC, que também fecha a janela em foco: a aba Atividade é reaberta
        if ($script:w -and -not (Test-WindowShown (Get-AgentWindow $script:w.Jvm 'Execução local'))) { $null = Open-Activity $script:w.Jvm $script:w.Handle }
    }
    $o = [ordered]@{
        momento = $Moment; quando_utc = (Get-Date).ToUniversalTime().ToString('o'); execucao = $id
        estado = Field $st 'state'; codigo = Field $st 'code'; bloqueios = [int](Field $st 'locks'); suspensoes = [int](Field $st 'suspensions')
        adotada = ($st -match '"adopted": true'); processo_da_execucao_vivo = [bool](Get-Process -Id $procPid -ErrorAction SilentlyContinue)
        processos_da_execucao = @(Run-Processes).Count; processos_novos_da_execucao = @(New-RunProcesses).Count
        processos_do_agent = $procs.Count; pids_do_agent = (@($procs.ProcessId | Sort-Object) -join ','); icones_na_bandeja = $icons
        life_start = Life-Starts; eventos_atividade = $lines.Count
        eventos_framework = @($lines | Where-Object { $_ -match '"source":"FRAMEWORK"' }).Count; sessao_bloqueada = Session-Locked
    }
    ($o | ConvertTo-Json -Compress) | Add-Content -Encoding UTF8 $pacoteEstados
    Note ("P [{0}] estado {1} · Agent {2} processo(s) [{3}] · ícones {4} · LIFE_START {5} · atividade {6} ({7} do Framework)" -f $Moment, $o.estado,
        $o.processos_do_agent, $o.pids_do_agent, $(if ($icons -lt 0) { 'não medido' } else { $icons }), $o.life_start, $o.eventos_atividade, $o.eventos_framework)
    return $o
}
function Open-Activity($jvm, $handle) {
    [NkUser32]::ForceForeground($handle) | Out-Null
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    $dlg = Wait-Until { $h = Get-AgentWindow $jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
    Start-Sleep -Milliseconds 600
    [NkUser32]::ForceForeground($dlg) | Out-Null
    [System.Windows.Forms.SendKeys]::SendWait('%t')
    Start-Sleep -Milliseconds 1200
    return $dlg
}
function Capture-Package([string]$Name) {
    # a captura copia os pixels da tela no retângulo da janela: ela vem para a frente antes, para nada por cima entrar
    $h = Get-AgentWindow $script:jvm 'Execução local'
    if (Test-WindowShown $h) { [NkUser32]::ForceForeground($h) | Out-Null; Start-Sleep -Milliseconds 400 }
    try { & $wincap -ProcessId $script:jvm -Out (Join-Path $raw "$Name.png") -TitleLike 'Execução local' | Out-Null } catch { Note "captura $Name indisponível: $($_.Exception.Message)" }
}

$launcher = Start-Agent
$script:w = $null
$w = Wait-Until {
    foreach ($q in Agent-Processes) { $h = Get-AgentWindow $q.ProcessId; if (Test-WindowShown $h) { return [pscustomobject]@{ Jvm = [int]$q.ProcessId; Handle = $h } } }
} 40 'janela do Agent empacotado'
$script:jvm = $w.Jvm
$script:w = $w
Check "P: Agent empacotado abriu sem Java no PATH" ($null -ne $w)
$script:runPids = @(Run-Processes | ForEach-Object { $_.ProcessId })
$adopted = Wait-Ok { (Read-Shared $estado) -match '"adopted": true' } 20 'adoção'
Check ("P: execução ADOTADA, processo vivo (PIDs da execução: {0}), nada relançado" -f ($script:runPids -join ',')) `
    ([bool]$adopted -and [bool](Get-Process -Id $procPid -ErrorAction SilentlyContinue) -and $procPid -in $script:runPids -and @(New-RunProcesses).Count -eq 0)
$dlg = Open-Activity $w.Jvm $w.Handle
Check "P: aba Atividade aberta pelo teclado (Alt+E, Alt+T)" ($dlg -ne [IntPtr]::Zero)
$antes = Snapshot-Package 'antes' $true
Check "P antes: 2 processos do Agent (lançador + JVM), 1 ícone na bandeja, 1 LIFE_START" ($antes.processos_do_agent -eq 2 -and $antes.icones_na_bandeja -eq 1 -and $antes.life_start -eq 1)
Capture-Package 'P-1-antes'

# ---------- 2. cenários sintéticos lançados pelo botão Iniciar ----------
if (-not $Ensaio -and -not $SemPausa) {
    Write-Host ""
    Write-Host ">>> Tudo pronto. Ao pressionar Enter, três execuções sintéticas começam (~6 min de máquina acordada cada)."
    Write-Host ">>> Logo depois o roteiro vai pedir: bloquear/desbloquear a tela e suspender/retomar o Windows."
    [void](Read-Host ">>> Pressione Enter para iniciar")
}
$scen = @('sucesso', 'sem_protocolo', 'falha')
$script:jobs = @{}
$jobs = $script:jobs
foreach ($c in $scen) {
    $dir = Join-Path $raw "cenario-$c"
    New-Item -ItemType Directory -Force $dir | Out-Null
    $jargs = @('-cp', $TestClasspath, 'com.nekkora.agent.ui.DesktopHomologation', 'sessao-cenario', $dir, $c, $Python, (Join-Path $dir 'saida'))
    if ($Ensaio) { $jargs += 'ensaio' }
    $jobs[$c] = Start-Process -FilePath 'java' -ArgumentList $jargs -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $dir 'stdout.txt') -RedirectStandardError (Join-Path $dir 'stderr.txt')
    $null = $jobs[$c].Handle  # sem o handle, o PowerShell 5.1 não guarda o código de saída
}
foreach ($c in $scen) {
    $f = Join-Path $raw "cenario-$c\fase.txt"
    $ok = Wait-Ok { (Test-Path $f) -and ((Read-Shared $f) -like 'EM_ANDAMENTO*') } 180 "cenário $c em andamento"
    Check "S ${c}: execução lançada pelo botão Iniciar e em andamento" ([bool]$ok)
}
Snapshot-Package 'execucoes-iniciadas' $true | Out-Null

if (-not $Ensaio) {
    Write-Host ""
    Write-Host "======================================================================================="
    Write-Host ">>> AGORA, nesta ordem:"
    Write-Host ">>>   1. Bloqueie a tela (Windows+L). Espere ~10 s. Desbloqueie."
    Write-Host ">>>   2. Iniciar > Ligar/Desligar > Suspender. Espere PELO MENOS 1 minuto. Retome e desbloqueie."
    Write-Host ">>>   3. Não use o computador até o roteiro terminar (ele usa teclas e o foco das janelas)."
    Write-Host ">>> Nada aqui bloqueia, suspende ou desbloqueia a máquina por você."
    Write-Host "======================================================================================="
}

# ---------- 3. acompanhamento: durante o bloqueio, depois do desbloqueio, depois da retomada ----------
$sw = [Diagnostics.Stopwatch]::StartNew()
$lastWall = Get-Date
$lastTick = $sw.ElapsedMilliseconds
$wasLocked = $false
$seen = @{ lock = $false; unlock = $false; resume = $false }
$deadline = (Get-Date).AddMinutes(40)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 1000
    $now = Get-Date
    $tick = $sw.ElapsedMilliseconds
    # salto do relógio de parede maior que o tempo acordado: a máquina dormiu (o cronômetro não anda na suspensão)
    $jump = (($now - $lastWall).TotalMilliseconds) - ($tick - $lastTick)
    $lastWall = $now; $lastTick = $tick
    $locked = Session-Locked
    if ($locked -and -not $seen.lock) { $seen.lock = $true; Snapshot-Package 'durante-bloqueio' $false | Out-Null }
    if ($wasLocked -and -not $locked -and -not $seen.unlock) { $seen.unlock = $true; Start-Sleep -Seconds 2; Snapshot-Package 'depois-do-desbloqueio' $false | Out-Null }
    if ($jump -gt 20000 -and -not $seen.resume) {
        $seen.resume = $true
        Note ("suspensão observada pelo roteiro: ~{0:N0} s parada" -f ($jump / 1000))
        Snapshot-Package 'logo-apos-retomar' $false | Out-Null
    }
    $wasLocked = $locked
    $doneS = @($scen | Where-Object { $f = Join-Path $raw "cenario-$_\fase.txt"; (Test-Path $f) -and ((Read-Shared $f) -like 'FIM*') -or $jobs[$_].HasExited }).Count
    $st = Field (Read-Shared $estado) 'state'
    if ($doneS -eq $scen.Count -and $st -and $st -ne 'RUNNING' -and $st -ne 'STARTING' -and -not $locked) { break }
}
if (-not $Ensaio) {
    Check "o roteiro observou o bloqueio da tela" $seen.lock
    Check "o roteiro observou a suspensão (salto do relógio)" $seen.resume
}
foreach ($c in $scen) { $jobs[$c].WaitForExit(120000) | Out-Null }

# ---------- 4. resultados dos cenários ----------
foreach ($c in $scen) {
    $dir = Join-Path $raw "cenario-$c"
    $log = @(Lines (Join-Path $dir 'homologacao-log.txt'))
    $res = $log | Where-Object { $_ -like 'RESULTADO:*' } | Select-Object -Last 1
    Check ("S ${c}: roteiro da janela real sem falhas ($res)") ($jobs[$c].ExitCode -eq 0)
    foreach ($l in $log | Where-Object { $_ -like 'OK*' -or $_ -like 'FALHA*' -or $_ -like '*NÃO EXERCIDO*' }) { Note ("   [$c] " + $l.Trim()) }
}

# ---------- 5. A13 + OBS-G6 no Agent empacotado ----------
$done = Wait-Ok { (Field (Read-Shared $estado) 'state') -notin @('RUNNING', 'STARTING') } 600 'fim da execução adotada'
Wait-Ok { (Lines $atividade | Select-Object -Last 1) -match '"event_code":"COLLECTION_COMPLETED"' } 60 'coleta encerrada' | Out-Null
$dlg = Get-AgentWindow $w.Jvm 'Execução local'
if (Test-WindowShown $dlg) { [NkUser32]::ForceForeground($dlg) | Out-Null; Start-Sleep -Milliseconds 800 }
$depois = Snapshot-Package 'depois' $true
Capture-Package 'P-2-depois'
Check ("P A13: mesmos processos do Agent antes e depois ([{0}] = [{1}])" -f $antes.pids_do_agent, $depois.pids_do_agent) ($antes.pids_do_agent -eq $depois.pids_do_agent -and $depois.processos_do_agent -eq 2)
Check ("P A13: um único ícone na bandeja depois da retomada ({0})" -f $depois.icones_na_bandeja) ($depois.icones_na_bandeja -eq 1)
Check ("P A13: nenhum novo LIFE_START ({0})" -f $depois.life_start) ($depois.life_start -eq 1)
# segunda abertura depois da retomada: recusada com aviso, nada muda
$second = Start-Agent
$script:notice = $null
$noticeShown = Wait-Ok {
    foreach ($q in Agent-Processes) { if ($q.ProcessId -notin @($antes.pids_do_agent -split ',') -and (Test-WindowShown (Get-AgentWindow $q.ProcessId 'O Nekkora Agent já está aberto'))) { $script:notice = $q; return $true } }
} 40 'aviso da segunda abertura'
if ($script:notice) {
    [NkUser32]::ForceForeground((Get-AgentWindow $script:notice.ProcessId 'O Nekkora Agent já está aberto')) | Out-Null; Start-Sleep -Milliseconds 400
    try { & $wincap -ProcessId $script:notice.ProcessId -Out (Join-Path $raw 'P-3-segunda-abertura-recusada.png') -TitleLike 'O Nekkora Agent*' | Out-Null } catch { }
    Close-Window (Get-AgentWindow $script:notice.ProcessId 'O Nekkora Agent já está aberto')
}
$second.WaitForExit(20000) | Out-Null
Start-Sleep -Seconds 1
$after2 = Snapshot-Package 'depois-da-segunda-abertura' $true
$dlg = Get-AgentWindow $w.Jvm 'Execução local'
Check ("P A13: segunda abertura mostrou o aviso e saiu (código {0}); continuam os mesmos 2 processos, 1 ícone, 1 LIFE_START" -f $second.ExitCode) `
    ([bool]$noticeShown -and $second.HasExited -and $after2.pids_do_agent -eq $antes.pids_do_agent -and $after2.icones_na_bandeja -eq 1 -and $after2.life_start -eq 1)
$st = Read-Shared $estado
Check ("P: execução adotada terminou pelo protocolo: {0}" -f (Field $st 'state')) ((Field $st 'state') -eq 'SUCCEEDED')
$lines = @(Lines $atividade)
$seqs = @($lines | ForEach-Object { [long]([regex]::Match($_, '"sequence":(\d+)').Groups[1].Value) })
$contiguous = $true
for ($i = 0; $i -lt $seqs.Count; $i++) { if ($seqs[$i] -ne $i + 1) { $contiguous = $false } }
$fwPos = @($lines | Where-Object { $_ -match '"source":"FRAMEWORK"' } | ForEach-Object { [regex]::Match($_, '"source_position":(\d+)').Groups[1].Value })
$rawCount = @(Lines $fwRaw).Count
Check ("P OBS-G6: sequência contínua 1..{0}; Framework {1} eventos = {2} linhas originais, sem repetição" -f $seqs.Count, $fwPos.Count, $rawCount) `
    ($contiguous -and $fwPos.Count -eq $rawCount -and (@($fwPos | Select-Object -Unique)).Count -eq $fwPos.Count)
$text = $lines -join "`n"
$novos = @($pacoteEstados | ForEach-Object { Lines $_ } | Where-Object { $_ -notmatch '"processos_novos_da_execucao":0' }).Count
Check ("P: ENGINE_STARTED uma única vez, nenhum processo novo da execução em nenhum instantâneo ({0}) e nenhum restante" -f $novos) `
    (([regex]::Matches($text, '"event_code":"ENGINE_STARTED"')).Count -eq 1 -and $novos -eq 0 -and @(Run-Processes).Count -eq 0)
Check "P OBS-G6: nenhum EVENT_GAP, EVENT_INVALID ou origem interrompida (linha do tempo completa)" ($text -notmatch '"event_code":"(EVENT_GAP|EVENT_INVALID|SOURCE_REPLACED|SOURCE_UNSAFE)"')
if (-not $Ensaio) {
    Check "P OBS-G6: SESSION_LOCKED, SESSION_UNLOCKED e SUSPENSION_DETECTED na linha do tempo do Agent empacotado" `
        ($text -match '"event_code":"SESSION_LOCKED"' -and $text -match '"event_code":"SESSION_UNLOCKED"' -and $text -match '"event_code":"SUSPENSION_DETECTED"')
}

# sair do Agent empacotado
if (Test-WindowShown $dlg) { [NkUser32]::ForceForeground($dlg) | Out-Null; [System.Windows.Forms.SendKeys]::SendWait('{ESC}'); Start-Sleep -Milliseconds 800 }
[NkUser32]::ForceForeground($w.Handle) | Out-Null
[System.Windows.Forms.SendKeys]::SendWait('%s')
$gone = Wait-Ok { -not (Get-Process -Id $w.Jvm -ErrorAction SilentlyContinue) } 40 'Agent saiu'
Check "P: Agent empacotado saiu (nenhuma execução ativa)" ([bool]$gone)

# ---------- 6. evidências sanitizadas ----------
Copy-Item $pacoteEstados (Join-Path $ev 'P-estados.jsonl')
Copy-Item $estado (Join-Path $ev 'P-estado-execucao.json')
Copy-Item (Join-Path $runDir 'eventos.jsonl') (Join-Path $ev 'P-eventos-execucao.jsonl')
Copy-Item $atividade (Join-Path $ev 'P-atividade-execucao.jsonl')
Get-ChildItem $raw -Filter 'P-*.png' | Copy-Item -Destination $ev
foreach ($c in $scen) {
    $dir = Join-Path $raw "cenario-$c"
    foreach ($f in 'homologacao-log.txt', 'estados.jsonl', 'estado-execucao.json', 'eventos-execucao.jsonl', 'atividade-exportada.jsonl') {
        if (Test-Path (Join-Path $dir $f)) { Copy-Item (Join-Path $dir $f) (Join-Path $ev "S-$c-$f") }
    }
    if (Test-Path (Join-Path $dir 'capturas')) { Get-ChildItem (Join-Path $dir 'capturas') -Filter '*.png' | ForEach-Object { Copy-Item $_.FullName (Join-Path $ev ("S-" + $_.Name)) } }
}
# nada que identifique a pessoa, a máquina ou pastas locais nos textos
$secrets = @($env:USERNAME, $env:COMPUTERNAME, $env:USERPROFILE, $Out, $root, ([IO.Path]::GetTempPath().TrimEnd('\'))) | Where-Object { $_ -and $_.Length -ge 3 } | Sort-Object Length -Descending
$redacted = 0
foreach ($f in Get-ChildItem $ev -File | Where-Object { $_.Extension -in '.txt', '.json', '.jsonl' }) {
    $t = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
    $t2 = $t
    foreach ($s in $secrets) {
        foreach ($v in @($s, $s.Replace('\', '\\'), $s.Replace('\', '/'))) { $t2 = [regex]::Replace($t2, [regex]::Escape($v), '<local>', 'IgnoreCase') }
    }
    $t2 = [regex]::Replace($t2, '[A-Za-z]:(\\\\|\\|/)[^"\s,]*', '<caminho>')
    if ($t2 -ne $t) { $redacted++; [IO.File]::WriteAllText($f.FullName, $t2, (New-Object Text.UTF8Encoding($false))) }
}
$leaks = @(Get-ChildItem $ev -File | Where-Object { $_.Extension -in '.txt', '.json', '.jsonl' } | Where-Object {
        $t = [IO.File]::ReadAllText($_.FullName); @($secrets | Where-Object { $t.IndexOf($_, [StringComparison]::OrdinalIgnoreCase) -ge 0 }).Count -gt 0 })
Note ("sanitização: {0} arquivo(s) com trechos substituídos por <local>/<caminho>" -f $redacted)
Check "evidências sem usuário, máquina ou caminhos locais" ($leaks.Count -eq 0)

$results.Add(("RESULTADO: {0} verificações, {1} falha(s){2}" -f @($results | Where-Object { $_ -like 'OK*' -or $_ -like 'FALHA*' }).Count, $fails,
        $(if ($Ensaio) { ' — ENSAIO: bloqueio/suspensão NÃO EXERCIDOS' } else { '' })))
$results | Set-Content -Encoding UTF8 (Join-Path $ev 'resultado.txt')
Write-Host ("RESULTADO: {0} falha(s) · evidências sanitizadas em {1}" -f $fails, $ev)
exit $(if ($fails -eq 0) { 0 } else { 2 })
