package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfidenceTest {

    @Test
    void scoreMappingIsStable() {
        assertEquals(0.6, Confidence.LEXICAL.score(), 1e-9);
        assertEquals(0.8, Confidence.SYNTACTIC.score(), 1e-9);
        assertEquals(0.95, Confidence.RESOLVED.score(), 1e-9);
    }

    @Test
    void naturalOrderingMatchesScore() {
        assertTrue(Confidence.LEXICAL.compareTo(Confidence.SYNTACTIC) < 0);
        assertTrue(Confidence.SYNTACTIC.compareTo(Confidence.RESOLVED) < 0);
    }

    @Test
    void fromStringNullIsRejected() {
        assertThrows(NullPointerException.class, () -> Confidence.fromString(null));
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertEquals(Confidence.RESOLVED, Confidence.fromString("resolved"));
        assertEquals(Confidence.RESOLVED, Confidence.fromString("RESOLVED"));
        assertEquals(Confidence.LEXICAL, Confidence.fromString("LeXiCaL"));
    }

    @Test
    void fromStringRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> Confidence.fromString("perfect"));
    }
}
