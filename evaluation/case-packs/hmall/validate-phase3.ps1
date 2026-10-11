param(
    [Parameter(Mandatory = $true)][string]$CodeAgentJar,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$Java11Home,
    [Parameter(Mandatory = $true)][string]$MavenWrapper,
    [string]$Java21 = 'java',
    [ValidateSet('All','Business','Evidence')][string]$Mode = 'All'
)
$ErrorActionPreference = 'Stop'
$pack = $PSScriptRoot
$jar = (Resolve-Path -LiteralPath $CodeAgentJar).Path
$root = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $root) { throw 'Use a new qualification directory' }
New-Item -ItemType Directory -Path $root | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$env:EVAL_JAVA_HOME = (Resolve-Path -LiteralPath $Java11Home).Path
$env:EVAL_MAVEN_WRAPPER = (Resolve-Path -LiteralPath $MavenWrapper).Path
$env:PSModulePath = (& powershell.exe -NoProfile -NonInteractive -Command '[Environment]::GetEnvironmentVariable("PSModulePath")').Trim()
$results = [Collections.Generic.List[object]]::new()
function Read-Json($Path) { Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json }
function Write-Json($Path,$Value) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Path) | Out-Null
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 60), $utf8)
}
function Assert-Result($Path,$Expected,$RequiredFailure,$Label) {
    $value = Read-Json $Path
    $valid = $value.status -eq $Expected
    if ($RequiredFailure) { $valid = $valid -and @($value.checks | Where-Object { $_.id -eq $RequiredFailure -and -not $_.passed }).Count -gt 0 }
    $results.Add([ordered]@{ label=$Label; expected=$Expected; actual=$value.status; requiredFailure=$RequiredFailure; qualified=$valid; result=$Path })
    Write-Json (Join-Path $root 'qualification.json') $results.ToArray()
    if (-not $valid) { throw "Qualification mismatch: $Label; inspect $Path" }
    Write-Output "$Label => $($value.status) (qualified)"
}
function New-SyntheticEvidence($Name,$Directory,$Variant) {
    # Background: checking a verifier requires known good and deliberately bad evidence without spending model calls.
    # Intent: mirror the existing exported schema and label every fixture synthetic, separate from real capability runs.
    # Constraint: these fixtures may qualify the judge only; never count them as actual Agent or mechanism success.
    $case = Read-Json (Join-Path $pack "cases/$Name/case.json")
    $expected = Read-Json (Join-Path $pack "cases/$Name/expected.json")
    $records = @(); $turns = @(); $sid = 'qualification-session-a'; $workspaceId = 'qualification-workspace'
    $ref = 'result_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    Write-Json (Join-Path $Directory 'initial-state.json') @{ workspaceId=$workspaceId; memories=@() }
    for ($i=0; $i -lt $case.steps.Count; $i++) {
        $step = $case.steps[$i]; $runId = "qualification-run-$i"; $calls = @(); $memories = @(); $references = @(); $checkpoint = $null
        if ($Name -eq 'session-memory' -and $i -eq 1) { $sid='qualification-session-b'; $turns=@() }
        $answer = 'Source analysis; runtime not verified.'
        if ($Name -eq 'cart-location') { $answer=($expected.paths + $expected.symbols) -join "`n"; if ($Variant -eq 'wrong-location') { $answer=$answer.Replace('cart-service/','wrong-service/') } }
        if ($Name -eq 'history-compaction' -and $i -eq 4) {
            $answer=$expected.marker+"`n"+($expected.sections -join "`n")
            $checkpoint=@{ summaryContent=$answer; coveredThroughRunId='qualification-run-1' }
            if ($Variant -eq 'uncovered-first') { $checkpoint.coveredThroughRunId='unrelated-run' }
            if ($Variant -eq 'lost-marker') { $answer='Unmarked result' }
        }
        if ($Name -eq 'session-memory') {
            $answer='| '+($expected.headers -join ' | ')+' |'
            if ($i -eq 0) {
                $memories=@(@{ id='qualification-memory'; scope='WORKSPACE'; workspaceId=$workspaceId; content=$answer; sourceSessionId=$sid; sourceRunId=$runId })
                if ($Variant -eq 'wrong-provenance') { $memories[0].sourceRunId='unrelated-run' }
                if ($Variant -eq 'missing-memory') { $memories=@() }
            }
            if ($i -eq 1 -and $Variant -eq 'missing-format') { $answer='Plain answer' }
        }
        if ($Name -eq 'tool-payload') {
            $payloadText=$expected.record+' '+$expected.value
            $references=@(@{ reference=$ref; sessionId=$sid; role='result'; path="payloads/$sid/$ref.json" })
            Write-Json (Join-Path $Directory "payloads/$sid/$ref.json") @{ reference=$ref; sessionId=$sid; runId='qualification-run-0'; content=$payloadText; payloadKind='READ_LINES'; payloadRole='RESULT' }
            if ($i -eq 0) {
                $calls=@(@{ name='read'; arguments=(@{file_path=$expected.sourcePath; offset=1; limit=120}|ConvertTo-Json -Compress); argumentsRef=$null; result=@{success=$true;modelContent='preview without target';resultRef=$ref} })
                if ($Variant -eq 'visible-target') { $calls[0].result.modelContent=$payloadText }
            } else {
                $answer=$payloadText
                $calls=@(@{name='read_tool_result';arguments=(@{result_ref=$ref;offset=116;limit=1}|ConvertTo-Json -Compress);argumentsRef=$null;result=@{success=$true;modelContent=$payloadText;resultRef=$null}})
                if ($Variant -eq 'missing-retrieval') { $calls=@() }
                if ($Variant -eq 'wrong-reference') { $calls[0].arguments='{"result_ref":"result_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}' }
                if ($Variant -eq 'wrong-answer') { $answer='Incorrect value' }
            }
        }
        $run=@{runId=$runId;status='SUCCEEDED';finalAnswer=$answer}
        if ($Name -eq 'history-compaction' -and $i -eq 4 -and $Variant -eq 'failed-after-checkpoint') { $run.status='FAILED'; $run.finalAnswer=$null; $run.errorCode='CONTEXT_CAPACITY_EXCEEDED' }
        $turn=@{runId=$runId;state='COMPLETED';user=@{content=[IO.File]::ReadAllText((Join-Path $pack $step.prompt))};assistant=@{content=$answer;toolRounds=@(@{calls=$calls})}}
        $turns+= $turn
        if ($Name -eq 'session-memory' -and $i -eq 1 -and $Variant -eq 'imported-turn') { $turns+=@{runId='qualification-run-0';state='COMPLETED';user=@{content='Old turn'}} }
        $session=@{sessionId=$sid;workspaceId=$workspaceId;turns=$turns;historyCheckpoint=$checkpoint}
        $dir=Join-Path $Directory "steps/$($step.id)"
        Write-Json (Join-Path $dir 'run.json') $run
        Write-Json (Join-Path $dir 'session.json') $session
        Write-Json (Join-Path $dir 'memories.json') @($memories)
        Write-Json (Join-Path $dir 'references.json') @($references)
        [IO.File]::WriteAllText((Join-Path $dir 'answer.md'),$answer,$utf8)
        $records+=@{id=$step.id;session=$step.session;sessionId=$sid;status=$run.status;run=$run;evidencePath="steps/$($step.id)"}
    }
    Write-Json (Join-Path $Directory 'manifest.json') @{schemaVersion=1;synthetic=$true;execution=@{steps=$records;missingEvidence=@()};missingEvidence=@()}
}

