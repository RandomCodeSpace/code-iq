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

/**
 * Detects GraphQL resolvers from Spring GraphQL and DGS framework annotations.
 */
@Component
public class GraphqlResolverDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern QUERY_MAPPING_RE = Pattern.compile("@QueryMapping(?:\\s*\\(\\s*(?:name\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?");
    private static final Pattern MUTATION_MAPPING_RE = Pattern.compile("@MutationMapping(?:\\s*\\(\\s*(?:name\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?");
    private static final Pattern SUBSCRIPTION_MAPPING_RE = Pattern.compile("@SubscriptionMapping(?:\\s*\\(\\s*(?:name\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?");
    private static final Pattern SCHEMA_MAPPING_RE = Pattern.compile("@SchemaMapping\\s*\\(\\s*(?:typeName\\s*=\\s*\"([^\"]*)\")?");
    private static final Pattern BATCH_MAPPING_RE = Pattern.compile("@BatchMapping(?:\\s*\\(\\s*(?:field\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?");
    private static final Pattern DGS_QUERY_RE = Pattern.compile("@DgsQuery(?:\\s*\\(\\s*field\\s*=\\s*\"([^\"]*)\"\\s*\\))?");
    private static final Pattern DGS_MUTATION_RE = Pattern.compile("@DgsMutation(?:\\s*\\(\\s*field\\s*=\\s*\"([^\"]*)\"\\s*\\))?");
    private static final Pattern DGS_SUBSCRIPTION_RE = Pattern.compile("@DgsSubscription(?:\\s*\\(\\s*field\\s*=\\s*\"([^\"]*)\"\\s*\\))?");
    private static final Pattern DGS_DATA_RE = Pattern.compile("@DgsData\\s*\\(\\s*parentType\\s*=\\s*\"([^\"]*)\"(?:\\s*,\\s*field\\s*=\\s*\"([^\"]*)\")?");
    private static final Pattern METHOD_RE = Pattern.compile("(?:public|protected|private)?\\s*(?:[\\w<>\\[\\],?\\s]+)\\s+(\\w+)\\s*\\(");

    private static final List<PatternMapping> PATTERNS = List.of(
            new PatternMapping(QUERY_MAPPING_RE, "Query"),
            new PatternMapping(MUTATION_MAPPING_RE, "Mutation"),
            new PatternMapping(SUBSCRIPTION_MAPPING_RE, "Subscription"),
            new PatternMapping(DGS_QUERY_RE, "Query"),
            new PatternMapping(DGS_MUTATION_RE, "Mutation"),
            new PatternMapping(DGS_SUBSCRIPTION_RE, "Subscription")
    );

    private record PatternMapping(Pattern pattern, String gqlType) {}

    @Override
    public String getName() {
        return "graphql_resolver";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("@QueryMapping") && !text.contains("@MutationMapping") && !text.contains("@SubscriptionMapping")
                && !text.contains("@SchemaMapping") && !text.contains("@BatchMapping")
                && !text.contains("@DgsQuery") && !text.contains("@DgsMutation") && !text.contains("@DgsSubscription") && !text.contains("@DgsData")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        for (String line : lines) {
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) { className = cm.group(1); break; }
        }
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;

        for (int i = 0; i < lines.length; i++) {
            // Standard patterns
            for (PatternMapping pm : PATTERNS) {
                Matcher m = pm.pattern.matcher(lines[i]);
                if (!m.find()) continue;

                String fieldName = (m.groupCount() >= 1 && m.group(1) != null) ? m.group(1) : null;
                if (fieldName == null) {
                    fieldName = findMethodName(lines, i);
                }
                if (fieldName == null) continue;

                String resolverId = ctx.filePath() + ":" + className + ":" + pm.gqlType + ":" + fieldName;
                addResolverNode(resolverId, pm.gqlType, fieldName, className, i + 1, ctx, nodes, Map.of());
                addExposeEdge(classNodeId, resolverId, className, pm.gqlType + "." + fieldName, edges, nodes);
            }

            // @SchemaMapping
            Matcher sm = SCHEMA_MAPPING_RE.matcher(lines[i]);
            if (sm.find()) {
                String typeName = sm.group(1) != null ? sm.group(1) : "Unknown";
                String methodName = findMethodName(lines, i);
                if (methodName != null) {
                    String resolverId = ctx.filePath() + ":" + className + ":SchemaMapping:" + typeName + "." + methodName;
                    addResolverNode(resolverId, typeName, methodName, className, i + 1, ctx, nodes, Map.of());
                    addExposeEdge(classNodeId, resolverId, className, typeName + "." + methodName, edges, nodes);
                }
            }

            // @DgsData
            Matcher dm = DGS_DATA_RE.matcher(lines[i]);
            if (dm.find()) {
                String parentType = dm.group(1);
                String fieldName = (dm.groupCount() >= 2 && dm.group(2) != null) ? dm.group(2) : null;
                if (fieldName == null) {
                    fieldName = findMethodName(lines, i);
                }
                if (fieldName != null) {
                    String resolverId = ctx.filePath() + ":" + className + ":DgsData:" + parentType + "." + fieldName;
                    addResolverNode(resolverId, parentType, fieldName, className, i + 1, ctx, nodes, Map.of("framework", "dgs"));
                    addExposeEdge(classNodeId, resolverId, className, parentType + "." + fieldName, edges, nodes);
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private String findMethodName(String[] lines, int lineIdx) {
        for (int k = lineIdx + 1; k < Math.min(lineIdx + 4, lines.length); k++) {
            Matcher mm = METHOD_RE.matcher(lines[k]);
            if (mm.find()) return mm.group(1);
        }
        return null;
    }

    private void addResolverNode(String id, String gqlType, String fieldName, String className,
                                 int line, DetectorContext ctx, List<CodeNode> nodes, Map<String, Object> extra) {
        CodeNode node = new CodeNode();
        node.setId(id);
        node.setKind(NodeKind.ENDPOINT);
        node.setLabel("GraphQL " + gqlType + "." + fieldName);
        node.setFqn(className + "." + fieldName);
        node.setFilePath(ctx.filePath());
        node.setLineStart(line);
        node.getProperties().put("graphql_type", gqlType);
        node.getProperties().put("field", fieldName);
        node.getProperties().put("protocol", "graphql");
        node.getProperties().putAll(extra);
        nodes.add(node);
    }

    private void addExposeEdge(String classNodeId, String resolverId, String className, String label,
                               List<CodeEdge> edges, List<CodeNode> nodes) {
        CodeEdge edge = new CodeEdge();
        edge.setId(classNodeId + "->exposes->" + resolverId);
        edge.setKind(EdgeKind.EXPOSES);
        edge.setSourceId(classNodeId);
        edge.setTarget(new CodeNode(resolverId, NodeKind.ENDPOINT, label));
        edges.add(edge);
    }
}
