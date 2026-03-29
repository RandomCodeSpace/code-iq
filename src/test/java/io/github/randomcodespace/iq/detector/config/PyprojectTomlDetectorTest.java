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

class PyprojectTomlDetectorTest {

    private final PyprojectTomlDetector detector = new PyprojectTomlDetector();

    @Test
    void positiveMatch_pep621() {
        Map<String, Object> parsedData = Map.of(
                "type", "toml",
                "data", Map.of(
                        "project", Map.of(
                                "name", "my-pkg",
                                "version", "0.1.0",
                                "dependencies", List.of("requests>=2.0", "click"),
                                "scripts", Map.of("cli", "my_pkg.main:app")
                        )
                )
        );
        DetectorContext ctx = new DetectorContext("pyproject.toml", "toml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
        assertEquals(2, result.edges().stream().filter(e -> e.getKind() == EdgeKind.DEPENDS_ON).count());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_DEFINITION));
    }

    @Test
    void negativeMatch_notPyproject() {
        Map<String, Object> parsedData = Map.of(
                "type", "toml",
                "data", Map.of("key", "value")
        );
        DetectorContext ctx = new DetectorContext("config.toml", "toml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void parseDepName_extractsCorrectly() {
        assertEquals("requests", PyprojectTomlDetector.parseDepName("requests>=2.0"));
        assertEquals("black", PyprojectTomlDetector.parseDepName("black[jupyter]>=22.0"));
        assertEquals("numpy", PyprojectTomlDetector.parseDepName("numpy"));
        assertNull(PyprojectTomlDetector.parseDepName(""));
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "toml",
                "data", Map.of("project", Map.of("name", "pkg", "version", "1.0"))
        );
        DetectorContext ctx = new DetectorContext("pyproject.toml", "toml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
