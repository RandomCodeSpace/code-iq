package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.python.Python3ParserBaseListener;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FastAPIAuthDetector extends AbstractAntlrDetector {

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
    public Set<String> getSupportedLanguages() {
        return Set.of("python");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        return AntlrParserFactory.parse("python", ctx.content());
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

                // Depends(get_current_user...) / Depends(require_auth...) / Depends(auth...)
                if (text.startsWith("Depends(")) {
                    Matcher m = DEPENDS_AUTH_RE.matcher(text);
                    if (m.find()) {
                        int line = lineOf(atomExpr);
                        String depName = m.group(1);
                        CodeNode node = new CodeNode();
                        node.setId("auth:" + filePath + ":Depends:" + line);
                        node.setKind(NodeKind.GUARD);
                        node.setLabel("Depends(" + depName + ")");
                        node.setModule(moduleName);
                        node.setFilePath(filePath);
                        node.setLineStart(line);
                        node.setAnnotations(List.of("Depends(" + depName + ")"));
                        node.getProperties().put("auth_type", "fastapi");
                        node.getProperties().put("auth_flow", "oauth2");
                        node.getProperties().put("dependency", depName);
                        node.getProperties().put("auth_required", true);
                        nodes.add(node);
                    }
                }

                // Security(scheme)
                if (text.startsWith("Security(")) {
                    Matcher m = SECURITY_RE.matcher(text);
                    if (m.find()) {
                        int line = lineOf(atomExpr);
                        String schemeName = m.group(1);
                        CodeNode node = new CodeNode();
                        node.setId("auth:" + filePath + ":Security:" + line);
                        node.setKind(NodeKind.GUARD);
                        node.setLabel("Security(" + schemeName + ")");
                        node.setModule(moduleName);
                        node.setFilePath(filePath);
                        node.setLineStart(line);
                        node.setAnnotations(List.of("Security(" + schemeName + ")"));
                        node.getProperties().put("auth_type", "fastapi");
                        node.getProperties().put("auth_flow", "oauth2");
                        node.getProperties().put("scheme", schemeName);
                        node.getProperties().put("auth_required", true);
                        nodes.add(node);
                    }
                }

                // HTTPBearer()
                if (text.contains("HTTPBearer(")) {
                    int line = lineOf(atomExpr);
                    CodeNode node = new CodeNode();
                    node.setId("auth:" + filePath + ":HTTPBearer:" + line);
                    node.setKind(NodeKind.GUARD);
                    node.setLabel("HTTPBearer()");
                    node.setModule(moduleName);
                    node.setFilePath(filePath);
                    node.setLineStart(line);
                    node.setAnnotations(List.of("HTTPBearer"));
                    node.getProperties().put("auth_type", "fastapi");
                    node.getProperties().put("auth_flow", "bearer");
                    node.getProperties().put("auth_required", true);
                    nodes.add(node);
                }

                // OAuth2PasswordBearer(tokenUrl=...)
                if (text.contains("OAuth2PasswordBearer(")) {
                    Matcher m = OAUTH2_PASSWORD_BEARER_RE.matcher(text);
                    if (m.find()) {
                        int line = lineOf(atomExpr);
                        String tokenUrl = m.group(1);
                        CodeNode node = new CodeNode();
                        node.setId("auth:" + filePath + ":OAuth2PasswordBearer:" + line);
                        node.setKind(NodeKind.GUARD);
                        node.setLabel("OAuth2PasswordBearer(" + tokenUrl + ")");
                        node.setModule(moduleName);
                        node.setFilePath(filePath);
                        node.setLineStart(line);
                        node.setAnnotations(List.of("OAuth2PasswordBearer"));
                        node.getProperties().put("auth_type", "fastapi");
                        node.getProperties().put("auth_flow", "oauth2");
                        node.getProperties().put("token_url", tokenUrl);
                        node.getProperties().put("auth_required", true);
                        nodes.add(node);
                    }
                }

                // HTTPBasic()
                if (text.contains("HTTPBasic(")) {
                    int line = lineOf(atomExpr);
                    CodeNode node = new CodeNode();
                    node.setId("auth:" + filePath + ":HTTPBasic:" + line);
                    node.setKind(NodeKind.GUARD);
                    node.setLabel("HTTPBasic()");
                    node.setModule(moduleName);
                    node.setFilePath(filePath);
                    node.setLineStart(line);
                    node.setAnnotations(List.of("HTTPBasic"));
                    node.getProperties().put("auth_type", "fastapi");
                    node.getProperties().put("auth_flow", "basic");
                    node.getProperties().put("auth_required", true);
                    nodes.add(node);
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
            int line = findLineNumber(text, m.start());
            String depName = m.group(1);
            CodeNode node = new CodeNode();
            node.setId("auth:" + filePath + ":Depends:" + line);
            node.setKind(NodeKind.GUARD);
            node.setLabel("Depends(" + depName + ")");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("Depends(" + depName + ")"));
            node.getProperties().put("auth_type", "fastapi");
            node.getProperties().put("auth_flow", "oauth2");
            node.getProperties().put("dependency", depName);
            node.getProperties().put("auth_required", true);
            nodes.add(node);
        }

        m = SECURITY_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            String schemeName = m.group(1);
            CodeNode node = new CodeNode();
            node.setId("auth:" + filePath + ":Security:" + line);
            node.setKind(NodeKind.GUARD);
            node.setLabel("Security(" + schemeName + ")");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("Security(" + schemeName + ")"));
            node.getProperties().put("auth_type", "fastapi");
            node.getProperties().put("auth_flow", "oauth2");
            node.getProperties().put("scheme", schemeName);
            node.getProperties().put("auth_required", true);
            nodes.add(node);
        }

        m = HTTP_BEARER_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId("auth:" + filePath + ":HTTPBearer:" + line);
            node.setKind(NodeKind.GUARD);
            node.setLabel("HTTPBearer()");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("HTTPBearer"));
            node.getProperties().put("auth_type", "fastapi");
            node.getProperties().put("auth_flow", "bearer");
            node.getProperties().put("auth_required", true);
            nodes.add(node);
        }

        m = OAUTH2_PASSWORD_BEARER_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            String tokenUrl = m.group(1);
            CodeNode node = new CodeNode();
            node.setId("auth:" + filePath + ":OAuth2PasswordBearer:" + line);
            node.setKind(NodeKind.GUARD);
            node.setLabel("OAuth2PasswordBearer(" + tokenUrl + ")");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("OAuth2PasswordBearer"));
            node.getProperties().put("auth_type", "fastapi");
            node.getProperties().put("auth_flow", "oauth2");
            node.getProperties().put("token_url", tokenUrl);
            node.getProperties().put("auth_required", true);
            nodes.add(node);
        }

        m = HTTP_BASIC_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId("auth:" + filePath + ":HTTPBasic:" + line);
            node.setKind(NodeKind.GUARD);
            node.setLabel("HTTPBasic()");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("HTTPBasic"));
            node.getProperties().put("auth_type", "fastapi");
            node.getProperties().put("auth_flow", "basic");
            node.getProperties().put("auth_required", true);
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }
}
