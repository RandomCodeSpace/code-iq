package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for linker classes — branches not hit by
 * existing tests.
 */
class LinkersCoverageTest {

    // =====================================================================
    // GuardLinker
    // =====================================================================
    @Nested
    class GuardLinkerCoverage {
        private final GuardLinker linker = new GuardLinker();

        @Test
        void linksGuardToEndpointInSameFile() {
            var guard = new CodeNode("guard:auth1", NodeKind.GUARD, "AuthGuard");
            guard.setFilePath("src/UserController.java");

            var endpoint = new CodeNode("ep:getUser", NodeKind.ENDPOINT, "GET /users/{id}");
            endpoint.setFilePath("src/UserController.java");

            LinkResult result = linker.link(List.of(guard, endpoint), List.of());

            assertEquals(1, result.edges().size());
            CodeEdge edge = result.edges().getFirst();
            assertEquals(EdgeKind.PROTECTS, edge.getKind());
            assertEquals("guard:auth1", edge.getSourceId());
            assertEquals("ep:getUser", edge.getTarget().getId());
            assertEquals(true, edge.getProperties().get("inferred"));
        }

        @Test
        void linksMiddlewareToEndpoint() {
            var middleware = new CodeNode("mw:jwt", NodeKind.MIDDLEWARE, "JwtMiddleware");
            middleware.setFilePath("src/SecureController.java");

            var endpoint = new CodeNode("ep:secure", NodeKind.ENDPOINT, "POST /secure");
            endpoint.setFilePath("src/SecureController.java");

            LinkResult result = linker.link(List.of(middleware, endpoint), List.of());

            assertEquals(1, result.edges().size());
            assertEquals(EdgeKind.PROTECTS, result.edges().getFirst().getKind());
        }

        @Test
        void noLinkBetweenDifferentFiles() {
            var guard = new CodeNode("guard:g1", NodeKind.GUARD, "Guard");
            guard.setFilePath("src/GuardConfig.java");

            var endpoint = new CodeNode("ep:e1", NodeKind.ENDPOINT, "GET /data");
            endpoint.setFilePath("src/DataController.java");

            LinkResult result = linker.link(List.of(guard, endpoint), List.of());

            assertTrue(result.edges().isEmpty());
        }

        @Test
        void noGuardsReturnsEmpty() {
            var endpoint = new CodeNode("ep:e1", NodeKind.ENDPOINT, "GET /x");
            endpoint.setFilePath("src/Ctrl.java");

            LinkResult result = linker.link(List.of(endpoint), List.of());
            assertTrue(result.edges().isEmpty());
        }

        @Test
        void noEndpointsReturnsEmpty() {
            var guard = new CodeNode("g:g1", NodeKind.GUARD, "G");
            guard.setFilePath("src/Ctrl.java");

            LinkResult result = linker.link(List.of(guard), List.of());
            assertTrue(result.edges().isEmpty());
        }

        @Test
        void nodeWithNullFilePathSkipped() {
            var guard = new CodeNode("g:g1", NodeKind.GUARD, "G");
            // no filePath set

            var endpoint = new CodeNode("ep:e1", NodeKind.ENDPOINT, "GET /x");
            // no filePath set

            LinkResult result = linker.link(List.of(guard, endpoint), List.of());
            assertTrue(result.edges().isEmpty());
        }

        @Test
        void nodeWithBlankFilePathSkipped() {
            var guard = new CodeNode("g:g1", NodeKind.GUARD, "G");
            guard.setFilePath("   ");

            var endpoint = new CodeNode("ep:e1", NodeKind.ENDPOINT, "GET /x");
            endpoint.setFilePath("   ");

            LinkResult result = linker.link(List.of(guard, endpoint), List.of());
            assertTrue(result.edges().isEmpty());
        }

        @Test
        void avoidsDuplicateProtectsEdges() {
            var guard = new CodeNode("g:g1", NodeKind.GUARD, "G");
            guard.setFilePath("src/Ctrl.java");

            var endpoint = new CodeNode("ep:e1", NodeKind.ENDPOINT, "GET /x");
            endpoint.setFilePath("src/Ctrl.java");

            // Pre-existing PROTECTS edge
            var existing = new CodeEdge();
            existing.setId("existing");
            existing.setKind(EdgeKind.PROTECTS);
            existing.setSourceId("g:g1");
            existing.setTarget(endpoint);

            LinkResult result = linker.link(List.of(guard, endpoint), List.of(existing));
            assertTrue(result.edges().isEmpty());
        }

        @Test
        void multipleGuardsAndEndpointsCrossLinked() {
            var guard1 = new CodeNode("g:g1", NodeKind.GUARD, "Auth");
            guard1.setFilePath("src/Ctrl.java");
            var guard2 = new CodeNode("g:g2", NodeKind.MIDDLEWARE, "Logging");
            guard2.setFilePath("src/Ctrl.java");
            var ep1 = new CodeNode("ep:e1", NodeKind.ENDPOINT, "GET /a");
            ep1.setFilePath("src/Ctrl.java");
            var ep2 = new CodeNode("ep:e2", NodeKind.ENDPOINT, "POST /b");
            ep2.setFilePath("src/Ctrl.java");

            LinkResult result = linker.link(List.of(guard1, guard2, ep1, ep2), List.of());
            // 2 guards x 2 endpoints = 4 edges
            assertEquals(4, result.edges().size());
        }

