package com.zlzcode.codeagent.retrieval.service;

import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import org.springframework.stereotype.Service;
import org.treesitter.*;
import java.util.*;

@Service
public final class StructureParserService {
    private final JavaStructureService javaStructure;
    private final ScriptStructureService scriptStructure;
    public StructureParserService(JavaStructureService javaStructure, ScriptStructureService scriptStructure) {
        this.javaStructure = javaStructure; this.scriptStructure = scriptStructure;
    }
    public Parsed parse(byte[] bytes, String language) {
        try (TSLanguage grammar = switch (language) {
            case "java" -> new TreeSitterJava();
            case "javascript", "jsx" -> new TreeSitterJavascript();
            case "typescript" -> new TreeSitterTypescript();
            case "tsx" -> new TreeSitterTsx();
            default -> throw new IllegalArgumentException("Unsupported structured language");
        }; TSParser parser = new TSParser()) {
            if (!parser.setLanguage(grammar)) throw new IllegalStateException("Incompatible grammar");
            /*
             * 背景：中文、emoji 与 CRLF 的源码定位必须与文件哈希使用同一份 UTF-8 字节。
             * 设计意图：使用 byte input 回调而不是 JNI 字符串解析，避免 UTF-16 下标误用。
             * 关键约束：节点 byte offset 只能截取原字节；转换成 String 下标会错交证据范围。
             */
            try (TSTree tree = parser.parse(new byte[4096], null, (buffer, offset, point) -> {
                int count = Math.min(buffer.length, bytes.length - offset);
                if (count <= 0) return 0;
                System.arraycopy(bytes, offset, buffer, 0, count);
                return count;
            }, TSInputEncoding.TSInputEncodingUTF8)) {
                return language.equals("java") ? javaStructure.extract(tree.getRootNode(), bytes)
                        : scriptStructure.extract(tree.getRootNode(), bytes);
            }
        }
    }
    public record Part(int start, int end, String kind, String name, String owner,
                       int parameterCount, List<String> parents, boolean exported,
                       List<Reference> references, List<Integer> boundaries) {}
    public record Parsed(String packageName, Map<String, String> imports,
                         Map<String, ModuleBinding> bindings, Map<String, String> exports,
                         List<Part> parts) {}
    static String text(byte[] bytes, TSNode node) {
        return node == null || node.isNull() ? "" : new String(bytes, node.getStartByte(),
                node.getEndByte() - node.getStartByte(), java.nio.charset.StandardCharsets.UTF_8);
    }
    static List<TSNode> children(TSNode node) {
        List<TSNode> result = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) result.add(node.getNamedChild(i));
        return result;
    }
    static List<TSNode> descendants(TSNode node) {
        List<TSNode> result = new ArrayList<>(); collect(node, result); return result;
    }
    private static void collect(TSNode node, List<TSNode> result) {
        result.add(node);
        for (TSNode child : children(node)) collect(child, result);
    }
    static List<Integer> boundaries(TSNode node) {
        TSNode body = node.getChildByFieldName("body");
        if (body.isNull()) body = node;
        return children(body).stream().map(TSNode::getEndByte).toList();
    }
    static int commentStart(TSNode node) {
        int start = node.getStartByte();
        TSNode previous = node.getPrevNamedSibling();
        while (!previous.isNull() && (previous.getType().contains("comment"))) {
            start = previous.getStartByte(); previous = previous.getPrevNamedSibling();
        }
        return start;
    }
}
