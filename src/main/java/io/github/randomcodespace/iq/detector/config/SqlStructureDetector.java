package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

/**
 * Detects SQL structures: tables, views, indexes, procedures, and foreign key relationships.
 */
@DetectorInfo(
    name = "sql_structure",
    category = "config",
    description = "Detects SQL DDL structure (tables, views, foreign keys, indexes)",
    languages = {"sql"},
    nodeKinds = {NodeKind.CONFIG_DEFINITION, NodeKind.ENTITY},
    edgeKinds = {EdgeKind.DEPENDS_ON},
    properties = {"table"}
)
@Component
public class SqlStructureDetector extends AbstractRegexDetector {
    private static final String PROP_ENTITY_TYPE = "entity_type";


    private static final Pattern TABLE_RE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\\w+\\.)?(\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIEW_RE = Pattern.compile(
            "CREATE\\s+(?:OR\\s+REPLACE\\s+)?VIEW\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\\w+\\.)?(\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_RE = Pattern.compile(
            "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\\w+\\.)?(\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROCEDURE_RE = Pattern.compile(
            "CREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\s+(?:\\w+\\.)?(\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FK_RE = Pattern.compile(
            "REFERENCES\\s+(?:\\w+\\.)?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String getName() {
        return "sql_structure";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("sql");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String content = ctx.content();
        if (content == null || content.isEmpty()) return DetectorResult.empty();

        String filepath = ctx.filePath();
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String currentTableId = null;

        for (IndexedLine il : iterLines(content)) {
            String line = il.text();
            int lineNum = il.lineNumber();
            Matcher m;

            // Tables
            m = TABLE_RE.matcher(line);
            if (m.find()) {
                String tableName = m.group(1);
                currentTableId = "sql:" + filepath + ":table:" + tableName;

                CodeNode node = new CodeNode(currentTableId, NodeKind.ENTITY, tableName);
                node.setFqn(tableName);
                node.setModule(ctx.moduleName());
                node.setFilePath(filepath);
                node.setLineStart(lineNum);
                node.setProperties(Map.of(PROP_ENTITY_TYPE, "table"));
                nodes.add(node);
                continue;
            }

            // Views
            m = VIEW_RE.matcher(line);
            if (m.find()) {
                String viewName = m.group(1);
                CodeNode node = new CodeNode("sql:" + filepath + ":view:" + viewName,
                        NodeKind.ENTITY, viewName);
                node.setFqn(viewName);
                node.setModule(ctx.moduleName());
                node.setFilePath(filepath);
                node.setLineStart(lineNum);
                node.setProperties(Map.of(PROP_ENTITY_TYPE, "view"));
                nodes.add(node);
                currentTableId = null;
                continue;
            }

            // Indexes
            m = INDEX_RE.matcher(line);
            if (m.find()) {
                String indexName = m.group(1);
                CodeNode node = new CodeNode("sql:" + filepath + ":index:" + indexName,
                        NodeKind.CONFIG_DEFINITION, indexName);
                node.setFqn(indexName);
                node.setModule(ctx.moduleName());
                node.setFilePath(filepath);
                node.setLineStart(lineNum);
                node.setProperties(Map.of("definition_type", "index"));
                nodes.add(node);
                continue;
            }

            // Procedures
            m = PROCEDURE_RE.matcher(line);
            if (m.find()) {
                String procName = m.group(1);
                CodeNode node = new CodeNode("sql:" + filepath + ":procedure:" + procName,
                        NodeKind.ENTITY, procName);
                node.setFqn(procName);
                node.setModule(ctx.moduleName());
                node.setFilePath(filepath);
                node.setLineStart(lineNum);
                node.setProperties(Map.of(PROP_ENTITY_TYPE, "procedure"));
                nodes.add(node);
                currentTableId = null;
                continue;
            }

            // Foreign key references
            m = FK_RE.matcher(line);
            if (m.find() && currentTableId != null) {
                String refTable = m.group(1);
                String refTableId = "sql:" + filepath + ":table:" + refTable;
                CodeEdge edge = new CodeEdge();
                edge.setId(currentTableId + "->" + refTableId);
                edge.setKind(EdgeKind.DEPENDS_ON);
                edge.setSourceId(currentTableId);
                edge.setTarget(new CodeNode(refTableId, null, null));
                edge.setProperties(Map.of("relationship", "foreign_key"));
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
