package io.github.randomcodespace.iq.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.query.StatsService;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Show rich categorized statistics from an already-analyzed graph.
 * Reads from the H2 analysis cache -- no re-scan.
 */
@Component
@Command(name = "stats", mixinStandardHelpOptions = true,
        description = "Show rich statistics from analyzed graph")
public class StatsCommand implements Callable<Integer> {
    private static final String PROP_COUNT = "Count";
    private static final String PROP_ARCHITECTURE = "architecture";
    private static final String PROP_AUTH = "auth";
    private static final String PROP_CONNECTIONS = "connections";
    private static final String PROP_FRAMEWORKS = "frameworks";
    private static final String PROP_GRAPH = "graph";
    private static final String PROP_INFRA = "infra";
    private static final String PROP_LANGUAGES = "languages";


    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--format", "-f"}, defaultValue = "pretty",
            description = "Output format: pretty, yaml, json, markdown")
    private String format;

    @Option(names = {"--category", "-c"}, defaultValue = "all",
            description = "Category: all, graph, languages, frameworks, infra, connections, auth, architecture")
    private String category;

    private final StatsService statsService;
    private final CodeIqConfig config;

    // For testing: allow injection of a custom PrintStream
    private PrintStream out = System.out;

    public StatsCommand(StatsService statsService, CodeIqConfig config) {
        this.statsService = statsService;
        this.config = config;
    }

    /** Visible for testing. */
    void setOut(PrintStream out) {
        this.out = out;
    }

    private static final Set<String> VALID_FORMATS = Set.of("pretty", "yaml", "json", "markdown");
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "all", PROP_GRAPH, PROP_LANGUAGES, PROP_FRAMEWORKS, PROP_INFRA,
            PROP_CONNECTIONS, PROP_AUTH, PROP_ARCHITECTURE);

    @Override
    public Integer call() {
        if (!VALID_FORMATS.contains(format.toLowerCase())) {
            CliOutput.error("Unknown format: " + format + ". Use: pretty, yaml, json, markdown");
            return 1;
        }
        if (!VALID_CATEGORIES.contains(category.toLowerCase())) {
            CliOutput.error("Unknown category: " + category
                    + ". Use: all, graph, languages, frameworks, infra, connections, auth, architecture");
            return 1;
        }

        Path root = path.toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        // H2 stores data in analysis-cache.mv.db — check for that file on disk
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!Files.exists(h2File)) {
            CliOutput.warn("No analysis cache found at " + cachePath);
            CliOutput.info("Run 'code-iq analyze' first to scan the codebase.");
            return 1;
        }

        List<CodeNode> nodes;
        List<CodeEdge> edges;
        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            nodes = cache.loadAllNodes();
            edges = cache.loadAllEdges();
        }

        if (nodes.isEmpty()) {
            CliOutput.warn("Analysis cache is empty. Run 'code-iq analyze' first.");
            return 1;
        }

        Map<String, Object> stats;
        if ("all".equalsIgnoreCase(category)) {
            stats = statsService.computeStats(nodes, edges);
        } else {
            Map<String, Object> catStats = statsService.computeCategory(nodes, edges, category);
            if (catStats == null) {
                CliOutput.error("Unknown category: " + category);
                return 1;
            }
            stats = new LinkedHashMap<>();
            stats.put(category.toLowerCase(), catStats);
        }

        return switch (format.toLowerCase()) {
            case "json" -> outputJson(stats);
            case "yaml" -> outputYaml(stats);
            case "markdown" -> outputMarkdown(stats);
            default -> outputPretty(stats);
        };
    }

    // --- Output formatters ---

    int outputPretty(Map<String, Object> stats) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        String projectName = java.util.Objects.toString(
                path.toAbsolutePath().normalize().getFileName(), "unknown");

        out.println();
        CliOutput.print(out, "@|bold [=] Code IQ Stats -- " + projectName + "|@");
        out.println();

        // Graph
        if (stats.containsKey(PROP_GRAPH)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> graph = (Map<String, Object>) stats.get(PROP_GRAPH);
            out.println("  Graph:        " + nf.format(toLong(graph.get("nodes")))
                    + " nodes, " + nf.format(toLong(graph.get("edges")))
                    + " edges, " + nf.format(toLong(graph.get("files"))) + " files");
        }

        // Languages
        if (stats.containsKey(PROP_LANGUAGES)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> languages = (Map<String, Object>) stats.get(PROP_LANGUAGES);
            if (!languages.isEmpty()) {
                StringBuilder sb = new StringBuilder("  Languages:    ");
                languages.entrySet().stream().limit(10).forEach(e ->
                        sb.append(e.getKey()).append(" (").append(nf.format(toLong(e.getValue()))).append("), "));
                trimTrailingComma(sb);
                out.println(sb);
            }
        }

        // Frameworks
        if (stats.containsKey(PROP_FRAMEWORKS)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> frameworks = (Map<String, Object>) stats.get(PROP_FRAMEWORKS);
            if (!frameworks.isEmpty()) {
                out.println();
                StringBuilder sb = new StringBuilder("  Frameworks:   ");
                frameworks.entrySet().stream().limit(15).forEach(e ->
                        sb.append(e.getKey()).append(" (").append(nf.format(toLong(e.getValue()))).append("), "));
                trimTrailingComma(sb);
                out.println(sb);
            }
        }

        // Infrastructure
        if (stats.containsKey("infra")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> infra = (Map<String, Object>) stats.get(PROP_INFRA);
            boolean hasInfra = infra.values().stream()
                    .anyMatch(v -> v instanceof Map<?, ?> m && !m.isEmpty());
            if (hasInfra) {
                out.println();
                out.println("  Infrastructure:");
                printInfraSection(nf, infra, "databases", "Databases");
                printInfraSection(nf, infra, "messaging", "Messaging");
                printInfraSection(nf, infra, "cloud", "Cloud");
            }
        }

        // Connections
        if (stats.containsKey(PROP_CONNECTIONS)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> conn = (Map<String, Object>) stats.get(PROP_CONNECTIONS);
            out.println();
            out.println("  Connections:");

            @SuppressWarnings("unchecked")
            Map<String, Object> rest = (Map<String, Object>) conn.get("rest");
            if (rest != null) {
                long restTotal = toLong(rest.get("total"));
                if (restTotal > 0) {
                    StringBuilder sb = new StringBuilder("    REST:         " + nf.format(restTotal));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> byMethod = (Map<String, Object>) rest.get("by_method");
                    if (byMethod != null && !byMethod.isEmpty()) {
                        sb.append(" (");
                        byMethod.forEach((k, v) -> sb.append(k).append(": ")
                                .append(nf.format(toLong(v))).append(", "));
                        trimTrailingComma(sb);
                        sb.append(")");
                    }
                    out.println(sb);
                }
            }
            printSimpleStat(nf, conn, "grpc", "gRPC");
            printSimpleStat(nf, conn, "websocket", "WebSocket");
            printSimpleStat(nf, conn, "producers", "Producers");
            printSimpleStat(nf, conn, "consumers", "Consumers");
        }

        // Auth
        if (stats.containsKey("auth")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = (Map<String, Object>) stats.get(PROP_AUTH);
            if (!auth.isEmpty()) {
                out.println();
                out.println("  Auth:");
                auth.forEach((k, v) ->
                        out.println("    " + padRight(k, 20) + nf.format(toLong(v))));
            }
        }

        // Architecture
        if (stats.containsKey(PROP_ARCHITECTURE)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> arch = (Map<String, Object>) stats.get(PROP_ARCHITECTURE);
            if (!arch.isEmpty()) {
                out.println();
                out.println("  Architecture:");
                arch.forEach((k, v) ->
                        out.println("    " + padRight(capitalize(k), 20) + nf.format(toLong(v))));
            }
        }

        out.println();
        return 0;
    }

    int outputJson(Map<String, Object> stats) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            out.println(mapper.writeValueAsString(stats));
            return 0;
        } catch (Exception e) {
            CliOutput.error("Failed to serialize JSON: " + e.getMessage());
            return 1;
        }
    }

    int outputYaml(Map<String, Object> stats) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(options), options);
        out.println(yaml.dump(stats));
        return 0;
    }

    int outputMarkdown(Map<String, Object> stats) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        out.println("# Code IQ Stats");
        out.println();

        if (stats.containsKey("graph")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> graph = (Map<String, Object>) stats.get(PROP_GRAPH);
            out.println("## Graph");
            out.println();
            out.println("| Metric | Count |");
            out.println("|--------|-------|");
            graph.forEach((k, v) -> out.println("| " + capitalize(k) + " | " + nf.format(toLong(v)) + " |"));
            out.println();
        }

        printMarkdownSection(nf, stats, PROP_LANGUAGES, "Languages", "Language", PROP_COUNT);
        printMarkdownSection(nf, stats, PROP_FRAMEWORKS, "Frameworks", "Framework", PROP_COUNT);

        if (stats.containsKey(PROP_INFRA)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> infra = (Map<String, Object>) stats.get(PROP_INFRA);
            out.println("## Infrastructure");
            out.println();
            for (Map.Entry<String, Object> section : infra.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sectionMap = (Map<String, Object>) section.getValue();
                if (!sectionMap.isEmpty()) {
                    out.println("### " + capitalize(section.getKey()));
                    out.println();
                    out.println("| Type | Count |");
                    out.println("|------|-------|");
                    sectionMap.forEach((k, v) -> out.println("| " + k + " | " + nf.format(toLong(v)) + " |"));
                    out.println();
                }
            }
        }

        if (stats.containsKey(PROP_CONNECTIONS)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> conn = (Map<String, Object>) stats.get(PROP_CONNECTIONS);
            out.println("## Connections");
            out.println();
            out.println("| Type | Count |");
            out.println("|------|-------|");
            @SuppressWarnings("unchecked")
            Map<String, Object> rest = (Map<String, Object>) conn.get("rest");
            if (rest != null) {
                out.println("| REST (total) | " + nf.format(toLong(rest.get("total"))) + " |");
                @SuppressWarnings("unchecked")
                Map<String, Object> byMethod = (Map<String, Object>) rest.get("by_method");
                if (byMethod != null) {
                    byMethod.forEach((k, v) ->
                            out.println("| REST " + k + " | " + nf.format(toLong(v)) + " |"));
                }
            }
            out.println("| gRPC | " + nf.format(toLong(conn.get("grpc"))) + " |");
            out.println("| WebSocket | " + nf.format(toLong(conn.get("websocket"))) + " |");
            out.println("| Producers | " + nf.format(toLong(conn.get("producers"))) + " |");
            out.println("| Consumers | " + nf.format(toLong(conn.get("consumers"))) + " |");
            out.println();
        }

        printMarkdownSection(nf, stats, PROP_AUTH, "Auth", "Type", PROP_COUNT);
        printMarkdownSection(nf, stats, PROP_ARCHITECTURE, "Architecture", "Kind", PROP_COUNT);

        return 0;
    }

    // --- Utility methods ---

    private void printInfraSection(NumberFormat nf, Map<String, Object> infra,
                                    String key, String label) {
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) infra.get(key);
        if (section != null && !section.isEmpty()) {
            StringBuilder sb = new StringBuilder("    " + padRight(label + ":", 14));
            section.forEach((k, v) -> sb.append(k).append(" (")
                    .append(nf.format(toLong(v))).append("), "));
            trimTrailingComma(sb);
            out.println(sb);
        }
    }

    private void printSimpleStat(NumberFormat nf, Map<String, Object> map,
                                  String key, String label) {
        long val = toLong(map.get(key));
        if (val > 0) {
            out.println("    " + padRight(label + ":", 14) + nf.format(val));
        }
    }

    private void printMarkdownSection(NumberFormat nf, Map<String, Object> stats,
                                       String key, String title, String col1, String col2) {
        if (stats.containsKey(key)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) stats.get(key);
            if (!section.isEmpty()) {
                out.println("## " + title);
                out.println();
                out.println("| " + col1 + " | " + col2 + " |");
                out.println("|" + "-".repeat(col1.length() + 2) + "|" + "-".repeat(col2.length() + 2) + "|");
                section.forEach((k, v) -> out.println("| " + k + " | " + nf.format(toLong(v)) + " |"));
                out.println();
            }
        }
    }

    private static long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val == null) return 0;
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).replace('_', ' ');
    }

    private static void trimTrailingComma(StringBuilder sb) {
        if (sb.length() >= 2 && sb.substring(sb.length() - 2).equals(", ")) {
            sb.setLength(sb.length() - 2);
        }
    }
}
