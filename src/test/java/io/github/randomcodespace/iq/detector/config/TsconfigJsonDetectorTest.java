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

class TsconfigJsonDetectorTest {

    private final TsconfigJsonDetector detector = new TsconfigJsonDetector();

    @Test
    void positiveMatch() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of(
                        "extends", "@tsconfig/node18/tsconfig.json",
                        "compilerOptions", Map.of(
                                "strict", true,
                                "target", "ES2022",
                                "outDir", "./dist"
                        ),
                        "references", List.of(Map.of("path", "./packages/core"))
                )
        );
        DetectorContext ctx = new DetectorContext("tsconfig.json", "json", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 config file + 3 compiler options = 4 nodes
        assertEquals(4, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_FILE));
        // 1 extends + 1 reference + 3 contains = 5 edges
        assertEquals(5, result.edges().size());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void negativeMatch_notTsconfig() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of("key", "value")
        );
        DetectorContext ctx = new DetectorContext("config.json", "json", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of("compilerOptions", Map.of("strict", true))
        );
        DetectorContext ctx = new DetectorContext("tsconfig.json", "json", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
