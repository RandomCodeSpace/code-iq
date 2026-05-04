package io.github.randomcodespace.iq.config;

import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.io.ByteUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

/**
 * Neo4j Embedded configuration.
 *
 * Configures a file-based Neo4j embedded instance using {@link DatabaseManagementService}
 * with a Bolt connector enabled so Spring Data Neo4j repositories work via the Bolt driver.
 *
 * Disabled when {@code codeiq.neo4j.enabled} is explicitly set to {@code false}
 * (e.g. in tests that do not need an embedded database).
 */
@Configuration
@ConditionalOnProperty(name = "codeiq.neo4j.enabled", havingValue = "true", matchIfMissing = true)
@EnableNeo4jRepositories(basePackages = "io.github.randomcodespace.iq.graph")
public class Neo4jConfig {

    @Value("${codeiq.neo4j.bolt.port:7688}")
    private int boltPort;

    @Bean(destroyMethod = "shutdown")
    DatabaseManagementService databaseManagementService(CodeIqConfig config, Environment env) {
        var builder = new DatabaseManagementServiceBuilder(Path.of(config.getGraph().getPath()))
                .setConfig(BoltConnector.enabled, true)
                .setConfig(BoltConnector.listen_address, new SocketAddress("localhost", boltPort))
                // Hard wall-clock cap on every transaction. Prevents a runaway Cypher
                // (e.g. unbounded variable-length match on a hub node) from hogging
                // the page cache and starving readiness/liveness probes. Audit
                // finding #2 (HIGH) — runs alongside per-tool timeouts in McpTools.
                .setConfig(GraphDatabaseSettings.transaction_timeout, Duration.ofSeconds(30))
                // Bound Neo4j off-heap page cache. Without an explicit cap, embedded
                // Neo4j auto-sizes the page cache to ~50% of free RAM at startup,
                // which races the JVM heap inside an AKS pod (single cgroup memory
                // limit) and triggers OOMKilled before either side stabilises. Sized
                // for the read-only serving workload — the ~200 K-node graphs we
                // ship today fit comfortably in 256 MB; lift this if your store
                // grows past ~500 MB on disk.
                .setConfig(GraphDatabaseSettings.pagecache_memory, ByteUnit.mebiBytes(256));

        // Read-only mode for serving profile — no lock files, no transaction logs.
        // Required for read-only filesystems (e.g., AKS with read-only volumes).
        boolean isServing = Arrays.asList(env.getActiveProfiles()).contains("serving");
        if (isServing && config.isReadOnly()) {
            builder.setConfig(GraphDatabaseSettings.read_only_database_default, true);
        }

        return builder.build();
    }

    @Bean
    GraphDatabaseService graphDatabaseService(DatabaseManagementService dbms) {
        return dbms.database("neo4j");
    }

    /**
     * Provide the Neo4j Java Driver pointing to the embedded Bolt connector.
     * This replaces the default auto-configured driver that would look for
     * spring.neo4j.uri configuration.
     */
    @Bean(destroyMethod = "close")
    Driver neo4jDriver() {
        return GraphDatabase.driver(
                "bolt://localhost:" + boltPort,
                AuthTokens.none());
    }
}
