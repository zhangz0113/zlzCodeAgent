package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.EvaluationAnalysis.*;
import com.zlzcode.codeagent.evaluation.model.EvaluationReport;
import com.zlzcode.codeagent.evaluation.model.EvaluationReport.Row;
import com.zlzcode.codeagent.evaluation.model.EvaluationSuite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public final class EvaluationAnalysisService {
    private static final Pattern REVISION = Pattern.compile("```evaluation-analysis\\R(.*?)\\R```", Pattern.DOTALL);
    private final ObjectMapper mapper;
    public EvaluationAnalysisService(ObjectMapper mapper) { this.mapper = mapper; }

    public Card analyze(Path reportRoot, Path input, Path issues) throws IOException {
        Entry entry = mapper.readValue(input.toFile(), Entry.class);
        EvaluationReport report = new EvaluationReportService(mapper).read(reportRoot);
        validate(entry, report, reportRoot);
        Files.createDirectories(issues);
        Path file = issues.resolve(entry.id() + ".md");
        String previous = Files.exists(file) ? Files.readString(file) : "";
        List<Revision> history = revisions(previous);
        if (!previous.isEmpty() && history.isEmpty()) throw new IOException("Existing issue is not an evaluation card; refusing to overwrite");
        if (!history.isEmpty()) {
            Entry old = history.getLast().entry();
            if (!old.id().equals(entry.id()) || !old.caseId().equals(entry.caseId()) || old.caseVersion() != entry.caseVersion())
                throw new IOException("Issue identity cannot change");
        }
        Revision revision = new Revision(history.size() + 1, Instant.now().toString(), entry);
        String comments = previous.isEmpty() ? "## Comments\n" : previous.substring(previous.indexOf("## Comments"));
        // 背景：分析会修订，但原始成绩和过去的解释必须可追查。设计意图：Markdown 内追加结构化修订，头部只显示最新摘要。
        // 关键约束：此入口不得调用 review 或改写 result.json；否则原因推测会静默改变评测事实。
        String text = "# " + entry.title() + "\n\nStatus: " + entry.status() + "\nLabel: " + entry.label()
                + "\n\n案例：" + entry.caseId() + " v" + entry.caseVersion() + "\n\n作者：" + entry.author()
                + "\n\n观察：" + entry.observed() + "\n\n来源：" + entry.origin() + "；分类：" + entry.category()
                + "；确定程度：" + entry.confidence() + "\n\n下一步：" + entry.nextAction() + "\n\n"
                + comments + "\n### 修订 " + revision.revision() + " · " + revision.recordedAt()
                + "\n\n```evaluation-analysis\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(revision) + "\n```\n";
        Files.writeString(file, text);
        return new Card(file.getFileName().toString(), revision);
    }

    public List<Card> readCards(Path issues) throws IOException {
        List<Card> cards = new ArrayList<>();
        try (var paths = Files.list(issues)) {
            for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                List<Revision> history = revisions(Files.readString(file));
                if (!history.isEmpty()) cards.add(new Card(file.getFileName().toString(), history.getLast()));
            }
        }
        return cards;
    }

    public Focus focus(Path reportRoot, Path issues, List<String> ids, Path pack, String name) throws IOException {
        EvaluationReportService.requireId(name);
        var report = new EvaluationReportService(mapper).read(reportRoot);
        Map<String, Card> cards = new LinkedHashMap<>();
        for (Card card : readCards(issues)) cards.put(card.current().entry().id(), card);
        Map<String, List<EvaluationSuite.CaseReference>> selected = new LinkedHashMap<>();
        var catalog = new CaseCatalogService(mapper);
        for (String id : ids) {
            if (!cards.containsKey(id)) throw new IOException("Unknown issue: " + id);
            Entry entry = cards.get(id).current().entry();
            for (Reference reference : entry.attempts()) {
                Row row = find(report, reference);
                if (!entry.caseId().equals(row.caseId()) || entry.caseVersion() != row.caseVersion()) throw new IOException("Issue case identity mismatch");
                var loaded = catalog.loadCase(pack, row.casePath());
                if (!loaded.definition().id().equals(row.caseId()) || loaded.definition().version() != row.caseVersion()
                        || !loaded.manifest().snapshotId().equals(row.snapshotId())) throw new IOException("Current case identity differs from report");
                var choice = new EvaluationSuite.CaseReference(row.casePath(), row.caseId(), row.caseVersion());
                List<EvaluationSuite.CaseReference> cases = selected.computeIfAbsent(row.group(), ignored -> new ArrayList<>());
                if (!cases.contains(choice)) cases.add(choice);
            }
        }
        if (selected.isEmpty()) throw new IOException("Select at least one issue with a case");
        Path directory = pack.toRealPath().resolve("suites").resolve(name);
        Files.createDirectories(directory.getParent());
        Files.createDirectory(directory);
        var files = new EvaluationEvidenceService(mapper);
        Map<String, String> suites = new LinkedHashMap<>();
        Map<String, Object> conditions = new LinkedHashMap<>();
        // 背景：正常题与压力题采用不同运行预算。设计意图：按原条件组分别生成 suite，只引用原题。
        // 关键约束：新增 suite 会改变整包摘要，必须在冻结并运行两侧基线之前创建，不能拿旧包成绩直接证明改进。
        for (var group : selected.entrySet()) {
            EvaluationReportService.requireId(group.getKey());
            String relative = "suites/" + name + "/" + group.getKey() + ".json";
            files.write(pack.resolve(relative), new EvaluationSuite(name + "-" + group.getKey(), 1, group.getValue()));
            suites.put(group.getKey(), relative);
            var source = report.groups().stream().filter(item -> item.id().equals(group.getKey())).findFirst().orElseThrow();
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("sourceReport", report.id()); condition.put("sourceBatch", source.batchId());
            condition.put("sourceCasePackSha256", source.casePackSha256());
            var config = source.config().deepCopy();
            if (config instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.remove(List.of("agentJar", "agentRevision", "apiKeyEnv"));
            }
            condition.put("runConfiguration", config);
            condition.put("suite", relative);
            conditions.put(group.getKey(), condition);
        }
        files.write(directory.resolve("conditions.json"), Map.of("groups", conditions,
                "instruction", "先创建关注集再冻结案例包；为两侧分别指定 Agent JAR/构建身份和凭据环境变量，保持各组其余条件一致。关注集与全量结果分别报告。"));
        return new Focus(directory.toString(), suites, "suites/" + name + "/conditions.json");
    }

    private void validate(Entry entry, EvaluationReport report, Path reportRoot) throws IOException {
        EvaluationReportService.requireId(entry.id());
        for (String value : Arrays.asList(entry.title(), entry.author(), entry.observed(), entry.nextAction(), entry.caseId(), entry.category()))
            if (value == null || value.isBlank()) throw new IOException("Analysis requires title, author, observed facts, category and next action");
        if (!Set.of("OPEN", "READY_FOR_RETEST", "VERIFIED").contains(Objects.toString(entry.status(), ""))
                || !Set.of("AGENT", "CASE_VERIFIER", "ENVIRONMENT", "UNKNOWN").contains(Objects.toString(entry.origin(), ""))
                || !Set.of("UNKNOWN", "SUSPECTED", "CONFIRMED").contains(Objects.toString(entry.confidence(), ""))
                || !Set.of("needs-triage", "needs-info", "ready-for-agent", "ready-for-human", "wontfix").contains(Objects.toString(entry.label(), "")))
            throw new IOException("Invalid analysis status, origin, confidence or label");
        if (entry.attempts() == null || entry.attempts().isEmpty()) throw new IOException("Analysis requires attempt evidence");
        for (Reference reference : entry.attempts()) {
            Row row = find(report, reference);
            if (!row.caseId().equals(entry.caseId()) || row.caseVersion() != entry.caseVersion()) throw new IOException("Analysis case identity mismatch");
            if (reference.evidence() == null || reference.evidence().isEmpty()) throw new IOException("Analysis requires evidence links");
            for (String link : reference.evidence()) CaseCatalogService.resolve(reportRoot.resolve(row.source()), link);
        }
        if ("READY_FOR_RETEST".equals(entry.status()) || "VERIFIED".equals(entry.status()))
            if (entry.change() == null || entry.change().isBlank()) throw new IOException("Retest requires a concrete change reference");
        if ("VERIFIED".equals(entry.status())) {
            if (entry.retestReport() == null || entry.comparisonReport() == null || entry.resolution() == null || entry.resolution().isBlank())
                throw new IOException("VERIFIED requires retest, comparison and resolution");
            var retest = new EvaluationReportService(mapper).read(Path.of(entry.retestReport()));
            Path comparisonRoot = Path.of(entry.comparisonReport());
            var comparison = mapper.readTree(comparisonRoot.resolve("comparison.json").toFile());
            var candidate = new EvaluationReportService(mapper).read(comparisonRoot.resolve("candidate"));
            Set<String> old = new HashSet<>(); entry.attempts().forEach(ref -> old.add(ref.attemptId()));
            boolean fresh = retest.rows().stream().anyMatch(row -> row.caseId().equals(entry.caseId()) && row.caseVersion() == entry.caseVersion()
                    && row.attemptId() != null && !old.contains(row.attemptId()) && row.steps().stream().anyMatch(step -> step.runId() != null)
                    && candidate.rows().stream().anyMatch(compared -> Objects.equals(compared.attemptId(), row.attemptId()) && compared.group().equals(row.group())));
            boolean related = false;
            for (var change : comparison.path("changes")) if (entry.caseId().equals(change.path("caseId").asText())
                    && change.path("caseVersion").asInt() == entry.caseVersion()
                    && !Set.of("SAME_EXECUTION", "OVERLAPPING_ATTEMPTS", "ADDED", "MISSING").contains(change.path("conclusion").asText())) related = true;
            if (!fresh || !related) throw new IOException("VERIFIED needs a new executed attempt linked to this case comparison");
        }
    }

    private Row find(EvaluationReport report, Reference reference) throws IOException {
        return report.rows().stream().filter(row -> row.group().equals(reference.group()) && Objects.equals(row.attemptId(), reference.attemptId()))
                .findFirst().orElseThrow(() -> new IOException("Unknown report attempt: " + reference.group() + "/" + reference.attemptId()));
    }

    private List<Revision> revisions(String text) throws IOException {
        List<Revision> values = new ArrayList<>();
        var matcher = REVISION.matcher(text);
        while (matcher.find()) values.add(mapper.readValue(matcher.group(1), Revision.class));
        return values;
    }
}
