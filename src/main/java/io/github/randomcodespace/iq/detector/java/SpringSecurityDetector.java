package io.github.randomcodespace.iq.detector.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Spring Security auth patterns using JavaParser AST with regex fallback.
 */
@Component
public class SpringSecurityDetector extends AbstractJavaParserDetector {

    // ---- Regex fallback patterns ----
    private static final Pattern SECURED_RE = Pattern.compile(
            "@Secured\\(\\s*(?:\\{([^}]*)\\}|\"([^\"]*)\")\\s*\\)");
    private static final Pattern PRE_AUTHORIZE_RE = Pattern.compile(
            "@PreAuthorize\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern ROLES_ALLOWED_RE = Pattern.compile(
            "@RolesAllowed\\(\\s*(?:\\{([^}]*)\\}|\"([^\"]*)\")\\s*\\)");
    private static final Pattern ENABLE_WEB_SECURITY_RE = Pattern.compile("@EnableWebSecurity\\b");
    private static final Pattern ENABLE_METHOD_SECURITY_RE = Pattern.compile("@EnableMethodSecurity\\b");
    private static final Pattern SECURITY_FILTER_CHAIN_RE = Pattern.compile(
            "(?:public\\s+)?SecurityFilterChain\\s+(\\w+)\\s*\\(");
    private static final Pattern AUTHORIZE_HTTP_REQUESTS_RE = Pattern.compile(
            "\\.authorizeHttpRequests\\s*\\(");
    private static final Pattern ROLE_STR_RE = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern HAS_ROLE_RE = Pattern.compile("hasRole\\(\\s*'([^']*)'\\s*\\)");
    private static final Pattern HAS_ANY_ROLE_RE = Pattern.compile("hasAnyRole\\(\\s*([^)]+)\\)");
    private static final Pattern SINGLE_QUOTED_RE = Pattern.compile("'([^']*)'");

    @Override
    public String getName() {
        return "spring_security";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        Optional<CompilationUnit> cu = parse(ctx);
        if (cu.isPresent()) {
            return detectWithAst(cu.get(), ctx);
        }
        return detectWithRegex(ctx);
    }

    // ==================== AST-based detection ====================

