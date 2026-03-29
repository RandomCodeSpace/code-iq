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
        // datasource.url contains "jdbc" -> DATABASE_CONNECTION
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.DATABASE_CONNECTION));
        // spring.datasource.username is spring config
        var springNode = result.nodes().stream()
                .filter(n -> "spring.datasource.username".equals(n.getLabel()))
                .findFirst().orElse(null);
        // datasource.username contains "datasource" -> DATABASE_CONNECTION (not CONFIG_KEY with spring_config)
        // Check server.port has no spring_config marker (it doesn't start with "spring.")
        var portNode = result.nodes().stream()
                .filter(n -> "server.port".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertNull(portNode.getProperties().get("spring_config"));
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
