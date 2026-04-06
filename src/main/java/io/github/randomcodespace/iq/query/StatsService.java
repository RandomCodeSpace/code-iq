package io.github.randomcodespace.iq.query;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Service that computes rich categorized statistics from graph nodes and edges.
 * Stateless -- takes nodes/edges as input and produces a structured breakdown.
 */
@Service
public class StatsService {

    /**
     * Compute full categorized stats from a list of nodes and edges.
     *
     * @param nodes all graph nodes
     * @param edges all graph edges
     * @return categorized stats map
     */
    public Map<String, Object> computeStats(List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("graph", computeGraph(nodes, edges));
        result.put("languages", computeLanguages(nodes));
        result.put("frameworks", computeFrameworks(nodes));
        result.put("infra", computeInfra(nodes));
        result.put("connections", computeConnections(nodes, edges));
        result.put("auth", computeAuth(nodes));
        result.put("architecture", computeArchitecture(nodes));
        return result;
    }

    /**
     * Compute stats for a single category.
     *
     * @param nodes    all graph nodes
     * @param edges    all graph edges
     * @param category the category name
     * @return stats for that category only, or null if unknown
     */
    public Map<String, Object> computeCategory(List<CodeNode> nodes, List<CodeEdge> edges,
                                                 String category) {
        return switch (category.toLowerCase()) {
            case "graph" -> computeGraph(nodes, edges);
            case "languages" -> computeLanguages(nodes);
            case "frameworks" -> computeFrameworks(nodes);
            case "infra" -> computeInfra(nodes);
            case "connections" -> computeConnections(nodes, edges);
            case "auth" -> computeAuth(nodes);
            case "architecture" -> computeArchitecture(nodes);
            default -> null;
        };
    }

    // --- Category implementations ---

    Map<String, Object> computeGraph(List<CodeNode> nodes, List<CodeEdge> edges) {
        long fileCount = nodes.stream()
                .map(CodeNode::getFilePath)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .count();

        // Edge kind breakdown
        Map<String, Long> edgesByKind = new TreeMap<>();
        for (CodeEdge edge : edges) {
            String kind = edge.getKind() != null ? edge.getKind().getValue() : "unknown";
            edgesByKind.merge(kind, 1L, Long::sum);
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes.size());
        graph.put("edges", edges.size());
        graph.put("files", fileCount);
        graph.put("edges_by_kind", new LinkedHashMap<>(sortByValueDesc(edgesByKind)));
        return graph;
    }

    Map<String, Object> computeLanguages(List<CodeNode> nodes) {
        // Group files by language from the file path extension or properties.language
        Map<String, Long> langCounts = new TreeMap<>();
        for (CodeNode node : nodes) {
            String lang = extractLanguage(node);
            if (lang != null && !lang.isBlank()) {
                langCounts.merge(lang, 1L, Long::sum);
            }
        }
        return new LinkedHashMap<>(sortByValueDesc(langCounts));
    }

    Map<String, Object> computeFrameworks(List<CodeNode> nodes) {
        Map<String, Long> fwCounts = new TreeMap<>();
        for (CodeNode node : nodes) {
            Object fw = node.getProperties().get("framework");
            if (fw != null) {
                String fwStr = fw.toString().trim();
                if (!fwStr.isBlank()) {
                    fwCounts.merge(fwStr, 1L, Long::sum);
                }
            }
        }
        return new LinkedHashMap<>(sortByValueDesc(fwCounts));
    }

    private static final Map<String, String> DB_TYPE_NORMALIZE = Map.ofEntries(
            Map.entry("mysql", "MySQL"),
            Map.entry("postgresql", "PostgreSQL"),
            Map.entry("postgres", "PostgreSQL"),
            Map.entry("sqlserver", "SQL Server"),
            Map.entry("mssql", "SQL Server"),
            Map.entry("oracle", "Oracle"),
            Map.entry("db2", "DB2"),
            Map.entry("h2", "H2"),
            Map.entry("sqlite", "SQLite"),
            Map.entry("mariadb", "MariaDB"),
            Map.entry("derby", "Derby"),
            Map.entry("hsqldb", "HSQLDB"),
            Map.entry("mongo", "MongoDB"),
            Map.entry("mongodb", "MongoDB"),
            Map.entry("redis", "Redis"),
            Map.entry("cassandra", "Cassandra"),
            Map.entry("dynamodb", "DynamoDB"),
            Map.entry("couchbase", "Couchbase"),
            Map.entry("neo4j", "Neo4j"),
            Map.entry("cockroachdb", "CockroachDB")
    );

