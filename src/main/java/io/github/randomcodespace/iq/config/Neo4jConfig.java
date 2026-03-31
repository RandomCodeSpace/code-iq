package io.github.randomcodespace.iq.config;

import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.graphdb.GraphDatabaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

import java.nio.file.Path;

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
    DatabaseManagementService databaseManagementService(CodeIqConfig config) {
        return new DatabaseManagementServiceBuilder(Path.of(config.getGraph().getPath()))
                .setConfig(BoltConnector.enabled, true)
                .setConfig(BoltConnector.listen_address, new SocketAddress("localhost", boltPort))
                .build();
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
