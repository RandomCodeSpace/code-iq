package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DockerComposeDetectorTest {

    private final DockerComposeDetector detector = new DockerComposeDetector();

    @Test
    void positiveMatch() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("services", Map.of(
                        "web", Map.of("image", "nginx", "ports", List.of("8080:80")),
                        "db", Map.of("image", "postgres")
                ))
        );
        DetectorContext ctx = new DetectorContext("docker-compose.yml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INFRA_RESOURCE));
        // 2 services + 1 port = 3 nodes
        assertEquals(3, result.nodes().size());
    }

    @Test
    void dependsOnEdges() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("services", Map.of(
                        "web", Map.of("image", "nginx", "depends_on", List.of("db")),
                        "db", Map.of("image", "postgres")
                ))
        );
        DetectorContext ctx = new DetectorContext("docker-compose.yml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.DEPENDS_ON, result.edges().getFirst().getKind());
    }

    @Test
    void negativeMatch_notComposeFile() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("key", "value")
        );
        DetectorContext ctx = new DetectorContext("config.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("services", Map.of(
                        "web", Map.of("image", "nginx"),
                        "db", Map.of("image", "postgres")
                ))
        );
        DetectorContext ctx = new DetectorContext("docker-compose.yml", "yaml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
