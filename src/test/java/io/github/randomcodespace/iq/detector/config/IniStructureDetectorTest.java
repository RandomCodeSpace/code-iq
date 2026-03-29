package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IniStructureDetectorTest {

    private final IniStructureDetector detector = new IniStructureDetector();

    @Test
    void positiveMatch() {
        Map<String, Object> parsedData = Map.of(
                "type", "ini",
                "data", Map.of(
                        "database", Map.of("host", "localhost", "port", "5432"),
                        "logging", Map.of("level", "info")
                )
        );
        DetectorContext ctx = new DetectorContext("config.ini", "ini", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 file + 2 sections + 3 keys = 6 nodes
        assertEquals(6, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_FILE));
    }

    @Test
    void negativeMatch_wrongType() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("key", "value")
        );
        DetectorContext ctx = new DetectorContext("config.ini", "ini", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // Just the file node
        assertEquals(1, result.nodes().size());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "ini",
                "data", Map.of("section", Map.of("key", "value"))
        );
        DetectorContext ctx = new DetectorContext("test.ini", "ini", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