$expectations = Read-Json (Join-Path $pack 'qualification/expectations.json')
foreach ($suite in 'capabilities-v1','tool-payload-v1','history-compaction-v1') {
    & $Java21 '-Dfile.encoding=UTF-8' -jar $jar evaluation inspect --pack $pack --suite "suites/$suite.json" > (Join-Path $root "$suite-inspect.json")
    if ($LASTEXITCODE -ne 0) { throw "Suite inspection failed: $suite" }
}
if ($Mode -ne 'Evidence') {
    & (Join-Path $pack 'cases/price-filter/validate-case.ps1') -CodeAgentJar $jar -OutputDirectory (Join-Path $root 'legacy') -Java11Home $Java11Home -MavenWrapper $MavenWrapper -Java21 $Java21
    if ($LASTEXITCODE -notin 0,1) { throw 'Legacy qualification failed' }
    foreach ($plan in $expectations.business) {
        $name=$plan.case; $variant=$plan.variant; $label="$name-$variant"
        $prepared=Join-Path $root $label
        & $Java21 -jar $jar evaluation prepare --pack $pack --case "cases/$name/case.json" --output $prepared > (Join-Path $root "$label-prepare.json")
        if ($LASTEXITCODE -ne 0) { throw "Preparation failed: $label" }
        $workspace=Join-Path $prepared 'workspace'
        if ($variant -ne 'baseline') {
            $oldCeiling=$env:GIT_CEILING_DIRECTORIES; Push-Location -LiteralPath $workspace
            try {
                $env:GIT_CEILING_DIRECTORIES=$prepared
                & git apply --ignore-space-change --ignore-whitespace (Join-Path $pack "cases/$name/reference.patch")
                if ($LASTEXITCODE -ne 0) { throw 'Reference patch failed' }
            } finally { Pop-Location; $env:GIT_CEILING_DIRECTORIES=$oldCeiling }
            $controller=Join-Path $workspace 'item-service/src/main/java/com/hmall/item/controller/SearchController.java'
            $text=[IO.File]::ReadAllText($controller)
            switch ($variant) {
                'wrong-default' { $text=$text.Replace('Boolean.TRUE.equals(query.getOnlyInStock())','!Boolean.FALSE.equals(query.getOnlyInStock())') }
                'forgot-stock' { $text=$text -replace '(?m)^.*\.gt\(Boolean.TRUE.equals\(query.getOnlyInStock\(\)\).*\r?\n','' }
                'wrong-pagination' { $text=$text.Replace('query.toMpPage("update_time", false)','new Page<>(1, 100)') }
            }
            [IO.File]::WriteAllText($controller,$text,$utf8)
        }
        $output=Join-Path $prepared 'verification'; New-Item -ItemType Directory -Path $output | Out-Null
        $evidence=Join-Path $prepared 'synthetic-evidence'
        if ($name -eq 'search-multiturn') { New-SyntheticEvidence $name $evidence 'good' }
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $pack 'cases/price-filter/verifier/verify.ps1') -Workspace $workspace -OutputDirectory $output -CaseDefinition (Join-Path $pack "cases/$name/case.json") -AcceptanceProfile (Join-Path $pack "cases/$name/acceptance.json") -EvidenceManifest (Join-Path $evidence 'manifest.json')
        Assert-Result (Join-Path $output 'result.json') $plan.status $plan.failedCheck $label
    }
}
if ($Mode -ne 'Business') {
    foreach ($plan in $expectations.evidence) {
        $name=$plan.case; $variant=$plan.variant; $label="$name-$variant"
        $directory=Join-Path $root $label
        $evidence=Join-Path $directory 'synthetic-evidence'
        New-SyntheticEvidence $name $evidence $variant
        $output=Join-Path $directory 'verification'; New-Item -ItemType Directory -Path $output | Out-Null
        $casePath=Join-Path $pack "cases/$name/case.json"
        $case=Read-Json $casePath
        $workspace=Join-Path (Split-Path (Join-Path $pack $case.snapshot)) 'workspace'
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $pack 'verifiers/verify-evidence.ps1') -Workspace $workspace -OutputDirectory $output -CaseDefinition $casePath -EvidenceManifest (Join-Path $evidence 'manifest.json') -Kind $name
        Assert-Result (Join-Path $output 'result.json') $plan.status $plan.failedCheck $label
    }
}
Write-Output "Qualification complete: $($results.Count) explicit expectations. Synthetic evidence is not an Agent capability result."
