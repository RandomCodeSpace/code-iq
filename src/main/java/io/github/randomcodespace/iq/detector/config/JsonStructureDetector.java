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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects JSON file structures: top-level keys and file identity.
 */
@Component
public class JsonStructureDetector extends AbstractStructuredDetector {

    @Override
    public String getName() {
        return "json_structure";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("json");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        String fileId = "json:" + fp;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // CONFIG_FILE node for the file itself
        CodeNode fileNode = new CodeNode(fileId, NodeKind.CONFIG_FILE, fp);
        fileNode.setFqn(fp);
        fileNode.setModule(ctx.moduleName());
        fileNode.setFilePath(fp);
        fileNode.setLineStart(1);
        fileNode.setProperties(Map.of("format", "json"));
        nodes.add(fileNode);

        // Extract data from parsed_data
        Object parsedData = ctx.parsedData();
        if (parsedData == null) {
            return DetectorResult.of(nodes, edges);
        }

        Map<String, Object> pd = asMap(parsedData);
        Map<String, Object> data = getMap(pd, "data");

        if (data.isEmpty()) {
            return DetectorResult.of(nodes, edges);
        }

        for (String key : data.keySet()) {
            String keyId = "json:" + fp + ":" + key;

            CodeNode keyNode = new CodeNode(keyId, NodeKind.CONFIG_KEY, key);
            keyNode.setFqn(fp + ":" + key);
            keyNode.setModule(ctx.moduleName());
            keyNode.setFilePath(fp);
            nodes.add(keyNode);

            CodeEdge edge = new CodeEdge();
            edge.setId(fileId + "->" + keyId);
            edge.setKind(EdgeKind.CONTAINS);
            edge.setSourceId(fileId);
            edge.setTarget(new CodeNode(keyId, null, null));
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
