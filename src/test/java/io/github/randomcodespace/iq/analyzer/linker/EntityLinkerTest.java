package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntityLinkerTest {

    private final EntityLinker linker = new EntityLinker();

    @Test
    void linksRepositoryToEntityByNamingConvention() {
        var entity = new CodeNode("entity:User", NodeKind.ENTITY, "User");
        entity.setFqn("com.example.User");
        var repo = new CodeNode("repo:UserRepository", NodeKind.REPOSITORY, "UserRepository");

        LinkResult result = linker.link(List.of(entity, repo), List.of());

        assertEquals(1, result.edges().size());
        CodeEdge edge = result.edges().getFirst();
        assertEquals(EdgeKind.QUERIES, edge.getKind());
        assertEquals("repo:UserRepository", edge.getSourceId());
        assertEquals("entity:User", edge.getTarget().getId());
        assertEquals(true, edge.getProperties().get("inferred"));
    }

    @Test
    void matchesRepoSuffix() {
        var entity = new CodeNode("entity:Order", NodeKind.ENTITY, "Order");
        var repo = new CodeNode("repo:OrderRepo", NodeKind.REPOSITORY, "OrderRepo");

        LinkResult result = linker.link(List.of(entity, repo), List.of());

        assertEquals(1, result.edges().size());
    }

    @Test
    void matchesDaoSuffix() {
        var entity = new CodeNode("entity:Product", NodeKind.ENTITY, "Product");
        var dao = new CodeNode("dao:ProductDao", NodeKind.REPOSITORY, "ProductDao");

        LinkResult result = linker.link(List.of(entity, dao), List.of());

        assertEquals(1, result.edges().size());
    }

    @Test
    void matchesDAOSuffix() {
        var entity = new CodeNode("entity:Item", NodeKind.ENTITY, "Item");
        var dao = new CodeNode("dao:ItemDAO", NodeKind.REPOSITORY, "ItemDAO");

        LinkResult result = linker.link(List.of(entity, dao), List.of());

        assertEquals(1, result.edges().size());
    }

    @Test
    void noEntityMatchReturnsEmpty() {
        var entity = new CodeNode("entity:User", NodeKind.ENTITY, "User");
        var repo = new CodeNode("repo:OrderRepository", NodeKind.REPOSITORY, "OrderRepository");

        LinkResult result = linker.link(List.of(entity, repo), List.of());

        assertTrue(result.edges().isEmpty());
    }

    @Test
    void avoidsDuplicateEdges() {
        var entity = new CodeNode("entity:User", NodeKind.ENTITY, "User");
        var repo = new CodeNode("repo:UserRepository", NodeKind.REPOSITORY, "UserRepository");

        // Pre-existing QUERIES edge
        var existing = new CodeEdge();
        existing.setId("existing");
        existing.setKind(EdgeKind.QUERIES);
        existing.setSourceId("repo:UserRepository");
        existing.setTarget(entity);

        LinkResult result = linker.link(List.of(entity, repo), List.of(existing));

        assertTrue(result.edges().isEmpty());
    }

    @Test
    void noEntitiesReturnsEmpty() {
        var repo = new CodeNode("repo:UserRepository", NodeKind.REPOSITORY, "UserRepository");
        LinkResult result = linker.link(List.of(repo), List.of());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void noRepositoriesReturnsEmpty() {
        var entity = new CodeNode("entity:User", NodeKind.ENTITY, "User");
        LinkResult result = linker.link(List.of(entity), List.of());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void caseInsensitiveEntityMatching() {
        var entity = new CodeNode("entity:user", NodeKind.ENTITY, "user");
        var repo = new CodeNode("repo:UserRepository", NodeKind.REPOSITORY, "UserRepository");

        LinkResult result = linker.link(List.of(entity, repo), List.of());

        assertEquals(1, result.edges().size());
    }
}
