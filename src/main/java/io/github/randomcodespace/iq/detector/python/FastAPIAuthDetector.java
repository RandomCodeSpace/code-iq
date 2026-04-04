package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.python.Python3ParserBaseListener;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "fastapi_auth",
    category = "auth",
    description = "Detects FastAPI authentication dependencies (OAuth2, API keys)",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.GUARD},
    properties = {"auth_type"}
)
@Component
public class FastAPIAuthDetector extends AbstractPythonAntlrDetector {
    private static final String PROP_AUTH_FLOW = "auth_flow";
    private static final String PROP_AUTH_REQUIRED = "auth_required";
    private static final String PROP_AUTH_TYPE = "auth_type";
    private static final String PROP_FASTAPI = "fastapi";
    private static final String PROP_OAUTH2 = "oauth2";


    // --- Regex fallback patterns ---
    private static final Pattern DEPENDS_AUTH_RE = Pattern.compile(
            "Depends\\(\\s*(get_current[\\w]*|require_auth[\\w]*|auth[\\w]*)\\s*\\)"
    );
    private static final Pattern SECURITY_RE = Pattern.compile(
            "Security\\(\\s*(\\w+)"
    );
    private static final Pattern HTTP_BEARER_RE = Pattern.compile(
            "HTTPBearer\\s*\\("
    );
    private static final Pattern OAUTH2_PASSWORD_BEARER_RE = Pattern.compile(
            "OAuth2PasswordBearer\\s*\\(\\s*tokenUrl\\s*=\\s*[\"']([^\"']*)[\"']"
    );
    private static final Pattern HTTP_BASIC_RE = Pattern.compile(
            "HTTPBasic\\s*\\("
    );

    @Override
    public String getName() {
        return "fastapi_auth";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterAtom_expr(Python3Parser.Atom_exprContext atomExpr) {
                String text = atomExpr.getText();

                if (text.startsWith("Depends(")) {
                    Matcher m = DEPENDS_AUTH_RE.matcher(text);
                    if (m.find()) {
                        nodes.add(createDependsGuard(filePath, moduleName, lineOf(atomExpr), m.group(1)));
                    }
                }

                if (text.startsWith("Security(")) {
                    Matcher m = SECURITY_RE.matcher(text);
                    if (m.find()) {
                        nodes.add(createSecurityGuard(filePath, moduleName, lineOf(atomExpr), m.group(1)));
                    }
                }

                if (text.contains("HTTPBearer(")) {
                    nodes.add(createHttpBearerGuard(filePath, moduleName, lineOf(atomExpr)));
                }

                if (text.contains("OAuth2PasswordBearer(")) {
                    Matcher m = OAUTH2_PASSWORD_BEARER_RE.matcher(text);
                    if (m.find()) {
                        nodes.add(createOAuth2PasswordBearerGuard(filePath, moduleName, lineOf(atomExpr), m.group(1)));
                    }
                }

                if (text.contains("HTTPBasic(")) {
                    nodes.add(createHttpBasicGuard(filePath, moduleName, lineOf(atomExpr)));
                }
            }
        }, tree);

        return DetectorResult.of(nodes, List.of());
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        Matcher m = DEPENDS_AUTH_RE.matcher(text);
        while (m.find()) {
            nodes.add(createDependsGuard(filePath, moduleName, findLineNumber(text, m.start()), m.group(1)));
        }

        m = SECURITY_RE.matcher(text);
        while (m.find()) {
            nodes.add(createSecurityGuard(filePath, moduleName, findLineNumber(text, m.start()), m.group(1)));
        }

        m = HTTP_BEARER_RE.matcher(text);
        while (m.find()) {
            nodes.add(createHttpBearerGuard(filePath, moduleName, findLineNumber(text, m.start())));
        }

        m = OAUTH2_PASSWORD_BEARER_RE.matcher(text);
        while (m.find()) {
            nodes.add(createOAuth2PasswordBearerGuard(filePath, moduleName, findLineNumber(text, m.start()), m.group(1)));
        }

        m = HTTP_BASIC_RE.matcher(text);
        while (m.find()) {
            nodes.add(createHttpBasicGuard(filePath, moduleName, findLineNumber(text, m.start())));
        }

        return DetectorResult.of(nodes, List.of());
    }

    private static CodeNode createDependsGuard(String filePath, String moduleName, int line, String depName) {
        CodeNode node = new CodeNode();
        node.setId("auth:" + filePath + ":Depends:" + line);
        node.setKind(NodeKind.GUARD);
        node.setLabel("Depends(" + depName + ")");
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.setAnnotations(List.of("Depends(" + depName + ")"));
        node.getProperties().put(PROP_AUTH_TYPE, PROP_FASTAPI);
        node.getProperties().put(PROP_AUTH_FLOW, PROP_OAUTH2);
        node.getProperties().put("dependency", depName);
        node.getProperties().put(PROP_AUTH_REQUIRED, true);
        return node;
    }

    private static CodeNode createSecurityGuard(String filePath, String moduleName, int line, String schemeName) {
        CodeNode node = new CodeNode();
        node.setId("auth:" + filePath + ":Security:" + line);
        node.setKind(NodeKind.GUARD);
        node.setLabel("Security(" + schemeName + ")");
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.setAnnotations(List.of("Security(" + schemeName + ")"));
        node.getProperties().put(PROP_AUTH_TYPE, PROP_FASTAPI);
        node.getProperties().put(PROP_AUTH_FLOW, PROP_OAUTH2);
        node.getProperties().put("scheme", schemeName);
        node.getProperties().put(PROP_AUTH_REQUIRED, true);
        return node;
    }

    private static CodeNode createHttpBearerGuard(String filePath, String moduleName, int line) {
        CodeNode node = new CodeNode();
        node.setId("auth:" + filePath + ":HTTPBearer:" + line);
        node.setKind(NodeKind.GUARD);
        node.setLabel("HTTPBearer()");
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.setAnnotations(List.of("HTTPBearer"));
        node.getProperties().put(PROP_AUTH_TYPE, PROP_FASTAPI);
        node.getProperties().put(PROP_AUTH_FLOW, "bearer");
        node.getProperties().put(PROP_AUTH_REQUIRED, true);
        return node;
    }

    private static CodeNode createOAuth2PasswordBearerGuard(String filePath, String moduleName, int line, String tokenUrl) {
        CodeNode node = new CodeNode();
        node.setId("auth:" + filePath + ":OAuth2PasswordBearer:" + line);
        node.setKind(NodeKind.GUARD);
        node.setLabel("OAuth2PasswordBearer(" + tokenUrl + ")");
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.setAnnotations(List.of("OAuth2PasswordBearer"));
        node.getProperties().put(PROP_AUTH_TYPE, PROP_FASTAPI);
        node.getProperties().put(PROP_AUTH_FLOW, PROP_OAUTH2);
        node.getProperties().put("token_url", tokenUrl);
        node.getProperties().put(PROP_AUTH_REQUIRED, true);
        return node;
    }

    private static CodeNode createHttpBasicGuard(String filePath, String moduleName, int line) {
        CodeNode node = new CodeNode();
        node.setId("auth:" + filePath + ":HTTPBasic:" + line);
        node.setKind(NodeKind.GUARD);
        node.setLabel("HTTPBasic()");
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.setAnnotations(List.of("HTTPBasic"));
        node.getProperties().put(PROP_AUTH_TYPE, PROP_FASTAPI);
        node.getProperties().put(PROP_AUTH_FLOW, "basic");
        node.getProperties().put(PROP_AUTH_REQUIRED, true);
        return node;
    }
}
