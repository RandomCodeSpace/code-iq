package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.config.GraphBootstrapper;
import io.github.randomcodespace.iq.graph.GraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Start the web UI + REST API + MCP server.
 * <p>
 * This command expects Neo4j to already be populated by {@code enrich}.
 * It simply starts the Spring Boot web server and blocks until shutdown.
 * <p>
 * Pipeline: {@code index} → {@code enrich} → {@code serve}
 */
@Component
@Command(name = "serve", mixinStandardHelpOptions = true,
        description = "Start web UI + REST API + MCP server")
public class ServeCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ServeCommand.class);

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Server port")
    private int port;

    @Option(names = {"--host"}, defaultValue = "0.0.0.0", description = "Bind address")
    private String host;

    @Option(names = {"--graph"}, description = "Path to shared graph directory (overrides default)")
    private Path graphPath;

    @Option(names = {"--no-ui"}, defaultValue = "false",
            description = "Disable the web UI (React SPA). API and MCP endpoints remain active.")
    private boolean noUi;

    @Option(names = {"--read-only"}, defaultValue = "false",
            description = "Read-only mode: no lock files, no writes. For read-only filesystems (AKS/K8s).")
    private boolean readOnly;

    @Autowired
    private CodeIqConfig config;

    @Autowired(required = false)
    private GraphStore graphStore;

    @Autowired
    private ApplicationEventPublisher events;

    // Optional: only present in the "serving" profile (same conditions as the bean).
    @Autowired(required = false)
    private GraphBootstrapper graphBootstrapper;

    @Override
    public Integer call() {
        Path root = path.toAbsolutePath().normalize();
        config.setRootPath(root.toString());
        if (readOnly) {
            config.setReadOnly(true);
        }
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        // Bootstrap Neo4j from the H2 analysis cache if Neo4j is empty. This is
        // a no-op when enrich has already run (guarded internally by a count>0
        // check) and when the H2 cache file is missing. Must happen before the
        // status report below so the advertised node/edge counts are truthful.
        // See GraphBootstrapper javadoc for why this is not an @EventListener.
        if (graphBootstrapper != null) {
            graphBootstrapper.bootstrapNeo4jFromCache();
        }

        // Report Neo4j graph status
        if (graphStore != null) {
            try {
                long nodeCount = graphStore.count();
                long edgeCount = graphStore.countEdges();
                if (nodeCount > 0) {
                    CliOutput.success("Neo4j graph: " + nf.format(nodeCount) + " nodes, "
                            + nf.format(edgeCount) + " edges");
                } else {
                    CliOutput.warn("Neo4j graph is empty. Run 'code-iq enrich " + root + "' to populate.");
                }
            } catch (Exception e) {
                log.debug("Could not check Neo4j state", e);
                CliOutput.warn("Could not read Neo4j graph. Run 'code-iq enrich' first.");
            }
        }

        CliOutput.step("[OK]", "@|bold,green Server started|@");
        System.out.println();
        if (noUi) {
            CliOutput.info("  Web UI:    disabled (API and MCP active at :" + port + ")");
        } else {
            CliOutput.info("  Web UI:    http://" + host + ":" + port + " (React SPA)");
        }
        CliOutput.info("  REST API:  http://" + host + ":" + port + "/api");
        CliOutput.info("  MCP:       http://" + host + ":" + port + "/mcp");
        CliOutput.info("  Health:    http://" + host + ":" + port + "/actuator/health");
        CliOutput.info("  Codebase:  " + root);
        System.out.println();
        CliOutput.info("Press Ctrl+C to stop.");

        // Publish availability transitions so /actuator/health reports UP (200).
        // This Callable is invoked from a CommandLineRunner that blocks forever
        // (the Thread.join below), so Spring's ApplicationReadyEvent — which
        // normally drives ReadinessState to ACCEPTING_TRAFFIC — never fires.
        // Without this, /actuator/health stays OUT_OF_SERVICE (503) even though
        // the server is accepting and serving traffic. See also: known gap on
        // GraphBootstrapper's @EventListener(ApplicationReadyEvent.class) which
        // is dead for the same reason — out of scope for this fix.
        markReady();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return 0;
    }

    /**
     * Flip availability state to live + accepting traffic. Extracted for
     * testability — callers can verify the right events are published.
     */
    void markReady() {
        AvailabilityChangeEvent.publish(events, this, LivenessState.CORRECT);
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    public Path getPath() { return path; }
    public int getPort() { return port; }
    public String getHost() { return host; }
    public Path getGraphPath() { return graphPath; }
    public boolean isNoUi() { return noUi; }
}
