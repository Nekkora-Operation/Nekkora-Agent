<#
  PLATFORM-AUTOMATION-LIFECYCLE-001, L4/L6 — Agent empacotado REAL executa o pacote GENUÍNO do Studio cuja versão foi
  PUBLICADA pela API (tools/platform-lab/ciclo.py preparar), sem fixture de publicação. Pré-condição: laboratório com
  LAB_REDE=50 (armazenamento de artefatos), ciclo.py preparar feito, Studio FECHADO.
    L1 [REAL] pareamento pela janela; uma autorização (a da versão publicada) baixada
    L2 [REAL] pacote do Studio aprovado pela pessoa; a autorização vem pré-escolhida; execução iniciada
    L3 [INJETADA] nginx pausado durante a execução: a automação segue, nada é relançado; a entrega retoma
    L4 [REAL] SUCCEEDED; entrega concluída; consulta da Platform com versão publicada, autorização, Worker, Agent, execução,
       supervisor e completude
    L5 [REAL] segunda execução: Agent e automação encerrados à força -> OUTCOME_UNKNOWN sem relançar, na Platform também
    L6 nenhum segredo (código, senha, fator) em evidências, dados do Agent e logs dos serviços
  Uso: powershell -File tools/platform-lab/jornada-ciclo.ps1 -Id agentlab-004 -Python <python do pacote> -Package <pasta> -InputFile <lista>
#>
param(
    [string]$Id = 'agentlab-004',
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$Package,
    [Parameter(Mandatory = $true)][string]$InputFile,
    [int]$TimeoutSeconds = 900
)
$ErrorActionPreference = 'Continue'
Set-StrictMode -Version 3.0
$Prefixo = 'ciclo-'
. (Join-Path $PSScriptRoot 'LabComum.ps1')
New-Item -ItemType Directory -Force (Join-Path $Lab 'saida-automacao') | Out-Null
$ids = Get-Content -Raw (Join-Path $Lab 'ciclo-preparacao.json') | ConvertFrom-Json
$p = Get-Content -Raw (Join-Path $Seg 'pessoa.json') | ConvertFrom-Json

