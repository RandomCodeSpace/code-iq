package io.github.randomcodespace.iq.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.analyzer.GraphBuilder;
import io.github.randomcodespace.iq.analyzer.LayerClassifier;
import io.github.randomcodespace.iq.analyzer.ServiceDetector;
import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CliStartupConfigOverrides;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.intelligence.RepositoryIdentity;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageEnricher;
import io.github.randomcodespace.iq.intelligence.lexical.LexicalEnricher;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Load indexed data from H2 into Neo4j, run linkers and classifiers.
 * <p>
 * This command reads the H2 index produced by {@code index} and bulk-loads
 * all nodes and edges into a Neo4j embedded database. It then runs cross-file
 * linkers (TopicLinker, EntityLinker, ModuleContainmentLinker) and the
 * LayerClassifier to enrich the graph with inferred relationships and
 * layer classifications.
 * <p>
 * Neo4j is started programmatically (not via Spring bean) to avoid starting
 * it during indexing.
 */
@Component
@Command(name = "enrich", mixinStandardHelpOptions = true,
        description = "Load indexed data into Neo4j, run linkers and classifiers")
public class EnrichCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(EnrichCommand.class);

    private static final int NODE_BATCH_SIZE = 500;
    private static final int EDGE_BATCH_SIZE = 500;
    private static final int STUB_BATCH_SIZE = 500;
    private static final int CLEAR_BATCH_SIZE = 5000;
    private static final int PROGRESS_REPORT_INTERVAL = 10000;
    private static final int INDEX_AWAIT_SECONDS = 300;
    private static final String UNWIND_STUB_MERGE =
            "UNWIND $batch AS n MERGE (node:CodeNode {id: n.id}) "
                    + "ON CREATE SET node.kind = n.kind, node.label = n.label";

    @Parameters(index = "0", defaultValue = ".", description = "Path to indexed codebase")
    private Path path;

    @picocli.CommandLine.Option(names = {"--graph"}, description = "Path to shared graph directory (for multi-repo)")
    private Path graphDir;

    @picocli.CommandLine.Option(names = {"--verbose", "-v"}, description = "Enable verbose per-file logging")
    private boolean verbose;

    private final CodeIqConfig config;
    private final LayerClassifier layerClassifier;
    private final List<Linker> linkers;
    private final LexicalEnricher lexicalEnricher;
    private final LanguageEnricher languageEnricher;

    public EnrichCommand(CodeIqConfig config, LayerClassifier layerClassifier,
                         List<Linker> linkers, LexicalEnricher lexicalEnricher,
                         LanguageEnricher languageEnricher) {
        this.config = config;
        this.layerClassifier = layerClassifier;
        this.linkers = linkers;
        this.lexicalEnricher = lexicalEnricher;
        this.languageEnricher = languageEnricher;
    }

    @Override
    public Integer call() {
        if (verbose) {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.github.randomcodespace.iq"))
                    .setLevel(ch.qos.logback.classic.Level.DEBUG);
        }

        Instant start = Instant.now();
        Path root = path.toAbsolutePath().normalize();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        applyGraphDirOverride();

        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        // cachePath.getParent() is always non-null here because we resolve off
        // `root` (a directory), but null-guard explicitly for SpotBugs and to
        // protect against a future refactor that changes the resolution.
        Path cacheParent = cachePath.getParent();
        if (cacheParent == null || !Files.exists(cacheParent)) {
            CliOutput.error("No index found at " + cacheParent);
            CliOutput.info("  Run 'codeiq index " + root + "' first.");
            return 1;
        }

        CliOutput.step("[+]", "Loading index from H2...");
        AnalysisCache cache;
        try {
            cache = new AnalysisCache(cachePath);
        } catch (Exception e) {
            CliOutput.error("Failed to open H2 index: " + e.getMessage());
            return 1;
        }

        try {
            return enrichFromCache(cache, root, nf, start);
        } finally {
            cache.close();
        }
    }

    private void applyGraphDirOverride() {
        if (graphDir == null) {
            return;
        }
        Path sharedDir = graphDir.toAbsolutePath().normalize();
        CliStartupConfigOverrides.applyCacheDir(config, sharedDir.toString());
        CliOutput.info("  Graph dir: " + sharedDir + " (shared multi-repo)");
    }

    private int enrichFromCache(AnalysisCache cache, Path root, NumberFormat nf, Instant start) {
        List<CodeNode> allNodes = cache.loadAllNodes();
        List<CodeEdge> allEdges = cache.loadAllEdges();

        if (allNodes.isEmpty()) {
            CliOutput.error("No indexed data found in H2. Run 'codeiq index' first.");
            return 1;
        }

        CliOutput.info("  Loaded " + nf.format(allNodes.size()) + " nodes, "
                + nf.format(allEdges.size()) + " edges from H2");

        GraphBuilder builder = runLinkerPhase(allNodes, allEdges, root, nf);
        List<CodeNode> enrichedNodes = new ArrayList<>(builder.getNodes());
        List<CodeEdge> enrichedEdges = new ArrayList<>(builder.getEdges());

        runClassifierAndEnrichers(enrichedNodes, enrichedEdges, root);

        EnrichedGraph withServices = runServiceDetection(builder, enrichedNodes, enrichedEdges, root);

        Path graphPath = resolveGraphPath(root);
        return bulkLoadIntoNeo4j(graphPath, withServices, nf, start);
    }

    private GraphBuilder runLinkerPhase(List<CodeNode> allNodes, List<CodeEdge> allEdges,
                                        Path root, NumberFormat nf) {
        CliOutput.step("[-]", "Running cross-file linkers...");
        Instant stepStart = Instant.now();
        RepositoryIdentity repoIdentity = RepositoryIdentity.resolve(root);
        var builder = new GraphBuilder(repoIdentity, VersionCommand.VERSION);
        for (CodeNode node : allNodes) {
            builder.addNodes(List.of(node));
        }
        builder.addEdges(allEdges);
        builder.runLinkers(linkers);

        // Flush buffered graph state and retry any deferred edges so the
        // side effects (provenance stamping, edge validation, dropped-edge
        // counters) still run even though we read enriched nodes/edges
        // straight off the builder below.
        builder.flush();
        builder.flushDeferred();

        int linkerNodeDelta = builder.getNodes().size() - allNodes.size();
        int linkerEdgeDelta = builder.getEdges().size() - allEdges.size();
        if (linkerNodeDelta > 0 || linkerEdgeDelta > 0) {
            CliOutput.info("  Linkers added " + nf.format(linkerNodeDelta) + " nodes, "
                    + nf.format(linkerEdgeDelta) + " edges");
        }
        logStepDone(stepStart);
        return builder;
    }

    private void runClassifierAndEnrichers(List<CodeNode> enrichedNodes,
                                           List<CodeEdge> enrichedEdges, Path root) {
        CliOutput.step("[#]", "Classifying layers...");
        Instant stepStart = Instant.now();
        layerClassifier.classify(enrichedNodes);
        logStepDone(stepStart);

        CliOutput.step("[*]", "Enriching lexical metadata...");
        stepStart = Instant.now();
        lexicalEnricher.enrich(enrichedNodes, root);
        logStepDone(stepStart);

        CliOutput.step("[*]", "Running language-specific enrichment...");
        stepStart = Instant.now();
        languageEnricher.enrich(enrichedNodes, enrichedEdges, root);
        logStepDone(stepStart);
    }

    private EnrichedGraph runServiceDetection(GraphBuilder builder, List<CodeNode> enrichedNodes,
                                              List<CodeEdge> enrichedEdges, Path root) {
        CliOutput.step("[^]", "Detecting service boundaries...");
        Instant stepStart = Instant.now();
        var serviceDetector = new ServiceDetector();
        String projectName = Objects.toString(root.getFileName(), "unknown");
        var serviceResult = serviceDetector.detect(enrichedNodes, enrichedEdges, projectName, root);

        List<CodeNode> nodesOut = enrichedNodes;
        List<CodeEdge> edgesOut = enrichedEdges;
        if (!serviceResult.serviceNodes().isEmpty()) {
            serviceResult.serviceNodes().forEach(n -> n.setProvenance(builder.getProvenance()));
            builder.addNodes(serviceResult.serviceNodes());
            builder.addEdges(serviceResult.serviceEdges());
            nodesOut = new ArrayList<>(builder.getNodes());
            edgesOut = new ArrayList<>(builder.getEdges());
            CliOutput.info("  Detected " + serviceResult.serviceNodes().size() + " service(s)");
        }
        logStepDone(stepStart);
        return new EnrichedGraph(nodesOut, edgesOut);
    }

    private Path resolveGraphPath(Path root) {
        return graphDir != null
                ? graphDir.toAbsolutePath().normalize().resolve("graph.db")
                : root.resolve(config.getGraph().getPath());
    }

    private int bulkLoadIntoNeo4j(Path graphPath, EnrichedGraph graph,
                                  NumberFormat nf, Instant start) {
        CliOutput.step("[~]", "Bulk-loading into Neo4j at " + graphPath + "...");
        Instant stepStart = Instant.now();

        DatabaseManagementService dbms = null;
        try {
            Files.createDirectories(graphPath);
            dbms = new DatabaseManagementServiceBuilder(graphPath).build();
            GraphDatabaseService db = dbms.database("neo4j");

            clearGraph(db);
            int nodesLoaded = bulkLoadNodes(db, graph.nodes(), nf);
            createPrimaryIndex(db);

            Set<String> loadedNodeIds = collectLoadedNodeIds(graph.nodes());
            createStubNodesForExternalRefs(db, graph.edges(), loadedNodeIds);

            List<Map<String, Object>> validEdgeMaps = buildValidEdgeMaps(graph.edges());
            int edgesLoaded = bulkLoadEdges(db, validEdgeMaps, nf);

            createSecondaryIndexes(db);
            CliOutput.info("  Created Neo4j indexes");
            logStepDone(stepStart);

            printCompletionSummary(graphPath, nodesLoaded, edgesLoaded, nf, start);
            return 0;

        } catch (IOException | RuntimeException e) {
            log.error("Enrichment failed", e);
            CliOutput.error("Enrichment failed: " + e.getMessage());
            return 1;
        } finally {
            shutdownQuietly(dbms);
        }
    }

    private void clearGraph(GraphDatabaseService db) {
        CliOutput.info("  Clearing existing graph...");
        // Clear in batches to avoid memory pool limit on large graphs.
        int deleted;
        do {
            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute(
                        "MATCH (n) WITH n LIMIT " + CLEAR_BATCH_SIZE + " DETACH DELETE n RETURN count(*) AS cnt");
                deleted = result.hasNext() ? ((Number) result.next().get("cnt")).intValue() : 0;
                tx.commit();
            }
        } while (deleted > 0);
    }

    private int bulkLoadNodes(GraphDatabaseService db, List<CodeNode> nodes, NumberFormat nf) {
        int totalNodes = nodes.size();
        int nodesLoaded = 0;
        // Smaller batches to avoid Neo4j memory pool limit (nodes carry prop_* properties).
        for (int i = 0; i < totalNodes; i += NODE_BATCH_SIZE) {
            int end = Math.min(i + NODE_BATCH_SIZE, totalNodes);
            List<Map<String, Object>> batch = buildNodePropsBatch(nodes, i, end);
            try (Transaction tx = db.beginTx()) {
                tx.execute("UNWIND $nodes AS props CREATE (n:CodeNode) SET n = props",
                        Map.of("nodes", batch));
                tx.commit();
            }
            nodesLoaded += batch.size();
            reportNodeProgress(nodesLoaded, totalNodes, nf);
        }
        return nodesLoaded;
    }

    private List<Map<String, Object>> buildNodePropsBatch(List<CodeNode> nodes, int start, int end) {
        List<Map<String, Object>> batch = new ArrayList<>(end - start);
        for (int j = start; j < end; j++) {
            batch.add(toNodeProps(nodes.get(j)));
        }
        return batch;
    }

    private Map<String, Object> toNodeProps(CodeNode node) {
        Map<String, Object> props = new HashMap<>();
        props.put("id", node.getId());
        props.put("kind", node.getKind().getValue());
        props.put("label", node.getLabel());
        putIfNotNull(props, "fqn", node.getFqn());
        putIfNotNull(props, "module", node.getModule());
        putIfNotNull(props, "filePath", node.getFilePath());
        putIfNotNull(props, "lineStart", node.getLineStart());
        putIfNotNull(props, "lineEnd", node.getLineEnd());
        putIfNotNull(props, "layer", node.getLayer());
        if (node.getAnnotations() != null && !node.getAnnotations().isEmpty()) {
            props.put("annotations", String.join(",", node.getAnnotations()));
        }
        // Include detector properties (framework, http_method, auth_type, etc.)
        if (node.getProperties() != null) {
            for (var entry : node.getProperties().entrySet()) {
                if (entry.getValue() != null) {
                    props.put("prop_" + entry.getKey(), entry.getValue().toString());
                }
            }
        }
        return props;
    }

    private static void putIfNotNull(Map<String, Object> props, String key, Object value) {
        if (value != null) {
            props.put(key, value);
        }
    }

    private void reportNodeProgress(int nodesLoaded, int totalNodes, NumberFormat nf) {
        if (nodesLoaded % PROGRESS_REPORT_INTERVAL < NODE_BATCH_SIZE || nodesLoaded >= totalNodes) {
            CliOutput.info("  nodes: " + nf.format(nodesLoaded) + "/" + nf.format(totalNodes)
                    + " (" + (100 * nodesLoaded / totalNodes) + "%)");
        }
    }

    private void createPrimaryIndex(GraphDatabaseService db) {
        CliOutput.info("  Creating index on node ID...");
        try (Transaction tx = db.beginTx()) {
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.id)");
            tx.commit();
        }
        // Wait for index to be populated (critical for edge MATCH performance).
        awaitIndexes(db, "Index await returned: {}");
        CliOutput.info("  Index ready");
    }

    private void awaitIndexes(GraphDatabaseService db, String debugTemplate) {
        try (Transaction tx = db.beginTx()) {
            tx.execute("CALL db.awaitIndexes(" + INDEX_AWAIT_SECONDS + ")");
        } catch (Exception e) {
            log.debug(debugTemplate, e.getMessage());
        }
    }

    private Set<String> collectLoadedNodeIds(List<CodeNode> nodes) {
        Set<String> loadedNodeIds = new HashSet<>(nodes.size());
        for (CodeNode n : nodes) {
            loadedNodeIds.add(n.getId());
        }
        return loadedNodeIds;
    }

    private void createStubNodesForExternalRefs(GraphDatabaseService db, List<CodeEdge> edges,
                                                Set<String> loadedNodeIds) {
        Set<String> stubIds = new LinkedHashSet<>();
        for (CodeEdge edge : edges) {
            String sourceId = edge.getSourceId();
            String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
            if (sourceId != null && !loadedNodeIds.contains(sourceId)) {
                stubIds.add(sourceId);
            }
            if (targetId != null && !loadedNodeIds.contains(targetId)) {
                stubIds.add(targetId);
            }
        }
        if (stubIds.isEmpty()) {
            return;
        }
        CliOutput.info("  Creating " + stubIds.size() + " stub nodes for external references...");
        flushStubBatches(db, stubIds, loadedNodeIds);
    }

    private void flushStubBatches(GraphDatabaseService db, Set<String> stubIds,
                                  Set<String> loadedNodeIds) {
        List<Map<String, Object>> stubBatch = new ArrayList<>();
        for (String stubId : stubIds) {
            stubBatch.add(Map.of("id", stubId, "kind", "external", "label", stubId));
            loadedNodeIds.add(stubId);
            if (stubBatch.size() >= STUB_BATCH_SIZE) {
                writeStubBatch(db, stubBatch);
                stubBatch.clear();
            }
        }
        if (!stubBatch.isEmpty()) {
            writeStubBatch(db, stubBatch);
        }
    }

    private void writeStubBatch(GraphDatabaseService db, List<Map<String, Object>> stubBatch) {
        try (Transaction tx = db.beginTx()) {
            tx.execute(UNWIND_STUB_MERGE, Map.of("batch", stubBatch));
            tx.commit();
        }
    }

    private List<Map<String, Object>> buildValidEdgeMaps(List<CodeEdge> edges) {
        ObjectMapper om = new ObjectMapper();
        List<Map<String, Object>> validEdgeMaps = new ArrayList<>(edges.size());
        for (CodeEdge edge : edges) {
            Map<String, Object> props = toEdgeProps(edge, om);
            if (props != null) {
                validEdgeMaps.add(props);
            }
        }
        return validEdgeMaps;
    }

    private Map<String, Object> toEdgeProps(CodeEdge edge, ObjectMapper om) {
        String sourceId = edge.getSourceId();
        String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
        if (sourceId == null || targetId == null) {
            return null;
        }
        String edgeKindValue = edge.getKind().getValue();
        try {
            EdgeKind.fromValue(edgeKindValue);
        } catch (IllegalArgumentException ex) {
            log.warn("Skipping edge with unknown kind: {}", edgeKindValue);
            return null;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("sourceId", sourceId);
        props.put("targetId", targetId);
        props.put("edgeId", edge.getId() != null ? edge.getId() : "");
        props.put("edgeKind", edgeKindValue);
        props.put("edgeSourceId", sourceId);
        if (edge.getProperties() != null && !edge.getProperties().isEmpty()) {
            try {
                props.put("edgeProperties", om.writeValueAsString(edge.getProperties()));
            } catch (JsonProcessingException ignored) {
                // Best-effort: omit edge properties when they cannot be serialized.
            }
        }
        return props;
    }

    private int bulkLoadEdges(GraphDatabaseService db, List<Map<String, Object>> validEdgeMaps,
                              NumberFormat nf) {
        int totalEdges = validEdgeMaps.size();
        CliOutput.info("  Loading " + nf.format(totalEdges) + " edges...");
        int edgesLoaded = 0;
        for (int i = 0; i < totalEdges; i += EDGE_BATCH_SIZE) {
            int end = Math.min(i + EDGE_BATCH_SIZE, totalEdges);
            List<Map<String, Object>> batch = validEdgeMaps.subList(i, end);
            try (Transaction tx = db.beginTx()) {
                tx.execute(
                        "UNWIND $edges AS edge "
                                + "MATCH (s:CodeNode {id: edge.sourceId}), (t:CodeNode {id: edge.targetId}) "
                                + "CREATE (s)-[r:RELATES_TO {id: edge.edgeId, kind: edge.edgeKind, sourceId: edge.edgeSourceId}]->(t)",
                        Map.of("edges", new ArrayList<>(batch)));
                tx.commit();
            }
            edgesLoaded += batch.size();
            reportEdgeProgress(edgesLoaded, totalEdges, nf);
        }
        return edgesLoaded;
    }

    private void reportEdgeProgress(int edgesLoaded, int totalEdges, NumberFormat nf) {
        if (edgesLoaded % PROGRESS_REPORT_INTERVAL < EDGE_BATCH_SIZE || edgesLoaded >= totalEdges) {
            CliOutput.info("  edges: " + nf.format(edgesLoaded) + "/" + nf.format(totalEdges)
                    + " (" + (100 * edgesLoaded / totalEdges) + "%)");
        }
    }

    private void createSecondaryIndexes(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.kind)");
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.layer)");
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.module)");
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.filePath)");
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.label_lower)");
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.fqn_lower)");
            tx.execute("CREATE FULLTEXT INDEX search_index IF NOT EXISTS "
                    + "FOR (n:CodeNode) ON EACH [n.label_lower, n.fqn_lower] "
                    + "OPTIONS {indexConfig: {`fulltext.analyzer`: 'keyword'}}");
            tx.execute("CREATE FULLTEXT INDEX lexical_index IF NOT EXISTS "
                    + "FOR (n:CodeNode) ON EACH [n.prop_lex_comment, n.prop_lex_config_keys] "
                    + "OPTIONS {indexConfig: {`fulltext.analyzer`: 'standard'}}");
            tx.commit();
        }
        // Wait for all indexes (including fulltext) to finish building.
        awaitIndexes(db, "Secondary index await returned: {}");
    }

    private void printCompletionSummary(Path graphPath, int nodesLoaded, int edgesLoaded,
                                        NumberFormat nf, Instant start) {
        Duration elapsed = Duration.between(start, Instant.now());
        long secs = elapsed.toSeconds();
        String timeStr = secs > 0 ? secs + "s" : elapsed.toMillis() + "ms";

        System.out.println();
        CliOutput.success("[OK] Enrichment complete -- "
                + nf.format(nodesLoaded) + " nodes, "
                + nf.format(edgesLoaded) + " edges in " + timeStr);
        System.out.println();
        CliOutput.info("  Graph:   " + graphPath);
        CliOutput.info("  Time:    " + timeStr);
        System.out.println();
        CliOutput.info("  Next step: codeiq serve " + path);
    }

    private static void shutdownQuietly(DatabaseManagementService dbms) {
        if (dbms == null) {
            return;
        }
        try {
            dbms.shutdown();
        } catch (Exception ignored) {
            // Best-effort shutdown — already failing or already shut down.
        }
    }

    private static void logStepDone(Instant stepStart) {
        CliOutput.info("  Done in " + Duration.between(stepStart, Instant.now()).toMillis() + "ms");
    }

    /** In-flight enriched graph state passed between phases. */
    private record EnrichedGraph(List<CodeNode> nodes, List<CodeEdge> edges) {
    }
}
