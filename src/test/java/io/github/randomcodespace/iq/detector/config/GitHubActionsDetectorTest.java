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

class GitHubActionsDetectorTest {

    private final GitHubActionsDetector detector = new GitHubActionsDetector();

    @Test
    void positiveMatch() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "name", "CI",
                        "on", Map.of("push", Map.of()),
                        "jobs", Map.of("build", Map.of("runs-on", "ubuntu-latest"))
                )
        );
        DetectorContext ctx = new DetectorContext(".github/workflows/ci.yml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 workflow MODULE + 1 trigger + 1 job
        assertEquals(3, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD));
    }

    @Test
    void jobDependencies() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "name", "CI",
                        "on", "push",
                        "jobs", Map.of(
                                "build", Map.of("runs-on", "ubuntu-latest"),
                                "deploy", Map.of("runs-on", "ubuntu-latest", "needs", "build")
                        )
                )
        );
        DetectorContext ctx = new DetectorContext(".github/workflows/ci.yml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void negativeMatch_notWorkflowPath() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("name", "CI", "on", "push")
        );
        DetectorContext ctx = new DetectorContext("config.yml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "name", "CI",
                        "on", List.of("push", "pull_request"),
                        "jobs", Map.of("build", Map.of("runs-on", "ubuntu-latest"))
                )
        );
        DetectorContext ctx = new DetectorContext(".github/workflows/ci.yml", "yaml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
