<#
.SYNOPSIS
  DESKTOP-001 no PACOTE do Agent (executável empacotado, sem Java no PATH): queda simulada no meio de uma execução,
  adoção do processo na abertura, "Sair" com confirmação ("Voltar" mantém tudo; "Encerrar execução e sair" encerra só
  a árvore possuída) e nenhum relançamento.

.DESCRIPTION
  1. Extrai o ZIP numa pasta nova, cria um pacote de laboratório (lab_lento.py: processo + filho que esperam).
  2. "Queda": o roteiro Java (DesktopHomologation semear) lança o processo e grava o registro RUNNING, como o Agent
     teria feito antes de cair.
  3. Abre o Agent empacotado sobre o mesmo diretório: a execução precisa ser ADOTADA (registro e log).
  4. Alt+S → confirmação → Voltar (Alt+V): o Agent e o processo continuam. Alt+S → Encerrar execução e sair (Alt+E): o
     processo e o filho são encerrados, o registro fica CANCELLED, o Agent sai. Nenhum processo de laboratório novo aparece.
  Não use o computador durante a execução (teclas e foco de janela).
#>
param(
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$TestClasspath,
    [string]$Zip,
    [string]$Out = (Join-Path ([IO.Path]::GetTempPath()) ("nekkora-desktop001-pacote-" + (Get-Date -Format 'yyyyMMdd-HHmmss')))
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

# ---------- pacote do Agent (pasta nova, fora do repositório) ----------
$expected = ((Get-Content "$Zip.sha256") -split '\s+')[0]
Check "SHA-256 do ZIP confere" ((Get-FileHash -Algorithm SHA256 $Zip).Hash.ToLowerInvariant() -eq $expected)
$app = Join-Path $Out 'agent'
Expand-Archive -Path $Zip -DestinationPath $app -Force
$exe = (Get-ChildItem -Recurse -Filter 'Nekkora Agent.exe' $app | Select-Object -First 1).FullName

# ---------- pacote de laboratório (hash do texto com LF, como o Agent confere) ----------
$lab = Join-Path $Out 'lab-lento'
New-Item -ItemType Directory -Force $lab | Out-Null
$files = [ordered]@{}
foreach ($f in 'lab_lento.py', 'lab_comum.py') {
    Copy-Item (Join-Path $root "tools\lab\scripts\$f") (Join-Path $lab $f)
    $text = [IO.File]::ReadAllText((Join-Path $lab $f), [Text.Encoding]::UTF8).Replace("`r`n", "`n")
    $sha = [Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($text))
    $files[$f] = 'sha256:' + (($sha | ForEach-Object { $_.ToString('x2') }) -join '')
}
$desc = [ordered]@{ schema = 'nekkora-agent/lab-package'; version = '1.0.0'; name = 'Laboratorio lento'; entry = 'lab_lento.py'; files = $files }
[IO.File]::WriteAllText((Join-Path $lab 'nekkora-agent-lab.json'), ($desc | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding $false))

# ---------- "queda": processo vivo + registro RUNNING ----------
$data = Join-Path $Out 'dados'
$saida = Join-Path $Out 'saida'
New-Item -ItemType Directory -Force $data, $saida | Out-Null
$seed = & java -cp $TestClasspath com.nekkora.agent.ui.DesktopHomologation semear (Join-Path $Out 'semear') $data $lab $Python $saida 2>&1
$m = [regex]::Match(($seed -join "`n"), 'SEMEADO (ex-[0-9a-f]{32}) pid=(\d+)')
Check "queda simulada: execução semeada (processo vivo + registro RUNNING)" $m.Success
$id = $m.Groups[1].Value
$pidsFile = Join-Path $saida "$id\pids.json"
Wait-Until { Test-Path $pidsFile } 30 'pids.json' | Out-Null
$pids = Get-Content -Raw $pidsFile | ConvertFrom-Json
$labPids = @([int]$m.Groups[2].Value, [int]$pids.pai, [int]$pids.filho)
Check "processo de laboratório e filho vivos antes de abrir o Agent" ((@($labPids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })).Count -eq 3)
$estado = Join-Path $data "execucoes\$id\estado.json"

