package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.query.QueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Query graph relationships.
 */
@Component
@Command(name = "query", mixinStandardHelpOptions = true,
        description = "Query graph relationships")
public class QueryCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = "--consumers-of", description = "Find consumers of a node")
    private String consumersOf;

    @Option(names = "--producers-of", description = "Find producers of a node")
    private String producersOf;

    @Option(names = "--callers-of", description = "Find callers of a node")
    private String callersOf;

    @Option(names = "--dependencies-of", description = "Find dependencies of a module")
    private String dependenciesOf;

    @Option(names = "--dependents-of", description = "Find dependents of a module")
    private String dependentsOf;

    @Option(names = "--cycles", description = "Find dependency cycles")
    private boolean cycles;

    @Option(names = "--shortest-path", arity = "2", description = "Find shortest path between two nodes")
    private String[] shortestPath;

    @Option(names = {"--limit"}, defaultValue = "100", description = "Result limit (default: 100)")
    private int limit;

    private final QueryService queryService;

    @Autowired
    public QueryCommand(Optional<QueryService> queryService) {
        this.queryService = queryService.orElse(null);
    }

    /** Convenience constructor for tests. */
    QueryCommand(QueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public Integer call() {
        if (queryService == null) {
            CliOutput.error("Graph queries require Neo4j. Run 'code-iq enrich' first, then 'code-iq serve'.");
            return 1;
        }
        if (consumersOf != null) {
            return printResult("Consumers of " + consumersOf, queryService.consumersOf(consumersOf));
        }
        if (producersOf != null) {
            return printResult("Producers of " + producersOf, queryService.producersOf(producersOf));
        }
        if (callersOf != null) {
            return printResult("Callers of " + callersOf, queryService.callersOf(callersOf));
        }
        if (dependenciesOf != null) {
            return printResult("Dependencies of " + dependenciesOf, queryService.dependenciesOf(dependenciesOf));
        }
        if (dependentsOf != null) {
            return printResult("Dependents of " + dependentsOf, queryService.dependentsOf(dependentsOf));
        }
        if (cycles) {
            return printResult("Dependency cycles", queryService.findCycles(limit));
        }
        if (shortestPath != null && shortestPath.length == 2) {
            Map<String, Object> result = queryService.shortestPath(shortestPath[0], shortestPath[1]);
            if (result == null) {
                CliOutput.warn("No path found between " + shortestPath[0] + " and " + shortestPath[1]);
                return 1;
            }
            return printResult("Shortest path", result);
        }

        CliOutput.warn("No query option specified. Use --help for available options.");
        return 1;
    }

    @SuppressWarnings("unchecked")
    private int printResult(String title, Map<String, Object> result) {
        CliOutput.bold(title);
        System.out.println();

        if (result == null) {
            CliOutput.warn("No results found.");
            return 1;
        }

        // Print count if available
        Object count = result.get("count");
        if (count != null) {
            CliOutput.info("  Results: " + count);
        }

        // Print node lists
        for (String key : List.of("consumers", "producers", "callers", "dependencies",
                "dependents", "impacted", "nodes")) {
            Object val = result.get(key);
            if (val instanceof List<?> list && !list.isEmpty()) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        printNodeSummary((Map<String, Object>) map);
                    }
                }
            }
        }

        // Print path
        Object pathVal = result.get("path");
        if (pathVal instanceof List<?> pathList) {
            CliOutput.info("  Path (" + result.getOrDefault("length", "?") + " hops):");
            for (Object step : pathList) {
                CliOutput.info("    -> " + step);
            }
        }

        // Print cycles
        Object cyclesVal = result.get("cycles");
        if (cyclesVal instanceof List<?> cycleList) {
            for (Object cycle : cycleList) {
                if (cycle instanceof List<?> c) {
                    CliOutput.info("  " + String.join(" -> ", c.stream()
                            .map(Object::toString).toList()));
                }
            }
        }

        return 0;
    }

    private void printNodeSummary(Map<String, Object> node) {
        String id = String.valueOf(node.getOrDefault("id", "?"));
        String kind = String.valueOf(node.getOrDefault("kind", "?"));
        String label = String.valueOf(node.getOrDefault("label", ""));
        CliOutput.info("  " + kind + "  " + id + "  " + label);
    }
}
