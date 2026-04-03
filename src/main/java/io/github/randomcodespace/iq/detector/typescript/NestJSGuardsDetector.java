package io.github.randomcodespace.iq.detector.typescript;

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
    name = "typescript.nestjs_guards",
    category = "auth",
    description = "Detects NestJS guards (AuthGuard, RolesGuard, custom guards)",
    parser = ParserType.REGEX,
    languages = {"typescript"},
    nodeKinds = {NodeKind.GUARD},
    properties = {"auth_type", "roles", "strategy"}
)
@Component
public class NestJSGuardsDetector extends AbstractAntlrDetector {

    private static final Pattern NESTJS_IMPORT = Pattern.compile("from\\s+['\"]@nestjs/");

    private static final Pattern USE_GUARDS_PATTERN = Pattern.compile(
            "@UseGuards\\(\\s*([^)]+)\\)"
    );

    private static final Pattern ROLES_PATTERN = Pattern.compile(
            "@Roles\\(\\s*([^)]+)\\)"
    );

    private static final Pattern CAN_ACTIVATE_PATTERN = Pattern.compile(
            "(?:async\\s+)?canActivate\\s*\\("
    );

    private static final Pattern AUTH_GUARD_PATTERN = Pattern.compile(
            "AuthGuard\\(\\s*['\"](\\w+)['\"]\\s*\\)"
    );

    private static final Pattern ROLE_STRING_PATTERN = Pattern.compile(
            "['\"]([\\w\\-]+)['\"]"
    );

    @Override
    public String getName() {
        return "typescript.nestjs_guards";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript");
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
        if (!NESTJS_IMPORT.matcher(text).find()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        // @UseGuards(...)
        Matcher matcher = USE_GUARDS_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            List<String> guardNames = parseGuardNames(matcher.group(1));
            for (String guardName : guardNames) {
                String nodeId = "auth:" + filePath + ":UseGuards(" + guardName + "):" + line;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.GUARD);
                node.setLabel("UseGuards(" + guardName + ")");
                node.setFqn(filePath + "::UseGuards(" + guardName + ")");
                node.setModule(moduleName);
                node.setFilePath(filePath);
                node.setLineStart(line);
                node.getAnnotations().add("@UseGuards");
                node.getProperties().put("auth_type", "nestjs_guard");
                node.getProperties().put("guard_name", guardName);
                node.getProperties().put("roles", List.of());
                nodes.add(node);
            }
        }

        // @Roles(...)
        matcher = ROLES_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            List<String> roles = parseRoles(matcher.group(1));
            String nodeId = "auth:" + filePath + ":Roles:" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.GUARD);
            node.setLabel("Roles(" + String.join(", ", roles) + ")");
            node.setFqn(filePath + "::Roles");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getAnnotations().add("@Roles");
            node.getProperties().put("auth_type", "nestjs_guard");
            node.getProperties().put("roles", roles);
            nodes.add(node);
        }

        // canActivate()
        matcher = CAN_ACTIVATE_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String nodeId = "auth:" + filePath + ":canActivate:" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.GUARD);
            node.setLabel("canActivate()");
            node.setFqn(filePath + "::canActivate");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("auth_type", "nestjs_guard");
            node.getProperties().put("guard_impl", "canActivate");
            node.getProperties().put("roles", List.of());
            nodes.add(node);
        }

        // AuthGuard('jwt')
        matcher = AUTH_GUARD_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String strategy = matcher.group(1);
            String nodeId = "auth:" + filePath + ":AuthGuard(" + strategy + "):" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.GUARD);
            node.setLabel("AuthGuard('" + strategy + "')");
            node.setFqn(filePath + "::AuthGuard(" + strategy + ")");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getAnnotations().add("AuthGuard");
            node.getProperties().put("auth_type", "nestjs_guard");
            node.getProperties().put("strategy", strategy);
            node.getProperties().put("roles", List.of());
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }

    private static List<String> parseGuardNames(String raw) {
        List<String> names = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty() && trimmed.matches("^\\w+$")) {
                names.add(trimmed);
            }
        }
        return names;
    }

    private static List<String> parseRoles(String raw) {
        List<String> roles = new ArrayList<>();
        Matcher m = ROLE_STRING_PATTERN.matcher(raw);
        while (m.find()) {
            roles.add(m.group(1));
        }
        return roles;
    }
}
