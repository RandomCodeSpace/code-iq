package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackageJsonDetectorTest {

    private final PackageJsonDetector detector = new PackageJsonDetector();

    @Test
    void positiveMatch() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of(
                        "name", "my-app",
                        "version", "1.0.0",
                        "dependencies", Map.of("express", "^4.18.0"),
                        "scripts", Map.of("start", "node index.js", "test", "jest")
                )
        );
        DetectorContext ctx = new DetectorContext("package.json", "json", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 module + 2 scripts = 3 nodes
        assertEquals(3, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void negativeMatch_notPackageJson() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of("name", "my-app")
        );
        DetectorContext ctx = new DetectorContext("config.json", "json", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of("name", "pkg", "version", "1.0.0")
        );
        DetectorContext ctx = new DetectorContext("package.json", "json", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
