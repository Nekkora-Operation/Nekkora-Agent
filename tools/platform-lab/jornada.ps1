<#
.SYNOPSIS
  AGENT-PLATFORM-001 — jornada integrada do Agent EMPACOTADO (processo real) com a API REAL do laboratório descartável.

.DESCRIPTION
  Cada passo é rotulado: [REAL] = Agent empacotado, automação e API reais; [INJETADA] = falha controlada, identificada
  (trava da linha da execução no banco + pausa do nginx para perder a resposta depois do commit). Nada aqui é simulado.

  J1  Agent empacotado (cópia de laboratório que confia SÓ no certificado do laboratório; validação TLS normal) abre.
  J2  Pareamento pela janela (Alt+A): o código é DIGITADO no campo de senha, nunca exibido nem registrado; o arquivo do
      código é apagado em seguida. A credencial é gerada e guardada pelo Agent (DPAPI).
  J3  Execução local real pela janela: pacote de laboratório (formato Studio, eventos reais do nekkora_rpa 0.3.0, dados
      fictícios), aprovado pela pessoa; a autorização da Platform vem pré-escolhida (única válida para a impressão).
  J4  [REAL] Interrupção da comunicação durante a entrega: o nginx do laboratório é PARADO por alguns segundos com a
      automação rodando; depois volta. A automação não é afetada; a entrega retoma.
  J5  [INJETADA] ACK perdido: trava a linha da execução (FOR UPDATE) para o pedido seguinte ficar preso no gateway, pausa o
      nginx, libera a trava (o gateway grava e responde para o nginx pausado), o Agent esgota o prazo; o nginx volta e o
      reenvio devolve DUPLICATE (contagens no log do Agent).
  J6  [REAL] Reinício: o processo do Agent é ENCERRADO à força com a automação rodando e envios pendentes; reaberto, ele
      adota o processo (sem relançar) e a entrega retoma de settledThrough + 1.
  J7  Desfecho do supervisor e completude na API (consulta como a pessoa) e conferência no banco (sem recusa, sem repetição).
  J8  [REAL] Revogação pela pessoa: a primeira chamada autenticada seguinte é recusada e o transporte para; uma execução
      local seguinte continua funcionando, só local.
  J9  Varredura de segredos (código de pareamento, credencial, senha e fator da pessoa) em evidências, dados e logs.
  Não use o computador durante a jornada (teclas e foco de janela).
