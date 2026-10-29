<#
.SYNOPSIS
  A13 (parte manual): suspensão e retorno do Windows com o Nekkora Agent aberto.

.DESCRIPTION
  Roteiro:
    1. Abra o Agent do pacote (de preferência com a janela oculta na bandeja).
    2. .\suspend-check.ps1 -Fase antes
    3. Suspenda o Windows (Iniciar > Ligar/Desligar > Suspender), aguarde pelo menos 1 minuto e retome.
    4. .\suspend-check.ps1 -Fase depois
  O "depois" compara com o "antes": mesmo processo (PIDs), um único ícone do Agent na bandeja,
  nenhum processo duplicado, logs sem novo LIFE_START. Grava suspend-antes.json / suspend-depois.json
  e imprime o veredito. Não toca em dados de outros produtos.

.PARAMETER DataDir
  Diretório de dados do Agent em teste (padrão: %LOCALAPPDATA%\Nekkora\Agent ou NEKKORA_AGENT_DATA_DIR).
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('antes', 'depois')][string]$Fase,
    [string]$DataDir = $(if ($env:NEKKORA_AGENT_DATA_DIR) { $env:NEKKORA_AGENT_DATA_DIR } else { Join-Path $env:LOCALAPPDATA 'Nekkora\Agent' }),
    [string]$Out = (Get-Location).Path
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'NkHomolog.psm1') -Force

$procs = @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Select-Object ProcessId, ParentProcessId, CreationDate)
Open-TrayOverflow | Out-Null
Start-Sleep -Milliseconds 900
$icons = @(Find-TrayButtons).Count
[System.Windows.Forms.SendKeys]::SendWait('{ESC}')
$log = Join-Path $DataDir 'logs\agent.log'
$starts = if (Test-Path $log) { ([regex]::Matches((Get-Content -Raw $log), 'LIFE_START')).Count } else { -1 }
$snap = [ordered]@{ fase = $Fase; quando = (Get-Date).ToString('s'); processos = $procs; iconesNaBandeja = $icons; lifeStarts = $starts }
$file = Join-Path $Out "suspend-$Fase.json"
$snap | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $file
Write-Host "Gravado: $file (processos $($procs.Count), ícones $icons, LIFE_START $starts)"

if ($Fase -eq 'depois') {
    $antes = Get-Content -Raw (Join-Path $Out 'suspend-antes.json') | ConvertFrom-Json
    $mesmos = (@($antes.processos.ProcessId) -join ',') -eq (@($procs.ProcessId) -join ',')
    $ok = $mesmos -and ($icons -eq 1) -and ($procs.Count -eq 2) -and ($starts -eq $antes.lifeStarts)
    Write-Host ("Veredito: {0} (mesmos processos: {1}; ícones: {2}; processos: {3}; novos inícios: {4})" -f `
        $(if ($ok) { 'APROVADO' } else { 'FALHOU' }), $mesmos, $icons, $procs.Count, ($starts - $antes.lifeStarts))
}
