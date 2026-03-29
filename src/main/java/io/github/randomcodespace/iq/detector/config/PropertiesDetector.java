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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects property keys, Spring config markers, and database connections from .properties files.
 */
@Component
public class PropertiesDetector extends AbstractStructuredDetector {

    private static final Set<String> DB_KEYWORDS = Set.of("url", "jdbc", "datasource");
    private static final int MAX_KEYS = 200;

    @Override
    public String getName() {
        return "properties";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("properties");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        Object parsedData = ctx.parsedData();
        if (parsedData == null) return DetectorResult.empty();

        Map<String, Object> pd = asMap(parsedData);
        if (!"properties".equals(getString(pd, "type"))) {
            return DetectorResult.empty();
        }

        Map<String, Object> data = getMap(pd, "data");
        if (data.isEmpty()) return DetectorResult.empty();

        String filepath = ctx.filePath();
        String fileId = "props:" + filepath;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // CONFIG_FILE node
        CodeNode fileNode = new CodeNode(fileId, NodeKind.CONFIG_FILE, filepath);
        fileNode.setFqn(filepath);
        fileNode.setModule(ctx.moduleName());
        fileNode.setFilePath(filepath);
        fileNode.setLineStart(1);
        fileNode.setProperties(Map.of("format", "properties"));
        nodes.add(fileNode);

        // Process keys (limit to avoid node explosion)
        int count = 0;
        for (var entry : data.entrySet()) {
            if (count >= MAX_KEYS) break;
            String key = entry.getKey();
            Object value = entry.getValue();

            String keyLower = key.toLowerCase();
            String keyId = "props:" + filepath + ":" + key;

            boolean isDb = DB_KEYWORDS.stream().anyMatch(keyLower::contains);

            Map<String, Object> props = new HashMap<>();
            props.put("key", key);
            if (value instanceof String s) {
                props.put("value", s);
            }

            if (isDb) {
                CodeNode keyNode = new CodeNode(keyId, NodeKind.DATABASE_CONNECTION, key);
                keyNode.setFqn(filepath + ":" + key);
                keyNode.setModule(ctx.moduleName());
                keyNode.setFilePath(filepath);
                keyNode.setProperties(props);
                nodes.add(keyNode);
            } else {
                if (key.startsWith("spring.")) {
                    props.put("spring_config", true);
                }
                CodeNode keyNode = new CodeNode(keyId, NodeKind.CONFIG_KEY, key);
                keyNode.setFqn(filepath + ":" + key);
                keyNode.setModule(ctx.moduleName());
                keyNode.setFilePath(filepath);
                keyNode.setProperties(props);
                nodes.add(keyNode);
            }

            CodeEdge edge = new CodeEdge();
            edge.setId(fileId + "->" + keyId);
            edge.setKind(EdgeKind.CONTAINS);
            edge.setSourceId(fileId);
            edge.setTarget(new CodeNode(keyId, null, null));
            edges.add(edge);

            count++;
        }

        return DetectorResult.of(nodes, edges);
    }
}
