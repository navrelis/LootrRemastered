# Builds every loader folder of this branch with its own Gradle wrapper; stops at the first failure.
# Loader folders are discovered generically (any top-level folder containing a gradlew.bat), the same rule used by
# release.ps1 and .github/workflows/build.yml.
# Usage: ./build-all.ps1 [gradle tasks and options...]   (default: build)
# Every gradlew call runs with --no-daemon (added unless the caller already passed it): a daemon left behind keeps
# running after the script, and "gradlew --stop" would also kill the daemons of other builds on the machine.
$ErrorActionPreference = 'Stop'
[string[]]$tasks = if ($args.Count -gt 0) { $args } else { @('build') }
if ($tasks -notcontains '--no-daemon') {
    $tasks += '--no-daemon'
}

$loaders = @(Get-ChildItem -LiteralPath $PSScriptRoot -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'gradlew.bat') } |
    Sort-Object Name |
    ForEach-Object { $_.Name })

if (-not $loaders -or $loaders.Count -eq 0) {
    throw "No loader folders discovered (looked for a gradlew.bat in the sub-folders of $PSScriptRoot)."
}

foreach ($loader in $loaders) {
    $dir = Join-Path $PSScriptRoot $loader
    Write-Host "==> $loader : gradlew $($tasks -join ' ')"
    Push-Location $dir
    try {
        if ($IsWindows -or $env:OS -eq 'Windows_NT') {
            & .\gradlew.bat @tasks
        } else {
            & ./gradlew @tasks
        }
        if ($LASTEXITCODE -ne 0) {
            throw "$loader build failed (exit code $LASTEXITCODE)"
        }
    } finally {
        Pop-Location
    }
}
Write-Host "All loader builds succeeded."
