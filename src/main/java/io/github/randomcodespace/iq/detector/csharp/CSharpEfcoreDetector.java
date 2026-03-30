package io.github.randomcodespace.iq.detector.csharp;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "csharp_efcore",
    category = "entities",
    description = "Detects Entity Framework Core DbContexts, entities, and migrations",
    parser = ParserType.REGEX,
    languages = {"csharp"},
    nodeKinds = {NodeKind.ENTITY, NodeKind.MIGRATION, NodeKind.REPOSITORY},
    edgeKinds = {EdgeKind.QUERIES},
    properties = {"framework"}
)
@Component
public class CSharpEfcoreDetector extends AbstractAntlrDetector {

    private static final Pattern DBCONTEXT_RE = Pattern.compile("class\\s+(\\w+)\\s*:\\s*(?:[\\w.]+\\.)?DbContext", Pattern.MULTILINE);
    private static final Pattern DBSET_RE = Pattern.compile("DbSet<(\\w+)>", Pattern.MULTILINE);
    private static final Pattern MIGRATION_RE = Pattern.compile("class\\s+(\\w+)\\s*:\\s*Migration", Pattern.MULTILINE);
    private static final Pattern CREATE_TABLE_RE = Pattern.compile("CreateTable\\s*\\(\\s*(?:name:\\s*)?\"(\\w+)\"", Pattern.MULTILINE);

    @Override
    public String getName() { return "csharp_efcore"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("csharp"); }
    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String filePath = ctx.filePath();
        List<String> contextIds = new ArrayList<>();

        // DbContext classes
        Matcher m = DBCONTEXT_RE.matcher(text);
        while (m.find()) {
            String contextName = m.group(1);
            String nodeId = "efcore:" + filePath + ":context:" + contextName;
            contextIds.add(nodeId);
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.REPOSITORY);
            node.setLabel(contextName);
            node.setFqn(contextName);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, m.start()));
            node.getProperties().put("framework", "efcore");
            nodes.add(node);
        }

        // DbSet properties
        m = DBSET_RE.matcher(text);
        while (m.find()) {
            String entityName = m.group(1);
            String entityId = "efcore:" + filePath + ":entity:" + entityName;
            CodeNode node = new CodeNode();
            node.setId(entityId);
            node.setKind(NodeKind.ENTITY);
            node.setLabel(entityName);
            node.setFqn(entityName);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, m.start()));
            node.getProperties().put("framework", "efcore");
            nodes.add(node);

            for (String ctxId : contextIds) {
                CodeEdge edge = new CodeEdge();
                edge.setId(ctxId + ":queries:" + entityName);
                edge.setKind(EdgeKind.QUERIES);
                edge.setSourceId(ctxId);
                edge.setTarget(new CodeNode(entityId, NodeKind.ENTITY, entityName));
                edges.add(edge);
            }
        }

        // Migration classes
        m = MIGRATION_RE.matcher(text);
        while (m.find()) {
            String migrationName = m.group(1);
            CodeNode node = new CodeNode();
            node.setId("efcore:" + filePath + ":migration:" + migrationName);
            node.setKind(NodeKind.MIGRATION);
            node.setLabel(migrationName);
            node.setFqn(migrationName);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, m.start()));
            node.getProperties().put("framework", "efcore");
            nodes.add(node);
        }

        // CreateTable
        m = CREATE_TABLE_RE.matcher(text);
        while (m.find()) {
            String tableName = m.group(1);
            String entityId = "efcore:" + filePath + ":entity:" + tableName;
            boolean exists = nodes.stream().anyMatch(n -> entityId.equals(n.getId()));
            if (!exists) {
                CodeNode node = new CodeNode();
                node.setId(entityId);
                node.setKind(NodeKind.ENTITY);
                node.setLabel(tableName);
                node.setFqn(tableName);
                node.setFilePath(filePath);
                node.setLineStart(findLineNumber(text, m.start()));
                node.getProperties().put("framework", "efcore");
                node.getProperties().put("source", "migration");
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
