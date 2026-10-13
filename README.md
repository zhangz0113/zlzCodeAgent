# CodeAgent

## 仓库代码检索

代码检索默认关闭。启用时，在启动后端的环境中配置以下变量；密钥只保存在本机环境，不写入源码：

```powershell
$env:RETRIEVAL_ENABLED = 'true'
$env:RETRIEVAL_QWEN_API_KEY = '<your-qwen-api-key>'
$env:RETRIEVAL_DEEPSEEK_API_KEY = '<your-deepseek-api-key>'
$env:RETRIEVAL_QDRANT_URL = 'https://<your-qdrant-host>'
$env:RETRIEVAL_QDRANT_API_KEY = '<your-qdrant-api-key>'
```

Qdrant 需支持命名向量、BM25 推理和 Query API 的三路 prefetch/RRF（已验证 1.19.2）。默认 embedding 为千问 `qwen3.7-text-embedding-flash`、1024 维；说明生成和源码选材使用 DeepSeek `deepseek-flash`。地址、模型和预算可通过 `codeagent.retrieval` 配置。启动不联网建库。

启动前后端并选择工作区，在“设置 → 代码检索索引”点击“准备索引”。面板展示阶段、文件进度与失败原因。首次准备完成后，Agent 可用 `repository_search` 根据完整业务问题检索原文；例如“商品搜索的价格上下限在哪里处理？只填一个边界时会执行什么条件？”。已知文件窗口继续使用 read，精确正则使用 grep，路径枚举使用 glob。

准备和搜索沿用工作区授权与根 ignore 规则，排除依赖、构建和运行产物；纳入 Java、JS/TS/TSX、文档及常见配置，默认单文件上限 1 MiB。纳入的源码和有限背景会发送给配置的模型服务，并写入该工作区的 Qdrant collection。索引记录默认位于 `workspaces/retrieval-index/`（启动目录为 backend），可用 `RETRIEVAL_STORAGE_DIR` 更换。

每次搜索前按文件哈希同步变化；大批量修改后可提前点击“更新索引”。未准备、忙、需重建、服务失败和源码版本变化均明确报错。返回的是当前文件的真实字节范围和原文，只有最终交付文件计入本 Run 的读取观察；修改仍经过既有版本检查与审批。历史证据缩减后可通过已有 `read_tool_result` 按 evidence 项取回，取回历史不会授予新 Run 编辑资格。

授权工作区的两个接口为：`POST /api/workspaces/{workspaceId}/retrieval-index/prepare`（接受返回 202），`GET /api/workspaces/{workspaceId}/retrieval-index`（状态查询）。索引按工作区隔离，配置需要重建时通过同一准备入口更新；工具内部固定执行混合召回、一轮关系扩展和一次源码选材。

CodeAgent 正在整理为单一 Git 仓库，包含两个独立的应用目录：

- `backend`：Java 21、Spring Boot 和 Maven 后端。
- `web`：React、TypeScript、Vite 和 pnpm 前端。

前后端分别管理依赖、构建和测试入口。仓库不使用 Git 子模块、Maven 多模块或根目录 pnpm workspace。

<a id="web"></a>
## 前端

环境要求：Node.js 和 pnpm 11.7.0。

```powershell
cd web
pnpm install --frozen-lockfile
pnpm build
pnpm dev
```

Vite 开发服务监听 `http://127.0.0.1:5173`。

<a id="backend"></a>
## 后端

环境要求：Java 21。仓库自带 Maven Wrapper，无需另行安装 Maven。

```powershell
cd backend
.\mvnw.cmd clean package
.\mvnw.cmd spring-boot:run
```

后端监听 `http://127.0.0.1:8000`。阶段 0 提供的健康检查接口为：

```text
GET http://127.0.0.1:8000/health
{"status":"ok"}
```

会话文件默认保存在仓库根目录的 `sessions/` 中。需要更换目录时，在启动后端前设置 `CODEAGENT_SESSION_DIRECTORY`。

已授权工作区的状态默认保存在仓库根目录的 `workspaces/authorized-workspaces.json` 中。需要更换文件位置时，在启动后端前设置 `CODEAGENT_WORKSPACE_STATE_FILE`。

