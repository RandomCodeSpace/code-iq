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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "django_auth",
    category = "auth",
    description = "Detects Django authentication (login_required, permissions, decorators)",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.GUARD},
    properties = {"auth_type", "permissions"}
)
@Component
public class DjangoAuthDetector extends AbstractPythonAntlrDetector {

    // --- Regex fallback patterns ---
    private static final Pattern LOGIN_REQUIRED_RE = Pattern.compile("@login_required\\b");
    private static final Pattern PERMISSION_REQUIRED_RE = Pattern.compile(
            "@permission_required\\(\\s*[\"']([^\"']*)[\"']"
    );
    private static final Pattern USER_PASSES_TEST_RE = Pattern.compile(
            "@user_passes_test\\(\\s*([^,)\\s]+)"
    );
    private static final Pattern MIXIN_RE = Pattern.compile(
            "class\\s+(\\w+)\\s*\\(([^)]*)\\):"
    );

    private static final Map<String, String> AUTH_MIXINS = Map.of(
            "LoginRequiredMixin", "login_required",
            "PermissionRequiredMixin", "permission_required",
            "UserPassesTestMixin", "user_passes_test"
    );

    @Override
    public String getName() {
        return "django_auth";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterDecorated(Python3Parser.DecoratedContext decorated) {
                if (decorated.decorators() == null) return;
                for (var dec : decorated.decorators().decorator()) {
                    if (dec.dotted_name() == null) continue;
                    String decoratorName = dec.dotted_name().getText();

                    // @login_required
                    if ("login_required".equals(decoratorName)) {
                        int line = lineOf(dec);
                        CodeNode node = new CodeNode();
                        node.setId("auth:" + filePath + ":login_required:" + line);
                        node.setKind(NodeKind.GUARD);
                        node.setLabel("@login_required");
                        node.setModule(moduleName);
                        node.setFilePath(filePath);
                        node.setLineStart(line);
                        node.setAnnotations(List.of("@login_required"));
                        node.getProperties().put("auth_type", "django");
                        node.getProperties().put("permissions", List.of());
                        node.getProperties().put("auth_required", true);
                        nodes.add(node);
                    }

                    // @permission_required("perm")
                    if ("permission_required".equals(decoratorName) && dec.arglist() != null) {
                        int line = lineOf(dec);
                        String permission = extractFirstStringArg(dec.arglist());
                        if (permission == null) permission = "";
                        CodeNode node = new CodeNode();
                        node.setId("auth:" + filePath + ":permission_required:" + line);
                        node.setKind(NodeKind.GUARD);
                        node.setLabel("@permission_required(" + permission + ")");
                        node.setModule(moduleName);
                        node.setFilePath(filePath);
                        node.setLineStart(line);
                        node.setAnnotations(List.of("@permission_required"));
                        node.getProperties().put("auth_type", "django");
                        node.getProperties().put("permissions", List.of(permission));
                        node.getProperties().put("auth_required", true);
                        nodes.add(node);
                    }

                    // @user_passes_test(fn)
                    if ("user_passes_test".equals(decoratorName) && dec.arglist() != null) {
                        int line = lineOf(dec);
                        String testFunc = extractFirstArgName(dec.arglist());
                        if (testFunc == null) testFunc = "";
                        CodeNode node = new CodeNode();
                        node.setId("auth:" + filePath + ":user_passes_test:" + line);
                        node.setKind(NodeKind.GUARD);
                        node.setLabel("@user_passes_test(" + testFunc + ")");
                        node.setModule(moduleName);
                        node.setFilePath(filePath);
                        node.setLineStart(line);
                        node.setAnnotations(List.of("@user_passes_test"));
                        node.getProperties().put("auth_type", "django");
                        node.getProperties().put("permissions", List.of());
                        node.getProperties().put("test_function", testFunc);
                        node.getProperties().put("auth_required", true);
                        nodes.add(node);
                    }
                }
            }

            @Override
            public void enterClassdef(Python3Parser.ClassdefContext classCtx) {
                if (classCtx.name() == null) return;
                String className = classCtx.name().getText();
                if (classCtx.arglist() == null) return;

                for (var arg : classCtx.arglist().argument()) {
                    String base = arg.getText().trim();
                    if (AUTH_MIXINS.containsKey(base)) {
                        int line = lineOf(classCtx);
                        CodeNode node = new CodeNode();
                        node.setId("auth:" + filePath + ":" + base + ":" + line);
                        node.setKind(NodeKind.GUARD);
                        node.setLabel(className + "(" + base + ")");
                        node.setModule(moduleName);
                        node.setFilePath(filePath);
                        node.setLineStart(line);
                        node.setAnnotations(List.of("mixin:" + base));
                        node.getProperties().put("auth_type", "django");
                        node.getProperties().put("permissions", List.of());
                        node.getProperties().put("mixin", base);
                        node.getProperties().put("class_name", className);
                        node.getProperties().put("auth_required", true);
                        nodes.add(node);
                    }
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

        Matcher m = LOGIN_REQUIRED_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId("auth:" + filePath + ":login_required:" + line);
            node.setKind(NodeKind.GUARD);
            node.setLabel("@login_required");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("@login_required"));
            node.getProperties().put("auth_type", "django");
            node.getProperties().put("permissions", List.of());
            node.getProperties().put("auth_required", true);
            nodes.add(node);
        }

        m = PERMISSION_REQUIRED_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            String permission = m.group(1);
            CodeNode node = new CodeNode();
            node.setId("auth:" + filePath + ":permission_required:" + line);
            node.setKind(NodeKind.GUARD);
            node.setLabel("@permission_required(" + permission + ")");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("@permission_required"));
            node.getProperties().put("auth_type", "django");
            node.getProperties().put("permissions", List.of(permission));
            node.getProperties().put("auth_required", true);
            nodes.add(node);
        }

        m = USER_PASSES_TEST_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            String testFunc = m.group(1);
            CodeNode node = new CodeNode();
            node.setId("auth:" + filePath + ":user_passes_test:" + line);
            node.setKind(NodeKind.GUARD);
            node.setLabel("@user_passes_test(" + testFunc + ")");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("@user_passes_test"));
            node.getProperties().put("auth_type", "django");
            node.getProperties().put("permissions", List.of());
            node.getProperties().put("test_function", testFunc);
            node.getProperties().put("auth_required", true);
            nodes.add(node);
        }

        m = MIXIN_RE.matcher(text);
        while (m.find()) {
            String className = m.group(1);
            String basesStr = m.group(2);
            String[] bases = basesStr.split(",");
            for (String base : bases) {
                String trimmed = base.trim();
                if (AUTH_MIXINS.containsKey(trimmed)) {
                    int line = findLineNumber(text, m.start());
                    CodeNode node = new CodeNode();
                    node.setId("auth:" + filePath + ":" + trimmed + ":" + line);
                    node.setKind(NodeKind.GUARD);
                    node.setLabel(className + "(" + trimmed + ")");
                    node.setModule(moduleName);
                    node.setFilePath(filePath);
                    node.setLineStart(line);
                    node.setAnnotations(List.of("mixin:" + trimmed));
                    node.getProperties().put("auth_type", "django");
                    node.getProperties().put("permissions", List.of());
                    node.getProperties().put("mixin", trimmed);
                    node.getProperties().put("class_name", className);
                    node.getProperties().put("auth_required", true);
                    nodes.add(node);
                }
            }
        }

        return DetectorResult.of(nodes, List.of());
    }

    private static String extractFirstStringArg(Python3Parser.ArglistContext arglist) {
        if (arglist == null) return null;
        for (var arg : arglist.argument()) {
            String argText = arg.getText();
            if ((argText.startsWith("\"") && argText.endsWith("\""))
                    || (argText.startsWith("'") && argText.endsWith("'"))) {
                return argText.substring(1, argText.length() - 1);
            }
        }
        return null;
    }

    private static String extractFirstArgName(Python3Parser.ArglistContext arglist) {
        if (arglist == null || arglist.argument().isEmpty()) return null;
        return arglist.argument(0).getText();
    }
}
