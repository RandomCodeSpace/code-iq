package io.github.randomcodespace.iq.cache;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggressive H2-cache round-trip coverage for {@link Confidence} and detector
 * source on cached nodes and edges. Verifies that bumping {@code CACHE_VERSION}
 * to 5 actually carries the new fields through both the serialize and
 * deserialize paths, including:
 * <ul>
 *   <li>All three confidence values (LEXICAL/SYNTACTIC/RESOLVED) on nodes and edges</li>
 *   <li>Bare model objects (no confidence explicitly set) round-trip as LEXICAL</li>
 *   <li>Source is optional and stays null on bare objects</li>
 *   <li>Repeated upsert preserves confidence (no silent decay)</li>
 *   <li>{@code CACHE_VERSION} is exactly 5 — guards against accidental rollback</li>
 * </ul>
 */
class AnalysisCacheConfidenceTest {

    private AnalysisCache cache;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        cache = new AnalysisCache(tempDir.resolve("test-cache.db"));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    // ---------- Node round-trips ----------

    @ParameterizedTest
    @EnumSource(Confidence.class)
    void node_allConfidenceValuesRoundTripThroughCache(Confidence value) {
        CodeNode node = new CodeNode("test:cache:" + value.name(), NodeKind.CLASS, "X");
        node.setConfidence(value);
        node.setSource("MyDetector");

        cache.storeResults("h-" + value.name(), "X.java", "java",
                List.of(node), List.of());

        var result = cache.loadCachedResults("h-" + value.name());
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        CodeNode loaded = result.nodes().getFirst();
        assertEquals(value, loaded.getConfidence(),
                "node confidence must round-trip through the H2 cache");
        assertEquals("MyDetector", loaded.getSource());
    }

    @Test
    void node_bareConstructionDefaultsRoundTripAsLexicalAndNullSource() {
        // Bare node — no confidence or source set. Round-trip must yield LEXICAL + null
        // (matches CodeNode field defaults and the "least committal" invariant).
        CodeNode node = new CodeNode("test:bare:Foo", NodeKind.CLASS, "Foo");
        cache.storeResults("h-bare", "Foo.java", "java",
                List.of(node), List.of());

        var result = cache.loadCachedResults("h-bare");
        assertNotNull(result);
        CodeNode loaded = result.nodes().getFirst();
        assertEquals(Confidence.LEXICAL, loaded.getConfidence(),
                "bare node round-trips as LEXICAL — least committal default");
        assertNull(loaded.getSource(),
                "bare node round-trips with null source — no string sentinel");
    }

    @Test
    void node_upsertPreservesConfidenceAndSource() {
        // First write with one confidence/source, then overwrite with a stronger one.
        // Reload must reflect the latest write — no silent decay.
        CodeNode v1 = new CodeNode("test:upsert:Foo", NodeKind.CLASS, "Foo");
        v1.setConfidence(Confidence.LEXICAL);
        v1.setSource("RegexDetector");
        cache.storeResults("h-upsert", "Foo.java", "java", List.of(v1), List.of());

        CodeNode v2 = new CodeNode("test:upsert:Foo:v2", NodeKind.CLASS, "Foo");
        v2.setConfidence(Confidence.RESOLVED);
        v2.setSource("ResolvedDetector");
        cache.storeResults("h-upsert", "Foo.java", "java", List.of(v2), List.of());

        var result = cache.loadCachedResults("h-upsert");
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        CodeNode loaded = result.nodes().getFirst();
        assertEquals(Confidence.RESOLVED, loaded.getConfidence(),
                "upsert must overwrite confidence — never silently keep the older value");
        assertEquals("ResolvedDetector", loaded.getSource());
    }

    @Test
    void node_clearThenStoreReroundtripsConfidence() {
        // Defensive: after a full clear, the next round-trip still works.
        CodeNode pre = new CodeNode("pre:n", NodeKind.CLASS, "Pre");
        pre.setConfidence(Confidence.RESOLVED);
        cache.storeResults("h-pre", "P.java", "java", List.of(pre), List.of());
        cache.clear();
        // Verify clear removed it.
        assertNull(cache.loadCachedResults("h-pre"));

        CodeNode post = new CodeNode("post:n", NodeKind.CLASS, "Post");
        post.setConfidence(Confidence.SYNTACTIC);
        post.setSource("PostClearDetector");
        cache.storeResults("h-post", "P.java", "java", List.of(post), List.of());

        var result = cache.loadCachedResults("h-post");
        assertNotNull(result);
        assertEquals(Confidence.SYNTACTIC, result.nodes().getFirst().getConfidence());
        assertEquals("PostClearDetector", result.nodes().getFirst().getSource());
    }