阶段 1 增加兼容 OpenAI 协议的模型调用和单轮对话接口。Vite 开发服务默认将 API 请求代理到 `http://127.0.0.1:8000`；直接访问后端时，将 `VITE_AGENT_API_URL` 设置为该地址。

<a id="evaluation-case-preparation-and-independent-verification"></a>
## 评测：案例准备与独立验收

生产 JAR 支持 `evaluation inspect`、`prepare` 和 `verify`。这些命令不启动 Web 应用，也不调用模型。案例包位于 `evaluation/case-packs/`，其版本独立于 Java 实现管理；也可以使用仓库外的案例包路径。

在 `backend` 中使用 Java 21 构建，然后回到仓库根目录执行：

```powershell
Push-Location backend
.\mvnw.cmd -DskipTests package
Pop-Location
$agentJar = (Resolve-Path backend/target/codeagent-backend-0.1.0-SNAPSHOT.jar).Path
$casePack = (Resolve-Path evaluation/case-packs/hmall).Path
java -jar $agentJar evaluation inspect --pack $casePack --suite suites/core.json
java -jar $agentJar evaluation prepare --pack $casePack --case cases/price-filter/case.json --output backend/target/evaluation/example
```

`prepare` 要求输出目录尚不存在。它会核对快照摘要，只复制快照的 `workspace` 内容；`prepared.json`、`snapshot.json` 和任务 `prompt.md` 保留在工作区外。只向 Agent 提供工作区和任务，完整案例包还包含不应向被测 Agent 展示的验收材料与参考修复补丁。

随仓库提供的 hmall 案例使用 Java 11、Maven、PowerShell、H2 2.1.214，以及真实的 Spring MVC / MyBatis 查询，无需启动 Nacos 或 MySQL。验收前设置本机工具路径，下面的 JDK 路径需要替换：

```powershell
$env:EVAL_JAVA_HOME = 'C:\path\to\jdk-11'
$env:EVAL_MAVEN_WRAPPER = (Resolve-Path backend/mvnw.cmd).Path
java -jar $agentJar evaluation verify --pack $casePack --case cases/price-filter/case.json --workspace backend/target/evaluation/example/workspace --output backend/target/evaluation/example-result
```

