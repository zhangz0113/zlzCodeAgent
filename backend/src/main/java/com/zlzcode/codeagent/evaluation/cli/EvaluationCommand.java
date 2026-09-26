package com.zlzcode.codeagent.evaluation.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.evaluation.model.PreparedEvaluation.ExperimentIdentity;
import com.zlzcode.codeagent.evaluation.model.VerificationResult;
import com.zlzcode.codeagent.evaluation.service.CaseCatalogService;
import com.zlzcode.codeagent.evaluation.service.VerificationService;
import com.zlzcode.codeagent.evaluation.service.WorkspacePreparationService;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class EvaluationCommand {
    public int execute(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("evaluation inspect --pack PATH --suite RELATIVE_JSON\n"
                    + "evaluation prepare --pack PATH --case RELATIVE_JSON --output NEW_DIR [--identity JSON]\n"
                    + "evaluation verify --pack PATH --case RELATIVE_JSON --workspace PATH --output NEW_DIR [--env JSON]");
            return 0;
        }
        try {
            Map<String, String> options = options(args);
            ObjectMapper mapper = new ObjectMapper();
            CaseCatalogService catalog = new CaseCatalogService(mapper);
            Path pack = Path.of(required(options, "pack"));
            Object result;
            int code = 0;
            switch (args[0]) {
                case "inspect" -> result = catalog.inspect(pack, required(options, "suite"));
                case "prepare" -> {
                    ExperimentIdentity identity = options.containsKey("identity")
                            ? mapper.readValue(Path.of(options.get("identity")).toFile(), ExperimentIdentity.class) : null;
                    result = new WorkspacePreparationService(mapper).prepare(
                            catalog.loadCase(pack, required(options, "case")), Path.of(required(options, "output")), identity);
                }
                case "verify" -> {
                    Map<String, String> environment = options.containsKey("env")
                            ? mapper.readValue(Path.of(options.get("env")).toFile(), new TypeReference<>() {}) : Map.of();
                    VerificationResult verification = new VerificationService(mapper).verify(
                            catalog.loadCase(pack, required(options, "case")), Path.of(required(options, "workspace")),
                            Path.of(required(options, "output")), environment);
                    result = verification;
                    code = switch (verification.status()) {
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
        Set<String> allowed = Set.of("pack", "suite", "case", "output", "workspace", "identity", "env");
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
