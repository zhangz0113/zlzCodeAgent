# CodeAgent

CodeAgent is being refactored into a single Git repository with two independent application packages:

- `backend`: Java 21, Spring Boot, and Maven.
- `web`: React, TypeScript, Vite, and pnpm.

The packages keep separate dependency, build, and test entry points. This repository does not use Git submodules, Maven multi-modules, or a root pnpm workspace.

## Web

Requirements: Node.js and pnpm 11.7.0.

```powershell
cd web
pnpm install --frozen-lockfile
pnpm build
pnpm dev
```

The Vite development server listens on `http://127.0.0.1:5173`.

## Backend

Requirements: Java 21. Maven is provided by the repository wrapper.

```powershell
cd backend
.\mvnw.cmd clean package
.\mvnw.cmd spring-boot:run
```

The backend listens on `http://127.0.0.1:8000`. Its stage 0 health endpoint is:

```text
GET http://127.0.0.1:8000/health
{"status":"ok"}
```

Session files are stored by default in the repository root `sessions/` directory. Set
`CODEAGENT_SESSION_DIRECTORY` before starting the backend to use another directory.

Authorized workspace state is stored by default in the repository root
`workspaces/authorized-workspaces.json` file. Set `CODEAGENT_WORKSPACE_STATE_FILE`
before starting the backend to use another file.

Stage 1 adds the OpenAI-compatible model and single-turn endpoints. The Vite development server proxies API requests to `http://127.0.0.1:8000` by default; set `VITE_AGENT_API_URL` to that address when calling the backend directly.

## Evaluation: case preparation and independent verification

The production executable supports `evaluation inspect`, `prepare`, and `verify`.
These commands do not start the web application or call a model. Case packages are
versioned separately from the Java implementation under `evaluation/case-packs/`;
an external package path works in the same way.

Build with Java 21 from `backend`, then return to the repository root:

```powershell
Push-Location backend
.\mvnw.cmd -DskipTests package
Pop-Location
$agentJar = (Resolve-Path backend/target/codeagent-backend-0.1.0-SNAPSHOT.jar).Path
$casePack = (Resolve-Path evaluation/case-packs/hmall).Path
java -jar $agentJar evaluation inspect --pack $casePack --suite suites/core.json
java -jar $agentJar evaluation prepare --pack $casePack --case cases/price-filter/case.json --output backend/target/evaluation/example
```

`prepare` requires a new output directory. It checks the snapshot hashes and copies
only the snapshot's `workspace` contents. `prepared.json`, `snapshot.json`, and the
task `prompt.md` remain outside that workspace. Only the workspace and task should
be supplied to an Agent; the complete package also contains hidden verification
materials and a reference patch.

The included hmall case uses Java 11, Maven, PowerShell, H2 2.1.214, and real
Spring MVC / MyBatis queries. It does not need Nacos or a running MySQL server.
Set the local tool paths before verification (replace the JDK example path):

```powershell
$env:EVAL_JAVA_HOME = 'C:\path\to\jdk-11'
$env:EVAL_MAVEN_WRAPPER = (Resolve-Path backend/mvnw.cmd).Path
java -jar $agentJar evaluation verify --pack $casePack --case cases/price-filter/case.json --workspace backend/target/evaluation/example/workspace --output backend/target/evaluation/example-result
```

