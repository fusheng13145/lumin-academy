package com.leyon.lumin.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T44（07 §2.4）Java 侧双证据：源码显式 import 扫描 + 02 §3.1 允许列表 +
 * DFS 环检测，并在内存图中注入两条非法边做负向对照（不改源码、不落盘）。
 *
 * <p>与 libs/tools/check_dependency_cycles.py 构成脚本 + 单测双证据。
 * 只能验证显式 import，不替代运行时装配、异步消息耦合与 CI 最终门禁（A.7.3）。
 */
class DependencyAcyclicTest {

    private static final Pattern PACKAGE = Pattern.compile(
            "^[ \\t]*package[ \\t]+(com\\.leyon\\.lumin\\.[A-Za-z0-9_.]+)[ \\t]*;",
            Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile(
            "^[ \\t]*import[ \\t]+(com\\.leyon\\.lumin\\.[A-Za-z0-9_.]+)[ \\t]*;",
            Pattern.MULTILINE);

    /** 02 §3.1 允许同步依赖；foundation 无领域依赖（零出边到业务域）。 */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("identity", Set.of("foundation")),
            Map.entry("catalog", Set.of("foundation")),
            Map.entry("authoring", Set.of("catalog", "identity", "foundation")),
            Map.entry("moderation", Set.of("foundation")),
            Map.entry("publication", Set.of("authoring", "catalog", "identity", "foundation")),
            Map.entry("governance", Set.of("catalog", "identity", "foundation")),
            Map.entry("configuration", Set.of("identity", "foundation")),
            Map.entry("engagement", Set.of("publication", "identity", "foundation")),
            Map.entry("audit", Set.of("foundation")),
            Map.entry("billing_reserved", Set.of("foundation")),
            Map.entry("ai", Set.of("foundation")),
            Map.entry("foundation", Set.of())
    );

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("contracts/p0.openapi.yaml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("未找到包含 contracts/p0.openapi.yaml 的项目根目录");
    }

    private static String module(String packageName) {
        String[] parts = packageName.split("\\.");
        return parts.length >= 4 ? parts[3] : "";
    }

    private static Map<String, Set<String>> scan() throws IOException {
        // 多模块拆分后，业务源码分布在 domain/application/infrastructure/ai/bootstrap 五个模块。
        Path base = projectRoot().resolve("lumin-backend");
        List<Path> roots = List.of(
                base.resolve("lumin-domain/src/main/java"),
                base.resolve("lumin-application/src/main/java"),
                base.resolve("lumin-infrastructure/src/main/java"),
                base.resolve("lumin-ai/src/main/java"),
                base.resolve("lumin-bootstrap/src/main/java")
        );
        Map<String, Set<String>> graph = new HashMap<>();
        for (String name : ALLOWED.keySet()) {
            graph.put(name, new HashSet<>());
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path source : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(source, StandardCharsets.UTF_8);
                    Matcher packageMatcher = PACKAGE.matcher(text);
                    if (!packageMatcher.find()) {
                        continue;
                    }
                    String from = module(packageMatcher.group(1));
                    Matcher importMatcher = IMPORT.matcher(text);
                    while (importMatcher.find()) {
                        String to = module(importMatcher.group(1));
                        if (!to.isEmpty() && !to.equals(from)) {
                            graph.computeIfAbsent(from, key -> new HashSet<>()).add(to);
                        }
                    }
                }
            }
        }
        return graph;
    }

    private static List<String> illegalEdges(Map<String, Set<String>> graph) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            String from = entry.getKey();
            for (String target : entry.getValue()) {
                String base = target.split("\\.")[0];
                if (target.contains(".repository") && !base.equals(from)) {
                    problems.add(from + " -> " + target + "（跨模块 Repository 直接访问）");
                } else if ("foundation".equals(from) && !"foundation".equals(base)) {
                    problems.add(from + " -> " + target + "（foundation 反向绑定业务域）");
                } else if (!ALLOWED.getOrDefault(from, Set.of()).contains(base)) {
                    problems.add(from + " -> " + target + "（不在 02 §3.1 允许列表）");
                }
            }
        }
        return problems;
    }

    private static boolean hasCycle(Map<String, Set<String>> graph) {
        Map<String, Integer> state = new HashMap<>();
        for (String node : graph.keySet()) {
            if (state.getOrDefault(node, 0) == 0 && visit(node, graph, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visit(String node, Map<String, Set<String>> graph,
                                 Map<String, Integer> state) {
        state.put(node, 1);
        for (String target : graph.getOrDefault(node, Set.of())) {
            String base = target.split("\\.")[0];
            if (!graph.containsKey(base)) {
                continue;
            }
            int current = state.getOrDefault(base, 0);
            if (current == 1 || (current == 0 && visit(base, graph, state))) {
                return true;
            }
        }
        state.put(node, 2);
        return false;
    }

    @Test
    void realSourceGraphHasNoIllegalEdgeAndNoCycle() throws IOException {
        Map<String, Set<String>> graph = scan();
        assertTrue(graph.values().stream().anyMatch(edges -> !edges.isEmpty()),
                "必须扫描到真实跨模块 import，不能是空跑");
        assertEquals(List.of(), illegalEdges(graph), "允许列表逐边比对须 0 非法边");
        assertFalse(hasCycle(graph), "模块级有向图须 0 环");
        assertEquals(Set.of(), graph.get("foundation"),
                "foundation 不得反向绑定任何业务域");
    }

    /** T44：唯一真实的跨业务域边 publication → identity 必须命中允许列表。 */
    @Test
    void onlyRealCrossDomainEdgeIsPublicationToIdentity() throws IOException {
        Map<String, Set<String>> graph = scan();
        assertTrue(graph.get("publication").contains("identity"),
                "VisibilityPolicy 必须复用 AgePolicy.canRead，形成真实允许边");
        assertTrue(ALLOWED.get("publication").contains("identity"));
        assertTrue(graph.get("identity").contains("foundation"));
        assertEquals(List.of(), illegalEdges(graph));
    }

    @Test
    void injectedFoundationReverseDependencyMustFail() throws IOException {
        Map<String, Set<String>> graph = scan();
        graph.get("foundation").add("identity");
        assertFalse(illegalEdges(graph).isEmpty(),
                "注入 foundation->identity 必须被判为非法边");
        assertTrue(illegalEdges(graph).stream().anyMatch(s -> s.contains("foundation")));
    }

    @Test
    void injectedCrossModuleRepositoryDependencyMustFail() throws IOException {
        Map<String, Set<String>> graph = scan();
        graph.get("moderation").add("authoring.repository");
        assertFalse(illegalEdges(graph).isEmpty(),
                "注入 moderation->authoring.repository 必须被判为非法边");
        assertTrue(illegalEdges(graph).stream().anyMatch(s -> s.contains("repository")));
    }

    @Test
    void removingInjectionsRestoresPass() throws IOException {
        Map<String, Set<String>> graph = scan();
        assertEquals(List.of(), illegalEdges(graph));
        graph.get("foundation").add("identity");
        graph.get("moderation").add("authoring.repository");
        assertEquals(2, illegalEdges(graph).size());
        graph.get("foundation").remove("identity");
        graph.get("moderation").remove("authoring.repository");
        assertEquals(List.of(), illegalEdges(graph), "恢复合法依赖后必须通过");
        assertFalse(hasCycle(graph));
    }
}
