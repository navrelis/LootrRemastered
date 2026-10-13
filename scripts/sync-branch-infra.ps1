#requires -Version 7.0
# Copies the infra files every version branch shares from templates/branch/ onto every version branch worktree
# (versions/<minecraft version>/). The files are:
#   build-all.ps1, release.ps1, .gitattributes, .github/workflows/build.yml
# The sub-folder layout of templates/branch/ is kept. Files are compared ignoring the difference between CRLF and
# LF line endings (git ignores it too under autocrlf); only files that are missing or differ are written, as an
# exact copy of the template.
#
# The script never runs git add, commit or push in a worktree: look at "git status" / "git diff" in each worktree,
# then commit there yourself.
#
# Usage: pwsh ./scripts/sync-branch-infra.ps1 [-DryRun] [-Only <version>[,<version>...]]
#   -DryRun   only list what differs (missing / differs); write nothing.
#   -Only     restrict the run to these worktrees, e.g. -Only 1.21.1.
# Prints, per worktree, which files are unchanged / changed / added. Safe to run twice: the second run changes nothing.

[CmdletBinding()]
param(
    [switch]$DryRun,
    [string[]]$Only
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
# "-File script.ps1 -Only a,b" from another shell arrives as the single string "a,b"; accept both forms.
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$templateRoot = Join-Path $repoRoot 'templates/branch'
$versionsDir = Join-Path $repoRoot 'versions'

if (-not (Test-Path -LiteralPath $templateRoot)) { throw "Template folder not found: $templateRoot" }
if (-not (Test-Path -LiteralPath $versionsDir)) { throw "versions/ not found at $versionsDir. Run scripts/setup-worktrees.ps1 first." }

function Get-LfContent {
    # File content as a string with CRLF turned into LF, so that a checkout with autocrlf does not count as a difference.
    param([string]$Path)
    return [System.Text.Encoding]::Latin1.GetString([System.IO.File]::ReadAllBytes($Path)).Replace("`r`n", "`n")
}

# Template files as paths relative to templates/branch (forward slashes).
$prefixLength = (Get-Item -LiteralPath $templateRoot).FullName.TrimEnd('\', '/').Length + 1
$templateFiles = @(Get-ChildItem -LiteralPath $templateRoot -Recurse -File -Force |
    ForEach-Object { $_.FullName.Substring($prefixLength) -replace '\\', '/' } |
    Sort-Object)
if ($templateFiles.Count -eq 0) { throw "No files found under $templateRoot" }

# A worktree has a .git entry (a file in a worktree, a folder in a clone).
$worktrees = @(Get-ChildItem -LiteralPath $versionsDir -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName '.git') } |
    Sort-Object Name)
if ($Only -and $Only.Count -gt 0) {
    $missing = @($Only | Where-Object { $_ -notin @($worktrees | ForEach-Object { $_.Name }) })
    if ($missing.Count -gt 0) { throw "No worktree under versions/ for: $($missing -join ', ')" }
    $worktrees = @($worktrees | Where-Object { $_.Name -in $Only })
}
if ($worktrees.Count -eq 0) { throw 'No version worktrees found under versions/.' }

Write-Host "Templates : $templateRoot"
Write-Host "Files     : $($templateFiles -join ', ')"
Write-Host "Worktrees : $(($worktrees | ForEach-Object { $_.Name }) -join ', ')"
Write-Host "Mode      : $(if ($DryRun) { 'dry run (nothing is written)' } else { 'sync' })"

$total = 0
foreach ($worktree in $worktrees) {
    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($relative in $templateFiles) {
        $source = Join-Path $templateRoot $relative
        $target = Join-Path $worktree.FullName $relative

        if (-not (Test-Path -LiteralPath $target)) {
            $state = 'added'
        } else {
            $same = (Get-LfContent $source) -ceq (Get-LfContent $target)
            $state = if ($same) { 'unchanged' } else { 'changed' }
        }

        if ($state -ne 'unchanged') {
            $total++
            if (-not $DryRun) {
                New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
                Copy-Item -LiteralPath $source -Destination $target -Force
            } else {
                $state = if ($state -eq 'added') { 'missing (would be added)' } else { 'differs (would be changed)' }
            }
        }
        $rows.Add([pscustomobject]@{ File = $relative; State = $state })
    }

    Write-Host ''
    $differing = @($rows | Where-Object { $_.State -ne 'unchanged' })
    Write-Host "versions/$($worktree.Name): $($differing.Count) of $($rows.Count) file(s) $(if ($DryRun) { 'differ' } else { 'written' })"
    $rows | Format-Table -Property File, State -AutoSize | Out-String -Width 4096 | Write-Host
}

if ($DryRun) {
    Write-Host "Dry run finished: $total file(s) differ from the templates."
} else {
    Write-Host "Sync finished: $total file(s) written. Nothing was committed; review and commit in each worktree."
}
exit 0
