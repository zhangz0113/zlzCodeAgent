package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.EvaluationReport;
import com.zlzcode.codeagent.evaluation.model.EvaluationReport.Row;
import com.zlzcode.codeagent.evaluation.model.EvaluationComparison;
import com.zlzcode.codeagent.evaluation.model.EvaluationComparison.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class EvaluationComparisonService {
    private final ObjectMapper mapper;
    public EvaluationComparisonService(ObjectMapper mapper) { this.mapper = mapper; }

    public EvaluationComparison generate(Path baseline, Path candidate, Path experimentFile, Path output) throws IOException {
        var reports = new EvaluationReportService(mapper);
        Experiment experiment = mapper.readValue(experimentFile.toFile(), Experiment.class);
        if (experiment.title() == null || experiment.hypothesis() == null || experiment.changedFactors() == null)
            throw new IOException("Experiment requires title, hypothesis and changedFactors");
        Path root = EvaluationReportService.newOutput(output, List.of(baseline, candidate));
        var files = new EvaluationEvidenceService(mapper);
        files.copyTree(baseline, root.resolve("baseline"));
        files.copyTree(candidate, root.resolve("candidate"));
        if (experiment.environmentEvidence() != null) for (String evidence : experiment.environmentEvidence()) {
            if (!evidence.startsWith("baseline/") && !evidence.startsWith("candidate/")) throw new IOException("Environment evidence must reference a bundled report");
            CaseCatalogService.resolve(root, evidence);
        }
        EvaluationComparison result = compare(reports.read(root.resolve("baseline")), reports.read(root.resolve("candidate")), experiment, reports.generator());
        new EvaluationReportRenderingService(mapper).comparison(root, result);
        return result;
    }

    public EvaluationComparison compare(EvaluationReport baseline, EvaluationReport candidate, Experiment experiment, String generator) throws IOException {
        Set<String> allowed = new HashSet<>(experiment.changedFactors());
        Set<String> available = new HashSet<>();
        for (Row row : concat(baseline.rows(), candidate.rows())) available.addAll(flatten(row.conditions()).keySet());
        for (String factor : allowed) {
            if (!available.contains(factor) || !(factor.equals("/agentJarSha256") || factor.equals("/provider") || factor.equals("/model")
                    || factor.equals("/baseUrl") || factor.equals("/reasoningEffort") || factor.equals("/runTimeoutSeconds")
                    || factor.equals("/modelTimeoutSeconds") || factor.startsWith("/runtimeProperties/") || factor.startsWith("/effective/context/")
                    || factor.startsWith("/effective/memory/") || factor.startsWith("/effective/tool/")
                    || Set.of("/effective/model", "/effective/provider", "/effective/reasoningEffort", "/effective/runTimeoutSeconds", "/effective/modelTimeoutSeconds").contains(factor)))
                throw new IOException("changedFactors must name an existing allowed leaf condition: " + factor);
        }
        Map<String, List<Row>> left = index(baseline.rows()), right = index(candidate.rows());
        Set<String> keys = new LinkedHashSet<>(left.keySet()); keys.addAll(right.keySet());
        List<Change> changes = new ArrayList<>();
        for (String key : keys) {
            List<Row> a = left.getOrDefault(key, List.of()), b = right.getOrDefault(key, List.of());
            Row representative = a.isEmpty() ? b.getFirst() : a.getFirst();
            List<String> reasons = new ArrayList<>(), declared = new ArrayList<>();
            String conclusion;
            if (a.isEmpty() || b.isEmpty()) {
                boolean otherVersion = concat(baseline.rows(), candidate.rows()).stream().anyMatch(row -> row.group().equals(representative.group())
                        && row.caseId().equals(representative.caseId()) && row.caseVersion() != representative.caseVersion());
                conclusion = otherVersion ? "VERSION_CHANGED" : a.isEmpty() ? "ADDED" : "MISSING";
            } else {
                boolean overlap = a.stream().anyMatch(x -> x.attemptId() != null && b.stream().anyMatch(y -> x.attemptId().equals(y.attemptId())));
                Set<String> identitiesA = identities(a), identitiesB = identities(b);
                if (overlap) {
                    conclusion = identitiesA.equals(identitiesB) && identitiesA.size() == a.size() && identitiesB.size() == b.size()
                            ? "SAME_EXECUTION" : "OVERLAPPING_ATTEMPTS";
                    reasons.add("存在相同 attemptId；审核、分析或旧产物复验不能证明 Agent 改善。");
                } else {
                    // 背景：题面变化和默认配置变化也能改变成绩。设计意图：逐叶比较实际条件，只豁免明确声明的实验变量。
                    // 关键约束：包、快照、验收和审批不允许豁免；否则不同考题或不同判卷会被伪装成 Agent 提升。
                    Row first = a.getFirst();
                    for (Row row : concat(a, b)) {
                        if (first.snapshotId() == null || !Objects.equals(first.snapshotId(), row.snapshotId())) reasons.add("固定源码身份缺失或不同");
                        if (row.conditions().path("casePackSha256").asText().isBlank()) reasons.add("案例包身份缺失");
                        if (row.conditions().path("effective").isEmpty()) reasons.add("实际生效配置缺失");
                    }
                    checkInternal(a, reasons); checkInternal(b, reasons);
                    Map<String, JsonNode> ca = flatten(first.conditions()), cb = flatten(b.getFirst().conditions());
                    Set<String> fields = new TreeSet<>(ca.keySet()); fields.addAll(cb.keySet());
                    for (String field : fields) if (!Objects.equals(ca.get(field), cb.get(field))) {
                        String difference = field + ": " + ca.get(field) + " → " + cb.get(field);
                        if (allowed.contains(field)) declared.add(difference); else reasons.add(difference);
                    }
                    if (!Objects.equals(baseline.generator(), candidate.generator())) reasons.add("两份报告的生成器版本不同，应使用同一工具重新生成");
                    if (!reasons.isEmpty()) conclusion = "NOT_COMPARABLE";
                    else if (concat(a, b).stream().anyMatch(row -> !Set.of("PASS", "FAIL").contains(row.status()))) {
                        conclusion = "INSUFFICIENT_OUTCOMES";
                        reasons.add("包含错误、待审、未执行或数据问题，不能仅凭有效分母推断整体改善。");
                    } else if (concat(a, b).stream().allMatch(this::hasEnvironment)) {
                        double before = EvaluationReportService.summarize(a).passRate(), after = EvaluationReportService.summarize(b).passRate();
                        conclusion = a.size() > 1 || b.size() > 1 ? "REPEATED_DISTRIBUTION"
                                : after > before ? "OBSERVED_IMPROVEMENT" : after < before ? "OBSERVED_REGRESSION" : "UNCHANGED";
                        reasons.add("归档中题目与控制条件一致；" + observed(a, b) + "。小样本观察不代表稳定提升或统计显著性。");
                    } else {
                        // 背景：旧档案缺少外部验收环境。设计意图：声明只形成有限比较，不读取当前机器替历史补证。
                        // 关键约束：不能把声明当成已核实快照，否则环境变化可能被归因于 Agent。
                        conclusion = "LIMITED_COMPARISON";
                        reasons.add(experiment.environmentDeclaration() == null || experiment.environmentDeclaration().isBlank()
                                ? "外部验收环境未记录；仅并排展示观察结果，不自动归因改善。"
                                : "外部验收环境依据实验者声明：" + experiment.environmentDeclaration() + "；不是已核实的历史环境快照。");
                        reasons.add("观察：" + observed(a, b));
                    }
                }
            }
            if (a.size() != b.size()) reasons.add("两侧尝试次数不同：" + a.size() + " / " + b.size());
            var passingA = a.stream().filter(row -> row.status().equals("PASS")).toList();
            var passingB = b.stream().filter(row -> row.status().equals("PASS")).toList();
            boolean commonPassing = !passingA.isEmpty() && !passingB.isEmpty();
            changes.add(new Change(representative.group(), representative.caseId(), representative.caseVersion(), conclusion,
                    reasons.stream().distinct().toList(), declared, EvaluationReportService.summarize(a), EvaluationReportService.summarize(b),
                    EvaluationReportService.summarize(commonPassing ? passingA : List.of()), EvaluationReportService.summarize(commonPassing ? passingB : List.of())));
        }
        return new EvaluationComparison(1, experiment.title(), Instant.now().toString(), generator, experiment, changes, "baseline/report.html", "candidate/report.html");
    }

    private void checkInternal(List<Row> rows, List<String> reasons) {
        for (Row row : rows) if (!rows.getFirst().conditions().equals(row.conditions())) reasons.add("同一侧重复尝试的条件不一致");
    }
    private boolean hasEnvironment(Row row) {
        JsonNode environment = row.conditions().path("verificationEnvironment");
        return List.of("runtime", "runtimeVersion", "buildTool", "buildToolVersion", "os").stream()
                .allMatch(key -> environment.path(key).isTextual() && !environment.path(key).asText().isBlank());
    }
    private String observed(List<Row> a, List<Row> b) {
        double x = EvaluationReportService.summarize(a).passRate(), y = EvaluationReportService.summarize(b).passRate();
        return y > x ? "通过比例上升" : y < x ? "通过比例下降" : x == 1 ? "维持通过" : x == 0 ? "维持失败" : "通过比例不变";
    }
    private Map<String, List<Row>> index(List<Row> rows) {
        Map<String, List<Row>> values = new LinkedHashMap<>();
        for (Row row : rows) values.computeIfAbsent(row.group() + "/" + row.caseId() + "/" + row.caseVersion(), ignored -> new ArrayList<>()).add(row);
        return values;
    }
    private Set<String> identities(List<Row> rows) {
        Set<String> ids = new HashSet<>(); for (Row row : rows) if (row.attemptId() != null) ids.add(row.attemptId()); return ids;
    }
    private static <T> List<T> concat(List<T> a, List<T> b) { List<T> all = new ArrayList<>(a); all.addAll(b); return all; }
    private Map<String, JsonNode> flatten(JsonNode node) { Map<String, JsonNode> values = new TreeMap<>(); flatten("", node, values); return values; }
    private void flatten(String path, JsonNode node, Map<String, JsonNode> values) {
        if (node.isObject() && !node.isEmpty()) node.fields().forEachRemaining(field -> flatten(path + "/" + field.getKey().replace("~", "~0").replace("/", "~1"), field.getValue(), values));
        else values.put(path, node);
    }
}
