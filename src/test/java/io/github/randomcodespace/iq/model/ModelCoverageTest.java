package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive coverage tests for model classes:
 * CodeNode, CodeEdge, NodeKind, EdgeKind, NodeKindConverter, EdgeKindConverter.
 */
class ModelCoverageTest {

    // ==================== NodeKind ====================
    @Nested
    class NodeKindCoverage {

        @Test
        void allEnumValuesHaveNonNullValue() {
            for (NodeKind kind : NodeKind.values()) {
                assertNotNull(kind.getValue(), "Value for " + kind.name() + " must not be null");
                assertFalse(kind.getValue().isEmpty(), "Value for " + kind.name() + " must not be empty");
            }
        }

        @Test
        void allValuesAreLowercase() {
            for (NodeKind kind : NodeKind.values()) {
                assertEquals(kind.getValue().toLowerCase(), kind.getValue(),
                        kind.name() + " value should be lowercase");
            }
        }

        @Test
        void fromValueRoundTripsAll() {
            for (NodeKind kind : NodeKind.values()) {
                assertEquals(kind, NodeKind.fromValue(kind.getValue()),
                        "fromValue round-trip failed for " + kind.name());
            }
        }

        @Test
        void fromValueThrowsOnNull() {
            assertThrows(Exception.class, () -> NodeKind.fromValue(null));
        }

        @Test
        void fromValueThrowsOnUnknown() {
            assertThrows(IllegalArgumentException.class, () -> NodeKind.fromValue("completely_unknown_kind"));
        }

        @Test
        void specificKindValues() {
            assertEquals("module", NodeKind.MODULE.getValue());
            assertEquals("class", NodeKind.CLASS.getValue());
            assertEquals("endpoint", NodeKind.ENDPOINT.getValue());
            assertEquals("entity", NodeKind.ENTITY.getValue());
            assertEquals("repository", NodeKind.REPOSITORY.getValue());
            assertEquals("topic", NodeKind.TOPIC.getValue());
            assertEquals("queue", NodeKind.QUEUE.getValue());
            assertEquals("event", NodeKind.EVENT.getValue());
            assertEquals("service", NodeKind.SERVICE.getValue());
            assertEquals("guard", NodeKind.GUARD.getValue());
            assertEquals("hook", NodeKind.HOOK.getValue());
            assertEquals("middleware", NodeKind.MIDDLEWARE.getValue());
            assertEquals("component", NodeKind.COMPONENT.getValue());
        }

        @Test
        void has32Values() {
            assertEquals(32, NodeKind.values().length);
        }

        @Test
        void fromValueSpecificCases() {
            assertEquals(NodeKind.WEBSOCKET_ENDPOINT, NodeKind.fromValue("websocket_endpoint"));
            assertEquals(NodeKind.DATABASE_CONNECTION, NodeKind.fromValue("database_connection"));
            assertEquals(NodeKind.ABSTRACT_CLASS, NodeKind.fromValue("abstract_class"));
            assertEquals(NodeKind.ANNOTATION_TYPE, NodeKind.fromValue("annotation_type"));
            assertEquals(NodeKind.PROTOCOL_MESSAGE, NodeKind.fromValue("protocol_message"));
            assertEquals(NodeKind.CONFIG_DEFINITION, NodeKind.fromValue("config_definition"));
            assertEquals(NodeKind.RMI_INTERFACE, NodeKind.fromValue("rmi_interface"));
            assertEquals(NodeKind.AZURE_RESOURCE, NodeKind.fromValue("azure_resource"));
            assertEquals(NodeKind.AZURE_FUNCTION, NodeKind.fromValue("azure_function"));
            assertEquals(NodeKind.MESSAGE_QUEUE, NodeKind.fromValue("message_queue"));
            assertEquals(NodeKind.INFRA_RESOURCE, NodeKind.fromValue("infra_resource"));
        }
    }

    // ==================== EdgeKind ====================
    @Nested
    class EdgeKindCoverage {

        @Test
        void allEnumValuesHaveNonNullValue() {
            for (EdgeKind kind : EdgeKind.values()) {
                assertNotNull(kind.getValue(), "Value for " + kind.name() + " must not be null");
                assertFalse(kind.getValue().isEmpty(), "Value for " + kind.name() + " must not be empty");
            }
        }

