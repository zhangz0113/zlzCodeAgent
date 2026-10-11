package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.EvaluationReport;
import com.zlzcode.codeagent.evaluation.model.EvaluationComparison;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class EvaluationReportRenderingService {
    private final ObjectMapper mapper;
    public EvaluationReportRenderingService(ObjectMapper mapper) { this.mapper = mapper; }

    public void report(Path root, EvaluationReport report) throws IOException {
        new EvaluationEvidenceService(mapper).write(root.resolve("report.json"), report);
        StringBuilder md = new StringBuilder("# " + text(report.title()) + "\n\n生成时间：" + report.generatedAt()
                + "\n\n[离线交互报告](report.html) · [JSON 明细](report.json)\n\n");
        md.append(summary(report.summary())).append("\n\n").append(report.metricScope()).append("\n\n");
        for (String problem : report.dataIssues()) md.append("- 数据问题：").append(text(problem)).append('\n');
        md.append("\n| 条件组 | 案例 | 版本 | 尝试 | 结果 | 原因 |\n| --- | --- | --- | --- | --- | --- |\n");
        for (var row : report.rows()) md.append("| ").append(text(row.group())).append(" | ").append(text(row.caseId()))
                .append(" | ").append(row.caseVersion()).append(" | ").append(row.attemptIndex()).append(" | ")
                .append(row.status()).append(" | ").append(text(row.reason())).append(" |\n");
        for (var group : report.groups()) md.append("\n## 条件组：").append(text(group.label())).append("\n\n批次：")
                .append(group.batchId()).append("\n\nAgent JAR：").append(group.agentJarSha256())
                .append("\n\n案例包：").append(group.casePackSha256()).append("\n");
        for (var row : report.rows()) {
            md.append("\n## ").append(text(row.group() + " / " + row.caseId())).append(" · 尝试 ").append(row.attemptIndex())
                    .append("\n\n").append(row.status()).append(" — ").append(text(row.detail())).append("\n\n初始状态：")
                    .append(row.initialStatus()).append("；当前状态：").append(row.status()).append("\n\n");
            for (var check : row.checks()) md.append("- ").append(check.path("passed").asBoolean() ? "通过" : "未通过").append("：")
                    .append(text(check.path("id").asText())).append(" — ").append(text(check.path("detail").asText())).append('\n');
            for (String pending : row.pendingChecks()) md.append("- 待审：").append(text(pending)).append('\n');
            for (String missing : row.missingEvidence()) md.append("- 缺失：").append(text(missing)).append('\n');
            md.append("\n证据：\n\n");
            for (var evidence : row.evidence().entrySet()) md.append("- [").append(text(evidence.getKey())).append("](<")
                    .append(url(evidence.getValue())).append(">)\n");
        }
        if (!report.issues().isEmpty()) md.append("\n## 问题分析\n\n");
        for (var card : report.issues()) md.append("- [").append(text(card.current().entry().title())).append("](<")
                .append(url("issues/" + card.path())).append(">)：").append(text(card.current().entry().observed())).append('\n');
        Files.writeString(root.resolve("report.md"), md);
        html(root.resolve("report.html"), Map.of("kind", "report", "data", report));
    }

    public void comparison(Path root, EvaluationComparison comparison) throws IOException {
        new EvaluationEvidenceService(mapper).write(root.resolve("comparison.json"), comparison);
        StringBuilder md = new StringBuilder("# " + text(comparison.title()) + "\n\n" + text(comparison.experiment().hypothesis())
                + "\n\n[基线报告](baseline/report.html) · [候选报告](candidate/report.html) · [交互比较](comparison.html)\n\n");
        for (var change : comparison.changes()) {
            md.append("## ").append(text(change.group() + " / " + change.caseId())).append(" v").append(change.caseVersion())
                    .append("\n\n结论：").append(change.conclusion()).append("\n\n基线：").append(summary(change.baseline()))
                    .append("\n\n候选：").append(summary(change.candidate())).append("\n\n");
            for (String reason : change.reasons()) md.append("- ").append(text(reason)).append('\n');
            for (String difference : change.declaredDifferences()) md.append("- 已声明变量：").append(text(difference)).append('\n');
        }
        Files.writeString(root.resolve("comparison.md"), md);
        html(root.resolve("comparison.html"), Map.of("kind", "comparison", "data", comparison));
    }

    private void html(Path target, Object data) throws IOException {
        // 背景：模型答案可能包含 HTML 或 script 结束标签。设计意图：嵌入 JSON 并由页面 textContent 展示，不插入模型生成的 HTML。
        // 关键约束：JSON 中的 < 必须转义，且不能改用 innerHTML；否则答案会截断数据块或作为脚本执行。
        String json = mapper.writeValueAsString(data).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        String page = resource("report.html").replace("/*REPORT_STYLE*/", resource("report.css"))
                .replace("/*REPORT_SCRIPT*/", resource("report.js")).replace("REPORT_DATA_JSON", json);
        Files.writeString(target, page);
    }
    private String resource(String file) throws IOException {
        try (var input = getClass().getResourceAsStream("/evaluation/" + file)) {
            if (input == null) throw new IOException("Missing report resource: " + file);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private String summary(EvaluationReport.Summary summary) {
        return "计划 " + summary.planned() + "，已执行 " + summary.executed() + "；" + summary.counts()
                + "；有效判定通过率 " + (summary.passRate() == null ? "不适用" : String.format(java.util.Locale.ROOT, "%.1f%%", summary.passRate() * 100))
                + "（分母 " + summary.decided() + "；未形成结论项见计数）";
    }
    private String text(String value) {
        return value == null ? "未记录" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("|", "\\|").replace("[", "\\[").replace("]", "\\]").replace("\r", "").replace("\n", " ");
    }
    private String url(String path) {
        return java.util.Arrays.stream(path.replace('\\', '/').split("/"))
                .map(part -> java.net.URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }
}
