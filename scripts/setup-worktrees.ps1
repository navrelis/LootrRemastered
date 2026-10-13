#requires -Version 7.0
# Creates a git worktree under versions/<minecraft version> for every remote branch origin/mc/<minecraft version>
# that has none yet. main is the hub without game code; every version branch (mc/1.21.1, ...) is checked out
# next to it as a worktree, and versions/ is git-ignored.
#
# For each origin/mc/<v>:
#   - a worktree of the local branch mc/<v> is registered already -> left alone and listed ("exists");
#   - the local branch mc/<v> exists but has no worktree           -> worktree added at versions/<v>;
#   - neither exists                                                -> local branch mc/<v> created from
#                                                                      origin/mc/<v> (tracking), worktree added;
#   - versions/<v> exists but is not a worktree of mc/<v>          -> never touched, listed as "skipped".
# Existing worktrees and their files are never changed, so the script is safe to run twice. It only looks at
# origin/mc/*: a local-only branch is not touched.
#
# Usage: pwsh ./scripts/setup-worktrees.ps1 [-Fetch]
#   -Fetch   run "git fetch origin --prune" first, to learn about branches that were pushed since the last fetch.
#
# Exits with 1 if a worktree could not be created.

[CmdletBinding()]
param(
    [switch]$Fetch
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$versionsDir = Join-Path $repoRoot 'versions'

function Invoke-Git {
    # Runs git in the repository root; returns the output lines, throws when git fails.
    param([string[]]$GitArgs)
    $out = & git -C $repoRoot @GitArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($GitArgs -join ' ') failed (exit code $LASTEXITCODE): $($out -join ' ')"
    }
    return @($out | ForEach-Object { "$_" })
}

function Get-Worktrees {
    # [{ Path; Branch }] from "git worktree list --porcelain"; Branch is $null for a detached worktree.
    $list = [System.Collections.Generic.List[object]]::new()
    $current = $null
    foreach ($line in (Invoke-Git @('worktree', 'list', '--porcelain'))) {
        if ($line -match '^worktree (.+)$') {
            if ($current) { $list.Add($current) }
            $current = [pscustomobject]@{ Path = $Matches[1]; Branch = $null }
        } elseif ($line -match '^branch refs/heads/(.+)$' -and $current) {
            $current.Branch = $Matches[1]
        }
    }
    if ($current) { $list.Add($current) }
    return $list
}

function Test-SamePath {
    param([string]$A, [string]$B)
    $na = [System.IO.Path]::GetFullPath($A).TrimEnd('\', '/')
    $nb = [System.IO.Path]::GetFullPath($B).TrimEnd('\', '/')
    return [string]::Equals($na, $nb, [System.StringComparison]::OrdinalIgnoreCase)
}

if ($Fetch) {
    Write-Host 'Fetching origin ...'
    [void](Invoke-Git @('fetch', 'origin', '--prune'))
}

$remoteBranches = @(Invoke-Git @('for-each-ref', '--format=%(refname)', 'refs/remotes/origin/mc/') |
    Where-Object { $_ } |
    ForEach-Object { $_ -replace '^refs/remotes/origin/', '' } |   # mc/1.21.1
    Sort-Object)

if ($remoteBranches.Count -eq 0) {
    Write-Warning 'No origin/mc/* branches found (run with -Fetch, or check that the remote "origin" has them).'
}

$results = [System.Collections.Generic.List[object]]::new()
$hadError = $false

foreach ($branch in $remoteBranches) {
    $version = $branch -replace '^mc/', ''
    $path = Join-Path $versionsDir $version
    $worktrees = Get-Worktrees

    $registered = $worktrees | Where-Object { $_.Branch -eq $branch } | Select-Object -First 1
    if ($registered) {
        $results.Add([pscustomobject]@{ Branch = $branch; Path = $registered.Path; Status = 'exists' })
        continue
    }
    if (Test-Path -LiteralPath $path) {
        $results.Add([pscustomobject]@{ Branch = $branch; Path = $path; Status = 'skipped (folder exists, but is not a worktree of this branch)' })
        continue
    }

    try {
        if (-not (Test-Path -LiteralPath $versionsDir)) {
            New-Item -ItemType Directory -Path $versionsDir | Out-Null
        }
        & git -C $repoRoot show-ref --verify --quiet "refs/heads/$branch"
        if ($LASTEXITCODE -eq 0) {
            [void](Invoke-Git @('worktree', 'add', $path, $branch))
        } else {
            [void](Invoke-Git @('worktree', 'add', '--track', '-b', $branch, $path, "origin/$branch"))
        }
        $results.Add([pscustomobject]@{ Branch = $branch; Path = $path; Status = 'created' })
    } catch {
        $hadError = $true
        $results.Add([pscustomobject]@{ Branch = $branch; Path = $path; Status = "FAILED: $($_.Exception.Message)" })
    }
}

Write-Host ''
Write-Host 'Worktrees of the origin/mc/* branches:'
if ($results.Count -gt 0) {
    $results | Format-Table -Property Branch, Path, Status -AutoSize -Wrap | Out-String -Width 4096 | Write-Host
} else {
    Write-Host '  (none)'
}

$created = @($results | Where-Object { $_.Status -eq 'created' }).Count
$existing = @($results | Where-Object { $_.Status -eq 'exists' }).Count
$other = $results.Count - $created - $existing
Write-Host "Summary: $created created, $existing already existed, $other skipped or failed."

if ($hadError) { exit 1 }
exit 0
