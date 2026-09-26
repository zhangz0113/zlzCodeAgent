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
java -jar $agentJar evaluation run --pack $casePack --suite suites/core-v2.json --config backend/target/evaluation-run.json --output backend/target/evaluation/batch-example --repeat 2
```

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
