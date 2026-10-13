# Builds and publishes a release jar for every loader folder of this branch. Loader discovery matches build-all.ps1
# and .github/workflows/build.yml (any top-level folder containing a gradlew.bat).
#
# For each discovered loader:
#   1. runs "gradlew build" (skip with -NoBuild to reuse an existing build/libs jar)
#   2. locates the main jar <loader>/build/libs/lootr-remastered-<loader>-*-<mod_version>.jar (never the -sources jar)
#   3. verifies the version embedded in the jar's mod metadata (fabric.mod.json / neoforge.mods.toml / mods.toml)
#      is "<minecraft_version>-<mod_version>", the format the mod declares
#   4. clears old jars out of <loader>/release/, copies the new jar there, writes <loader>/release/SHA256SUMS.txt
#   5. prints a summary table
#
# Exits non-zero if any loader fails to build, produces no matching jar, or has a version mismatch.
#
# Usage: pwsh ./release.ps1 [-NoBuild]

[CmdletBinding()]
param(
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = $PSScriptRoot
$jarPrefix = 'lootr-remastered'

function Read-Properties {
    param([string]$Path)
    $props = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $idx = $trimmed.IndexOf('=')
        if ($idx -lt 0) { continue }
        $key = $trimmed.Substring(0, $idx).Trim()
        $value = $trimmed.Substring($idx + 1).Trim()
        $props[$key] = $value
    }
    return $props
}

# ---- Shared metadata ---------------------------------------------------------------------------------------
$sharedPropsPath = Join-Path $root 'gradle/shared.properties'
if (-not (Test-Path $sharedPropsPath)) { throw "Not found: $sharedPropsPath" }
$sharedProps = Read-Properties -Path $sharedPropsPath

$modVersion = $sharedProps['mod_version']
$mcVersion = $sharedProps['minecraft_version']
if (-not $modVersion -or -not $mcVersion) {
    throw "mod_version / minecraft_version missing from gradle/shared.properties"
}
$declaredVersion = "$mcVersion-$modVersion"

Write-Host "Releasing Lootr Remastered $modVersion (Minecraft $mcVersion)"

# ---- Discover loaders (same rule as build-all.ps1 and the CI workflow) ---------------------------------------
$loaders = @(Get-ChildItem -LiteralPath $root -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'gradlew.bat') } |
    Sort-Object Name |
    ForEach-Object { $_.Name })

if (-not $loaders -or $loaders.Count -eq 0) {
    throw "No loader folders discovered (looked for a gradlew.bat in the sub-folders of $root)."
}
Write-Host "Discovered loaders: $($loaders -join ', ')"
Write-Host ''

$metadataFiles = [ordered]@{
    'fabric.mod.json'             = 'json'
    'META-INF/neoforge.mods.toml' = 'toml'
    'META-INF/mods.toml'          = 'toml'
}

$results = [System.Collections.Generic.List[object]]::new()
$hadError = $false

foreach ($loader in $loaders) {
    $loaderDir = Join-Path $root $loader
    $row = [ordered]@{
        Loader = $loader
        Status = 'pending'
        Jar    = ''
        SHA256 = ''
    }

    try {
        if (-not $NoBuild) {
            Write-Host "==> $loader : gradlew build"
            Push-Location $loaderDir
            try {
                if ($IsWindows -or $env:OS -eq 'Windows_NT') {
                    & .\gradlew.bat build --no-daemon --stacktrace
                } else {
                    & ./gradlew build --no-daemon --stacktrace
                }
                if ($LASTEXITCODE -ne 0) {
                    throw "gradlew build failed (exit code $LASTEXITCODE)"
                }
            } finally {
                Pop-Location
            }
        }

        # lootr-remastered-<loader>-<minecraft_version>-<mod_version>.jar; the "-*-<mod_version>.jar" pattern does
        # not match the -sources jar.
        $libsDir = Join-Path $loaderDir 'build/libs'
        $jars = @(Get-ChildItem -LiteralPath $libsDir -Filter "$jarPrefix-$loader-*-$modVersion.jar" -File -ErrorAction SilentlyContinue)
        if ($jars.Count -ne 1) {
            throw "Expected exactly one jar build/libs/$jarPrefix-$loader-*-$modVersion.jar, found $($jars.Count) (run without -NoBuild, or check archivesName/mod_version)"
        }
        $jarPath = $jars[0].FullName
        $expectedName = $jars[0].Name

        # ---- Verify the embedded mod version matches shared.properties -------------------------------------
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
        try {
            $checked = $false
            foreach ($entryName in $metadataFiles.Keys) {
                $entry = $zip.GetEntry($entryName)
                if ($null -eq $entry) { continue }
                $checked = $true

                $stream = $entry.Open()
                $reader = New-Object System.IO.StreamReader($stream)
                $content = $reader.ReadToEnd()
                $reader.Close()
                $stream.Close()

                $embeddedVersion = $null
                if ($metadataFiles[$entryName] -eq 'json') {
                    $embeddedVersion = ($content | ConvertFrom-Json).version
                } else {
                    # Top-level (unindented) "version = "..."" line; avoids matching "loaderVersion"/"versionRange".
                    $m = [regex]::Match($content, '(?m)^version\s*=\s*"([^"]+)"')
                    if ($m.Success) { $embeddedVersion = $m.Groups[1].Value }
                }

                if ($embeddedVersion -ne $declaredVersion) {
                    throw "version mismatch in ${entryName}: jar has '$embeddedVersion', expected '$declaredVersion'"
                }
            }
            if (-not $checked) {
                throw "no recognized mod metadata file (fabric.mod.json / neoforge.mods.toml / mods.toml) found inside $expectedName"
            }
        } finally {
            $zip.Dispose()
        }

        # ---- Publish: clear old jars, copy the new one, write the checksum file ----------------------------
        $releaseDir = Join-Path $loaderDir 'release'
        New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
        Get-ChildItem -LiteralPath $releaseDir -Filter '*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force

        $destJar = Join-Path $releaseDir $expectedName
        Copy-Item -LiteralPath $jarPath -Destination $destJar -Force

        $hash = (Get-FileHash -LiteralPath $destJar -Algorithm SHA256).Hash.ToLowerInvariant()
        $sumsPath = Join-Path $releaseDir 'SHA256SUMS.txt'
        Set-Content -LiteralPath $sumsPath -Value "$hash  $expectedName" -Encoding utf8NoBOM

        $row.Status = 'ok'
        $row.Jar = $expectedName
        $row.SHA256 = $hash
    } catch {
        $row.Status = 'FAILED'
        $row.Jar = $_.Exception.Message
        $hadError = $true
        Write-Error "${loader}: $($_.Exception.Message)"
    }

    $results.Add([pscustomobject]$row)
}

Write-Host ''
$results | Format-Table -AutoSize | Out-String | Write-Host

if ($hadError) {
    Write-Error 'release.ps1 finished with errors.'
    exit 1
}

Write-Host 'release.ps1 finished successfully.'
exit 0
