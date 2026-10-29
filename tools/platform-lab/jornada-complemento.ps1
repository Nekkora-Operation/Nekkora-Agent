<#
.SYNOPSIS
  AGENT-PLATFORM-002 — jornada COMPLEMENTAR (PA-05) do Agent EMPACOTADO com a API REAL, num laboratório descartável próprio.

.DESCRIPTION
  Fecha o que a jornada da AGENT-PLATFORM-001 não provou com processos reais. Rótulos: [REAL] = Agent empacotado, automação
  e API reais; [INJETADA] = falha controlada e identificada (serviço do laboratório parado ou arquivo travado por outro
  programa). Nada aqui usa transporte simulado.

  C1  Agent empacotado abre e é pareado pela janela (código digitado no campo de senha; arquivo apagado).
  C2  [REAL] Presença: o heartbeat avança no banco (agent_presence.reported_at) entre dois ciclos.
  C3  [REAL] Início offline: com o nginx pausado (sem Platform), a execução autorizada começa pela autorização GUARDADA;
      nada é registrado enquanto isso; com a volta, o registro e a entrega acontecem.
  C4  [INJETADA] 503 real: o Redis do laboratório é PARADO; o limitador do canal falha fechado (503 + Retry-After, sem ACK);
      o Agent registra AGT-PLT-011 e espera; a automação segue; com o Redis de volta a entrega retoma.
  C5  [INJETADA] Perda local definitiva: outro programa abre o atividade.jsonl SEM compartilhar a escrita até a coleta
      esgotar as tentativas; a coleta registra a lacuna (EVENT_GAP AGT-OBS-009) e o transporte DECLARA a faixa exata
      (declaredMissing); a Platform mostra INCOMPLETE/LOST_LOCAL, storedThrough < settledThrough.
  C6  [REAL] OUTCOME_UNKNOWN com resultado parcial: o Agent é encerrado à força e a automação termina enquanto ele está
      fora; reaberto, a reconciliação fecha OUTCOME_UNKNOWN (nada relançado) e a Platform mostra UNKNOWN com as contagens.
  C7  [REAL] Rotação pela janela (Alt+N) com o código da pessoa: credencial anterior revogada (ROTATION) só depois da
      confirmação, presença continua com a nova, nenhuma recusa no canal.
  C8  [REAL] Limite do histórico: execução com mais de 20.000 eventos; a coleta registra AGT-OBS-005 e a Platform mostra
      INCOMPLETE/LOST_AT_SOURCE com storedThrough = settledThrough (perda na origem, nada declarado ausente).
  C9  Consulta da pessoa (correlação com a autorização e a versão, desfecho, completude) e varredura de segredos.
  Não use o computador durante a jornada (teclas e foco de janela).
#>
param(
    [string]$Id = 'agentlab-002',
    [Parameter(Mandatory = $true)][string]$Python
)
$Prefixo = 'complemento-'
. (Join-Path $PSScriptRoot 'LabComum.ps1')
$imp = Get-Content -Raw (Join-Path $Lab 'impressoes.json') | ConvertFrom-Json
$ids = Get-Content -Raw (Join-Path $Lab 'pessoa-preparacao.json') | ConvertFrom-Json
$authLimite = (Get-ChildItem (Join-Path $Lab 'pessoa-autorizacao-*.json') | Select-Object -First 1 | Get-Content -Raw | ConvertFrom-Json)