function Exec-Dir([string]$ExecId) { return Join-Path $script:data "execucoes\$ExecId" }
function Run-State([string]$ExecId) { return (Get-Content -Raw (Join-Path (Exec-Dir $ExecId) 'estado.json') | ConvertFrom-Json) }
function Wait-End([string]$ExecId, [int]$Seconds) {
    Wait-Until { $s = (Run-State $ExecId).state; $s -ne 'RUNNING' -and $s -ne 'STARTING' -and $s -ne 'READY' } $Seconds "fim da execução $ExecId" | Out-Null
}
function Exec-Pythons([string]$ExecId) {
    return @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine -like "*$ExecId*" })
}
function Db-Run([string]$ExecId) {
    return Sql ("SELECT supervisor_state || '|' || stored_through || '|' || settled_through || '|' || " +
            "(SELECT count(*) FROM agent_run_events e WHERE e.agent_run_id = r.agent_run_id) || '|' || " +
            "(SELECT count(*) FROM agent_ingestion_refusals f WHERE f.agent_run_id = r.agent_run_id) || '|' || " +
            "authorization_id || '|' || automation_version_id || '|' || worker_id || '|' || agent_id FROM agent_runs r WHERE agent_execution_id = '$ExecId'")
}
function Kill-Agent {
    Stop-Process -Id $script:jvm -Force
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) { Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue }
}
function Consultar([string]$ExecId, [string]$Name) {
    $f = Join-Path $Out "$Name.json"
    & python -I (Join-Path $PSScriptRoot 'ciclo.py') consultar $Id $ExecId 2>$null | Set-Content -Encoding UTF8 $f
    return (Get-Content -Raw $f | ConvertFrom-Json)
}
function Start-Real([string]$Shot, [switch]$Approve) {
    $before = @(Get-ChildItem (Join-Path $script:data 'execucoes') -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
    $started0 = Log-Count 'EXEC_STARTED'; $insp0 = Log-Count 'EXEC_PACKAGE_INSPECTED'; $py0 = Log-Count 'EXEC_PYTHON_CHECKED'
    Focus $script:w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    $dlg = Wait-Until { $h = Get-AgentWindow $script:jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
    Start-Sleep -Milliseconds 800
    Choose-Path $dlg '%p' 'Pasta do pacote da automação' $Package
    Wait-Until { (Log-Count 'EXEC_PACKAGE_INSPECTED') -gt $insp0 } 30 'pacote inspecionado' | Out-Null
    Start-Sleep -Milliseconds 800
    if ($Approve) {
        Capture "$Shot-antes-de-aprovar" 'Execução local'
        $approved0 = Log-Count 'EXEC_PACKAGE_APPROVED'
        $pos = Find-Button $dlg @(@(109, 60, 82), @(118, 71, 92)) 185 330 450 1000
        if (-not $pos) { throw 'botão Aprovar este pacote… não encontrado' }
        Click-In $dlg $pos[0] $pos[1]
        $confirm = Wait-Until { $h = Get-AgentWindow $script:jvm 'Aprovar este pacote?'; if (Test-WindowShown $h) { $h } } 20 'confirmação da aprovação'
        Start-Sleep -Milliseconds 500
        $pos = Find-Button $confirm @(, @(240, 163, 184)) 200 420 120 400 -FromRight
        if (-not $pos) { throw 'botão Aprovar da confirmação não encontrado' }
        Click-In $confirm $pos[0] $pos[1]
        Wait-Until { (Log-Count 'EXEC_PACKAGE_APPROVED') -gt $approved0 } 20 'aprovação registrada' | Out-Null
    }
    Choose-Path $dlg '%y' 'Python do ambiente do projeto' $Python
    Wait-Until { (Log-Count 'EXEC_PYTHON_CHECKED') -gt $py0 } 60 'Python conferido' | Out-Null
    Choose-Path $dlg '%e' 'Entrada da automação' $InputFile
    Start-Sleep -Seconds 1
    Choose-Path $dlg '%a' 'Pasta de saída (uma subpasta por execução)' (Join-Path $Lab 'saida-automacao')
    Start-Sleep -Seconds 1
    Capture "$Shot-pronto-com-autorizacao" 'Execução local'
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('%i')
    Wait-Until { (Log-Count 'EXEC_STARTED') -gt $started0 } 30 'execução iniciada' | Out-Null
    $d = Wait-Until { Get-ChildItem (Join-Path $script:data 'execucoes') -Directory | Where-Object { $before -notcontains $_.Name } | Select-Object -First 1 } 20 'pasta da execução'
    Start-Sleep -Seconds 1
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
    return $d.Name
}

$script:w = Start-Agent
Note '[REAL]' "L1 Agent empacotado aberto (JVM $($script:jvm)); Studio fechado: $(-not (Get-Process -Name 'Nekkora Studio*' -ErrorAction SilentlyContinue))"
$code = $null
try {
    # ---------- L1: pareamento ----------
    $codeFile = Join-Path $Seg 'codigo-pareamento'
    $code = ([IO.File]::ReadAllText($codeFile)).Trim()
    Focus $script:w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%a')
    $pd = Wait-Until { $h = Get-AgentWindow $script:jvm 'Parear com a Platform'; if (Test-WindowShown $h) { $h } } 20 'diálogo de pareamento (Alt+A)'
    Focus $pd
    Start-Sleep -Milliseconds 500
    Send-Text $code
    Start-Sleep -Milliseconds 300
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    Remove-Item -Force $codeFile
    Wait-Until { (Log-Count 'PLATFORM_PAIRED') -ge 1 } 60 'pareamento confirmado' | Out-Null
    $vinculo = Get-Content -Raw (Join-Path $script:data 'plataforma\vinculo.json') | ConvertFrom-Json
    Wait-Until { (Log-Count 'PLATFORM_AUTHORIZATIONS count=1') -ge 1 } 60 'autorização baixada' | Out-Null
    $authFile = Get-Content -Raw (Join-Path $script:data 'plataforma\autorizacoes.json')
    Check "L1 pareado; a autorização baixada é a da versão PUBLICADA pela API com a impressão do pacote ($($ids.impressao_autorizada.Substring(0, 12))…)" ($authFile.Contains($ids.autorizacao) -and $authFile.Contains($ids.impressao_autorizada) -and $authFile.Contains($ids.versao_publicada))

    # ---------- L2/L3: execução real com a Platform fora por um tempo ----------
    $run1 = Start-Real 'l2' -Approve
    Note '[REAL]' "L2 execução $run1 iniciada (pacote do Studio aprovado; autorização pré-escolhida)"
    Check 'L2 execução vinculada à autorização da versão publicada (PLATFORM_RUN_LINKED)' ((Log-Count "PLATFORM_RUN_LINKED execution=$run1") -ge 1)
    Wait-Until { (Run-State $run1).state -eq 'RUNNING' } 120 'RUNNING' | Out-Null
    Wait-Until { (Log-Count "PLATFORM_ACK execution=$run1") -ge 1 } 120 'primeiro ACK' | Out-Null
    $py0 = @(Exec-Pythons $run1).Count
    $null = Dc @('pause', 'nginx')
    Note '[INJETADA]' 'L3 nginx do laboratório PAUSADO durante a execução'
    Start-Sleep -Seconds 40
    $st = (Run-State $run1).state
    $py1 = @(Exec-Pythons $run1).Count
    $null = Dc @('unpause', 'nginx')
    Note '[INJETADA]' 'L3 nginx de volta'
    Check "L3 sem Platform a automação seguiu ($st) e nada foi relançado (processos da execução: $py0 -> $py1)" (($st -eq 'RUNNING' -or $st -eq 'SUCCEEDED') -and $py1 -le $py0)

    # ---------- L4: desfecho, entrega e consulta ----------
    Wait-End $run1 $TimeoutSeconds
    $s1 = Run-State $run1
    Capture 'l4-fim'
    Wait-Until { (Log-Count "PLATFORM_DELIVERY_COMPLETED execution=$run1") -ge 1 } 600 'envio concluído' | Out-Null
    $db = (Db-Run $run1) -split '\|'
    $outs = @(Get-ChildItem (Join-Path $Lab 'saida-automacao') -Directory | Where-Object { $_.Name -eq $run1 }).Count
    Check "L4 execução SUCCEEDED ($($s1.state)), uma pasta de saída ($outs) e um registro na Platform" ($s1.state -eq 'SUCCEEDED' -and $outs -eq 1 -and (Sql "SELECT count(*) FROM agent_runs WHERE agent_execution_id = '$run1'") -eq '1')
    Check "L4 banco: estado=$($db[0]) stored=$($db[1]) settled=$($db[2]) eventos=$($db[3]) recusas=$($db[4]); versão e autorização da publicação" ($db[0] -eq '4' -and $db[1] -eq $db[2] -and $db[1] -eq $db[3] -and $db[4] -eq '0' -and $db[5] -eq $ids.autorizacao -and $db[6] -eq $ids.versao_publicada)
    $q1 = Consultar $run1 'l4-consulta-platform'
    $c1 = $q1.detalhe.correlation
    Check ("L4 consulta autenticada: versão publicada, autorização, Worker, Agent e execução local; supervisor SUCCEEDED/KNOWN; histórico " + $q1.detalhe.history.completeness) ($c1.automationVersionId -eq $ids.versao_publicada -and $c1.authorizationId -eq $ids.autorizacao -and $c1.workerId -eq $ids.worker -and $c1.agentId -eq $vinculo.agentId -and $c1.agentExecutionId -eq $run1 -and $q1.detalhe.supervisor.state -eq 'SUCCEEDED' -and $q1.detalhe.outcome -eq 'KNOWN' -and $q1.detalhe.history.completeness -eq 'COMPLETE')

    # ---------- L5: OUTCOME_UNKNOWN sem relançar ----------
    $run2 = Start-Real 'l5'
    Wait-Until { (Run-State $run2).state -eq 'RUNNING' } 120 'RUNNING (2)' | Out-Null
    Wait-Until { (Log-Count "PLATFORM_ACK execution=$run2") -ge 1 } 120 'primeiro ACK (2)' | Out-Null
    $pids = @(Exec-Pythons $run2 | ForEach-Object { $_.ProcessId })
    Kill-Agent
    Start-Sleep -Seconds 2
    foreach ($x in $pids) { Stop-Process -Id $x -Force -ErrorAction SilentlyContinue }
    Note '[REAL]' "L5 Agent e automação encerrados à força ($($pids.Count) processos)"
    Start-Sleep -Seconds 3
    $script:w = Start-Agent
    Wait-Until { (Run-State $run2).state -eq 'OUTCOME_UNKNOWN' } 120 'OUTCOME_UNKNOWN' | Out-Null
    Wait-Until { (Log-Count "PLATFORM_DELIVERY_COMPLETED execution=$run2") -ge 1 } 600 'envio concluído (2)' | Out-Null
    $relaunch = @(Exec-Pythons $run2).Count
    $q2 = Consultar $run2 'l5-consulta-platform'
    Check "L5 OUTCOME_UNKNOWN sem relançar ($relaunch processos novos, $((Sql "SELECT count(*) FROM agent_runs WHERE agent_execution_id = '$run2'")) registro); Platform $($q2.detalhe.supervisor.state)/$($q2.detalhe.outcome)" ((Run-State $run2).state -eq 'OUTCOME_UNKNOWN' -and $relaunch -eq 0 -and $q2.detalhe.supervisor.state -eq 'OUTCOME_UNKNOWN' -and $q2.detalhe.outcome -eq 'UNKNOWN')
    Check 'L5 publicação e autorização não geraram execução por conta própria: só as 2 iniciadas pela pessoa' ((Sql "SELECT count(*) FROM agent_runs WHERE organization_id = '$($ids.organizacao)'") -eq '2')
} catch {
    Check "jornada interrompida por exceção: $($_.Exception.Message)" $false
} finally {
    if ($script:jvm) { Kill-Agent }
    $null = Dc @('unpause', 'nginx')
}

# ---------- L6: segredos ----------
$secretsList = @($p.senha, $p.totp) + $(if ($code) { @($code) } else { @() })
$logs = Join-Path $Out 'logs-servicos.txt'
$old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& docker @composeArgs logs --no-color 2>$null | Set-Content -Encoding UTF8 $logs
$ErrorActionPreference = $old
$hits = 0
foreach ($f in @(Get-ChildItem -Recurse -File $Out) + @(Get-ChildItem -Recurse -File $script:data -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike 'credencial*' })) {
    $t = [IO.File]::ReadAllText($f.FullName)
    foreach ($s in $secretsList) { if ($s -and $t.Contains($s)) { $hits++ } }
}
Remove-Item -Force $logs
Check "L6 nenhum segredo ($($secretsList.Count) valores) em evidências, dados do Agent e logs dos serviços" ($hits -eq 0)
$ok = @($checks | Where-Object { $_.ok }).Count
$checks | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $Out 'resultado.json')
$timeline | Set-Content -Encoding UTF8 (Join-Path $Out 'linha-do-tempo.txt')
Write-Host ("JORNADA DO CICLO DE VIDA: {0}/{1} conferências OK - {2}" -f $ok, $checks.Count, $Out)
