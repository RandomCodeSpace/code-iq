package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.GraphBuilder;
import io.github.randomcodespace.iq.analyzer.LayerClassifier;
import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.cache.AnalysisCache;
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
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @Parameters(index = "0", defaultValue = ".", description = "Path to indexed codebase")
    private Path path;

    @picocli.CommandLine.Option(names = {"--graph"}, description = "Path to shared graph directory (for multi-repo)")
    private Path graphDir;

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
        Instant start = Instant.now();
        Path root = path.toAbsolutePath().normalize();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        // If --graph is set, override cache directory to shared location
        if (graphDir != null) {
            config.setCacheDir(graphDir.toAbsolutePath().normalize().toString());
            CliOutput.info("  Graph dir: " + graphDir.toAbsolutePath().normalize() + " (shared multi-repo)");
        }

        // 1. Open H2 file
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        if (!Files.exists(cachePath.getParent())) {
            CliOutput.error("No index found at " + cachePath.getParent());
            CliOutput.info("  Run 'code-iq index " + root + "' first.");
            return 1;
        }

        CliOutput.step("\uD83D\uDCE6", "Loading index from H2...");
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

    private int enrichFromCache(AnalysisCache cache, Path root, NumberFormat nf, Instant start) {
        // Load all nodes and edges from H2
        List<CodeNode> allNodes = cache.loadAllNodes();
        List<CodeEdge> allEdges = cache.loadAllEdges();

        if (allNodes.isEmpty()) {
            CliOutput.error("No indexed data found in H2. Run 'code-iq index' first.");
            return 1;
        }

        CliOutput.info("  Loaded " + nf.format(allNodes.size()) + " nodes, "
                + nf.format(allEdges.size()) + " edges from H2");

        // 2. Run linkers (these work on in-memory node/edge lists)
        CliOutput.step("\uD83D\uDD17", "Running cross-file linkers...");
        RepositoryIdentity repoIdentity = RepositoryIdentity.resolve(root);
        var builder = new GraphBuilder(repoIdentity, VersionCommand.VERSION);
        for (CodeNode node : allNodes) {
            builder.addNodes(List.of(node));
        }
        builder.addEdges(allEdges);
        builder.runLinkers(linkers);

        // Flush and collect valid edges
        GraphBuilder.FlushResult flushed = builder.flush();
        List<CodeEdge> recoveredEdges = builder.flushDeferred();

        List<CodeNode> enrichedNodes = new ArrayList<>(builder.getNodes());
        List<CodeEdge> enrichedEdges = new ArrayList<>(builder.getEdges());

        int linkerNodeDelta = enrichedNodes.size() - allNodes.size();
        int linkerEdgeDelta = enrichedEdges.size() - allEdges.size();
        if (linkerNodeDelta > 0 || linkerEdgeDelta > 0) {
            CliOutput.info("  Linkers added " + nf.format(linkerNodeDelta) + " nodes, "
                    + nf.format(linkerEdgeDelta) + " edges");
        }

        // 3. Classify layers
        CliOutput.step("\uD83C\uDFF7\uFE0F", "Classifying layers...");
        layerClassifier.classify(enrichedNodes);

        // 3b. Enrich lexical metadata (doc comments, config keys) for fulltext search
        CliOutput.step("\uD83D\uDD0D", "Enriching lexical metadata...");
        lexicalEnricher.enrich(enrichedNodes, root);

        // 3b2. Language-specific enrichment (call graph, type hints, import resolution)
        CliOutput.step("\uD83D\uDD0D", "Running language-specific enrichment...");
        languageEnricher.enrich(enrichedNodes, enrichedEdges, root);

        // 3c. Detect services
        CliOutput.step("\uD83C\uDFD7\uFE0F", "Detecting service boundaries...");
        var serviceDetector = new io.github.randomcodespace.iq.analyzer.ServiceDetector();
        String projectName = root.getFileName().toString();
        var serviceResult = serviceDetector.detect(enrichedNodes, enrichedEdges, projectName, root);
        if (!serviceResult.serviceNodes().isEmpty()) {
            serviceResult.serviceNodes().forEach(n -> n.setProvenance(builder.getProvenance()));
            // Add service nodes and edges to the builder
            builder.addNodes(serviceResult.serviceNodes());
            builder.addEdges(serviceResult.serviceEdges());
            enrichedNodes = new ArrayList<>(builder.getNodes());
            enrichedEdges = new ArrayList<>(builder.getEdges());
            CliOutput.info("  Detected " + serviceResult.serviceNodes().size() + " service(s)");
        }

        // 4. Start Neo4j Embedded and bulk-load
        Path graphPath = graphDir != null
                ? graphDir.toAbsolutePath().normalize().resolve("graph.db")
                : root.resolve(".osscodeiq/graph.db");

        CliOutput.step("\uD83D\uDCBE", "Bulk-loading into Neo4j at " + graphPath + "...");

        DatabaseManagementService dbms = null;
        try {
            Files.createDirectories(graphPath);
            dbms = new DatabaseManagementServiceBuilder(graphPath).build();
            GraphDatabaseService db = dbms.database("neo4j");

            // Clear existing data in batches to avoid memory pool limit on large graphs
            CliOutput.info("  Clearing existing graph...");
            int deleted;
            do {
                try (Transaction tx = db.beginTx()) {
                    var result = tx.execute(
                            "MATCH (n) WITH n LIMIT 5000 DETACH DELETE n RETURN count(*) AS cnt");
                    deleted = result.hasNext() ? ((Number) result.next().get("cnt")).intValue() : 0;
                    tx.commit();
                }
            } while (deleted > 0);

            // Bulk-load nodes in batches using UNWIND
            // Smaller batches to avoid Neo4j memory pool limit (nodes carry prop_* properties)
            int nodeBatchSize = 500;
            int nodesLoaded = 0;
            int totalNodes = enrichedNodes.size();
            for (int i = 0; i < totalNodes; i += nodeBatchSize) {
                int end = Math.min(i + nodeBatchSize, totalNodes);
                var batch = new ArrayList<Map<String, Object>>(end - i);
                for (int j = i; j < end; j++) {
                    CodeNode node = enrichedNodes.get(j);
                    var props = new HashMap<String, Object>();
                    props.put("id", node.getId());
                    props.put("kind", node.getKind().getValue());
                    props.put("label", node.getLabel());
                    if (node.getFqn() != null) props.put("fqn", node.getFqn());
                    if (node.getModule() != null) props.put("module", node.getModule());
                    if (node.getFilePath() != null) props.put("filePath", node.getFilePath());
                    if (node.getLineStart() != null) props.put("lineStart", node.getLineStart());
                    if (node.getLineEnd() != null) props.put("lineEnd", node.getLineEnd());
                    if (node.getLayer() != null) props.put("layer", node.getLayer());
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
                    batch.add(props);
                }
                try (Transaction tx = db.beginTx()) {
                    tx.execute("UNWIND $nodes AS props CREATE (n:CodeNode) SET n = props",
                            Map.of("nodes", batch));
                    tx.commit();
                }
                nodesLoaded += batch.size();
                if (nodesLoaded % 10000 < nodeBatchSize || nodesLoaded >= totalNodes) {
                    CliOutput.info("  nodes: " + nf.format(nodesLoaded) + "/" + nf.format(totalNodes)
                            + " (" + (100 * nodesLoaded / totalNodes) + "%)");
                }
            }

            // Create index on id for edge resolution and wait for it to come online
            CliOutput.info("  Creating index on node ID...");
            try (Transaction tx = db.beginTx()) {
                tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.id)");
                tx.commit();
            }
            // Wait for index to be populated (critical for edge MATCH performance)
            try (Transaction tx = db.beginTx()) {
                tx.execute("CALL db.awaitIndexes(300)");
            } catch (Exception e) {
                log.debug("Index await returned: {}", e.getMessage());
                // Index may already be online, continue
            }
            CliOutput.info("  Index ready");

            // Bulk-load edges
            // Use validated edges from flush
            List<CodeEdge> validEdges = flushed.edges();
            if (!recoveredEdges.isEmpty()) {
                var combined = new java.util.ArrayList<>(validEdges);
                combined.addAll(recoveredEdges);
                validEdges = combined;
            }

            // Collect valid edge maps (pre-validate before batching)
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            var validEdgeMaps = new ArrayList<Map<String, Object>>(validEdges.size());
            for (CodeEdge edge : validEdges) {
                String sourceId = edge.getSourceId();
                String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
                if (sourceId == null || targetId == null) continue;

                // Validate edge kind comes from EdgeKind enum
                String edgeKindValue = edge.getKind().getValue();
                try {
                    EdgeKind.fromValue(edgeKindValue);
                } catch (IllegalArgumentException ex) {
                    log.warn("Skipping edge with unknown kind: {}", edgeKindValue);
                    continue;
                }
                var props = new HashMap<String, Object>();
                props.put("sourceId", sourceId);
                props.put("targetId", targetId);
                props.put("edgeId", edge.getId() != null ? edge.getId() : "");
                props.put("edgeKind", edgeKindValue);
                props.put("edgeSourceId", sourceId);
                if (edge.getProperties() != null && !edge.getProperties().isEmpty()) {
                    try {
                        props.put("edgeProperties", om.writeValueAsString(edge.getProperties()));
                    } catch (Exception ignored) {}
                }
                validEdgeMaps.add(props);
            }

            int edgeBatchSize = 500;
            int edgesLoaded = 0;
            int totalEdges = validEdgeMaps.size();
            CliOutput.info("  Loading " + nf.format(totalEdges) + " edges...");
            for (int i = 0; i < totalEdges; i += edgeBatchSize) {
                int end = Math.min(i + edgeBatchSize, totalEdges);
                var batch = validEdgeMaps.subList(i, end);
                try (Transaction tx = db.beginTx()) {
                    tx.execute(
                            "UNWIND $edges AS edge "
                                    + "MATCH (s:CodeNode {id: edge.sourceId}), (t:CodeNode {id: edge.targetId}) "
                                    + "CREATE (s)-[r:RELATES_TO {id: edge.edgeId, kind: edge.edgeKind, sourceId: edge.edgeSourceId}]->(t)",
                            Map.of("edges", new ArrayList<>(batch)));
                    tx.commit();
                }
                edgesLoaded += batch.size();
                if (edgesLoaded % 10000 < edgeBatchSize || edgesLoaded >= totalEdges) {
                    CliOutput.info("  edges: " + nf.format(edgesLoaded) + "/" + nf.format(totalEdges)
                            + " (" + (100 * edgesLoaded / totalEdges) + "%)");
                }
            }

            // Create additional indexes for fast queries
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
            // Wait for all indexes (including fulltext) to finish building
            try (Transaction tx = db.beginTx()) {
                tx.execute("CALL db.awaitIndexes(300)");
            } catch (Exception e) {
                log.debug("Secondary index await returned: {}", e.getMessage());
            }
            CliOutput.info("  Created Neo4j indexes");

            Duration elapsed = Duration.between(start, Instant.now());
            long secs = elapsed.toSeconds();
            String timeStr = secs > 0 ? secs + "s" : elapsed.toMillis() + "ms";

            System.out.println();
            CliOutput.success("\u2705 Enrichment complete -- "
                    + nf.format(nodesLoaded) + " nodes, "
                    + nf.format(edgesLoaded) + " edges in " + timeStr);
            System.out.println();
            CliOutput.info("  Graph:   " + graphPath);
            CliOutput.info("  Time:    " + timeStr);
            System.out.println();
            CliOutput.info("  Next step: code-iq serve " + path);

            return 0;

        } catch (Exception e) {
            log.error("Enrichment failed", e);
            CliOutput.error("Enrichment failed: " + e.getMessage());
            return 1;
        } finally {
            if (dbms != null) {
                try {
                    dbms.shutdown();
                } catch (Exception ignored) {
                }
            }
        }
    }

}
