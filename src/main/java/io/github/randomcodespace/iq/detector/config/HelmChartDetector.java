package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.AbstractStructuredDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Helm chart patterns in Chart.yaml, values.yaml, and templates.
 */
@Component
public class HelmChartDetector extends AbstractStructuredDetector {

    private static final Pattern VALUES_REF_RE = Pattern.compile(
            "\\{\\{\\s*\\.Values\\.([a-zA-Z0-9_.]+)\\s*\\}\\}");
    private static final Pattern INCLUDE_RE = Pattern.compile(
            "\\{\\{-?\\s*include\\s+[\"']([^\"']+)[\"']");

    @Override
    public String getName() {
        return "helm_chart";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        if (fp.endsWith("Chart.yaml")) {
            detectChartYaml(ctx, nodes, edges);
        } else if (fp.endsWith("values.yaml") && (fp.contains("charts/") || fp.contains("helm/"))) {
            detectValuesYaml(ctx, nodes, edges);
        } else if (fp.contains("/templates/") && fp.endsWith(".yaml")) {
            detectTemplate(ctx, nodes, edges);
        } else {
            return DetectorResult.empty();
        }

        return DetectorResult.of(nodes, edges);
    }

    private void detectChartYaml(DetectorContext ctx, List<CodeNode> nodes, List<CodeEdge> edges) {
        String fp = ctx.filePath();
        Map<String, Object> data = getYamlData(ctx);
        if (data == null) return;

        String chartName = getStringOrDefault(data, "name", "unknown");
        String chartVersion = getStringOrDefault(data, "version", "0.0.0");
        String chartNodeId = "helm:" + fp + ":chart:" + chartName;

        Map<String, Object> props = new HashMap<>();
        props.put("chart_name", chartName);
        props.put("chart_version", chartVersion);
        props.put("type", "helm_chart");

        CodeNode chartNode = new CodeNode(chartNodeId, NodeKind.MODULE, "helm:" + chartName);
        chartNode.setFqn("helm:" + chartName + ":" + chartVersion);
        chartNode.setModule(ctx.moduleName());
        chartNode.setFilePath(fp);
        chartNode.setProperties(props);
        nodes.add(chartNode);

        // Process dependencies
        List<Object> dependencies = getList(data, "dependencies");
        for (Object dep : dependencies) {
            Map<String, Object> depMap = asMap(dep);
            if (depMap.isEmpty()) continue;

            String depName = getString(depMap, "name");
            if (depName == null || depName.isEmpty()) continue;

            String depVersion = getStringOrDefault(depMap, "version", "");
            String depRepo = getStringOrDefault(depMap, "repository", "");
            String depNodeId = "helm:" + fp + ":dep:" + depName;

            Map<String, Object> depProps = new HashMap<>();
            depProps.put("chart_name", depName);
            depProps.put("chart_version", depVersion);
            depProps.put("repository", depRepo);
            depProps.put("type", "helm_dependency");

            CodeNode depNode = new CodeNode(depNodeId, NodeKind.MODULE, "helm-dep:" + depName);
            depNode.setFqn("helm:" + depName + ":" + depVersion);
            depNode.setModule(ctx.moduleName());
            depNode.setFilePath(fp);
            depNode.setProperties(depProps);
            nodes.add(depNode);

            CodeEdge edge = new CodeEdge();
            edge.setId(chartNodeId + "->" + depNodeId);
            edge.setKind(EdgeKind.DEPENDS_ON);
            edge.setSourceId(chartNodeId);
            edge.setTarget(new CodeNode(depNodeId, null, null));
            edge.setProperties(Map.of("version", depVersion));
            edges.add(edge);
        }
    }

    private void detectValuesYaml(DetectorContext ctx, List<CodeNode> nodes, List<CodeEdge> edges) {
        String fp = ctx.filePath();
        Map<String, Object> data = getYamlData(ctx);
        if (data == null) return;

        for (String key : data.keySet().stream().sorted().toList()) {
            CodeNode keyNode = new CodeNode("helm:" + fp + ":value:" + key,
                    NodeKind.CONFIG_KEY, "helm-value:" + key);
            keyNode.setModule(ctx.moduleName());
            keyNode.setFilePath(fp);
            keyNode.setProperties(Map.of("helm_value", true, "key", key));
            nodes.add(keyNode);
        }
    }

    private void detectTemplate(DetectorContext ctx, List<CodeNode> nodes, List<CodeEdge> edges) {
        String fp = ctx.filePath();
        String content = ctx.content();
        if (content == null || content.isEmpty()) return;

        String fileNodeId = "helm:" + fp + ":template";
        Set<String> seenValues = new LinkedHashSet<>();
        Set<String> seenIncludes = new LinkedHashSet<>();

        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i];

            Matcher vm = VALUES_REF_RE.matcher(line);
            while (vm.find()) {
                String key = vm.group(1);
                if (seenValues.add(key)) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(fileNodeId + "->helm:values:" + key);
                    edge.setKind(EdgeKind.READS_CONFIG);
                    edge.setSourceId(fileNodeId);
                    edge.setTarget(new CodeNode("helm:values:" + key, null, null));
                    edge.setProperties(Map.of("key", key, "line", lineNo));
                    edges.add(edge);
                }
            }

            Matcher im = INCLUDE_RE.matcher(line);
            while (im.find()) {
                String helper = im.group(1);
                if (seenIncludes.add(helper)) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(fileNodeId + "->helm:helper:" + helper);
                    edge.setKind(EdgeKind.IMPORTS);
                    edge.setSourceId(fileNodeId);
                    edge.setTarget(new CodeNode("helm:helper:" + helper, null, null));
                    edge.setProperties(Map.of("helper", helper, "line", lineNo));
                    edges.add(edge);
                }
            }
        }
    }

    private Map<String, Object> getYamlData(DetectorContext ctx) {
        Object parsedData = ctx.parsedData();
        if (parsedData == null) return null;

        Map<String, Object> pd = asMap(parsedData);
        if (!"yaml".equals(getString(pd, "type"))) return null;

        Map<String, Object> data = getMap(pd, "data");
        return data.isEmpty() ? null : data;
    }
}
