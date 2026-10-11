param(
    [Parameter(Mandatory = $true)][string]$Workspace,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$CaseDefinition,
    [Parameter(Mandatory = $true)][string]$EvidenceManifest,
    [Parameter(Mandatory = $true)][ValidateSet('cart-location','search-multiturn','tool-payload','history-compaction','session-memory')][string]$Kind
)
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
$checks = [Collections.Generic.List[object]]::new()
$observations = [ordered]@{ kind = $Kind; synthetic = $false; steps = @(); alternativeMemorySource = $false }
function Read-Json([string]$Path) { Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json }
function Add-Check([string]$Id, [bool]$Passed, [string]$Detail) {
    $checks.Add([ordered]@{ id = $Id; passed = $Passed; detail = $Detail })
}
function Save-Result([string]$Status, [string]$ErrorMessage) {
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'mechanism-evidence.json'), ($observations | ConvertTo-Json -Depth 30), $utf8)
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'result.json'), ([ordered]@{
        status = $Status; checks = @($checks.ToArray()); evidence = @('mechanism-evidence.json'); error = $ErrorMessage
    } | ConvertTo-Json -Depth 30), $utf8)
}
function Has-All([string]$Text, $Values) {
    foreach ($value in $Values) { if (-not $Text.Contains([string]$value)) { return $false } }
    return $true
}
function Get-Payload($Step, [string]$Reference) {
    $match = @($Step.references | Where-Object { $_.reference -eq $Reference -and $_.sessionId -eq $Step.session.sessionId })
    if (-not $match.Count) { throw "Missing exported reference: $Reference" }
    Read-Json (Join-Path $evidenceRoot $match[0].path)
}
function Get-Arguments($Step, $Call) {
    $raw = if ($Call.argumentsRef) { (Get-Payload $Step $Call.argumentsRef).content } else { $Call.arguments }
    $raw | ConvertFrom-Json
}
function Get-Calls($Step) {
    $turn = @($Step.session.turns | Where-Object { $_.runId -eq $Step.run.runId })
    @($turn | ForEach-Object { $_.assistant.toolRounds } | ForEach-Object { $_.calls } | Where-Object { $null -ne $_ })
}

