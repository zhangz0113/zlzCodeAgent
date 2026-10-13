package com.zlzcode.codeagent.retrieval.service;

import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.retrieval.service.StructureParserService.*;
import org.springframework.stereotype.Service;
import org.treesitter.TSNode;
import java.util.*;
import java.util.regex.Pattern;
import static com.zlzcode.codeagent.retrieval.service.StructureParserService.*;

@Service
public final class ScriptStructureService {
    public Parsed extract(TSNode root, byte[] bytes) {
        var bindings = new LinkedHashMap<String, ModuleBinding>();
        var exports = new LinkedHashMap<String, String>();
        List<Part> parts = new ArrayList<>();
        for (TSNode child : children(root)) {
            if (child.getType().equals("import_statement")) {
                imports(child, bytes, bindings); continue;
            }
            if (child.getType().equals("export_statement")) {
                TSNode declaration = child.getChildByFieldName("declaration");
                if (!declaration.isNull()) {
                    String name = declaredName(declaration, bytes);
                    exports.put(text(bytes, child).matches("(?s)^export\\s+default\\b.*") ? "default" : name, name);
                    add(declaration, commentStart(child), "", true, bytes, parts);
                } else {
                    String module = unquote(text(bytes, child.getChildByFieldName("source")));
                    for (TSNode spec : descendants(child)) if (spec.getType().equals("export_specifier")) {
                        String local = text(bytes, spec.getChildByFieldName("name"));
                        String alias = text(bytes, spec.getChildByFieldName("alias"));
                        if (alias.isEmpty()) alias = local;
                        if (module.isEmpty()) exports.put(alias, local);
                        else {
                            String key = "@reexport:" + alias;
                            bindings.put(key, new ModuleBinding(module, local)); exports.put(alias, key);
                        }
                    }
                }
            } else add(child, commentStart(child), "", false, bytes, parts);
        }
        if (parts.isEmpty() && bytes.length > 0) parts.add(new Part(0, bytes.length, "text", "", "", -1, List.of(), false, List.of(), List.of()));
        parts.sort(Comparator.comparingInt(Part::start));
        return new Parsed("", Map.of(), bindings, exports, parts);
    }
    private void imports(TSNode node, byte[] bytes, Map<String, ModuleBinding> bindings) {
        String module = unquote(text(bytes, node.getChildByFieldName("source")));
        if (!module.startsWith(".")) return;
        for (TSNode child : descendants(node)) {
            if (child.getType().equals("import_specifier")) {
                String name = text(bytes, child.getChildByFieldName("name"));
                String alias = text(bytes, child.getChildByFieldName("alias"));
                bindings.put(alias.isEmpty() ? name : alias, new ModuleBinding(module, name));
            } else if (child.getType().equals("import_clause")) {
                for (TSNode item : children(child)) if (item.getType().equals("identifier"))
                    bindings.put(text(bytes, item), new ModuleBinding(module, "default"));
            }
        }
    }
    private void add(TSNode node, int start, String owner, boolean exported, byte[] bytes, List<Part> parts) {
        String type = node.getType();
        if (type.contains("comment") || type.equals("import_statement")) return;
        String name = declaredName(node, bytes);
        String kind = switch (type) {
            case "function_declaration", "generator_function_declaration", "method_definition" -> "function";
            case "interface_declaration" -> "interface";
            case "type_alias_declaration", "enum_declaration" -> "type";
            case "class_declaration", "abstract_class_declaration" -> "class";
            case "lexical_declaration", "variable_declaration" -> "binding";
            default -> "text";
        };
        if (kind.equals("binding")) {
            for (TSNode variable : children(node)) if (variable.getType().equals("variable_declarator")) {
                TSNode value = variable.getChildByFieldName("value");
                if (!value.isNull() && Set.of("arrow_function", "function_expression").contains(value.getType())) kind = "function";
            }
        }
        List<String> parents = new ArrayList<>();
        for (TSNode child : children(node)) if (child.getType().contains("heritage"))
            descendants(child).stream().filter(n -> Set.of("identifier", "type_identifier").contains(n.getType()))
                    .map(n -> text(bytes, n)).forEach(parents::add);
        TSNode body = node.getChildByFieldName("body");
        int end = kind.equals("class") && !body.isNull() ? body.getStartByte() + 1 : node.getEndByte();
        var refs = references(node, bytes, kind.equals("class") ? end : Integer.MAX_VALUE);
        TSNode parameters = node.getChildByFieldName("parameters");
        parts.add(new Part(start, end, kind, name, owner, parameters.isNull() ? -1 : parameters.getNamedChildCount(),
                parents, exported, refs, boundaries(node)));
        if (kind.equals("class") && !body.isNull()) for (TSNode child : children(body))
            add(child, commentStart(child), owner.isEmpty() ? name : owner + "." + name, exported, bytes, parts);
    }
    private List<Reference> references(TSNode node, byte[] bytes, int end) {
        var refs = new LinkedHashSet<Reference>();
        for (TSNode child : descendants(node)) {
            if (child.getStartByte() >= end) continue;
            if (child.getType().equals("type_identifier")) refs.add(new Reference("type", text(bytes, child), "", -1));
            if (child.getType().equals("call_expression")) {
                TSNode function = child.getChildByFieldName("function");
                String name = text(bytes, function);
                if (Pattern.matches("[\\w$]+", name)) refs.add(new Reference("call", name, "", -1));
            }
        }
        return List.copyOf(refs);
    }
    private String declaredName(TSNode node, byte[] bytes) {
        String name = text(bytes, node.getChildByFieldName("name"));
        if (!name.isEmpty()) return name;
        for (TSNode child : children(node)) if (child.getType().equals("variable_declarator"))
            return text(bytes, child.getChildByFieldName("name"));
        return "";
    }
    private String unquote(String value) { return value.length() < 2 ? "" : value.substring(1, value.length() - 1); }
}
