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