# ---------- abrir o Agent empacotado: adoção ----------
$env0 = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME; DATA = $env:NEKKORA_AGENT_DATA_DIR; HOST = $env:COMPUTERNAME }
# nome fictício: as capturas não mostram o nome real da máquina
$env:NEKKORA_AGENT_DATA_DIR = $data; $env:PATH = 'C:\Windows\System32;C:\Windows'; $env:COMPUTERNAME = 'LAB-AGENT-01'; Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
try { $proc = Start-Process -FilePath $exe -PassThru; $null = $proc.Handle }
finally { $env:PATH = $env0.PATH; $env:COMPUTERNAME = $env0.HOST; if ($env0.JAVA_HOME) { $env:JAVA_HOME = $env0.JAVA_HOME }; if ($env0.DATA) { $env:NEKKORA_AGENT_DATA_DIR = $env0.DATA } else { Remove-Item Env:NEKKORA_AGENT_DATA_DIR } }
$w = Wait-Until {
    foreach ($p in @(Get-CimInstance Win32_Process -Filter "Name='Nekkora Agent.exe'" | Where-Object { $_.ExecutablePath -eq $exe })) {
        $h = Get-AgentWindow $p.ProcessId
        if (Test-WindowShown $h) { return [pscustomobject]@{ Jvm = [int]$p.ProcessId; Handle = $h } }
    }
} 30 'janela do Agent'
Check "Agent empacotado abriu" ($null -ne $w)
$adopted = Wait-Until { (Get-Content -Raw $estado) -match '"adopted": true' } 15 'adoção'
Check "execução ADOTADA na abertura (registro adopted=true, ainda RUNNING)" ([bool]$adopted -and ((Get-Content -Raw $estado) -match '"state": "RUNNING"'))
$log = Join-Path $data 'logs\agent.log'
Check "log registra a reconciliação com adoção (só códigos)" ((Get-Content -Raw $log) -match 'EXEC_RECONCILED code=AGT-EXE-025 records=1 adopted=true')
Start-Sleep -Milliseconds 1500
& $wincap -ProcessId $w.Jvm -Out (Join-Path $Out 'P01-agent-adotou.png') -TitleLike 'Nekkora Agent' | Out-Null

function Wait-ExitConfirmation {
    Wait-Until {
        foreach ($h in [NkUser32]::Windows([uint32]$w.Jvm, $true)) { if ([NkUser32]::Title($h) -eq 'Sair do Nekkora Agent?') { return $h } }
    } 15 'confirmação de saída'
}
function Ask-Exit {
    [NkUser32]::ForceForeground($w.Handle) | Out-Null
    [System.Windows.Forms.SendKeys]::SendWait('%s')
    return Wait-ExitConfirmation
}

# ---------- Sair → confirmação → Voltar (Alt+V): nada muda ----------
$dlg = Ask-Exit
Check "Sair com execução em andamento pede confirmação" ($null -ne $dlg)
& $wincap -ProcessId $w.Jvm -Out (Join-Path $Out 'P02-confirmacao-de-saida.png') -TitleLike 'Sair do Nekkora Agent?' | Out-Null
[NkUser32]::ForceForeground($dlg) | Out-Null
[System.Windows.Forms.SendKeys]::SendWait('%v')
Start-Sleep -Seconds 2
Check "Voltar (Alt+V): o Agent continua aberto e o processo continua vivo" ([bool](Get-Process -Id $w.Jvm -ErrorAction SilentlyContinue) -and [bool](Get-Process -Id $pids.filho -ErrorAction SilentlyContinue))

# ---------- Sair → Encerrar execução e sair (Alt+E) ----------
$dlg = Ask-Exit
[NkUser32]::ForceForeground($dlg) | Out-Null
[System.Windows.Forms.SendKeys]::SendWait('%e')
$gone = Wait-Until { -not (Get-Process -Id $w.Jvm -ErrorAction SilentlyContinue) } 40 'Agent saiu'
Check "Encerrar execução e sair (Alt+E): o Agent saiu" ([bool]$gone)
Check "processo de laboratório E filho encerrados (árvore possuída)" ((@($labPids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })).Count -eq 0)
Check "registro final CANCELLED (AGT-EXE-024)" (((Get-Content -Raw $estado) -match '"state": "CANCELLED"') -and ((Get-Content -Raw $estado) -match 'AGT-EXE-024'))
$others = @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine -like '*lab_lento.py*' })
Check "nenhum processo de laboratório relançado" ($others.Count -eq 0)
Check "log com LIFE_EXIT (saída ordenada)" ((Get-Content -Raw $log) -match 'LIFE_EXIT')
Copy-Item $estado (Join-Path $Out 'estado-execucao.json')
Copy-Item (Join-Path $data "execucoes\$id\eventos.jsonl") (Join-Path $Out 'eventos-execucao.jsonl')
$results.Add(("RESULTADO: {0} verificações, {1} falha(s)" -f $results.Count, $fails))
$results | Set-Content -Encoding UTF8 (Join-Path $Out 'resultado.txt')
Write-Host ("RESULTADO: {0} falha(s) · evidências em {1}" -f $fails, $Out)
exit $(if ($fails -eq 0) { 0 } else { 2 })
