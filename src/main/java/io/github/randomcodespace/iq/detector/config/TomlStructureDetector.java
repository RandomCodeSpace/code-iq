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

import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects TOML file structures: sections, top-level keys, and file identity.
 * <p>
 * Expects parsedData to be a Map with type PROP_TOML and "data" containing the parsed structure.
 */
@DetectorInfo(
    name = "toml_structure",
    category = "config",
    description = "Detects TOML file structure (sections and key-value pairs)",
    parser = ParserType.STRUCTURED,
    languages = {"toml"},
    nodeKinds = {NodeKind.CONFIG_FILE, NodeKind.CONFIG_KEY},
    edgeKinds = {EdgeKind.CONTAINS}
)
@Component
public class TomlStructureDetector extends AbstractStructuredDetector {
    private static final String PROP_TOML = "toml";


    @Override
    public String getName() {
        return "toml_structure";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of(PROP_TOML);
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        String fileId = "toml:" + fp;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // CONFIG_FILE node for the file itself
        nodes.add(buildFileNode(ctx, PROP_TOML));

        Object parsedData = ctx.parsedData();
        if (parsedData == null) {
            return DetectorResult.of(nodes, edges);
        }

        Map<String, Object> pd = asMap(parsedData);
        Map<String, Object> data = getMap(pd, "data");
        if (data.isEmpty()) {
            return DetectorResult.of(nodes, edges);
        }

        for (var entry : data.entrySet()) {
            String keyStr = entry.getKey();
            boolean isSection = entry.getValue() instanceof Map<?, ?>;
            String keyId = "toml:" + fp + ":" + keyStr;

            Map<String, Object> props = new HashMap<>();
            if (isSection) {
                props.put("section", true);
            }

            CodeNode keyNode = new CodeNode(keyId, NodeKind.CONFIG_KEY, keyStr);
            keyNode.setFqn(fp + ":" + keyStr);
            keyNode.setModule(ctx.moduleName());
            keyNode.setFilePath(fp);
            keyNode.setProperties(props);
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
