package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NodeKindConverter} and {@link EdgeKindConverter}.
 * Verifies all write/read paths including null handling.
 */
class KindConverterTest {

    @Nested
    class NodeKindConverterTests {

        private final NodeKindConverter converter = new NodeKindConverter();

        @Test
        void writeProducesLowercaseStringValue() {
            Value v = converter.write(NodeKind.CLASS);
            assertEquals("class", v.asString());
        }

        @Test
        void writeNullThrowsOrProducesNullLikeValue() {
            // Values.value(null) throws IllegalArgumentException in the Neo4j driver.
            // The converter passes null → Values.value(null), so this path throws.
            // Coverage: exercises the null branch of the ternary.
            assertThrows(Exception.class, () -> converter.write(null));
        }

        @Test
        void readReturnsCorrectKind() {
            Value v = Values.value("endpoint");
            assertEquals(NodeKind.ENDPOINT, converter.read(v));
        }

        @Test
        void readNullNeo4jValueReturnsNull() {
            assertNull(converter.read(Values.NULL));
        }

        @Test
        void readNullReferenceReturnsNull() {
            assertNull(converter.read(null));
        }

        @Test
        void writeReadRoundTripForAllKinds() {
            for (NodeKind kind : NodeKind.values()) {
                Value written = converter.write(kind);
                NodeKind readBack = converter.read(written);
                assertEquals(kind, readBack, "Round-trip failed for " + kind.name());
            }
        }

        @Test
        void specificKindValues() {
            assertEquals("module", converter.write(NodeKind.MODULE).asString());
            assertEquals("entity", converter.write(NodeKind.ENTITY).asString());
            assertEquals("service", converter.write(NodeKind.SERVICE).asString());
            assertEquals("topic", converter.write(NodeKind.TOPIC).asString());
            assertEquals("config_key", converter.write(NodeKind.CONFIG_KEY).asString());
        }
    }

    @Nested
    class EdgeKindConverterTests {

        private final EdgeKindConverter converter = new EdgeKindConverter();

        @Test
        void writeProducesLowercaseStringValue() {
            Value v = converter.write(EdgeKind.DEPENDS_ON);
            assertEquals("depends_on", v.asString());
        }

        @Test
        void writeNullThrowsOrProducesNullLikeValue() {
            // Values.value(null) throws IllegalArgumentException in the Neo4j driver.
            // The converter passes null → Values.value(null), so this path throws.
            assertThrows(Exception.class, () -> converter.write(null));
        }

        @Test
        void readReturnsCorrectKind() {
            Value v = Values.value("calls");
            assertEquals(EdgeKind.CALLS, converter.read(v));
        }

        @Test
        void readNullNeo4jValueReturnsNull() {
            assertNull(converter.read(Values.NULL));
        }

        @Test
        void readNullReferenceReturnsNull() {
            assertNull(converter.read(null));
        }

        @Test
        void writeReadRoundTripForAllKinds() {
            for (EdgeKind kind : EdgeKind.values()) {
                Value written = converter.write(kind);
                EdgeKind readBack = converter.read(written);
                assertEquals(kind, readBack, "Round-trip failed for " + kind.name());
            }
        }

        @Test
        void specificEdgeKindValues() {
            assertEquals("imports", converter.write(EdgeKind.IMPORTS).asString());
            assertEquals("contains", converter.write(EdgeKind.CONTAINS).asString());
            assertEquals("protects", converter.write(EdgeKind.PROTECTS).asString());
        }
    }
}
