package io.github.randomcodespace.iq.config.unified;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedConfigLoaderTest {

    private static Path fixture(String name) {
        return Paths.get("src/test/resources/config-unified/" + name);
    }

    @Test
    void missingFileProducesEmptyOverlay() {
        CodeIqUnifiedConfig cfg = UnifiedConfigLoader.load(Paths.get("does/not/exist.yml"));
        // Empty overlay = every section present with null/default-empty values.
        assertEquals(CodeIqUnifiedConfig.empty(), cfg);
    }

    @Test
    void minimalFileSetsOnlyDeclaredFields() {
        // minimal.yml deliberately uses the deprecated camelCase alias (batchSize)
        // so this test also exercises the alias path end-to-end.
        CodeIqUnifiedConfig cfg = UnifiedConfigLoader.load(fixture("minimal.yml"));
        assertEquals("my-service", cfg.project().name());
        assertEquals(2000, cfg.indexing().batchSize());
        // Unset fields stay null (indicating "inherit from lower layer")
        assertNull(cfg.indexing().cacheDir());
        assertNull(cfg.serving().port());
    }

    @Test
    void fullFileRoundTripsEveryField() {
        CodeIqUnifiedConfig cfg = UnifiedConfigLoader.load(fixture("full.yml"));
        assertEquals("demo", cfg.project().name());
        assertEquals(2, cfg.project().modules().size());
        assertEquals("services/api", cfg.project().modules().get(0).path());
        assertEquals("maven", cfg.project().modules().get(0).type());
        assertEquals(9090, cfg.serving().port());
        assertEquals("127.0.0.1", cfg.serving().bindAddress());
        assertEquals(true, cfg.serving().readOnly());
        assertEquals(".code-iq/graph/graph.db", cfg.serving().neo4j().dir());
        assertEquals(2048, cfg.serving().neo4j().heapMaxMb());
        assertEquals(10000, cfg.mcp().limits().perToolTimeoutMs());
        assertEquals(List.of("run_cypher"), cfg.mcp().tools().disabled());
        assertEquals(Boolean.TRUE, cfg.detectors().overrides().get("SpringRestDetector").enabled());
        assertEquals(Boolean.FALSE, cfg.detectors().overrides().get("QuarkusRestDetector").enabled());
    }

    @Test
    void malformedFileThrowsWithFileAnchor() {
        Path f = fixture("malformed.yml");
        ConfigLoadException e = assertThrows(ConfigLoadException.class,
                () -> UnifiedConfigLoader.load(f));
        assertTrue(e.getMessage().contains("malformed.yml"),
                "error must name the file, got: " + e.getMessage());
        // Canonical field path uses snake_case; legacy camelCase substring lives on
        // only as a transparent alias and does NOT appear in error messages.
        assertTrue(e.getMessage().contains("batch_size"),
                "error must name the canonical snake_case field, got: " + e.getMessage());
    }

    // ---- Casing-normalization (Task 13 prep) ---------------------------------

    @Test
    void snakeCaseKeysAreLoadedWithoutWarning(@TempDir Path tmp) throws Exception {
        // The canonical spelling is snake_case for every leaf. Loading a fully
        // snake_cased YAML must (a) populate the record fields and (b) emit ZERO
        // deprecation warnings.
        Path yml = tmp.resolve("codeiq.yml");
        Files.writeString(yml,
                "indexing:\n  batch_size: 123\n  cache_dir: .cache\n"
              + "serving:\n  bind_address: 0.0.0.0\n  read_only: true\n"
              + "  neo4j:\n    page_cache_mb: 64\n    heap_initial_mb: 128\n    heap_max_mb: 256\n"
              + "mcp:\n  base_path: /mcp\n"
              + "  auth:\n    token_env: FOO\n"
              + "  limits:\n    per_tool_timeout_ms: 500\n    max_results: 10\n"
              + "    max_payload_bytes: 1000\n    rate_per_minute: 30\n"
              + "observability:\n  log_format: json\n  log_level: info\n");

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            CodeIqUnifiedConfig cfg = UnifiedConfigLoader.load(yml);

            assertEquals(123, cfg.indexing().batchSize());
            assertEquals(".cache", cfg.indexing().cacheDir());
            assertEquals("0.0.0.0", cfg.serving().bindAddress());
            assertEquals(Boolean.TRUE, cfg.serving().readOnly());
            assertEquals(64, cfg.serving().neo4j().pageCacheMb());
            assertEquals(128, cfg.serving().neo4j().heapInitialMb());
            assertEquals(256, cfg.serving().neo4j().heapMaxMb());
            assertEquals("/mcp", cfg.mcp().basePath());
            assertEquals("FOO", cfg.mcp().auth().tokenEnv());
            assertEquals(500, cfg.mcp().limits().perToolTimeoutMs());
            assertEquals(10, cfg.mcp().limits().maxResults());
            assertEquals(1000L, cfg.mcp().limits().maxPayloadBytes());
            assertEquals(30, cfg.mcp().limits().ratePerMinute());
            assertEquals("json", cfg.observability().logFormat());
            assertEquals("info", cfg.observability().logLevel());

            long warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .count();
            assertEquals(0, warnings,
                    "snake_case keys must not trigger deprecation warnings, got: "
                            + appender.list.stream()
                                    .map(Object::toString)
                                    .collect(Collectors.joining("\n")));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void camelCaseAliasIsAcceptedAndWarns(@TempDir Path tmp) throws Exception {
        // camelCase must still load correctly (backward compatibility), but each
        // alias used must produce exactly one WARN naming the canonical form.
        Path yml = tmp.resolve("codeiq.yml");
        Files.writeString(yml,
                "indexing:\n  batchSize: 777\n  cacheDir: /tmp/c\n"
              + "serving:\n  bindAddress: 1.2.3.4\n  readOnly: false\n"
              + "observability:\n  logFormat: text\n  logLevel: warn\n");

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            CodeIqUnifiedConfig cfg = UnifiedConfigLoader.load(yml);

            // Values from the alias keys must flow into the record.
            assertEquals(777, cfg.indexing().batchSize());
            assertEquals("/tmp/c", cfg.indexing().cacheDir());
            assertEquals("1.2.3.4", cfg.serving().bindAddress());
            assertEquals(Boolean.FALSE, cfg.serving().readOnly());
            assertEquals("text", cfg.observability().logFormat());
            assertEquals("warn", cfg.observability().logLevel());

            // Exactly one WARN per alias used.
            assertWarnsExactlyFor(appender,
                    "indexing.batchSize", "indexing.cacheDir",
                    "serving.bindAddress", "serving.readOnly",
                    "observability.logFormat", "observability.logLevel");
            for (ILoggingEvent w : warnsOnly(appender)) {
                String msg = w.getFormattedMessage();
                assertTrue(msg.contains("deprecated"),
                        "alias WARN must flag the key as deprecated, got: " + msg);
            }
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void whenBothSnakeAndCamelCaseSetSnakeCaseWins(@TempDir Path tmp) throws Exception {
        // Conflict: both canonical snake_case and deprecated camelCase present
        // for the same leaf. snake_case must win; a single WARN must flag the
        // conflict.
        Path yml = tmp.resolve("codeiq.yml");
        Files.writeString(yml,
                "indexing:\n  batch_size: 100\n  batchSize: 999\n");

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            CodeIqUnifiedConfig cfg = UnifiedConfigLoader.load(yml);
            assertEquals(100, cfg.indexing().batchSize(),
                    "snake_case must win when both forms are set");
            List<ILoggingEvent> warns = warnsOnly(appender);
            assertEquals(1, warns.size(),
                    "exactly one WARN for the conflict; got: " + warns);
            String msg = warns.get(0).getFormattedMessage();
            assertTrue(msg.contains("indexing.batchSize"),
                    "WARN must name the deprecated alias, got: " + msg);
            assertTrue(msg.contains("indexing.batch_size"),
                    "WARN must name the canonical key, got: " + msg);
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void aliasWarnIsDedupedPerFile(@TempDir Path tmp) throws Exception {
        // A single load() call must emit at most ONE WARN per alias even if the
        // same deprecated key appears on multiple leaves. (Here the load touches
        // two distinct camelCase leaves -- each produces exactly one WARN;
        // reloading the same file produces two more -- the dedupe is per load,
        // not global.)
        Path yml = tmp.resolve("codeiq.yml");
        Files.writeString(yml,
                "indexing:\n  batchSize: 1\n"
              + "mcp:\n  limits:\n    perToolTimeoutMs: 2\n    maxResults: 3\n");

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            UnifiedConfigLoader.load(yml);
            List<ILoggingEvent> warns = warnsOnly(appender);
            assertEquals(3, warns.size(),
                    "one WARN per distinct alias (3 here), got: " + warns);

            // Second load of the same file: another 3 WARNs, NOT cumulative
            // (dedupe is scoped per-load).
            UnifiedConfigLoader.load(yml);
            assertEquals(6, warnsOnly(appender).size(),
                    "per-load dedupe means a fresh load re-warns");
        } finally {
            detachAppender(appender);
        }
    }

    // ---- helpers --------------------------------------------------------------

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(UnifiedConfigLoader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(UnifiedConfigLoader.class);
        logger.detachAppender(appender);
    }

    private static List<ILoggingEvent> warnsOnly(ListAppender<ILoggingEvent> a) {
        return a.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static void assertWarnsExactlyFor(ListAppender<ILoggingEvent> a, String... aliases) {
        List<ILoggingEvent> warns = warnsOnly(a);
        assertEquals(aliases.length, warns.size(),
                "expected one WARN per alias. aliases=" + List.of(aliases) + "; warns=" + warns);
        for (String alias : aliases) {
            boolean found = warns.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains(alias));
            assertTrue(found, "expected a WARN mentioning alias '" + alias
                    + "', got: " + warns);
        }
    }
}
