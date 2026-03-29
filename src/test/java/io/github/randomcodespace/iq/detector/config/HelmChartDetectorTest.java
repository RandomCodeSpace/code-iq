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

class HelmChartDetectorTest {

    private final HelmChartDetector detector = new HelmChartDetector();

    @Test
    void positiveMatch_chartYaml() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "name", "my-app",
                        "version", "1.0.0",
                        "dependencies", List.of(
                                Map.of("name", "redis", "version", "17.0.0", "repository", "https://charts.bitnami.com/bitnami")
                        )
                )
        );
        DetectorContext ctx = new DetectorContext("charts/my-app/Chart.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.MODULE));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void positiveMatch_template() {
        String content = """
                apiVersion: v1
                kind: Service
                metadata:
                  name: {{ .Values.service.name }}
                spec:
                  type: {{ .Values.service.type }}
                  ports:
                    - port: {{ .Values.service.port }}
                  selector:
                    {{- include "my-app.selectorLabels" . | nindent 4 }}
                """;
        DetectorContext ctx = new DetectorContext("charts/my-app/templates/service.yaml", "yaml", content, null, null);
        DetectorResult result = detector.detect(ctx);

        // 3 unique .Values refs + 1 include = 4 edges
        assertEquals(3, result.edges().stream().filter(e -> e.getKind() == EdgeKind.READS_CONFIG).count());
        assertEquals(1, result.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void negativeMatch_notHelmFile() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("key", "value")
        );
        DetectorContext ctx = new DetectorContext("config.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("name", "chart", "version", "1.0.0")
        );
        DetectorContext ctx = new DetectorContext("charts/my/Chart.yaml", "yaml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