原始代码预期无法通过仅设置价格上限或下限的要求。使用 Git 和案例包中的参考补丁，可以验证整道题是否有效：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File evaluation/case-packs/hmall/cases/price-filter/validate-case.ps1 -CodeAgentJar $agentJar -OutputDirectory backend/target/evaluation/qualification -Java11Home $env:EVAL_JAVA_HOME -MavenWrapper $env:EVAL_MAVEN_WRAPPER
```

该流程使用同样的十项检查，分别验收未修改快照和独立的参考修复副本，不需要也不会修改原 hmall 项目。资格验证用于确认考题有效，不代表 Agent 的表现。每次运行都使用新的输出路径。

`verification.json` 保存统一格式的验收结果；`execution.json` 记录案例、快照、工作区、耗时和进程退出信息。hmall 验收器还保留 Maven 输出、JUnit XML 和工具环境信息。`PASS`、`FAIL`、`ERROR`、`PENDING_REVIEW` 分别对应 CLI 退出码 0、1、2、3。进程失败或验收响应无效记为 `ERROR`；有效的业务检查未通过记为 `FAIL`。验收器进程只要交付了有效结果，就必须以 0 退出，包括业务检查失败的情况。执行 `clean` 前先归档完整结果目录。

案例包声明命令参数数组、相对案例包的工作目录和超时时间。`${pack}`、`${workspace}`、`${output}` 在各个参数中分别替换，不把模型文本解释为 shell 命令。验收命令向输出目录写入 `result.json`，包含 `status`、`checks`（`id`、`passed`、`detail`）、`evidence`（相对输出目录的文件）和可选 `error`。语言相关的环境准备与结果转换留在案例包内，核心执行器不依赖 Maven、H2 或 hmall。只运行可信的本地案例包；执行器不提供代码沙箱。

可选参数 `prepare --identity FILE` 使用 `batchId`、`agentRevision`、`model`、`provider`、`reasoningEffort`、`strategy`、`budgets` 记录声明的实验信息。未配置的字段保持 null，这份信息不表示模型已经执行。可选参数 `verify --env FILE` 从本地 JSON 映射读取环境变量覆盖值。凭据和本机环境文件应保存在案例包外。

<a id="evaluation-real-agent-runs-and-review"></a>
## 评测：真实 Agent 执行与人工审核

`evaluation run` 串行执行任务集。每次尝试都在独立的 Java 21 进程中启动指定 CodeAgent JAR，使用独立的工作区、会话、记忆、审批、运行记录和日志。工作进程调用真实应用服务，但不监听 HTTP 端口。父进程等待它退出，归档全部修改、新增和删除的文件，再进行独立验收。

先构建后端，再按照以下结构创建本地 `backend/target/evaluation-run.json`，替换模型连接信息和声明的构建版本：

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

`agentJar` 相对于该 JSON 文件解析；JAR 的 SHA-256 与声明的 Git 提交分别记录。通过指定环境变量提供密钥，配置快照和工作进程命令行均不包含密钥。hmall 验收器还需要前文配置的 `EVAL_JAVA_HOME` 和 `EVAL_MAVEN_WRAPPER`。

```powershell
$verifierEnvironment = @{
  EVAL_JAVA_HOME = $env:EVAL_JAVA_HOME
  EVAL_MAVEN_WRAPPER = $env:EVAL_MAVEN_WRAPPER
  PSModulePath = (& powershell.exe -NoProfile -NonInteractive -Command '[Environment]::GetEnvironmentVariable("PSModulePath")').Trim()
}
$verifierEnvironment | ConvertTo-Json | Set-Content backend/target/evaluation-env.json -Encoding utf8
java -jar $agentJar evaluation run --pack $casePack --suite suites/core-v2.json --config backend/target/evaluation-run.json --output backend/target/evaluation/batch-example --repeat 2 --env backend/target/evaluation-env.json
```

hmall 验收器使用 Windows PowerShell。显式传入它的模块路径后，也可以从 PowerShell 7 启动 Java 执行器；否则继承的模块路径可能导致验收器无法加载 `Get-FileHash` 等命令。该环境文件只保存验收配置，模型密钥仍通过独立的进程环境变量提供。

v2 案例声明一个修复步骤，以及针对业务 Java 文件的审批规则，其中排除 `hm-service`。v1 案例和资格验证命令仍可使用。案例的 `steps` 包含 `id`、逻辑 `session` 别名和相对案例包的题面文件。相同别名复用会话，新别名在同一次尝试中创建新会话；未声明步骤时，使用原题面执行一次。审批规则通过 Ant 风格路径声明操作、包含模式和排除模式；没有规则时不允许自动修改。被拒绝的提议会被记录，Agent 仍可继续使用获准操作。

每次尝试包含 `attempt.json`、原始 `worker-result.json`、统一格式的 `execution.json`、`evidence/manifest.json`、`artifact/`、`changes.json`、`changes.patch` 和独立的 `verification/` 输出。清单中各步骤的证据路径相对于 `evidence/`；`runtime/` 保留隔离存储和日志。未被 Git 跟踪的新增文件也会归档，产物与验收工作区使用不同副本。

`initial-result.json` 保留首次结论，`result.json` 保存当前派生结论。Run 执行成功不会自动得到 `PASS`，还需要完成全部必要执行并通过独立检查。已知模型连接故障记为 `ERROR`；整体 `AGENT_RUN_TIMEOUT` 预算耗尽或上下文容量耗尽记为 `FAIL`；未知执行失败保留为 `PENDING_REVIEW`。失败 Run 留下的部分产物即使通过测试，也不能将该次尝试改判为 `PASS`。

案例可用 `id` / `instruction` 对声明 `manualChecks`；只需人工验收的案例可以省略 `verifier`。将生成的 `pending-review.json` 复制为本地输入文件，填写审核人、评论、每项必需检查的 PASS/FAIL 和非空证据文件列表，路径相对于尝试目录，然后导入：

```powershell
java -jar $agentJar evaluation review --attempt PATH_TO_ATTEMPT --input LOCAL_REVIEW_JSON
```

未知执行失败需要填写 `executionClassification`（FAIL 或 ERROR）、原因和证据，不能审核为 PASS。审核记录追加到 `reviews/`，修正后的完整审核会创建新记录；自动失败与原始执行仍保留。`batch.json` 是执行时的索引，审核后应读取每次尝试的当前 `result.json` 获取结论。

解释类或场景类任务的验收器可通过 `${evidence}` 接收证据清单，独立 `verify` 命令也支持为这些案例传入 `--evidence PATH`，已有验收器无需修改参数。工具载荷引用、步骤会话快照和适用记忆会被导出，缺失证据会被标记。记忆存在本身不能证明模型使用过它。token 指标只覆盖已返回的主循环用量，可能不完整，不包含额外压缩和记忆归并调用。

已知 `ERROR` 会中止批次，剩余项保留为 `NOT_RUN`；`FAIL` 和 `PENDING_REVIEW` 不阻止后续尝试。批次退出码优先级为 ERROR=2、FAIL=1、PENDING_REVIEW=3，否则为 0，不自动重试。清理 `target` 前归档整个批次。内部 `worker` 命令是本地实现入口，不是公开服务或安全沙箱。

<a id="evaluation-hmall-capability-cases"></a>
## 评测：hmall 能力案例

案例资产归属、版本、实验冻结和本地输出规则见[评测资产维护](evaluation/README.md)。

案例包包含七项任务，覆盖代码理解、缺陷修复、小功能、多轮与上下文行为，以及跨会话记忆。案例资产、参考补丁和独立验收测试位于 `evaluation/case-packs/hmall/`；只向 Agent 提供快照源码和步骤题面。核心执行器保持独立于 Java、Maven 和 hmall。

| 任务集 | 案例 | 运行条件配置 |
| --- | --- | --- |
| `suites/capabilities-v1.json` | 购物车解释、价格修复 v2、库存筛选、多轮搜索、会话记忆 | `profiles/normal-v1.json` |
| `suites/tool-payload-v1.json` | 从已保存的工具结果中定向取回原文 | `profiles/tool-payload-v1.json` |
| `suites/history-compaction-v1.json` | 历史压缩后保留早期任务约束 | `profiles/history-compaction-v1.json` |

功能题从 `search-base-v1` 开始，它在原固定快照上应用了可信的价格修复。验收保留原有十项价格测试：库存题共 16 项业务检查，多轮搜索题共 23 项业务检查，另加场景证据检查。验收器使用 Java 11、Maven 和本地 H2 构建独立副本，无需服务注册中心、共享数据库或完整微服务集群。

收集模型结果前先完成案例资格验证。在仓库根目录使用前文配置的 `$agentJar`、`$casePack` 和验收环境执行：

```powershell
& "$casePack/validate-phase3.ps1" -CodeAgentJar $agentJar `
  -OutputDirectory backend/target/evaluation/phase3-qualification `
  -Java11Home $env:EVAL_JAVA_HOME -MavenWrapper $env:EVAL_MAVEN_WRAPPER
