package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.AbstractStructuredDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects property keys, Spring config markers, and database connections from .properties files.
 */
@DetectorInfo(
    name = "properties",
    category = "config",
    description = "Detects Java properties files and database connection strings",
    parser = ParserType.STRUCTURED,
    languages = {"properties"},
    nodeKinds = {NodeKind.CONFIG_FILE, NodeKind.CONFIG_KEY, NodeKind.DATABASE_CONNECTION},
    edgeKinds = {EdgeKind.CONTAINS},
    properties = {"url"}
)
@Component
public class PropertiesDetector extends AbstractStructuredDetector {
    private static final String PROP_PROPERTIES = "properties";


    private static final Set<String> DB_URL_KEYWORDS = Set.of("url", "jdbc-url", "uri");
    private static final Pattern JDBC_DB_TYPE_RE = Pattern.compile(
            "jdbc:(mysql|postgresql|sqlserver|oracle|db2|h2|sqlite|mariadb|derby|hsqldb)");
    private static final Map<String, String> DB_TYPE_LABELS = Map.ofEntries(
            Map.entry("mysql", "MySQL"),
            Map.entry("postgresql", "PostgreSQL"),
            Map.entry("sqlserver", "SQL Server"),
            Map.entry("oracle", "Oracle"),
            Map.entry("db2", "DB2"),
            Map.entry("h2", "H2"),
            Map.entry("sqlite", "SQLite"),
            Map.entry("mariadb", "MariaDB"),
            Map.entry("derby", "Derby"),
            Map.entry("hsqldb", "HSQLDB")
    );
    private static final int MAX_KEYS = 200;

    @Override
    public String getName() {
        return PROP_PROPERTIES;
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of(PROP_PROPERTIES);
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        Object parsedData = ctx.parsedData();
        if (parsedData == null) return DetectorResult.empty();

        Map<String, Object> pd = asMap(parsedData);
        if (!"properties".equals(getString(pd, "type"))) {
            return DetectorResult.empty();
        }

        Map<String, Object> data = getMap(pd, "data");
        if (data.isEmpty()) return DetectorResult.empty();

        String filepath = ctx.filePath();
        String fileId = "props:" + filepath;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // CONFIG_FILE node
        CodeNode fileNode = new CodeNode(fileId, NodeKind.CONFIG_FILE, filepath);
        fileNode.setFqn(filepath);
        fileNode.setModule(ctx.moduleName());
        fileNode.setFilePath(filepath);
        fileNode.setLineStart(1);
        fileNode.setProperties(Map.of("format", PROP_PROPERTIES));
        nodes.add(fileNode);

        // Process keys (limit to avoid node explosion)
        int count = 0;
        for (var entry : data.entrySet()) {
            if (count >= MAX_KEYS) break;
            String key = entry.getKey();
            Object value = entry.getValue();

            String keyLower = key.toLowerCase();
            String keyId = "props:" + filepath + ":" + key;

            // Only treat as DATABASE_CONNECTION if the key is a URL-type key
            // AND the value contains a recognizable connection string
            boolean isDbUrlKey = DB_URL_KEYWORDS.stream().anyMatch(kw -> {
                // Match key segments like "spring.datasource.url" or "jdbc-url"
                String lastSegment = keyLower.contains(".") ? keyLower.substring(keyLower.lastIndexOf('.') + 1) : keyLower;
                return lastSegment.equals(kw) || lastSegment.contains(kw);
            });
            boolean hasDbValue = value instanceof String s && s.contains("jdbc:");

            Map<String, Object> props = new HashMap<>();
            props.put("key", key);
            if (value instanceof String s) {
                props.put("value", s);
            }

            if (isDbUrlKey && hasDbValue) {
                String dbType = extractDbType(value.toString());
                String dbLabel = dbType != null ? dbType : "database";
                props.put("db_type", dbLabel);
                CodeNode keyNode = new CodeNode(keyId, NodeKind.DATABASE_CONNECTION, dbLabel);
                keyNode.setFqn(filepath + ":" + key);
                keyNode.setModule(ctx.moduleName());
                keyNode.setFilePath(filepath);
                keyNode.setProperties(props);
                nodes.add(keyNode);
            } else {
                if (key.startsWith("spring.")) {
                    props.put("spring_config", true);
                }
                CodeNode keyNode = new CodeNode(keyId, NodeKind.CONFIG_KEY, key);
                keyNode.setFqn(filepath + ":" + key);
                keyNode.setModule(ctx.moduleName());
                keyNode.setFilePath(filepath);
                keyNode.setProperties(props);
                nodes.add(keyNode);
            }

            CodeEdge edge = new CodeEdge();
            edge.setId(fileId + "->" + keyId);
            edge.setKind(EdgeKind.CONTAINS);
            edge.setSourceId(fileId);
            edge.setTarget(new CodeNode(keyId, null, null));
            edges.add(edge);

            count++;
        }

        return DetectorResult.of(nodes, edges);
    }

    /**
     * Extract a normalized database type label from a JDBC URL string.
     * Returns null if the type cannot be determined.
     */
    static String extractDbType(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        Matcher m = JDBC_DB_TYPE_RE.matcher(jdbcUrl.toLowerCase());
        if (m.find()) {
            return DB_TYPE_LABELS.getOrDefault(m.group(1), m.group(1));
        }
        return null;
    }
}
