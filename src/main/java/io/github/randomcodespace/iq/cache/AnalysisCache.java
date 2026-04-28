package io.github.randomcodespace.iq.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * H2-backed cache for incremental analysis results.
 * <p>
 * Stores per-file parse results (nodes and edges) keyed by content hash,
 * enabling fast incremental re-analysis when only a subset of files change.
 * <p>
 * Uses H2 in embedded mode — pure Java, no JNI, MVCC concurrency,
 * fully compatible with virtual threads.
 */
public final class AnalysisCache implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCache.class);

    /** Bump when hash algorithm or serialization shape changes to force cache invalidation. */
    private static final int CACHE_VERSION = 5;

    private static final String SCHEMA_SQL = """
            CREATE TABLE IF NOT EXISTS cache_meta (
                meta_key VARCHAR PRIMARY KEY,
                meta_value VARCHAR NOT NULL
            );

            CREATE TABLE IF NOT EXISTS files (
                content_hash VARCHAR PRIMARY KEY,
                path VARCHAR NOT NULL,
                language VARCHAR NOT NULL,
                parsed_at VARCHAR NOT NULL,
                status VARCHAR DEFAULT 'DETECTED',
                detection_method VARCHAR DEFAULT 'antlr',
                file_type VARCHAR DEFAULT 'source',
                snippet VARCHAR
            );

            CREATE TABLE IF NOT EXISTS nodes (
                row_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                id VARCHAR NOT NULL,
                content_hash VARCHAR NOT NULL,
                kind VARCHAR NOT NULL,
                data VARCHAR NOT NULL,
                FOREIGN KEY (content_hash) REFERENCES files(content_hash)
            );

            CREATE TABLE IF NOT EXISTS edges (
                source VARCHAR NOT NULL,
                target VARCHAR NOT NULL,
                content_hash VARCHAR NOT NULL,
                kind VARCHAR NOT NULL,
                data VARCHAR NOT NULL
            );

            CREATE TABLE IF NOT EXISTS analysis_runs (
                run_id VARCHAR PRIMARY KEY,
                commit_sha VARCHAR,
                timestamp VARCHAR NOT NULL,
                file_count INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_nodes_content_hash ON nodes(content_hash);
            CREATE INDEX IF NOT EXISTS idx_edges_content_hash ON edges(content_hash);
            CREATE INDEX IF NOT EXISTS idx_analysis_runs_timestamp ON analysis_runs(timestamp);
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Read-write lock replacing synchronized methods.
     * Read operations (isCached, loadCachedResults, getHashForPath, etc.) use readLock.
     * Write operations (storeResults, replaceAll, clear, removeFile) use writeLock.
     * This prevents ClosedChannelException from concurrent virtual thread writes
     * to H2's MVStore file channel.
     */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    private final Connection conn;
    private final Path dbPath;

    /**
     * Open or create an analysis cache at the given path.
     * <p>
     * The path should point to the desired database file location.
     * H2 will append {@code .mv.db} to the actual file on disk.
     *
     * @param dbPath path to the H2 database file (without extension)
     */
    public AnalysisCache(Path dbPath) {
        this(dbPath, false);
    }

    /**
     * Open an analysis cache. In read-only mode, no lock files are created
     * and no writes are allowed — suitable for read-only filesystems (AKS/K8s).
     *
     * @param dbPath   path to the H2 database file (without extension)
     * @param readOnly if true, opens DB in read-only mode (no lock files, no writes)
     */
    public AnalysisCache(Path dbPath, boolean readOnly) {
        this.dbPath = dbPath;
        try {
            if (!readOnly) {
                // dbPath.getParent() is null for a bare filename (no directory
                // component). Treat "already a directory" as nothing to create.
                Path parent = dbPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
            // Strip .db extension if present — H2 appends its own .mv.db
            String dbFile = dbPath.toString();
            if (dbFile.endsWith(".db")) {
                dbFile = dbFile.substring(0, dbFile.length() - 3);
            }
            String url = "jdbc:h2:file:" + dbFile + ";AUTO_SERVER=FALSE;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
            if (readOnly) {
                url += ";ACCESS_MODE_DATA=r;FILE_LOCK=NO";
            } else {
                url += ";WRITE_DELAY=0";
            }
            this.conn = DriverManager.getConnection(url);
            if (!readOnly) {
                initDb();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to open analysis cache at " + dbPath, e);
        }
    }

    private void initDb() throws SQLException {
        for (String sql : SCHEMA_SQL.split(";")) {
            String trimmed = sql.trim();
            if (!trimmed.isEmpty()) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute(trimmed);
                }
            }
        }
        checkCacheVersion();
    }

    /**
     * If the cache was created with a different version (e.g. MD5 vs SHA-256 hashes),
     * clear all cached data so files are re-analyzed with the current algorithm.
     */
    private void checkCacheVersion() throws SQLException {
        String storedVersion = null;
        try (var ps = conn.prepareStatement("SELECT meta_value FROM cache_meta WHERE meta_key = 'version'");
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                storedVersion = rs.getString(1);
            }
        }
        if (!String.valueOf(CACHE_VERSION).equals(storedVersion)) {
            if (storedVersion != null) {
                log.info("Cache version mismatch (stored={}, current={}) — dropping and recreating tables",
                        storedVersion, CACHE_VERSION);
            }
            // DROP and recreate tables to pick up schema changes (new columns, types, etc.)
            // CREATE TABLE IF NOT EXISTS doesn't add columns to existing tables.
            try (var stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS edges");
                stmt.execute("DROP TABLE IF EXISTS nodes");
                stmt.execute("DROP TABLE IF EXISTS files");
                stmt.execute("DROP TABLE IF EXISTS analysis_runs");
            }
            // Recreate with current schema
            for (String sql : SCHEMA_SQL.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty() && !trimmed.toUpperCase().startsWith("CREATE TABLE IF NOT EXISTS CACHE_META")) {
                    try (var stmt = conn.createStatement()) {
                        stmt.execute(trimmed);
                    }
                }
            }
            try (var stmt = conn.createStatement()) {
                stmt.execute("MERGE INTO cache_meta (meta_key, meta_value) VALUES ('version', '" + CACHE_VERSION + "')");
            }
        }
    }

    // --- Commit tracking ---

    /**
     * Return the commit SHA from the most recent analysis run, or null.
     */
    public String getLastCommit() {
        rwLock.readLock().lock();
        try (var stmt = conn.prepareStatement(
                "SELECT commit_sha FROM analysis_runs ORDER BY timestamp DESC LIMIT 1")) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.debug("Failed to get last commit", e);
        } finally {
            rwLock.readLock().unlock();
        }
        return null;
    }

    // --- Cache lookups ---

    /**
     * Check whether results for the given content hash are cached.
     */
    public boolean isCached(String contentHash) {
        rwLock.readLock().lock();
        try (var stmt = conn.prepareStatement(
                "SELECT 1 FROM files WHERE content_hash = ?")) {
            stmt.setString(1, contentHash);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.debug("Cache lookup failed", e);
            return false;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Look up the content hash stored for a given file path.
     * Returns null if the path has not been cached yet.
     */
    public String getHashForPath(String filePath) {
        rwLock.readLock().lock();
        try (var stmt = conn.prepareStatement(
                "SELECT content_hash FROM files WHERE path = ? LIMIT 1")) {
            stmt.setString(1, filePath);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.debug("Hash lookup by path failed", e);
            return null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // --- Store results ---

    /**
     * Persist analysis results for a single file with default status and detection method.
     */
    public void storeResults(String contentHash, String filePath, String language,
                             List<CodeNode> nodes, List<CodeEdge> edges) {
        storeResults(contentHash, filePath, language, nodes, edges, "DETECTED", "antlr");
    }

    /**
     * Persist analysis results for a single file with explicit status and detection method.
     *
     * @param contentHash     content hash key
     * @param filePath        file path
     * @param language        programming language
     * @param nodes           detected nodes
     * @param edges           detected edges
     * @param status          file status (e.g. "DETECTED", "filtered")
     * @param detectionMethod detection method used (e.g. "antlr", "regex_fallback", "none")
     */
    public void storeResults(String contentHash, String filePath, String language,
                             List<CodeNode> nodes, List<CodeEdge> edges,
                             String status, String detectionMethod) {
        storeResults(contentHash, filePath, language, nodes, edges, status, detectionMethod, "source", null);
    }

    /**
     * Persist analysis results for a single file with file type and snippet.
     *
     * @param contentHash     content hash key
     * @param filePath        file path
     * @param language        programming language
     * @param nodes           detected nodes
     * @param edges           detected edges
     * @param status          file status (e.g. "DETECTED", "filtered")
     * @param detectionMethod detection method used (e.g. "antlr", "regex_fallback", "none")
     * @param fileType        file type classification (e.g. "source", "test", "config", "binary")
     * @param snippet         first 200 lines of the file content (max 10KB), or null
     */
    public void storeResults(String contentHash, String filePath, String language,
                             List<CodeNode> nodes, List<CodeEdge> edges,
                             String status, String detectionMethod,
                             String fileType, String snippet) {
        rwLock.writeLock().lock();
        try {
            conn.setAutoCommit(false);
            String now = Instant.now().toString();

            // Upsert file record (H2 MySQL mode supports INSERT ... ON DUPLICATE KEY UPDATE)
            try (var stmt = conn.prepareStatement(
                    "MERGE INTO files (content_hash, path, language, parsed_at, status, detection_method, file_type, snippet) KEY (content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, contentHash);
                stmt.setString(2, filePath);
                stmt.setString(3, language);
                stmt.setString(4, now);
                stmt.setString(5, status);
                stmt.setString(6, detectionMethod);
                stmt.setString(7, fileType);
                stmt.setString(8, snippet);
                stmt.execute();
            }

            // Remove old nodes/edges for this hash
            try (var stmt = conn.prepareStatement("DELETE FROM nodes WHERE content_hash = ?")) {
                stmt.setString(1, contentHash);
                stmt.execute();
            }
            try (var stmt = conn.prepareStatement("DELETE FROM edges WHERE content_hash = ?")) {
                stmt.setString(1, contentHash);
                stmt.execute();
            }

            // Insert nodes (no unique constraint on id — duplicates preserved for accurate cache replay)
            try (var stmt = conn.prepareStatement(
                    "INSERT INTO nodes (id, content_hash, kind, data) VALUES (?, ?, ?, ?)")) {
                for (CodeNode node : nodes) {
                    stmt.setString(1, node.getId());
                    stmt.setString(2, contentHash);
                    stmt.setString(3, node.getKind().getValue());
                    stmt.setString(4, serializeNode(node));
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            // Insert edges
            try (var stmt = conn.prepareStatement(
                    "INSERT INTO edges (source, target, content_hash, kind, data) VALUES (?, ?, ?, ?, ?)")) {
                for (CodeEdge edge : edges) {
                    stmt.setString(1, edge.getSourceId());
                    stmt.setString(2, edge.getTarget() != null ? edge.getTarget().getId() : "");
                    stmt.setString(3, contentHash);
                    stmt.setString(4, edge.getKind().getValue());
                    stmt.setString(5, serializeEdge(edge));
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            log.warn("Failed to store cached results for hash {}", contentHash, e);
        } finally {
            try {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // best-effort restore; the INSERTs have already been committed or rolled back.
                }
            } finally {
                // Guarantee unlock even if conn.setAutoCommit throws a non-SQLException
                // (RuntimeException / Error). Fixes SpotBugs UL_UNRELEASED_LOCK_EXCEPTION_PATH.
                rwLock.writeLock().unlock();
            }
        }
    }

    // --- Load cached results ---

    /**
     * Load cached nodes and edges for a given content hash.
     *
     * @return a CachedResult with the nodes and edges, or null if not cached
     */
    public CachedResult loadCachedResults(String contentHash) {
        rwLock.readLock().lock();
        try {
            List<CodeNode> nodes = new ArrayList<>();
            try (var stmt = conn.prepareStatement("SELECT data FROM nodes WHERE content_hash = ?")) {
                stmt.setString(1, contentHash);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        CodeNode node = deserializeNode(rs.getString(1));
                        if (node != null) nodes.add(node);
                    }
                }
            }

            List<CodeEdge> edges = new ArrayList<>();
            try (var stmt = conn.prepareStatement("SELECT data FROM edges WHERE content_hash = ?")) {
                stmt.setString(1, contentHash);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        CodeEdge edge = deserializeEdge(rs.getString(1));
                        if (edge != null) edges.add(edge);
                    }
                }
            }

            if (nodes.isEmpty() && edges.isEmpty()) {
                return null;
            }
            return new CachedResult(nodes, edges);
        } catch (SQLException e) {
            log.debug("Failed to load cached results for hash {}", contentHash, e);
            return null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // --- Cache invalidation ---

    /**
     * Delete all cached results associated with a content hash.
     */
    public void removeFile(String contentHash) {
        rwLock.writeLock().lock();
        try {
            conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement("DELETE FROM nodes WHERE content_hash = ?")) {
                stmt.setString(1, contentHash);
                stmt.execute();
            }
            try (var stmt = conn.prepareStatement("DELETE FROM edges WHERE content_hash = ?")) {
                stmt.setString(1, contentHash);
                stmt.execute();
            }
            try (var stmt = conn.prepareStatement("DELETE FROM files WHERE content_hash = ?")) {
                stmt.setString(1, contentHash);
                stmt.execute();
            }
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            log.warn("Failed to remove cached file {}", contentHash, e);
        } finally {
            try {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // best-effort restore; the DELETEs have already been committed or rolled back.
                }
            } finally {
                // Guarantee unlock even if conn.setAutoCommit throws a non-SQLException
                // (RuntimeException / Error). Fixes SpotBugs UL_UNRELEASED_LOCK_EXCEPTION_PATH.
                rwLock.writeLock().unlock();
            }
        }
    }

    // --- Run tracking ---

    /**
     * Record an analysis run with its commit SHA and file count.
     */
    public void recordRun(String commitSha, int fileCount) {
        rwLock.writeLock().lock();
        try (var stmt = conn.prepareStatement(
                "INSERT INTO analysis_runs (run_id, commit_sha, timestamp, file_count) VALUES (?, ?, ?, ?)")) {
            stmt.setString(1, UUID.randomUUID().toString());
            stmt.setString(2, commitSha);
            stmt.setString(3, Instant.now().toString());
            stmt.setInt(4, fileCount);
            stmt.execute();
        } catch (SQLException e) {
            log.warn("Failed to record analysis run", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // --- Statistics ---

    /**
     * Return cache statistics.
     */
    public Map<String, Object> getStats() {
        rwLock.readLock().lock();
        try {
            Map<String, Object> stats = new LinkedHashMap<>();
            try {
                stats.put("cached_files", countFiles());
                stats.put("cached_nodes", countNodesInternal());
                stats.put("cached_edges", countEdges());
                stats.put("total_runs", countAnalysisRuns());
                stats.put("db_path", dbPath.toString());
            } catch (SQLException e) {
                stats.put("error", e.getMessage());
            }
            return stats;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Clear all cached data.
     */
    public void clear() {
        rwLock.writeLock().lock();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM edges");
            stmt.execute("DELETE FROM nodes");
            stmt.execute("DELETE FROM files");
            stmt.execute("DELETE FROM analysis_runs");
        } catch (SQLException e) {
            log.warn("Failed to clear cache", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Replace all cached nodes and edges with the given enriched data.
     * <p>
     * This is used after enrichment (linkers, layer classifier, service detection)
     * to write the enriched graph back to H2 so that {@code serve} picks up the
     * full enriched data without requiring Neo4j.
     * <p>
     * Uses a synthetic content hash ({@code __enriched__}) so the data is stored
     * under a single batch key. Existing nodes and edges are cleared first.
     *
     * @param nodes enriched nodes (including new SERVICE nodes, layer classifications, etc.)
     * @param edges enriched edges (including linker edges, CONTAINS edges, etc.)
     */
    public void replaceAll(List<CodeNode> nodes, List<CodeEdge> edges) {
        rwLock.writeLock().lock();
        try {
            conn.setAutoCommit(false);

            // Clear existing nodes and edges (but preserve analysis_runs metadata)
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM edges");
                stmt.execute("DELETE FROM nodes");
                stmt.execute("DELETE FROM files");
            }

            String now = Instant.now().toString();
            String syntheticHash = "__enriched__";

            // Insert synthetic file record
            try (var stmt = conn.prepareStatement(
                    "MERGE INTO files (content_hash, path, language, parsed_at, status, detection_method) KEY (content_hash) VALUES (?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, syntheticHash);
                stmt.setString(2, "__enriched__");
                stmt.setString(3, "enriched");
                stmt.setString(4, now);
                stmt.setString(5, "ENRICHED");
                stmt.setString(6, "enriched");
                stmt.execute();
            }

            // Batch-insert all nodes
            try (var stmt = conn.prepareStatement(
                    "INSERT INTO nodes (id, content_hash, kind, data) VALUES (?, ?, ?, ?)")) {
                for (CodeNode node : nodes) {
                    stmt.setString(1, node.getId());
                    stmt.setString(2, syntheticHash);
                    stmt.setString(3, node.getKind().getValue());
                    stmt.setString(4, serializeNode(node));
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            // Batch-insert all edges
            try (var stmt = conn.prepareStatement(
                    "INSERT INTO edges (source, target, content_hash, kind, data) VALUES (?, ?, ?, ?, ?)")) {
                for (CodeEdge edge : edges) {
                    stmt.setString(1, edge.getSourceId());
                    stmt.setString(2, edge.getTarget() != null ? edge.getTarget().getId() : "");
                    stmt.setString(3, syntheticHash);
                    stmt.setString(4, edge.getKind().getValue());
                    stmt.setString(5, serializeEdge(edge));
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            conn.commit();
            log.info("Replaced H2 cache with enriched data: {} nodes, {} edges", nodes.size(), edges.size());
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            log.warn("Failed to replace cache with enriched data", e);
        } finally {
            try {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // best-effort restore; the INSERTs have already been committed or rolled back.
                }
            } finally {
                // Guarantee unlock even if conn.setAutoCommit throws a non-SQLException
                // (RuntimeException / Error). Fixes SpotBugs UL_UNRELEASED_LOCK_EXCEPTION_PATH.
                rwLock.writeLock().unlock();
            }
        }
    }

    /**
     * Search file snippets for the given query string.
     *
     * @param query text to search for (case-sensitive substring match)
     * @return list of matching snippet results ordered by path
     */
    public List<SnippetResult> searchSnippets(String query) {
        rwLock.readLock().lock();
        try {
            List<SnippetResult> results = new ArrayList<>();
            try (var stmt = conn.prepareStatement(
                    "SELECT path, language, file_type, snippet FROM files WHERE snippet LIKE ? ORDER BY path")) {
                stmt.setString(1, "%" + query + "%");
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new SnippetResult(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4)));
                    }
                }
            }
            return results;
        } catch (SQLException e) {
            log.debug("Snippet search failed for query '{}'", query, e);
            return List.of();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * A snippet search result.
     */
    public record SnippetResult(String path, String language, String fileType, String snippet) {}

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.debug("Failed to close cache connection", e);
        }
    }

    // --- Serialization helpers ---

    private String serializeNode(CodeNode node) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", node.getId());
        data.put("kind", node.getKind().getValue());
        data.put("label", node.getLabel());
        if (node.getFqn() != null) data.put("fqn", node.getFqn());
        if (node.getModule() != null) data.put("module", node.getModule());
        if (node.getFilePath() != null) data.put("file_path", node.getFilePath());
        if (node.getLineStart() != null) data.put("line_start", node.getLineStart());
        if (node.getLineEnd() != null) data.put("line_end", node.getLineEnd());
        if (node.getLayer() != null) data.put("layer", node.getLayer());
        // Confidence is never null at rest (setter normalizes to LEXICAL); store the
        // enum name. Source is optional and stays null for bare construction.
        data.put("confidence", node.getConfidence().name());
        if (node.getSource() != null) data.put("source", node.getSource());
        if (node.getAnnotations() != null && !node.getAnnotations().isEmpty()) {
            data.put("annotations", node.getAnnotations());
        }
        if (node.getProperties() != null && !node.getProperties().isEmpty()) {
            data.put("properties", node.getProperties());
        }
        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private CodeNode deserializeNode(String json) {
        try {
            Map<String, Object> data = MAPPER.readValue(json, new TypeReference<>() {});
            String kindStr = (String) data.get("kind");
            if (kindStr == null) {
                log.debug("Skipping node with null kind: {}", json);
                return null;
            }
            CodeNode node = new CodeNode();
            node.setId((String) data.get("id"));
            node.setKind(NodeKind.fromValue(kindStr));
            node.setLabel((String) data.get("label"));
            node.setFqn((String) data.get("fqn"));
            node.setModule((String) data.get("module"));
            node.setFilePath((String) data.get("file_path"));
            if (data.get("line_start") instanceof Number n) node.setLineStart(n.intValue());
            if (data.get("line_end") instanceof Number n) node.setLineEnd(n.intValue());
            node.setLayer((String) data.get("layer"));
            // Confidence + source: missing/malformed values fall back to LEXICAL/null
            // — never throw — so legacy cache rows without these fields still load.
            Object confObj = data.get("confidence");
            if (confObj instanceof String confStr) {
                try {
                    node.setConfidence(Confidence.fromString(confStr));
                } catch (IllegalArgumentException ignored) {
                    // keep default LEXICAL
                }
            }
            Object srcObj = data.get("source");
            if (srcObj instanceof String src) {
                node.setSource(src);
            }
            if (data.get("annotations") instanceof List<?> list) {
                node.setAnnotations(list.stream().map(Object::toString).toList());
            }
            if (data.get("properties") instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) map;
                node.setProperties(new LinkedHashMap<>(props));
            }
            return node;
        } catch (Exception e) {
            log.debug("Failed to deserialize node: {}", json, e);
            return null;
        }
    }

    private String serializeEdge(CodeEdge edge) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", edge.getId());
        data.put("kind", edge.getKind().getValue());
        data.put("source_id", edge.getSourceId());
        if (edge.getTarget() != null) {
            data.put("target_id", edge.getTarget().getId());
        }
        // Confidence is never null at rest; source is optional.
        data.put("confidence", edge.getConfidence().name());
        if (edge.getSource() != null) data.put("source", edge.getSource());
        if (edge.getProperties() != null && !edge.getProperties().isEmpty()) {
            data.put("properties", edge.getProperties());
        }
        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private CodeEdge deserializeEdge(String json) {
        try {
            Map<String, Object> data = MAPPER.readValue(json, new TypeReference<>() {});
            String id = (String) data.get("id");
            String kindStr = (String) data.get("kind");
            if (kindStr == null) {
                log.debug("Skipping edge with null kind: {}", json);
                return null;
            }
            String sourceId = (String) data.get("source_id");
            String targetId = (String) data.get("target_id");

            // Create a placeholder target node
            CodeNode target = null;
            if (targetId != null) {
                target = new CodeNode(targetId, NodeKind.CLASS, targetId);
            }

            CodeEdge edge = new CodeEdge(id, EdgeKind.fromValue(kindStr), sourceId, target);
            // Confidence + source: missing/malformed → LEXICAL/null, never throw.
            Object confObj = data.get("confidence");
            if (confObj instanceof String confStr) {
                try {
                    edge.setConfidence(Confidence.fromString(confStr));
                } catch (IllegalArgumentException ignored) {
                    // keep default LEXICAL
                }
            }
            Object srcObj = data.get("source");
            if (srcObj instanceof String src) {
                edge.setSource(src);
            }
            if (data.get("properties") instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) map;
                edge.setProperties(new LinkedHashMap<>(props));
            }
            return edge;
        } catch (Exception e) {
            log.debug("Failed to deserialize edge: {}", json, e);
            return null;
        }
    }

    private long countFiles() throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM files")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countEdges() throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM edges")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countAnalysisRuns() throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM analysis_runs")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Return the total number of cached nodes.
     */
    public long getNodeCount() {
        rwLock.readLock().lock();
        try {
            return countNodesInternal();
        } catch (SQLException e) {
            log.debug("Failed to count nodes", e);
            return 0;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Internal node count -- caller must hold the appropriate lock. */
    private long countNodesInternal() throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT id) FROM nodes")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Return the total number of cached edges.
     */
    public long getEdgeCount() {
        rwLock.readLock().lock();
        try {
            return countEdges();
        } catch (SQLException e) {
            log.debug("Failed to count edges", e);
            return 0;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Store a batch of nodes and edges from the analyzer, keyed by a synthetic batch hash.
     * Used during batched indexing where the Analyzer flushes results per batch
     * rather than per file content hash.
     *
     * @param batchId   unique batch identifier
     * @param filePath  representative file path for the batch
     * @param language  representative language
     * @param nodes     nodes to store
     * @param edges     edges to store
     */
    public void storeBatchResults(String batchId, String filePath, String language,
                                  List<CodeNode> nodes, List<CodeEdge> edges) {
        storeResults(batchId, filePath, language, nodes, edges);
    }

    /**
     * Load all cached nodes across all files.
     *
     * @return list of all cached nodes
     */
    public List<CodeNode> loadAllNodes() {
        rwLock.readLock().lock();
        try {
            List<CodeNode> nodes = new ArrayList<>();
            // Deduplicate by id, keeping the LAST inserted version (most complete data)
            try (var stmt = conn.prepareStatement("""
                    SELECT n.data FROM nodes n
                    INNER JOIN (SELECT id, MAX(row_id) AS max_id FROM nodes GROUP BY id) m
                    ON n.id = m.id AND n.row_id = m.max_id
                    """)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        CodeNode node = deserializeNode(rs.getString(1));
                        if (node != null) nodes.add(node);
                    }
                }
            } catch (SQLException e) {
                log.debug("Failed to load all nodes", e);
            }
            return nodes;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Load all cached edges across all files.
     *
     * @return list of all cached edges
     */
    public List<CodeEdge> loadAllEdges() {
        rwLock.readLock().lock();
        try {
            List<CodeEdge> edges = new ArrayList<>();
            try (var stmt = conn.prepareStatement("SELECT data FROM edges")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        CodeEdge edge = deserializeEdge(rs.getString(1));
                        if (edge != null) edges.add(edge);
                    }
                }
            } catch (SQLException e) {
                log.debug("Failed to load all edges", e);
            }
            return edges;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Cached nodes and edges for a single file.
     */
    public record CachedResult(List<CodeNode> nodes, List<CodeEdge> edges) {}
}
