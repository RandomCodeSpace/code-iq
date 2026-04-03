package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuardLinkerTest {

    private final GuardLinker linker = new GuardLinker();

    private CodeNode guardNode(String id, String filePath) {
        var node = new CodeNode(id, NodeKind.GUARD, id);
        node.setFilePath(filePath);
        return node;
    }

    private CodeNode middlewareNode(String id, String filePath) {
        var node = new CodeNode(id, NodeKind.MIDDLEWARE, id);
        node.setFilePath(filePath);
        return node;
    }

    private CodeNode endpointNode(String id, String filePath) {
        var node = new CodeNode(id, NodeKind.ENDPOINT, id);
        node.setFilePath(filePath);
        return node;
    }

    @Test
    void linksGuardToEndpointInSameFile() {
        var guard = guardNode("auth:UserController.java:Secured:10", "UserController.java");
        var endpoint = endpointNode("ep:UserController.java:GET:/users:20", "UserController.java");

        LinkResult result = linker.link(List.of(guard, endpoint), List.of());

        assertEquals(1, result.edges().size());
        CodeEdge edge = result.edges().getFirst();
        assertEquals(EdgeKind.PROTECTS, edge.getKind());
        assertEquals(guard.getId(), edge.getSourceId());
        assertEquals(endpoint.getId(), edge.getTarget().getId());
        assertEquals(true, edge.getProperties().get("inferred"));
    }

    @Test
    void linksMiddlewareToEndpointInSameFile() {
        var middleware = middlewareNode("mw:routes.ts:authMiddleware:5", "routes.ts");
        var endpoint = endpointNode("ep:routes.ts:GET:/profile:15", "routes.ts");

        LinkResult result = linker.link(List.of(middleware, endpoint), List.of());

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.PROTECTS, result.edges().getFirst().getKind());
    }

    @Test
    void classLevelGuardProtectsAllEndpointsInSameFile() {
        var guard = guardNode("auth:OrderController.java:PreAuthorize:3", "OrderController.java");
        var ep1 = endpointNode("ep:OrderController.java:GET:/orders:25", "OrderController.java");
        var ep2 = endpointNode("ep:OrderController.java:POST:/orders:35", "OrderController.java");
        var ep3 = endpointNode("ep:OrderController.java:DELETE:/orders/{id}:45", "OrderController.java");

        LinkResult result = linker.link(List.of(guard, ep1, ep2, ep3), List.of());

        assertEquals(3, result.edges().size());
        assertTrue(result.edges().stream().allMatch(e -> e.getKind() == EdgeKind.PROTECTS));
        assertTrue(result.edges().stream().allMatch(e -> e.getSourceId().equals(guard.getId())));
    }

    @Test
    void guardInDifferentFileDoesNotProtectEndpoint() {
        var guard = guardNode("auth:SecurityConfig.java:EnableWebSecurity:1", "SecurityConfig.java");
        var endpoint = endpointNode("ep:UserController.java:GET:/users:10", "UserController.java");

        LinkResult result = linker.link(List.of(guard, endpoint), List.of());

        assertTrue(result.edges().isEmpty());
    }

    @Test
    void noGuardsReturnsEmpty() {
        var endpoint = endpointNode("ep:UserController.java:GET:/users:10", "UserController.java");

        LinkResult result = linker.link(List.of(endpoint), List.of());

        assertTrue(result.edges().isEmpty());
    }

    @Test
    void noEndpointsReturnsEmpty() {
        var guard = guardNode("auth:UserController.java:Secured:5", "UserController.java");

        LinkResult result = linker.link(List.of(guard), List.of());

        assertTrue(result.edges().isEmpty());
    }

    @Test
    void avoidsDuplicateEdges() {
        var guard = guardNode("auth:UserController.java:Secured:5", "UserController.java");
        var endpoint = endpointNode("ep:UserController.java:GET:/users:15", "UserController.java");

        var existing = new CodeEdge();
        existing.setId("existing");
        existing.setKind(EdgeKind.PROTECTS);
        existing.setSourceId(guard.getId());
        existing.setTarget(endpoint);

        LinkResult result = linker.link(List.of(guard, endpoint), List.of(existing));

        assertTrue(result.edges().isEmpty());
    }

    @Test
    void nodesWithNullFilePathAreIgnored() {
        var guard = new CodeNode("auth:guard:1", NodeKind.GUARD, "guard");
        // filePath is null by default
        var endpoint = endpointNode("ep:file.java:GET:/users:10", "file.java");

        LinkResult result = linker.link(List.of(guard, endpoint), List.of());

        assertTrue(result.edges().isEmpty());
    }

    @Test
    void determinismRunTwiceProducesSameResult() {
        var guard1 = guardNode("auth:Ctrl.java:Secured:5", "Ctrl.java");
        var guard2 = guardNode("auth:Ctrl.java:PreAuthorize:8", "Ctrl.java");
        var ep1 = endpointNode("ep:Ctrl.java:GET:/a:20", "Ctrl.java");
        var ep2 = endpointNode("ep:Ctrl.java:POST:/b:30", "Ctrl.java");

        LinkResult r1 = linker.link(List.of(guard1, guard2, ep1, ep2), List.of());
        LinkResult r2 = linker.link(List.of(guard1, guard2, ep1, ep2), List.of());

        assertEquals(r1.edges().size(), r2.edges().size());
        for (int i = 0; i < r1.edges().size(); i++) {
            assertEquals(r1.edges().get(i).getId(), r2.edges().get(i).getId());
        }
    }
}
