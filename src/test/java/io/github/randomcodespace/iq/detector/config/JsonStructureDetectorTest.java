package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonStructureDetectorTest {

    private final JsonStructureDetector detector = new JsonStructureDetector();

    @Test
    void positiveMatch() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of("name", "app", "version", "1.0", "main", "index.js")
        );
        DetectorContext ctx = new DetectorContext("config.json", "json", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 file + 3 keys
        assertEquals(4, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_FILE));
        assertEquals(3, result.edges().size());
    }

    @Test
    void negativeMatch_noParsedData() {
        DetectorContext ctx = new DetectorContext("config.json", "json", "", null, null);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of("a", "1", "b", "2")
        );
        DetectorContext ctx = new DetectorContext("test.json", "json", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
