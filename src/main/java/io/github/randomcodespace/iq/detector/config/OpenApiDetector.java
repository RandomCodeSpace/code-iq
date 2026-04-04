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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects API endpoints and schemas from OpenAPI/Swagger specifications.
 */
@DetectorInfo(
    name = "openapi",
    category = "config",
    description = "Detects OpenAPI/Swagger specifications and their endpoints",
    parser = ParserType.STRUCTURED,
    languages = {"json", "yaml"},
    nodeKinds = {NodeKind.CONFIG_FILE, NodeKind.ENDPOINT, NodeKind.ENTITY},
    edgeKinds = {EdgeKind.CONTAINS, EdgeKind.DEPENDS_ON},
    properties = {"api_version", "config_type", "http_method", "path", "version"}
)
@Component
public class OpenApiDetector extends AbstractStructuredDetector {
    private static final String PROP_OPENAPI = "openapi";


    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options", "trace");

    @Override
    public String getName() {
        return PROP_OPENAPI;
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("json", "yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        Object parsedData = ctx.parsedData();
        if (parsedData == null) return DetectorResult.empty();

        Map<String, Object> pd = asMap(parsedData);
        Map<String, Object> spec = getMap(pd, "data");
        if (spec.isEmpty()) return DetectorResult.empty();

        // Only trigger for OpenAPI or Swagger spec
        if (!spec.containsKey(PROP_OPENAPI) && !spec.containsKey("swagger")) {
            return DetectorResult.empty();
        }

        String filepath = ctx.filePath();
        String configId = "api:" + filepath;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Extract info metadata
        Map<String, Object> info = getMap(spec, "info");
        String apiTitle = getString(info, "title");
        if (apiTitle == null) apiTitle = filepath;
        String apiVersion = getStringOrDefault(info, "version", "");
        Object specVersionObj = spec.get(PROP_OPENAPI);
        if (specVersionObj == null) specVersionObj = spec.get("swagger");
        String specVersion = specVersionObj != null ? String.valueOf(specVersionObj) : "";

        // CONFIG_FILE node for the spec
        Map<String, Object> cfProps = new HashMap<>();
        cfProps.put("config_type", PROP_OPENAPI);
        cfProps.put("api_title", apiTitle);
        cfProps.put("api_version", apiVersion);
        cfProps.put("spec_version", specVersion);

        CodeNode configNode = new CodeNode(configId, NodeKind.CONFIG_FILE, apiTitle);
        configNode.setFqn(filepath);
        configNode.setModule(ctx.moduleName());
        configNode.setFilePath(filepath);
        configNode.setProperties(cfProps);
        nodes.add(configNode);

        // ENDPOINT nodes for each path + method combination
        Map<String, Object> paths = getMap(spec, "paths");
        for (var pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            Map<String, Object> pathItem = asMap(pathEntry.getValue());

            for (var methodEntry : pathItem.entrySet()) {
                String method = methodEntry.getKey();
                if (!HTTP_METHODS.contains(method.toLowerCase())) continue;

                String methodUpper = method.toUpperCase();
                String endpointId = "api:" + filepath + ":" + method.toLowerCase() + ":" + path;
                Map<String, Object> props = new HashMap<>();
                props.put("http_method", methodUpper);
                props.put("path", path);

                Map<String, Object> operation = asMap(methodEntry.getValue());
                String opId = getString(operation, "operationId");
                if (opId != null) props.put("operation_id", opId);
                String summary = getString(operation, "summary");
                if (summary != null) props.put("summary", summary);

                CodeNode endpointNode = new CodeNode(endpointId, NodeKind.ENDPOINT,
                        methodUpper + " " + path);
                endpointNode.setModule(ctx.moduleName());
                endpointNode.setFilePath(filepath);
                endpointNode.setProperties(props);
                nodes.add(endpointNode);

                edges.add(createEdge(configId, endpointId, EdgeKind.CONTAINS,
                        apiTitle + " contains " + methodUpper + " " + path));
            }
        }

        // ENTITY nodes for schemas
        Map<String, Object> schemas = extractSchemas(spec);
        for (var schemaEntry : schemas.entrySet()) {
            String schemaName = schemaEntry.getKey();
            String schemaId = "api:" + filepath + ":schema:" + schemaName;
            Map<String, Object> schemaProps = new HashMap<>();
            schemaProps.put("schema_name", schemaName);

            Map<String, Object> schemaDef = asMap(schemaEntry.getValue());
            String schemaType = getString(schemaDef, "type");
            if (schemaType != null) schemaProps.put("schema_type", schemaType);

            CodeNode schemaNode = new CodeNode(schemaId, NodeKind.ENTITY, schemaName);
            schemaNode.setModule(ctx.moduleName());
            schemaNode.setFilePath(filepath);
            schemaNode.setProperties(schemaProps);
            nodes.add(schemaNode);

            edges.add(createEdge(configId, schemaId, EdgeKind.CONTAINS,
                    apiTitle + " defines schema " + schemaName));

            // DEPENDS_ON edges for $ref references
            List<String> refs = collectRefs(schemaEntry.getValue(), new HashSet<>());
            for (String ref : refs) {
                String refName = refToSchemaName(ref);
                if (refName != null && !refName.equals(schemaName) && schemas.containsKey(refName)) {
                    edges.add(createEdge(schemaId, "api:" + filepath + ":schema:" + refName,
                            EdgeKind.DEPENDS_ON, schemaName + " references " + refName));
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private Map<String, Object> extractSchemas(Map<String, Object> spec) {
        // OpenAPI 3.x: components.schemas
        Map<String, Object> components = getMap(spec, "components");
        Map<String, Object> schemas = getMap(components, "schemas");
        if (!schemas.isEmpty()) return schemas;

        // Swagger 2.0: definitions
        Map<String, Object> definitions = getMap(spec, "definitions");
        if (!definitions.isEmpty()) return definitions;

        return Map.of();
    }

    private List<String> collectRefs(Object obj, Set<Integer> seen) {
        List<String> refs = new ArrayList<>();
        int objId = System.identityHashCode(obj);
        if (seen.contains(objId)) return refs;
        seen.add(objId);

        if (obj instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref instanceof String s) refs.add(s);
            for (Object value : map.values()) {
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    refs.addAll(collectRefs(value, seen));
                }
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    refs.addAll(collectRefs(item, seen));
                }
            }
        }
        return refs;
    }

    private String refToSchemaName(String ref) {
        if (ref == null || !ref.startsWith("#/")) return null;
        String[] parts = ref.split("/");
        return parts.length >= 2 ? parts[parts.length - 1] : null;
    }

    private CodeEdge createEdge(String sourceId, String targetId, EdgeKind kind, String label) {
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->" + targetId);
        edge.setKind(kind);
        edge.setSourceId(sourceId);
        edge.setTarget(new CodeNode(targetId, null, null));
        return edge;
    }
}
