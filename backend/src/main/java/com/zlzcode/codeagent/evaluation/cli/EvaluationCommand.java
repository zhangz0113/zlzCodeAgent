package com.zlzcode.codeagent.evaluation.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zlzcode.codeagent.evaluation.model.EvaluationRun;
import com.zlzcode.codeagent.evaluation.model.PreparedEvaluation.ExperimentIdentity;
import com.zlzcode.codeagent.evaluation.model.VerificationResult;
import com.zlzcode.codeagent.evaluation.service.CaseCatalogService;
import com.zlzcode.codeagent.evaluation.service.VerificationService;
import com.zlzcode.codeagent.evaluation.service.WorkspacePreparationService;
import com.zlzcode.codeagent.evaluation.service.AgentAttemptService;
import com.zlzcode.codeagent.evaluation.service.EvaluationRunnerService;
import com.zlzcode.codeagent.evaluation.service.EvaluationResultService;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class EvaluationCommand {
    public int execute(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("evaluation inspect --pack PATH --suite RELATIVE_JSON\n"
                    + "evaluation prepare --pack PATH --case RELATIVE_JSON --output NEW_DIR [--identity JSON]\n"
                    + "evaluation verify --pack PATH --case RELATIVE_JSON --workspace PATH --output NEW_DIR [--env JSON] [--evidence JSON]\n"
                    + "evaluation run --pack PATH --suite RELATIVE_JSON --config JSON --output NEW_DIR [--repeat N] [--env JSON]\n"
                    + "evaluation review --attempt PATH --input JSON");
            return 0;
        }
        try {
            Map<String, String> options = options(args);
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            CaseCatalogService catalog = new CaseCatalogService(mapper);
            Path pack = options.containsKey("pack") ? Path.of(options.get("pack")) : null;
            Object result;
            int code = 0;
            switch (args[0]) {
                case "inspect" -> result = catalog.inspect(Path.of(required(options, "pack")), required(options, "suite"));
                case "prepare" -> {
                    required(options, "pack");
                    ExperimentIdentity identity = options.containsKey("identity")
                            ? mapper.readValue(Path.of(options.get("identity")).toFile(), ExperimentIdentity.class) : null;
                    result = new WorkspacePreparationService(mapper).prepare(
                            catalog.loadCase(pack, required(options, "case")), Path.of(required(options, "output")), identity);
                }
                case "verify" -> {
                    required(options, "pack");
                    Map<String, String> environment = options.containsKey("env")
                            ? mapper.readValue(Path.of(options.get("env")).toFile(), new TypeReference<>() {}) : Map.of();
                    VerificationResult verification = new VerificationService(mapper).verify(
                            catalog.loadCase(pack, required(options, "case")), Path.of(required(options, "workspace")),
                            Path.of(required(options, "output")), environment,
                            options.containsKey("evidence") ? Path.of(options.get("evidence")) : null);
                    result = verification;
                    code = switch (verification.status()) {
                        case PASS -> 0;
                        case FAIL -> 1;
                        case ERROR -> 2;
                        case PENDING_REVIEW -> 3;
                    };
                }
                case "run" -> {
                    Map<String, String> environment = options.containsKey("env")
                            ? mapper.readValue(Path.of(options.get("env")).toFile(), new TypeReference<>() {}) : Map.of();
                    EvaluationRun.Batch batch = new EvaluationRunnerService(mapper).run(Path.of(required(options, "pack")),
                            required(options, "suite"), Path.of(required(options, "config")), Path.of(required(options, "output")),
                            Integer.parseInt(options.getOrDefault("repeat", "1")), environment);
                    result = batch;
                    code = batch.attempts().stream().anyMatch(entry -> entry.status().equals("ERROR")) ? 2
                            : batch.attempts().stream().anyMatch(entry -> entry.status().equals("FAIL")) ? 1
                            : batch.attempts().stream().anyMatch(entry -> entry.status().equals("PENDING_REVIEW")) ? 3 : 0;
                }
                case "worker" -> result = new AgentAttemptService(mapper).execute(mapper.readValue(
                        Path.of(required(options, "request")).toFile(), EvaluationRun.WorkerRequest.class));
                case "review" -> {
                    EvaluationRun.Result reviewed = new EvaluationResultService(mapper).review(
                            Path.of(required(options, "attempt")), Path.of(required(options, "input")));
                    result = reviewed;
                    code = switch (reviewed.status()) {
                        case PASS -> 0;
                        case FAIL -> 1;
                        case ERROR -> 2;
                        case PENDING_REVIEW -> 3;
                    };
                }
                default -> throw new IllegalArgumentException("Unknown evaluation command: " + args[0]);
            }
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            return code;
        } catch (Exception exception) {
            System.err.println("Evaluation failed: " + exception.getMessage());
            return 2;
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new HashMap<>();
        Set<String> allowed = switch (args[0]) {
            case "inspect" -> Set.of("pack", "suite");
            case "prepare" -> Set.of("pack", "case", "output", "identity");
            case "verify" -> Set.of("pack", "case", "output", "workspace", "env", "evidence");
            case "run" -> Set.of("pack", "suite", "config", "output", "repeat", "env");
            case "worker" -> Set.of("request");
            case "review" -> Set.of("attempt", "input");
            default -> throw new IllegalArgumentException("Unknown evaluation command: " + args[0]);
        };
        for (int index = 1; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 == args.length) {
                throw new IllegalArgumentException("Expected --name value pairs");
            }
            String key = args[index].substring(2);
            if (!allowed.contains(key) || values.putIfAbsent(key, args[index + 1]) != null) {
                throw new IllegalArgumentException("Unknown or duplicate option: " + key);
            }
        }
        return values;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }
}
