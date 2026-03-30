package io.github.randomcodespace.iq.detector.rust;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
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
    name = "actix_web",
    category = "endpoints",
    description = "Detects Actix-web route handlers and middleware",
    parser = ParserType.REGEX,
    languages = {"rust"},
    nodeKinds = {NodeKind.ENDPOINT, NodeKind.MIDDLEWARE, NodeKind.MODULE},
    properties = {"framework", "http_method", "path"}
)
@Component
public class ActixWebDetector extends AbstractAntlrDetector {

    private static final Pattern ACTIX_ATTR_RE = Pattern.compile("#\\[(get|post|put|delete)\\s*\\(\\s*\"([^\"]*)\"\\s*\\)\\s*\\]");
    private static final Pattern HTTP_SERVER_RE = Pattern.compile("HttpServer::new\\s*\\(");
    private static final Pattern ROUTE_RE = Pattern.compile("\\.route\\s*\\(\\s*\"([^\"]*)\"\\s*,\\s*web::(get|post|put|delete)\\s*\\(\\s*\\)\\s*\\.to\\s*\\(\\s*(\\w+)");
    private static final Pattern SERVICE_RESOURCE_RE = Pattern.compile("\\.service\\s*\\(\\s*web::resource\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern AXUM_ROUTE_RE = Pattern.compile("\\.route\\s*\\(\\s*\"([^\"]*)\"\\s*,\\s*(get|post|put|delete)\\s*\\(\\s*(\\w+)\\s*\\)");
    private static final Pattern AXUM_LAYER_RE = Pattern.compile("\\.layer\\s*\\(\\s*(\\w+)");
    private static final Pattern MAIN_ATTR_RE = Pattern.compile("#\\[(actix_web::main|tokio::main)\\]");
    private static final Pattern FN_RE = Pattern.compile("(?:pub\\s+)?(?:async\\s+)?fn\\s+(\\w+)");

    @Override
    public String getName() { return "actix_web"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("rust"); }
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

        boolean hasMarker = false;
        for (String marker : List.of("#[get", "#[post", "#[put", "#[delete", "HttpServer::new", "web::get", "web::post", "web::resource", "Router::new", ".layer(", "actix_web::main", "tokio::main", "actix_web", "axum")) {
            if (text.contains(marker)) { hasMarker = true; break; }
        }
        if (!hasMarker) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineno = i + 1;

            Matcher m = ACTIX_ATTR_RE.matcher(line);
            if (m.find()) {
                String method = m.group(1).toUpperCase();
                String path = m.group(2);
                String fnName = null;
                for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                    Matcher fm = FN_RE.matcher(lines[k]);
                    if (fm.find()) { fnName = fm.group(1); break; }
                }
                CodeNode node = new CodeNode();
                node.setId("rust_web:" + filePath + ":" + method + ":" + path + ":" + lineno);
                node.setKind(NodeKind.ENDPOINT);
                node.setLabel(method + " " + path);
                node.setFqn(fnName);
                node.setFilePath(filePath);
                node.setLineStart(lineno);
                node.getProperties().put("framework", "actix_web");
                node.getProperties().put("http_method", method);
                node.getProperties().put("path", path);
                nodes.add(node);
            }

            m = HTTP_SERVER_RE.matcher(line);
            if (m.find()) {
                CodeNode node = new CodeNode();
                node.setId("rust_web:" + filePath + ":http_server:" + lineno);
                node.setKind(NodeKind.MODULE);
                node.setLabel("HttpServer");
                node.setFqn("HttpServer");
                node.setFilePath(filePath);
                node.setLineStart(lineno);
                node.getProperties().put("framework", "actix_web");
                nodes.add(node);
            }

            m = ROUTE_RE.matcher(line);
            if (m.find()) {
                String path = m.group(1);
                String method = m.group(2).toUpperCase();
                String handler = m.group(3);
                CodeNode node = new CodeNode();
                node.setId("rust_web:" + filePath + ":" + method + ":" + path + ":" + lineno);
                node.setKind(NodeKind.ENDPOINT);
                node.setLabel(method + " " + path);
                node.setFqn(handler);
                node.setFilePath(filePath);
                node.setLineStart(lineno);
                node.getProperties().put("framework", "actix_web");
                node.getProperties().put("http_method", method);
                node.getProperties().put("path", path);
                node.getProperties().put("handler", handler);
                nodes.add(node);
            }

            m = SERVICE_RESOURCE_RE.matcher(line);
            if (m.find()) {
                String path = m.group(1);
                CodeNode node = new CodeNode();
                node.setId("rust_web:" + filePath + ":resource:" + path + ":" + lineno);
                node.setKind(NodeKind.ENDPOINT);
                node.setLabel("resource " + path);
                node.setFqn(path);
                node.setFilePath(filePath);
                node.setLineStart(lineno);
                node.getProperties().put("framework", "actix_web");
                node.getProperties().put("path", path);
                nodes.add(node);
            }

            m = AXUM_ROUTE_RE.matcher(line);
            if (m.find()) {
                String path = m.group(1);
                String method = m.group(2).toUpperCase();
                String handler = m.group(3);
                CodeNode node = new CodeNode();
                node.setId("rust_web:" + filePath + ":" + method + ":" + path + ":" + lineno);
                node.setKind(NodeKind.ENDPOINT);
                node.setLabel(method + " " + path);
                node.setFqn(handler);
                node.setFilePath(filePath);
                node.setLineStart(lineno);
                node.getProperties().put("framework", "axum");
                node.getProperties().put("http_method", method);
                node.getProperties().put("path", path);
                node.getProperties().put("handler", handler);
                nodes.add(node);
            }

            m = AXUM_LAYER_RE.matcher(line);
            if (m.find()) {
                String mwName = m.group(1);
                CodeNode node = new CodeNode();
                node.setId("rust_web:" + filePath + ":layer:" + mwName + ":" + lineno);
                node.setKind(NodeKind.MIDDLEWARE);
                node.setLabel("layer(" + mwName + ")");
                node.setFqn(mwName);
                node.setFilePath(filePath);
                node.setLineStart(lineno);
                node.getProperties().put("framework", "axum");
                node.getProperties().put("middleware", mwName);
                nodes.add(node);
            }

            m = MAIN_ATTR_RE.matcher(line);
            if (m.find()) {
                String attr = m.group(1);
                CodeNode node = new CodeNode();
                node.setId("rust_web:" + filePath + ":main:" + lineno);
                node.setKind(NodeKind.MODULE);
                node.setLabel("#[" + attr + "]");
                node.setFqn("main");
                node.setFilePath(filePath);
                node.setLineStart(lineno);
                node.getProperties().put("framework", attr.contains("actix") ? "actix_web" : "tokio");
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, List.of());
    }
}
