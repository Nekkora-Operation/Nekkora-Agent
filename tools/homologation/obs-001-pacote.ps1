<#
.SYNOPSIS
  OBS-001 no PACOTE do Agent (executável empacotado, sem Java no PATH): aba "Atividade" de uma execução VIVA que
  publica eventos do Framework, adotada depois de uma queda simulada; histórico depois de reabrir o Agent.

.DESCRIPTION
  1. Extrai o ZIP numa pasta nova. O roteiro Java (DesktopHomologation semear-obs) cria um pacote no formato do Studio
     cujo main.py publica eventos REAIS do nekkora_rpa (EventLogger), lança o processo e grava o registro RUNNING,
     como o Agent teria feito antes de cair.
  2. Abre o Agent empacotado (PATH = C:\Windows\System32;C:\Windows, sem JAVA_HOME): a execução é ADOTADA.
  3. Alt+E (Execução local) e Alt+T (aba Atividade): capturas DURANTE a execução enquanto o atividade.jsonl cresce.
  4. Fim pelo protocolo; captura final; a coleta fecha (COLLECTION_COMPLETED) sem duplicar eventos.
  5. Sai e reabre o Agent empacotado: o histórico continua (mesmo arquivo, nada reescrito); captura.
  Não use o computador durante a execução (teclas e foco de janela).
#>
param(
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$TestClasspath,
    [string]$Zip,
    [string]$Out = (Join-Path ([IO.Path]::GetTempPath()) ("nekkora-obs001-pacote-" + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Zip) { $Zip = Join-Path $root 'target\dist\nekkora-agent-0.1.0-windows-x64-lab.zip' }
Import-Module (Join-Path $PSScriptRoot 'NkHomolog.psm1') -Force
$wincap = Join-Path $PSScriptRoot 'wincap.ps1'
New-Item -ItemType Directory -Force $Out | Out-Null
$results = New-Object System.Collections.Generic.List[string]
$fails = 0
function Check([string]$What, [bool]$Ok) {
    $line = ('{0} {1}' -f $(if ($Ok) { 'OK   ' } else { 'FALHA' }), $What)
    $results.Add($line); Write-Host $line
    if (-not $Ok) { $script:fails++ }
}
function Activity-Lines([string]$File) {
    if (-not (Test-Path $File)) { return @() }
    return @([IO.File]::ReadAllLines($File, [Text.Encoding]::UTF8) | Where-Object { $_ -ne '' })
}

# ---------- pacote do Agent ----------
$expected = ((Get-Content "$Zip.sha256") -split '\s+')[0]
Check "SHA-256 do ZIP confere" ((Get-FileHash -Algorithm SHA256 $Zip).Hash.ToLowerInvariant() -eq $expected)
$app = Join-Path $Out 'agent'
Expand-Archive -Path $Zip -DestinationPath $app -Force
$exe = (Get-ChildItem -Recurse -Filter 'Nekkora Agent.exe' $app | Select-Object -First 1).FullName

# ---------- queda simulada com eventos do Framework ----------
$data = Join-Path $Out 'dados'
$saida = Join-Path $Out 'saida'
$pkg = Join-Path $Out 'pacote-formato-studio'
New-Item -ItemType Directory -Force $data, $saida | Out-Null
Push-Location $root
try { $seed = & java -cp $TestClasspath com.nekkora.agent.ui.DesktopHomologation semear-obs (Join-Path $Out 'semear') $data $pkg $Python $saida 2>&1 }
finally { Pop-Location }
$m = [regex]::Match(($seed -join "`n"), 'SEMEADO (ex-[0-9a-f]{32}) pid=(\d+)')
Check "queda simulada: processo vivo publicando eventos + registro RUNNING" $m.Success
$id = $m.Groups[1].Value
$procPid = [int]$m.Groups[2].Value
$runDir = Join-Path $data "execucoes\$id"
$estado = Join-Path $runDir 'estado.json'
$atividade = Join-Path $runDir 'atividade.jsonl'
$raw = Join-Path $saida "$id\execucao\events.jsonl"

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
    return Wait-Until {
        foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) {
            $h = Get-AgentWindow $q.ProcessId
            if (Test-WindowShown $h) { return [pscustomobject]@{ Jvm = [int]$q.ProcessId; Handle = $h } }
        }
    } 40 'janela do Agent'
}
function Open-Activity($w) {
    [NkUser32]::ForceForeground($w.Handle) | Out-Null
    [System.Windows.Forms.SendKeys]::SendWait('%e')
    $dlg = Wait-Until { $h = Get-AgentWindow $w.Jvm 'Execução local'; if (Test-WindowShown $h) { $h } } 20 'janela Execução local'
    Start-Sleep -Milliseconds 600
    [NkUser32]::ForceForeground($dlg) | Out-Null
    [System.Windows.Forms.SendKeys]::SendWait('%t')
    Start-Sleep -Milliseconds 1200
    return $dlg
}
function Exit-Agent($w, $dlg) {
    if ($dlg) { [NkUser32]::ForceForeground($dlg) | Out-Null; [System.Windows.Forms.SendKeys]::SendWait('{ESC}'); Start-Sleep -Milliseconds 800 }
    [NkUser32]::ForceForeground($w.Handle) | Out-Null
    [System.Windows.Forms.SendKeys]::SendWait('%s')
    return Wait-Until { -not (Get-Process -Id $w.Jvm -ErrorAction SilentlyContinue) } 40 'Agent saiu'
}

