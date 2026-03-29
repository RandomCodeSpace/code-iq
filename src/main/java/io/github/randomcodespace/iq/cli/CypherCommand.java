package io.github.randomcodespace.iq.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Execute raw Cypher queries against the Neo4j embedded graph.
 *
 * Note: This command requires the Neo4j backend. In future, when additional
 * backends are supported, this command will validate backend compatibility.
 */
@Component
@Command(name = "cypher", mixinStandardHelpOptions = true,
        description = "Execute raw Cypher queries (Neo4j backend only)")
public class CypherCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Cypher query string")
    private String query;

    @Option(names = {"--limit"}, defaultValue = "100",
            description = "Result limit (default: 100)")
    private int limit;

    @Override
    public Integer call() {
        // Cypher execution requires direct Neo4j GraphDatabaseService access.
        // For now, this command reports that Cypher queries are available
        // via the REST API or MCP tools.
        CliOutput.warn("Direct Cypher execution from CLI is not yet implemented.");
        CliOutput.info("Use the REST API instead:");
        CliOutput.info("  curl -X POST http://localhost:8080/api/cypher \\");
        CliOutput.info("    -H 'Content-Type: application/json' \\");
        CliOutput.info("    -d '{\"query\": \"" + query + "\", \"limit\": " + limit + "}'");
        CliOutput.info("");
        CliOutput.info("Or start the server with: code-iq serve");
        return 0;
    }
}
