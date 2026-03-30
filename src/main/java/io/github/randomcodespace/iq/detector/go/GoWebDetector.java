package io.github.randomcodespace.iq.detector.go;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "go_web",
    category = "endpoints",
    description = "Detects Go web endpoints (Gin, Echo, Chi, net/http)",
    parser = ParserType.REGEX,
    languages = {"go"},
    nodeKinds = {NodeKind.ENDPOINT, NodeKind.MIDDLEWARE},
    properties = {"framework", "http_method", "method", "path"}
)
@Component
public class GoWebDetector extends AbstractAntlrDetector {

    private static final Pattern UPPER_ROUTE_RE = Pattern.compile("\\.(?<method>GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s*\\(\\s*\"(?<path>[^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern LOWER_ROUTE_RE = Pattern.compile("\\.(?<method>Get|Post|Put|Delete|Patch|Head|Options)\\s*\\(\\s*\"(?<path>[^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern HANDLEFUNC_RE = Pattern.compile("\\.HandleFunc\\s*\\(\\s*\"(?<path>[^\"]*)\".*?\\.Methods\\s*\\(\\s*\"(?<method>[A-Z]+)\"", Pattern.DOTALL);
    private static final Pattern HANDLEFUNC_NO_METHOD_RE = Pattern.compile("\\.HandleFunc\\s*\\(\\s*\"(?<path>[^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern HTTP_HANDLE_RE = Pattern.compile("http\\.(?:HandleFunc|Handle)\\s*\\(\\s*\"(?<path>[^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern GIN_RE = Pattern.compile("gin\\.(?:Default|New)\\s*\\(");
    private static final Pattern ECHO_RE = Pattern.compile("echo\\.New\\s*\\(");
    private static final Pattern CHI_RE = Pattern.compile("chi\\.NewRouter\\s*\\(");
    private static final Pattern MUX_RE = Pattern.compile("mux\\.NewRouter\\s*\\(");
    private static final Pattern USE_RE = Pattern.compile("\\.Use\\s*\\(\\s*(\\w+)");

    @Override
    public String getName() {
        return "go_web";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("go");
    }

    private static String detectFramework(String text) {
        if (GIN_RE.matcher(text).find()) return "gin";
        if (ECHO_RE.matcher(text).find()) return "echo";
        if (CHI_RE.matcher(text).find()) return "chi";
        if (MUX_RE.matcher(text).find()) return "mux";
        return "net_http";
    }
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
        String filePath = ctx.filePath();
        String framework = detectFramework(text);

        // Gin/Echo uppercase routes
        Matcher m = UPPER_ROUTE_RE.matcher(text);
        while (m.find()) {
            String method = m.group("method");
            String path = m.group("path");
            int line = findLineNumber(text, m.start());
            nodes.add(endpointNode(filePath, method, path, line, framework));
        }

        // Chi lowercase routes
        m = LOWER_ROUTE_RE.matcher(text);
        while (m.find()) {
            String method = m.group("method").toUpperCase();
            String path = m.group("path");
            int line = findLineNumber(text, m.start());
            nodes.add(endpointNode(filePath, method, path, line, "chi"));
        }

        // gorilla/mux HandleFunc with .Methods()
        Set<Integer> handleFuncWithMethodPositions = new HashSet<>();
        m = HANDLEFUNC_RE.matcher(text);
        while (m.find()) {
            String method = m.group("method");
            String path = m.group("path");
            int line = findLineNumber(text, m.start());
            nodes.add(endpointNode(filePath, method, path, line, "mux"));
            handleFuncWithMethodPositions.add(m.start());
        }

        // gorilla/mux HandleFunc without .Methods()
        if ("mux".equals(framework)) {
            m = HANDLEFUNC_NO_METHOD_RE.matcher(text);
            while (m.find()) {
                if (handleFuncWithMethodPositions.contains(m.start())) continue;
                String path = m.group("path");
                int line = findLineNumber(text, m.start());
                nodes.add(endpointNode(filePath, "ANY", path, line, "mux"));
            }
        }

        // net/http Handle/HandleFunc
        m = HTTP_HANDLE_RE.matcher(text);
        while (m.find()) {
            String path = m.group("path");
            int line = findLineNumber(text, m.start());
            nodes.add(endpointNode(filePath, "ANY", path, line, "net_http"));
        }

        // Middleware
        m = USE_RE.matcher(text);
        while (m.find()) {
            String mwName = m.group(1);
            int line = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId("go_web:" + filePath + ":middleware:" + mwName + ":" + line);
            node.setKind(NodeKind.MIDDLEWARE);
            node.setLabel(mwName);
            node.setFqn(filePath + "::middleware:" + mwName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("framework", framework);
            node.getProperties().put("middleware", mwName);
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }

    private static CodeNode endpointNode(String filePath, String method, String path, int line, String fw) {
        CodeNode node = new CodeNode();
        node.setId("go_web:" + filePath + ":" + method + ":" + path + ":" + line);
        node.setKind(NodeKind.ENDPOINT);
        node.setLabel(method + " " + path);
        node.setFqn(filePath + "::" + method + ":" + path);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("framework", fw);
        node.getProperties().put("http_method", method);
        node.getProperties().put("path", path);
        return node;
    }
}
