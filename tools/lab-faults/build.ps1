<#
  AGENT-PYTHON-001: gera o artefato EXCLUSIVO DE LABORATÓRIO target\lab-faults\nekkora-agent-lab-falhas.jar, a partir de
  tools\lab-faults\src. A distribuição normal não leva este JAR (tools\package-windows.ps1 confere e recusa).

  Uso (depois de tools\package-windows.ps1, no mesmo clone):
    powershell -File tools\lab-faults\build.ps1

  Na cópia de laboratório do Agent, o roteiro do laboratório:
    1) copia o JAR para "<cópia>\app\";
    2) acrescenta ao "Nekkora Agent.cfg":
         app.classpath=$APPDIR\nekkora-agent-lab-falhas.jar
         java-options=-Dnekkora.agent.lab.falha=quedaAposStarting | perderConfirmacaoDecisao
         java-options=-Dnekkora.agent.lab.registro=<arquivo do registro>
  Sem o JAR, a propriedade não faz nada. Sem a propriedade, o JAR não faz nada.
#>
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$agentJar = Join-Path $root 'target\nekkora-agent.jar'
if (-not (Test-Path $agentJar)) { throw "rode antes tools\package-windows.ps1 (falta $agentJar)" }
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source) }
$jbin = Join-Path $javaHome 'bin'
$out = Join-Path $root 'target\lab-faults'
$classes = Join-Path $out 'classes'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $classes | Out-Null
$src = Join-Path $PSScriptRoot 'src'
$javaFiles = @(Get-ChildItem -Recurse -File -Filter '*.java' $src | ForEach-Object { $_.FullName })
& (Join-Path $jbin 'javac.exe') --release 21 -encoding UTF-8 -cp $agentJar -d $classes @javaFiles
if ($LASTEXITCODE -ne 0) { throw 'javac falhou' }
Copy-Item -Recurse (Join-Path $src 'META-INF') $classes
$jar = Join-Path $out 'nekkora-agent-lab-falhas.jar'
& (Join-Path $jbin 'jar.exe') --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar falhou' }
$hash = (Get-FileHash -Algorithm SHA256 $jar).Hash.ToLowerInvariant()
Write-Host "artefato de laboratório: $jar"
Write-Host "sha256: $hash"
