package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.flow.FlowDataSource;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Facade service over the Neo4j graph backend.
 * All graph access goes through this service — never access GraphRepository directly
 * from controllers or other services.
 */
@Service
@ConditionalOnBean(GraphRepository.class)
public class GraphStore implements FlowDataSource {

    private final GraphRepository repository;

    public GraphStore(GraphRepository repository) {
        this.repository = repository;
    }

    public CodeNode save(CodeNode node) {
        return repository.save(node);
    }

    public List<CodeNode> saveAll(Iterable<CodeNode> nodes) {
        return repository.saveAll(nodes);
    }

    public Optional<CodeNode> findById(String id) {
        return repository.findById(id);
    }

    public List<CodeNode> findAll() {
        return repository.findAll();
    }

    public List<CodeNode> findByKind(NodeKind kind) {
        return repository.findByKind(kind.getValue());
    }

    public List<CodeNode> findByLayer(String layer) {
        return repository.findByLayer(layer);
    }

    public List<CodeNode> findByModule(String module) {
        return repository.findByModule(module);
    }

    public List<CodeNode> findByFilePath(String filePath) {
        return repository.findByFilePath(filePath);
    }

    public List<CodeNode> search(String text) {
        return repository.search(text);
    }

    public List<CodeNode> search(String text, int limit) {
        return repository.search(text, limit);
    }

    public List<CodeNode> findNeighbors(String nodeId) {
        return repository.findNeighbors(nodeId);
    }

    public List<CodeNode> findOutgoingNeighbors(String nodeId) {
        return repository.findOutgoingNeighbors(nodeId);
    }

    public List<CodeNode> findIncomingNeighbors(String nodeId) {
        return repository.findIncomingNeighbors(nodeId);
    }

    public long count() {
        return repository.count();
    }

    public void deleteAll() {
        repository.deleteAll();
    }

    public void deleteById(String id) {
        repository.deleteById(id);
    }

    // --- Graph traversal queries ---

    public List<String> findShortestPath(String source, String target) {
        return repository.findShortestPath(source, target);
    }

    public List<CodeNode> findEgoGraph(String center, int radius) {
        return repository.findEgoGraph(center, radius);
    }

    public List<CodeNode> traceImpact(String nodeId, int depth) {
        return repository.traceImpact(nodeId, depth);
    }

    public List<List<String>> findCycles(int limit) {
        return repository.findCycles(limit);
    }

    public List<CodeNode> findConsumers(String targetId) {
        return repository.findConsumers(targetId);
    }

    public List<CodeNode> findProducers(String targetId) {
        return repository.findProducers(targetId);
    }

    public List<CodeNode> findCallers(String targetId) {
        return repository.findCallers(targetId);
    }

    public List<CodeNode> findDependencies(String moduleId) {
        return repository.findDependencies(moduleId);
    }

    public List<CodeNode> findDependents(String moduleId) {
        return repository.findDependents(moduleId);
    }

    public List<CodeNode> findByKindPaginated(String kind, int offset, int limit) {
        return repository.findByKindPaginated(kind, offset, limit);
    }

    public List<CodeNode> findAllPaginated(int offset, int limit) {
        return repository.findAllPaginated(offset, limit);
    }

    public long countByKind(String kind) {
        return repository.countByKind(kind);
    }
}
