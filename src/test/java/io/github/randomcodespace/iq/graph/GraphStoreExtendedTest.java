package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphStoreExtendedTest {

    @Mock
    private GraphRepository repository;

    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore(repository);
    }

    @Test
    void shouldSaveAll() {
        var nodes = List.of(
                new CodeNode("n1", NodeKind.CLASS, "A"),
                new CodeNode("n2", NodeKind.CLASS, "B")
        );
        when(repository.saveAll(nodes)).thenReturn(nodes);

        var saved = store.saveAll(nodes);
        assertEquals(2, saved.size());
        verify(repository).saveAll(nodes);
    }

    @Test
    void shouldFindAll() {
        var nodes = List.of(new CodeNode("n1", NodeKind.CLASS, "A"));
        when(repository.findAll()).thenReturn(nodes);

        var result = store.findAll();
        assertEquals(1, result.size());
    }

    @Test
    void shouldFindByLayer() {
        var node = new CodeNode("n1", NodeKind.CLASS, "A");
        when(repository.findByLayer("backend")).thenReturn(List.of(node));

        var results = store.findByLayer("backend");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindByModule() {
        var node = new CodeNode("n1", NodeKind.MODULE, "core");
        when(repository.findByModule("core")).thenReturn(List.of(node));

        var results = store.findByModule("core");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindByFilePath() {
        var node = new CodeNode("n1", NodeKind.CLASS, "A");
        when(repository.findByFilePath("src/Main.java")).thenReturn(List.of(node));

        var results = store.findByFilePath("src/Main.java");
        assertEquals(1, results.size());
    }

    @Test
    void shouldSearchWithLimit() {
        var node = new CodeNode("n1", NodeKind.CLASS, "User");
        when(repository.search("User", 10)).thenReturn(List.of(node));

        var results = store.search("User", 10);
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindNeighbors() {
        var neighbor = new CodeNode("n2", NodeKind.CLASS, "B");
        when(repository.findNeighbors("n1")).thenReturn(List.of(neighbor));

        var results = store.findNeighbors("n1");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindOutgoingNeighbors() {
        var target = new CodeNode("n2", NodeKind.CLASS, "B");
        when(repository.findOutgoingNeighbors("n1")).thenReturn(List.of(target));

        var results = store.findOutgoingNeighbors("n1");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindIncomingNeighbors() {
        var source = new CodeNode("n0", NodeKind.CLASS, "A");
        when(repository.findIncomingNeighbors("n1")).thenReturn(List.of(source));

        var results = store.findIncomingNeighbors("n1");
        assertEquals(1, results.size());
    }

    @Test
    void shouldDeleteById() {
        store.deleteById("n1");
        verify(repository).deleteById("n1");
    }

    @Test
    void shouldFindShortestPath() {
        when(repository.findShortestPath("A", "C")).thenReturn(List.of("A", "B", "C"));

        var path = store.findShortestPath("A", "C");
        assertEquals(3, path.size());
    }

    @Test
    void shouldFindEgoGraph() {
        var node = new CodeNode("n1", NodeKind.CLASS, "A");
        when(repository.findEgoGraph("center", 2)).thenReturn(List.of(node));

        var result = store.findEgoGraph("center", 2);
        assertEquals(1, result.size());
    }

    @Test
    void shouldTraceImpact() {
        var node = new CodeNode("n2", NodeKind.CLASS, "B");
        when(repository.traceImpact("n1", 3)).thenReturn(List.of(node));

        var result = store.traceImpact("n1", 3);
        assertEquals(1, result.size());
    }

    @Test
    void shouldFindCycles() {
        when(repository.findCycles(10)).thenReturn(List.of(List.of("A", "B", "A")));

        var cycles = store.findCycles(10);
        assertEquals(1, cycles.size());
    }

    @Test
    void shouldFindConsumers() {
        var node = new CodeNode("c1", NodeKind.CLASS, "Consumer");
        when(repository.findConsumers("topic")).thenReturn(List.of(node));

        var results = store.findConsumers("topic");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindProducers() {
        var node = new CodeNode("p1", NodeKind.CLASS, "Producer");
        when(repository.findProducers("topic")).thenReturn(List.of(node));

        var results = store.findProducers("topic");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindCallers() {
        var node = new CodeNode("caller1", NodeKind.METHOD, "doWork");
        when(repository.findCallers("target")).thenReturn(List.of(node));

        var results = store.findCallers("target");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindDependencies() {
        var dep = new CodeNode("dep1", NodeKind.MODULE, "lib");
        when(repository.findDependencies("mod")).thenReturn(List.of(dep));

        var results = store.findDependencies("mod");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindDependents() {
        var dep = new CodeNode("dep1", NodeKind.MODULE, "app");
        when(repository.findDependents("lib")).thenReturn(List.of(dep));

        var results = store.findDependents("lib");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindByKindPaginated() {
        var node = new CodeNode("n1", NodeKind.CLASS, "A");
        when(repository.findByKindPaginated("class", 0, 10)).thenReturn(List.of(node));

        var results = store.findByKindPaginated("class", 0, 10);
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindAllPaginated() {
        var node = new CodeNode("n1", NodeKind.CLASS, "A");
        when(repository.findAllPaginated(0, 10)).thenReturn(List.of(node));

        var results = store.findAllPaginated(0, 10);
        assertEquals(1, results.size());
    }

    @Test
    void shouldCountByKind() {
        when(repository.countByKind("class")).thenReturn(15L);

        assertEquals(15L, store.countByKind("class"));
    }
}
