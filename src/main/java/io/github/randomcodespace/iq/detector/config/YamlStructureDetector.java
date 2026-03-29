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
import java.util.TreeSet;

/**
 * Detects YAML file structures: top-level keys and file identity.
 */
@Component
public class YamlStructureDetector extends AbstractStructuredDetector {

    @Override
    public String getName() {
        return "yaml_structure";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        String fileId = "yaml:" + fp;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // CONFIG_FILE node for the file itself
        CodeNode fileNode = new CodeNode(fileId, NodeKind.CONFIG_FILE, fp);
        fileNode.setFqn(fp);
        fileNode.setModule(ctx.moduleName());
        fileNode.setFilePath(fp);
        fileNode.setLineStart(1);
        fileNode.setProperties(Map.of("format", "yaml"));
        nodes.add(fileNode);

        Object parsedData = ctx.parsedData();
        if (parsedData == null) {
            return DetectorResult.of(nodes, edges);
        }

        Map<String, Object> pd = asMap(parsedData);
        String docType = getStringOrDefault(pd, "type", "");

        Set<String> topLevelKeys = new TreeSet<>();

        if ("yaml_multi".equals(docType)) {
            List<Object> documents = getList(pd, "documents");
            for (Object doc : documents) {
                Map<String, Object> docMap = asMap(doc);
                for (Object k : docMap.keySet()) {
                    topLevelKeys.add(String.valueOf(k));
                }
            }
        } else if ("yaml".equals(docType)) {
            Map<String, Object> data = getMap(pd, "data");
            for (Object k : data.keySet()) {
                topLevelKeys.add(String.valueOf(k));
            }
        }

        for (String keyStr : topLevelKeys) {
            String keyId = "yaml:" + fp + ":" + keyStr;

            CodeNode keyNode = new CodeNode(keyId, NodeKind.CONFIG_KEY, keyStr);
            keyNode.setFqn(fp + ":" + keyStr);
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