        @Test
        void allValuesAreLowercase() {
            for (EdgeKind kind : EdgeKind.values()) {
                assertEquals(kind.getValue().toLowerCase(), kind.getValue(),
                        kind.name() + " value should be lowercase");
            }
        }

        @Test
        void fromValueRoundTripsAll() {
            for (EdgeKind kind : EdgeKind.values()) {
                assertEquals(kind, EdgeKind.fromValue(kind.getValue()),
                        "fromValue round-trip failed for " + kind.name());
            }
        }

        @Test
        void fromValueThrowsOnNull() {
            assertThrows(Exception.class, () -> EdgeKind.fromValue(null));
        }

        @Test
        void fromValueThrowsOnUnknown() {
            assertThrows(IllegalArgumentException.class, () -> EdgeKind.fromValue("completely_unknown_edge"));
        }

        @Test
        void specificEdgeKindValues() {
            assertEquals("depends_on", EdgeKind.DEPENDS_ON.getValue());
            assertEquals("imports", EdgeKind.IMPORTS.getValue());
            assertEquals("extends", EdgeKind.EXTENDS.getValue());
            assertEquals("implements", EdgeKind.IMPLEMENTS.getValue());
            assertEquals("calls", EdgeKind.CALLS.getValue());
            assertEquals("injects", EdgeKind.INJECTS.getValue());
            assertEquals("exposes", EdgeKind.EXPOSES.getValue());
            assertEquals("queries", EdgeKind.QUERIES.getValue());
            assertEquals("maps_to", EdgeKind.MAPS_TO.getValue());
            assertEquals("produces", EdgeKind.PRODUCES.getValue());
            assertEquals("consumes", EdgeKind.CONSUMES.getValue());
            assertEquals("publishes", EdgeKind.PUBLISHES.getValue());
            assertEquals("listens", EdgeKind.LISTENS.getValue());
            assertEquals("invokes_rmi", EdgeKind.INVOKES_RMI.getValue());
            assertEquals("exports_rmi", EdgeKind.EXPORTS_RMI.getValue());
            assertEquals("reads_config", EdgeKind.READS_CONFIG.getValue());
            assertEquals("migrates", EdgeKind.MIGRATES.getValue());
            assertEquals("contains", EdgeKind.CONTAINS.getValue());
            assertEquals("defines", EdgeKind.DEFINES.getValue());
            assertEquals("overrides", EdgeKind.OVERRIDES.getValue());
            assertEquals("connects_to", EdgeKind.CONNECTS_TO.getValue());
            assertEquals("triggers", EdgeKind.TRIGGERS.getValue());
            assertEquals("provisions", EdgeKind.PROVISIONS.getValue());
            assertEquals("sends_to", EdgeKind.SENDS_TO.getValue());
            assertEquals("receives_from", EdgeKind.RECEIVES_FROM.getValue());
            assertEquals("protects", EdgeKind.PROTECTS.getValue());
            assertEquals("renders", EdgeKind.RENDERS.getValue());
        }

        @Test
        void has27Values() {
            assertEquals(27, EdgeKind.values().length);
        }
    }

    // ==================== CodeNode — comprehensive ====================
    @Nested
    class CodeNodeCoverage {

        @Test
        void defaultConstructorInitializesCollections() {
            var node = new CodeNode();
            assertNotNull(node.getAnnotations());
            assertNotNull(node.getProperties());
            assertNotNull(node.getEdges());
            assertTrue(node.getAnnotations().isEmpty());
            assertTrue(node.getProperties().isEmpty());
            assertTrue(node.getEdges().isEmpty());
        }

        @Test
        void threeArgConstructorSetsFields() {
            var node = new CodeNode("entity:1", NodeKind.ENTITY, "User");
            assertEquals("entity:1", node.getId());
            assertEquals(NodeKind.ENTITY, node.getKind());
            assertEquals("User", node.getLabel());
        }