#>
param(
    [string]$Id = 'agentlab-001',
    [Parameter(Mandatory = $true)][string]$Python
)
$ErrorActionPreference = 'Continue' # comandos nativos (docker, python) escrevem no stderr sem falhar; as esperas lançam exceção
Set-StrictMode -Version 3.0
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $root 'tools\homologation\NkHomolog.psm1') -Force
$wincap = Join-Path $root 'tools\homologation\wincap.ps1'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$Lab = Join-Path $PSScriptRoot "saidas\$Id"
$Seg = Join-Path $env:USERPROFILE ".nekkora\agent-lab\$Id"
$Out = Join-Path $Lab ('jornada-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
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

# ---------- J1: Agent empacotado, cópia de laboratório ----------
$dist = Join-Path $root 'target\dist\Nekkora Agent'
$app = Join-Path $Lab 'agent-app'
if (Test-Path $app) { Remove-Item -Recurse -Force $app }
Copy-Item -Recurse $dist $app
$exe = Join-Path $app 'Nekkora Agent.exe'
$cfg = Join-Path $app 'app\Nekkora Agent.cfg'
$trust = Join-Path $Lab 'confianca-laboratorio.p12'
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
$w = Start-Agent
Note '[REAL]' "J1 Agent empacotado aberto (JVM $($script:jvm)), sem Java no PATH"
Check 'J1 Agent empacotado abriu com a configuração do laboratório' ($null -ne $w)
Start-Sleep -Seconds 2
Capture 'j1-nao-pareado'

$code = $null
$execId = $null
try {
    # ---------- J2: pareamento pela janela ----------
    $codeFile = Join-Path $Seg 'codigo-pareamento'
    $code = ([IO.File]::ReadAllText($codeFile)).Trim()
    Focus $w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%a')
    $pd = Wait-Until { $h = Get-AgentWindow $script:jvm 'Parear com a Platform'; if (Test-WindowShown $h) { $h } } 20 'diálogo de pareamento'
    Focus $pd
    Start-Sleep -Milliseconds 500
    Send-Text $code # campo de senha: nada é exibido
    Start-Sleep -Milliseconds 300
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    Remove-Item -Force $codeFile
    Note '[REAL]' 'J2 código digitado no campo de senha; arquivo do código apagado'
    Wait-Until { (Log-Count 'PLATFORM_PAIRED') -ge 1 } 60 'pareamento confirmado' | Out-Null
    Check 'J2 pareamento confirmado pela Platform real (PLATFORM_PAIRED)' ((Log-Count 'PLATFORM_PAIRED') -ge 1)
    $vinculo = Get-Content -Raw (Join-Path $script:data 'plataforma\vinculo.json') | ConvertFrom-Json
    Check 'J2 vínculo gravado sem segredo (vinculo.json) e credencial só no arquivo protegido' ((Test-Path (Join-Path $script:data 'plataforma\credencial.dpapi')) -and -not (Test-Path (Join-Path $script:data 'plataforma\resgate.dpapi')))
    $agentDb = Sql "SELECT status || '|' || worker_id FROM agents a JOIN worker_agent_assignments w ON w.agent_id = a.agent_id AND w.status = 1 WHERE a.agent_id = '$($vinculo.agentId)'"
    Check "J2 Agent ACTIVE e associado ao Worker na Platform ($agentDb)" ($agentDb -match '^2\|')
    Wait-Until { (Log-Count 'PLATFORM_AUTHORIZATIONS count=2') -ge 1 } 60 'autorizações baixadas' | Out-Null
    Check 'J2 autorizações baixadas (2 válidas para o Worker, uma por impressão)' ((Log-Count 'PLATFORM_AUTHORIZATIONS count=2') -ge 1)
    Start-Sleep -Seconds 1
    Capture 'j2-pareado'

    # ---------- J3: execução local real ----------
    Focus $w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    $dlg = Wait-Until { $h = Get-AgentWindow $script:jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
    Start-Sleep -Milliseconds 800
    Choose-Path $dlg '%p' 'Pasta do pacote da automação' (Join-Path $Lab 'pacote-laboratorio-longo')
    Wait-Until { (Log-Count 'EXEC_PACKAGE_INSPECTED') -ge 1 } 30 'pacote inspecionado' | Out-Null
    Start-Sleep -Milliseconds 600
    $pos = Find-Button $dlg @(@(109, 60, 82), @(118, 71, 92)) 185 330 450 1000
    if (-not $pos) { throw 'botão Aprovar este pacote… não encontrado' }
    Click-In $dlg $pos[0] $pos[1]
    $confirm = Wait-Until { $h = Get-AgentWindow $script:jvm 'Aprovar este pacote?'; if (Test-WindowShown $h) { $h } } 20 'confirmação da aprovação'
    Start-Sleep -Milliseconds 500
    $pos = Find-Button $confirm @(, @(240, 163, 184)) 200 420 120 400 -FromRight
    if (-not $pos) { throw 'botão Aprovar da confirmação não encontrado' }
    Click-In $confirm $pos[0] $pos[1]
    Wait-Until { (Log-Count 'EXEC_PACKAGE_APPROVED') -ge 1 } 20 'aprovação registrada' | Out-Null
    Choose-Path $dlg '%y' 'Python do ambiente do projeto' $Python
    Wait-Until { (Log-Count 'EXEC_PYTHON_CHECKED') -ge 1 } 60 'Python conferido' | Out-Null
    Choose-Path $dlg '%e' 'Entrada da automação' (Join-Path $Lab 'entrada-ficticia.csv')
    Start-Sleep -Seconds 1
    Choose-Path $dlg '%a' 'Pasta de saída (uma subpasta por execução)' (Join-Path $Lab 'saida-automacao')
    Start-Sleep -Seconds 1
    Capture 'j3-pronto-com-autorizacao' 'Execução local'
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('%i')
    Wait-Until { (Log-Count 'EXEC_STARTED') -ge 1 } 30 'execução iniciada' | Out-Null
    $runDir = Wait-Until { Get-ChildItem (Join-Path $script:data 'execucoes') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1 } 20 'pasta da execução'
    $execId = $runDir.Name
    $estado = Join-Path $runDir.FullName 'estado.json'
    $atividade = Join-Path $runDir.FullName 'atividade.jsonl'
    Note '[REAL]' "J3 execução local iniciada pela janela: $execId"
    Wait-Until { (Log-Count "PLATFORM_RUN_LINKED execution=$execId") -ge 1 } 20 'vínculo da execução' | Out-Null
    Check 'J3 execução vinculada à autorização escolhida (PLATFORM_RUN_LINKED)' ((Log-Count "PLATFORM_RUN_LINKED execution=$execId") -ge 1)
    Wait-Until { (Log-Count "PLATFORM_RUN_REGISTERED execution=$execId") -ge 1 } 30 'registro na Platform' | Out-Null
    Check 'J3 execução registrada na Platform (PLATFORM_RUN_REGISTERED)' ((Log-Count "PLATFORM_RUN_REGISTERED execution=$execId") -ge 1)
    Wait-Until { (Log-Count "PLATFORM_ACK execution=$execId") -ge 2 } 60 'primeiros ACKs' | Out-Null
    Capture 'j3-em-andamento' 'Execução local'

    # ---------- J4: interrupção da comunicação (REAL) ----------
    $disc0 = Log-Count 'PLATFORM_DISCONNECTED'
    $lines0 = @(Read-Shared $atividade).Count
    Note '[REAL]' 'J4 nginx do laboratório PARADO (sem caminho até a Platform) com a automação rodando'
    $null = Dc @('stop', '-t', '1', 'nginx')
    Wait-Until { (Log-Count 'PLATFORM_DISCONNECTED') -gt $disc0 } 60 'Agent percebe a queda' | Out-Null
    Check 'J4 o Agent registra o problema de comunicação (PLATFORM_DISCONNECTED AGT-PLT-010)' ((Log-Text) -match 'PLATFORM_DISCONNECTED.*AGT-PLT-010')
    Start-Sleep -Seconds 12
    $st = (Get-Content -Raw $estado | ConvertFrom-Json).state
    Check "J4 automação segue durante a queda (estado $st, atividade crescendo)" ($st -eq 'RUNNING' -and (Read-Shared $atividade).Length -gt 0)
    Capture 'j4-sem-comunicacao'
    $null = Dc @('start', 'nginx')
    Note '[REAL]' 'J4 nginx de volta'
    $ack0 = Log-Count "PLATFORM_ACK execution=$execId"
    Wait-Until { (Log-Count "PLATFORM_ACK execution=$execId") -gt $ack0 } 120 'entrega retoma' | Out-Null
    Check 'J4 entrega retomada depois da volta (novo PLATFORM_ACK)' ((Log-Count "PLATFORM_ACK execution=$execId") -gt $ack0)

    # ---------- J5: ACK perdido (INJETADA) ----------
    $dup0 = ([regex]::Matches((Log-Text), "PLATFORM_ACK execution=$execId [^\r\n]*duplicates=([1-9][0-9]*)")).Count
    Note '[INJETADA]' 'J5 trava FOR UPDATE da linha da execução (12 s) para o próximo lote ficar preso no gateway'
    $lockJob = Start-Job -ScriptBlock {
        param($argsList, $ex)
        "BEGIN; SELECT 1 FROM agent_runs WHERE agent_execution_id = '$ex' FOR UPDATE; SELECT pg_sleep(12); COMMIT;" |
                & docker @argsList exec -T postgres psql -X -q -At -U nekkora -d nekkora 2>&1 | Out-Null
    } -ArgumentList (, $composeArgs), $execId
    Start-Sleep -Seconds 4
    Note '[INJETADA]' 'J5 nginx PAUSADO com o pedido em andamento (a resposta não chegará ao Agent)'
    $null = Dc @('pause', 'nginx')
    Wait-Job $lockJob -Timeout 30 | Out-Null
    Remove-Job $lockJob -Force
    Note '[INJETADA]' 'J5 trava liberada: o gateway grava e responde ao nginx pausado'
    Start-Sleep -Seconds 32 # o Agent esgota o prazo de resposta (30 s)
    $null = Dc @('unpause', 'nginx')
    Note '[INJETADA]' 'J5 nginx retomado; o Agent reenvia o mesmo lote'
    Wait-Until { ([regex]::Matches((Log-Text), "PLATFORM_ACK execution=$execId [^\r\n]*duplicates=([1-9][0-9]*)")).Count -gt $dup0 } 180 'ACK com DUPLICATE' | Out-Null
    Check 'J5 reenvio do lote cujo ACK se perdeu devolveu DUPLICATE (sem duplicar no servidor)' (([regex]::Matches((Log-Text), "PLATFORM_ACK execution=$execId [^\r\n]*duplicates=([1-9][0-9]*)")).Count -gt $dup0)

    # ---------- J6: reinício do Agent com a automação rodando (REAL) ----------
    $st = (Get-Content -Raw $estado | ConvertFrom-Json)
    if ($st.state -ne 'RUNNING') { Note '[REAL]' "J6 aviso: a automação já terminou ($($st.state)) antes do reinício" }
    $pyPid = $st.process.pid
    Note '[REAL]' "J6 processo do Agent (JVM $($script:jvm)) ENCERRADO à força; automação (PID $pyPid) segue"
    Stop-Process -Id $script:jvm -Force
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) { Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
    $w = Start-Agent
    Note '[REAL]' "J6 Agent reaberto (JVM $($script:jvm))"
    Wait-Until { (Log-Count 'EXEC_RECONCILED') -ge 1 } 60 'reconciliação' | Out-Null
    Check 'J6 reconciliação depois do reinício, sem relançar (EXEC_RECONCILED)' ((Log-Count 'EXEC_RECONCILED') -ge 1)
    $pyAlive = @(Get-Process -Id $pyPid -ErrorAction SilentlyContinue).Count
    $pyCount = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine -like "*$execId*" }).Count
    Check "J6 a mesma automação (PID $pyPid ainda vivo=$pyAlive), nenhuma segunda instância ($pyCount processo da execução)" ($pyCount -le 1)

    # ---------- J7: desfecho e completude ----------
    Wait-Until { $s = (Get-Content -Raw $estado | ConvertFrom-Json).state; $s -ne 'RUNNING' -and $s -ne 'STARTING' } 600 'fim da automação' | Out-Null
    $final = (Get-Content -Raw $estado | ConvertFrom-Json)
    Note '[REAL]' "J7 fim pelo supervisor: $($final.state)"
    Wait-Until { (Log-Count "PLATFORM_DELIVERY_COMPLETED execution=$execId") -ge 1 } 300 'envio concluído' | Out-Null
    Check 'J7 envio concluído e confirmado (PLATFORM_DELIVERY_COMPLETED)' ((Log-Count "PLATFORM_DELIVERY_COMPLETED execution=$execId") -ge 1)
    Focus $w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    Start-Sleep -Seconds 2
    Capture 'j7-automacao-e-envio-concluidos' 'Execução local'
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
    $lastSeq = 0
    foreach ($l in (Read-Shared $atividade) -split "`n") { if ($l -match '"sequence":(\d+)') { $lastSeq = [Math]::Max($lastSeq, [int]$Matches[1]) } }
    $db = Sql "SELECT supervisor_state || '|' || stored_through || '|' || settled_through || '|' || (SELECT count(*) FROM agent_run_events e WHERE e.agent_run_id = r.agent_run_id) || '|' || (SELECT count(*) FROM agent_ingestion_refusals f WHERE f.agent_run_id = r.agent_run_id) || '|' || (SELECT count(*) FROM agent_run_missing m WHERE m.agent_run_id = r.agent_run_id) || '|' || coalesce(framework_execution_id, '-') FROM agent_runs r WHERE agent_execution_id = '$execId'"
    $p = $db -split '\|'
    Check "J7 banco: estado=$($p[0]) stored=$($p[1]) settled=$($p[2]) eventos=$($p[3]) (local até $lastSeq) recusas=$($p[4]) ausências=$($p[5])" ($p[1] -eq "$lastSeq" -and $p[2] -eq "$lastSeq" -and $p[3] -eq "$lastSeq" -and $p[4] -eq '0' -and $p[5] -eq '0')
    $runs = Sql "SELECT count(*) FROM agent_runs WHERE agent_execution_id = '$execId'"
    Check "J7 uma execução registrada ($runs), nunca repetida" ($runs -eq '1')
    $outs = @(Get-ChildItem (Join-Path $Lab 'saida-automacao') -Directory).Count
    Check "J7 uma pasta de saída da automação ($outs): a automação não foi repetida" ($outs -eq 1)
    $reports = Sql "SELECT string_agg(r.supervisor_state || ':' || r.outcome, ' ' ORDER BY r.received_at) FROM agent_run_reports r JOIN agent_runs a ON a.agent_run_id = r.agent_run_id WHERE a.agent_execution_id = '$execId'"
    Note '[REAL]' "J7 relatórios do supervisor na Platform (estado:desfecho do relatório): $reports"
    & python -I (Join-Path $PSScriptRoot 'pessoa.py') consultar $Id $execId | Out-File -Encoding utf8 (Join-Path $Out 'j7-consulta-platform.json')
    $q = Get-Content -Raw (Join-Path $Out 'j7-consulta-platform.json')
    Check 'J7 consulta da Platform pela pessoa: correlação, desfecho e completude gravados' ($q -match $execId)

    # ---------- J8: revogação ----------
    $refused0 = Log-Count 'PLATFORM_CREDENTIAL_REFUSED'
    & python -I (Join-Path $PSScriptRoot 'pessoa.py') revogar $Id | Out-File -Encoding utf8 (Join-Path $Out 'j8-revogacao.json')
    Note '[REAL]' 'J8 Agent revogado pela pessoa na Platform (ADMINISTRATIVE, com step-up)'
    Wait-Until { (Log-Count 'PLATFORM_CREDENTIAL_REFUSED') -gt $refused0 } 150 'recusa da credencial' | Out-Null
    Check 'J8 a primeira chamada autenticada depois da revogação foi recusada e o transporte parou' ((Log-Count 'PLATFORM_CREDENTIAL_REFUSED') -gt $refused0)
    $gw0 = @(& docker @composeArgs logs --no-color agent-gateway 2>$null | Select-String 'autenticacao_recusada').Count
    Start-Sleep -Seconds 75 # mais de um ciclo de heartbeat
    $gw1 = @(& docker @composeArgs logs --no-color agent-gateway 2>$null | Select-String 'autenticacao_recusada').Count
    Check "J8 nenhuma nova tentativa autenticada depois da recusa (recusas no gateway: $gw0 → $gw1)" ($gw1 -eq $gw0)
    Capture 'j8-credencial-recusada'
    # uma execução local depois da revogação: só local (nenhuma autorização é oferecida)
    Focus $w.Handle
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    $dlg = Wait-Until { $h = Get-AgentWindow $script:jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
    Start-Sleep -Seconds 1
    $started0 = Log-Count 'EXEC_STARTED'
    Choose-Path $dlg '%p' 'Pasta do pacote da automação' (Join-Path $Lab 'pacote-laboratorio')
    Start-Sleep -Seconds 2
    Choose-Path $dlg '%y' 'Python do ambiente do projeto' $Python
    Wait-Until { (Log-Count 'EXEC_PYTHON_CHECKED') -ge 2 } 60 'Python conferido' | Out-Null
    Choose-Path $dlg '%e' 'Entrada da automação' (Join-Path $Lab 'entrada-ficticia.csv')
    Start-Sleep -Seconds 1
    Choose-Path $dlg '%a' 'Pasta de saída (uma subpasta por execução)' (Join-Path $Lab 'saida-automacao')
    Start-Sleep -Seconds 1
    Capture 'j8-execucao-local-depois-da-revogacao' 'Execução local'
    Focus $dlg
    [System.Windows.Forms.SendKeys]::SendWait('%i')
    Wait-Until { (Log-Count 'EXEC_STARTED') -gt $started0 } 30 'execução local iniciada' | Out-Null
    $second = Get-ChildItem (Join-Path $script:data 'execucoes') -Directory | Where-Object { $_.Name -ne $execId } | Select-Object -First 1
    Wait-Until { $s = (Get-Content -Raw (Join-Path $second.FullName 'estado.json') | ConvertFrom-Json).state; $s -ne 'RUNNING' -and $s -ne 'STARTING' } 600 'fim da execução local' | Out-Null
    $s2 = (Get-Content -Raw (Join-Path $second.FullName 'estado.json') | ConvertFrom-Json).state
    $reg2 = Sql "SELECT count(*) FROM agent_runs WHERE agent_execution_id = '$($second.Name)'"
    Check "J8 execução local depois da revogação terminou ($s2), sem registro na Platform ($reg2) e sem vínculo de entrega" ($s2 -eq 'SUCCEEDED' -and $reg2 -eq '0' -and -not (Test-Path (Join-Path $script:data "plataforma\entregas\$($second.Name).json")))
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
}
catch {
    Note 'ERRO' ($_.Exception.Message + ' @linha ' + $_.InvocationInfo.ScriptLineNumber)
    Check ('jornada interrompida por exceção: ' + $_.Exception.Message) $false
}
finally {
    # ---------- encerramento do Agent ----------
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) {
        $h = Get-AgentWindow $q.ProcessId
        if (Test-WindowShown $h) { Focus $h; [System.Windows.Forms.SendKeys]::SendWait('%s'); Start-Sleep -Seconds 3 }
    }
    foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) { Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue }
    $null = Dc @('unpause', 'nginx')
    $null = Dc @('start', 'nginx')
}

