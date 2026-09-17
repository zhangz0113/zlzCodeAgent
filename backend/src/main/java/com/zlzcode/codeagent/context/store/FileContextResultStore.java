package com.zlzcode.codeagent.context.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.context.config.ContextProperties;
import com.zlzcode.codeagent.context.exception.ContextReductionException;
import com.zlzcode.codeagent.context.model.ContextReduction.PayloadKind;
import com.zlzcode.codeagent.context.model.ContextReduction.PayloadRole;
import com.zlzcode.codeagent.context.model.ContextReduction.ReductionScope;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class FileContextResultStore {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,160}");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("result_[a-f0-9]{40}");

    private final ObjectMapper objectMapper;
    private final Path directory;

    public FileContextResultStore(ObjectMapper objectMapper, ContextProperties properties) {
        this.objectMapper = objectMapper;
        this.directory = Path.of(properties.resultDirectory().trim()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw ContextReductionException.storeFailed(exception);
        }
    }

    /*
     * 背景：同一条历史会在一个 Run 的多次模型请求以及后续 Run 中重复参与容量处理，
     * 若每次生成随机引用会不断复制相同快照并让预览前缀持续变化。
     * 设计意图：以 Session、稳定来源和内容摘要生成确定性不透明引用，并使用原子替换保存快照。
     * 关键约束：引用只能在所属 Session 目录中解析，不能把摘要本身当作跨 Session 授权；
     * 否则相同内容可能被其他 Session 猜中并越权读取。
     */
    public synchronized String save(
            ReductionScope scope,
            String source,
            String toolName,
            PayloadRole payloadRole,
            PayloadKind payloadKind,
            String content) {
        requireId(scope.sessionId());
        if (source == null || source.isBlank()
                || toolName == null || toolName.isBlank()
                || payloadRole == null
                || payloadKind == null) {
            throw new IllegalArgumentException("Stored context result requires source and tool name");
        }
        String reference = "result_" + sha256(
                scope.sessionId() + "\u0000" + source + "\u0000" + content).substring(0, 40);
        Path sessionDirectory = directory.resolve(scope.sessionId());
        Path target = sessionDirectory.resolve(reference + ".json");
        if (Files.exists(target)) {
            return reference;
        }

        StoredResult stored = new StoredResult(
                2,
                reference,
                scope.sessionId(),
                scope.runId(),
                source,
                toolName,
                payloadRole,
                payloadKind,
                Instant.now(),
                content);
        Path temporary = sessionDirectory.resolve("." + reference + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(sessionDirectory);
            objectMapper.writeValue(temporary.toFile(), stored);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                Files.deleteIfExists(temporary);
            }
            return reference;
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            throw ContextReductionException.storeFailed(exception);
        }
    }

    public StoredResult read(String sessionId, String reference) {
        requireId(sessionId);
        if (reference == null || !REFERENCE_PATTERN.matcher(reference).matches()) {
            throw ContextReductionException.resultNotFound();
        }
        Path target = directory.resolve(sessionId).resolve(reference + ".json");
        if (Files.notExists(target)) {
            throw ContextReductionException.resultNotFound();
        }
        try {
            StoredResult stored = objectMapper.readValue(target.toFile(), StoredResult.class);
            if (stored == null
                    || (stored.version() != 1 && stored.version() != 2)
                    || !reference.equals(stored.reference())
                    || !sessionId.equals(stored.sessionId())
                    || stored.content() == null) {
                throw ContextReductionException.resultNotFound();
            }
            /*
             * 背景：Phase 1 之前的 v1 快照只有字符正文，没有参数/结果角色和语义类型。
             * 设计意图：读取时把 v1 规范化为 RESULT + GENERIC_TEXT，v2 则保留显式元数据；不做离线迁移。
             * 关键约束：不能把缺失元数据猜成 read/grep 等语义类型，否则旧快照的 offset 含义会从字符静默变成逻辑项。
             */
            if (stored.version() == 1) {
                return new StoredResult(
                        stored.version(),
                        stored.reference(),
                        stored.sessionId(),
                        stored.runId(),
                        stored.source(),
                        stored.toolName(),
                        PayloadRole.RESULT,
                        PayloadKind.GENERIC_TEXT,
                        stored.createdAt(),
                        stored.content());
            }
            if (stored.payloadRole() == null || stored.payloadKind() == null) {
                throw ContextReductionException.resultNotFound();
            }
            return stored;
        } catch (ContextReductionException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw ContextReductionException.readFailed(exception);
        }
    }

    private void requireId(String value) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw ContextReductionException.resultNotFound();
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record StoredResult(
            int version,
            String reference,
            String sessionId,
            String runId,
            String source,
            String toolName,
            PayloadRole payloadRole,
            PayloadKind payloadKind,
            Instant createdAt,
            String content) {
    }
}
