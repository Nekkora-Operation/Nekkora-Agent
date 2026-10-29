<#
.SYNOPSIS
  Gera a distribuição Windows de laboratório do Nekkora Agent (imagem de aplicativo + runtime Java incluído).

.DESCRIPTION
  1. Confere a preparação do design system (.design-system/design-system.lock.properties).
  2. Constrói e testa (mvnw clean verify) — use -SkipTests apenas para diagnóstico local.
  3. Gera o ícone a partir da marca da biblioteca visual (tools/IconExport.java).
  4. Monta um runtime Java reduzido com jlink (módulos listados em $Modules, conferidos com jdeps).
  5. Cria a imagem de aplicativo com jpackage ("Nekkora Agent\Nekkora Agent.exe"), sem instalador.
  6. Copia avisos e licenças, LEIA-ME e a lista de versões/dependências com SHA-256.
  7. Compacta em ZIP e grava o SHA-256 do ZIP (-NoZip: só a pasta, com o SHA-256 de cada arquivo em VERSOES.txt).

  Saída: target\dist\nekkora-agent-<versão>-windows-x64-lab.zip (+ .sha256) e target\dist\Nekkora Agent\ (pasta).
  O pacote NÃO é assinado digitalmente e não é instalador de produção.
#>
[CmdletBinding()]
param([switch]$SkipTests, [switch]$NoZip)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# AGENT-LEAN-001: -Xms16m — sem ele o heap inicial é 1/64 da RAM (limitado ao -Xmx256m) e fica todo comprometido na
# abertura (memória privada 350 MB → 108 MB medidos; teto -Xmx256m e GC serial inalterados; docs/agent-lean-001).
# Módulos do runtime: o que o jdeps encontra + o que é carregado por reflexão/serviço.
#  - jdk.unsupported: ExtendedOpenOption.NOSHARE_DELETE (trava) e FlatLaf
#  - java.prefs: Preferences (o ThemeManager a usa; o Agent a redireciona para memória)
#  - jdk.accessibility: Java Access Bridge (leitores de tela no Windows)
#  - java.net.http: cliente HTTPS do canal da máquina (AGENT-PLATFORM-001; só o pacote com.nekkora.agent.platform o usa)
#  - jdk.crypto.ec: curvas elípticas do TLS (ECDHE/X25519 e certificados EC) no JDK 21 (carregado como provedor)
$Modules = @('java.base', 'java.desktop', 'java.prefs', 'java.logging', 'jdk.unsupported', 'jdk.accessibility', 'java.net.http', 'jdk.crypto.ec')

function Invoke-Checked([string]$what, [scriptblock]$block) {
    & $block
    if ($LASTEXITCODE -ne 0) { throw "$what falhou ($LASTEXITCODE)" }
}

$lock = Join-Path $root '.design-system\design-system.lock.properties'
if (-not (Test-Path $lock)) { throw "Rode antes: tools\prepare-design-system.ps1" }
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source) }
$jbin = Join-Path $javaHome 'bin'
foreach ($t in 'jlink.exe', 'jpackage.exe', 'jdeps.exe') { if (-not (Test-Path (Join-Path $jbin $t))) { throw "JDK sem $t em $jbin" } }

Write-Host "[1/7] Construção (mvnw clean verify)"
$mvnArgs = '-B -q clean verify'
if ($SkipTests) { $mvnArgs = '-B -q -DskipTests clean verify' }
Invoke-Checked 'mvnw' { cmd.exe /d /c ".\mvnw.cmd $mvnArgs" }