function Exec-Dir([string]$ExecId) { return Join-Path $script:data "execucoes\$ExecId" }
function Run-State([string]$ExecId) { return (Get-Content -Raw (Join-Path (Exec-Dir $ExecId) 'estado.json') | ConvertFrom-Json) }
function Approved([string]$Fp) { $f = Join-Path $script:data 'execucao\aprovacoes.json'; return ((Test-Path $f) -and (Get-Content -Raw $f).Contains($Fp)) }
function Ack-Count([string]$ExecId) { return Log-Count "PLATFORM_ACK execution=$ExecId" }
function Wait-End([string]$ExecId, [int]$Seconds) {
    Wait-Until { $s = (Run-State $ExecId).state; $s -ne 'RUNNING' -and $s -ne 'STARTING' -and $s -ne 'READY' } $Seconds "fim da execução $ExecId" | Out-Null
}
function Exec-Pythons([string]$ExecId) {
    return @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine -like "*$ExecId*" })
}
function Presence([string]$AgentId) {
    return Sql "SELECT to_char(reported_at, 'YYYY-MM-DD`"T`"HH24:MI:SS.US') FROM agent_presence WHERE agent_id = '$AgentId'"
}
function Db-Run([string]$ExecId) {
    $r = Sql ("SELECT supervisor_state || '|' || stored_through || '|' || settled_through || '|' || " +
            "(SELECT count(*) FROM agent_run_events e WHERE e.agent_run_id = r.agent_run_id) || '|' || " +
            "(SELECT count(*) FROM agent_ingestion_refusals f WHERE f.agent_run_id = r.agent_run_id) || '|' || " +
            "(SELECT coalesce(string_agg(m.seq_from || '-' || m.seq_to, ',' ORDER BY m.seq_from), '') FROM agent_run_missing m WHERE m.agent_run_id = r.agent_run_id) || '|' || " +
            "authorization_id || '|' || automation_version_id FROM agent_runs r WHERE agent_execution_id = '$ExecId'")
    $p = $r -split '\|'
    return [pscustomobject]@{ State = $p[0]; Stored = $p[1]; Settled = $p[2]; Events = $p[3]; Refusals = $p[4]; Missing = $p[5]; Authorization = $p[6]; Version = $p[7] }
}
function Local-Sequences([string]$ExecId) {
    $seqs = New-Object System.Collections.Generic.List[long]
    $gaps = New-Object System.Collections.Generic.List[string]
    foreach ($l in (Read-Shared (Join-Path (Exec-Dir $ExecId) 'atividade.jsonl')) -split "`n") {
        if ($l -match '"sequence":(\d+)') { $seqs.Add([long]$Matches[1]) }
        if ($l -match '"event_code":"EVENT_GAP"' -and $l -match '"error_code":"(AGT-OBS-\d+)"') { $gaps.Add($Matches[1]) }
    }
    $holes = New-Object System.Collections.Generic.List[string]
    for ($i = 1; $i -lt $seqs.Count; $i++) { if ($seqs[$i] -gt $seqs[$i - 1] + 1) { $holes.Add(('{0}-{1}' -f ($seqs[$i - 1] + 1), ($seqs[$i] - 1))) } }
    return [pscustomobject]@{ Last = $(if ($seqs.Count) { $seqs[$seqs.Count - 1] } else { 0 }); Count = $seqs.Count; Holes = ($holes -join ','); Gaps = ($gaps -join ',') }
}
function Consult([string]$ExecId, [string]$Name) {
    & python -I (Join-Path $PSScriptRoot 'pessoa.py') consultar $Id $ExecId | Out-File -Encoding utf8 (Join-Path $Out "$Name.json")
    return (Get-Content -Raw (Join-Path $Out "$Name.json") | ConvertFrom-Json)
}
function Gateway-Count([string]$Pattern) { return @(& docker @composeArgs logs --no-color agent-gateway 2>$null | Select-String $Pattern).Count }
function Start-Run([string]$Pkg, [string]$Shot) {
    $before = @(Get-ChildItem (Join-Path $script:data 'execucoes') -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
    $started0 = Log-Count 'EXEC_STARTED'; $insp0 = Log-Count 'EXEC_PACKAGE_INSPECTED'; $py0 = Log-Count 'EXEC_PYTHON_CHECKED'
    Focus $script:w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    $dlg = Wait-Until { $h = Get-AgentWindow $script:jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
    Start-Sleep -Milliseconds 800
    Choose-Path $dlg '%p' 'Pasta do pacote da automação' (Join-Path $Lab $Pkg)
    Wait-Until { (Log-Count 'EXEC_PACKAGE_INSPECTED') -gt $insp0 } 30 'pacote inspecionado' | Out-Null
    Start-Sleep -Milliseconds 800
    if (-not (Approved $imp.$Pkg)) {
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
    Choose-Path $dlg '%a' 'Pasta de saída (uma subpasta por execução)' (Join-Path $Lab 'saida-automacao')
    Start-Sleep -Seconds 1
    if ($Shot) { Capture $Shot 'Execução local' }
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('%i')
    Wait-Until { (Log-Count 'EXEC_STARTED') -gt $started0 } 30 'execução iniciada' | Out-Null
    $d = Wait-Until { Get-ChildItem (Join-Path $script:data 'execucoes') -Directory | Where-Object { $before -notcontains $_.Name } | Select-Object -First 1 } 20 'pasta da execução'
    Start-Sleep -Seconds 1
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}') # fechar a janela não cancela a execução
    Start-Sleep -Milliseconds 500
    return $d.Name
}
function Kill-Agent {
    Stop-Process -Id $script:jvm -Force
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) { Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue }
}

$script:w = Start-Agent
Note '[REAL]' "C1 Agent empacotado aberto (JVM $($script:jvm)), sem Java no PATH"
Check 'C1 Agent empacotado do build candidato abriu com a configuração do laboratório' ($null -ne $script:w)
Start-Sleep -Seconds 2

$code = $null; $rotCode = $null; $oldTok = $null
$run1 = $null; $run2 = $null; $run3 = $null
try {
    # ---------- C1: pareamento pela janela ----------
    $codeFile = Join-Path $Seg 'codigo-pareamento'
    $code = ([IO.File]::ReadAllText($codeFile)).Trim()
    Focus $script:w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%a')
    $pd = Wait-Until { $h = Get-AgentWindow $script:jvm 'Parear com a Platform'; if (Test-WindowShown $h) { $h } } 20 'diálogo de pareamento (Alt+A)'
    Focus $pd
    Start-Sleep -Milliseconds 500
    Send-Text $code # campo de senha: nada é exibido
    Start-Sleep -Milliseconds 300
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    Remove-Item -Force $codeFile
    Note '[REAL]' 'C1 código digitado no campo de senha; arquivo do código apagado'
    Wait-Until { (Log-Count 'PLATFORM_PAIRED') -ge 1 } 60 'pareamento confirmado' | Out-Null
    $vinculo = Get-Content -Raw (Join-Path $script:data 'plataforma\vinculo.json') | ConvertFrom-Json
    $agentDb = Sql "SELECT a.status || '|' || w.worker_id FROM agents a JOIN worker_agent_assignments w ON w.agent_id = a.agent_id AND w.status = 1 WHERE a.agent_id = '$($vinculo.agentId)'"
    Check "C1 pareado pela janela: PLATFORM_PAIRED, Agent ACTIVE no Worker preparado pela pessoa ($agentDb)" ((Log-Count 'PLATFORM_PAIRED') -ge 1 -and $agentDb -eq "2|$($ids.worker)")
    Wait-Until { (Log-Count 'PLATFORM_AUTHORIZATIONS count=2') -ge 1 } 60 'autorizações baixadas' | Out-Null
    Check 'C1 duas autorizações baixadas e guardadas para uso offline (uma por impressão)' ((Log-Count 'PLATFORM_AUTHORIZATIONS count=2') -ge 1 -and (Test-Path (Join-Path $script:data 'plataforma\autorizacoes.json')))
    Capture 'c1-pareado'

    # ---------- C2: presença ----------
    $hb0 = Presence $vinculo.agentId
    Start-Sleep -Seconds 70
    $hb1 = Presence $vinculo.agentId
    Note '[REAL]' "C2 presença no banco: $hb0 -> $hb1"
    Check "C2 heartbeat avançou entre dois ciclos ($hb0 -> $hb1)" ([bool]$hb0 -and [bool]$hb1 -and ($hb1 -gt $hb0))

    # ---------- C3: início offline pela autorização guardada ----------
    $disc0 = Log-Count 'PLATFORM_DISCONNECTED'
    Note '[REAL]' 'C3 nginx do laboratório PAUSADO antes de iniciar (nenhuma resposta da Platform)'
    $null = Dc @('pause', 'nginx')
    Wait-Until { (Log-Count 'PLATFORM_DISCONNECTED') -gt $disc0 } 150 'Agent percebe a falta da Platform' | Out-Null
    Check 'C3 sem Platform antes do início (PLATFORM_DISCONNECTED AGT-PLT-010)' ((Log-Text) -match 'PLATFORM_DISCONNECTED.*AGT-PLT-010')
    $run1 = Start-Run 'pacote-jornada' 'c3-offline-autorizacao-guardada'
    Note '[REAL]' "C3 execução iniciada SEM Platform: $run1"
    Check 'C3 execução vinculada à autorização guardada sem rede (PLATFORM_RUN_LINKED)' ((Log-Count "PLATFORM_RUN_LINKED execution=$run1") -ge 1)
    Start-Sleep -Seconds 15
    $st = (Run-State $run1).state
    $regOff = Sql "SELECT count(*) FROM agent_runs WHERE agent_execution_id = '$run1'"
    Check "C3 automação roda sem Platform ($st) e nada foi registrado ainda ($regOff)" ($st -eq 'RUNNING' -and $regOff -eq '0')
    Capture 'c3-rodando-sem-platform'
    $null = Dc @('unpause', 'nginx')
    Note '[REAL]' 'C3 nginx de volta'
    Wait-Until { (Log-Count "PLATFORM_RUN_REGISTERED execution=$run1") -ge 1 } 180 'registro depois da volta' | Out-Null
    Wait-Until { (Ack-Count $run1) -ge 2 } 120 'primeiros ACKs' | Out-Null
    $d = Db-Run $run1
    Check "C3 registro depois da volta, sob a autorização guardada ($($d.Authorization)) e a versão publicada ($($d.Version))" ($d.Authorization -eq $ids.autorizacao -and $d.Version -eq $ids.versao_publicada)

    # ---------- C4: 503 real (Redis parado) ----------
    $disc0 = ([regex]::Matches((Log-Text), 'PLATFORM_DISCONNECTED[^\r\n]*AGT-PLT-011')).Count
    $gw0 = Gateway-Count 'redis_indisponivel'
    Note '[INJETADA]' 'C4 Redis do laboratório PARADO: o limitador do canal falha fechado (503, sem ACK)'
    $null = Dc @('stop', 'redis')
    Wait-Until { ([regex]::Matches((Log-Text), 'PLATFORM_DISCONNECTED[^\r\n]*AGT-PLT-011')).Count -gt $disc0 } 150 'Agent recebe 503' | Out-Null
    $gw1 = Gateway-Count 'redis_indisponivel'
    Check "C4 a Platform respondeu 503 (gateway: redis_indisponivel $gw0 -> $gw1) e o Agent registrou AGT-PLT-011" ($gw1 -gt $gw0)
    $ackStop = Ack-Count $run1
    Start-Sleep -Seconds 10
    $st = (Run-State $run1).state
    $ackNow = Ack-Count $run1
    Check "C4 automação segue durante o 503 ($st) e nenhum ACK foi aceito sem o servidor ($ackStop -> $ackNow)" ($st -eq 'RUNNING' -and $ackNow -eq $ackStop)
    Capture 'c4-servidor-indisponivel'
    $null = Dc @('start', 'redis')
    Note '[INJETADA]' 'C4 Redis de volta'
    Wait-Until { (Ack-Count $run1) -gt $ackStop } 240 'entrega retoma' | Out-Null
    Check 'C4 entrega retomada depois do 503 (novo PLATFORM_ACK)' ((Ack-Count $run1) -gt $ackStop)

    # ---------- C5: perda local definitiva (arquivo travado por outro programa) ----------
    $atividade = Join-Path (Exec-Dir $run1) 'atividade.jsonl'
    $loss0 = Log-Count "PLATFORM_LOSS_DECLARED[^\r\n]*execution=$run1"
    Note '[INJETADA]' 'C5 outro programa abre o atividade.jsonl sem compartilhar a escrita (65 s)'
    $lockJob = Start-Job -ScriptBlock {
        param($f)
        # a abertura falha se a coleta estiver gravando naquele instante: tenta de novo por alguns segundos
        $fs = $null
        for ($i = 0; $i -lt 200 -and -not $fs; $i++) { try { $fs = [IO.File]::Open($f, 'Open', 'Read', 'Read') } catch { Start-Sleep -Milliseconds 25 } }
        if (-not $fs) { throw 'atividade.jsonl não pôde ser aberto sem compartilhar a escrita' }
        try { Start-Sleep -Seconds 65 } finally { $fs.Dispose() }
    } -ArgumentList $atividade
    Wait-Job $lockJob -Timeout 90 | Out-Null
    Receive-Job $lockJob -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $lockJob -Force
    Note '[INJETADA]' 'C5 arquivo liberado'
    Wait-Until { (Log-Count "PLATFORM_LOSS_DECLARED[^\r\n]*execution=$run1") -gt $loss0 } 240 'perda declarada' | Out-Null
    $loc = Local-Sequences $run1
    Note '[INJETADA]' "C5 local: lacunas $($loc.Holes), avisos $($loc.Gaps)"
    Check "C5 a coleta registrou a perda (EVENT_GAP AGT-OBS-009, lacuna $($loc.Holes)) e o transporte declarou (PLATFORM_LOSS_DECLARED)" ($loc.Holes -ne '' -and $loc.Gaps -match 'AGT-OBS-009' -and (Log-Count "PLATFORM_LOSS_DECLARED[^\r\n]*execution=$run1") -gt $loss0)
    Capture 'c5-perda-local'

    # ---------- fim da execução 1 ----------
    Wait-End $run1 900
    Wait-Until { (Log-Count "PLATFORM_DELIVERY_COMPLETED execution=$run1") -ge 1 } 300 'envio concluído' | Out-Null
    $loc = Local-Sequences $run1
    $d = Db-Run $run1
    Note '[REAL]' "C5 banco: estado=$($d.State) stored=$($d.Stored) settled=$($d.Settled) eventos=$($d.Events) ausentes=$($d.Missing) recusas=$($d.Refusals); local último=$($loc.Last) lacunas=$($loc.Holes)"
    $missingCount = 0
    foreach ($m in ($d.Missing -split ',' | Where-Object { $_ })) { $ab = $m -split '-'; $missingCount += [int]$ab[1] - [int]$ab[0] + 1 }
    Check "C5 ausências declaradas = lacuna local exata ($($d.Missing) = $($loc.Holes)), settled=$($d.Settled) = último local, eventos+ausentes=$([int]$d.Events + $missingCount), recusas=$($d.Refusals)" ($d.Missing -eq $loc.Holes -and $d.Settled -eq "$($loc.Last)" -and ([int]$d.Events + $missingCount) -eq [int]$loc.Last -and $d.Refusals -eq '0' -and [int]$d.Stored -lt [int]$d.Settled)
    $q1 = Consult $run1 'c5-consulta-platform'
    $h1 = $q1.detalhe.history
    Check "C5 Platform: $($q1.detalhe.supervisor.state)/$($q1.detalhe.outcome), histórico $($h1.completeness) ($(@($h1.reasons) -join ',')), stored=$($h1.storedThrough) < settled=$($h1.settledThrough)" ($q1.detalhe.supervisor.state -eq 'SUCCEEDED' -and $q1.detalhe.outcome -eq 'KNOWN' -and $h1.completeness -eq 'INCOMPLETE' -and (@($h1.reasons) -contains 'LOST_LOCAL') -and $h1.storedThrough -lt $h1.settledThrough)
    $c1 = $q1.detalhe.correlation
    Check 'C5 correlação na consulta: autorização, versão publicada, Worker e Agent do vínculo, origem LOCAL_AUTHORIZED' ($c1.authorizationId -eq $ids.autorizacao -and $c1.automationVersionId -eq $ids.versao_publicada -and $c1.workerId -eq $ids.worker -and $c1.agentId -eq $vinculo.agentId -and $q1.detalhe.origin -eq 'LOCAL_AUTHORIZED')

    # ---------- C6: OUTCOME_UNKNOWN com resultado parcial ----------
    $run2 = Start-Run 'pacote-jornada' $null
    Note '[REAL]' "C6 segunda execução iniciada: $run2"
    Wait-Until { (Log-Count "PLATFORM_RUN_REGISTERED execution=$run2") -ge 1 } 60 'registro' | Out-Null
    Wait-Until { (Ack-Count $run2) -ge 2 } 120 'ACKs' | Out-Null
    Start-Sleep -Seconds 20
    $pys = Exec-Pythons $run2
    Note '[REAL]' "C6 Agent (JVM $($script:jvm)) ENCERRADO à força; em seguida a automação ($(@($pys).Count) processos) termina com o Agent fora"
    Kill-Agent
    Start-Sleep -Seconds 2
    foreach ($q in $pys) { Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
    $rec0 = Log-Count 'EXEC_RECONCILED'
    $script:w = Start-Agent
    Note '[REAL]' "C6 Agent reaberto (JVM $($script:jvm))"
    Wait-Until { (Log-Count 'EXEC_RECONCILED') -gt $rec0 } 60 'reconciliação' | Out-Null
    $s2 = Run-State $run2
    $partial = ($s2.counts | ConvertTo-Json -Compress)
    Check "C6 reconciliação fechou $($s2.state) ($($s2.code)) sem relançar, com o resultado parcial $partial" ($s2.state -eq 'OUTCOME_UNKNOWN' -and @(Exec-Pythons $run2).Count -eq 0)
    Wait-Until { (Log-Count "PLATFORM_REPORT execution=$run2 state=OUTCOME_UNKNOWN") -ge 1 } 180 'relatório OUTCOME_UNKNOWN' | Out-Null
    Wait-Until { (Log-Count "PLATFORM_DELIVERY_COMPLETED execution=$run2") -ge 1 } 300 'envio concluído' | Out-Null
    $q2 = Consult $run2 'c6-consulta-platform'
    $sv2 = $q2.detalhe.supervisor
    Check "C6 Platform: $($sv2.state)/$($q2.detalhe.process)/$($q2.detalhe.outcome), contagens $($sv2.counts | ConvertTo-Json -Compress), transações vistas $($q2.detalhe.transactionsSeen | ConvertTo-Json -Compress)" ($sv2.state -eq 'OUTCOME_UNKNOWN' -and $q2.detalhe.outcome -eq 'UNKNOWN' -and $q2.detalhe.process -eq 'ENDED')
    $runsReg = Sql "SELECT count(*) FROM agent_runs WHERE agent_execution_id = '$run2'"
    $outs = @(Get-ChildItem (Join-Path $Lab 'saida-automacao') -Directory).Count
    Check "C6 nada relançado: um registro ($runsReg) e uma pasta de saída por execução ($outs para 2)" ($runsReg -eq '1' -and $outs -eq 2)
    Focus $script:w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    Start-Sleep -Seconds 2
    Capture 'c6-desfecho-desconhecido' 'Execução local'
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}')

    # ---------- C7: rotação pela janela ----------
    Add-Type -AssemblyName System.Security
    $cred = Join-Path $script:data 'plataforma\credencial.dpapi'
    $entropy = [Text.Encoding]::ASCII.GetBytes('nekkora-agent/platform-secret/1')
    $oldTok = [Text.Encoding]::ASCII.GetString([Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($cred), $entropy, 'CurrentUser'))
    $cr0 = Sql "SELECT count(*) || '|' || count(*) FILTER (WHERE revoked_at IS NULL) FROM agent_credentials WHERE agent_id = '$($vinculo.agentId)'"
    & python -I (Join-Path $PSScriptRoot 'pessoa.py') rotacao $Id | Out-Null
    $rotFile = Join-Path $Seg 'codigo-rotacao'
    $rotCode = ([IO.File]::ReadAllText($rotFile)).Trim()
    Note '[REAL]' 'C7 a pessoa gerou um código de rotação na Platform'
    Focus $script:w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%n')
    $rd = Wait-Until { $h = Get-AgentWindow $script:jvm 'Nova credencial da máquina'; if (Test-WindowShown $h) { $h } } 20 'diálogo de rotação (Alt+N)'
    Focus $rd
    Start-Sleep -Milliseconds 500
    Send-Text $rotCode
    Start-Sleep -Milliseconds 300
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    Remove-Item -Force $rotFile
    Note '[REAL]' 'C7 código de rotação digitado no campo de senha; arquivo apagado'
    Wait-Until { (Log-Count 'PLATFORM_ROTATED') -ge 1 } 60 'rotação confirmada' | Out-Null
    $cr1 = Sql "SELECT count(*) || '|' || count(*) FILTER (WHERE revoked_at IS NULL) || '|' || count(*) FILTER (WHERE revocation_reason = 1) FROM agent_credentials WHERE agent_id = '$($vinculo.agentId)'"
    $newTok = [Text.Encoding]::ASCII.GetString([Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($cred), $entropy, 'CurrentUser'))
    $changed = $newTok -ne $oldTok
    $newTok = $null
    Check "C7 rotação confirmada (PLATFORM_ROTATED): credenciais $cr0 -> $cr1 (total|ativas|revogadas por ROTATION), segredo local trocado" ((Log-Count 'PLATFORM_ROTATED') -ge 1 -and $cr1 -eq '2|1|1' -and $changed -and -not (Test-Path (Join-Path $script:data 'plataforma\resgate.dpapi')))
    $refGw0 = Gateway-Count 'autenticacao_recusada'
    $hb0 = Presence $vinculo.agentId
    Start-Sleep -Seconds 70
    $hb1 = Presence $vinculo.agentId
    $refGw1 = Gateway-Count 'autenticacao_recusada'
    Check "C7 a presença continua com a credencial nova ($hb0 -> $hb1) e nenhuma recusa no canal ($refGw0 -> $refGw1)" ($hb1 -gt $hb0 -and $refGw1 -eq $refGw0 -and (Log-Count 'PLATFORM_CREDENTIAL_REFUSED') -eq 0)
    Capture 'c7-credencial-nova'

    # ---------- C8: limite do histórico (perda na origem) ----------
    $run3 = Start-Run 'pacote-limite' 'c8-pacote-limite-autorizacao'
    Note '[REAL]' "C8 execução com mais de 20.000 eventos: $run3"
    Wait-End $run3 900
    Wait-Until { (Log-Count "PLATFORM_DELIVERY_COMPLETED execution=$run3") -ge 1 } 900 'envio concluído' | Out-Null
    $loc = Local-Sequences $run3
    $d = Db-Run $run3
    Note '[REAL]' "C8 local: último=$($loc.Last) lacunas='$($loc.Holes)' avisos=$($loc.Gaps); banco: stored=$($d.Stored) settled=$($d.Settled) eventos=$($d.Events) ausentes='$($d.Missing)'"
    Check "C8 a coleta registrou o limite (AGT-OBS-005) sem lacuna de sequência; banco stored=settled=$($d.Settled) = último local, nada declarado ausente, sob a autorização da segunda impressão" ($loc.Gaps -match 'AGT-OBS-005' -and $loc.Holes -eq '' -and $d.Stored -eq "$($loc.Last)" -and $d.Settled -eq "$($loc.Last)" -and $d.Missing -eq '' -and $d.Refusals -eq '0' -and $d.Authorization -eq $authLimite.autorizacao)
    $q3 = Consult $run3 'c8-consulta-platform'
    $h3 = $q3.detalhe.history
    Check "C8 Platform: $($q3.detalhe.supervisor.state)/$($q3.detalhe.outcome), histórico $($h3.completeness) ($(@($h3.reasons) -join ',')), eventos $($q3.eventos.quantidade) em ordem, sem repetição" ($q3.detalhe.supervisor.state -eq 'SUCCEEDED' -and $h3.completeness -eq 'INCOMPLETE' -and (@($h3.reasons) -contains 'LOST_AT_SOURCE') -and -not (@($h3.reasons) -contains 'LOST_LOCAL') -and $q3.eventos.sequencias_repetidas -eq 0 -and $q3.eventos.em_ordem)
    Focus $script:w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    Start-Sleep -Seconds 2
    Capture 'c8-execucao-e-envio' 'Execução local'
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
}
catch {
    Note 'ERRO' ($_.Exception.Message + ' @linha ' + $_.InvocationInfo.ScriptLineNumber)
    Check ('jornada interrompida por exceção: ' + $_.Exception.Message) $false
}
finally {
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) {
        $h = Get-AgentWindow $q.ProcessId
        if (Test-WindowShown $h) { Focus $h; [System.Windows.Forms.SendKeys]::SendWait('%s'); Start-Sleep -Seconds 3 }
    }
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) { Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue }
    $null = Dc @('unpause', 'nginx')
    $null = Dc @('start', 'redis')
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($Lab) })) {
        Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

# ---------- C9: varredura de segredos ----------
Add-Type -AssemblyName System.Security
$secretsToFind = New-Object System.Collections.Generic.List[string]
foreach ($c in @($code, $rotCode, $oldTok)) { if ($c) { $secretsToFind.Add($c); $secretsToFind.Add($c.Substring($c.LastIndexOf('.') + 1)) } }
$cred = Join-Path $script:data 'plataforma\credencial.dpapi'
if (Test-Path $cred) {
    $tok = [Text.Encoding]::ASCII.GetString([Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($cred), [Text.Encoding]::ASCII.GetBytes('nekkora-agent/platform-secret/1'), 'CurrentUser'))
    $secretsToFind.Add($tok); $secretsToFind.Add($tok.Substring($tok.LastIndexOf('.') + 1))
}
$pessoa = Get-Content -Raw (Join-Path $Seg 'pessoa.json') | ConvertFrom-Json
$secretsToFind.Add($pessoa.senha); $secretsToFind.Add($pessoa.totp)
$haystack = New-Object System.Text.StringBuilder
foreach ($f in Get-ChildItem -Recurse -File $Lab | Where-Object { $_.FullName -notlike "$app*" -and $_.Extension -ne '.dpapi' -and $_.Extension -ne '.png' -and $_.Extension -ne '.p12' }) {
    try { [void]$haystack.Append((Read-Shared $f.FullName)) } catch { }
}
foreach ($c in 'nginx', 'platform', 'agent-gateway', 'portal', 'postgres', 'redis') { [void]$haystack.Append((& docker @composeArgs logs --no-color $c 2>$null) -join "`n") }
[void]$haystack.Append((Sql "SELECT coalesce(string_agg(envelope::text, ' '), '') FROM agent_run_events"))
$text = $haystack.ToString()
$leaks = @($secretsToFind | Where-Object { $_ -and $text.Contains($_) }).Count
Check "C9 nenhum segredo (códigos de pareamento e rotação, credenciais anterior e atual, senha e fator da pessoa: $($secretsToFind.Count) valores) em evidências, dados, logs dos 6 serviços e envelopes" ($leaks -eq 0)
$secretsToFind.Clear(); $code = $null; $rotCode = $null; $oldTok = $null; $tok = $null

# ---------- registro ----------
$ok = @($checks | Where-Object { $_.ok }).Count
$timeline | Set-Content -Encoding UTF8 (Join-Path $Out 'linha-do-tempo.txt')
[ordered]@{ schema = 'nekkora-agent/platform-lab-journey'; version = '1.0.0'; laboratorio = $Id; jornada = 'complemento PA-05'
    execucoes = [ordered]@{ offline_503_perda_local = $run1; desfecho_desconhecido = $run2; limite_do_historico = $run3 }
    agent = ($agentVersions -split "`n" | Select-Object -First 3) -join ' / '; rotulos = '[REAL] processo e API reais; [INJETADA] falha controlada identificada'
    checks = $checks; total = $checks.Count; ok = $ok } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $Out 'resultado.json')
Write-Host ''
Write-Host "JORNADA COMPLEMENTAR: $ok/$($checks.Count) conferências OK — $Out"