The original code is expected to fail the single-bound price requirements.
To qualify the complete case, use Git and the shipped reference patch:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File evaluation/case-packs/hmall/cases/price-filter/validate-case.ps1 -CodeAgentJar $agentJar -OutputDirectory backend/target/evaluation/qualification -Java11Home $env:EVAL_JAVA_HOME -MavenWrapper $env:EVAL_MAVEN_WRAPPER
```

This checks the unmodified snapshot and a separate reference repair with the same
ten checks. The original hmall source is not needed or modified. This qualification
validates the task, not Agent performance. Use a new output path for each run.

`verification.json` is the normalized result; `execution.json` records the case,
snapshot, workspace, duration and process exit. The hmall verifier additionally
preserves Maven output, JUnit XML and tool environment information. `PASS`, `FAIL`,
`ERROR`, and `PENDING_REVIEW` map to CLI exits 0, 1, 2, and 3. A process failure or
invalid verifier response produces `ERROR`; a valid failing business check produces
`FAIL`. The verifier process itself must exit 0 when delivering a valid result,
including a business failure. Archive complete result directories before `clean`.

Case packages declare a command argument array, package-relative working directory,
and timeout. `${pack}`, `${workspace}`, and `${output}` are replaced in individual
arguments without interpreting model text as a shell command. The command writes
`result.json` into the output directory with `status`, `checks` (`id`, `passed`,
`detail`), `evidence` (output-relative files), and optional `error`. Language-specific
setup and result translation stay in the package. The core does not know Maven,
H2, or hmall. Run only trusted local case packages; this is not a code sandbox.

Optional `prepare --identity FILE` records requested experiment metadata using
`batchId`, `agentRevision`, `model`, `provider`, `reasoningEffort`, `strategy` and
`budgets`. Unconfigured metadata remains null; it does not claim a model was run.
Optional `verify --env FILE` reads a local JSON map of environment overrides.
Keep credentials and machine-specific environment files outside case packages.

## Evaluation: real Agent runs and review

`evaluation run` executes a suite serially. Each attempt launches the selected
CodeAgent JAR in a separate Java 21 process, with its own workspace, sessions,
memory, approvals, run records, and logs. The worker uses the real application
services without listening on an HTTP port. The parent waits for worker exit,
archives all changed/new/deleted files, and then invokes independent verification.

Build the backend first. Create a local `backend/target/evaluation-run.json`
using this shape, replacing the model connection and declared build revision:

```json
{
  "agentJar": "codeagent-backend-0.1.0-SNAPSHOT.jar",
  "agentRevision": "COMMIT_USED_TO_BUILD_THE_JAR",
  "provider": "your-provider",
  "model": "your-model",
  "baseUrl": "https://your-provider.example/v1",
  "reasoningEffort": null,
  "apiKeyEnv": "CODEAGENT_EVAL_API_KEY",
  "runTimeoutSeconds": 300,
  "modelTimeoutSeconds": 120,
  "runtimeProperties": {}
}
```

`agentJar` is relative to that JSON file. Its SHA-256 is recorded separately from
the declared Git revision. Supply the credential through the named environment
variable; it is not included in configuration snapshots or worker command lines.
The hmall verifier also needs `EVAL_JAVA_HOME` and `EVAL_MAVEN_WRAPPER` as above.

```powershell
$verifierEnvironment = @{
  EVAL_JAVA_HOME = $env:EVAL_JAVA_HOME
  EVAL_MAVEN_WRAPPER = $env:EVAL_MAVEN_WRAPPER
  PSModulePath = (& powershell.exe -NoProfile -NonInteractive -Command '[Environment]::GetEnvironmentVariable("PSModulePath")').Trim()
}
$verifierEnvironment | ConvertTo-Json | Set-Content backend/target/evaluation-env.json -Encoding utf8
java -jar $agentJar evaluation run --pack $casePack --suite suites/core-v2.json --config backend/target/evaluation-run.json --output backend/target/evaluation/batch-example --repeat 2 --env backend/target/evaluation-env.json
```

The hmall verifier uses Windows PowerShell. Passing its module path explicitly
also supports launching the Java runner from PowerShell 7, whose inherited module
path can otherwise prevent the verifier from loading commands such as `Get-FileHash`.
This environment file contains verifier settings only; keep the model key in its
separate process environment variable.

The v2 case declares a single repair step and approval rules for business Java
files, excluding `hm-service`. The v1 case and qualification command remain
available. Case `steps` contain `id`, a logical `session` alias, and a package-relative
prompt file. Repeated aliases share a session; a new alias creates a new session
within the same attempt. Missing steps use the original prompt once. Approval
rules declare operations, include patterns, and exclude patterns using Ant-style
paths. Missing approval rules permit no automatic modifications. A denied proposal
is recorded and the Agent can continue using allowed operations.

Each attempt includes `attempt.json`, the original `worker-result.json`, normalized
`execution.json`, `evidence/manifest.json`, `artifact/`, `changes.json`, `changes.patch`,
and independent `verification/` output. Relative evidence paths in the manifest's
step records start at `evidence/`. `runtime/` retains the isolated stores and logs.
New files are included even when they were never tracked by Git. Artifact and
verification workspace are separate copies.

`initial-result.json` preserves the first conclusion. `result.json` is the current
derived conclusion. A successful Run is not an automatic PASS: required execution
must complete and independent checks must pass. Known model connection failures
produce ERROR; the overall `AGENT_RUN_TIMEOUT` budget or context capacity exhaustion
produces FAIL. Unknown execution failures remain PENDING_REVIEW. A failed Run's
partial artifact passing tests cannot change the attempt into PASS.

Cases can declare `manualChecks` as `id` / `instruction` pairs; a manual-only case
may omit `verifier`. Copy the generated `pending-review.json` to a local input file,
fill its reviewer/comment, each required PASS/FAIL and nonempty evidence file list
(paths relative to the attempt directory), then import it:

```powershell
java -jar $agentJar evaluation review --attempt PATH_TO_ATTEMPT --input LOCAL_REVIEW_JSON
```

An unknown failed execution instead requests `executionClassification`: FAIL or
ERROR, a reason, and evidence. It cannot be reviewed as PASS. Reviews are appended
under `reviews/`; a corrected complete review creates another record. Automatic
failures and the original execution are preserved. `batch.json` is the run-time
index; after reviews, read each attempt's current `result.json` for its conclusion.

Verifiers for explanation or scenario tasks may use `${evidence}` to receive the
evidence manifest. Standalone `verify` accepts `--evidence PATH` for those cases;
existing verifiers need no parameter changes. Tool payload references, step session
snapshots and applicable memory are exported, with missing evidence marked. Memory
existence alone does not prove the model used it. Token metrics cover only the
returned main-loop usage and may be incomplete; extra compaction/consolidation
calls are not included in that figure.

A known ERROR stops the batch and preserves the remaining NOT_RUN entries. FAIL
and PENDING_REVIEW do not stop subsequent attempts. Batch exit precedence is
ERROR=2, FAIL=1, PENDING_REVIEW=3, otherwise 0. There are no automatic retries.
Archive the entire batch before cleaning `target`. The internal `worker` command
is a local implementation entry, not a public service or security sandbox.

## Evaluation: hmall capability cases

Case asset ownership, versioning, frozen experiments and local-output rules are
documented in [Evaluation asset maintenance](evaluation/README.md).

The case package includes seven tasks covering code understanding, bug repair,
small features, multi-turn/context behavior, and cross-session memory. Case assets,
reference patches and independent acceptance tests live under
`evaluation/case-packs/hmall/`; only snapshot source and step prompts are presented
to the Agent. The core runner remains independent of Java, Maven and hmall.

| Suite | Cases | Runtime properties |
| --- | --- | --- |
| `suites/capabilities-v1.json` | Cart explanation, price repair v2, stock filter, multi-turn search, session memory | `profiles/normal-v1.json` |
| `suites/tool-payload-v1.json` | Directed retrieval from a stored tool result | `profiles/tool-payload-v1.json` |
| `suites/history-compaction-v1.json` | Early task constraint retained across history compaction | `profiles/history-compaction-v1.json` |

Feature tasks start from `search-base-v1`, which applies the trusted price repair
to the original fixed snapshot. Their checks retain the original 10 price tests:
stock has 16 business checks, and multi-turn search has 23 plus scenario evidence
checks. The verifier builds a separate copy using Java 11, Maven and local H2;
no service registry, shared database or running microservice cluster is required.

Qualify the cases before collecting model results. From the repository root,
using the `$agentJar`, `$casePack` and verifier environment configured above:

```powershell
& "$casePack/validate-phase3.ps1" -CodeAgentJar $agentJar `
  -OutputDirectory backend/target/evaluation/phase3-qualification `
  -Java11Home $env:EVAL_JAVA_HOME -MavenWrapper $env:EVAL_MAVEN_WRAPPER
