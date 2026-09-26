param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$SnapshotName
)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath $Source).Path.TrimEnd('\', '/')
if ($SnapshotName -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._-]*$') { throw 'Invalid snapshot name' }
$destination = Join-Path $PSScriptRoot "snapshots/$SnapshotName"
if (Test-Path -LiteralPath $destination) { throw 'Snapshot already exists; choose a new name' }
$rules = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'snapshot-rules.json') -Raw | ConvertFrom-Json
$selected = @{}
$excluded = [System.Collections.Generic.List[object]]::new()
foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File) {
    $relative = $file.FullName.Substring($sourceRoot.Length + 1).Replace('\', '/')
    $reason = $null
    foreach ($rule in $rules.excludeRules) {
        if ($relative -match $rule.pattern) { $reason = $rule.reason; break }
    }
    if (-not $reason) {
        $included = $false
        foreach ($pattern in $rules.includePatterns) { if ($relative -match $pattern) { $included = $true; break } }
        if (-not $included) { $reason = 'Not part of the selected source/build resource set' }
    }
    if ($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw "Linked source file is not supported: $relative" }
    if ($reason) { $excluded.Add([ordered]@{ path = $relative; reason = $reason }); continue }
    $selected[$relative] = $file.FullName
}
if ($selected.Count -eq 0) { throw 'No source files selected' }
# Background: repeated evaluations must start with identical source, not the evolving learning project.
# Intent: publish a new immutable copy plus an ordinal manifest, without editing the source directory.
# Constraint: never replace an existing snapshot or copy deployment secrets; either would invalidate comparison.
$workspace = Join-Path $destination 'workspace'
New-Item -ItemType Directory -Path $workspace | Out-Null
$paths = [string[]]@($selected.Keys)
[Array]::Sort($paths, [StringComparer]::Ordinal)
$entries = [System.Collections.Generic.List[object]]::new()
$identity = [System.Text.StringBuilder]::new()
foreach ($relative in $paths) {
    $target = Join-Path $workspace $relative
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($target)) | Out-Null
    Copy-Item -LiteralPath $selected[$relative] -Destination $target
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    $entries.Add([ordered]@{ path = $relative; sha256 = $hash })
    [void]$identity.Append($relative).Append("`t").Append($hash).Append("`n")
}
$digest = [Security.Cryptography.SHA256]::Create()
try { $snapshotId = 'sha256:' + ([BitConverter]::ToString($digest.ComputeHash([Text.Encoding]::UTF8.GetBytes($identity.ToString())))).Replace('-', '').ToLowerInvariant() }
finally { $digest.Dispose() }
$manifest = [ordered]@{ snapshotId = $snapshotId; sourceDescription = 'User-selected hmall learning project; source-only snapshot'; files = @($entries.ToArray()); excluded = @($excluded.ToArray()) }
[IO.File]::WriteAllText((Join-Path $destination 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output "Snapshot: $snapshotId; files: $($entries.Count); excluded: $($excluded.Count)"
