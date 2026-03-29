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
 * Detects Azure Cosmos DB client usage patterns.
 */
@Component
public class CosmosDbDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern DATABASE_RE = Pattern.compile("\\.(?:getDatabase|database)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CONTAINER_RE = Pattern.compile("\\.(?:getContainer|container)\\s*\\(\\s*\"([^\"]+)\"");

    @Override
    public String getName() {
        return "cosmos_db";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java", "typescript", "javascript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("CosmosClient") && !text.contains("CosmosDatabase")
                && !text.contains("CosmosContainer") && !text.contains("@azure/cosmos")) {
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

        String sourceNodeId = className != null ? ctx.filePath() + ":" + className : ctx.filePath();
        Set<String> seenDatabases = new LinkedHashSet<>();
        Set<String> seenContainers = new LinkedHashSet<>();

        for (int i = 0; i < lines.length; i++) {
            for (Matcher m = DATABASE_RE.matcher(lines[i]); m.find(); ) {
                String dbName = m.group(1);
                if (!seenDatabases.contains(dbName)) {
                    seenDatabases.add(dbName);
                    String dbNodeId = "azure:cosmos:db:" + dbName;
                    CodeNode node = new CodeNode();
                    node.setId(dbNodeId);
                    node.setKind(NodeKind.AZURE_RESOURCE);
                    node.setLabel("cosmosdb:" + dbName);
                    node.setFilePath(ctx.filePath());
                    node.setLineStart(i + 1);
                    node.getProperties().put("cosmos_type", "database");
                    node.getProperties().put("resource_name", dbName);
                    nodes.add(node);

                    CodeEdge edge = new CodeEdge();
                    edge.setId(sourceNodeId + "->connects_to->" + dbNodeId);
                    edge.setKind(EdgeKind.CONNECTS_TO);
                    edge.setSourceId(sourceNodeId);
                    edge.setTarget(node);
                    edges.add(edge);
                }
            }

            for (Matcher m = CONTAINER_RE.matcher(lines[i]); m.find(); ) {
                String containerName = m.group(1);
                if (!seenContainers.contains(containerName)) {
                    seenContainers.add(containerName);
                    String containerNodeId = "azure:cosmos:container:" + containerName;
                    CodeNode node = new CodeNode();
                    node.setId(containerNodeId);
                    node.setKind(NodeKind.AZURE_RESOURCE);
                    node.setLabel("cosmosdb-container:" + containerName);
                    node.setFilePath(ctx.filePath());
                    node.setLineStart(i + 1);
                    node.getProperties().put("cosmos_type", "container");
                    node.getProperties().put("resource_name", containerName);
                    nodes.add(node);

                    CodeEdge edge = new CodeEdge();
                    edge.setId(sourceNodeId + "->connects_to->" + containerNodeId);
                    edge.setKind(EdgeKind.CONNECTS_TO);
                    edge.setSourceId(sourceNodeId);
                    edge.setTarget(node);
                    edges.add(edge);
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
