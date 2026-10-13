package com.zlzcode.codeagent.retrieval.service;

import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.Reference;
import com.zlzcode.codeagent.retrieval.service.StructureParserService.*;
import org.springframework.stereotype.Service;
import org.treesitter.TSNode;
import java.util.*;
import java.util.regex.Pattern;
import static com.zlzcode.codeagent.retrieval.service.StructureParserService.*;

@Service
public final class JavaStructureService {
    private static final Set<String> TYPES = Set.of("class_declaration", "interface_declaration", "enum_declaration", "record_declaration", "annotation_type_declaration");
    public Parsed extract(TSNode root, byte[] bytes) {
        String packageName = "";
        Map<String, String> imports = new LinkedHashMap<>();
        List<Part> parts = new ArrayList<>();
        for (TSNode child : children(root)) {
            if (child.getType().equals("package_declaration")) {
                packageName = text(bytes, child).replaceFirst("^package\\s+", "").replace(";", "").strip();
            } else if (child.getType().equals("import_declaration")) {
                String value = text(bytes, child).replaceFirst("^import\\s+(static\\s+)?", "").replace(";", "").strip();
                if (!value.endsWith(".*")) imports.put(value.substring(value.lastIndexOf('.') + 1), value);
            }
        }
        for (TSNode child : children(root)) if (TYPES.contains(child.getType())) type(child, "", bytes, parts);
        addErrors(root, bytes, parts);
        if (parts.isEmpty() && bytes.length > 0) parts.add(new Part(0, bytes.length, "text", "", "", -1, List.of(), false, List.of(), List.of()));
        parts.sort(Comparator.comparingInt(Part::start));
        return new Parsed(packageName, imports, Map.of(), Map.of(), parts);
    }
    private void type(TSNode node, String enclosing, byte[] bytes, List<Part> parts) {
        String name = text(bytes, node.getChildByFieldName("name"));
        String owner = enclosing.isEmpty() ? name : enclosing + "." + name;
        TSNode body = node.getChildByFieldName("body");
        List<String> parents = new ArrayList<>();
        for (TSNode child : children(node)) {
            if (Set.of("super_interfaces", "superclass", "extends_interfaces").contains(child.getType())) {
                descendants(child).stream().filter(n -> n.getType().equals("type_identifier"))
                        .map(n -> text(bytes, n)).forEach(parents::add);
            }
        }
        parts.add(new Part(commentStart(node), body.isNull() ? node.getEndByte() : body.getStartByte() + 1,
                node.getType().equals("interface_declaration") ? "interface" : "type", name, enclosing,
                -1, parents, true, references(node, bytes, owner, true), List.of()));
        if (body.isNull()) return;
        Map<String, String> fields = variables(body, bytes, true);
        for (TSNode member : children(body)) {
            if (TYPES.contains(member.getType())) { type(member, owner, bytes, parts); continue; }
            String kind = switch (member.getType()) {
                case "method_declaration", "constructor_declaration", "compact_constructor_declaration" -> "method";
                case "field_declaration", "constant_declaration", "enum_constant" -> "field";
                case "static_initializer", "block" -> "initializer";
                default -> null;
            };
            if (kind == null) continue;
            String memberName = text(bytes, member.getChildByFieldName("name"));
            if (kind.equals("field")) {
                memberName = descendants(member).stream().filter(n -> n.getType().equals("variable_declarator"))
                        .map(n -> text(bytes, n.getChildByFieldName("name"))).findFirst().orElse(memberName);
            }
            TSNode parameters = member.getChildByFieldName("parameters");
            int arity = parameters.isNull() ? -1 : (int) children(parameters).stream()
                    .filter(n -> n.getType().equals("formal_parameter") || n.getType().equals("spread_parameter")).count();
            var refs = references(member, bytes, owner, false, fields);
            parts.add(new Part(commentStart(member), member.getEndByte(), kind, memberName, owner,
                    arity, List.of(), true, refs, boundaries(member)));
        }
    }
    private Map<String, String> variables(TSNode node, byte[] bytes, boolean fieldsOnly) {
        Map<String, String> values = new LinkedHashMap<>();
        for (TSNode child : descendants(node)) {
            if (child.getType().equals("formal_parameter") && !fieldsOnly) {
                values.put(text(bytes, child.getChildByFieldName("name")), text(bytes, child.getChildByFieldName("type")));
            } else if (child.getType().equals("field_declaration") || !fieldsOnly && child.getType().equals("local_variable_declaration")) {
                String type = text(bytes, child.getChildByFieldName("type"));
                for (TSNode variable : children(child)) if (variable.getType().equals("variable_declarator"))
                    values.put(text(bytes, variable.getChildByFieldName("name")), type);
            }
        }
        return values;
    }
    private List<Reference> references(TSNode node, byte[] bytes, String owner, boolean header) {
        return references(node, bytes, owner, header, Map.of());
    }
    private List<Reference> references(TSNode node, byte[] bytes, String owner, boolean header, Map<String, String> fields) {
        LinkedHashSet<Reference> refs = new LinkedHashSet<>();
        Map<String, String> variables = new LinkedHashMap<>(fields);
        variables.putAll(variables(node, bytes, false));
        TSNode body = node.getChildByFieldName("body");
        for (TSNode child : descendants(node)) {
            if (header && !body.isNull() && child.getStartByte() >= body.getStartByte()) continue;
            if (child.getType().equals("type_identifier")) refs.add(new Reference("type", text(bytes, child), "", -1));
            if (!header && child.getType().equals("method_invocation")) {
                String receiver = text(bytes, child.getChildByFieldName("object"));
                String receiverType = receiver.isEmpty() || receiver.equals("this") ? owner
                        : receiver.startsWith("this.") ? variables.get(receiver.substring(5)) : variables.get(receiver);
                if (receiverType == null && Pattern.matches("[A-Z][\\w]*", receiver)) receiverType = receiver;
                if (receiverType != null) {
                    TSNode arguments = child.getChildByFieldName("arguments");
                    refs.add(new Reference("call", text(bytes, child.getChildByFieldName("name")), receiverType,
                            arguments.isNull() ? 0 : arguments.getNamedChildCount()));
                }
            }
        }
        return List.copyOf(refs);
    }
    private void addErrors(TSNode node, byte[] bytes, List<Part> parts) {
        for (TSNode child : descendants(node)) if (child.getType().equals("ERROR")
                && parts.stream().noneMatch(p -> p.start() <= child.getStartByte() && p.end() >= child.getEndByte()))
            parts.add(new Part(child.getStartByte(), child.getEndByte(), "text", "", "", -1, List.of(), false, List.of(), boundaries(child)));
    }
}
