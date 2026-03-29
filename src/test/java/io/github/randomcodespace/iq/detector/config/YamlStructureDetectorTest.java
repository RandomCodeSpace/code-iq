package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlStructureDetectorTest {

    private final YamlStructureDetector detector = new YamlStructureDetector();

    @Test
    void positiveMatch_singleDoc() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("name", "app", "version", "1.0")
        );
        DetectorContext ctx = new DetectorContext("config.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 file node + 2 key nodes
        assertEquals(3, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_FILE));
    }

    @Test
    void positiveMatch_multiDoc() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml_multi",
                "documents", List.of(
                        Map.of("key1", "val"),
                        Map.of("key2", "val")
                )
        );
        DetectorContext ctx = new DetectorContext("multi.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 file + 2 keys
        assertEquals(3, result.nodes().size());
    }

    @Test
    void negativeMatch_noParsedData() {
        DetectorContext ctx = new DetectorContext("config.yaml", "yaml", "", null, null);
        DetectorResult result = detector.detect(ctx);

        // Still produces file node
        assertEquals(1, result.nodes().size());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("a", "1", "b", "2")
        );
        DetectorContext ctx = new DetectorContext("test.yaml", "yaml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
