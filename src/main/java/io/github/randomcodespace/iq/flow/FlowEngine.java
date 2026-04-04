package io.github.randomcodespace.iq.flow;

import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.model.CodeNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Core engine for generating and rendering architecture flow diagrams.
 *
 * <p>Works with any {@link FlowDataSource} -- either Neo4j (via GraphStore)
 * or H2 cache (via CacheFlowDataSource). Not a Spring bean -- created
 * manually by FlowCommand, FlowController, and BundleCommand.</p>
 */
public class FlowEngine {
    private static final String PROP_AUTH = "auth";
    private static final String PROP_CI = "ci";
    private static final String PROP_DEPLOY = "deploy";
    private static final String PROP_OVERVIEW = "overview";
    private static final String PROP_RUNTIME = "runtime";


    public static final List<String> AVAILABLE_VIEWS = List.of(PROP_OVERVIEW, PROP_CI, PROP_DEPLOY, PROP_RUNTIME, PROP_AUTH);

    private static final Map<String, Function<FlowDataSource, FlowDiagram>> VIEW_BUILDERS = Map.of(
            PROP_OVERVIEW, FlowViews::buildOverview,
            PROP_CI, FlowViews::buildCiView,
            PROP_DEPLOY, FlowViews::buildDeployView,
            PROP_RUNTIME, FlowViews::buildRuntimeView,
            PROP_AUTH, FlowViews::buildAuthView
    );

    private final FlowDataSource dataSource;

    public FlowEngine(FlowDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Create a FlowEngine backed by H2 cache data (no Neo4j required).
     */
    public static FlowEngine fromCache(List<CodeNode> nodes) {
        return new FlowEngine(new CacheFlowDataSource(nodes));
    }

    public FlowDiagram generate(String view) {
        var builder = VIEW_BUILDERS.get(view);
        if (builder == null) {
            throw new IllegalArgumentException(
                    "Unknown view: " + view + ". Available: " + String.join(", ", AVAILABLE_VIEWS));
        }
        return builder.apply(dataSource);
    }

    public Map<String, FlowDiagram> generateAll() {
        var result = new LinkedHashMap<String, FlowDiagram>();
        for (var viewName : AVAILABLE_VIEWS) {
            result.put(viewName, generate(viewName));
        }
        return result;
    }

    public String render(FlowDiagram diagram, String format) {
        return switch (format) {
            case "mermaid" -> FlowRenderer.renderMermaid(diagram);
            case "json" -> FlowRenderer.renderJson(diagram);
            default -> throw new IllegalArgumentException(
                    "Unknown format: " + format + ". Available: mermaid, json, html");
        };
    }

    public String renderInteractive(String projectName) {
        var allViews = generateAll();
        var stats = Map.<String, Object>of(
                "total_nodes", dataSource.count(),
                "total_edges", countEdges()
        );
        return FlowRenderer.renderHtml(allViews, stats, projectName);
    }

    public Map<String, Object> getParentContext(String nodeId) {
        for (var viewName : List.of(PROP_CI, PROP_DEPLOY, PROP_RUNTIME, PROP_AUTH)) {
            var diagram = generate(viewName);
            for (var sg : diagram.subgraphs()) {
                for (var node : sg.nodes()) {
                    if (node.id().equals(nodeId)) {
                        var result = new LinkedHashMap<String, Object>();
                        result.put("parent_view", PROP_OVERVIEW);
                        result.put("parent_subgraph", sg.id());
                        result.put("current_view", viewName);
                        return result;
                    }
                }
            }
        }
        return null;
    }

    public Map<String, Object> getChildren(String view, String nodeId) {
        var diagram = generate(view);
        for (var sg : diagram.subgraphs()) {
            if (sg.drillDownView() != null) {
                for (var node : sg.nodes()) {
                    if (node.id().equals(nodeId)) {
                        var childDiagram = generate(sg.drillDownView());
                        var result = new LinkedHashMap<String, Object>();
                        result.put("drill_down_view", sg.drillDownView());
                        result.put("diagram", childDiagram.toMap());
                        return result;
                    }
                }
            }
        }
        return null;
    }

    private long countEdges() {
        return dataSource.findAll().stream()
                .mapToLong(n -> n.getEdges().size())
                .sum();
    }
}
