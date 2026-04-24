package io.github.randomcodespace.iq.query;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service topology analysis — works on in-memory node/edge lists.
 * <p>
 * Runtime connections: CALLS, PRODUCES, CONSUMES, QUERIES, CONNECTS_TO,
 * PUBLISHES, LISTENS, SENDS_TO, RECEIVES_FROM, INVOKES_RMI, EXPORTS_RMI.
 * DEPENDS_ON is excluded (build dependency, not runtime).
 */
@Service
public class TopologyService {
    private static final String PROP_CONNECTIONS = "connections";
    private static final String PROP_ENDPOINT_COUNT = "endpoint_count";
    private static final String PROP_ENTITY_COUNT = "entity_count";
    private static final String PROP_SERVICE = "service";
    private static final String PROP_SOURCE = "source";
    private static final String PROP_TARGET = "target";
    private static final String PROP_TOTAL_CONNECTIONS = "total_connections";
    private static final String PROP_TYPE = "type";


    /** Edge kinds that represent runtime service-to-service connections. */
    private static final Set<EdgeKind> RUNTIME_EDGES = EnumSet.of(
            EdgeKind.CALLS,
            EdgeKind.PRODUCES,
            EdgeKind.CONSUMES,
            EdgeKind.QUERIES,
            EdgeKind.CONNECTS_TO,
            EdgeKind.PUBLISHES,
            EdgeKind.LISTENS,
            EdgeKind.SENDS_TO,
            EdgeKind.RECEIVES_FROM,
            EdgeKind.INVOKES_RMI,
            EdgeKind.EXPORTS_RMI
    );

    /**
     * Full topology map — all SERVICE nodes and connections between them.
     */
    public Map<String, Object> getTopology(List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, CodeNode> serviceNodes = findServiceNodes(nodes);
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        List<Map<String, Object>> connections = findCrossServiceConnections(edges, nodeToService);

        // Pre-build degree maps once — O(N+M) instead of O(N×M)
        Map<Object, Long> outDegree = connections.stream()
                .collect(Collectors.groupingBy(c -> c.get(PROP_SOURCE), Collectors.counting()));
        Map<Object, Long> inDegree = connections.stream()
                .collect(Collectors.groupingBy(c -> c.get(PROP_TARGET), Collectors.counting()));

        // Build service summaries
        List<Map<String, Object>> services = new ArrayList<>();
        for (CodeNode svc : sortedValues(serviceNodes)) {
            Map<String, Object> svcMap = new LinkedHashMap<>();
            svcMap.put("name", svc.getLabel());
            svcMap.put("build_tool", svc.getProperties().getOrDefault("build_tool", "unknown"));
            svcMap.put(PROP_ENDPOINT_COUNT, svc.getProperties().getOrDefault(PROP_ENDPOINT_COUNT, 0));
            svcMap.put(PROP_ENTITY_COUNT, svc.getProperties().getOrDefault(PROP_ENTITY_COUNT, 0));

            String name = svc.getLabel();
            svcMap.put("connections_out", outDegree.getOrDefault(name, 0L));
            svcMap.put("connections_in", inDegree.getOrDefault(name, 0L));
            services.add(svcMap);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("services", services);
        result.put(PROP_CONNECTIONS, connections);
        result.put("service_count", services.size());
        result.put("connection_count", connections.size());
        return result;
    }

    /**
     * Detailed view of a specific service.
     */
    public Map<String, Object> serviceDetail(String serviceName, List<CodeNode> nodes, List<CodeEdge> edges) {
        List<CodeNode> childNodes = nodes.stream()
                .filter(n -> serviceName.equals(serviceOf(n)))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", serviceName);

        // Group by kind
        result.put("endpoints", childNodes.stream()
                .filter(n -> n.getKind() == NodeKind.ENDPOINT)
                .map(this::nodeToCompact)
                .toList());
        result.put("entities", childNodes.stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY)
                .map(this::nodeToCompact)
                .toList());
        result.put("guards", childNodes.stream()
                .filter(n -> n.getKind() == NodeKind.GUARD)
                .map(this::nodeToCompact)
                .toList());
        result.put("databases", childNodes.stream()
                .filter(n -> n.getKind() == NodeKind.DATABASE_CONNECTION)
                .map(this::nodeToCompact)
                .toList());
        result.put("queues", childNodes.stream()
                .filter(n -> n.getKind() == NodeKind.TOPIC || n.getKind() == NodeKind.QUEUE
                        || n.getKind() == NodeKind.MESSAGE_QUEUE)
                .map(this::nodeToCompact)
                .toList());

        // Connections involving this service
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        List<Map<String, Object>> connections = findCrossServiceConnections(edges, nodeToService);
        result.put(PROP_CONNECTIONS, connections.stream()
                .filter(c -> serviceName.equals(c.get(PROP_SOURCE)) || serviceName.equals(c.get(PROP_TARGET)))
                .toList());

        result.put("files", childNodes.stream()
                .map(CodeNode::getFilePath)
                .filter(fp -> fp != null)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .size());
        result.put("nodes", childNodes.size());

        return result;
    }