        @Test
        void allSettersAndGetters() {
            var node = new CodeNode();
            node.setId("service:1");
            node.setKind(NodeKind.SERVICE);
            node.setLabel("UserService");
            node.setFqn("com.example.UserService");
            node.setModule("user-module");
            node.setFilePath("src/UserService.java");
            node.setLineStart(5);
            node.setLineEnd(150);
            node.setLayer("backend");

            List<String> annotations = new ArrayList<>(List.of("@Service", "@Transactional"));
            node.setAnnotations(annotations);

            Map<String, Object> props = new HashMap<>();
            props.put("framework", "spring_boot");
            props.put("count", 42);
            node.setProperties(props);

            List<CodeEdge> edges = new ArrayList<>();
            node.setEdges(edges);

            assertEquals("service:1", node.getId());
            assertEquals(NodeKind.SERVICE, node.getKind());
            assertEquals("UserService", node.getLabel());
            assertEquals("com.example.UserService", node.getFqn());
            assertEquals("user-module", node.getModule());
            assertEquals("src/UserService.java", node.getFilePath());
            assertEquals(5, node.getLineStart());
            assertEquals(150, node.getLineEnd());
            assertEquals("backend", node.getLayer());
            assertEquals(2, node.getAnnotations().size());
            assertEquals("spring_boot", node.getProperties().get("framework"));
            assertEquals(42, node.getProperties().get("count"));
            assertTrue(node.getEdges().isEmpty());
        }

        @Test
        void equalsByIdOnly() {
            var n1 = new CodeNode("id:A", NodeKind.CLASS, "ClassA");
            var n2 = new CodeNode("id:A", NodeKind.ENDPOINT, "endpoint");
            var n3 = new CodeNode("id:B", NodeKind.CLASS, "ClassA");

            assertEquals(n1, n2, "Same ID = equal regardless of other fields");
            assertNotEquals(n1, n3, "Different ID = not equal");
        }

        @Test
        void hashCodeConsistentWithEquals() {
            var n1 = new CodeNode("id:X", NodeKind.TOPIC, "Topic");
            var n2 = new CodeNode("id:X", NodeKind.QUEUE, "Queue");
            assertEquals(n1.hashCode(), n2.hashCode(), "Same ID should have same hashCode");
        }

        @Test
        void equalsNull() {
            var node = new CodeNode("id:1", NodeKind.CLASS, "A");
            assertNotEquals(null, node);
        }

        @Test
        void equalsSelf() {
            var node = new CodeNode("id:1", NodeKind.CLASS, "A");
            assertEquals(node, node);
        }

        @Test
        void equalsOtherType() {
            var node = new CodeNode("id:1", NodeKind.CLASS, "A");
            assertNotEquals("string", node);
        }

        @Test
        void toStringContainsKeyFields() {
            var node = new CodeNode("endpoint:1", NodeKind.ENDPOINT, "GET /api");
            String str = node.toString();
            assertTrue(str.contains("endpoint:1"), "toString should contain id");
            assertTrue(str.contains("GET /api"), "toString should contain label");
        }

        @Test
        void propertiesAreMutable() {
            var node = new CodeNode();
            node.getProperties().put("key", "value");
            assertEquals("value", node.getProperties().get("key"));
        }

        @Test
        void annotationsAreMutable() {
            var node = new CodeNode();
            node.getAnnotations().add("@Entity");
            assertEquals(1, node.getAnnotations().size());
        }

        @Test
        void edgesAreMutable() {
            var node = new CodeNode("n1", NodeKind.CLASS, "A");
            var edge = new CodeEdge();
            edge.setId("e1");
            node.getEdges().add(edge);
            assertEquals(1, node.getEdges().size());
        }

        @Test
        void nodeWithNullIdEquality() {
            var n1 = new CodeNode();
            var n2 = new CodeNode();
            // Both have null id, should be equal by equals logic (Objects.equals(null, null) = true)
            assertEquals(n1, n2);
        }
    }

    // ==================== CodeEdge — comprehensive ====================
    @Nested
    class CodeEdgeCoverage {

        @Test
        void defaultConstructorInitializesProperties() {
            var edge = new CodeEdge();
            assertNotNull(edge.getProperties());
            assertTrue(edge.getProperties().isEmpty());
            assertNull(edge.getId());
            assertNull(edge.getKind());
            assertNull(edge.getSourceId());
            assertNull(edge.getTarget());
            assertNull(edge.getInternalId());
        }

