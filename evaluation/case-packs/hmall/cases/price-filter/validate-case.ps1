param(
    [Parameter(Mandatory = $true)][string]$CodeAgentJar,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$Java11Home,
    [Parameter(Mandatory = $true)][string]$MavenWrapper,
    [string]$Java21 = 'java'
)
$ErrorActionPreference = 'Stop'
$pack = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$jar = (Resolve-Path -LiteralPath $CodeAgentJar).Path
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) { throw 'Use a new output directory' }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$env:EVAL_JAVA_HOME = (Resolve-Path -LiteralPath $Java11Home).Path
$env:EVAL_MAVEN_WRAPPER = (Resolve-Path -LiteralPath $MavenWrapper).Path
$caseFile = 'cases/price-filter/case.json'
foreach ($variant in 'baseline','reference') {
    $prepared = Join-Path $outputRoot $variant
    & $Java21 -jar $jar evaluation prepare --pack $pack --case $caseFile --output $prepared
    if ($LASTEXITCODE -ne 0) { throw "Preparation failed: $variant" }
    $workspace = Join-Path $prepared 'workspace'
    if ($variant -eq 'reference') {
        $controller = Join-Path $workspace 'item-service/src/main/java/com/hmall/item/controller/SearchController.java'
        $beforePatch = (Get-FileHash -LiteralPath $controller -Algorithm SHA256).Hash
        $previousCeiling = $env:GIT_CEILING_DIRECTORIES
        Push-Location -LiteralPath $workspace
        try {
            # Background: this workspace may live below the CodeAgent Git repository.
            # Intent: stop parent repository discovery and apply the trusted patch within the prepared copy.
            # Constraint: inheriting the parent repository can silently skip the patch; verify that the copy changed.
            $env:GIT_CEILING_DIRECTORIES = $prepared
            & git apply --ignore-space-change --ignore-whitespace (Join-Path $PSScriptRoot 'reference.patch')
            if ($LASTEXITCODE -ne 0) { throw 'Could not apply reference patch' }
            if ((Get-FileHash -LiteralPath $controller -Algorithm SHA256).Hash -eq $beforePatch) { throw 'Reference patch did not change the prepared copy' }
        } finally {
            $env:GIT_CEILING_DIRECTORIES = $previousCeiling
            Pop-Location
        }
    }
    $resultDirectory = Join-Path $outputRoot "$variant-result"
    & $Java21 -jar $jar evaluation verify --pack $pack --case $caseFile --workspace $workspace --output $resultDirectory
    if ($LASTEXITCODE -notin 0,1) { throw "Verifier failed: $variant" }
    $result = Get-Content -LiteralPath (Join-Path $resultDirectory 'verification.json') -Raw | ConvertFrom-Json
    $expected = if ($variant -eq 'baseline') { 'FAIL' } else { 'PASS' }
    if ($result.status -ne $expected) { throw "Expected $expected for $variant, got $($result.status)" }
    if ($variant -eq 'baseline') {
        $priceFailures = @($result.checks | Where-Object { $_.id -match '^V[12]_' -and -not $_.passed })
        if (-not $priceFailures.Count) { throw 'Baseline must fail a single-bound price assertion, not just compilation' }
        $preservedChecks = @($result.checks | Where-Object { $_.id -match '^V[348]_' -and $_.passed })
        if ($preservedChecks.Count -ne 3) { throw 'Baseline must retain existing interval, unfiltered and combined behavior before qualification' }
    }
}
Write-Output 'Case qualified: baseline fails the target behavior; reference passes all required checks. No Agent run was performed.'
