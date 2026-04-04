package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expanded branch-coverage tests for KubernetesDetector.
 * Targets the 69% missed-line gap reported by SonarCloud.
 */
class KubernetesDetectorExpandedTest {

    private final KubernetesDetector detector = new KubernetesDetector();

    // ── Helper to build the minimal parsedData map for a single-doc YAML ──
    private DetectorContext singleDoc(Map<String, Object> docContent) {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", docContent
        );
        return new DetectorContext("k8s/manifest.yaml", "yaml", "", parsedData, null);
    }

    private DetectorContext multiDoc(List<Map<String, Object>> docs) {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml_multi",
                "documents", docs
        );
        return new DetectorContext("k8s/multi.yaml", "yaml", "", parsedData, null);
    }

    // ── Null / empty parsedData ──

    @Test
    void returnsEmptyWhenNoParsedData() {
        DetectorContext ctx = new DetectorContext("k8s/empty.yaml", "yaml", "");
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void returnsEmptyWhenNullParsedData() {
        DetectorContext ctx = new DetectorContext("k8s/null.yaml", "yaml", "", null, null);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void returnsEmptyForUnknownType() {
        Map<String, Object> parsedData = Map.of("type", "json", "data", Map.of("kind", "Deployment"));
        DetectorContext ctx = new DetectorContext("k8s/unknown.yaml", "yaml", "", parsedData, null);
        assertTrue(detector.detect(ctx).nodes().isEmpty());
    }

    // ── ConfigMap ──

    @Test
    void detectsConfigMap() {
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "ConfigMap",
                "metadata", Map.of("name", "app-config", "namespace", "default"),
                "spec", Map.of()
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INFRA_RESOURCE));
    }

    // ── Secret ──

    @Test
    void detectsSecret() {
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Secret",
                "metadata", Map.of("name", "db-secret", "namespace", "prod"),
                "spec", Map.of()
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
    }

    // ── StatefulSet ──

    @Test
    void detectsStatefulSet() {
        Map<String, Object> podSpec = Map.of(
                "containers", List.of(
                        Map.of("name", "db", "image", "postgres:15")
                )
        );
        Map<String, Object> spec = Map.of(
                "selector", Map.of("matchLabels", Map.of("app", "postgres")),
                "template", Map.of(
                        "metadata", Map.of("labels", Map.of("app", "postgres")),
                        "spec", podSpec
                )
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "StatefulSet",
                "metadata", Map.of("name", "postgres", "namespace", "data"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INFRA_RESOURCE));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_KEY));
    }

    // ── DaemonSet ──

    @Test
    void detectsDaemonSet() {
        Map<String, Object> spec = Map.of(
                "selector", Map.of("matchLabels", Map.of("app", "monitor")),
                "template", Map.of(
                        "metadata", Map.of("labels", Map.of("app", "monitor")),
                        "spec", Map.of("containers", List.of(
                                Map.of("name", "agent", "image", "monitoring:latest")
                        ))
                )
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "DaemonSet",
                "metadata", Map.of("name", "monitor-agent"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
    }

    // ── Job ──

    @Test
    void detectsJob() {
        Map<String, Object> spec = Map.of(
                "template", Map.of(
                        "spec", Map.of("containers", List.of(
                                Map.of("name", "migrator", "image", "migration:1.0")
                        ))
                )
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Job",
                "metadata", Map.of("name", "db-migration"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_KEY));
    }

    // ── CronJob ──

    @Test
    void detectsCronJob() {
        Map<String, Object> innerSpec = Map.of(
                "template", Map.of(
                        "spec", Map.of("containers", List.of(
                                Map.of("name", "reporter", "image", "reporter:2.0")
                        ))
                )
        );
        Map<String, Object> spec = Map.of(
                "schedule", "0 * * * *",
                "jobTemplate", Map.of("spec", innerSpec)
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "CronJob",
                "metadata", Map.of("name", "daily-report"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_KEY));
    }

    // ── Pod ──

    @Test
    void detectsPod() {
        Map<String, Object> spec = Map.of(
                "containers", List.of(
                        Map.of("name", "web", "image", "nginx:1.21")
                )
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Pod",
                "metadata", Map.of("name", "single-pod"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_KEY));
    }

    // ── Container with ports and env vars ──

    @Test
    void detectsContainerWithPortsAndEnvVars() {
        Map<String, Object> spec = Map.of(
                "template", Map.of(
                        "spec", Map.of("containers", List.of(
                                Map.of(
                                        "name", "api",
                                        "image", "api:latest",
                                        "ports", List.of(
                                                Map.of("containerPort", 8080, "protocol", "TCP")
                                        ),
                                        "env", List.of(
                                                Map.of("name", "DB_URL", "value", "jdbc:..."),
                                                Map.of("name", "API_KEY", "value", "secret")
                                        )
                                )
                        ))
                )
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Deployment",
                "metadata", Map.of("name", "api-deploy", "namespace", "default"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
        var containerNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CONFIG_KEY)
                .findFirst();
        assertTrue(containerNode.isPresent());
        assertNotNull(containerNode.get().getProperties().get("ports"));
        assertNotNull(containerNode.get().getProperties().get("env_vars"));
    }

    // ── Container with init containers ──

    @Test
    void detectsInitContainers() {
        Map<String, Object> spec = Map.of(
                "template", Map.of(
                        "spec", Map.of(
                                "containers", List.of(Map.of("name", "main", "image", "app:1.0")),
                                "initContainers", List.of(Map.of("name", "init", "image", "busybox"))
                        )
                )
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Deployment",
                "metadata", Map.of("name", "app-with-init"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        // Should detect both main + init containers as CONFIG_KEY nodes
        long containerNodes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CONFIG_KEY)
                .count();
        assertEquals(2, containerNodes);
    }

    // ── Ingress ──

    @Test
    void detectsIngressWithRules() {
        Map<String, Object> spec = Map.of(
                "rules", List.of(
                        Map.of("host", "api.example.com",
                                "http", Map.of("paths", List.of(
                                        Map.of("path", "/api",
                                                "backend", Map.of(
                                                        "service", Map.of("name", "api-svc", "port", Map.of("number", 80))
                                                ))
                                )))
                )
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Ingress",
                "metadata", Map.of("name", "main-ingress"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
    }

    @Test
    void detectsIngressWithDefaultBackend() {
        Map<String, Object> spec = Map.of(
                "defaultBackend", Map.of(
                        "service", Map.of("name", "default-svc", "port", Map.of("number", 80))
                ),
                "rules", List.of()
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Ingress",
                "metadata", Map.of("name", "default-ingress"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
    }

    // ── Ingress -> Service -> Deployment cross-resource edges ──

    @Test
    void detectsIngressToServiceEdge() {
        List<Map<String, Object>> docs = List.of(
                Map.of(
                        "kind", "Ingress",
                        "metadata", Map.of("name", "web-ingress", "namespace", "default"),
                        "spec", Map.of(
                                "rules", List.of(
                                        Map.of("http", Map.of("paths", List.of(
                                                Map.of("backend", Map.of("service",
                                                        Map.of("name", "web-svc")))
                                        )))
                                )
                        )
                ),
                Map.of(
                        "kind", "Service",
                        "metadata", Map.of("name", "web-svc", "namespace", "default"),
                        "spec", Map.of("selector", Map.of("app", "web"))
                )
        );
        DetectorContext ctx = multiDoc(docs);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO));
    }

    // ── Namespace ──

    @Test
    void detectsNamespaceResource() {
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Namespace",
                "metadata", Map.of("name", "production"),
                "spec", Map.of()
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
    }

    // ── Default namespace when not specified ──

    @Test
    void usesDefaultNamespaceWhenMissing() {
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Service",
                "metadata", Map.of("name", "my-svc"),
                "spec", Map.of()
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
        // FQN should include "default" namespace
        String fqn = result.nodes().get(0).getFqn();
        assertTrue(fqn.contains("default"));
    }

    // ── Metadata with labels and annotations ──

    @Test
    void preservesLabelsAndAnnotations() {
        Map<String, Object> meta = Map.of(
                "name", "labeled-deploy",
                "labels", Map.of("app", "myapp", "env", "prod"),
                "annotations", Map.of("prometheus.io/scrape", "true")
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Deployment",
                "metadata", meta,
                "spec", Map.of()
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
        var node = result.nodes().get(0);
        assertNotNull(node.getProperties().get("labels"));
        assertNotNull(node.getProperties().get("annotations"));
    }

    // ── Non-K8s yaml_multi document gets filtered ──

    @Test
    void filtersNonK8sDocumentsInMultiDoc() {
        List<Map<String, Object>> docs = List.of(
                Map.of("kind", "Deployment",
                        "metadata", Map.of("name", "app"),
                        "spec", Map.of()),
                Map.of("name", "not-k8s", "version", "1.0") // no kind field
        );
        DetectorContext ctx = multiDoc(docs);
        DetectorResult result = detector.detect(ctx);
        // Only the Deployment should be detected
        long k8sNodes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.INFRA_RESOURCE)
                .count();
        assertEquals(1, k8sNodes);
    }

    // ── Determinism ──

    @Test
    void isDeterministic() {
        List<Map<String, Object>> docs = List.of(
                Map.of(
                        "kind", "Deployment",
                        "metadata", Map.of("name", "api", "namespace", "default"),
                        "spec", Map.of(
                                "selector", Map.of("matchLabels", Map.of("app", "api")),
                                "template", Map.of(
                                        "metadata", Map.of("labels", Map.of("app", "api")),
                                        "spec", Map.of("containers", List.of(
                                                Map.of("name", "api", "image", "api:1.0")
                                        ))
                                )
                        )
                ),
                Map.of(
                        "kind", "Service",
                        "metadata", Map.of("name", "api-svc", "namespace", "default"),
                        "spec", Map.of("selector", Map.of("app", "api"))
                )
        );
        DetectorContext ctx = multiDoc(docs);
        DetectorResult r1 = detector.detect(ctx);
        DetectorResult r2 = detector.detect(ctx);
        assertEquals(r1.nodes().size(), r2.nodes().size());
        assertEquals(r1.edges().size(), r2.edges().size());
    }

    // ── Old-style backend.serviceName (pre-networking.k8s.io/v1) ──

    @Test
    void detectsIngressWithOldStyleServiceName() {
        Map<String, Object> spec = Map.of(
                "backend", Map.of("serviceName", "legacy-svc", "servicePort", 80),
                "rules", List.of()
        );
        DetectorContext ctx = singleDoc(Map.of(
                "kind", "Ingress",
                "metadata", Map.of("name", "legacy-ingress"),
                "spec", spec
        ));
        DetectorResult result = detector.detect(ctx);
        assertFalse(result.nodes().isEmpty());
    }
}