    /**
     * Dependencies of a service — other services, databases, queues, external systems.
     */
    public Map<String, Object> serviceDependencies(String serviceName, List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        List<Map<String, Object>> connections = findCrossServiceConnections(edges, nodeToService);

        List<Map<String, Object>> deps = connections.stream()
                .filter(c -> serviceName.equals(c.get(PROP_SOURCE)))
                .toList();

        Set<String> depServices = deps.stream()
                .map(c -> (String) c.get(PROP_TARGET))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_SERVICE, serviceName);
        result.put("depends_on", depServices);
        result.put(PROP_CONNECTIONS, deps);
        result.put("count", depServices.size());
        return result;
    }

    /**
     * Services that depend on this service.
     */
    public Map<String, Object> serviceDependents(String serviceName, List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        List<Map<String, Object>> connections = findCrossServiceConnections(edges, nodeToService);

        List<Map<String, Object>> deps = connections.stream()
                .filter(c -> serviceName.equals(c.get(PROP_TARGET)))
                .toList();

        Set<String> dependentServices = deps.stream()
                .map(c -> (String) c.get(PROP_SOURCE))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_SERVICE, serviceName);
        result.put("depended_by", dependentServices);
        result.put(PROP_CONNECTIONS, deps);
        result.put("count", dependentServices.size());
        return result;
    }

    /**
     * Blast radius — what services and nodes are affected if this node changes.
     * Traces outward through runtime edges.
     */
    public Map<String, Object> blastRadius(String nodeId, List<CodeNode> nodes, List<CodeEdge> edges) {
        // Build adjacency for BFS
        Map<String, List<String>> adjacency = new HashMap<>();
        for (CodeEdge edge : edges) {
            if (!RUNTIME_EDGES.contains(edge.getKind())) continue;
            String src = edge.getSourceId();
            String tgt = edge.getTarget() != null ? edge.getTarget().getId() : null;
            if (src == null || tgt == null) continue;
            adjacency.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
        }

        // BFS from nodeId, max depth 5
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> depth = new HashMap<>();
        queue.add(nodeId);
        depth.put(nodeId, 0);
        visited.add(nodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int d = depth.get(current);
            if (d >= 5) continue;
            for (String neighbor : adjacency.getOrDefault(current, List.of())) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                    depth.put(neighbor, d + 1);
                }
            }
        }

        visited.remove(nodeId); // Don't include the source

        // Map affected nodes to services
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        Map<String, CodeNode> nodeMap = nodes.stream()
                .collect(Collectors.toMap(CodeNode::getId, n -> n, (a, b) -> a));

        Set<String> affectedServices = new LinkedHashSet<>();
        List<Map<String, Object>> affectedNodes = new ArrayList<>();
        for (String nid : visited) {
            String svc = nodeToService.get(nid);
            if (svc != null) affectedServices.add(svc);
            CodeNode n = nodeMap.get(nid);
            if (n != null) {
                Map<String, Object> nm = nodeToCompact(n);
                nm.put("depth", depth.get(nid));
                affectedNodes.add(nm);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_SOURCE, nodeId);
        result.put("affected_services", affectedServices);
        result.put("affected_nodes", affectedNodes);
        result.put("affected_service_count", affectedServices.size());
        result.put("affected_node_count", affectedNodes.size());
        return result;
    }

    /**
     * Find connection path between two services.
     */
    public List<Map<String, Object>> findPath(String source, String target,
                                               List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        List<Map<String, Object>> connections = findCrossServiceConnections(edges, nodeToService);

        // Build service-level adjacency
        Map<String, Map<String, Map<String, Object>>> adj = new HashMap<>();
        for (Map<String, Object> conn : connections) {
            String src = (String) conn.get(PROP_SOURCE);
            String tgt = (String) conn.get(PROP_TARGET);
            adj.computeIfAbsent(src, k -> new HashMap<>()).putIfAbsent(tgt, conn);
        }

        // BFS to find shortest path
        Queue<List<String>> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        queue.add(List.of(source));
        visited.add(source);

        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            String current = path.getLast();
            if (current.equals(target)) {
                // Build result with connection details
                List<Map<String, Object>> result = new ArrayList<>();
                for (int i = 0; i < path.size() - 1; i++) {
                    Map<String, Object> hop = adj.getOrDefault(path.get(i), Map.of())
                            .getOrDefault(path.get(i + 1), Map.of());
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("from", path.get(i));
                    step.put("to", path.get(i + 1));
                    step.put(PROP_TYPE, hop.getOrDefault(PROP_TYPE, "unknown"));
                    result.add(step);
                }
                return result;
            }
            for (String neighbor : adj.getOrDefault(current, Map.of()).keySet()) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    List<String> newPath = new ArrayList<>(path);
                    newPath.add(neighbor);
                    queue.add(newPath);
                }
            }
        }

        return List.of(); // No path found
    }

    /**
     * Hub services with most connections (in + out).
     */
    public List<Map<String, Object>> findBottlenecks(List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        List<Map<String, Object>> connections = findCrossServiceConnections(edges, nodeToService);

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();

        for (Map<String, Object> conn : connections) {
            String src = (String) conn.get(PROP_SOURCE);
            String tgt = (String) conn.get(PROP_TARGET);
            outDegree.merge(src, 1, Integer::sum);
            inDegree.merge(tgt, 1, Integer::sum);
        }

        Map<String, CodeNode> serviceNodes = findServiceNodes(nodes);
        List<Map<String, Object>> result = new ArrayList<>();

        for (CodeNode svc : serviceNodes.values()) {
            String name = svc.getLabel();
            int in = inDegree.getOrDefault(name, 0);
            int out = outDegree.getOrDefault(name, 0);
            int total = in + out;
            if (total > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(PROP_SERVICE, name);
                entry.put("connections_in", in);
                entry.put("connections_out", out);
                entry.put(PROP_TOTAL_CONNECTIONS, total);
                result.add(entry);
            }
        }

        // Sort by total connections descending
        result.sort((a, b) -> Integer.compare(
                (int) b.get(PROP_TOTAL_CONNECTIONS),
                (int) a.get(PROP_TOTAL_CONNECTIONS)));
        return result;
    }

    /**
     * Circular service-to-service dependencies.
     */
    public List<List<String>> findCircularDeps(List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        List<Map<String, Object>> connections = findCrossServiceConnections(edges, nodeToService);

        // Build adjacency
        Map<String, Set<String>> adj = new HashMap<>();
        for (Map<String, Object> conn : connections) {
            String src = (String) conn.get(PROP_SOURCE);
            String tgt = (String) conn.get(PROP_TARGET);
            adj.computeIfAbsent(src, k -> new LinkedHashSet<>()).add(tgt);
        }

        // Find cycles using DFS
        List<List<String>> cycles = new ArrayList<>();
        Set<String> seenCycles = new HashSet<>();
        Set<String> allServices = new LinkedHashSet<>(adj.keySet());
        for (Map<String, Object> conn : connections) {
            allServices.add((String) conn.get(PROP_TARGET));
        }

        Set<String> globalVisited = new HashSet<>();

        for (String start : sorted(allServices)) {
            Set<String> inStack = new LinkedHashSet<>();
            List<String> stack = new ArrayList<>();
            dfsFindCycles(start, adj, inStack, stack, cycles, seenCycles, globalVisited);
        }

        return cycles;
    }

    private void dfsFindCycles(String node, Map<String, Set<String>> adj,
                               Set<String> inStack, List<String> stack,
                               List<List<String>> cycles, Set<String> seenCycles,
                               Set<String> globalVisited) {
        if (inStack.contains(node)) {
            // Found a cycle
            int idx = stack.indexOf(node);
            if (idx >= 0) {
                List<String> cycle = new ArrayList<>(stack.subList(idx, stack.size()));
                cycle.add(node);
                // Normalize: start from lexicographically smallest
                int minIdx = 0;
                for (int i = 1; i < cycle.size() - 1; i++) {
                    if (cycle.get(i).compareTo(cycle.get(minIdx)) < 0) {
                        minIdx = i;
                    }
                }
                List<String> normalized = new ArrayList<>();
                for (int i = 0; i < cycle.size() - 1; i++) {
                    normalized.add(cycle.get((minIdx + i) % (cycle.size() - 1)));
                }
                normalized.add(normalized.getFirst());
                String cycleKey = String.join("->", normalized);
                if (seenCycles.add(cycleKey)) {
                    cycles.add(normalized);
                }
            }
            return;
        }
        if (globalVisited.contains(node)) return;

        inStack.add(node);
        stack.add(node);

        for (String neighbor : sorted(adj.getOrDefault(node, Set.of()))) {
            dfsFindCycles(neighbor, adj, inStack, stack, cycles, seenCycles, globalVisited);
        }

        inStack.remove(node);
        stack.removeLast();
        globalVisited.add(node);
    }

    /**
     * Orphan services with no incoming connections.
     */
    public List<Map<String, Object>> findDeadServices(List<CodeNode> nodes, List<CodeEdge> edges) {
        Map<String, String> nodeToService = buildNodeToServiceMap(nodes);
        List<Map<String, Object>> connections = findCrossServiceConnections(edges, nodeToService);
        Map<String, CodeNode> serviceNodes = findServiceNodes(nodes);

        Set<String> hasIncoming = connections.stream()
                .map(c -> (String) c.get(PROP_TARGET))
                .collect(Collectors.toSet());

        List<Map<String, Object>> result = new ArrayList<>();
        for (CodeNode svc : sortedValues(serviceNodes)) {
            if (!hasIncoming.contains(svc.getLabel())) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(PROP_SERVICE, svc.getLabel());
                entry.put(PROP_ENDPOINT_COUNT, svc.getProperties().getOrDefault(PROP_ENDPOINT_COUNT, 0));
                entry.put(PROP_ENTITY_COUNT, svc.getProperties().getOrDefault(PROP_ENTITY_COUNT, 0));
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Find nodes by label — exact match priority, then partial match.
     */
    public List<Map<String, Object>> findNode(String query, List<CodeNode> nodes) {
        if (query == null || query.isBlank()) return List.of();

        String lowerQuery = query.toLowerCase();
        List<Map<String, Object>> exact = new ArrayList<>();
        List<Map<String, Object>> partial = new ArrayList<>();

        for (CodeNode node : nodes) {
            String label = node.getLabel();
            if (label == null) continue;

            if (label.equalsIgnoreCase(query)) {
                exact.add(nodeToCompact(node));
            } else if (label.toLowerCase().contains(lowerQuery)
                    || (node.getId() != null && node.getId().toLowerCase().contains(lowerQuery))) {
                partial.add(nodeToCompact(node));
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(exact);
        // Limit partial matches to avoid flooding
        int remaining = Math.min(partial.size(), 50 - exact.size());
        if (remaining > 0) {
            result.addAll(partial.subList(0, remaining));
        }
        return result;
    }

    // --- Internal helpers ---

    private Map<String, CodeNode> findServiceNodes(List<CodeNode> nodes) {
        Map<String, CodeNode> services = new LinkedHashMap<>();
        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.SERVICE) {
                services.put(node.getLabel(), node);
            }
        }
        return services;
    }

    private Map<String, String> buildNodeToServiceMap(List<CodeNode> nodes) {
        Map<String, String> map = new HashMap<>();
        for (CodeNode node : nodes) {
            Object svc = node.getProperties().get(PROP_SERVICE);
            if (svc instanceof String s && !s.isBlank()) {
                map.put(node.getId(), s);
            }
        }
        return map;
    }

    /**
     * Find edges that cross service boundaries (source and target in different services).
     * Only considers runtime edge kinds.
     */
    private List<Map<String, Object>> findCrossServiceConnections(
            List<CodeEdge> edges, Map<String, String> nodeToService) {
        // Deduplicate connections at the service level
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> connections = new ArrayList<>();

        for (CodeEdge edge : edges) {
            if (!RUNTIME_EDGES.contains(edge.getKind())) continue;

            String sourceId = edge.getSourceId();
            String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
            if (sourceId == null || targetId == null) continue;

            String sourceSvc = nodeToService.get(sourceId);
            String targetSvc = nodeToService.get(targetId);

            if (sourceSvc != null && targetSvc != null && !sourceSvc.equals(targetSvc)) {
                String key = sourceSvc + "->" + targetSvc + ":" + edge.getKind().getValue();
                if (seen.add(key)) {
                    Map<String, Object> conn = new LinkedHashMap<>();
                    conn.put(PROP_SOURCE, sourceSvc);
                    conn.put(PROP_TARGET, targetSvc);
                    conn.put(PROP_TYPE, edge.getKind().getValue());
                    connections.add(conn);
                }
            }
        }
        return connections;
    }

    private Map<String, Object> nodeToCompact(CodeNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", node.getId());
        m.put("kind", node.getKind().getValue());
        m.put("label", node.getLabel());
        if (node.getFilePath() != null) m.put("file_path", node.getFilePath());
        if (node.getLayer() != null) m.put("layer", node.getLayer());
        Object svc = node.getProperties().get(PROP_SERVICE);
        if (svc != null) m.put(PROP_SERVICE, svc);
        return m;
    }

    private static List<CodeNode> sortedValues(Map<String, CodeNode> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }

    private static String serviceOf(CodeNode node) {
        Object svc = node.getProperties().get(PROP_SERVICE);
        return svc instanceof String s ? s : null;
    }
}
