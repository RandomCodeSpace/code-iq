package io.github.randomcodespace.iq.flow;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CacheFlowDataSourceTest {

    private static CodeNode node(String id, NodeKind kind) {
        return new CodeNode(id, kind, id);
    }

    @Test
    void findAllReturnsAllNodes() {
        var n1 = node("cls:1", NodeKind.CLASS);
        var n2 = node("ep:1", NodeKind.ENDPOINT);
        var n3 = node("svc:1", NodeKind.SERVICE);
        var ds = new CacheFlowDataSource(List.of(n1, n2, n3));

        assertEquals(List.of(n1, n2, n3), ds.findAll());
    }

    @Test
    void findByKindFiltersCorrectly() {
        var cls1 = node("cls:1", NodeKind.CLASS);
        var cls2 = node("cls:2", NodeKind.CLASS);
        var ep1 = node("ep:1", NodeKind.ENDPOINT);
        var ds = new CacheFlowDataSource(List.of(cls1, cls2, ep1));

        List<CodeNode> classes = ds.findByKind(NodeKind.CLASS);
        assertEquals(2, classes.size());
        assertTrue(classes.contains(cls1));
        assertTrue(classes.contains(cls2));
    }

    @Test
    void findByKindReturnsEmptyWhenNoneMatch() {
        var cls1 = node("cls:1", NodeKind.CLASS);
        var ds = new CacheFlowDataSource(List.of(cls1));

        List<CodeNode> topics = ds.findByKind(NodeKind.TOPIC);
        assertTrue(topics.isEmpty());
    }

    @Test
    void countReturnsNodeListSize() {
        var ds = new CacheFlowDataSource(List.of(
                node("a", NodeKind.CLASS),
                node("b", NodeKind.METHOD),
                node("c", NodeKind.ENDPOINT)
        ));
        assertEquals(3, ds.count());
    }

    @Test
    void countReturnsZeroForEmptyList() {
        var ds = new CacheFlowDataSource(List.of());
        assertEquals(0, ds.count());
    }

    @Test
    void findAllReturnsEmptyForEmptyList() {
        var ds = new CacheFlowDataSource(List.of());
        assertTrue(ds.findAll().isEmpty());
    }

    @Test
    void findByKindAllSameKind() {
        var n1 = node("e:1", NodeKind.ENDPOINT);
        var n2 = node("e:2", NodeKind.ENDPOINT);
        var ds = new CacheFlowDataSource(List.of(n1, n2));

        assertEquals(2, ds.findByKind(NodeKind.ENDPOINT).size());
    }
}
