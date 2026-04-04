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
import java.util.TreeMap;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects AWS CloudFormation resources, parameters, outputs, and dependencies.
 */
@DetectorInfo(
    name = "cloudformation",
    category = "config",
    description = "Detects AWS CloudFormation resources and stack dependencies",
    parser = ParserType.STRUCTURED,
    languages = {"yaml", "json"},
    nodeKinds = {NodeKind.CONFIG_DEFINITION, NodeKind.INFRA_RESOURCE},
    edgeKinds = {EdgeKind.DEPENDS_ON},
    properties = {"resource_type"}
)
@Component
public class CloudFormationDetector extends AbstractStructuredDetector {
    private static final String PROP_TYPE = "Type";


    @Override
    public String getName() {
        return "cloudformation";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("yaml", "json");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        Map<String, Object> data = getData(ctx);
        if (data == null) {
            return DetectorResult.empty();
        }

        String fp = ctx.filePath();
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Process Resources
        Map<String, Object> resources = getMap(data, "Resources");
        if (!resources.isEmpty()) {
            // Sort for determinism
            TreeMap<String, Object> sorted = new TreeMap<>(resources);
            for (var entry : sorted.entrySet()) {
                String logicalId = entry.getKey();
                Map<String, Object> resource = asMap(entry.getValue());
                if (resource.isEmpty()) continue;

                String resourceType = getStringOrDefault(resource, PROP_TYPE, "unknown");
                String nodeId = "cfn:" + fp + ":resource:" + logicalId;

                Map<String, Object> props = new HashMap<>();
                props.put("logical_id", logicalId);
                props.put("resource_type", resourceType);

                CodeNode node = new CodeNode(nodeId, NodeKind.INFRA_RESOURCE,
                        logicalId + " (" + resourceType + ")");
                node.setFqn("cfn:" + logicalId);
                node.setModule(ctx.moduleName());
                node.setFilePath(fp);
                node.setProperties(props);
                nodes.add(node);

                // Collect Ref and Fn::GetAtt references
                Set<String> refs = new LinkedHashSet<>();
                collectRefs(resource, refs);
                refs.remove(logicalId);

                for (String ref : refs.stream().sorted().toList()) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(nodeId + "->cfn:" + fp + ":resource:" + ref);
                    edge.setKind(EdgeKind.DEPENDS_ON);
                    edge.setSourceId(nodeId);
                    edge.setTarget(new CodeNode("cfn:" + fp + ":resource:" + ref, null, null));
                    edge.setProperties(Map.of("ref_type", "Ref/GetAtt"));
                    edges.add(edge);
                }
            }
        }

        // Process Parameters
        Map<String, Object> parameters = getMap(data, "Parameters");
        if (!parameters.isEmpty()) {
            TreeMap<String, Object> sorted = new TreeMap<>(parameters);
            for (var entry : sorted.entrySet()) {
                String paramName = entry.getKey();
                Map<String, Object> paramDef = asMap(entry.getValue());
                if (paramDef.isEmpty()) continue;

                String paramType = getStringOrDefault(paramDef, PROP_TYPE, "String");
                Map<String, Object> props = new HashMap<>();
                props.put("param_type", paramType);
                props.put("cfn_type", "parameter");

                Object defaultVal = paramDef.get("Default");
                if (defaultVal != null) props.put("default", String.valueOf(defaultVal));
                String description = getString(paramDef, "Description");
                if (description != null && !description.isEmpty()) props.put("description", description);

                CodeNode node = new CodeNode("cfn:" + fp + ":parameter:" + paramName,
                        NodeKind.CONFIG_DEFINITION, "param:" + paramName);
                node.setFqn("cfn:param:" + paramName);
                node.setModule(ctx.moduleName());
                node.setFilePath(fp);
                node.setProperties(props);
                nodes.add(node);
            }
        }

        // Process Outputs
        Map<String, Object> outputs = getMap(data, "Outputs");
        if (!outputs.isEmpty()) {
            TreeMap<String, Object> sorted = new TreeMap<>(outputs);
            for (var entry : sorted.entrySet()) {
                String outputName = entry.getKey();
                Map<String, Object> outputDef = asMap(entry.getValue());
                if (outputDef.isEmpty()) continue;

                Map<String, Object> props = new HashMap<>();
                props.put("cfn_type", "output");
                String description = getString(outputDef, "Description");
                if (description != null && !description.isEmpty()) props.put("description", description);

                Map<String, Object> export = getMap(outputDef, "Export");
                String exportName = getString(export, "Name");
                if (exportName != null) props.put("export_name", exportName);

                CodeNode node = new CodeNode("cfn:" + fp + ":output:" + outputName,
                        NodeKind.CONFIG_DEFINITION, "output:" + outputName);
                node.setFqn("cfn:output:" + outputName);
                node.setModule(ctx.moduleName());
                node.setFilePath(fp);
                node.setProperties(props);
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private Map<String, Object> getData(DetectorContext ctx) {
        Object parsedData = ctx.parsedData();
        if (parsedData == null) return null;

        Map<String, Object> pd = asMap(parsedData);
        String ptype = getString(pd, "type");

        if ("yaml".equals(ptype) || "json".equals(ptype)) {
            Map<String, Object> data = getMap(pd, "data");
            if (!data.isEmpty() && isCfnTemplate(data)) {
                return data;
            }
        }
        return null;
    }

    private boolean isCfnTemplate(Map<String, Object> data) {
        if (data.containsKey("AWSTemplateFormatVersion")) return true;
        Map<String, Object> resources = getMap(data, "Resources");
        for (Object val : resources.values()) {
            Map<String, Object> resource = asMap(val);
            String rtype = getString(resource, PROP_TYPE);
            if (rtype != null && rtype.startsWith("AWS::")) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void collectRefs(Object value, Set<String> refs) {
        if (value instanceof Map<?, ?> map) {
            Object ref = map.get("Ref");
            if (ref instanceof String s) refs.add(s);

            Object getAtt = map.get("Fn::GetAtt");
            if (getAtt instanceof List<?> attList && !attList.isEmpty()) {
                refs.add(String.valueOf(attList.getFirst()));
            } else if (getAtt instanceof String s && s.contains(".")) {
                refs.add(s.split("\\.")[0]);
            }

            for (Object v : map.values()) {
                collectRefs(v, refs);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectRefs(item, refs);
            }
        }
    }
}
