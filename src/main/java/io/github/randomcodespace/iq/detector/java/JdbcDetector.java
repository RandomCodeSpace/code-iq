package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

/**
 * Detects Java database connectivity patterns (JDBC, JdbcTemplate, DataSource).
 */
@DetectorInfo(
    name = "jdbc",
    category = "database",
    description = "Detects JDBC database connections and driver configuration",
    languages = {"java"},
    nodeKinds = {NodeKind.DATABASE_CONNECTION},
    edgeKinds = {EdgeKind.CONNECTS_TO},
    properties = {"db_type"}
)
@Component
public class JdbcDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern DRIVER_MANAGER_RE = Pattern.compile(
            "DriverManager\\s*\\.\\s*getConnection\\s*\\(\\s*\"(jdbc:[^\"]+)\"");
    private static final Pattern JDBC_TEMPLATE_RE = Pattern.compile(
            "(?:private|protected|public|final)\\s+"
                    + "(?:final\\s+)?"
                    + "(JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient)"
                    + "\\s+\\w+");
    private static final Pattern DATASOURCE_BEAN_RE = Pattern.compile("(?:@Bean|DataSource)\\s*(?:\\(|\\.)");
    private static final Pattern SPRING_DATASOURCE_RE = Pattern.compile(
            "spring\\.datasource\\.url\\s*=\\s*(jdbc:[^\\s]+)");
    private static final Pattern JDBC_URL_RE = Pattern.compile(
            "jdbc:(mysql|postgresql|sqlserver|oracle|db2|h2|sqlite|mariadb)"
                    + "(?::(?:thin:)?(?:@)?)?(?://([^/\"'\\s;?]+))?");
    private static final Pattern JDBC_STRING_RE = Pattern.compile("\"(jdbc:[^\"]+)\"");

    @Override
    public String getName() {
        return "jdbc";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }

        if (!text.contains("JdbcTemplate") && !text.contains("DriverManager")
                && !text.contains("DataSource") && !text.contains("NamedParameterJdbcTemplate")
                && !text.contains("JdbcClient")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        for (String line : lines) {
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) {
                className = cm.group(1);
                break;
            }
        }

        if (className == null) {
            return DetectorResult.empty();
        }

        String classNodeId = ctx.filePath() + ":" + className;
        Set<String> seenDbs = new LinkedHashSet<>();

        // Pattern 1: DriverManager.getConnection
        for (int i = 0; i < lines.length; i++) {
            Matcher m = DRIVER_MANAGER_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String url = m.group(1);
            Map<String, String> props = parseJdbcUrl(url);
            String dbType = props.getOrDefault("db_type", "unknown");
            String host = props.getOrDefault("host", "unknown");
            String dbId = "db:" + dbType + ":" + host;
            ensureDbNode(dbId, dbType + "@" + host, i + 1, ctx, new LinkedHashMap<>(props), seenDbs, nodes);
            addConnectEdge(classNodeId, dbId, className + " connects to " + dbType + "@" + host, edges, nodes);
        }

        // Pattern 2: JdbcTemplate / NamedParameterJdbcTemplate / JdbcClient usage
        for (int i = 0; i < lines.length; i++) {
            Matcher m = JDBC_TEMPLATE_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String templateType = m.group(1);
            String dbId = ctx.filePath() + ":jdbc:" + className;
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("template_type", templateType);
            ensureDbNode(dbId, className + " (" + templateType + ")", i + 1, ctx, props, seenDbs, nodes);
            addConnectEdge(classNodeId, dbId, className + " uses " + templateType, edges, nodes);
        }

        // Pattern 3: DataSource bean definitions
        for (int i = 0; i < lines.length; i++) {
            Matcher m = DATASOURCE_BEAN_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String dbId = ctx.filePath() + ":jdbc:" + className;
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("datasource", true);
            ensureDbNode(dbId, className + " (DataSource)", i + 1, ctx, props, seenDbs, nodes);
        }

        // Pattern 4: spring.datasource.url
        if (text.contains("spring.datasource")) {
            for (int i = 0; i < lines.length; i++) {
                Matcher m = SPRING_DATASOURCE_RE.matcher(lines[i]);
                if (!m.find()) continue;
                String url = m.group(1);
                Map<String, String> props = parseJdbcUrl(url);
                String dbType = props.getOrDefault("db_type", "unknown");
                String host = props.getOrDefault("host", "unknown");
                String dbId = "db:" + dbType + ":" + host;
                ensureDbNode(dbId, dbType + "@" + host, i + 1, ctx, new LinkedHashMap<>(props), seenDbs, nodes);
            }
        }

        // Pattern 5: Standalone JDBC URL strings
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("DriverManager") || lines[i].contains("spring.datasource")) {
                continue;
            }
            Matcher urlMatcher = JDBC_STRING_RE.matcher(lines[i]);
            while (urlMatcher.find()) {
                String url = urlMatcher.group(1);
                Map<String, String> props = parseJdbcUrl(url);
                String dbType = props.getOrDefault("db_type", "unknown");
                String host = props.getOrDefault("host", "unknown");
                String dbId = "db:" + dbType + ":" + host;
                ensureDbNode(dbId, dbType + "@" + host, i + 1, ctx, new LinkedHashMap<>(props), seenDbs, nodes);
                addConnectEdge(classNodeId, dbId, className + " connects to " + dbType + "@" + host, edges, nodes);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private Map<String, String> parseJdbcUrl(String url) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("connection_url", url);
        Matcher m = JDBC_URL_RE.matcher(url);
        if (m.find()) {
            props.put("db_type", m.group(1));
            if (m.group(2) != null) {
                props.put("host", m.group(2));
            }
        }
        return props;
    }

    private void ensureDbNode(String dbId, String label, int line, DetectorContext ctx,
                              Map<String, ? extends Object> properties, Set<String> seenDbs, List<CodeNode> nodes) {
        if (!seenDbs.contains(dbId)) {
            seenDbs.add(dbId);
            CodeNode node = new CodeNode();
            node.setId(dbId);
            node.setKind(NodeKind.DATABASE_CONNECTION);
            node.setLabel(label);
            node.setFilePath(ctx.filePath());
            node.setLineStart(line);
            node.setProperties(new LinkedHashMap<>(properties));
            nodes.add(node);
        }
    }

    private void addConnectEdge(String classNodeId, String dbId, String label, List<CodeEdge> edges, List<CodeNode> nodes) {
        CodeEdge edge = new CodeEdge();
        edge.setId(classNodeId + "->connects_to->" + dbId);
        edge.setKind(EdgeKind.CONNECTS_TO);
        edge.setSourceId(classNodeId);
        CodeNode targetRef = new CodeNode(dbId, NodeKind.DATABASE_CONNECTION, label);
        edge.setTarget(targetRef);
        edges.add(edge);
    }
}