        @Test
        void fourArgConstructor() {
            var target = new CodeNode("target:1", NodeKind.ENTITY, "Entity");
            var edge = new CodeEdge("edge:1", EdgeKind.MAPS_TO, "source:1", target);
            assertEquals("edge:1", edge.getId());
            assertEquals(EdgeKind.MAPS_TO, edge.getKind());
            assertEquals("source:1", edge.getSourceId());
            assertEquals(target, edge.getTarget());
        }

        @Test
        void allSettersAndGetters() {
            var edge = new CodeEdge();
            var target = new CodeNode("t:1", NodeKind.TOPIC, "MyTopic");

            edge.setId("edge:consume:1");
            edge.setKind(EdgeKind.CONSUMES);
            edge.setSourceId("consumer:1");
            edge.setTarget(target);

            Map<String, Object> props = new HashMap<>();
            props.put("topic", "orders");
            props.put("group_id", "order-group");
            edge.setProperties(props);

            assertEquals("edge:consume:1", edge.getId());
            assertEquals(EdgeKind.CONSUMES, edge.getKind());
            assertEquals("consumer:1", edge.getSourceId());
            assertEquals(target, edge.getTarget());
            assertEquals("orders", edge.getProperties().get("topic"));
            assertEquals("order-group", edge.getProperties().get("group_id"));
        }

        @Test
        void equalsByIdOnly() {
            var e1 = new CodeEdge("e:1", EdgeKind.CALLS, "s1", null);
            var e2 = new CodeEdge("e:1", EdgeKind.CONSUMES, "s2", null);
            var e3 = new CodeEdge("e:2", EdgeKind.CALLS, "s1", null);

            assertEquals(e1, e2, "Same ID = equal");
            assertNotEquals(e1, e3, "Different ID = not equal");
        }

        @Test
        void hashCodeConsistentWithEquals() {
            var e1 = new CodeEdge("e:X", EdgeKind.PRODUCES, "src", null);
            var e2 = new CodeEdge("e:X", EdgeKind.CONSUMES, "other", null);
            assertEquals(e1.hashCode(), e2.hashCode());
        }

        @Test
        void equalsNull() {
            var edge = new CodeEdge("e:1", EdgeKind.CALLS, "src", null);
            assertNotEquals(null, edge);
        }

        @Test
        void equalsSelf() {
            var edge = new CodeEdge("e:1", EdgeKind.CALLS, "src", null);
            assertEquals(edge, edge);
        }

        @Test
        void equalsOtherType() {
            var edge = new CodeEdge("e:1", EdgeKind.CALLS, "src", null);
            assertNotEquals("string", edge);
        }

        @Test
        void toStringContainsKeyFields() {
            var edge = new CodeEdge("edge:calls:1", EdgeKind.CALLS, "src:1", null);
            String str = edge.toString();
            assertTrue(str.contains("edge:calls:1"), "toString should contain id");
        }

        @Test
        void propertiesAreMutable() {
            var edge = new CodeEdge();
            edge.getProperties().put("weight", 5);
            assertEquals(5, edge.getProperties().get("weight"));
        }

        @Test
        void edgeWithNullIdEquality() {
            var e1 = new CodeEdge();
            var e2 = new CodeEdge();
            assertEquals(e1, e2);
        }
    }

    // ==================== NodeKind and EdgeKind enum completeness ====================
    @Nested
    class EnumValuesCompleteness {

