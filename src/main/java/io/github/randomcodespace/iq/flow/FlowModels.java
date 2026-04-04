package io.github.randomcodespace.iq.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Data models for flow diagrams -- the single source of truth for all renderers.
 */
public final class FlowModels {
    private static final String PROP_LABEL = "label";


    private FlowModels() {
    }

    /**
     * A node in a flow diagram (collapsed/summarized from graph nodes).
     */
    public record FlowNode(
            String id,
            String label,
            String kind,
            String style,
            Map<String, Object> properties
    ) {
        public FlowNode(String id, String label, String kind) {
            this(id, label, kind, "default", Map.of());
        }

        public FlowNode(String id, String label, String kind, Map<String, Object> properties) {
            this(id, label, kind, "default", properties);
        }

        public Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put(PROP_LABEL, label);
            m.put("kind", kind);
            m.put("style", style);
            m.put("properties", properties);
            return m;
        }
    }

    /**
     * An edge in a flow diagram.
     */
    public record FlowEdge(
            String source,
            String target,
            String label,
            String style
    ) {
        public FlowEdge(String source, String target) {
            this(source, target, null, "solid");
        }

        public FlowEdge(String source, String target, String label) {
            this(source, target, label, "solid");
        }

        public Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("source", source);
            m.put("target", target);
            m.put(PROP_LABEL, label);
            m.put("style", style);
            return m;
        }
    }

    /**
     * A labeled group of nodes in a flow diagram.
     */
    public record FlowSubgraph(
            String id,
            String label,
            List<FlowNode> nodes,
            String drillDownView,
            String parentView
    ) {
        public FlowSubgraph(String id, String label, List<FlowNode> nodes, String drillDownView) {
            this(id, label, nodes, drillDownView, null);
        }

        public FlowSubgraph(String id, String label, List<FlowNode> nodes) {
            this(id, label, nodes, null, null);
        }

        public Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put(PROP_LABEL, label);
            m.put("drill_down_view", drillDownView);
            m.put("parent_view", parentView);
            m.put("nodes", nodes.stream().map(FlowNode::toMap).toList());
            return m;
        }
    }

    /**
     * A complete flow diagram -- the single source of truth for all renderers.
     */
    public record FlowDiagram(
            String title,
            String view,
            String direction,
            List<FlowSubgraph> subgraphs,
            List<FlowNode> looseNodes,
            List<FlowEdge> edges,
            Map<String, Object> stats
    ) {
        public FlowDiagram(String title, String view) {
            this(title, view, "LR", new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
        }

        /**
         * Return all nodes across subgraphs and loose nodes.
         */
        public List<FlowNode> allNodes() {
            var result = new ArrayList<>(looseNodes);
            for (var sg : subgraphs) {
                result.addAll(sg.nodes());
            }
            return result;
        }

        public Map<String, Object> toMap() {
            var all = allNodes();
            // Collect valid node IDs so edges can be filtered
            Set<String> nodeIds = all.stream().map(FlowNode::id).collect(Collectors.toSet());

            // Filter edges: only include edges where both source and target exist
            var validEdges = edges.stream()
                    .filter(e -> nodeIds.contains(e.source()) && nodeIds.contains(e.target()))
                    .toList();

            var m = new LinkedHashMap<String, Object>();
            m.put("title", title);
            m.put("view", view);
            m.put("direction", direction);
            m.put("subgraphs", subgraphs.stream().map(FlowSubgraph::toMap).toList());
            m.put("loose_nodes", looseNodes.stream().map(FlowNode::toMap).toList());
            m.put("nodes", all.stream().map(FlowNode::toMap).toList());
            m.put("edges", validEdges.stream().map(FlowEdge::toMap).toList());
            m.put("stats", stats);
            return m;
        }
    }
}
