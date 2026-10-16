<#
.SYNOPSIS
  Prepara as bibliotecas visuais Nekkora (design tokens + Swing UI) a partir do commit fixado do Studio.

.DESCRIPTION
  1. Lê design-system.properties (repositório, commit, versão e módulos).
  2. Faz um checkout novo e destacado (detached) do commit em .design-system/src e confere o hash.
  3. Remove do repositório Maven isolado (.design-system/m2) qualquer artefato com.nekkora anterior.
  4. Constrói e instala somente os módulos indicados (e o POM pai) com -Dmaven.repo.local isolado.
     Os testes do Studio não são executados aqui: eles pertencem ao Studio e alguns dependem da JDK
     (goldens de fonte); o Agent exercita as bibliotecas em seus próprios testes.
  5. Grava .design-system/design-system.lock.properties com o commit e o SHA-256 de cada artefato.

  Os fontes do Studio não são modificados. Nada é publicado. O checkout não é necessário depois da
  construção: o pacote final do Agent inclui os JARs.

.PARAMETER StudioRepository
  Substitui a URL do repositório (por exemplo, um clone local para trabalhar sem rede).
  O commit continua sendo conferido.

.PARAMETER Clean
  Apaga também as dependências de terceiros já baixadas em .design-system/m2.
#>
[CmdletBinding()]
param(
    [string]$StudioRepository,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$root = Split-Path -Parent $PSScriptRoot
$props = @{}
foreach ($line in Get-Content -Encoding UTF8 (Join-Path $root 'design-system.properties')) {
    if ($line -match '^\s*([^#=\s][^=]*)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
}
foreach ($k in 'studio.repository', 'studio.commit', 'studio.version', 'studio.modules') {
    if (-not $props.ContainsKey($k) -or [string]::IsNullOrWhiteSpace($props[$k])) { throw "design-system.properties sem '$k'" }
}
$commit = $props['studio.commit']
if ($commit -notmatch '^[0-9a-f]{40}$') { throw "studio.commit deve ser um hash completo de 40 caracteres" }
$repo = if ($StudioRepository) { $StudioRepository } else { $props['studio.repository'] }
$modules = $props['studio.modules']
$version = $props['studio.version']

$work = Join-Path $root '.design-system'
$src = Join-Path $work 'src'
$m2 = Join-Path $work 'm2'
New-Item -ItemType Directory -Force $work | Out-Null

if ($Clean -and (Test-Path $m2)) { Remove-Item -Recurse -Force $m2 }
if (Test-Path $src) { Remove-Item -Recurse -Force $src }
$ours = Join-Path $m2 'com\nekkora'
if (Test-Path $ours) { Remove-Item -Recurse -Force $ours }
New-Item -ItemType Directory -Force $m2 | Out-Null

Write-Host "[1/4] Checkout de $commit"
& git clone --quiet --no-checkout $repo $src
if ($LASTEXITCODE -ne 0) { throw "git clone falhou ($LASTEXITCODE)" }
& git -C $src -c advice.detachedHead=false checkout --quiet --detach $commit
if ($LASTEXITCODE -ne 0) { throw "commit $commit não encontrado em $repo" }
$head = (& git -C $src rev-parse HEAD).Trim()
if ($head -ne $commit) { throw "checkout em $head, esperado $commit" }
$dirty = & git -C $src status --porcelain
if ($dirty) { throw "checkout do Studio não está limpo" }

Write-Host "[2/4] Construindo $modules (repositório Maven isolado: $m2)"
$mvnw = Join-Path $src 'mvnw.cmd'
# cmd /c: o PowerShell 5.1 parte argumentos -Dchave.com.pontos=valor ao chamar .cmd diretamente.
# A data do próprio commit fixa os carimbos dentro dos JARs: duas preparações produzem os mesmos bytes.
$stamp = (& git -C $src show -s --format=%cI HEAD).Trim()
$cmdLine = "`"$mvnw`" -B -q -f `"$(Join-Path $src 'pom.xml')`" `"-Dmaven.repo.local=$m2`" `"-Dproject.build.outputTimestamp=$stamp`" -DskipTests -pl $modules -am install"
& cmd.exe /d /c $cmdLine
if ($LASTEXITCODE -ne 0) { throw "construção das bibliotecas falhou ($LASTEXITCODE)" }

Write-Host "[3/4] Conferindo artefatos instalados"
$artifacts = @()
$artifacts += [pscustomobject]@{ Id = 'nekkora-studio-parent'; Path = Join-Path $m2 "com\nekkora\nekkora-studio-parent\$version\nekkora-studio-parent-$version.pom" }
foreach ($m in $modules.Split(',')) {
    $m = $m.Trim()
    $artifacts += [pscustomobject]@{ Id = $m; Path = Join-Path $m2 "com\nekkora\$m\$version\$m-$version.jar" }
}
$installed = Get-ChildItem -Recurse -File (Join-Path $m2 'com\nekkora') | Where-Object { $_.Extension -in '.jar', '.pom' }
$expected = @($artifacts | ForEach-Object { (Resolve-Path $_.Path).Path })
foreach ($f in $installed) {
    if ($expected -notcontains $f.FullName -and $f.Extension -eq '.jar') { throw "artefato inesperado em com/nekkora: $($f.FullName)" }
}

Write-Host "[4/4] Gravando design-system.lock.properties"
$java = (& cmd.exe /d /c "java -version 2>&1" | Select-Object -First 1)
$lines = @(
    '# Gerado por tools/prepare-design-system.ps1 - não editar.',
    "studio.repository=$($props['studio.repository'])",
    "studio.commit=$head",
    "studio.version=$version",
    "studio.modules=$modules",
    "build.outputTimestamp=$stamp",
    "built.jdk=$java"
)
foreach ($a in $artifacts) {
    $hash = (Get-FileHash -Algorithm SHA256 $a.Path).Hash.ToLowerInvariant()
    $lines += "sha256.$($a.Id)=$hash"
}
$lock = Join-Path $work 'design-system.lock.properties'
[System.IO.File]::WriteAllLines($lock, [string[]]$lines, (New-Object System.Text.UTF8Encoding $false))
Get-Content $lock
Write-Host "Pronto. O checkout em .design-system/src pode ser apagado; os JARs ficam em .design-system/m2."
