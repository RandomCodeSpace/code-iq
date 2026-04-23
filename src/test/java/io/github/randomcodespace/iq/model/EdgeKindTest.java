package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EdgeKindTest {

    @Test
    void shouldHave28Values() {
        assertEquals(28, EdgeKind.values().length,
                "EdgeKind must have exactly 28 types (includes REFERENCES_TABLE)");
    }

    @Test
    void shouldReturnCorrectValue() {
        assertEquals("depends_on", EdgeKind.DEPENDS_ON.getValue());
        assertEquals("invokes_rmi", EdgeKind.INVOKES_RMI.getValue());
        assertEquals("reads_config", EdgeKind.READS_CONFIG.getValue());
        assertEquals("receives_from", EdgeKind.RECEIVES_FROM.getValue());
    }

    @Test
    void shouldLookUpFromValue() {
        assertEquals(EdgeKind.DEPENDS_ON, EdgeKind.fromValue("depends_on"));
        assertEquals(EdgeKind.RENDERS, EdgeKind.fromValue("renders"));
        assertEquals(EdgeKind.PROTECTS, EdgeKind.fromValue("protects"));
    }

    @Test
    void shouldThrowOnUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> EdgeKind.fromValue("nonexistent"));
    }

    @Test
    void shouldRoundTripAllValues() {
        for (EdgeKind kind : EdgeKind.values()) {
            assertEquals(kind, EdgeKind.fromValue(kind.getValue()),
                    "Round-trip failed for " + kind.name());
        }
    }
}
