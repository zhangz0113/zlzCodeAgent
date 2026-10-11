param(
    [Parameter(Mandatory = $true)][string]$Workspace,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$CaseDefinition,
    [string]$AcceptanceProfile,
    [string]$EvidenceManifest
)
$ErrorActionPreference = 'Stop'
$outputRoot = (Resolve-Path -LiteralPath $OutputDirectory).Path
$utf8 = [Text.UTF8Encoding]::new($false)

function Save-Result($Status, $Checks, $Evidence, $Message) {
    $result = [ordered]@{ status = $Status; checks = @($Checks); evidence = @($Evidence); error = $Message }
    [IO.File]::WriteAllText((Join-Path $outputRoot 'result.json'), ($result | ConvertTo-Json -Depth 10), $utf8)
}

try {
    $sourceRoot = (Resolve-Path -LiteralPath $Workspace).Path.TrimEnd('\', '/')
    $packRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
    $casePath = if ($CaseDefinition) { $CaseDefinition } else { Join-Path $PSScriptRoot '../case.json' }
    $case = Get-Content -LiteralPath $casePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $profile = if ($AcceptanceProfile) { Get-Content -LiteralPath $AcceptanceProfile -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
    $manifestPath = Join-Path $packRoot $case.snapshot
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $baseline = @{}
    foreach ($entry in $manifest.files) { $baseline[$entry.path] = $entry.sha256 }
    $candidateFiles = @{}
    foreach ($entry in Get-ChildItem -LiteralPath $sourceRoot -Recurse -Force) {
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked candidate paths are not supported' }
        if (-not $entry.PSIsContainer) { $candidateFiles[$entry.FullName.Substring($sourceRoot.Length + 1).Replace('\', '/')] = $entry.FullName }
    }
    # Background: the task authorizes business Java edits, not replacement of the build or grading configuration.
    # Intent: reject out-of-scope changes before executing a fresh copy, while accepting alternative Java repairs.
    # Constraint: never execute a candidate-controlled POM or test command; it could disable the independent checks.
    $scopeErrors = [Collections.Generic.List[string]]::new()
    foreach ($name in @($baseline.Keys) + @($candidateFiles.Keys) | Select-Object -Unique) {
        $editable = $name -match '/src/main/java/.*\.java$' -and $name -notmatch '^hm-service/'
        if ($editable) { continue }
        if (-not $baseline.ContainsKey($name) -or -not $candidateFiles.ContainsKey($name)) {
            $scopeErrors.Add($name); continue
        }
        if ((Get-FileHash -LiteralPath $candidateFiles[$name] -Algorithm SHA256).Hash.ToLowerInvariant() -ne $baseline[$name]) {
            $scopeErrors.Add($name)
        }
    }
    if ($scopeErrors.Count) {
        Save-Result 'FAIL' @([ordered]@{ id = 'allowed-changes'; passed = $false; detail = ($scopeErrors -join ', ') }) @() $null
        exit 0
    }

    $javaHome = $env:EVAL_JAVA_HOME
    $mavenWrapper = $env:EVAL_MAVEN_WRAPPER
    if (-not $javaHome -or -not (Test-Path -LiteralPath (Join-Path $javaHome 'bin/java.exe'))) { throw 'Set EVAL_JAVA_HOME to the case JDK 11 directory' }
    if (-not $mavenWrapper -or -not (Test-Path -LiteralPath $mavenWrapper)) { throw 'Set EVAL_MAVEN_WRAPPER to an available mvnw.cmd' }
    $env:JAVA_HOME = $javaHome
    $buildRoot = Join-Path $outputRoot 'build'
    if (Test-Path -LiteralPath $buildRoot) { throw 'Build directory already exists' }
    New-Item -ItemType Directory -Path $buildRoot | Out-Null
    foreach ($name in $candidateFiles.Keys) {
        $target = Join-Path $buildRoot $name
        New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($target)) | Out-Null
        Copy-Item -LiteralPath $candidateFiles[$name] -Destination $target
    }
    $testJava = Join-Path $buildRoot 'item-service/src/test/java/com/hmall/evaluation'
    $testResources = Join-Path $buildRoot 'item-service/src/test/resources'
    New-Item -ItemType Directory -Force -Path $testJava,$testResources | Out-Null
    foreach ($name in 'PriceFilterTestConfiguration.java','HmallPriceFilterAcceptanceTest.java') {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $testJava $name)
    }
    foreach ($name in 'schema.sql','data.sql') {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $testResources $name)
    }
    # Background: new search tasks share the existing price regression and local H2 assembly.
    # Intent: trusted profiles add tests and fixture updates, leaving the legacy invocation unchanged.
    # Constraint: never obtain profiles from candidate source; otherwise the candidate could select its own passing checks.
    if ($profile) {
        foreach ($name in $profile.testSources) {
            Copy-Item -LiteralPath (Join-Path $packRoot $name) -Destination (Join-Path $testJava (Split-Path $name -Leaf))
        }
        $dataPath = Join-Path $testResources 'data.sql'
        [IO.File]::AppendAllText($dataPath, "`n" + [IO.File]::ReadAllText((Join-Path $packRoot $profile.additionalData)), $utf8)
    }
    $itemPom = Join-Path $buildRoot 'item-service/pom.xml'
    [xml]$pom = [IO.File]::ReadAllText($itemPom)
    $ns = [Xml.XmlNamespaceManager]::new($pom.NameTable)
    $ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    $dependencies = $pom.SelectSingleNode('/m:project/m:dependencies', $ns)
    $dependency = $pom.CreateElement('dependency', $pom.DocumentElement.NamespaceURI)
    foreach ($pair in @(@('groupId','com.h2database'),@('artifactId','h2'),@('version','2.1.214'),@('scope','test'))) {
        $element = $pom.CreateElement($pair[0], $pom.DocumentElement.NamespaceURI)
        $element.InnerText = $pair[1]
        [void]$dependency.AppendChild($element)
    }
    [void]$dependencies.AppendChild($dependency)
    $pom.Save($itemPom)
    $rootPom = Join-Path $buildRoot 'pom.xml'
    $log = Join-Path $outputRoot 'maven.log'
    $testClasses = @('HmallPriceFilterAcceptanceTest')
    if ($profile) { $testClasses += @($profile.testClasses) }
    $mavenArguments = @('-B','-ntp','-f',$rootPom,'-pl','item-service','-am','test',
        ('-Dtest=' + ($testClasses -join ',')),'-Dsurefire.failIfNoSpecifiedTests=false','-Dfile.encoding=UTF-8')
    [IO.File]::WriteAllText((Join-Path $outputRoot 'environment.json'), ([ordered]@{
        javaHome = $javaHome; mavenWrapper = $mavenWrapper; arguments = $mavenArguments; h2Version = '2.1.214'
    } | ConvertTo-Json -Depth 5), $utf8)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location -LiteralPath ([IO.Path]::GetDirectoryName($mavenWrapper))
    try { & $mavenWrapper @mavenArguments *> $log; $mavenExit = $LASTEXITCODE }
    finally { Pop-Location; $ErrorActionPreference = $previousPreference }

    $reportFiles = @($testClasses | ForEach-Object { Join-Path $buildRoot "item-service/target/surefire-reports/TEST-com.hmall.evaluation.$_.xml" })
    if (@($reportFiles | Where-Object { -not (Test-Path -LiteralPath $_) }).Count) {
        $mavenText = Get-Content -LiteralPath $log -Raw
        if ($mavenText -match 'COMPILATION ERROR') {
            Save-Result 'FAIL' @([ordered]@{ id = 'compilation'; passed = $false; detail = 'Candidate Java compilation failed; see maven.log' }) @('maven.log','environment.json') $null
        } else {
            Save-Result 'ERROR' @() @('maven.log','environment.json') "Maven exited $mavenExit without the required test report"
        }
        exit 0
    }
    $checks = [Collections.Generic.List[object]]::new()
    $infrastructureError = $false
    $evidenceFiles = @('maven.log','environment.json')
    foreach ($reportFile in $reportFiles) {
    $reportName = if ($profile) { Split-Path $reportFile -Leaf } else { 'tests.xml' }
    Copy-Item -LiteralPath $reportFile -Destination (Join-Path $outputRoot $reportName)
    $evidenceFiles += $reportName
    [xml]$report = [IO.File]::ReadAllText($reportFile)
    foreach ($test in $report.testsuite.testcase) {
        $failure = $test.SelectSingleNode('failure')
        $testError = $test.SelectSingleNode('error')
        $skipped = $test.SelectSingleNode('skipped')
        if ($testError -and $testError.InnerText -match 'Failed to load ApplicationContext|Cannot load ApplicationContext') { $infrastructureError = $true }
        if ($skipped) { $infrastructureError = $true }
        $passed = -not ($failure -or $testError -or $skipped)
        $detail = if ($failure) { $failure.GetAttribute('message') } elseif ($testError) { $testError.GetAttribute('message') } elseif ($skipped) { 'Required check skipped' } else { 'Passed' }
        $checks.Add([ordered]@{ id = [string]$test.name; passed = [bool]$passed; detail = $detail })
    }
    }
    $complete = $checks.Count -eq 10
    if ($profile) {
        $required = @($profile.requiredTestIds)
        $complete = $checks.Count -eq $required.Count -and @($required | Where-Object { $_ -notin $checks.id }).Count -eq 0
        if ($profile.scenario) {
            $scenarioOutput = Join-Path $outputRoot 'scenario'
            New-Item -ItemType Directory -Path $scenarioOutput | Out-Null
            & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $packRoot 'verifiers/verify-evidence.ps1') -Workspace $Workspace -OutputDirectory $scenarioOutput -CaseDefinition $casePath -EvidenceManifest $EvidenceManifest -Kind $profile.scenario
            $scenario = Get-Content -LiteralPath (Join-Path $scenarioOutput 'result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach ($check in $scenario.checks) { $checks.Add($check) }
            if ($scenario.status -eq 'ERROR') { $infrastructureError = $true }
            $evidenceFiles += 'scenario/result.json','scenario/mechanism-evidence.json'
        }
    }
    $status = if (-not $complete -or $infrastructureError) { 'ERROR' } elseif (@($checks | Where-Object { -not $_.passed }).Count) { 'FAIL' } elseif ($mavenExit -ne 0) { 'ERROR' } else { 'PASS' }
    $message = if ($status -eq 'ERROR') { 'Verifier did not complete all required checks in a valid environment; inspect evidence' } else { $null }
    Save-Result $status $checks.ToArray() $evidenceFiles $message
    exit 0
} catch {
    Save-Result 'ERROR' @() @() $_.Exception.Message
    exit 0
}