$props = @{}
foreach ($l in Get-Content -Encoding UTF8 'target\classes\com\nekkora\agent\agent-build.properties') {
    if ($l -match '^([^=]+)=(.*)$') { $props[$Matches[1]] = $Matches[2] }
}
$version = $props['version']
$pkg = Join-Path $root 'target\package'
$dist = Join-Path $root 'target\dist'
foreach ($d in $pkg, $dist) { if (Test-Path $d) { Remove-Item -Recurse -Force $d } }
New-Item -ItemType Directory -Force (Join-Path $pkg 'input\lib'), $dist | Out-Null
Copy-Item 'target\nekkora-agent.jar' (Join-Path $pkg 'input')
Copy-Item 'target\lib\*.jar' (Join-Path $pkg 'input\lib')
# DLL nativa do FlatLaf (bordas/título da janela no Windows) junto do app: sem extração para %TEMP% em tempo de execução
Add-Type -AssemblyName System.IO.Compression.FileSystem
$flatJar = Get-ChildItem 'target\lib\flatlaf-*.jar' | Select-Object -First 1
$flatVersion = $flatJar.BaseName.Substring('flatlaf-'.Length)
$zf = [System.IO.Compression.ZipFile]::OpenRead($flatJar.FullName)
try {
    $dll = $zf.GetEntry('com/formdev/flatlaf/natives/flatlaf-windows-x86_64.dll')
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($dll, (Join-Path $pkg "input\lib\flatlaf-$flatVersion-windows-x86_64.dll"), $true)
} finally { $zf.Dispose() }

Write-Host "[2/7] Ícone"
Invoke-Checked 'IconExport' { & (Join-Path $jbin 'java.exe') '-Djava.awt.headless=true' -cp "target\lib\*" tools\IconExport.java (Join-Path $pkg 'nekkora-agent.ico') }

Write-Host "[3/7] Conferência de módulos (jdeps)"
$found = (& (Join-Path $jbin 'jdeps.exe') --print-module-deps --ignore-missing-deps --multi-release 21 `
        --class-path "$pkg\input\lib\*" "$pkg\input\nekkora-agent.jar").Trim()
if ($LASTEXITCODE -ne 0) { throw "jdeps falhou" }
foreach ($m in $found.Split(',')) { if ($Modules -notcontains $m) { throw "jdeps encontrou o módulo '$m', ausente da lista do runtime" } }
Write-Host "     jdeps: $found"

Write-Host "[4/7] Runtime (jlink)"
$runtime = Join-Path $pkg 'runtime'
Invoke-Checked 'jlink' { & (Join-Path $jbin 'jlink.exe') --add-modules ($Modules -join ',') --strip-debug --no-header-files --no-man-pages `
        --compress=zip-6 --output $runtime }

