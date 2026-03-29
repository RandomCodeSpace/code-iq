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

class KubernetesRbacDetectorTest {

    private final KubernetesRbacDetector detector = new KubernetesRbacDetector();

    @Test
    void positiveMatch_roleAndBinding() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml_multi",
                "documents", List.of(
                        Map.of("kind", "Role",
                                "metadata", Map.of("name", "pod-reader", "namespace", "default"),
                                "rules", List.of(Map.of(
                                        "apiGroups", List.of(""),
                                        "resources", List.of("pods"),
                                        "verbs", List.of("get", "list")))),
                        Map.of("kind", "ServiceAccount",
                                "metadata", Map.of("name", "my-sa", "namespace", "default")),
                        Map.of("kind", "RoleBinding",
                                "metadata", Map.of("name", "read-pods", "namespace", "default"),
                                "roleRef", Map.of("kind", "Role", "name", "pod-reader"),
                                "subjects", List.of(Map.of("kind", "ServiceAccount",
                                        "name", "my-sa", "namespace", "default")))
                )
        );
        DetectorContext ctx = new DetectorContext("rbac.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertEquals(3, result.nodes().size());
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.GUARD));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.PROTECTS));
    }

    @Test
    void negativeMatch_notRbac() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("kind", "Deployment", "metadata", Map.of("name", "web"))
        );
        DetectorContext ctx = new DetectorContext("deploy.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("kind", "ClusterRole",
                        "metadata", Map.of("name", "admin"),
                        "rules", List.of(Map.of("apiGroups", List.of("*"),
                                "resources", List.of("*"), "verbs", List.of("*"))))
        );
        DetectorContext ctx = new DetectorContext("rbac.yaml", "yaml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