```

脚本使用新的输出目录，检查旧版兼容性、正确参考、刻意构造的错误实现，以及合成的正反例证据。`-Mode Business` 或 `-Mode Evidence` 可只执行一组。合成证据会明确标识，只用于确认验收器有效，不作为 Agent 能力成绩。与原价格题一样，运行前需要确保 Maven 依赖可用。

运行条件配置作用于整个批次，因此三个任务集应分别搭配对应 profile 执行。基于前文的本地连接配置：

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

模型密钥保存在 `CODEAGENT_EVAL_API_KEY` 中，profiles 不含连接密钥。后续运行使用新的输出路径。只比较案例、快照及声明执行条件一致的尝试；每题执行一次不能证明稳定成功率。

解释、多轮交付、压缩语义和记忆语义都有固定人工检查项。因此自动验收通过后，尝试仍可能处于 `PENDING_REVIEW`，直到前文的 review 命令记录人工决定。人工给出通过结论也不能覆盖自动失败。

机制验收器在检查结果旁导出 `mechanism-evidence.json`。原文取回必须有实际匹配的 `read_tool_result` 响应，通过重新读取文件答对不能替代该证据。压缩要求存在覆盖早期轮次的 checkpoint，并将记忆标为可能的替代信息源。记忆案例检查 A 的存储来源、B 的独立会话和学到的格式。适用记忆导出不能证明某条记忆曾注入某次模型请求。多轮业务测试检查最终产物，执行器不会逐一快照并测试每个中间源码版本。

<a id="offline-evaluation-reports-and-controlled-comparisons"></a>
### 离线评测报告与受控比较

生产 JAR 还提供 `report`、`compare`、`analyze` 和 `focus`。这些命令读取已保存文件，不调用模型，也不启动日常 Web 应用。报告保留输入副本，输出目录必须使用新路径；搬迁时移动整个报告目录，以保留相对证据链接。

创建本地报告选取文件，路径相对于该文件解析：

```json
{
  "id": "capabilities-observation",
  "title": "CodeAgent 能力观察",
  "groups": [
    {"id": "normal", "label": "常规条件", "batch": "phase3-capabilities-v1", "suite": "suites/capabilities-v1.json"},
    {"id": "payload", "label": "工具载荷条件", "batch": "phase3-tool-payload-v1", "suite": "suites/tool-payload-v1.json"},
    {"id": "compaction", "label": "历史压缩条件", "batch": "phase3-history-compaction-v1", "suite": "suites/history-compaction-v1.json"}
  ]
}
```

显式选取正式批次，校准和合成验收检查不计为 Agent 观察结果。比较报告之间的分组 ID 必须保持稳定；即使复制了归档，重复的 attempt ID 仍会被拒绝。

```powershell
java -jar $agentJar evaluation report --input LOCAL_SELECTION_JSON --output NEW_REPORT_DIR
java -jar $agentJar evaluation report --input LOCAL_SELECTION_JSON --issues LOCAL_ISSUES_DIR --output NEW_ANALYZED_REPORT_DIR
java -jar $agentJar evaluation compare --baseline BASELINE_REPORT_DIR --candidate CANDIDATE_REPORT_DIR --experiment LOCAL_EXPERIMENT_JSON --output NEW_COMPARISON_DIR
```

直接打开 `report.html` 或 `comparison.html` 即可查看，CSS、脚本和数据均内嵌，不依赖 CDN 或服务器。Markdown 和 JSON 输出使用同一份计算结果。当前 `result.json` 优先于过期批次索引，同时保留初始结果和审核链接。缺少必要结果属于报告数据问题，不猜测为 FAIL；此时 `report` 以 2 退出。数据完整但包含 Agent 失败尝试的报告以 0 退出。实验不可比时，也会生成有效的比较报告。

实验输入声明假设及实际变化的条件路径。以更换 Agent 构建为例：

```json
{
  "title": "Agent 修改前后比较",
  "hypothesis": "这项修改能够改善选定任务，同时不破坏其他案例。",
  "changedFactors": ["/agentJarSha256"],
  "environmentDeclaration": "说明已知验收环境及其证据；未知时填写 null。",
  "environmentEvidence": []
}
```

条件路径使用报告 JSON 中的 JSON Pointer。必须逐一声明实际发生变化的叶子字段；更换模型可能同时改变 `/model` 和 `/effective/model`。不支持使用通配符豁免。案例包、快照、验收器、人工检查和审批规则的身份不能作为策略变量豁免比较；修改案例后，需要在同一新包上重新建立基线。比较采用实际生效的默认值，并排除已知的每次尝试专属状态路径。

验收器可以在结果旁保存 `environment.json`，以非空字符串记录 `runtime`、`runtimeVersion`、`buildTool`、`buildToolVersion`、`os`，以及相关依赖版本或配置。例如，前端案例记录 Node.js 和 pnpm，Java 案例记录 JDK 和 Maven。这些记录必须描述实际验收环境，不能根据目录名称推测版本。已记录条件一致时可以进行受控观察；旧环境记录不完整或仅有人工声明时，只能给出受限比较。可选的 `environmentEvidence` 引用 `baseline/` 或 `candidate/` 归档内的文件来支持声明，但不会自动认证声明成立。小样本不能证明统计显著性。

重复尝试展示完整分布，不按重复序号一一配对。相同或重叠的 attempt ID 会被明确标出；重新审核答案或复验旧产物不属于新 Agent 尝试。成本只展示已记录的主循环用量及覆盖范围，缺失指标保留未知。只有某题两侧都有通过记录时，才展示该题通过样本的成本比较。

可通过本地 JSON 映射（例如 `{"hmall-price-filter": 3}`）为各题指定重复次数，覆盖默认 `--repeat`；未知案例 ID 或非正数次数会在执行前被拒绝：

```powershell
java -jar $agentJar evaluation run --pack $casePack --suite suites/capabilities-v1.json --config LOCAL_RUN_CONFIG --output NEW_BATCH_DIR --repeat 1 --repeat-map LOCAL_REPEAT_MAP --env LOCAL_VERIFIER_ENV
```

<a id="failure-analysis-and-regression-follow-up"></a>
### 失败分析与回归跟进

分析条目包含 ID、案例身份、引用的尝试、观察事实、问题来源（`AGENT`、`CASE_VERIFIER`、`ENVIRONMENT`、`UNKNOWN`）、原因类别、确定程度（`UNKNOWN`、`SUSPECTED`、`CONFIRMED`）、作者和下一步行动。例如：

```json
{
  "id": "01-observed-problem", "title": "描述观察到的问题",
  "status": "OPEN", "label": "needs-triage", "author": "填写姓名或标注为分析建议",
  "caseId": "hmall-price-filter", "caseVersion": 2,
  "attempts": [{"group": "normal", "attemptId": "COPY_FROM_REPORT", "evidence": ["result.json", "verification/verification.json"]}],
  "observed": "填写这些文件能够支持的事实，与推测原因分开。",
  "origin": "UNKNOWN", "category": "UNKNOWN", "confidence": "UNKNOWN",
  "nextAction": "填写下一项具体排查或复测动作。",
  "change": null, "retestReport": null, "comparisonReport": null, "resolution": null
}
```

```powershell
java -jar $agentJar evaluation analyze --report REPORT_DIR --input LOCAL_ANALYSIS_JSON --issues LOCAL_ISSUES_DIR
java -jar $agentJar evaluation focus --report REPORT_DIR --issues LOCAL_ISSUES_DIR --ids 01-observed-problem --pack $casePack --name regression-focus-example
```

每个问题保存为 Markdown 文件，新分析在 `Comments` 下追加结构化修订，保留旧分析和原始判定。人工成绩审核使用 `review`，产物复验使用 `verify`，问题卡不能替代它们。`READY_FOR_RETEST` 要求填写具体 `change`；`VERIFIED` 要求说明结果，并提供已存在的 `retestReport` 和 `comparisonReport` 目录，将该案例关联到一次新的执行尝试。它表示已经完成验证，不一定表示问题有所改善。

`focus` 在 `suites/<name>/` 下生成新任务集，每个条件组一个文件，另附 `conditions.json`。这些文件引用已有案例，不复制源码、题面或测试。运行时仍需提供选定的 Agent 构建和凭据，生成的条件文件是指导材料，不是完整运行配置。创建这些任务集会改变案例包摘要，因此应在冻结案例包、运行基线和候选版本**之前**生成。关注集结果与全量回归结果分别保存。

对于已有案例未覆盖的新问题：保留问题来源，固定可复现的代码快照与题面，定义独立检查，验证失败起点和正确参考，再增加版本化案例及任务集引用。复用 `inspect`、`prepare`、`verify` 和案例包资格验证入口。无法取得历史源码时，将新案例标注为代表性复现。随后记录基线，按单独审核的方案修改 Agent，运行关注集与全量回归，比较所有观察结果，再将结论关联回问题卡。工具交付本身不代表这一实验流程已经完成。
