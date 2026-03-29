package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiDetectorTest {

    private final OpenApiDetector detector = new OpenApiDetector();

    @Test
    void positiveMatch_openapi3() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of(
                        "openapi", "3.0.0",
                        "info", Map.of("title", "Pet Store", "version", "1.0"),
                        "paths", Map.of(
                                "/pets", Map.of(
                                        "get", Map.of("summary", "List pets", "operationId", "listPets"),
                                        "post", Map.of("summary", "Create pet")
                                )
                        ),
                        "components", Map.of("schemas", Map.of(
                                "Pet", Map.of("type", "object"),
                                "Error", Map.of("type", "object")
                        ))
                )
        );
        DetectorContext ctx = new DetectorContext("api.json", "json", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 config_file + 2 endpoints + 2 schemas = 5
        assertEquals(5, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENTITY));
    }

    @Test
    void schemaReferences() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "openapi", "3.0.0",
                        "info", Map.of("title", "API", "version", "1.0"),
                        "paths", Map.of(),
                        "components", Map.of("schemas", Map.of(
                                "Order", Map.of("type", "object",
                                        "properties", Map.of("customer",
                                                Map.of("$ref", "#/components/schemas/Customer"))),
                                "Customer", Map.of("type", "object")
                        ))
                )
        );
        DetectorContext ctx = new DetectorContext("api.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void negativeMatch_notOpenApi() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of("name", "not-openapi")
        );
        DetectorContext ctx = new DetectorContext("config.json", "json", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of(
                        "openapi", "3.0.0",
                        "info", Map.of("title", "API", "version", "1.0"),
                        "paths", Map.of("/health", Map.of("get", Map.of()))
                )
        );
        DetectorContext ctx = new DetectorContext("api.json", "json", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
