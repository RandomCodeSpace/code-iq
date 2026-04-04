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
import io.github.randomcodespace.iq.detector.DetectorInfo;

/**
 * Detects Quarkus-specific patterns in Java source files.
 */
@DetectorInfo(
    name = "quarkus",
    category = "endpoints",
    description = "Detects Quarkus REST endpoints, scheduled tasks, and observers",
    languages = {"java"},
    nodeKinds = {NodeKind.CLASS, NodeKind.CONFIG_KEY, NodeKind.EVENT, NodeKind.MIDDLEWARE},
    properties = {"framework", "schedule"}
)
@Component
public class QuarkusDetector extends AbstractRegexDetector {
    private static final String PROP_FRAMEWORK = "framework";
    private static final String PROP_QUARKUS = "quarkus";


    private static final Pattern QUARKUS_TEST_RE = Pattern.compile("@QuarkusTest\\b");
    private static final Pattern CONFIG_PROPERTY_RE = Pattern.compile("@ConfigProperty\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern CDI_SCOPE_RE = Pattern.compile("@(Inject|Singleton|ApplicationScoped|RequestScoped)\\b");
    private static final Pattern SCHEDULED_RE = Pattern.compile("@Scheduled\\s*\\(\\s*(?:every|cron)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TRANSACTIONAL_RE = Pattern.compile("@Transactional\\b");
    private static final Pattern STARTUP_RE = Pattern.compile("@Startup\\b");
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");

    @Override
    public String getName() {
        return PROP_QUARKUS;
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        // First, require a Quarkus-specific indicator to avoid false positives on
        // Spring Boot or other frameworks that share common annotations like
        // @Transactional, @Scheduled, @Singleton, @ApplicationScoped, etc.
        boolean hasQuarkusIndicator = text.contains("io.quarkus")
                || text.contains("io.smallrye")
                || text.contains("@QuarkusTest");
        if (!hasQuarkusIndicator) {
            return DetectorResult.empty();
        }

        if (!text.contains("@QuarkusTest") && !text.contains("@ConfigProperty")
                && !text.contains("@Singleton") && !text.contains("@ApplicationScoped")
                && !text.contains("@RequestScoped") && !text.contains("@Scheduled")
                && !text.contains("@Transactional") && !text.contains("@Startup")
                && !text.contains("io.quarkus")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();

        String className = null;
        for (String line : lines) {
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) { className = cm.group(1); break; }
        }

        for (int i = 0; i < lines.length; i++) {
            int lineno = i + 1;

            if (QUARKUS_TEST_RE.matcher(lines[i]).find()) {
                nodes.add(makeNode("quarkus:" + ctx.filePath() + ":quarkus_test:" + lineno,
                        NodeKind.CLASS, "@QuarkusTest " + (className != null ? className : "unknown"),
                        className, lineno, ctx, List.of("@QuarkusTest"),
                        Map.of(PROP_FRAMEWORK, PROP_QUARKUS, "test", true)));
            }

            Matcher cpm = CONFIG_PROPERTY_RE.matcher(lines[i]);
            if (cpm.find()) {
                String configKey = cpm.group(1);
                nodes.add(makeNode("quarkus:" + ctx.filePath() + ":config_property:" + lineno,
                        NodeKind.CONFIG_KEY, "@ConfigProperty(" + configKey + ")",
                        configKey, lineno, ctx, List.of("@ConfigProperty"),
                        Map.of(PROP_FRAMEWORK, PROP_QUARKUS, "config_key", configKey)));
            }

            Matcher cdim = CDI_SCOPE_RE.matcher(lines[i]);
            if (cdim.find()) {
                String annotation = cdim.group(1);
                nodes.add(makeNode("quarkus:" + ctx.filePath() + ":cdi_" + annotation.toLowerCase() + ":" + lineno,
                        NodeKind.MIDDLEWARE, "@" + annotation + " (CDI)",
                        className != null ? className + "." + annotation : annotation, lineno, ctx,
                        List.of("@" + annotation),
                        Map.of(PROP_FRAMEWORK, PROP_QUARKUS, "cdi_scope", annotation)));
            }

            Matcher sm = SCHEDULED_RE.matcher(lines[i]);
            if (sm.find()) {
                String scheduleExpr = sm.group(1);
                nodes.add(makeNode("quarkus:" + ctx.filePath() + ":scheduled:" + lineno,
                        NodeKind.EVENT, "@Scheduled(" + scheduleExpr + ")",
                        className != null ? className + ".scheduled" : "scheduled", lineno, ctx,
                        List.of("@Scheduled"),
                        Map.of(PROP_FRAMEWORK, PROP_QUARKUS, "schedule", scheduleExpr)));
            }

            if (TRANSACTIONAL_RE.matcher(lines[i]).find()) {
                nodes.add(makeNode("quarkus:" + ctx.filePath() + ":transactional:" + lineno,
                        NodeKind.MIDDLEWARE, "@Transactional",
                        className != null ? className + ".transactional" : "transactional", lineno, ctx,
                        List.of("@Transactional"),
                        Map.of(PROP_FRAMEWORK, PROP_QUARKUS)));
            }

            if (STARTUP_RE.matcher(lines[i]).find()) {
                nodes.add(makeNode("quarkus:" + ctx.filePath() + ":startup:" + lineno,
                        NodeKind.MIDDLEWARE, "@Startup " + (className != null ? className : "unknown"),
                        className, lineno, ctx, List.of("@Startup"),
                        Map.of(PROP_FRAMEWORK, PROP_QUARKUS)));
            }
        }

        return DetectorResult.of(nodes, List.of());
    }

    private CodeNode makeNode(String id, NodeKind kind, String label, String fqn, int line,
                              DetectorContext ctx, List<String> annotations, Map<String, Object> properties) {
        CodeNode node = new CodeNode();
        node.setId(id);
        node.setKind(kind);
        node.setLabel(label);
        node.setFqn(fqn);
        node.setFilePath(ctx.filePath());
        node.setLineStart(line);
        node.setAnnotations(new ArrayList<>(annotations));
        node.setProperties(new LinkedHashMap<>(properties));
        return node;
    }
}
