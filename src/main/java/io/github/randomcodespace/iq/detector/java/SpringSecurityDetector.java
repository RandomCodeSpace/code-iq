package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Spring Security auth patterns in Java source files.
 */
@Component
public class SpringSecurityDetector extends AbstractRegexDetector {

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
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }

        List<CodeNode> nodes = new ArrayList<>();

        // @Secured
        for (Matcher m = SECURED_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            List<String> roles = extractRolesFromAnnotation(m.group(1), m.group(2));
            nodes.add(guardNode("auth:" + ctx.filePath() + ":Secured:" + line,
                    "@Secured", line, ctx, List.of("@Secured"),
                    Map.of("auth_type", "spring_security", "roles", roles, "auth_required", true)));
        }

        // @PreAuthorize
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

        // @RolesAllowed
        for (Matcher m = ROLES_ALLOWED_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            List<String> roles = extractRolesFromAnnotation(m.group(1), m.group(2));
            nodes.add(guardNode("auth:" + ctx.filePath() + ":RolesAllowed:" + line,
                    "@RolesAllowed", line, ctx, List.of("@RolesAllowed"),
                    Map.of("auth_type", "spring_security", "roles", roles, "auth_required", true)));
        }

        // @EnableWebSecurity
        for (Matcher m = ENABLE_WEB_SECURITY_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            nodes.add(guardNode("auth:" + ctx.filePath() + ":EnableWebSecurity:" + line,
                    "@EnableWebSecurity", line, ctx, List.of("@EnableWebSecurity"),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
        }

        // @EnableMethodSecurity
        for (Matcher m = ENABLE_METHOD_SECURITY_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            nodes.add(guardNode("auth:" + ctx.filePath() + ":EnableMethodSecurity:" + line,
                    "@EnableMethodSecurity", line, ctx, List.of("@EnableMethodSecurity"),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
        }

        // SecurityFilterChain
        for (Matcher m = SECURITY_FILTER_CHAIN_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            String methodName = m.group(1);
            nodes.add(guardNode("auth:" + ctx.filePath() + ":SecurityFilterChain:" + line,
                    "SecurityFilterChain:" + methodName, line, ctx, List.of(),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "method_name", methodName, "auth_required", true)));
        }

        // .authorizeHttpRequests()
        for (Matcher m = AUTHORIZE_HTTP_REQUESTS_RE.matcher(text); m.find(); ) {
            int line = findLineNumber(text, m.start());
            nodes.add(guardNode("auth:" + ctx.filePath() + ":authorizeHttpRequests:" + line,
                    ".authorizeHttpRequests()", line, ctx, List.of(),
                    Map.of("auth_type", "spring_security", "roles", List.of(), "auth_required", true)));
        }

        return DetectorResult.of(nodes, List.of());
    }

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
        if (single != null) {
            return List.of(single);
        }
        if (multi != null) {
            List<String> roles = new ArrayList<>();
            for (Matcher m = ROLE_STR_RE.matcher(multi); m.find(); ) {
                roles.add(m.group(1));
            }
            return roles;
        }
        return List.of();
    }

    private List<String> extractRolesFromSpel(String expr) {
        List<String> roles = new ArrayList<>();
        for (Matcher m = HAS_ROLE_RE.matcher(expr); m.find(); ) {
            roles.add(m.group(1));
        }
        for (Matcher m = HAS_ANY_ROLE_RE.matcher(expr); m.find(); ) {
            String inner = m.group(1);
            for (Matcher q = SINGLE_QUOTED_RE.matcher(inner); q.find(); ) {
                roles.add(q.group(1));
            }
        }
        return roles;
    }
}
