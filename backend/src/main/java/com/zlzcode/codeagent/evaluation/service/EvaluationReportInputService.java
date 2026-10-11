package com.zlzcode.codeagent.evaluation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zlzcode.codeagent.evaluation.model.EvaluationReport.*;
import com.zlzcode.codeagent.evaluation.model.EvaluationSuite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class EvaluationReportInputService {
    private final ObjectMapper mapper;
    public EvaluationReportInputService(ObjectMapper mapper) { this.mapper = mapper; }

    public Group readGroup(Selection selection, Path root, Map<String, String> hashes) throws IOException {
        JsonNode batch = read(root, "sources/" + selection.id() + "/batch.json", hashes);
        if (batch.path("schemaVersion").asInt() != 1 || !batch.path("attempts").isArray())
            throw new IOException("Invalid batch: " + selection.id());
        return new Group(selection.id(), selection.label(), batch.path("batchId").asText(), selection.suite(),
                mapper.treeToValue(batch.path("suite"), EvaluationSuite.class), batch.path("config"),
                batch.path("agentJarSha256").asText(), batch.path("casePackSha256").asText());
    }

    public List<Row> readRows(Group group, Path root, Map<String, String> hashes,
            List<String> problems, Set<String> attemptIds) throws IOException {
        String batchPath = "sources/" + group.id();
        JsonNode batch = read(root, batchPath + "/batch.json", hashes);
        List<Row> rows = new ArrayList<>();
        for (JsonNode slot : batch.path("attempts")) {
            String id = slot.path("attemptId").asText("");
            if (!id.isBlank() && !attemptIds.add(id)) throw new IOException("Duplicate attemptId: " + id);
            try { rows.add(readRow(group, slot, root, hashes)); }
            catch (IOException | IllegalArgumentException exception) {
                String detail = group.id() + "/" + slot.path("caseId").asText() + "/"
                        + slot.path("attemptIndex").asInt() + ": " + exception.getMessage();
                problems.add(detail);
                rows.add(emptyRow(group, slot, "DATA_ISSUE", detail, ""));
            }
        }
        return rows;
    }

    private Row readRow(Group group, JsonNode slot, Path root, Map<String, String> hashes) throws IOException {
        if ("NOT_RUN".equals(slot.path("status").asText())) return emptyRow(group, slot, "NOT_RUN", "未执行", "");
        String resultPath = "sources/" + group.id() + "/" + slot.path("resultPath").asText();
        JsonNode result = read(root, resultPath, hashes);
        String source = resultPath.substring(0, resultPath.lastIndexOf('/'));
        if (resultPath.endsWith("/preparation-error.json"))
            return emptyRow(group, slot, "ERROR", result.path("detail").asText(), source);
        String id = slot.path("attemptId").asText();
        if (!id.equals(result.path("attemptId").asText())
                || !slot.path("caseId").equals(result.path("caseId"))
                || !slot.path("caseVersion").equals(result.path("caseVersion"))) throw new IOException("Result identity mismatch");
        String status = result.path("status").asText();
        if (!Set.of("PASS", "FAIL", "ERROR", "PENDING_REVIEW").contains(status)) throw new IOException("Invalid result status");
        JsonNode attempt = read(root, source + "/attempt.json", hashes);
        if (!id.equals(attempt.path("prepared").path("attemptId").asText())) throw new IOException("Attempt identity mismatch");
        List<String> missing = new ArrayList<>();
        JsonNode initial = optional(root, source + "/initial-result.json", hashes, missing);
        JsonNode execution = optional(root, source + "/execution.json", hashes, missing);
        JsonNode manifest = optional(root, source + "/evidence/manifest.json", hashes, missing);
        JsonNode verification = optional(root, source + "/verification/verification.json", hashes, missing);
        execution.path("missingEvidence").forEach(value -> missing.add(value.asText()));
        List<Step> steps = new ArrayList<>();
        for (JsonNode step : execution.path("steps")) {
            JsonNode run = step.path("run");
            steps.add(new Step(step.path("id").asText(), step.path("session").asText(), step.path("status").asText(),
                    run.path("runId").asText(null), run.path("finalAnswer").asText(null), run.path("errorCode").asText(null),
                    run.path("metrics"), step.path("evidencePath").isTextual() ? source + "/evidence/" + step.path("evidencePath").asText() : null));
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        Path attemptRoot = CaseCatalogService.resolve(root, source);
        try (var paths = Files.walk(attemptRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = attemptRoot.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith("evidence/") || relative.startsWith("verification/") || relative.startsWith("reviews/")
                        || Set.of("result.json", "initial-result.json", "pending-review.json", "changes.json", "changes.patch", "attempt.json", "execution.json").contains(relative)) {
                    String link = source + "/" + relative;
                    evidence.put(relative, link);
                    hashes.put(link, EvaluationEvidenceService.sha256(path));
                }
            }
        }
        JsonNode definition = attempt.path("definition");
        if (!slot.path("caseId").equals(definition.path("id")) || !slot.path("caseVersion").equals(definition.path("version"))
                || !group.agentJarSha256().equals(attempt.path("agentJarSha256").asText())
                || !group.casePackSha256().equals(attempt.path("casePackSha256").asText())) throw new IOException("Attempt and batch identities differ");
        ObjectNode conditions = (ObjectNode) conditions(group, attempt, execution);
        if (Files.isRegularFile(root.resolve(source + "/verification/environment.json"))) {
            JsonNode environment = optional(root, source + "/verification/environment.json", hashes, missing);
            if (environment.has("runtime") && environment.has("buildTool")) conditions.set("verificationEnvironment", environment);
        }
        return new Row(group.id(), slot.path("caseId").asText(), slot.path("caseVersion").asInt(),
                casePath(group, slot), definition.path("category").asText("unknown"), slot.path("attemptIndex").asInt(), id,
                status, initial.path("status").asText(null), result.path("reasonCode").asText(), result.path("detail").asText(),
                attempt.path("prepared").path("snapshotId").asText(), source, conditions,
                steps, verification.path("checks"), strings(result.path("pendingChecks")), evidence,
                number(manifest, "runDurationMs"), number(manifest, "verificationDurationMs"), number(manifest, "totalDurationMs"), missing);
    }

    // 背景：不同尝试的隔离路径必然不同，默认配置也可能随 JAR 改变。设计意图：保存实际生效条件，仅排除已知隔离路径。
    // 关键约束：不能只比较显式覆盖项或忽略整个配置对象，否则默认行为变化会被误认为公平比较。
    private JsonNode conditions(Group group, JsonNode attempt, JsonNode execution) {
        ObjectNode result = mapper.createObjectNode();
        JsonNode config = attempt.path("config");
        for (String key : List.of("provider", "model", "baseUrl", "reasoningEffort", "runTimeoutSeconds", "modelTimeoutSeconds"))
            result.set(key, config.path(key).isMissingNode() ? mapper.nullNode() : config.path(key));
        result.put("agentJarSha256", group.agentJarSha256());
        result.put("casePackSha256", group.casePackSha256());
        result.set("runtimeProperties", config.path("runtimeProperties"));
        JsonNode effective = execution.path("effectiveConfiguration").deepCopy();
        if (effective.path("context") instanceof ObjectNode context) context.remove("resultDirectory");
        if (effective.path("memory") instanceof ObjectNode memory) memory.remove("stateFile");
        result.set("effective", effective);
        result.set("approvalRules", attempt.path("definition").path("approvalRules"));
        result.set("verifier", attempt.path("definition").path("verifier"));
        result.set("manualChecks", attempt.path("definition").path("manualChecks"));
        return result;
    }

    private Row emptyRow(Group group, JsonNode slot, String status, String detail, String source) {
        return new Row(group.id(), slot.path("caseId").asText(), slot.path("caseVersion").asInt(), casePath(group, slot), "unknown",
                slot.path("attemptIndex").asInt(), slot.path("attemptId").asText(null), status, null, status, detail,
                null, source, mapper.createObjectNode(), List.of(), mapper.createArrayNode(), List.of(),
                source.isBlank() ? Map.of() : Map.of("preparation-error.json", source + "/preparation-error.json"), null, null, null, List.of());
    }

    private String casePath(Group group, JsonNode slot) {
        return group.suite().cases().stream().filter(item -> item.id().equals(slot.path("caseId").asText())
                && item.version() == slot.path("caseVersion").asInt()).map(EvaluationSuite.CaseReference::path).findFirst().orElse(null);
    }

    private JsonNode optional(Path root, String relative, Map<String, String> hashes, List<String> missing) {
        try { return read(root, relative, hashes); }
        catch (IOException exception) { missing.add(relative + ": " + exception.getMessage()); return mapper.createObjectNode(); }
    }

    private JsonNode read(Path root, String relative, Map<String, String> hashes) throws IOException {
        Path file = CaseCatalogService.resolve(root, relative);
        JsonNode node = mapper.readTree(file.toFile());
        if (node == null) throw new IOException("Empty JSON: " + relative);
        hashes.put(relative, EvaluationEvidenceService.sha256(file));
        return node;
    }

    private static Long number(JsonNode node, String key) { return node.path(key).isNumber() ? node.path(key).longValue() : null; }
    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>(); array.forEach(item -> values.add(item.asText())); return values;
    }
}
