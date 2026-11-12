<#
.SYNOPSIS
    Cuts a release tag and pushes it, or dispatches Publish Release for an existing tag.
    PowerShell wrapper around scripts/release.sh.
.DESCRIPTION
    The checks live in release.sh. This wrapper only locates Git Bash and hands over, so
    there is one implementation of the release guard rather than two that can drift apart.
    Every argument is passed through, including the publish mode.

    PowerShell parses an unquoted comma list such as github,shared-state as an array, and
    a [string[]] parameter would hand it on as "github shared-state". The parameter is
    therefore [object[]], and each array argument is joined back with commas before Git
    Bash sees it, so release.sh reports the list it was actually given.
.EXAMPLE
    scripts\release.ps1 0.5.0-rc.1 --candidate 123456789
.EXAMPLE
    scripts\release.ps1 0.5.0 --candidate 123456789 --dry-run
.EXAMPLE
    scripts\release.ps1 publish 0.5.0-rc.1 123456789
.EXAMPLE
    scripts\release.ps1 publish 0.5.0 123456789 --destinations shared-state --dry-run
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [object[]] $ScriptArgs
)

# An unquoted a,b arrives as one array element; release.sh expects the literal "a,b".
$bashArgs = @(foreach ($arg in $ScriptArgs) { @($arg) -join ',' })

# Git installs are searched before PATH, because PATH may resolve to
# C:\Windows\System32\bash.exe, which launches WSL with a different filesystem view.
function Find-GitBash {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Git\bin\bash.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Git\bin\bash.exe')
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    $onPath = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($onPath -and $onPath.Source -notlike "$env:SystemRoot*") { return $onPath.Source }
    return $null
}

$bash = Find-GitBash
if (-not $bash) {
    Write-Error 'Could not find Git Bash. Install Git for Windows (https://git-scm.com/download/win), then run this again.'
    exit 1
}

$repoRoot = Split-Path -Parent $PSScriptRoot
# Forward slashes: bash.exe handles "C:/path" but treats backslashes as escapes.
$script = (Join-Path $PSScriptRoot 'release.sh').Replace([char]92, [char]47)

Push-Location $repoRoot
try {
    & $bash $script @bashArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