        @Test
        void deterministic() {
            var guard = new CodeNode("g:g1", NodeKind.GUARD, "G");
            guard.setFilePath("f.java");
            var ep1 = new CodeNode("ep:e1", NodeKind.ENDPOINT, "GET /a");
            ep1.setFilePath("f.java");
            var ep2 = new CodeNode("ep:e2", NodeKind.ENDPOINT, "GET /b");
            ep2.setFilePath("f.java");

            LinkResult r1 = linker.link(List.of(guard, ep1, ep2), List.of());
            LinkResult r2 = linker.link(List.of(guard, ep1, ep2), List.of());

            assertEquals(r1.edges().size(), r2.edges().size());
            for (int i = 0; i < r1.edges().size(); i++) {
                assertEquals(r1.edges().get(i).getId(), r2.edges().get(i).getId());
            }
        }
    }

    // =====================================================================
    // EntityLinker — additional branches
    // =====================================================================
    @Nested
    class EntityLinkerCoverage {
        private final EntityLinker linker = new EntityLinker();

        @Test
        void matchesFqnSimpleNameForEntity() {
            // Entity has fqn "com.example.User" — repo uses label "User"
            var entity = new CodeNode("entity:com.example.User", NodeKind.ENTITY, "UserEntity");
            entity.setFqn("com.example.User");
            var repo = new CodeNode("repo:UserRepository", NodeKind.REPOSITORY, "UserRepository");

            LinkResult result = linker.link(List.of(entity, repo), List.of());

            // "user" (from fqn "com.example.User" -> "user") should match "user" (from "UserRepository" - "Repository")
            assertEquals(1, result.edges().size());
        }

        @Test
        void matchesByLabelLowercase() {
            var entity = new CodeNode("entity:Product", NodeKind.ENTITY, "Product");
            var repo = new CodeNode("repo:ProductRepository", NodeKind.REPOSITORY, "ProductRepository");

            LinkResult result = linker.link(List.of(entity, repo), List.of());
            assertEquals(1, result.edges().size());
            assertEquals("entity:Product", result.edges().getFirst().getTarget().getId());
        }

        @Test
        void multipleReposForSameEntity() {
            var entity = new CodeNode("entity:Order", NodeKind.ENTITY, "Order");
            var repo1 = new CodeNode("repo:OrderRepository", NodeKind.REPOSITORY, "OrderRepository");
            var repo2 = new CodeNode("repo:OrderRepo", NodeKind.REPOSITORY, "OrderRepo");

            LinkResult result = linker.link(List.of(entity, repo1, repo2), List.of());
            assertEquals(2, result.edges().size());
        }

        @Test
        void entityWithNullFqnUsesLabel() {
            var entity = new CodeNode("entity:Item", NodeKind.ENTITY, "Item");
            // fqn is null
            var repo = new CodeNode("repo:ItemDao", NodeKind.REPOSITORY, "ItemDao");

            LinkResult result = linker.link(List.of(entity, repo), List.of());
            assertEquals(1, result.edges().size());
        }

        @Test
        void noPrefixMatchSkips() {
            // "SalesDAO" doesn't match "Product" entity
            var entity = new CodeNode("entity:Product", NodeKind.ENTITY, "Product");
            var repo = new CodeNode("repo:SalesDAO", NodeKind.REPOSITORY, "SalesDAO");

            LinkResult result = linker.link(List.of(entity, repo), List.of());
            assertTrue(result.edges().isEmpty());
        }

        @Test
        void deterministic() {
            var entity1 = new CodeNode("entity:Alpha", NodeKind.ENTITY, "Alpha");
            var entity2 = new CodeNode("entity:Beta", NodeKind.ENTITY, "Beta");
            var repo1 = new CodeNode("repo:AlphaRepository", NodeKind.REPOSITORY, "AlphaRepository");
            var repo2 = new CodeNode("repo:BetaRepo", NodeKind.REPOSITORY, "BetaRepo");

            LinkResult r1 = linker.link(List.of(entity1, entity2, repo1, repo2), List.of());
            LinkResult r2 = linker.link(List.of(entity1, entity2, repo1, repo2), List.of());

            assertEquals(r1.edges().size(), r2.edges().size());
            for (int i = 0; i < r1.edges().size(); i++) {
                assertEquals(r1.edges().get(i).getId(), r2.edges().get(i).getId());
            }
        }
    }

    // =====================================================================
    // ModuleContainmentLinker — additional branches
    // =====================================================================
    @Nested
    class ModuleContainmentCoverage {
        private final ModuleContainmentLinker linker = new ModuleContainmentLinker();