# ---------- Agent empacotado: adoção e atividade ao vivo ----------
$w = Start-Agent
Check "Agent empacotado abriu sem Java no PATH" ($null -ne $w)
$adopted = Wait-Until { (Get-Content -Raw $estado) -match '"adopted": true' } 20 'adoção'
Check "execução ADOTADA (processo vivo, sem relançar)" ([bool]$adopted -and [bool](Get-Process -Id $procPid -ErrorAction SilentlyContinue))
$dlg = Open-Activity $w
Check "aba Atividade aberta pelo teclado (Alt+E, Alt+T)" ($dlg -ne [IntPtr]::Zero)
$counts = @()
for ($i = 1; $i -le 3; $i++) {
    if (-not (Get-Process -Id $procPid -ErrorAction SilentlyContinue)) { break }
    $n = (Activity-Lines $atividade | Where-Object { $_ -match '"source":"FRAMEWORK"' }).Count
    $counts += $n
    & $wincap -ProcessId $w.Jvm -Out (Join-Path $Out ("P-OBS-0{0}-durante.png" -f $i)) -TitleLike 'Execução local' | Out-Null
    Write-Host ("  durante {0}: {1} eventos do Framework guardados; processo vivo" -f $i, $n)
    Start-Sleep -Seconds 4
}
Check ("atividade cresce DURANTE a execução adotada (" + ($counts -join ' → ') + ")") ($counts.Count -ge 2 -and $counts[-1] -gt $counts[0])
$done = Wait-Until { (Get-Content -Raw $estado) -match '"state": "SUCCEEDED"' } 120 'fim pelo protocolo'
Check "fim pelo protocolo do supervisor: SUCCEEDED (adotada, summary.json COMPLETED)" ([bool]$done)
$closed = Wait-Until { (Activity-Lines $atividade | Select-Object -Last 1) -match '"event_code":"COLLECTION_COMPLETED"' } 30 'coleta encerrada'
Check "coleta encerrada (COLLECTION_COMPLETED)" ([bool]$closed)
Start-Sleep -Seconds 1
& $wincap -ProcessId $w.Jvm -Out (Join-Path $Out 'P-OBS-04-concluida.png') -TitleLike 'Execução local' | Out-Null
$lines = Activity-Lines $atividade
$seqs = @($lines | ForEach-Object { [long]([regex]::Match($_, '"sequence":(\d+)').Groups[1].Value) })
$contiguous = $true
for ($i = 0; $i -lt $seqs.Count; $i++) { if ($seqs[$i] -ne $i + 1) { $contiguous = $false } }
$fwPos = @($lines | Where-Object { $_ -match '"source":"FRAMEWORK"' } | ForEach-Object { [regex]::Match($_, '"source_position":(\d+)').Groups[1].Value })
$rawCount = @([IO.File]::ReadAllLines($raw) | Where-Object { $_ -ne '' }).Count
Check ("sequência contínua 1..{0}; Framework {1} eventos = {2} linhas originais, sem repetição" -f $seqs.Count, $fwPos.Count, $rawCount) `
    ($contiguous -and $fwPos.Count -eq $rawCount -and (@($fwPos | Select-Object -Unique)).Count -eq $fwPos.Count)
Check "a linha do tempo tem RECONCILE e ADOPTED do supervisor" ((($lines -join "`n") -match '"event_code":"RECONCILE"') -and (($lines -join "`n") -match '"event_code":"ADOPTED"'))
$gone = Exit-Agent $w $dlg
Check "Agent saiu (nenhuma execução ativa)" ([bool]$gone)

# ---------- reabrir: histórico ----------
$before = (Get-FileHash -Algorithm SHA256 $atividade).Hash
$w2 = Start-Agent
$dlg2 = Open-Activity $w2
Start-Sleep -Seconds 2
& $wincap -ProcessId $w2.Jvm -Out (Join-Path $Out 'P-OBS-05-historico-apos-reabrir.png') -TitleLike 'Execução local' | Out-Null
Check "histórico depois de reabrir: atividade.jsonl intacto (nada recoletado nem duplicado)" ((Get-FileHash -Algorithm SHA256 $atividade).Hash -eq $before)
$gone2 = Exit-Agent $w2 $dlg2
Check "Agent saiu de novo" ([bool]$gone2)
$others = @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine -like '*main.py --output*' -and $_.CommandLine -like "*$id*" })
Check "nenhum processo relançado" ($others.Count -eq 0)

Copy-Item $atividade (Join-Path $Out 'atividade-execucao.jsonl')
Copy-Item (Join-Path $runDir 'eventos.jsonl') (Join-Path $Out 'eventos-execucao.jsonl')
Copy-Item $estado (Join-Path $Out 'estado-execucao.json')
$results.Add(("RESULTADO: {0} verificações, {1} falha(s)" -f $results.Count, $fails))
$results | Set-Content -Encoding UTF8 (Join-Path $Out 'resultado.txt')
Write-Host ("RESULTADO: {0} falha(s) · evidências em {1}" -f $fails, $Out)
exit $(if ($fails -eq 0) { 0 } else { 2 })