Write-Host "[5/7] Imagem de aplicativo (jpackage)"
$image = Join-Path $pkg 'image'
Invoke-Checked 'jpackage' { & (Join-Path $jbin 'jpackage.exe') --type app-image --name 'Nekkora Agent' --app-version $version `
        --vendor 'Nekkora' --description 'Nekkora Agent (laboratório)' --copyright 'Nekkora' `
        --icon (Join-Path $pkg 'nekkora-agent.ico') --input (Join-Path $pkg 'input') --main-jar 'nekkora-agent.jar' `
        --main-class 'com.nekkora.agent.AgentMain' --runtime-image $runtime --dest $image `
        --java-options '-Djava.util.prefs.PreferencesFactory=com.nekkora.agent.storage.IsolatedPreferencesFactory' `
        --java-options '-Dflatlaf.nativeLibraryPath=$APPDIR\lib' --java-options '-XX:+UseSerialGC' --java-options '-Xms16m' --java-options '-Xmx256m' --java-options '-XX:TieredStopAtLevel=1' }
$app = Join-Path $image 'Nekkora Agent'

Write-Host "[6/7] Licenças, LEIA-ME e versões"
$legal = Join-Path $app 'legal'
New-Item -ItemType Directory -Force $legal | Out-Null
Copy-Item 'THIRD-PARTY-NOTICES.md' $legal
Copy-Item 'tools\distribution\LEIA-ME.txt' $app
function Export-Entry([string]$jar, [string]$entry, [string]$target) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $e = $z.GetEntry($entry)
        if ($null -eq $e) { throw "$entry ausente em $jar" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
    } finally { $z.Dispose() }
}
$flat = Get-ChildItem "$pkg\input\lib\flatlaf-*.jar" | Select-Object -First 1
$ui = Get-ChildItem "$pkg\input\lib\nekkora-swing-ui-*.jar" | Select-Object -First 1
Export-Entry $flat.FullName 'META-INF/LICENSE' (Join-Path $legal 'FlatLaf-LICENSE-Apache-2.0.txt')
$jna = Get-ChildItem "$pkg\input\lib\jna-[0-9]*.jar" | Select-Object -First 1
Export-Entry $jna.FullName 'META-INF/AL2.0' (Join-Path $legal 'JNA-LICENSE-Apache-2.0.txt')
foreach ($f in 'Inter', 'JetBrainsMono', 'Sora') { Export-Entry $ui.FullName "com/nekkora/ui/fonts/OFL-$f.txt" (Join-Path $legal "OFL-$f.txt") }
# o runtime já traz runtime\legal\ (OpenJDK, GPLv2 com Classpath Exception) gerado pelo jlink

$release = Get-Content (Join-Path $runtime 'release') | Where-Object { $_ -match '^(JAVA_VERSION|JAVA_RUNTIME_VERSION|IMPLEMENTOR|OS_ARCH|MODULES)=' }
$lines = @("Nekkora Agent $version - distribuição de laboratório (Windows x64, sem assinatura digital)",
    "commit: $($props['commit'])$(if ($props['dirty'] -eq 'true') { ' (com alterações locais)' })",
    "data do commit: $($props['commitTime'])",
    "gerado em: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))",
    "",
    "Bibliotecas (app\lib) com SHA-256:")
foreach ($j in Get-ChildItem "$pkg\input\nekkora-agent.jar", "$pkg\input\lib\*.jar", "$pkg\input\lib\*.dll") {
    $lines += "  $($j.Name)  $((Get-FileHash -Algorithm SHA256 $j.FullName).Hash.ToLowerInvariant())"
}
$lines += ""
$lines += "Origem do design system (nekkora-design-tokens, nekkora-swing-ui):"
$lines += (Get-Content -Encoding UTF8 $lock | Where-Object { $_ -notmatch '^#' } | ForEach-Object { "  $_" })
$lines += ""
$lines += "Runtime Java incluído (jlink):"
$lines += ($release | ForEach-Object { "  $_" })
$lines += ""
$lines += "Biblioteca nativa: app\lib\flatlaf-$flatVersion-windows-x86_64.dll (extraída do JAR do FlatLaf; -Dflatlaf.nativeLibraryPath=`$APPDIR\lib)"
$lines += "Opções da JVM: -Dflatlaf.nativeLibraryPath=`$APPDIR\lib -XX:+UseSerialGC -Xms16m -Xmx256m -XX:TieredStopAtLevel=1 -Djava.util.prefs.PreferencesFactory=com.nekkora.agent.storage.IsolatedPreferencesFactory"
[System.IO.File]::WriteAllLines((Join-Path $app 'VERSOES.txt'), [string[]]$lines, (New-Object System.Text.UTF8Encoding $false))

if ($NoZip) {
    # AGENT-PLATFORM-001: entrega por PR, sem ZIP; a pasta da imagem é o artefato de homologação
    Copy-Item -Recurse $app (Join-Path $dist 'Nekkora Agent')
    Copy-Item (Join-Path $app 'VERSOES.txt') (Join-Path $dist 'VERSOES.txt')
    Write-Host "[7/7] sem ZIP (-NoZip): pasta $(Join-Path $dist 'Nekkora Agent')"
    exit 0
}
Write-Host "[7/7] ZIP e SHA-256"
$zipName = "nekkora-agent-$version-windows-x64-lab.zip"
$zip = Join-Path $dist $zipName
Copy-Item -Recurse $app (Join-Path $dist 'Nekkora Agent')
[System.IO.Compression.ZipFile]::CreateFromDirectory($app, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)
$hash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText("$zip.sha256", "$hash  $zipName`n", (New-Object System.Text.UTF8Encoding $false))
Copy-Item (Join-Path $app 'VERSOES.txt') (Join-Path $dist 'VERSOES.txt')

$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
$folder = [math]::Round(((Get-ChildItem -Recurse -File $app | Measure-Object Length -Sum).Sum) / 1MB, 1)
Write-Host ""
Write-Host "Pacote: $zip ($size MiB; pasta $folder MiB)"
Write-Host "SHA-256: $hash"