        @Test
        void multipleKindsInSameModule() {
            var cls = new CodeNode("cls:A", NodeKind.CLASS, "A");
            cls.setModule("org.example");
            var iface = new CodeNode("iface:B", NodeKind.INTERFACE, "B");
            iface.setModule("org.example");
            var enm = new CodeNode("enum:C", NodeKind.ENUM, "C");
            enm.setModule("org.example");

            LinkResult result = linker.link(List.of(cls, iface, enm), List.of());

            assertEquals(1, result.nodes().size()); // one module node
            assertEquals(3, result.edges().size()); // 3 CONTAINS edges
        }

        @Test
        void nullModuleSkipped() {
            var node = new CodeNode("cls:A", NodeKind.CLASS, "A");
            // module not set — should be null

            LinkResult result = linker.link(List.of(node), List.of());
            assertTrue(result.nodes().isEmpty());
            assertTrue(result.edges().isEmpty());
        }

        @Test
        void deterministic() {
            var n1 = new CodeNode("cls:X", NodeKind.CLASS, "X");
            n1.setModule("com.mod");
            var n2 = new CodeNode("cls:Y", NodeKind.CLASS, "Y");
            n2.setModule("com.mod");

            LinkResult r1 = linker.link(List.of(n1, n2), List.of());
            LinkResult r2 = linker.link(List.of(n1, n2), List.of());

            assertEquals(r1.nodes().size(), r2.nodes().size());
            assertEquals(r1.edges().size(), r2.edges().size());
        }
    }

    // =====================================================================
    // TopicLinker — additional branches
    // =====================================================================
    @Nested
    class TopicLinkerCoverage {
        private final TopicLinker linker = new TopicLinker();

        @Test
        void emptyNodesAndEdgesReturnsEmpty() {
            LinkResult result = linker.link(List.of(), List.of());
            assertTrue(result.edges().isEmpty());
            assertTrue(result.nodes().isEmpty());
        }

        @Test
        void topicWithNoProducersOrConsumersReturnsEmpty() {
            var topic = new CodeNode("topic:orphan", NodeKind.TOPIC, "orphan");
            LinkResult result = linker.link(List.of(topic), List.of());
            assertTrue(result.edges().isEmpty());
        }

        @Test
        void multipleConsumersForOneTopic() {
            var topic = new CodeNode("topic:updates", NodeKind.TOPIC, "updates");
            var producer = new CodeNode("svc:Prod", NodeKind.CLASS, "Prod");
            var consumer1 = new CodeNode("svc:Con1", NodeKind.CLASS, "Con1");
            var consumer2 = new CodeNode("svc:Con2", NodeKind.CLASS, "Con2");

            var producesEdge = new CodeEdge();
            producesEdge.setId("e1");
            producesEdge.setKind(EdgeKind.PRODUCES);
            producesEdge.setSourceId("svc:Prod");
            producesEdge.setTarget(topic);

            var consumesEdge1 = new CodeEdge();
            consumesEdge1.setId("e2");
            consumesEdge1.setKind(EdgeKind.CONSUMES);
            consumesEdge1.setSourceId("svc:Con1");
            consumesEdge1.setTarget(topic);

            var consumesEdge2 = new CodeEdge();
            consumesEdge2.setId("e3");
            consumesEdge2.setKind(EdgeKind.CONSUMES);
            consumesEdge2.setSourceId("svc:Con2");
            consumesEdge2.setTarget(topic);

            LinkResult result = linker.link(
                    List.of(topic, producer, consumer1, consumer2),
                    List.of(producesEdge, consumesEdge1, consumesEdge2));

            assertEquals(2, result.edges().size());
        }

        @Test
        void multipleProducersForOneTopic() {
            var topic = new CodeNode("topic:orders", NodeKind.TOPIC, "orders");
            var prod1 = new CodeNode("svc:Prod1", NodeKind.CLASS, "Prod1");
            var prod2 = new CodeNode("svc:Prod2", NodeKind.CLASS, "Prod2");
            var consumer = new CodeNode("svc:Con", NodeKind.CLASS, "Con");

            var e1 = new CodeEdge();
            e1.setId("e1");
            e1.setKind(EdgeKind.PRODUCES);
            e1.setSourceId("svc:Prod1");
            e1.setTarget(topic);

            var e2 = new CodeEdge();
            e2.setId("e2");
            e2.setKind(EdgeKind.PRODUCES);
            e2.setSourceId("svc:Prod2");
            e2.setTarget(topic);

            var e3 = new CodeEdge();
            e3.setId("e3");
            e3.setKind(EdgeKind.CONSUMES);
            e3.setSourceId("svc:Con");
            e3.setTarget(topic);

            LinkResult result = linker.link(
                    List.of(topic, prod1, prod2, consumer),
                    List.of(e1, e2, e3));

            assertEquals(2, result.edges().size());
        }
    }
}
