package io.github.randomcodespace.iq.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.randomcodespace.iq.analyzer.ServiceDetector;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import io.github.randomcodespace.iq.query.TopologyService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Show service topology map from the analysis cache.
 */
@Component
@Command(name = "topology", mixinStandardHelpOptions = true,
        description = "Show service topology map")
public class TopologyCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--format", "-f"}, defaultValue = "pretty",
            description = "Output format: pretty, json (default: pretty)")
    private String format;

    @Option(names = {"--service", "-s"}, description = "Show detail for a specific service")
    private String service;

    @Option(names = {"--deps"}, description = "Show dependencies for a service")
    private String deps;

    @Option(names = {"--blast-radius"}, description = "Show blast radius for a node ID")
    private String blastRadius;

    private final CodeIqConfig config;
    private final TopologyService topologyService;

    public TopologyCommand(CodeIqConfig config, TopologyService topologyService) {
        this.config = config;
        this.topologyService = topologyService;
    }

    @Override
    public Integer call() {
        Path root = path.toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!Files.exists(h2File)) {
            CliOutput.error("No analysis cache found at " + h2File.getParent());
            CliOutput.info("  Run 'code-iq index " + root + "' and 'code-iq enrich " + root + "' first.");
            return 1;
        }

        List<CodeNode> nodes;
        List<CodeEdge> edges;
        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            nodes = cache.loadAllNodes();
            edges = cache.loadAllEdges();
        } catch (Exception e) {
            CliOutput.error("Failed to load analysis cache: " + e.getMessage());
            return 1;
        }

        // Check if service nodes exist; if not, run ServiceDetector
        boolean hasServices = nodes.stream().anyMatch(n -> n.getKind() == NodeKind.SERVICE);
        if (!hasServices) {
            String projectName = java.util.Objects.toString(root.getFileName(), "unknown");
            var detector = new ServiceDetector();
            var result = detector.detect(nodes, edges, projectName);
            nodes.addAll(result.serviceNodes());
            edges.addAll(result.serviceEdges());
        }

        try {
            Object result;
            if (service != null) {
                result = topologyService.serviceDetail(service, nodes, edges);
            } else if (deps != null) {
                result = topologyService.serviceDependencies(deps, nodes, edges);
            } else if (blastRadius != null) {
                result = topologyService.blastRadius(blastRadius, nodes, edges);
            } else {
                result = topologyService.getTopology(nodes, edges);
            }

            if ("json".equalsIgnoreCase(format)) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                System.out.println(mapper.writeValueAsString(result));
            } else {
                printPretty(result);
            }
            return 0;
        } catch (Exception e) {
            CliOutput.error("Topology analysis failed: " + e.getMessage());
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private void printPretty(Object result) {
        if (!(result instanceof Map<?, ?> map)) {
            System.out.println(result);
            return;
        }

        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        if (map.containsKey("services") && map.containsKey("connections")) {
            // Topology overview
            List<Map<String, Object>> services = (List<Map<String, Object>>) map.get("services");
            List<Map<String, Object>> connections = (List<Map<String, Object>>) map.get("connections");

            System.out.println();
            CliOutput.bold("Service Topology");
            System.out.println();

            for (Map<String, Object> svc : services) {
                String name = (String) svc.get("name");
                Object endpoints = svc.get("endpoint_count");
                Object entities = svc.get("entity_count");
                Object connOut = svc.get("connections_out");
                Object connIn = svc.get("connections_in");
                CliOutput.cyan("  " + name);
                CliOutput.info("    endpoints: " + endpoints
                        + " | entities: " + entities
                        + " | out: " + connOut + " | in: " + connIn);
            }

            if (!connections.isEmpty()) {
                System.out.println();
                CliOutput.bold("  Connections:");
                for (Map<String, Object> conn : connections) {
                    CliOutput.info("    " + conn.get("source") + " --["
                            + conn.get("type") + "]--> " + conn.get("target"));
                }
            }
            System.out.println();
            CliOutput.info("  " + nf.format(services.size()) + " services, "
                    + nf.format(connections.size()) + " connections");
        } else {
            // Generic map output
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                System.out.println(mapper.writeValueAsString(result));
            } catch (Exception e) {
                System.out.println(result);
            }
        }
    }
}
