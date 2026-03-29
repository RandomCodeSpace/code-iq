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

class GitLabCiDetectorTest {

    private final GitLabCiDetector detector = new GitLabCiDetector();

    @Test
    void positiveMatch() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "stages", List.of("build", "test", "deploy"),
                        "build_job", Map.of("stage", "build", "script", List.of("docker build .")),
                        "test_job", Map.of("stage", "test", "script", List.of("npm test"),
                                "needs", List.of("build_job"))
                )
        );
        DetectorContext ctx = new DetectorContext(".gitlab-ci.yml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        // 1 pipeline + 3 stages + 2 jobs = 6 nodes
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void toolDetection() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "build_job", Map.of("script", List.of("docker build .", "helm package ."))
                )
        );
        DetectorContext ctx = new DetectorContext(".gitlab-ci.yml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        var jobNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) jobNode.getProperties().get("tools");
        assertTrue(tools.contains("docker"));
        assertTrue(tools.contains("helm"));
    }

    @Test
    void negativeMatch_notGitlabCi() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("key", "value")
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
                        "stages", List.of("build"),
                        "job1", Map.of("stage", "build", "script", List.of("echo hi"))
                )
        );
        DetectorContext ctx = new DetectorContext(".gitlab-ci.yml", "yaml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