```

The script uses a new output directory, checks legacy compatibility, verifies
reference and deliberately incorrect implementations, and checks synthetic
positive/negative evidence. `-Mode Business` or `-Mode Evidence` runs one group.
Synthetic evidence is explicitly marked; it is verifier qualification, not a
measurement of Agent capability. Maven dependencies must be available as with
the original price case.

Runtime properties apply to an entire batch, so execute the three suites with
their matching profiles. Starting from the local connection config above:

```powershell
$config = Get-Content backend/target/evaluation-run.json -Raw -Encoding UTF8 | ConvertFrom-Json
$groups = @(
  @{ Suite = 'capabilities-v1'; Profile = 'normal-v1' },
  @{ Suite = 'tool-payload-v1'; Profile = 'tool-payload-v1' },
  @{ Suite = 'history-compaction-v1'; Profile = 'history-compaction-v1' }
)
foreach ($group in $groups) {
  $config.runtimeProperties = Get-Content "$casePack/profiles/$($group.Profile).json" -Raw -Encoding UTF8 | ConvertFrom-Json
  $configPath = "backend/target/evaluation-$($group.Suite).json"
  $config | ConvertTo-Json -Depth 10 | Set-Content $configPath -Encoding utf8
  java -jar $agentJar evaluation run --pack $casePack --suite "suites/$($group.Suite).json" --config $configPath --output "backend/target/evaluation/phase3-$($group.Suite)" --repeat 1 --env backend/target/evaluation-env.json
}
```

Keep the model key in `CODEAGENT_EVAL_API_KEY`; profiles contain no connection
secrets. Use fresh output paths for subsequent runs. Compare only attempts with
the same case/snapshot and declared execution conditions; one attempt per case
does not establish a stable success rate.

Explanation, multi-turn delivery, compaction semantics and memory semantics have
fixed manual checks. An automatic PASS can therefore leave the attempt
PENDING_REVIEW until the review command above records the human decision.
Automatic failures cannot be overridden by a favorable manual review.

Mechanism verifiers export `mechanism-evidence.json` alongside their checks.
Retrieval requires the actual matching `read_tool_result` response, not a correct
answer obtained by rereading the file. Compaction requires a checkpoint covering
the early turn, and reports memory as a possible alternative source. The memory
case checks A's stored provenance and B's independent conversation and learned
format. Applicable-memory exports do not prove which entries were injected into
an individual model request. Multi-turn business tests inspect the final artifact;
the runner does not snapshot and test each intermediate source revision.

### Offline evaluation reports and controlled comparisons

The production JAR also provides `report`, `compare`, `analyze`, and `focus`.
These commands read saved files and do not call a model or start the daily Web
application. Reports preserve a copy of their inputs; always use a new output
directory. Move the whole report directory to retain relative evidence links.

Create a local report selection file (paths are relative to that file):

```json
{
  "id": "capabilities-observation",
  "title": "CodeAgent capability observations",
  "groups": [
    {"id": "normal", "label": "Normal conditions", "batch": "phase3-capabilities-v1", "suite": "suites/capabilities-v1.json"},
    {"id": "payload", "label": "Tool payload conditions", "batch": "phase3-tool-payload-v1", "suite": "suites/tool-payload-v1.json"},
    {"id": "compaction", "label": "Compaction conditions", "batch": "phase3-history-compaction-v1", "suite": "suites/history-compaction-v1.json"}
  ]
}
```

Select formal batches explicitly; calibration and synthetic verifier checks do
not count as Agent observations. Group IDs must remain stable between compared
reports. A duplicate attempt ID is rejected even if its archive was copied.

```powershell
java -jar $agentJar evaluation report --input LOCAL_SELECTION_JSON --output NEW_REPORT_DIR
java -jar $agentJar evaluation report --input LOCAL_SELECTION_JSON --issues LOCAL_ISSUES_DIR --output NEW_ANALYZED_REPORT_DIR
java -jar $agentJar evaluation compare --baseline BASELINE_REPORT_DIR --candidate CANDIDATE_REPORT_DIR --experiment LOCAL_EXPERIMENT_JSON --output NEW_COMPARISON_DIR
```

Open `report.html` or `comparison.html` directly; CSS, scripts, and data are
embedded, with no CDN or server. Markdown and JSON outputs contain the same
calculated results. Current `result.json` takes precedence over a stale batch
index; initial results and reviews remain linked. Missing required results are
report data issues, not guessed FAILs. `report` exits with 2 for these data
issues; a complete report containing failed Agent attempts exits with 0.
An incomparable experiment also produces a valid comparison report.

The experiment input specifies the hypothesis and exact changing condition
paths. For an Agent build change, for example:

```json
{
  "title": "Before and after the Agent change",
  "hypothesis": "The change improves the selected task without breaking other cases.",
  "changedFactors": ["/agentJarSha256"],
  "environmentDeclaration": "Describe the known verifier environment and its evidence; leave null when unknown.",
  "environmentEvidence": []
}
```

Condition paths are JSON pointers shown in report JSON. Declare every actual
changed leaf; a model change can affect both `/model` and `/effective/model`.
There is no wildcard exemption. Case-pack, snapshot, verifier, manual-check and
approval identities cannot be waived as strategy variables. Changed cases need
a new baseline on the same new package. Effective defaults are compared, while
known per-attempt state paths are excluded.

A verifier may save `environment.json` alongside its results, recording
`runtime`, `runtimeVersion`, `buildTool`, `buildToolVersion`, and `os` as nonempty
strings, plus any relevant dependency versions or configuration. For example,
a frontend case can record Node.js and pnpm, and a Java case JDK and Maven.
These records must describe the actual verification environment, not inferred
versions from directory names. Matching recorded conditions permit a controlled
observation; incomplete legacy environment records or a human declaration yield
only a limited comparison. Optional `environmentEvidence` entries reference files
inside `baseline/` or `candidate/` bundles and support that declaration; they do
not automatically certify it. Small samples never establish statistical significance.

Repeated attempts are reported as full distributions, not paired by repetition
number. Same or overlapping attempt IDs are explicitly identified. Re-reviewing
an answer or re-verifying an old artifact is not a new Agent attempt. Costs show
recorded main-loop use and coverage; absent metrics remain unknown. Passing-case
costs are provided only when both sides have passing observations of that case.

For per-case repetition, a local JSON map such as `{"hmall-price-filter": 3}`
overrides the default `--repeat`; unknown case IDs and nonpositive counts are
rejected before execution:

```powershell
java -jar $agentJar evaluation run --pack $casePack --suite suites/capabilities-v1.json --config LOCAL_RUN_CONFIG --output NEW_BATCH_DIR --repeat 1 --repeat-map LOCAL_REPEAT_MAP --env LOCAL_VERIFIER_ENV
```

### Failure analysis and regression follow-up

An analysis entry has an ID, case identity, referenced attempts, observed facts,
origin (`AGENT`, `CASE_VERIFIER`, `ENVIRONMENT`, `UNKNOWN`), cause category,
confidence (`UNKNOWN`, `SUSPECTED`, `CONFIRMED`), author, and next action. Example:

```json
{
  "id": "01-observed-problem", "title": "Describe the observed problem",
  "status": "OPEN", "label": "needs-triage", "author": "Your name / analysis proposal",
  "caseId": "hmall-price-filter", "caseVersion": 2,
  "attempts": [{"group": "normal", "attemptId": "COPY_FROM_REPORT", "evidence": ["result.json", "verification/verification.json"]}],
  "observed": "Facts supported by those files, separately from a proposed cause.",
  "origin": "UNKNOWN", "category": "UNKNOWN", "confidence": "UNKNOWN",
  "nextAction": "The next concrete investigation or retest.",
  "change": null, "retestReport": null, "comparisonReport": null, "resolution": null
}
```

```powershell
java -jar $agentJar evaluation analyze --report REPORT_DIR --input LOCAL_ANALYSIS_JSON --issues LOCAL_ISSUES_DIR
java -jar $agentJar evaluation focus --report REPORT_DIR --issues LOCAL_ISSUES_DIR --ids 01-observed-problem --pack $casePack --name regression-focus-example
```

Each issue is a Markdown file. New analysis appends a structured revision under
`Comments`, preserving earlier analysis and the original result. Use `review`
for declared human judgments and `verify` for artifact rechecks. Neither is
replaced by an issue card. `READY_FOR_RETEST` requires a concrete `change`;
`VERIFIED` requires an explanation and existing `retestReport` and
`comparisonReport` directories linking a new executed attempt to this case.
It means verification was performed, not that the issue necessarily improved.

Focus outputs are new suites under `suites/<name>/`, one per condition group,
plus `conditions.json`. They reference existing cases without copying sources,
prompts or tests. Supply the chosen Agent build and credentials when running;
the generated conditions file is guidance, not a complete run configuration.
Creating these suites changes the package hash: create them **before** freezing
the package and running both baseline and candidate. Keep focus results and
full-suite regression results separate.

For a newly discovered issue not covered by an existing case: preserve its
source, fix a reproducible code snapshot and prompt, define independent checks,
validate the failing start and a correct reference, then add a versioned case
and suite reference. Use existing `inspect`, `prepare`, and `verify` commands
and the package's qualification entry. When the historical source is unavailable,
label the new case as a representative reproduction. Then record the baseline,
make the separately reviewed Agent change, run focused and full regression,
compare all observations, and link the outcome back to the issue. Tool delivery
alone does not constitute this completed experiment.