    Map<String, Object> computeInfra(List<CodeNode> nodes) {
        Map<String, Object> infra = new LinkedHashMap<>();

        // Databases -- use db_type property with normalization, skip config-key labels
        Map<String, Long> databases = new TreeMap<>();
        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.DATABASE_CONNECTION) {
                String dbType = resolveDbType(node);
                if (dbType != null) {
                    databases.merge(dbType, 1L, Long::sum);
                }
            }
        }
        infra.put("databases", sortByValueDesc(databases));

        // Messaging
        Map<String, Long> messaging = new TreeMap<>();
        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.TOPIC || node.getKind() == NodeKind.QUEUE
                    || node.getKind() == NodeKind.MESSAGE_QUEUE) {
                String protocol = propOrLabel(node, "protocol");
                messaging.merge(protocol, 1L, Long::sum);
            }
        }
        infra.put("messaging", sortByValueDesc(messaging));

        // Cloud
        Map<String, Long> cloud = new TreeMap<>();
        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.AZURE_RESOURCE
                    || node.getKind() == NodeKind.INFRA_RESOURCE) {
                String resType = propOrLabel(node, "resource_type");
                cloud.merge(resType, 1L, Long::sum);
            }
        }
        infra.put("cloud", sortByValueDesc(cloud));

        return infra;
    }

    Map<String, Object> computeConnections(List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, Object> connections = new LinkedHashMap<>();

        // REST endpoints by method
        Map<String, Long> restByMethod = new TreeMap<>();
        long grpcCount = 0;
        long wsCount = 0;

        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.ENDPOINT) {
                Object protocol = node.getProperties().get("protocol");
                if ("grpc".equalsIgnoreCase(protocol != null ? protocol.toString() : "")) {
                    grpcCount++;
                } else {
                    Object method = node.getProperties().get("http_method");
                    String m = method != null ? method.toString().toUpperCase() : "UNKNOWN";
                    restByMethod.merge(m, 1L, Long::sum);
                }
            } else if (node.getKind() == NodeKind.WEBSOCKET_ENDPOINT) {
                wsCount++;
            }
        }

        long restTotal = restByMethod.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> rest = new LinkedHashMap<>();
        rest.put("total", restTotal);
        rest.put("by_method", sortByValueDesc(restByMethod));
        connections.put("rest", rest);
        connections.put("grpc", grpcCount);
        connections.put("websocket", wsCount);

        // Producers and consumers from edges
        long producers = edges.stream()
                .filter(e -> e.getKind() == EdgeKind.PRODUCES || e.getKind() == EdgeKind.PUBLISHES)
                .count();
        long consumers = edges.stream()
                .filter(e -> e.getKind() == EdgeKind.CONSUMES || e.getKind() == EdgeKind.LISTENS)
                .count();
        connections.put("producers", producers);
        connections.put("consumers", consumers);

        return connections;
    }

    Map<String, Object> computeAuth(List<CodeNode> nodes) {
        Map<String, Long> authCounts = new TreeMap<>();
        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.GUARD) {
                Object authType = node.getProperties().get("auth_type");
                String at = authType != null ? authType.toString() : "unknown";
                authCounts.merge(at, 1L, Long::sum);
            } else {
                // Also count nodes where framework property starts with "auth:"
                Object fw = node.getProperties().get("framework");
                if (fw != null) {
                    String fwStr = fw.toString().trim();
                    if (fwStr.startsWith("auth:")) {
                        String authType = fwStr.substring("auth:".length()).trim();
                        if (!authType.isEmpty()) {
                            authCounts.merge(authType, 1L, Long::sum);
                        }
                    }
                }
            }
        }
        return new LinkedHashMap<>(sortByValueDesc(authCounts));
    }

    Map<String, Object> computeArchitecture(List<CodeNode> nodes) {
        Map<String, Object> arch = new LinkedHashMap<>();
        long classes = 0, interfaces = 0, abstracts = 0, enums = 0;
        long annotations = 0, modules = 0, methods = 0;

        for (CodeNode node : nodes) {
            switch (node.getKind()) {
                case CLASS -> classes++;
                case INTERFACE -> interfaces++;
                case ABSTRACT_CLASS -> abstracts++;
                case ENUM -> enums++;
                case ANNOTATION_TYPE -> annotations++;
                case MODULE -> modules++;
                case METHOD -> methods++;
                default -> { /* skip */ }
            }
        }

        // Only include non-zero counts, sorted for determinism
        if (classes > 0) arch.put("classes", classes);
        if (interfaces > 0) arch.put("interfaces", interfaces);
        if (abstracts > 0) arch.put("abstract_classes", abstracts);
        if (enums > 0) arch.put("enums", enums);
        if (annotations > 0) arch.put("annotation_types", annotations);
        if (modules > 0) arch.put("modules", modules);
        if (methods > 0) arch.put("methods", methods);

        return arch;
    }

    // --- Helpers ---

    private String extractLanguage(CodeNode node) {
        // Try properties.language first
        Object lang = node.getProperties().get("language");
        if (lang != null && !lang.toString().isBlank()) {
            return lang.toString().toLowerCase();
        }
        // Fall back to file extension
        String path = node.getFilePath();
        if (path == null || !path.contains(".")) return null;
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "py" -> "python";
            case "js", "mjs", "cjs" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "go" -> "go";
            case "rs" -> "rust";
            case "cs" -> "csharp";
            case "rb" -> "ruby";
            case "scala" -> "scala";
            case "cpp", "cc", "cxx" -> "cpp";
            case "c", "h" -> "c";
            case "proto" -> "protobuf";
            case "yml", "yaml" -> "yaml";
            case "json" -> "json";
            case "xml" -> "xml";
            case "toml" -> "toml";
            case "ini", "cfg" -> "ini";
            case "properties" -> "properties";
            case "gradle" -> "gradle";
            case "tf" -> "terraform";
            case "bicep" -> "bicep";
            case "sql" -> "sql";
            case "md" -> "markdown";
            case "html", "htm" -> "html";
            case "css", "scss", "sass" -> "css";
            case "vue" -> "vue";
            case "svelte" -> "svelte";
            case "jsx" -> "jsx";
            case "sh", "bash" -> "shell";
            default -> ext;
        };
    }

    /**
     * Resolve the display-friendly database type for a DATABASE_CONNECTION node.
     * Uses db_type property first, then tries to extract from connection_url or value,
     * then falls back to label only if it looks like a DB name (not a config key).
     * Returns null if the node is a false positive (e.g. a config key like "spring.datasource.password").
     */
    private String resolveDbType(CodeNode node) {
        Map<String, Object> props = node.getProperties();

        // 1. Check db_type property
        Object dbType = props.get("db_type");
        if (dbType != null && !dbType.toString().isBlank()) {
            return normalizeDbType(dbType.toString());
        }

        // 2. Try to extract from connection_url or value properties
        for (String urlProp : List.of("connection_url", "value", "url")) {
            Object urlVal = props.get(urlProp);
            if (urlVal instanceof String s && s.contains("jdbc:")) {
                String extracted = extractDbTypeFromUrl(s);
                if (extracted != null) return extracted;
            }
        }

        // 3. Fall back to label, but only if it doesn't look like a config key
        String label = node.getLabel();
        if (label != null && !label.isBlank() && !label.contains(".") && !label.contains("=")) {
            return normalizeDbType(label);
        }

        // Skip -- this is likely a false-positive config key node
        return null;
    }

    private String normalizeDbType(String raw) {
        String lower = raw.trim().toLowerCase();
        // Handle "type@host" format from JdbcDetector (e.g. "mysql@localhost")
        if (lower.contains("@")) {
            lower = lower.substring(0, lower.indexOf('@'));
        }
        return DB_TYPE_NORMALIZE.getOrDefault(lower, raw.trim());
    }

    private String extractDbTypeFromUrl(String url) {
        // Match jdbc:TYPE pattern
        int idx = url.indexOf("jdbc:");
        if (idx < 0) return null;
        String afterJdbc = url.substring(idx + 5);
        int colonIdx = afterJdbc.indexOf(':');
        if (colonIdx > 0) {
            String type = afterJdbc.substring(0, colonIdx).toLowerCase();
            return DB_TYPE_NORMALIZE.getOrDefault(type, type);
        }
        return null;
    }

    private String propOrLabel(CodeNode node, String propKey) {
        Object val = node.getProperties().get(propKey);
        if (val != null && !val.toString().isBlank()) {
            return val.toString();
        }
        return node.getLabel() != null ? node.getLabel() : "unknown";
    }

    static <K> Map<K, Long> sortByValueDesc(Map<K, Long> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<K, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
