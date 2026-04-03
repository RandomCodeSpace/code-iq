package io.github.randomcodespace.iq.detector.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects Kafka ConfigDef.define() configuration definitions, Spring @Value bindings,
 * and Spring @ConfigurationProperties class-level prefix declarations.
 */
@DetectorInfo(
    name = "config_def",
    category = "config",
    description = "Detects Kafka ConfigDef definitions, Spring @Value bindings, and @ConfigurationProperties prefixes",
    parser = ParserType.JAVAPARSER,
    languages = {"java"},
    nodeKinds = {NodeKind.CONFIG_DEFINITION},
    edgeKinds = {EdgeKind.READS_CONFIG}
)
@Component
public class ConfigDefDetector extends AbstractJavaParserDetector {

    // ---- Regex fallback patterns ----
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern DEFINE_RE = Pattern.compile("\\.define\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern VALUE_RE = Pattern.compile("@Value\\s*\\(\\s*\"\\$\\{([^}]+)\\}\"");
    private static final Pattern CONFIG_PROPS_RE = Pattern.compile("@ConfigurationProperties\\s*\\(\\s*(?:prefix\\s*=\\s*)?\"([^\"]+)\"");

    private static final String VALUE_ANNOTATION = "Value";
    private static final String CONFIG_PROPS_ANNOTATION = "ConfigurationProperties";

    @Override
    public String getName() {
        return "config_def";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null) return DetectorResult.empty();

        boolean hasConfigDef = text.contains("ConfigDef");
        boolean hasValue = text.contains("@Value");
        boolean hasConfigProps = text.contains("@ConfigurationProperties");

        if (!hasConfigDef && !hasValue && !hasConfigProps) return DetectorResult.empty();

        Optional<CompilationUnit> cu = parse(ctx);
        if (cu.isPresent()) {
            return detectWithAst(cu.get(), ctx);
        }
        return detectWithRegex(ctx);
    }

    // ==================== AST-based detection ====================

