package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

/**
 * Detects raw SQL queries in @Query annotations and JdbcTemplate calls.
 */
@DetectorInfo(
    name = "raw_sql",
    category = "database",
    description = "Detects raw SQL queries in Java source code (JDBC, JPA native queries)",
    languages = {"java"},
    nodeKinds = {NodeKind.QUERY}
)
@Component
public class RawSqlDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern QUERY_ANNO_RE = Pattern.compile(
            "@Query\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"", Pattern.DOTALL);
    private static final Pattern NATIVE_QUERY_RE = Pattern.compile("nativeQuery\\s*=\\s*true");
    private static final Pattern JDBC_TEMPLATE_RE = Pattern.compile(
            "(?:jdbcTemplate|namedParameterJdbcTemplate|JdbcTemplate)\\s*\\."
                    + "(?:query|queryForObject|queryForList|queryForMap|update|execute|batchUpdate)"
                    + "\\s*\\(\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"", Pattern.DOTALL);
    private static final Pattern EM_QUERY_RE = Pattern.compile(
            "(?:entityManager|em)\\s*\\.(?:createNativeQuery|createQuery)\\s*\\(\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"",
            Pattern.DOTALL);
    private static final Pattern TABLE_REF_RE = Pattern.compile(
            "\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    @Override
    public String getName() {
        return "raw_sql";
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

        if (!text.contains("@Query") && !text.contains("jdbcTemplate") && !text.contains("JdbcTemplate")
                && !text.contains("createNativeQuery") && !text.contains("createQuery")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();

        String className = null;
        for (String line : lines) {
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) {
                className = cm.group(1);
                break;
            }
        }
        if (className == null) className = "Unknown";

        // @Query annotations
        for (Matcher m = QUERY_ANNO_RE.matcher(text); m.find(); ) {
            String queryStr = m.group(1);
            int lineNum = findLineNumber(text, m.start());
            boolean isNative = NATIVE_QUERY_RE.matcher(
                    text.substring(m.start(), Math.min(m.end() + 50, text.length()))).find();
            List<String> tables = findAllMatches(TABLE_REF_RE, queryStr);

            String queryId = ctx.filePath() + ":" + className + ":query:L" + lineNum;
            nodes.add(queryNode(queryId, queryStr, className + ".query@L" + lineNum,
                    lineNum, ctx, List.of("@Query"),
                    Map.of("query", queryStr, "native", isNative, "source", "annotation", "tables", tables)));
        }

        // JdbcTemplate queries
        for (Matcher m = JDBC_TEMPLATE_RE.matcher(text); m.find(); ) {
            String queryStr = m.group(1);
            int lineNum = findLineNumber(text, m.start());
            List<String> tables = findAllMatches(TABLE_REF_RE, queryStr);

            String queryId = ctx.filePath() + ":" + className + ":jdbc:L" + lineNum;
            nodes.add(queryNode(queryId, queryStr, className + ".jdbc@L" + lineNum,
                    lineNum, ctx, List.of(),
                    Map.of("query", queryStr, "native", true, "source", "jdbc_template", "tables", tables)));
        }

        // EntityManager queries
        for (Matcher m = EM_QUERY_RE.matcher(text); m.find(); ) {
            String queryStr = m.group(1);
            int lineNum = findLineNumber(text, m.start());
            List<String> tables = findAllMatches(TABLE_REF_RE, queryStr);
            boolean isNative = text.substring(Math.max(0, m.start() - 30), Math.min(m.start() + 20, text.length()))
                    .contains("createNativeQuery");

            String queryId = ctx.filePath() + ":" + className + ":em:L" + lineNum;
            nodes.add(queryNode(queryId, queryStr, className + ".em@L" + lineNum,
                    lineNum, ctx, List.of(),
                    Map.of("query", queryStr, "native", isNative, "source", "entity_manager", "tables", tables)));
        }

        return DetectorResult.of(nodes, List.of());
    }

    private CodeNode queryNode(String id, String queryStr, String fqn, int line,
                               DetectorContext ctx, List<String> annotations, Map<String, Object> properties) {
        CodeNode node = new CodeNode();
        node.setId(id);
        node.setKind(NodeKind.QUERY);
        String label = queryStr.length() > 80 ? queryStr.substring(0, 80) + "..." : queryStr;
        node.setLabel(label);
        node.setFqn(fqn);
        node.setFilePath(ctx.filePath());
        node.setLineStart(line);
        node.setAnnotations(new ArrayList<>(annotations));
        node.setProperties(new LinkedHashMap<>(properties));
        return node;
    }

    private List<String> findAllMatches(Pattern pattern, String text) {
        List<String> results = new ArrayList<>();
        for (Matcher m = pattern.matcher(text); m.find(); ) {
            results.add(m.group(1));
        }
        return results;
    }
}
