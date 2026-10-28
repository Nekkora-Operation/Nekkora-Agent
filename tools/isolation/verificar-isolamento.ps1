<#
.SYNOPSIS
  STUDIO-AGENT-LOCAL-001 (ISO-R): confere, num Agent REAL em execução, que ele não expõe nem usa nenhum canal: nenhuma
  porta TCP (escuta ou conexão) nem UDP do processo, nenhum Unix Domain Socket (ponto de reparse AF_UNIX) nas pastas do
  Agent e nenhum pipe nomeado novo. Roda ANTES do canal local existir (linha de base) e, depois da aprovação, de novo
  com o canal desligado (padrão) e ligado.

.DESCRIPTION
  Amostra a cada 500 ms durante -Seconds:
    - Get-NetTCPConnection -OwningProcess <pid> (qualquer estado, inclusive Listen) e Get-NetUDPEndpoint -OwningProcess;
    - pontos de reparse (Attributes ReparsePoint) em -DataDir e na pasta do executável (um AF_UNIX no Windows é um);
    - pipes nomeados (\\.\pipe\) que não existiam antes. Com -Quiet (nada mais sendo lançado durante a medição) a
      diferença é atribuída ao Agent; sem -Quiet (execução de automação em paralelo: o Chrome cria pipes) ela só é
      informada.
  Escreve <Out>\isolamento.json e devolve código 0 (isolado) ou 1 (canal encontrado). Não altera nada no sistema.

.PARAMETER ProcessId   PID do Agent já aberto (o executável do jpackage roda a JVM no próprio processo).
.PARAMETER DataDir     Pasta de dados do Agent (NEKKORA_AGENT_DATA_DIR) usada por esse processo.
.PARAMETER Seconds     Duração da amostragem.
.PARAMETER Out         Pasta da evidência.
.PARAMETER Quiet       Nada além do Agent é iniciado durante a medição (atribui os pipes novos ao Agent).
#>
param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][string]$DataDir,
    [int]$Seconds = 20,
    [Parameter(Mandatory = $true)][string]$Out,
    [switch]$Quiet,
    # pipes que já existiam ANTES de abrir o Agent (um por linha); sem ele, a referência é o início da medição
    [string]$PipesBeforeFile
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
New-Item -ItemType Directory -Force -Path $Out | Out-Null

function Get-Pipes { try { return @([System.IO.Directory]::GetFiles('\\.\pipe\') | ForEach-Object { $_.Substring(9) }) } catch { return @() } }

$proc = Get-Process -Id $ProcessId -ErrorAction Stop
$exeDir = Split-Path -Parent $proc.Path
$PipesBefore = if ($PipesBeforeFile -and (Test-Path -LiteralPath $PipesBeforeFile)) { @(Get-Content -LiteralPath $PipesBeforeFile) } else { Get-Pipes }
$before = [System.Collections.Generic.HashSet[string]]::new([string[]]$PipesBefore)
$tcp = @{}; $udp = @{}; $reparse = @{}; $newPipes = @{}; $samples = 0
$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { break }
    $samples++
    foreach ($c in @(Get-NetTCPConnection -OwningProcess $ProcessId -ErrorAction SilentlyContinue)) {
        $tcp["$($c.State) $($c.LocalAddress):$($c.LocalPort) -> $($c.RemoteAddress):$($c.RemotePort)"] = $true
    }
    foreach ($u in @(Get-NetUDPEndpoint -OwningProcess $ProcessId -ErrorAction SilentlyContinue)) { $udp["$($u.LocalAddress):$($u.LocalPort)"] = $true }
    foreach ($root in @($DataDir, $exeDir)) {
        if (Test-Path -LiteralPath $root) {
            foreach ($f in @(Get-ChildItem -LiteralPath $root -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue)) {
                $reparse[$f.FullName.Replace($root, '<' + (Split-Path -Leaf $root) + '>')] = $true
            }
        }
    }
    foreach ($p in Get-Pipes) { if (-not $before.Contains($p)) { $newPipes[$p] = $true } }
    Start-Sleep -Milliseconds 500
}
$alive = [bool](Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
# Atribuição dos pipes novos SEM abri-los (abrir conectaria ao pipe de outro programa): pelo PID que o criador põe no
# nome (mojo.<pid>.… do Chromium, uv\N-<pid> do libuv/Node, PSHost.….<pid>.… do PowerShell; qualquer número do nome que
# seja o PID de um processo vivo). Com o PID do Agent → canal do Agent; com o de outro processo → fora do Agent (só a
# contagem e o nome do processo vão para o relatório); sem PID reconhecível → sem atribuição (com -Quiet, conta contra).
$agentPipes = @(); $unattributed = @(); $others = @{}
$me = [Environment]::UserName
foreach ($p in @($newPipes.Keys | Sort-Object)) {
    $nums = @([regex]::Matches($p, '\d{2,7}') | ForEach-Object { [int64]$_.Value } | Where-Object { $_ -gt 4 -and $_ -lt 4194304 })
    if ($nums -contains $ProcessId) { $agentPipes += $p.Replace($me, '<usuario>'); continue }
    $owner = $null
    foreach ($n in $nums) { $q = Get-Process -Id $n -ErrorAction SilentlyContinue; if ($q) { $owner = $q.ProcessName; break } }
    if ($owner) { $others[$owner] = 1 + $(if ($others.ContainsKey($owner)) { $others[$owner] } else { 0 }) } else { $unattributed += $p.Replace($me, '<usuario>') }
}
$pipeList = @($newPipes.Keys)
$isolated = ($tcp.Count -eq 0) -and ($udp.Count -eq 0) -and ($reparse.Count -eq 0) -and ($agentPipes.Count -eq 0) -and ((-not $Quiet) -or $unattributed.Count -eq 0)
$report = [ordered]@{
    schema         = 'nekkora-agent/isolation-check'
    version        = '1.0.0'
    measured_utc   = (Get-Date).ToUniversalTime().ToString('o')
    seconds        = $Seconds
    samples        = $samples
    agent_alive_at_end = $alive
    quiet          = [bool]$Quiet
    tcp            = @($tcp.Keys | Sort-Object)
    udp            = @($udp.Keys | Sort-Object)
    af_unix_or_reparse = @($reparse.Keys | Sort-Object)
    new_named_pipes_total = $pipeList.Count
    new_named_pipes_agent = @($agentPipes)
    new_named_pipes_other_processes = $others
    new_named_pipes_unattributed = @($unattributed)
    isolated       = $isolated
}
$report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Out 'isolamento.json') -Encoding utf8
Write-Host ("ISO-R: {0} amostras · TCP {1} · UDP {2} · AF_UNIX/reparse {3} · pipes novos {4} (do Agent {5}, de outros processos {6}, sem atribuição {7}) → {8}" -f `
    $samples, $tcp.Count, $udp.Count, $reparse.Count, $pipeList.Count, $agentPipes.Count, ($pipeList.Count - $agentPipes.Count - $unattributed.Count),
    $unattributed.Count, $(if ($isolated) { 'ISOLADO' } else { 'CANAL ENCONTRADO' }))
if ($isolated) { exit 0 } else { exit 1 }
