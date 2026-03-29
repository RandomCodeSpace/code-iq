package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TomlStructureDetectorTest {

    private final TomlStructureDetector detector = new TomlStructureDetector();

    @Test
    void positiveMatch() {
        Map<String, Object> parsedData = Map.of(
                "type", "toml",
                "data", Map.of(
                        "title", "My Config",
                        "database", Map.of("host", "localhost", "port", 5432)
                )
        );
        DetectorContext ctx = new DetectorContext("config.toml", "toml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 file + 2 keys
        assertEquals(3, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_FILE));
        // database key should have section=true
        var dbNode = result.nodes().stream()
                .filter(n -> "database".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertEquals(true, dbNode.getProperties().get("section"));
    }

    @Test
    void negativeMatch_noParsedData() {
        DetectorContext ctx = new DetectorContext("config.toml", "toml", "", null, null);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "toml",
                "data", Map.of("a", "1", "b", Map.of("c", "2"))
        );
        DetectorContext ctx = new DetectorContext("test.toml", "toml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