        @Test
        void nodeKindContainsAllExpectedKinds() {
            // Verify all known kinds exist in the enum
            assertNotNull(NodeKind.MODULE);
            assertNotNull(NodeKind.PACKAGE);
            assertNotNull(NodeKind.CLASS);
            assertNotNull(NodeKind.METHOD);
            assertNotNull(NodeKind.ENDPOINT);
            assertNotNull(NodeKind.ENTITY);
            assertNotNull(NodeKind.REPOSITORY);
            assertNotNull(NodeKind.QUERY);
            assertNotNull(NodeKind.MIGRATION);
            assertNotNull(NodeKind.TOPIC);
            assertNotNull(NodeKind.QUEUE);
            assertNotNull(NodeKind.EVENT);
            assertNotNull(NodeKind.RMI_INTERFACE);
            assertNotNull(NodeKind.CONFIG_FILE);
            assertNotNull(NodeKind.CONFIG_KEY);
            assertNotNull(NodeKind.WEBSOCKET_ENDPOINT);
            assertNotNull(NodeKind.INTERFACE);
            assertNotNull(NodeKind.ABSTRACT_CLASS);
            assertNotNull(NodeKind.ENUM);
            assertNotNull(NodeKind.ANNOTATION_TYPE);
            assertNotNull(NodeKind.PROTOCOL_MESSAGE);
            assertNotNull(NodeKind.CONFIG_DEFINITION);
            assertNotNull(NodeKind.DATABASE_CONNECTION);
            assertNotNull(NodeKind.AZURE_RESOURCE);
            assertNotNull(NodeKind.AZURE_FUNCTION);
            assertNotNull(NodeKind.MESSAGE_QUEUE);
            assertNotNull(NodeKind.INFRA_RESOURCE);
            assertNotNull(NodeKind.COMPONENT);
            assertNotNull(NodeKind.GUARD);
            assertNotNull(NodeKind.MIDDLEWARE);
            assertNotNull(NodeKind.HOOK);
            assertNotNull(NodeKind.SERVICE);
        }

        @Test
        void edgeKindContainsAllExpectedKinds() {
            assertNotNull(EdgeKind.DEPENDS_ON);
            assertNotNull(EdgeKind.IMPORTS);
            assertNotNull(EdgeKind.EXTENDS);
            assertNotNull(EdgeKind.IMPLEMENTS);
            assertNotNull(EdgeKind.CALLS);
            assertNotNull(EdgeKind.INJECTS);
            assertNotNull(EdgeKind.EXPOSES);
            assertNotNull(EdgeKind.QUERIES);
            assertNotNull(EdgeKind.MAPS_TO);
            assertNotNull(EdgeKind.PRODUCES);
            assertNotNull(EdgeKind.CONSUMES);
            assertNotNull(EdgeKind.PUBLISHES);
            assertNotNull(EdgeKind.LISTENS);
            assertNotNull(EdgeKind.INVOKES_RMI);
            assertNotNull(EdgeKind.EXPORTS_RMI);
            assertNotNull(EdgeKind.READS_CONFIG);
            assertNotNull(EdgeKind.MIGRATES);
            assertNotNull(EdgeKind.CONTAINS);
            assertNotNull(EdgeKind.DEFINES);
            assertNotNull(EdgeKind.OVERRIDES);
            assertNotNull(EdgeKind.CONNECTS_TO);
            assertNotNull(EdgeKind.TRIGGERS);
            assertNotNull(EdgeKind.PROVISIONS);
            assertNotNull(EdgeKind.SENDS_TO);
            assertNotNull(EdgeKind.RECEIVES_FROM);
            assertNotNull(EdgeKind.PROTECTS);
            assertNotNull(EdgeKind.RENDERS);
        }
    }

    // ==================== CodeNode with Provenance ====================
    @Nested
    class CodeNodeProvenance {

        @Test
        void getProvenanceReturnsNullWhenNoProvenance() {
            var node = new CodeNode("n1", NodeKind.CLASS, "A");
            // No provenance keys set — should return null
            assertNull(node.getProvenance());
        }

        @Test
        void setProvenanceWithNullIsNoop() {
            var node = new CodeNode("n1", NodeKind.CLASS, "A");
            node.setProvenance(null); // Should not throw
            assertTrue(node.getProperties().isEmpty());
        }
    }

    // ==================== Mixed CodeNode/CodeEdge usage ====================
    @Nested
    class NodeEdgeIntegration {

        @Test
        void nodeWithAttachedEdges() {
            var entityNode = new CodeNode("entity:User", NodeKind.ENTITY, "User");
            var dbNode = new CodeNode("db:unknown", NodeKind.DATABASE_CONNECTION, "Database");

            var edge = new CodeEdge();
            edge.setId("entity:User->connects_to->db:unknown");
            edge.setKind(EdgeKind.CONNECTS_TO);
            edge.setSourceId("entity:User");
            edge.setTarget(dbNode);

            entityNode.getEdges().add(edge);

            assertEquals(1, entityNode.getEdges().size());
            assertEquals(EdgeKind.CONNECTS_TO, entityNode.getEdges().get(0).getKind());
            assertEquals(dbNode, entityNode.getEdges().get(0).getTarget());
        }