    private DetectorResult detectWithAst(CompilationUnit cu, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        // File-scoped seenKeys prevents duplicate config nodes when multiple classes
        // in the same file reference the same key (e.g., @Value("${server.port}")).
        Set<String> seenKeys = new LinkedHashSet<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            String className = classDecl.getNameAsString();
            String classNodeId = ctx.filePath() + ":" + className;

            // 1. Kafka ConfigDef.define() calls
            // Discriminator: receiver must mention "ConfigDef" or "CONFIG" to avoid matching
            // unrelated .define() calls (per CLAUDE.md: framework detectors must have guards).
            classDecl.findAll(MethodCallExpr.class).forEach(call -> {
                if (!"define".equals(call.getNameAsString())) return;
                if (call.getArguments().isEmpty()) return;
                // Receiver check: scope must reference a ConfigDef instance or chain
                boolean receiverIsConfigDef = call.getScope()
                        .map(scope -> {
                            String s = scope.toString();
                            return s.contains("ConfigDef") || s.toUpperCase().contains("CONFIG");
                        })
                        .orElse(false);
                if (!receiverIsConfigDef) return;
                var firstArg = call.getArguments().get(0);
                if (!firstArg.isStringLiteralExpr()) return;
                String configKey = firstArg.asStringLiteralExpr().getValue();
                if (seenKeys.add(configKey)) {
                    int line = call.getBegin().map(p -> p.line).orElse(1);
                    addConfigNode(configKey, "kafka_configdef", classNodeId, ctx.filePath(), line, nodes, edges);
                }
            });

            // 2. Spring @Value("${some.key}") on fields and method parameters
            classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                field.getAnnotations().forEach(ann -> {
                    if (!VALUE_ANNOTATION.equals(ann.getNameAsString())) return;
                    extractValueKey(ann).ifPresent(key -> {
                        if (seenKeys.add(key)) {
                            int line = ann.getBegin().map(p -> p.line).orElse(1);
                            addConfigNode(key, "spring_value", classNodeId, ctx.filePath(), line, nodes, edges);
                        }
                    });
                });
            });

            classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                method.getParameters().forEach(param -> {
                    param.getAnnotations().forEach(ann -> {
                        if (!VALUE_ANNOTATION.equals(ann.getNameAsString())) return;
                        extractValueKey(ann).ifPresent(key -> {
                            if (seenKeys.add(key)) {
                                int line = ann.getBegin().map(p -> p.line).orElse(1);
                                addConfigNode(key, "spring_value", classNodeId, ctx.filePath(), line, nodes, edges);
                            }
                        });
                    });
                });
            });

            // 3. @ConfigurationProperties(prefix="some.prefix") on class
            classDecl.getAnnotations().forEach(ann -> {
                if (!CONFIG_PROPS_ANNOTATION.equals(ann.getNameAsString())) return;
                extractAnnotationStringValue(ann).ifPresent(prefix -> {
                    if (seenKeys.add(prefix)) {
                        int line = ann.getBegin().map(p -> p.line).orElse(1);
                        addConfigNode(prefix, "spring_config_props", classNodeId, ctx.filePath(), line, nodes, edges);
                    }
                });
            });
        });

        return DetectorResult.of(nodes, edges);
    }

    private Optional<String> extractValueKey(AnnotationExpr ann) {
        // @Value("${some.key}") or @Value(value = "${some.key}")
        String raw = ann.toString();
        Matcher m = Pattern.compile("\"\\$\\{([^}]+)\\}\"").matcher(raw);
        if (m.find()) return Optional.of(m.group(1));
        return Optional.empty();
    }

    private Optional<String> extractAnnotationStringValue(AnnotationExpr ann) {
        // @ConfigurationProperties("prefix") or @ConfigurationProperties(prefix = "prefix")
        String raw = ann.toString();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(raw);
        if (m.find()) return Optional.of(m.group(1));
        return Optional.empty();
    }

    private void addConfigNode(String key, String source, String classNodeId,
                                String filePath, int line,
                                List<CodeNode> nodes, List<CodeEdge> edges) {
        String nodeId = "config:" + key;
        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(NodeKind.CONFIG_DEFINITION);
        node.setLabel(key);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("config_key", key);
        node.getProperties().put("config_source", source);
        nodes.add(node);

        CodeEdge edge = new CodeEdge();
        edge.setId(classNodeId + "->reads_config->" + nodeId);
        edge.setKind(EdgeKind.READS_CONFIG);
        edge.setSourceId(classNodeId);
        edge.setTarget(node);
        edge.setProperties(Map.of("config_key", key));
        edges.add(edge);
    }

    // ==================== Regex fallback ====================

    private DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        for (String line : lines) {
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) { className = cm.group(1); break; }
        }
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;
        Set<String> seenKeys = new LinkedHashSet<>();

        for (int i = 0; i < lines.length; i++) {
            // Kafka ConfigDef.define()
            Matcher m = DEFINE_RE.matcher(lines[i]);
            if (m.find()) {
                String configKey = m.group(1);
                if (seenKeys.add(configKey)) {
                    addConfigNode(configKey, "kafka_configdef", classNodeId, ctx.filePath(), i + 1, nodes, edges);
                }
            }

            // Spring @Value("${...}")
            Matcher vm = VALUE_RE.matcher(lines[i]);
            while (vm.find()) {
                String key = vm.group(1);
                if (seenKeys.add(key)) {
                    addConfigNode(key, "spring_value", classNodeId, ctx.filePath(), i + 1, nodes, edges);
                }
            }

            // Spring @ConfigurationProperties("prefix")
            Matcher cpm = CONFIG_PROPS_RE.matcher(lines[i]);
            if (cpm.find()) {
                String prefix = cpm.group(1);
                if (seenKeys.add(prefix)) {
                    addConfigNode(prefix, "spring_config_props", classNodeId, ctx.filePath(), i + 1, nodes, edges);
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
