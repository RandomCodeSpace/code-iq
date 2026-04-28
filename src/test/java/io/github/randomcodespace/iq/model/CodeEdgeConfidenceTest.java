package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodeEdgeConfidenceTest {

    private CodeEdge newEdge() {
        CodeNode target = new CodeNode("node:Bar.java:class:Bar", NodeKind.CLASS, "Bar");
        return new CodeEdge("edge:Foo->Bar:depends_on", EdgeKind.DEPENDS_ON,
                "node:Foo.java:class:Foo", target);
    }

    @Test
    void confidenceDefaultsToLexicalOnFreshEdge() {
        assertEquals(Confidence.LEXICAL, newEdge().getConfidence(),
                "fresh edge defaults to LEXICAL — least committal");
    }

    @Test
    void confidenceCanBeSetAndRead() {
        CodeEdge e = newEdge();
        e.setConfidence(Confidence.RESOLVED);
        assertEquals(Confidence.RESOLVED, e.getConfidence());
    }

    @Test
    void confidenceSetterNormalizesNullToLexical() {
        CodeEdge e = newEdge();
        e.setConfidence(Confidence.RESOLVED);
        e.setConfidence(null);
        assertEquals(Confidence.LEXICAL, e.getConfidence(),
                "null setter falls back to LEXICAL — never null");
    }

    @Test
    void sourceIsNullUntilSet() {
        assertNull(newEdge().getSource(),
                "source defaults to null on the bare constructor; "
                        + "detector base classes stamp it via setSource() during emission");
    }

    @Test
    void sourceCanBeSetAndRead() {
        CodeEdge e = newEdge();
        e.setSource("SpringServiceDetector");
        assertEquals("SpringServiceDetector", e.getSource());
    }

    @Test
    void confidenceAndSourceAreIndependent() {
        CodeEdge e = newEdge();
        e.setConfidence(Confidence.SYNTACTIC);
        e.setSource("JpaEntityDetector");
        assertEquals(Confidence.SYNTACTIC, e.getConfidence());
        assertEquals("JpaEntityDetector", e.getSource());
    }
}