    private DetectorResult detectWithAst(CompilationUnit cu, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            // Class-level annotations
            for (AnnotationExpr ann : classDecl.getAnnotations()) {
                String annName = ann.getNameAsString();
                int line = ann.getBegin().map(p -> p.line).orElse(1);

                if ("EnableWebSecurity".equals(annName)) {
                    nodes.add(guardNode("auth:" + ctx.filePath() + ":EnableWebSecurity:" + line,
                            "@EnableWebSecurity", line, ctx, List.of("@EnableWebSecurity"),
                            Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
                } else if ("EnableMethodSecurity".equals(annName)) {
                    nodes.add(guardNode("auth:" + ctx.filePath() + ":EnableMethodSecurity:" + line,
                            "@EnableMethodSecurity", line, ctx, List.of("@EnableMethodSecurity"),
                            Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
                }
            }

            // Method-level annotations and SecurityFilterChain
            for (MethodDeclaration method : classDecl.getMethods()) {
                int methodLine = method.getBegin().map(p -> p.line).orElse(1);

                // Check for SecurityFilterChain return type
                if ("SecurityFilterChain".equals(method.getTypeAsString())) {
                    String methodName = method.getNameAsString();
                    nodes.add(guardNode("auth:" + ctx.filePath() + ":SecurityFilterChain:" + methodLine,
                            "SecurityFilterChain:" + methodName, methodLine, ctx, List.of(),
                            Map.of("auth_type", "spring_security", "roles", List.of(), "method_name", methodName, "auth_required", true)));
                }

                for (AnnotationExpr ann : method.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    int line = ann.getBegin().map(p -> p.line).orElse(1);

                    if ("Secured".equals(annName)) {
                        List<String> roles = extractRolesFromAstAnnotation(ann);
                        nodes.add(guardNode("auth:" + ctx.filePath() + ":Secured:" + line,
                                "@Secured", line, ctx, List.of("@Secured"),
                                Map.of("auth_type", "spring_security", "roles", roles, "auth_required", true)));
                    } else if ("PreAuthorize".equals(annName)) {
                        String expr = extractAnnotationStringValue(ann);
                        List<String> roles = expr != null ? extractRolesFromSpel(expr) : List.of();
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put("auth_type", "spring_security");
                        props.put("roles", roles);
                        if (expr != null) props.put("expression", expr);
                        props.put("auth_required", true);
                        nodes.add(guardNode("auth:" + ctx.filePath() + ":PreAuthorize:" + line,
                                "@PreAuthorize", line, ctx, List.of("@PreAuthorize"), props));
                    } else if ("RolesAllowed".equals(annName)) {
                        List<String> roles = extractRolesFromAstAnnotation(ann);
                        nodes.add(guardNode("auth:" + ctx.filePath() + ":RolesAllowed:" + line,
                                "@RolesAllowed", line, ctx, List.of("@RolesAllowed"),
                                Map.of("auth_type", "spring_security", "roles", roles, "auth_required", true)));
                    }
                }
            }
        });

        // Also scan for .authorizeHttpRequests() which may appear in method bodies
        String text = ctx.content();
        for (Matcher m = AUTHORIZE_HTTP_REQUESTS_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            nodes.add(guardNode("auth:" + ctx.filePath() + ":authorizeHttpRequests:" + line,
                    ".authorizeHttpRequests()", line, ctx, List.of(),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
        }

        return DetectorResult.of(nodes, List.of());
    }

    private List<String> extractRolesFromAstAnnotation(AnnotationExpr ann) {
        List<String> roles = new ArrayList<>();
        if (ann.isSingleMemberAnnotationExpr()) {
            var val = ann.asSingleMemberAnnotationExpr().getMemberValue();
            if (val.isStringLiteralExpr()) {
                roles.add(val.asStringLiteralExpr().getValue());
            } else if (val.isArrayInitializerExpr()) {
                val.asArrayInitializerExpr().getValues().forEach(v -> {
                    if (v.isStringLiteralExpr()) {
                        roles.add(v.asStringLiteralExpr().getValue());
                    }
                });
            }
        } else if (ann.isNormalAnnotationExpr()) {
            ann.asNormalAnnotationExpr().getPairs().forEach(pair -> {
                if ("value".equals(pair.getNameAsString())) {
                    var val = pair.getValue();
                    if (val.isStringLiteralExpr()) {
                        roles.add(val.asStringLiteralExpr().getValue());
                    } else if (val.isArrayInitializerExpr()) {
                        val.asArrayInitializerExpr().getValues().forEach(v -> {
                            if (v.isStringLiteralExpr()) {
                                roles.add(v.asStringLiteralExpr().getValue());
                            }
                        });
                    }
                }
            });
        }
        return roles;
    }

    private String extractAnnotationStringValue(AnnotationExpr ann) {
        if (ann.isSingleMemberAnnotationExpr()) {
            var val = ann.asSingleMemberAnnotationExpr().getMemberValue();
            if (val.isStringLiteralExpr()) {
                return val.asStringLiteralExpr().getValue();
            }
        }
        return null;
    }

    // ==================== Regex fallback ====================

    private DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        List<CodeNode> nodes = new ArrayList<>();

        for (Matcher m = SECURED_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            List<String> roles = extractRolesFromAnnotation(m.group(1), m.group(2));
            nodes.add(guardNode("auth:" + ctx.filePath() + ":Secured:" + line,
                    "@Secured", line, ctx, List.of("@Secured"),
                    Map.of("auth_type", "spring_security", "roles", roles, "auth_required", true)));
        }

        for (Matcher m = PRE_AUTHORIZE_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            String expr = m.group(1);
            List<String> roles = extractRolesFromSpel(expr);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("auth_type", "spring_security");
            props.put("roles", roles);
            props.put("expression", expr);
            props.put("auth_required", true);
            nodes.add(guardNode("auth:" + ctx.filePath() + ":PreAuthorize:" + line,
                    "@PreAuthorize", line, ctx, List.of("@PreAuthorize"), props));
        }

        for (Matcher m = ROLES_ALLOWED_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            List<String> roles = extractRolesFromAnnotation(m.group(1), m.group(2));
            nodes.add(guardNode("auth:" + ctx.filePath() + ":RolesAllowed:" + line,
                    "@RolesAllowed", line, ctx, List.of("@RolesAllowed"),
                    Map.of("auth_type", "spring_security", "roles", roles, "auth_required", true)));
        }

        for (Matcher m = ENABLE_WEB_SECURITY_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            nodes.add(guardNode("auth:" + ctx.filePath() + ":EnableWebSecurity:" + line,
                    "@EnableWebSecurity", line, ctx, List.of("@EnableWebSecurity"),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
        }

        for (Matcher m = ENABLE_METHOD_SECURITY_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            nodes.add(guardNode("auth:" + ctx.filePath() + ":EnableMethodSecurity:" + line,
                    "@EnableMethodSecurity", line, ctx, List.of("@EnableMethodSecurity"),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
        }

        for (Matcher m = SECURITY_FILTER_CHAIN_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            String methodName = m.group(1);
            nodes.add(guardNode("auth:" + ctx.filePath() + ":SecurityFilterChain:" + line,
                    "SecurityFilterChain:" + methodName, line, ctx, List.of(),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "method_name", methodName, "auth_required", true)));
        }

        for (Matcher m = AUTHORIZE_HTTP_REQUESTS_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            nodes.add(guardNode("auth:" + ctx.filePath() + ":authorizeHttpRequests:" + line,
                    ".authorizeHttpRequests()", line, ctx, List.of(),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
        }

        return DetectorResult.of(nodes, List.of());
    }

    // ==================== Shared helpers ====================

    private CodeNode guardNode(String id, String label, int line, DetectorContext ctx,
                               List<String> annotations, Map<String, Object> properties) {
        CodeNode node = new CodeNode();
        node.setId(id);
        node.setKind(NodeKind.GUARD);
        node.setLabel(label);
        node.setFilePath(ctx.filePath());
        node.setLineStart(line);
        node.setAnnotations(new ArrayList<>(annotations));
        node.setProperties(new LinkedHashMap<>(properties));
        return node;
    }

    private List<String> extractRolesFromAnnotation(String multi, String single) {
        if (single != null) return List.of(single);
        if (multi != null) {
            List<String> roles = new ArrayList<>();
            for (Matcher m = ROLE_STR_RE.matcher(multi); m.find(); ) roles.add(m.group(1));
            return roles;
        }
        return List.of();
    }

    private List<String> extractRolesFromSpel(String expr) {
        List<String> roles = new ArrayList<>();
        for (Matcher m = HAS_ROLE_RE.matcher(expr); m.find(); ) roles.add(m.group(1));
        for (Matcher m = HAS_ANY_ROLE_RE.matcher(expr); m.find(); ) {
            String inner = m.group(1);
            for (Matcher q = SINGLE_QUOTED_RE.matcher(inner); q.find(); ) roles.add(q.group(1));
        }
        return roles;
    }
}