try {
    $case = Read-Json $CaseDefinition
    $expected = Read-Json (Join-Path (Split-Path $CaseDefinition) 'expected.json')
    $pack = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
    $manifest = Read-Json $EvidenceManifest
    $evidenceRoot = Split-Path ([IO.Path]::GetFullPath($EvidenceManifest))
    $observations.synthetic = $manifest.synthetic -eq $true
    $steps = @($manifest.execution.steps)
    $completed = $steps.Count -eq $case.steps.Count -and @($steps | Where-Object { $_.status -ne 'SUCCEEDED' }).Count -eq 0
    Add-Check 'steps-completed' $completed 'Every declared step must finish successfully; failed preparation turns remain part of the task.'
    if (-not $completed) {
        # Background: a run can fail after successfully persisting a history checkpoint.
        # Intent: retain that partial mechanism observation while failing the complete task.
        # Constraint: a checkpoint alone cannot turn a failed run into PASS or prove final constraint compliance.
        $observations.steps = @($steps | Select-Object id,sessionId,status,evidencePath)
        if ($Kind -eq 'history-compaction') {
            $lastExport = @($steps | Where-Object { $_.evidencePath } | Select-Object -Last 1)
            if ($lastExport.Count) {
                $partial = Read-Json (Join-Path (Join-Path $evidenceRoot $lastExport[0].evidencePath) 'session.json')
                $observations.checkpoint = $partial.historyCheckpoint
                $observations.triggered = $null -ne $partial.historyCheckpoint
                $observations.finalBehavior = 'Not completed; checkpoint observation does not satisfy the task.'
                $observations.alternativeMemorySource = @($steps | Where-Object { $_.evidencePath } | ForEach-Object {
                    Read-Json (Join-Path (Join-Path $evidenceRoot $_.evidencePath) 'memories.json')
                } | Where-Object { ([string]$_.content).Contains('CPT-47') }).Count -gt 0
            }
        }
        Save-Result 'FAIL' $null; exit 0
    }
    if (@($manifest.missingEvidence).Count -or @($manifest.execution.missingEvidence).Count) { throw 'Runner reported missing evidence' }
    $snapshots = @()
    for ($i = 0; $i -lt $steps.Count; $i++) {
        $step = $steps[$i]
        $dir = Join-Path $evidenceRoot $step.evidencePath
        $item = [pscustomobject]@{
            run = Read-Json (Join-Path $dir 'run.json')
            session = Read-Json (Join-Path $dir 'session.json')
            memories = @(Read-Json (Join-Path $dir 'memories.json'))
            references = @(Read-Json (Join-Path $dir 'references.json'))
            answer = [IO.File]::ReadAllText((Join-Path $dir 'answer.md'))
        }
        Add-Check "step-$($i+1)-identity" ($step.id -eq $case.steps[$i].id -and $step.session -eq $case.steps[$i].session -and $step.run.runId -eq $item.run.runId -and $step.sessionId -eq $item.session.sessionId) 'Manifest, case order and exported step must identify the same run/session.'
        $snapshots += $item
    }
    $observations.steps = @($steps | Select-Object id,sessionId,evidencePath)
    $first = $snapshots[0]
    $last = $snapshots[-1]
    $sameWorkspace = @($snapshots | ForEach-Object { $_.session.workspaceId } | Select-Object -Unique).Count -eq 1
    Add-Check 'same-workspace' $sameWorkspace 'Scenario steps share one isolated workspace.'
    if ($Kind -ne 'session-memory') {
        Add-Check 'same-session' (@($snapshots | ForEach-Object { $_.session.sessionId } | Select-Object -Unique).Count -eq 1) 'All steps in this scenario use one session.'
    }
    if ($Kind -ne 'search-multiturn') {
        $baseline = Read-Json (Join-Path $pack $case.snapshot)
        $root = (Resolve-Path -LiteralPath $Workspace).Path.TrimEnd('\','/')
        $files = @(Get-ChildItem -LiteralPath $root -File -Recurse -Force)
        $unchanged = $files.Count -eq $baseline.files.Count
        foreach ($entry in $baseline.files) {
            $path = Join-Path $root $entry.path
            if (-not (Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { $unchanged = $false }
        }
        Add-Check 'read-only-tree' $unchanged 'The complete source tree must match the fixed snapshot; file notes cannot transmit answers.'
    }
    switch ($Kind) {
        'cart-location' {
            foreach ($path in $expected.paths) {
                Add-Check ('location-' + [IO.Path]::GetFileNameWithoutExtension($path)) ($last.answer.Replace('\','/').Contains($path)) "Required source location: $path"
            }
            foreach ($symbol in $expected.symbols) { Add-Check "symbol-$symbol" ($last.answer.Contains($symbol)) "Required symbol: $symbol; semantics require manual review." }
        }
        'search-multiturn' {
            Add-Check 'retained-turns' (@($last.session.turns).Count -eq $expected.steps) 'The final session contains the full three-turn interaction; business tests use its final artifact.'
        }
        'tool-payload' {
            # Background: a correct answer may come from a direct reread rather than stored-payload retrieval.
            # Intent: correlate the first read, its hidden payload and the later tool response, not just answer keywords.
            # Constraint: only calls in the current step count; scanning cumulative history would falsely reuse earlier calls.
            $candidates = @()
            foreach ($call in @(Get-Calls $first)) {
                if ($call.name -ne 'read' -or -not $call.result.success -or -not $call.result.resultRef) { continue }
                $args = Get-Arguments $first $call
                if (-not ([string]$args.file_path).Replace('\','/').EndsWith($expected.sourcePath)) { continue }
                $payload = Get-Payload $first $call.result.resultRef
                if ((Has-All $payload.content @($expected.record,$expected.value)) -and $payload.sessionId -eq $first.session.sessionId) {
                    $candidates += [pscustomobject]@{ call = $call; payload = $payload }
                }
            }
            Add-Check 'spilled-target' ($candidates.Count -gt 0) 'The first read must export a stored payload containing the target record and value.'
            $previewAbsent = $candidates.Count -gt 0
            foreach ($candidate in $candidates) { if ($candidate.call.result.modelContent.Contains($expected.value)) { $previewAbsent = $false } }
            Add-Check 'target-hidden' ($previewAbsent -and -not $first.answer.Contains($expected.value)) 'The initial preview and answer must not already reveal the target value.'
            $retrieved = $false
            foreach ($call in @(Get-Calls $last)) {
                if ($call.name -ne 'read_tool_result' -or -not $call.result.success) { continue }
                $args = Get-Arguments $last $call
                if ($args.result_ref -in @($candidates | ForEach-Object { $_.payload.reference }) -and (Has-All $call.result.modelContent @($expected.record,$expected.value))) { $retrieved = $true }
            }
            Add-Check 'matching-retrieval' $retrieved 'A successful read_tool_result must use that reference and return the actual target text.'
            Add-Check 'no-source-reread' (@(Get-Calls $last | Where-Object { $_.name -in 'read','grep' }).Count -eq 0) 'The directed retrieval task cannot be satisfied by rereading source.'
            Add-Check 'correct-answer' (Has-All $last.answer @($expected.record,$expected.value)) 'Final answer must contain the correct record and value.'
            $observations.references = @($candidates | ForEach-Object { $_.payload.reference })
            $observations.triggered = $candidates.Count -gt 0
        }
        'history-compaction' {
            $checkpoint = $last.session.historyCheckpoint
            $ids = @($last.session.turns | ForEach-Object { $_.runId })
            $covered = [Array]::IndexOf($ids, $checkpoint.coveredThroughRunId)
            $earliest = [Array]::IndexOf($ids, $first.run.runId)
            $valid = $null -ne $checkpoint -and $earliest -ge 0 -and $covered -ge $earliest -and $covered -lt ($ids.Count - 2)
            if ($valid) {
                $valid = @($last.session.turns[0..$covered] | Where-Object { $_.state -ne 'COMPLETED' }).Count -eq 0
            }
            Add-Check 'checkpoint-covers-first' $valid 'Persisted checkpoint must cover the initial completed turn and retain recent turns.'
            Add-Check 'summary-retains-marker' ([string]$checkpoint.summaryContent).Contains($expected.marker) 'The checkpoint must retain the unique temporary delivery marker; semantic review checks its meaning.'
            Add-Check 'final-marker' ($last.answer.TrimStart().StartsWith($expected.marker)) 'Final delivery begins with the original temporary marker.'
            Add-Check 'evidence-sections' (Has-All $last.answer $expected.sections) 'Final delivery distinguishes source findings from unverified runtime behavior.'
            $observations.triggered = $null -ne $checkpoint
            $observations.checkpoint = $checkpoint
            $observations.alternativeMemorySource = @($snapshots | ForEach-Object { $_.memories } | Where-Object { ([string]$_.content).Contains('CPT-47') }).Count -gt 0
            $observations.attribution = 'Applicable memory is exported, not selected-memory telemetry; no claim of exclusive causal benefit.'
        }
        'session-memory' {
            # Background: memory existence alone cannot show transfer to a new conversation.
            # Intent: combine provenance, session isolation, unchanged source and an arbitrary learned output convention.
            # Constraint: use the A-after memory snapshot, not B-after; B may rewrite provenance during consolidation.
            $initial = Read-Json (Join-Path $evidenceRoot 'initial-state.json')
            Add-Check 'initial-memory-empty' (@($initial.memories).Count -eq 0) 'The attempt begins without applicable memory.'
            Add-Check 'different-session' ($first.session.sessionId -ne $last.session.sessionId) 'The application step must use a new session.'
            Add-Check 'no-imported-turns' (@($last.session.turns).Count -eq 1 -and $last.session.turns[0].runId -eq $last.run.runId) 'The new session contains only its own turn.'
            $learned = @($first.memories | Where-Object {
                $_.scope -eq 'WORKSPACE' -and $_.workspaceId -eq $first.session.workspaceId -and $_.sourceSessionId -eq $first.session.sessionId -and $_.sourceRunId -eq $first.run.runId -and (Has-All $_.content $expected.headers)
            })
            Add-Check 'learned-with-provenance' ($learned.Count -gt 0) 'A creates the workspaces format memory, with the actual source session and run.'
            $prompt = [string]$last.session.turns[0].user.content
            Add-Check 'no-prompt-reminder' (-not (Has-All $prompt $expected.headers)) 'B does not repeat the learned format.'
            $pattern = '\|?\s*' + (($expected.headers | ForEach-Object { [Regex]::Escape($_) }) -join '\s*\|\s*') + '\s*\|?'
            Add-Check 'learned-table-format' ($last.answer -match $pattern) 'B uses the learned table headers in the specified order.'
            $observations.learnedEntries = $learned
            $observations.attribution = 'Provenance and behavior observed; actual selected-memory injection is not exported.'
        }
    }
    $status = if (@($checks | Where-Object { -not $_.passed }).Count) { 'FAIL' } else { 'PASS' }
    Save-Result $status $null
} catch { Save-Result 'ERROR' $_.Exception.Message }
