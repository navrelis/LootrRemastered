#requires -Version 7.0
# Runs build-all.ps1 of every version branch worktree (versions/<minecraft version>/ that contains a build-all.ps1),
# one after the other, and prints a summary table. build-all.ps1 builds every loader folder of a branch with its own
# Gradle wrapper (see docs/TESTING.md).
#
# A failing branch does not stop the run: the remaining branches are still built. The exit code is 1 if at least
# one branch failed, 0 otherwise. Folders under versions/ without a build-all.ps1 are listed as skipped.
#
# Usage: pwsh ./scripts/build-all-versions.ps1 [-Only <version>[,<version>...]] [gradle tasks and options...]
#   -Only   build just these versions, e.g. -Only 1.21.1 (must match the folder names under versions/).
#   Everything else is handed to every build-all.ps1 unchanged (default there: build), e.g.
#       pwsh ./scripts/build-all-versions.ps1 build -x test
#       pwsh ./scripts/build-all-versions.ps1 -Only 1.21.1 clean build
# Every build-all.ps1 adds --no-daemon itself; never run "gradlew --stop" to clean up after a run.

param(
    [string[]]$Only
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
# "-File script.ps1 -Only a,b" from another shell arrives as the single string "a,b"; accept both forms.
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$versionsDir = Join-Path $repoRoot 'versions'
[string[]]$extraArgs = @($args)

if (-not (Test-Path -LiteralPath $versionsDir)) {
    throw "versions/ not found at $versionsDir. Run scripts/setup-worktrees.ps1 first."
}

$folders = @(Get-ChildItem -LiteralPath $versionsDir -Directory | Sort-Object Name)
if ($Only -and $Only.Count -gt 0) {
    $names = @($folders | ForEach-Object { $_.Name })
    $missing = @($Only | Where-Object { $_ -notin $names })
    $folders = @($folders | Where-Object { $_.Name -in $Only })
} else {
    $missing = @()
}

$pwsh = (Get-Process -Id $PID).Path
$rows = [System.Collections.Generic.List[object]]::new()
$failed = 0

foreach ($name in $missing) {
    $rows.Add([pscustomobject]@{ Version = $name; Status = 'FAILED (no such folder under versions/)'; Seconds = '' })
    $failed++
}

foreach ($folder in $folders) {
    $script = Join-Path $folder.FullName 'build-all.ps1'
    if (-not (Test-Path -LiteralPath $script)) {
        $rows.Add([pscustomobject]@{ Version = $folder.Name; Status = 'skipped (no build-all.ps1)'; Seconds = '' })
        continue
    }

    Write-Host "==> versions/$($folder.Name) : build-all.ps1 $($extraArgs -join ' ')"
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location $folder.FullName
    try {
        # A child process keeps the exit and the $ErrorActionPreference of the branch's script away from this one.
        & $pwsh -NoProfile -File $script @extraArgs
        $code = $LASTEXITCODE
    } catch {
        Write-Host "    $($_.Exception.Message)"
        $code = 1
    } finally {
        Pop-Location
        $watch.Stop()
    }

    if ($code -eq 0) {
        $status = 'passed'
    } else {
        $status = "FAILED (exit code $code)"
        $failed++
    }
    $rows.Add([pscustomobject]@{ Version = $folder.Name; Status = $status; Seconds = [int]$watch.Elapsed.TotalSeconds })
}

Write-Host ''
Write-Host 'Build summary:'
if ($rows.Count -gt 0) {
    $rows | Format-Table -Property Version, Status, Seconds -AutoSize | Out-String -Width 4096 | Write-Host
} else {
    Write-Host '  (no version folders found under versions/)'
}

if ($failed -gt 0) {
    Write-Host "$failed branch(es) failed."
    exit 1
}
Write-Host 'All selected branches built.'
exit 0
