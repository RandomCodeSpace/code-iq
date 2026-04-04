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
 * Detects JSON file structures: top-level keys and file identity.
 */
@DetectorInfo(
    name = "json_structure",
    category = "config",
    description = "Detects JSON file structure (top-level keys and nested objects)",
    parser = ParserType.STRUCTURED,
    languages = {"json"},
    nodeKinds = {NodeKind.CONFIG_FILE, NodeKind.CONFIG_KEY},
    edgeKinds = {EdgeKind.CONTAINS}
)
@Component
public class JsonStructureDetector extends AbstractStructuredDetector {
    private static final String PROP_JSON = "json";


    @Override
    public String getName() {
        return "json_structure";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of(PROP_JSON);
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        String fileId = "json:" + fp;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // CONFIG_FILE node for the file itself
        nodes.add(buildFileNode(ctx, PROP_JSON));

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
            addKeyNode(fileId, fp, key, PROP_JSON, ctx, nodes, edges);
        }

        return DetectorResult.of(nodes, edges);
    }
}
