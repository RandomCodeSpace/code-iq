package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.detector.jvm.java.AbstractJavaMessagingDetector;
import io.github.randomcodespace.iq.detector.jvm.java.AbstractJavaParserDetector;
import io.github.randomcodespace.iq.detector.python.AbstractPythonAntlrDetector;
import io.github.randomcodespace.iq.detector.python.AbstractPythonDbDetector;
import io.github.randomcodespace.iq.detector.typescript.AbstractTypeScriptDetector;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggressive coverage for {@link Detector#defaultConfidence()} on every base
 * class, plus the orchestrator stamping pass in {@link DetectorEmissionDefaults}.
 *
 * <p>Verifies the contract that lets us migrate detectors incrementally:
 * <ul>
 *   <li>Each base class declares (or inherits) the right confidence floor.</li>
 *   <li>The stamping pass writes source + confidence ONLY when source is null
 *       (the "detector didn't think about it" sentinel).</li>
 *   <li>Explicitly-stamped emissions survive a stamping pass unchanged.</li>
 *   <li>Mixed results (some explicit, some default) get the right treatment
 *       on a per-emission basis.</li>
 * </ul>
 */
class DetectorEmissionDefaultsTest {

    // ---------- Per-base default confidence ----------

    static Stream<Arguments> baseClassDefaults() {
        return Stream.of(
                Arguments.of("interface default (LEXICAL)", new InterfaceOnlyDetector(), Confidence.LEXICAL),
                Arguments.of("AbstractRegexDetector → LEXICAL", new RegexStub(), Confidence.LEXICAL),
                Arguments.of("AbstractAntlrDetector → SYNTACTIC", new AntlrStub(), Confidence.SYNTACTIC),
                Arguments.of("AbstractStructuredDetector → SYNTACTIC", new StructuredStub(), Confidence.SYNTACTIC),
                Arguments.of("AbstractJavaParserDetector → SYNTACTIC", new JavaParserStub(), Confidence.SYNTACTIC),
                Arguments.of("AbstractJavaMessagingDetector → SYNTACTIC", new JavaMessagingStub(), Confidence.SYNTACTIC),
                Arguments.of("AbstractTypeScriptDetector inherits SYNTACTIC", new TypeScriptStub(), Confidence.SYNTACTIC),
                Arguments.of("AbstractPythonAntlrDetector inherits SYNTACTIC", new PythonAntlrStub(), Confidence.SYNTACTIC),
                Arguments.of("AbstractPythonDbDetector inherits SYNTACTIC", new PythonDbStub(), Confidence.SYNTACTIC)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("baseClassDefaults")
    void defaultConfidencePerBaseClass(String label, Detector detector, Confidence expected) {
        assertEquals(expected, detector.defaultConfidence(), label);
    }

    // ---------- Stamping behavior ----------

    @Test
    void applyDefaults_stampsSourceAndConfidenceOnNullSourceNode() {
        CodeNode node = new CodeNode("n:1", NodeKind.CLASS, "Foo");
        // Bare construction — source is null, confidence is the model default LEXICAL.
        DetectorResult result = DetectorResult.of(new ArrayList<>(List.of(node)), new ArrayList<>());

        DetectorEmissionDefaults.applyDefaults(result, new AntlrStub());

        assertEquals("AntlrStub", node.getSource(), "source stamped to detector class simple name");
        assertEquals(Confidence.SYNTACTIC, node.getConfidence(),
                "confidence bumped to base default (SYNTACTIC for AntlrStub)");
    }

    @Test
    void applyDefaults_stampsSourceAndConfidenceOnNullSourceEdge() {
        CodeNode tgt = new CodeNode("n:tgt", NodeKind.CLASS, "Tgt");
        CodeEdge edge = new CodeEdge("e:1", EdgeKind.DEPENDS_ON, "n:src", tgt);
        DetectorResult result = DetectorResult.of(new ArrayList<>(), new ArrayList<>(List.of(edge)));

        DetectorEmissionDefaults.applyDefaults(result, new RegexStub());

        assertEquals("RegexStub", edge.getSource());
        assertEquals(Confidence.LEXICAL, edge.getConfidence(),
                "regex base default is LEXICAL");
    }

    @Test
    void applyDefaults_leavesExplicitlyStampedNodeAlone() {
        // Detector explicitly stamped — stamping pass must not clobber.
        CodeNode node = new CodeNode("n:explicit", NodeKind.CLASS, "Foo");
        node.setSource("CustomResolverDetector");
        node.setConfidence(Confidence.RESOLVED);
        DetectorResult result = DetectorResult.of(new ArrayList<>(List.of(node)), new ArrayList<>());

        DetectorEmissionDefaults.applyDefaults(result, new AntlrStub());

        assertEquals("CustomResolverDetector", node.getSource(),
                "explicit source survives stamping pass");
        assertEquals(Confidence.RESOLVED, node.getConfidence(),
                "explicit confidence survives stamping pass — not down-graded to base default");
    }

    @Test
    void applyDefaults_leavesExplicitlyStampedEdgeAlone() {
        CodeNode tgt = new CodeNode("n:tgt", NodeKind.CLASS, "Tgt");
        CodeEdge edge = new CodeEdge("e:explicit", EdgeKind.DEPENDS_ON, "n:src", tgt);
        edge.setSource("ExplicitDetector");
        edge.setConfidence(Confidence.RESOLVED);
        DetectorResult result = DetectorResult.of(new ArrayList<>(), new ArrayList<>(List.of(edge)));

        DetectorEmissionDefaults.applyDefaults(result, new RegexStub());

        assertEquals("ExplicitDetector", edge.getSource());
        assertEquals(Confidence.RESOLVED, edge.getConfidence());
    }

    @Test
    void applyDefaults_mixedExplicitAndDefaultsHandledIndependently() {
        // One node was explicitly stamped, another wasn't. Verify the pass is
        // per-emission, not all-or-nothing.
        CodeNode explicit = new CodeNode("n:explicit", NodeKind.CLASS, "Explicit");
        explicit.setSource("ResolverDetector");
        explicit.setConfidence(Confidence.RESOLVED);

        CodeNode bare = new CodeNode("n:bare", NodeKind.CLASS, "Bare");

        DetectorResult result = DetectorResult.of(
                new ArrayList<>(List.of(explicit, bare)),
                new ArrayList<>());

        DetectorEmissionDefaults.applyDefaults(result, new StructuredStub());

        // Explicit untouched
        assertEquals("ResolverDetector", explicit.getSource());
        assertEquals(Confidence.RESOLVED, explicit.getConfidence());
        // Bare stamped
        assertEquals("StructuredStub", bare.getSource());
        assertEquals(Confidence.SYNTACTIC, bare.getConfidence());
    }

    @Test
    void applyDefaults_nullResultIsNoOp() {
        // Defensive: callers may pass null on early returns. Must not NPE.
        assertDoesNotThrow(() -> DetectorEmissionDefaults.applyDefaults(null, new RegexStub()));
    }

    @Test
    void applyDefaults_nullDetectorIsNoOp() {
        // Defensive: the orchestrator should never pass null but the helper
        // is the single trust boundary — must not NPE.
        CodeNode node = new CodeNode("n:1", NodeKind.CLASS, "Foo");
        DetectorResult result = DetectorResult.of(new ArrayList<>(List.of(node)), new ArrayList<>());
        assertDoesNotThrow(() -> DetectorEmissionDefaults.applyDefaults(result, null));
        // Model state is untouched
        assertNull(node.getSource());
    }

    @Test
    void applyDefaults_emptyResultIsNoOp() {
        DetectorResult result = DetectorResult.empty();
        assertDoesNotThrow(() -> DetectorEmissionDefaults.applyDefaults(result, new RegexStub()));
        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void applyDefaults_idempotentOnRepeatCall() {
        // After the first stamp, the detector "owns" these emissions. A second
        // stamping pass with the SAME detector is a no-op (source is no longer null).
        CodeNode node = new CodeNode("n:idem", NodeKind.CLASS, "Foo");
        DetectorResult result = DetectorResult.of(new ArrayList<>(List.of(node)), new ArrayList<>());
        Detector detector = new AntlrStub();

        DetectorEmissionDefaults.applyDefaults(result, detector);
        String firstSource = node.getSource();
        Confidence firstConfidence = node.getConfidence();

        DetectorEmissionDefaults.applyDefaults(result, detector);

        assertEquals(firstSource, node.getSource());
        assertEquals(firstConfidence, node.getConfidence());
    }

    @Test
    void applyDefaults_secondPassWithDifferentDetectorIsAlsoNoOp() {
        // After first stamp, source is set — a different detector running over
        // the same result must NOT relabel the node. (This guards against pipeline
        // reorder bugs where two detectors emit the same node.)
        CodeNode node = new CodeNode("n:multi", NodeKind.CLASS, "Foo");
        DetectorResult result = DetectorResult.of(new ArrayList<>(List.of(node)), new ArrayList<>());

        DetectorEmissionDefaults.applyDefaults(result, new AntlrStub());
        DetectorEmissionDefaults.applyDefaults(result, new RegexStub()); // different detector

        assertEquals("AntlrStub", node.getSource(),
                "first detector's stamp wins — second pass is no-op");
        assertEquals(Confidence.SYNTACTIC, node.getConfidence());
    }

    // ---------- Test-only stub detectors ----------

    /** Bare interface implementation — uses the interface's default LEXICAL. */
    private static final class InterfaceOnlyDetector implements Detector {
        @Override public String getName() { return "iface_stub"; }
        @Override public Set<String> getSupportedLanguages() { return Set.of("test"); }
        @Override public DetectorResult detect(DetectorContext ctx) { return DetectorResult.empty(); }
    }

    private static final class RegexStub extends AbstractRegexDetector {
        @Override public String getName() { return "regex_stub"; }
        @Override public Set<String> getSupportedLanguages() { return Set.of("test"); }
        @Override public DetectorResult detect(DetectorContext ctx) { return DetectorResult.empty(); }
    }

    private static final class AntlrStub extends AbstractAntlrDetector {
        @Override public String getName() { return "antlr_stub"; }
        @Override public Set<String> getSupportedLanguages() { return Set.of("test"); }
    }

    private static final class StructuredStub extends AbstractStructuredDetector {
        @Override public String getName() { return "structured_stub"; }
        @Override public Set<String> getSupportedLanguages() { return Set.of("yaml"); }
        @Override public DetectorResult detect(DetectorContext ctx) { return DetectorResult.empty(); }
    }

    private static final class JavaParserStub extends AbstractJavaParserDetector {
        @Override public String getName() { return "javaparser_stub"; }
        @Override public Set<String> getSupportedLanguages() { return Set.of("java"); }
        @Override public DetectorResult detect(DetectorContext ctx) { return DetectorResult.empty(); }
    }

    private static final class JavaMessagingStub extends AbstractJavaMessagingDetector {
        @Override public String getName() { return "messaging_stub"; }
        @Override public Set<String> getSupportedLanguages() { return Set.of("java"); }
        @Override public DetectorResult detect(DetectorContext ctx) { return DetectorResult.empty(); }
    }

    private static final class TypeScriptStub extends AbstractTypeScriptDetector {
        @Override public String getName() { return "ts_stub"; }
    }

    private static final class PythonAntlrStub extends AbstractPythonAntlrDetector {
        @Override public String getName() { return "python_antlr_stub"; }
    }

    private static final class PythonDbStub extends AbstractPythonDbDetector {
        @Override public String getName() { return "python_db_stub"; }
    }
}
