package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodeNodeConfidenceTest {

    @Test
    void confidenceDefaultsToLexicalOnFreshNode() {
        CodeNode n = new CodeNode("node:Foo.java:class:Foo", NodeKind.CLASS, "Foo");
        assertEquals(Confidence.LEXICAL, n.getConfidence(),
                "fresh node defaults to LEXICAL — least committal");
    }

    @Test
    void confidenceCanBeSetAndRead() {
        CodeNode n = new CodeNode("node:Foo.java:class:Foo", NodeKind.CLASS, "Foo");
        n.setConfidence(Confidence.RESOLVED);
        assertEquals(Confidence.RESOLVED, n.getConfidence());
    }

    @Test
    void confidenceSetterNormalizesNullToLexical() {
        CodeNode n = new CodeNode("node:Foo.java:class:Foo", NodeKind.CLASS, "Foo");
        n.setConfidence(Confidence.RESOLVED);
        n.setConfidence(null);
        assertEquals(Confidence.LEXICAL, n.getConfidence(),
                "null setter falls back to LEXICAL — never null");
    }

    @Test
    void sourceIsNullUntilSet() {
        CodeNode n = new CodeNode("node:Foo.java:class:Foo", NodeKind.CLASS, "Foo");
        assertNull(n.getSource(),
                "source defaults to null on the bare constructor; "
                        + "detector base classes stamp it via setSource() during emission");
    }

    @Test
    void sourceCanBeSetAndRead() {
        CodeNode n = new CodeNode("node:Foo.java:class:Foo", NodeKind.CLASS, "Foo");
        n.setSource("SpringServiceDetector");
        assertEquals("SpringServiceDetector", n.getSource());
    }

    @Test
    void confidenceAndSourceAreIndependent() {
        CodeNode n = new CodeNode("node:Foo.java:class:Foo", NodeKind.CLASS, "Foo");
        n.setConfidence(Confidence.SYNTACTIC);
        n.setSource("JpaEntityDetector");
        assertEquals(Confidence.SYNTACTIC, n.getConfidence());
        assertEquals("JpaEntityDetector", n.getSource());
    }
}