# ---------- J9: varredura de segredos ----------
Add-Type -AssemblyName System.Security
$secretsToFind = New-Object System.Collections.Generic.List[string]
if ($code) { $secretsToFind.Add($code); $secretsToFind.Add($code.Substring($code.LastIndexOf('.') + 1)) }
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
foreach ($c in 'nginx', 'platform', 'agent-gateway', 'portal', 'postgres') { [void]$haystack.Append((& docker @composeArgs logs --no-color $c 2>$null) -join "`n") }
[void]$haystack.Append((Sql "SELECT coalesce(string_agg(envelope::text, ' '), '') FROM agent_run_events"))
$text = $haystack.ToString()
$leaks = @($secretsToFind | Where-Object { $_ -and $text.Contains($_) }).Count
Check "J9 nenhum segredo (código, credencial, senha e fator da pessoa: $($secretsToFind.Count) valores) em evidências, dados, logs dos 5 serviços e envelopes" ($leaks -eq 0)
$secretsToFind.Clear(); $code = $null; $tok = $null

# ---------- registro ----------
$ok = @($checks | Where-Object { $_.ok }).Count
$timeline | Set-Content -Encoding UTF8 (Join-Path $Out 'linha-do-tempo.txt')
[ordered]@{ schema = 'nekkora-agent/platform-lab-journey'; version = '1.0.0'; laboratorio = $Id; execucao = $execId
    agent = ($agentVersions -split "`n" | Select-Object -First 3) -join ' / '; rotulos = '[REAL] processo e API reais; [INJETADA] falha controlada identificada'
    checks = $checks; total = $checks.Count; ok = $ok } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $Out 'resultado.json')
Write-Host ''
Write-Host "JORNADA: $ok/$($checks.Count) conferências OK — $Out"
