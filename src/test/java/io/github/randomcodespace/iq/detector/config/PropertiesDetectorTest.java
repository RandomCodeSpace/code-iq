package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesDetectorTest {

    private final PropertiesDetector detector = new PropertiesDetector();

    @Test
    void positiveMatch_springConfig() {
        Map<String, Object> parsedData = Map.of(
                "type", "properties",
                "data", Map.of(
                        "spring.datasource.url", "jdbc:mysql://localhost/db",
                        "spring.datasource.username", "root",
                        "server.port", "8080"
                )
        );
        DetectorContext ctx = new DetectorContext("application.properties", "properties", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 file + 3 keys
        assertEquals(4, result.nodes().size());
        // datasource.url with jdbc: value -> DATABASE_CONNECTION
        var dbNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.DATABASE_CONNECTION)
                .findFirst().orElseThrow();
        assertEquals("MySQL", dbNode.getLabel(), "Label should be the DB type, not the config key");
        assertEquals("MySQL", dbNode.getProperties().get("db_type"));

        // spring.datasource.username is NOT a DATABASE_CONNECTION (no jdbc: in value)
        var usernameNode = result.nodes().stream()
                .filter(n -> "spring.datasource.username".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertEquals(NodeKind.CONFIG_KEY, usernameNode.getKind(),
                "Non-URL datasource keys should be CONFIG_KEY, not DATABASE_CONNECTION");

        // Check server.port has no spring_config marker (it doesn't start with "spring.")
        var portNode = result.nodes().stream()
                .filter(n -> "server.port".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertNull(portNode.getProperties().get("spring_config"));
    }

    @Test
    void dbConnectionSetsDbTypeFromJdbcUrl() {
        Map<String, Object> parsedData = Map.of(
                "type", "properties",
                "data", Map.of(
                        "spring.datasource.url", "jdbc:postgresql://db-host:5432/mydb",
                        "spring.datasource.password", "secret",
                        "spring.datasource.driver-class-name", "org.postgresql.Driver"
                )
        );
        DetectorContext ctx = new DetectorContext("application.properties", "properties", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // Only the url key should be DATABASE_CONNECTION
        var dbNodes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.DATABASE_CONNECTION)
                .toList();
        assertEquals(1, dbNodes.size(), "Only the URL key should produce a DATABASE_CONNECTION");
        assertEquals("PostgreSQL", dbNodes.getFirst().getLabel());
        assertEquals("PostgreSQL", dbNodes.getFirst().getProperties().get("db_type"));

        // password and driver-class-name should be CONFIG_KEY
        var configKeys = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CONFIG_KEY)
                .toList();
        assertTrue(configKeys.stream().anyMatch(n -> n.getLabel().contains("password")),
                "spring.datasource.password should be CONFIG_KEY");
        assertTrue(configKeys.stream().anyMatch(n -> n.getLabel().contains("driver-class-name")),
                "spring.datasource.driver-class-name should be CONFIG_KEY");
    }

    @Test
    void nonJdbcUrlKeyIsConfigKey() {
        // Keys that contain "datasource" but are not URLs should NOT be DATABASE_CONNECTION
        Map<String, Object> parsedData = Map.of(
                "type", "properties",
                "data", Map.of(
                        "spring.datasource.hikari.maximum-pool-size", "10",
                        "spring.datasource.username", "admin"
                )
        );
        DetectorContext ctx = new DetectorContext("application.properties", "properties", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().noneMatch(n -> n.getKind() == NodeKind.DATABASE_CONNECTION),
                "Non-URL datasource keys should not produce DATABASE_CONNECTION nodes");
    }

    @Test
    void negativeMatch_wrongType() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("key", "value")
        );
        DetectorContext ctx = new DetectorContext("app.properties", "properties", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "properties",
                "data", Map.of("key1", "val1", "key2", "val2")
        );
        DetectorContext ctx = new DetectorContext("app.properties", "properties", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
