<#
  INTEGRATION-CONSOLIDATION-001, C4 item 8 — troca de destino com o Agent empacotado e a API reais.
  Sobre os dados do laboratório (vínculo ativo, credencial rotacionada pela jornada complementar):
    D1 com a configuração apontando OUTRA origem https, o Agent abre e nada chega ao canal da máquina (nginx sem nenhuma
       requisição /agent/ nova durante 75 s); o card mostra o endereço alterado (captura)
    D2 a configuração volta à origem do pareamento: a presença retoma pela credencial guardada (requisições /agent/ novas
       e heartbeat no banco avança), sem novo pareamento
  Uso: powershell -File tools/platform-lab/destino.ps1 -Id agentlab-003 -Python <python do Framework 0.3.0>
#>
param(
    [string]$Id = 'agentlab-003',
    [Parameter(Mandatory = $true)][string]$Python
)
$ErrorActionPreference = 'Continue'
Set-StrictMode -Version 3.0
$Prefixo = 'destino-'
. (Join-Path $PSScriptRoot 'LabComum.ps1')

function Agent-Requests {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { return @(& docker @composeArgs logs --no-color nginx 2>$null | Where-Object { $_ -match '"[A-Z]+ /agent/' }).Count } finally { $ErrorActionPreference = $old }
}
function Heartbeat { return (Sql "SELECT coalesce(max(reported_at)::text, '-') FROM agent_presence") }
function Stop-Agent { Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force } ; Start-Sleep -Seconds 2 }

$cfgFile = Join-Path $script:data 'config\agent-config.json'
$original = [IO.File]::ReadAllText($cfgFile)
$origin = 'https://localhost:' + $secrets['E2E_PORTA']
if (-not $original.Contains($origin)) { throw 'a configuração do laboratório não tem a origem do pareamento' }
Stop-Agent
try {
    # ---------- D1: outra origem ----------
    [IO.File]::WriteAllText($cfgFile, $original.Replace($origin, 'https://localhost:55999'), (New-Object System.Text.UTF8Encoding $false))
    $r0 = Agent-Requests
    $hb0 = Heartbeat
    $w = Start-Agent
    Note '[REAL]' "D1 Agent aberto com a configuração em https://localhost:55999 (pareado em $origin)"
    Start-Sleep -Seconds 75
    Capture 'd1-endereco-alterado'
    $r1 = Agent-Requests
    $hb1 = Heartbeat
    Check "D1 nenhuma requisição ao canal da máquina com o destino alterado (nginx /agent/: $r0 -> $r1) e presença parada ($hb0 -> $hb1)" ($r1 -eq $r0 -and $hb1 -eq $hb0)
    Stop-Agent
    # ---------- D2: origem do pareamento de volta ----------
    [IO.File]::WriteAllText($cfgFile, $original, (New-Object System.Text.UTF8Encoding $false))
    $w = Start-Agent
    Wait-Until { (Agent-Requests) -gt $r1 } 120 'requisição nova ao canal' | Out-Null
    Start-Sleep -Seconds 5
    $r2 = Agent-Requests
    $hb2 = Heartbeat
    Check "D2 com a origem do pareamento a presença retoma pela credencial guardada (nginx /agent/: $r1 -> $r2; heartbeat $hb1 -> $hb2), sem novo pareamento" ($r2 -gt $r1 -and $hb2 -ne $hb1 -and (Log-Count 'PLATFORM_PAIRED') -le 1)
    Capture 'd2-retomado'
} catch {
    Check "jornada interrompida por exceção: $($_.Exception.Message)" $false
} finally {
    [IO.File]::WriteAllText($cfgFile, $original, (New-Object System.Text.UTF8Encoding $false))
    Stop-Agent
}
$ok = @($checks | Where-Object { $_.ok }).Count
$checks | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $Out 'resultado.json')
$timeline | Set-Content -Encoding UTF8 (Join-Path $Out 'linha-do-tempo.txt')
Write-Host ("DESTINO: {0}/{1} conferências OK - {2}" -f $ok, $checks.Count, $Out)
