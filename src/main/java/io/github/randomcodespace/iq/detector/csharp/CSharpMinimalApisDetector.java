package io.github.randomcodespace.iq.detector.csharp;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "csharp_minimal_apis",
    category = "endpoints",
    description = "Detects C# minimal API endpoints (MapGet, MapPost, etc.)",
    parser = ParserType.REGEX,
    languages = {"csharp"},
    nodeKinds = {NodeKind.ENDPOINT, NodeKind.GUARD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.EXPOSES},
    properties = {"framework", "guard_type", "http_method", "path"}
)
@Component
public class CSharpMinimalApisDetector extends AbstractAntlrDetector {

    private static final Pattern MAP_RE = Pattern.compile("\\.Map(Get|Post|Put|Delete|Patch)\\s*\\(\\s*\"([^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern BUILDER_RE = Pattern.compile("WebApplication\\.CreateBuilder\\s*\\(", Pattern.MULTILINE);
    private static final Pattern AUTH_USE_RE = Pattern.compile("\\.Use(Authentication|Authorization)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern AUTH_ADD_RE = Pattern.compile("\\.Add(Authentication|Authorization)\\s*\\(", Pattern.MULTILINE);

    @Override
    public String getName() { return "csharp_minimal_apis"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("csharp"); }
    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String filePath = ctx.filePath();
        String appModuleId = null;

        Matcher bm = BUILDER_RE.matcher(text);
        if (bm.find()) {
            appModuleId = "dotnet:" + filePath + ":app";
            CodeNode node = new CodeNode();
            node.setId(appModuleId);
            node.setKind(NodeKind.MODULE);
            node.setLabel("WebApplication(" + filePath + ")");
            node.setFqn(filePath);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, bm.start()));
            node.getProperties().put("framework", "dotnet_minimal_api");
            nodes.add(node);
        }

        Matcher m = MAP_RE.matcher(text);
        while (m.find()) {
            String httpMethod = m.group(1).toUpperCase();
            String path = m.group(2);
            int line = findLineNumber(text, m.start());
            String endpointId = "dotnet:" + filePath + ":endpoint:" + httpMethod + ":" + path + ":" + line;
            CodeNode node = new CodeNode();
            node.setId(endpointId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel(httpMethod + " " + path);
            node.setFqn(httpMethod + " " + path);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("http_method", httpMethod);
            node.getProperties().put("path", path);
            node.getProperties().put("framework", "dotnet_minimal_api");
            nodes.add(node);

            if (appModuleId != null) {
                CodeEdge edge = new CodeEdge();
                edge.setId(appModuleId + ":exposes:" + endpointId);
                edge.setKind(EdgeKind.EXPOSES);
                edge.setSourceId(appModuleId);
                edge.setTarget(new CodeNode(endpointId, NodeKind.ENDPOINT, httpMethod + " " + path));
                edges.add(edge);
            }
        }

        for (Pattern p : List.of(AUTH_USE_RE, AUTH_ADD_RE)) {
            String prefix = p == AUTH_USE_RE ? "Use" : "Add";
            Matcher am = p.matcher(text);
            while (am.find()) {
                String authType = am.group(1);
                int line = findLineNumber(text, am.start());
                CodeNode node = new CodeNode();
                node.setId("dotnet:" + filePath + ":guard:" + prefix + authType + ":" + line);
                node.setKind(NodeKind.GUARD);
                node.setLabel(prefix + authType);
                node.setFqn(prefix + authType);
                node.setFilePath(filePath);
                node.setLineStart(line);
                node.getProperties().put("guard_type", authType.toLowerCase());
                node.getProperties().put("framework", "dotnet_minimal_api");
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