        @Test
        void multipleEdgesOnNode() {
            var controller = new CodeNode("ctrl:1", NodeKind.CLASS, "UserController");
            var endpoint1 = new CodeNode("ep:1", NodeKind.ENDPOINT, "GET /users");
            var endpoint2 = new CodeNode("ep:2", NodeKind.ENDPOINT, "POST /users");

            var e1 = new CodeEdge("e1", EdgeKind.EXPOSES, "ctrl:1", endpoint1);
            var e2 = new CodeEdge("e2", EdgeKind.EXPOSES, "ctrl:1", endpoint2);

            controller.getEdges().add(e1);
            controller.getEdges().add(e2);

            assertEquals(2, controller.getEdges().size());
        }

        @Test
        void nodePropertiesSupportVariousTypes() {
            var node = new CodeNode("n1", NodeKind.ENDPOINT, "POST /api");
            node.getProperties().put("http_method", "POST");
            node.getProperties().put("path", "/api");
            node.getProperties().put("line", 42);
            node.getProperties().put("active", true);
            node.getProperties().put("tags", List.of("public", "v1"));

            assertEquals("POST", node.getProperties().get("http_method"));
            assertEquals(42, node.getProperties().get("line"));
            assertEquals(true, node.getProperties().get("active"));
            assertInstanceOf(List.class, node.getProperties().get("tags"));
        }
    }

    // ==================== NodeKindConverter ====================
    @Nested
    class NodeKindConverterCoverage {

        private final NodeKindConverter converter = new NodeKindConverter();

        @Test
        void writeProducesLowercaseValue() {
            Value v = converter.write(NodeKind.CLASS);
            assertEquals("class", v.asString());
        }

        @Test
        void writeNullThrowsOrProducesNullLikeValue() {
            // Values.value(null) throws in the Neo4j driver — that is the expected behavior.
            assertThrows(Exception.class, () -> converter.write(null));
        }

        @Test
        void readReturnsCorrectKind() {
            Value v = Values.value("endpoint");
            assertEquals(NodeKind.ENDPOINT, converter.read(v));
        }

        @Test
        void readNullValueReturnsNull() {
            assertEquals(null, converter.read(Values.NULL));
        }

        @Test
        void readNullReferenceReturnsNull() {
            assertEquals(null, converter.read(null));
        }

        @Test
        void writeReadRoundTripForAllKinds() {
            for (NodeKind kind : NodeKind.values()) {
                Value written = converter.write(kind);
                NodeKind readBack = converter.read(written);
                assertEquals(kind, readBack, "Round-trip failed for " + kind);
            }
        }
    }

    // ==================== EdgeKindConverter ====================
    @Nested
    class EdgeKindConverterCoverage {

        private final EdgeKindConverter converter = new EdgeKindConverter();

        @Test
        void writeProducesLowercaseValue() {
            Value v = converter.write(EdgeKind.DEPENDS_ON);
            assertEquals("depends_on", v.asString());
        }

        @Test
        void writeNullThrowsOrProducesNullLikeValue() {
            // Values.value(null) throws in the Neo4j driver — that is the expected behavior.
            assertThrows(Exception.class, () -> converter.write(null));
        }

        @Test
        void readReturnsCorrectKind() {
            Value v = Values.value("calls");
            assertEquals(EdgeKind.CALLS, converter.read(v));
        }

        @Test
        void readNullValueReturnsNull() {
            assertEquals(null, converter.read(Values.NULL));
        }

        @Test
        void readNullReferenceReturnsNull() {
            assertEquals(null, converter.read(null));
        }

        @Test
        void writeReadRoundTripForAllKinds() {
            for (EdgeKind kind : EdgeKind.values()) {
                Value written = converter.write(kind);
                EdgeKind readBack = converter.read(written);
                assertEquals(kind, readBack, "Round-trip failed for " + kind);
            }
        }
    }
}
