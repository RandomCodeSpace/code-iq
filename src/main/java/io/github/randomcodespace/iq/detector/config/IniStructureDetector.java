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
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects INI file structures: sections, keys, and file identity.
 * <p>
 * Expects parsedData to be a Map with type PROP_INI and "data" containing
 * a Map of section names to Maps of key-value pairs.
 */
@DetectorInfo(
    name = "ini_structure",
    category = "config",
    description = "Detects INI file structure (sections and key-value pairs)",
    parser = ParserType.STRUCTURED,
    languages = {"ini"},
    nodeKinds = {NodeKind.CONFIG_FILE, NodeKind.CONFIG_KEY},
    edgeKinds = {EdgeKind.CONTAINS}
)
@Component
public class IniStructureDetector extends AbstractStructuredDetector {
    private static final String PROP_INI = "ini";


    @Override
    public String getName() {
        return "ini_structure";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of(PROP_INI);
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        String fileId = "ini:" + fp;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // CONFIG_FILE node for the file itself
        nodes.add(buildFileNode(ctx, PROP_INI));

        Object parsedData = ctx.parsedData();
        if (parsedData == null) {
            return DetectorResult.of(nodes, edges);
        }

        Map<String, Object> pd = asMap(parsedData);
        if (!"ini".equals(getString(pd, "type"))) {
            return DetectorResult.of(nodes, edges);
        }

        Map<String, Object> data = getMap(pd, "data");
        if (data.isEmpty()) {
            return DetectorResult.of(nodes, edges);
        }

        for (var sectionEntry : data.entrySet()) {
            String section = sectionEntry.getKey();
            String sectionId = "ini:" + fp + ":" + section;

            CodeNode sectionNode = new CodeNode(sectionId, NodeKind.CONFIG_KEY, section);
            sectionNode.setFqn(fp + ":" + section);
            sectionNode.setModule(ctx.moduleName());
            sectionNode.setFilePath(fp);
            sectionNode.setProperties(Map.of("section", true));
            nodes.add(sectionNode);

            CodeEdge sectionEdge = new CodeEdge();
            sectionEdge.setId(fileId + "->" + sectionId);
            sectionEdge.setKind(EdgeKind.CONTAINS);
            sectionEdge.setSourceId(fileId);
            sectionEdge.setTarget(new CodeNode(sectionId, null, null));
            edges.add(sectionEdge);

            // Keys within the section
            Map<String, Object> sectionData = asMap(sectionEntry.getValue());
            for (var keyEntry : sectionData.entrySet()) {
                String key = keyEntry.getKey();
                String keyId = "ini:" + fp + ":" + section + ":" + key;

                CodeNode keyNode = new CodeNode(keyId, NodeKind.CONFIG_KEY, key);
                keyNode.setFqn(fp + ":" + section + ":" + key);
                keyNode.setModule(ctx.moduleName());
                keyNode.setFilePath(fp);
                keyNode.setProperties(Map.of("section", section));
                nodes.add(keyNode);

                CodeEdge keyEdge = new CodeEdge();
                keyEdge.setId(sectionId + "->" + keyId);
                keyEdge.setKind(EdgeKind.CONTAINS);
                keyEdge.setSourceId(sectionId);
                keyEdge.setTarget(new CodeNode(keyId, null, null));
                edges.add(keyEdge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
