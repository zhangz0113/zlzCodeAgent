package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.EvaluationReport;
import com.zlzcode.codeagent.evaluation.model.EvaluationReport.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class EvaluationReportService {
    public static final String METRIC_SCOPE = "仅汇总已记录的主循环 Run 用量；缺失不补零，已有数字不证明调用计量完整；压缩与记忆额外调用不计入。耗时为尝试累计耗时。";
    private final ObjectMapper mapper;
    private final EvaluationEvidenceService files;
    public EvaluationReportService(ObjectMapper mapper) { this.mapper = mapper; this.files = new EvaluationEvidenceService(mapper); }

    public EvaluationReport generate(Path input, Path output, Path issues) throws IOException {
        Input selection = mapper.readValue(input.toFile(), Input.class);
        requireId(selection.id());
        if (selection.title() == null || selection.title().isBlank() || selection.groups() == null || selection.groups().isEmpty())
            throw new IOException("Report requires a title and groups");
        Set<String> ids = new HashSet<>();
        List<Path> sources = new ArrayList<>();
        for (Selection group : selection.groups()) {
            requireId(group.id());
            if (!ids.add(group.id())) throw new IOException("Duplicate group: " + group.id());
            sources.add(input.toAbsolutePath().getParent().resolve(group.batch()).toRealPath());
        }
        if (issues != null) sources.add(issues.toRealPath());
        Path root = newOutput(output, sources);
        Files.createDirectory(root.resolve("sources"));
        // 背景：人工审核会更新当前结果，旧报告必须可复核。设计意图：先复制归档，再从副本计算并保存输入摘要。
        // 关键约束：不得在渲染时回读原目录；否则同一报告里的计数、明细和证据可能来自不同审核时点。
        for (int i = 0; i < selection.groups().size(); i++) files.copyTree(sources.get(i), root.resolve("sources/" + selection.groups().get(i).id()));
        if (issues != null) files.copyTree(issues, root.resolve("issues"));
        files.write(root.resolve("selection.json"), selection);
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("selection.json", EvaluationEvidenceService.sha256(root.resolve("selection.json")));
        List<Group> groups = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> attempts = new HashSet<>();
        var reader = new EvaluationReportInputService(mapper);
        for (Selection group : selection.groups()) {
            Group loaded;
            try { loaded = reader.readGroup(group, root, hashes); }
            catch (IOException exception) { problems.add(group.id() + ": " + exception.getMessage()); continue; }
            groups.add(loaded);
            rows.addAll(reader.readRows(loaded, root, hashes, problems, attempts));
        }
        var cards = issues == null ? List.<com.zlzcode.codeagent.evaluation.model.EvaluationAnalysis.Card>of()
                : new EvaluationAnalysisService(mapper).readCards(root.resolve("issues"));
        for (var card : cards) hashes.put("issues/" + card.path(), EvaluationEvidenceService.sha256(root.resolve("issues").resolve(card.path())));
        Map<String, Summary> categories = new TreeMap<>();
        rows.stream().map(Row::category).distinct().forEach(category -> categories.put(category,
                summarize(rows.stream().filter(row -> row.category().equals(category)).toList())));
        EvaluationReport report = new EvaluationReport(1, selection.id(), selection.title(), Instant.now().toString(), generator(),
                groups, rows, summarize(rows), categories, hashes, problems, cards, METRIC_SCOPE);
        new EvaluationReportRenderingService(mapper).report(root, report);
        return report;
    }

    public EvaluationReport read(Path root) throws IOException {
        EvaluationReport report = mapper.readValue(root.resolve("report.json").toFile(), EvaluationReport.class);
        if (report.schemaVersion() != 1) throw new IOException("Unsupported report schema");
        return report;
    }

    public static Summary summarize(List<Row> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("PASS", "FAIL", "ERROR", "PENDING_REVIEW", "NOT_RUN", "DATA_ISSUE")) counts.put(status, 0);
        Map<String, JsonNode> runs = new LinkedHashMap<>();
        int executed = 0, recordedDurations = 0;
        long duration = 0;
        for (Row row : rows) {
            counts.merge(row.status(), 1, Integer::sum);
            if (row.attemptId() != null && !row.attemptId().isBlank() && !row.status().equals("NOT_RUN")) executed++;
            if (row.totalDurationMs() != null) { duration += row.totalDurationMs(); recordedDurations++; }
            // 背景：同一 Session 的后续快照包含前序 Run。设计意图：只按步骤的唯一 Run 汇总，缺字段单独计覆盖率。
            // 关键约束：不能累加 Session 全历史或把缺失填零，否则多轮成本重复计数并伪造完整用量。
            for (Step step : row.steps()) if (step.runId() != null) runs.putIfAbsent(step.runId(), step.metrics());
        }
        Map<String, Metric> metrics = new LinkedHashMap<>();
        for (String name : List.of("steps", "durationMs", "inputTokens", "outputTokens")) {
            long total = 0; int recorded = 0;
            for (JsonNode values : runs.values()) if (values != null && values.path(name).isNumber()) {
                total += values.path(name).longValue(); recorded++;
            }
            metrics.put(name, new Metric(recorded == 0 ? null : total, recorded, runs.size()));
        }
        int decided = counts.get("PASS") + counts.get("FAIL");
        return new Summary(rows.size(), executed, counts, decided, decided == 0 ? null : (double) counts.get("PASS") / decided,
                metrics, recordedDurations == 0 ? null : duration, recordedDurations);
    }

    public static void requireId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) throw new IOException("Invalid identifier: " + value);
    }

    public static Path newOutput(Path output, List<Path> sources) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path target = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        for (Path source : sources) if (target.startsWith(source.toRealPath())) throw new IOException("Output must be outside its inputs");
        return Files.createDirectory(target);
    }

    public String generator() throws IOException {
        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        for (int i = 0; i + 1 < arguments.length; i++) if (arguments[i].equals("-jar") && Files.isRegularFile(Path.of(arguments[i + 1])))
            return "evaluation-report-v1 sha256:" + EvaluationEvidenceService.sha256(Path.of(arguments[i + 1]));
        try {
            Path location = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return "evaluation-report-v1 sha256:" + (Files.isRegularFile(location) ? EvaluationEvidenceService.sha256(location)
                    : files.treeIdentity(files.treeHashes(location)));
        } catch (java.net.URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException exception) {
            throw new IOException("Cannot identify report generator", exception);
        }
    }
}
