package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import org.antlr.v4.runtime.tree.ParseTree;
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

@Component
public class PassportJwtDetector extends AbstractAntlrDetector {

    private static final Pattern PASSPORT_USE_PATTERN = Pattern.compile(
            "passport\\.use\\(\\s*new\\s+(\\w+Strategy)\\s*\\("
    );

    private static final Pattern PASSPORT_AUTH_PATTERN = Pattern.compile(
            "passport\\.authenticate\\(\\s*['\"](\\w+)['\"]"
    );

    private static final Pattern JWT_VERIFY_PATTERN = Pattern.compile(
            "jwt\\.verify\\s*\\("
    );

    private static final Pattern REQUIRE_EXPRESS_JWT_PATTERN = Pattern.compile(
            "require\\(\\s*['\"]express-jwt['\"]\\s*\\)"
    );

    private static final Pattern IMPORT_EXPRESS_JWT_PATTERN = Pattern.compile(
            "import\\s+\\{[^}]*\\bexpressjwt\\b[^}]*\\}\\s+from\\s+['\"]express-jwt['\"]"
    );

    @Override
    public String getName() {
        return "typescript.passport_jwt";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        return AntlrParserFactory.parse(ctx.language(), ctx.content());
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        // passport.use(new XxxStrategy(...))
        Matcher matcher = PASSPORT_USE_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String strategyName = matcher.group(1);
            String nodeId = "auth:" + filePath + ":passport.use(" + strategyName + "):" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.GUARD);
            node.setLabel("passport.use(" + strategyName + ")");
            node.setFqn(filePath + "::passport.use(" + strategyName + ")");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("auth_type", "passport");
            node.getProperties().put("strategy", strategyName);
            nodes.add(node);
        }

        // passport.authenticate('xxx')
        matcher = PASSPORT_AUTH_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String strategy = matcher.group(1);
            String nodeId = "auth:" + filePath + ":passport.authenticate(" + strategy + "):" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MIDDLEWARE);
            node.setLabel("passport.authenticate('" + strategy + "')");
            node.setFqn(filePath + "::passport.authenticate(" + strategy + ")");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("auth_type", "jwt");
            node.getProperties().put("strategy", strategy);
            nodes.add(node);
        }

        // jwt.verify(...)
        matcher = JWT_VERIFY_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String nodeId = "auth:" + filePath + ":jwt.verify:" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MIDDLEWARE);
            node.setLabel("jwt.verify()");
            node.setFqn(filePath + "::jwt.verify");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("auth_type", "jwt");
            nodes.add(node);
        }

        // require('express-jwt')
        matcher = REQUIRE_EXPRESS_JWT_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String nodeId = "auth:" + filePath + ":require(express-jwt):" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MIDDLEWARE);
            node.setLabel("require('express-jwt')");
            node.setFqn(filePath + "::require(express-jwt)");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("auth_type", "jwt");
            node.getProperties().put("library", "express-jwt");
            nodes.add(node);
        }

        // import { expressjwt } from 'express-jwt'
        matcher = IMPORT_EXPRESS_JWT_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String nodeId = "auth:" + filePath + ":import(expressjwt):" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MIDDLEWARE);
            node.setLabel("import { expressjwt }");
            node.setFqn(filePath + "::import(expressjwt)");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("auth_type", "jwt");
            node.getProperties().put("library", "express-jwt");
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }
}