    // ---------- Edge round-trips ----------

    @ParameterizedTest
    @EnumSource(Confidence.class)
    void edge_allConfidenceValuesRoundTripThroughCache(Confidence value) {
        CodeNode src = new CodeNode("e:src:" + value.name(), NodeKind.CLASS, "Src");
        CodeNode tgt = new CodeNode("e:tgt:" + value.name(), NodeKind.CLASS, "Tgt");
        CodeEdge edge = new CodeEdge("e:edge:" + value.name(), EdgeKind.DEPENDS_ON,
                "e:src:" + value.name(), tgt);
        edge.setConfidence(value);
        edge.setSource("EdgeDetector");

        cache.storeResults("e-" + value.name(), "E.java", "java",
                List.of(src, tgt), List.of(edge));

        var result = cache.loadCachedResults("e-" + value.name());
        assertNotNull(result);
        assertEquals(1, result.edges().size());
        CodeEdge loaded = result.edges().getFirst();
        assertEquals(value, loaded.getConfidence(),
                "edge confidence must round-trip through the H2 cache");
        assertEquals("EdgeDetector", loaded.getSource());
    }

    @Test
    void edge_bareConstructionDefaultsRoundTripAsLexicalAndNullSource() {
        CodeNode src = new CodeNode("e:bare:src", NodeKind.CLASS, "Src");
        CodeNode tgt = new CodeNode("e:bare:tgt", NodeKind.CLASS, "Tgt");
        CodeEdge edge = new CodeEdge("e:bare:edge", EdgeKind.DEPENDS_ON, "e:bare:src", tgt);

        cache.storeResults("e-bare", "E.java", "java",
                List.of(src, tgt), List.of(edge));

        var result = cache.loadCachedResults("e-bare");
        assertNotNull(result);
        CodeEdge loaded = result.edges().getFirst();
        assertEquals(Confidence.LEXICAL, loaded.getConfidence(),
                "bare edge round-trips as LEXICAL");
        assertNull(loaded.getSource(),
                "bare edge round-trips with null source");
    }

    @Test
    void edge_setNullSourceNormalizesToLexicalNotNull() {
        // Edge model setter normalizes null confidence → LEXICAL. Verify cache
        // round-trip preserves this invariant: getConfidence() never returns null.
        CodeNode src = new CodeNode("e:null:src", NodeKind.CLASS, "Src");
        CodeNode tgt = new CodeNode("e:null:tgt", NodeKind.CLASS, "Tgt");
        CodeEdge edge = new CodeEdge("e:null:edge", EdgeKind.DEPENDS_ON, "e:null:src", tgt);
        edge.setConfidence(null); // setter normalizes to LEXICAL
        edge.setSource(null);

        cache.storeResults("e-null", "E.java", "java", List.of(src, tgt), List.of(edge));

        var result = cache.loadCachedResults("e-null");
        assertNotNull(result);
        CodeEdge loaded = result.edges().getFirst();
        assertNotNull(loaded.getConfidence(), "confidence is never null at rest");
        assertEquals(Confidence.LEXICAL, loaded.getConfidence());
    }

    // ---------- Schema invariant ----------

    @Test
    void cacheVersionIsBumpedToFive() throws Exception {
        // Reflection-driven assertion — confidence + source serialization is a
        // breaking change to the JSON shape of cached rows. CACHE_VERSION must be
        // bumped to 5 so existing v4 caches are dropped on next open. Reverting
        // this without re-thinking the schema invalidation is a footgun.
        Field f = AnalysisCache.class.getDeclaredField("CACHE_VERSION");
        f.setAccessible(true);
        int version = (int) f.get(null);
        assertEquals(5, version,
                "CACHE_VERSION must be 5 after the confidence + source schema change");
    }
}
