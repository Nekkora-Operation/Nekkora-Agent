"""Cria run-in-agent-sal.ps1 a partir do roteiro da compat-001 (sem alterar o original): ISO-R ocioso e em execução,
-KillAutomationToo (queda do Agent E da automação) e campos 1.1.0 do estado.json no resultado."""

import sys
from pathlib import Path

src, dst = Path(sys.argv[1]), Path(sys.argv[2])
s = src.read_text(encoding="utf-8-sig")


def rep(old: str, new: str) -> None:
    global s
    assert s.count(old) == 1, (s.count(old), old[:80])
    s = s.replace(old, new)


rep("""    [string]$AgentModule
)""", """    [string]$AgentModule,
    # STUDIO-AGENT-LOCAL-001: roteiro ISO-R (tools/isolation/verificar-isolamento.ps1) — ocioso (atribuído) e durante a execução
    [string]$IsolationScript,
    # STUDIO-AGENT-LOCAL-001: com -KillAgentAfterSeconds, derruba também a automação (queda da máquina simulada, sem resumo)
    [switch]$KillAutomationToo
)""")

rep("""$t0 = Get-Date
if ($GcLog)""", """$pipes0 = @(); try { $pipes0 = @([System.IO.Directory]::GetFiles('\\\\.\\pipe\\') | ForEach-Object { $_.Substring(9) }) } catch { }
$pipesFile = Join-Path $Out "$Label-pipes-antes.txt"; Set-Content -LiteralPath $pipesFile -Value $pipes0 -Encoding UTF8
$t0 = Get-Date
if ($GcLog)""")

rep("""Check ("Agent empacotado abriu sem Java no PATH em {0:N1} s" -f ((Get-Date) - $t0).TotalSeconds) ($null -ne $w)
""", """Check ("Agent empacotado abriu sem Java no PATH em {0:N1} s" -f ((Get-Date) - $t0).TotalSeconds) ($null -ne $w)
if ($IsolationScript) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $IsolationScript -ProcessId $script:jvm -DataDir $script:data -Seconds 15 `
        -Out (Join-Path $Out "$Label-isolamento-ocioso") -Quiet -PipesBeforeFile $pipesFile
    Check "ISO-R com o Agent aberto e ocioso (15 s): nenhuma porta TCP/UDP, nenhum AF_UNIX, nenhum pipe atribuído ao Agent" ($LASTEXITCODE -eq 0)
}
""")

rep("""    Check "execução iniciada pelo botão Iniciar (Alt+I)" (Log-Has 'EXEC_STARTED')
""", """    Check "execução iniciada pelo botão Iniciar (Alt+I)" (Log-Has 'EXEC_STARTED')
    $isoRun = $null
    if ($IsolationScript) {
        $isoRun = Start-Process -FilePath powershell.exe -PassThru -WindowStyle Hidden -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            ('"' + $IsolationScript + '"'), '-ProcessId', $script:jvm, '-DataDir', ('"' + $script:data + '"'), '-Seconds', '12', '-Out',
            ('"' + (Join-Path $Out "$Label-isolamento-em-execucao") + '"'))
    }
""")

rep("""        Check "Agent derrubado à força (Stop-Process) com a execução RUNNING" $true
""", """        Check "Agent derrubado à força (Stop-Process) com a execução RUNNING" $true
        if ($KillAutomationToo) {
            foreach ($q in @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine -like '*main.py*' -and $_.CommandLine -like "*$OutputRoot*" })) {
                & taskkill.exe /PID $q.ProcessId /T /F | Out-Null
            }
            Check "automação derrubada junto (taskkill /T /F): nenhum resultado final publicado" (-not (Test-Path (Join-Path (Join-Path $OutputRoot (Split-Path -Leaf $runDir.FullName)) 'execucao\\summary.json')))
        }
""")

rep("""    if (-not $final) { throw 'tempo esgotado: fim da execução' }
""", """    if (-not $final) { throw 'tempo esgotado: fim da execução' }
    if ($isoRun) {
        $isoRun.WaitForExit()
        $isoJson = Join-Path (Join-Path $Out "$Label-isolamento-em-execucao") 'isolamento.json'
        $iso = if (Test-Path $isoJson) { Get-Content -Raw $isoJson | ConvertFrom-Json } else { $null }
        Check "ISO-R durante a execução (Agent): nenhuma porta TCP/UDP do processo do Agent, nenhum AF_UNIX" ($null -ne $iso -and $iso.tcp.Count -eq 0 -and $iso.udp.Count -eq 0 -and $iso.af_unix_or_reparse.Count -eq 0)
    }
""")

rep("""        run_output = (Sanitize $runOut)""", """        run_output = (Sanitize $runOut)
        # estado.json 1.1.0 (STUDIO-AGENT-LOCAL-001)
        record_version = [regex]::Match($estadoText, '"version"\\s*:\\s*"([0-9.]+)"').Groups[1].Value
        origin = [regex]::Match($estadoText, '"origin"\\s*:\\s*\\{"kind"\\s*:\\s*"([A-Z_]+)"').Groups[1].Value
        recovered = [regex]::Match($estadoText, '"recovered"\\s*:\\s*(true|false)').Groups[1].Value
        cancellation = [regex]::Match($estadoText, '"cancellation"\\s*:\\s*(\\{[^}]*\\})').Groups[1].Value
        progress = [regex]::Match($estadoText, '"progress"\\s*:\\s*(\\{"available"[^\\n]*\\})').Groups[1].Value
        counts = [regex]::Match($estadoText, '"counts"\\s*:\\s*(\\{[^}]*\\})').Groups[1].Value""")

dst.write_bytes(b"\xef\xbb\xbf" + s.replace("\r\n", "\n").replace("\n", "\r\n").encode("utf-8"))
print("ok", dst)
