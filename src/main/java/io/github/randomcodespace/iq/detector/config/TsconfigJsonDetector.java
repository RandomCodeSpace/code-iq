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
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects configuration structure from tsconfig.json files.
 */
@DetectorInfo(
    name = "tsconfig_json",
    category = "config",
    description = "Detects TypeScript compiler configuration and project references",
    parser = ParserType.STRUCTURED,
    languages = {"json"},
    nodeKinds = {NodeKind.CONFIG_FILE, NodeKind.CONFIG_KEY},
    edgeKinds = {EdgeKind.CONTAINS, EdgeKind.DEPENDS_ON},
    properties = {"config_type", "path", "strict", "target"}
)
@Component
public class TsconfigJsonDetector extends AbstractStructuredDetector {
    private static final String PROP_EXTENDS = "extends";


    private static final Pattern TSCONFIG_RE = Pattern.compile("^tsconfig(?:\\..+)?\\.json$");
    private static final List<String> TRACKED_COMPILER_OPTIONS = List.of(
            "strict", "target", "module", "outDir", "rootDir");

    @Override
    public String getName() {
        return "tsconfig_json";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("json");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        String bname = basename(fp);
        if (!TSCONFIG_RE.matcher(bname).matches()) {
            return DetectorResult.empty();
        }

        Object parsedData = ctx.parsedData();
        if (parsedData == null) return DetectorResult.empty();

        Map<String, Object> cfg = getMap(parsedData, "data");
        if (cfg.isEmpty()) return DetectorResult.empty();

        String filepath = ctx.filePath();
        String configId = "tsconfig:" + filepath;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // CONFIG_FILE node
        CodeNode configNode = new CodeNode(configId, NodeKind.CONFIG_FILE, bname);
        configNode.setFqn(filepath);
        configNode.setModule(ctx.moduleName());
        configNode.setFilePath(filepath);
        configNode.setProperties(Map.of("config_type", "tsconfig"));
        nodes.add(configNode);

        // DEPENDS_ON edge for PROP_EXTENDS
        String extendsVal = getString(cfg, PROP_EXTENDS);
        if (extendsVal != null && !extendsVal.isEmpty()) {
            CodeEdge edge = new CodeEdge();
            edge.setId(configId + "->" + extendsVal);
            edge.setKind(EdgeKind.DEPENDS_ON);
            edge.setSourceId(configId);
            edge.setTarget(new CodeNode(extendsVal, null, null));
            edge.setProperties(Map.of("relation", PROP_EXTENDS));
            edges.add(edge);
        }

        // DEPENDS_ON edges for "references"
        List<Object> references = getList(cfg, "references");
        for (Object ref : references) {
            Map<String, Object> refMap = asMap(ref);
            String refPath = getString(refMap, "path");
            if (refPath != null && !refPath.isEmpty()) {
                CodeEdge edge = new CodeEdge();
                edge.setId(configId + "->" + refPath);
                edge.setKind(EdgeKind.DEPENDS_ON);
                edge.setSourceId(configId);
                edge.setTarget(new CodeNode(refPath, null, null));
                edge.setProperties(Map.of("relation", "reference"));
                edges.add(edge);
            }
        }

        // CONFIG_KEY nodes for key compiler options
        Map<String, Object> compilerOptions = getMap(cfg, "compilerOptions");
        for (String opt : TRACKED_COMPILER_OPTIONS) {
            if (!compilerOptions.containsKey(opt)) continue;
            Object value = compilerOptions.get(opt);
            String keyId = "tsconfig:" + filepath + ":option:" + opt;

            Map<String, Object> keyProps = new HashMap<>();
            keyProps.put("key", opt);
            keyProps.put("value", value);

            CodeNode keyNode = new CodeNode(keyId, NodeKind.CONFIG_KEY,
                    "compilerOptions." + opt);
            keyNode.setModule(ctx.moduleName());
            keyNode.setFilePath(filepath);
            keyNode.setProperties(keyProps);
            nodes.add(keyNode);

            CodeEdge edge = new CodeEdge();
            edge.setId(configId + "->" + keyId);
            edge.setKind(EdgeKind.CONTAINS);
            edge.setSourceId(configId);
            edge.setTarget(new CodeNode(keyId, null, null));
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }

    private String basename(String path) {
        if (path == null) return "";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
