package com.zlzcode.codeagent.retrieval.service;

import com.zlzcode.codeagent.retrieval.config.RetrievalProperties;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import com.zlzcode.codeagent.retrieval.model.RepositorySearch.*;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.*;

@Service
public final class RelationExpansionService {
    private final RetrievalProperties properties;
    public RelationExpansionService(RetrievalProperties properties) { this.properties = properties; }
    public Expansion expand(Snapshot snapshot, List<Candidate> primary, Scope scope) {
        View view = new View(snapshot);
        Map<String, Candidate> added = new LinkedHashMap<>();
        Map<String, Candidate> primaryIds = new LinkedHashMap<>(); primary.forEach(c -> primaryIds.put(c.chunk().id(), c));
        for (int category = 0; category < 3; category++) for (Candidate candidate : primary) {
            Chunk source = candidate.chunk(); FileRecord file = snapshot.files().get(source.path());
            if (category == 0 && !source.owner().isEmpty()) {
                for (Target target : type(file, source.owner(), view)) offer(target.chunk(), "context", "owner", source, scope, primaryIds, added);
            }
            for (Reference reference : source.references()) {
                if (category == 1 && reference.kind().equals("type")) {
                    for (Target target : resolve(file, reference.name(), view, 0, new HashSet<>(), new ArrayList<>())) {
                        offer(target.chunk(), "context", "explicit_type", source, scope, primaryIds, added);
                        if (isType(target.declaration())) for (Chunk field : target.file().chunks())
                            if (field.kind().equals("field") && field.owner().equals(ownerName(target.declaration())))
                                offer(field, "context", "type_field", source, scope, primaryIds, added);
                    }
                }
                if (category == 2 && reference.kind().equals("call")) {
                    if (file.language().equals("java")) {
                        for (Target receiver : type(file, reference.receiverType(), view)) {
                            List<Target> methods = methods(receiver, reference, view);
                            if (methods.size() != 1) continue;
                            Target method = methods.getFirst();
                            offer(method.chunk(), "context", "direct_call", source, scope, primaryIds, added);
                            if (receiver.declaration().kind().equals("interface")) {
                                /*
                                 * 背景：接口接收者只能证明声明类型，不能证明依赖注入后的运行时分派。
                                 * 设计意图：有界查找明确 implements/父类声明链并标为候选，不按类名猜唯一实现。
                                 * 关键约束：不得从新找到的实现体继续追调用；否则一轮扩展变成无界调用图探索。
                                 */
                                for (Target implementation : view.types) if (!implementation.declaration().kind().equals("interface")
                                        && implementsType(implementation, receiver.declaration().qualifiedName(), view, 0, new HashSet<>())) {
                                    List<Target> matching = methods(implementation, reference, view);
                                    if (matching.size() == 1) offer(matching.getFirst().chunk(), "implementation_candidate", "interface_implementation_candidate",
                                            source, scope, primaryIds, added);
                                }
                            }
                        }
                    } else {
                        List<Chunk> bindings = new ArrayList<>();
                        List<Target> targets = resolve(file, reference.name(), view, 0, new HashSet<>(), bindings);
                        /*
                         * 背景：相对模块别名可定位目标，但只返回调用和目标原文无法证明二者如何连接。
                         * 设计意图：沿同一次有界声明解析收集 import/export 的独立源码范围，作为互补依据。
                         * 关键约束：仅在唯一目标解析成功后提交绑定材料，不遍历新增材料的调用；否则会交付误绑定或扩大为无界图探索。
                         */
                        if (!targets.isEmpty()) for (Chunk binding : bindings)
                            offer(binding, "context", "relative_binding", source, scope, primaryIds, added);
                        for (Target target : targets) offer(target.chunk(), "context", "direct_call", source, scope, primaryIds, added);
                    }
                }
            }
        }
        boolean truncated = added.size() > properties.supplementaryCandidates();
        return new Expansion(List.copyOf(primaryIds.values()), added.values().stream().limit(properties.supplementaryCandidates()).toList(), truncated);
    }
    private void offer(Chunk target, String role, String kind, Chunk source, Scope scope, Map<String, Candidate> primary, Map<String, Candidate> added) {
        if (!scope.includes(target)) return;
        if (primary.containsKey(target.id())) {
            if (role.equals("implementation_candidate")) {
                Candidate previous = primary.get(target.id());
                var roles = new LinkedHashSet<>(previous.roles()); roles.add(role);
                var relations = new LinkedHashSet<>(previous.relations()); relations.add(Relation.from(kind, source));
                primary.put(target.id(), new Candidate(target, List.copyOf(roles), List.copyOf(relations)));
            }
            return;
        }
        Relation relation = Relation.from(kind, source);
        Candidate prior = added.get(target.id());
        LinkedHashSet<String> roles = new LinkedHashSet<>(); LinkedHashSet<Relation> relations = new LinkedHashSet<>();
        if (prior != null) { roles.addAll(prior.roles()); relations.addAll(prior.relations()); }
        roles.add(role); relations.add(relation);
        added.put(target.id(), new Candidate(target, List.copyOf(roles), List.copyOf(relations)));
    }
    private List<Target> resolve(FileRecord file, String name, View view, int depth, Set<String> visited, List<Chunk> bindings) {
        if (file.language().equals("java")) return type(file, name, view);
        if (depth >= 8 || !visited.add(file.path() + "#" + name)) return List.of();
        List<Target> local = view.targets.stream().filter(t -> t.file().path().equals(file.path()) && t.declaration().name().equals(name)).toList();
        if (local.size() == 1) return local;
        ModuleBinding binding = file.moduleBindings().get(name);
        if (binding == null || !binding.module().startsWith(".")) return List.of();
        String base = Path.of(file.path()).getParent() == null ? "" : Path.of(file.path()).getParent().toString();
        String module = Path.of(base).resolve(binding.module()).normalize().toString().replace('\\', '/');
        if (module.startsWith("../")) return List.of();
        List<FileRecord> possible = new ArrayList<>();
        for (String extension : List.of("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", "/index.ts", "/index.tsx", "/index.js")) {
            FileRecord target = view.files.get(module + extension);
            if (target != null) possible.add(target);
        }
        if (possible.size() != 1) return List.of();
        FileRecord target = possible.getFirst(); String exported = target.exports().get(binding.exportedName());
        if (exported == null) return List.of();
        file.chunks().stream().filter(c -> c.kind().equals("import") && c.references().stream().anyMatch(r -> r.name().equals(name))).forEach(bindings::add);
        target.chunks().stream().filter(c -> c.kind().equals("export_binding")
                && c.references().stream().anyMatch(r -> r.name().equals(binding.exportedName()))).forEach(bindings::add);
        return resolve(target, exported, view, depth + 1, visited, bindings);
    }
    private List<Target> type(FileRecord file, String raw, View view) {
        String name = raw.replaceAll("<.*>|\\[\\]", "").strip();
        if (name.isEmpty()) return List.of();
        String qualified = file.imports().get(name);
        if (qualified == null) qualified = file.packageName().isEmpty() ? name : file.packageName() + "." + name;
        String finalName = qualified;
        List<Target> found = view.types.stream().filter(t -> t.declaration().qualifiedName().equals(finalName)).toList();
        if (found.isEmpty() && name.contains(".")) found = view.types.stream().filter(t -> t.declaration().qualifiedName().equals(name)).toList();
        return found.size() == 1 ? found : List.of();
    }
    private List<Target> methods(Target type, Reference reference, View view) {
        return view.targets.stream().filter(t -> t.file().path().equals(type.file().path())
                && t.declaration().owner().equals(ownerName(type.declaration())) && t.declaration().name().equals(reference.name())
                && (reference.argumentCount() < 0 || t.declaration().parameterCount() == reference.argumentCount())).toList();
    }
    private String ownerName(Declaration declaration) {
        return declaration.owner().isEmpty() ? declaration.name() : declaration.owner() + "." + declaration.name();
    }
    private boolean implementsType(Target target, String interfaceName, View view, int depth, Set<String> visited) {
        if (depth >= 8 || !visited.add(target.declaration().qualifiedName())) return false;
        for (String parent : target.declaration().parents()) for (Target resolved : type(target.file(), parent, view)) {
            if (resolved.declaration().qualifiedName().equals(interfaceName)
                    || implementsType(resolved, interfaceName, view, depth + 1, visited)) return true;
        }
        return false;
    }
    private boolean isType(Declaration declaration) { return Set.of("type", "class", "interface").contains(declaration.kind()); }
    private record Target(FileRecord file, Declaration declaration, Chunk chunk) {}
    private final class View {
        final Map<String, FileRecord> files;
        final List<Target> targets = new ArrayList<>(), types = new ArrayList<>();
        View(Snapshot snapshot) {
            files = snapshot.files();
            for (FileRecord file : files.values()) {
                Map<String, Chunk> chunks = new HashMap<>(); file.chunks().forEach(c -> chunks.put(c.id(), c));
                for (Declaration declaration : file.declarations()) {
                    Chunk chunk = chunks.get(declaration.chunkId());
                    if (chunk == null) continue;
                    Target target = new Target(file, declaration, chunk); targets.add(target);
                    if (isType(declaration)) types.add(target);
                }
            }
        }
    }
}
