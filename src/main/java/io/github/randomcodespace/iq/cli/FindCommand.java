package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Preset graph queries — find specific kinds of nodes quickly.
 */
@Component
@Command(name = "find", mixinStandardHelpOptions = true,
        description = "Preset graph queries (endpoints, guards, entities, etc.)")
public class FindCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "What to find: endpoints, guards, entities, " +
            "components, middleware, hooks, configs, modules, queries, topics, events")
    private String what;

    @Parameters(index = "1", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--limit"}, defaultValue = "100", description = "Result limit (default: 100)")
    private int limit;

    @Option(names = {"--layer"}, description = "Filter by layer (frontend, backend, infra, shared)")
    private String layer;

    private final GraphStore graphStore;

    /** No-arg constructor for Picocli direct instantiation. */
    public FindCommand() {
        this.graphStore = null;
    }

    @Autowired
    public FindCommand(Optional<GraphStore> graphStore) {
        this.graphStore = graphStore.orElse(null);
    }

    /** Convenience constructor for tests. */
    FindCommand(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    @Override
    public Integer call() {
        if (graphStore == null) {
            CliOutput.error("Find queries require the serve profile (Neo4j). Use 'codeiq serve' to start the server, or 'codeiq stats' for cache-based queries.");
            return 1;
        }
        NodeKind kind = resolveKind(what);
        if (kind == null) {
            CliOutput.error("Unknown find target: " + what);
            CliOutput.info("Available: endpoints, guards, entities, components, " +
                    "middleware, hooks, configs, modules, queries, topics, events");
            return 1;
        }

        List<CodeNode> nodes = graphStore.findByKindPaginated(kind.getValue(), 0, limit);

        if (layer != null && !layer.isBlank()) {
            nodes = nodes.stream()
                    .filter(n -> layer.equalsIgnoreCase(n.getLayer()))
                    .toList();
        }

        if (nodes.isEmpty()) {
            CliOutput.warn("No " + what + " found. Run 'codeiq analyze' first.");
            return 1;
        }

        CliOutput.bold("Found " + nodes.size() + " " + what + ":");
        System.out.println();

        for (CodeNode node : nodes) {
            StringBuilder line = new StringBuilder();
            line.append("  ").append(node.getLabel());
            if (node.getFilePath() != null) {
                line.append("  @|faint (").append(node.getFilePath());
                if (node.getLineStart() != null) {
                    line.append(":").append(node.getLineStart());
                }
                line.append(")|@");
            }
            if (node.getLayer() != null) {
                line.append("  @|cyan [").append(node.getLayer()).append("]|@");
            }
            CliOutput.print(System.out, line.toString());
        }

        return 0;
    }

    static NodeKind resolveKind(String what) {
        if (what == null) return null;
        return switch (what.toLowerCase()) {
            case "endpoints", "endpoint" -> NodeKind.ENDPOINT;
            case "guards", "guard" -> NodeKind.GUARD;
            case "entities", "entity" -> NodeKind.ENTITY;
            case "components", "component" -> NodeKind.COMPONENT;
            case "middleware", "middlewares" -> NodeKind.MIDDLEWARE;
            case "hooks", "hook" -> NodeKind.HOOK;
            case "configs", "config", "config_file", "config_files" -> NodeKind.CONFIG_FILE;
            case "modules", "module" -> NodeKind.MODULE;
            case "queries", "query" -> NodeKind.QUERY;
            case "topics", "topic" -> NodeKind.TOPIC;
            case "events", "event" -> NodeKind.EVENT;
            case "classes", "class" -> NodeKind.CLASS;
            case "methods", "method" -> NodeKind.METHOD;
            case "interfaces", "interface" -> NodeKind.INTERFACE;
            default -> null;
        };
    }
}
