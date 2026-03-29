package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesDetectorTest {

    private final KubernetesDetector detector = new KubernetesDetector();

    @Test
    void positiveMatch_deployment() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "kind", "Deployment",
                        "metadata", Map.of("name", "web-app", "namespace", "prod"),
                        "spec", Map.of(
                                "template", Map.of(
                                        "spec", Map.of(
                                                "containers", List.of(
                                                        Map.of("name", "app", "image", "nginx:latest")
                                                )
                                        )
                                )
                        )
                )
        );
        DetectorContext ctx = new DetectorContext("k8s/deploy.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INFRA_RESOURCE));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_KEY));
    }

    @Test
    void multiDocumentWithServiceSelector() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml_multi",
                "documents", List.of(
                        Map.of("kind", "Deployment",
                                "metadata", Map.of("name", "web", "namespace", "default"),
                                "spec", Map.of(
                                        "selector", Map.of("matchLabels", Map.of("app", "web")),
                                        "template", Map.of("spec", Map.of("containers", List.of()))
                                )),
                        Map.of("kind", "Service",
                                "metadata", Map.of("name", "web-svc", "namespace", "default"),
                                "spec", Map.of("selector", Map.of("app", "web")))
                )
        );
        DetectorContext ctx = new DetectorContext("k8s/app.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        assertFalse(result.edges().isEmpty());
    }

    @Test
    void negativeMatch_notK8s() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("name", "not-k8s", "version", "1.0")
        );
        DetectorContext ctx = new DetectorContext("config.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "kind", "Pod",
                        "metadata", Map.of("name", "test-pod"),
                        "spec", Map.of("containers", List.of(
                                Map.of("name", "main", "image", "alpine")
                        ))
                )
        );
        DetectorContext ctx = new DetectorContext("k8s/pod.yaml", "yaml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
