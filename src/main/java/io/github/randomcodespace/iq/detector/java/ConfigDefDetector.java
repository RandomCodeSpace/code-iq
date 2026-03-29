package io.github.randomcodespace.iq.detector.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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

/**
 * Detects Kafka ConfigDef.define() configuration definitions and Spring @ConfigurationProperties
 * using JavaParser AST with regex fallback.
 */
@Component
public class ConfigDefDetector extends AbstractJavaParserDetector {

    // ---- Regex fallback patterns ----
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern DEFINE_RE = Pattern.compile("\\.define\\s*\\(\\s*\"([^\"]+)\"");

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
        if (text == null || !text.contains("ConfigDef")) return DetectorResult.empty();

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

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            String className = classDecl.getNameAsString();
            String classNodeId = ctx.filePath() + ":" + className;

            Set<String> seenKeys = new LinkedHashSet<>();

            // Find all .define() method calls in the class
            classDecl.findAll(MethodCallExpr.class).forEach(call -> {
                if (!"define".equals(call.getNameAsString())) return;
                if (call.getArguments().isEmpty()) return;

                var firstArg = call.getArguments().get(0);
                if (!firstArg.isStringLiteralExpr()) return;

                String configKey = firstArg.asStringLiteralExpr().getValue();
                if (seenKeys.contains(configKey)) return;
                seenKeys.add(configKey);

                int line = call.getBegin().map(p -> p.line).orElse(1);

                String nodeId = "config:" + configKey;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.CONFIG_DEFINITION);
                node.setLabel(configKey);
                node.setFilePath(ctx.filePath());
                node.setLineStart(line);
                node.getProperties().put("config_key", configKey);
                nodes.add(node);

                CodeEdge edge = new CodeEdge();
                edge.setId(classNodeId + "->reads_config->" + nodeId);
                edge.setKind(EdgeKind.READS_CONFIG);
                edge.setSourceId(classNodeId);
                edge.setTarget(node);
                edge.setProperties(Map.of("config_key", configKey));
                edges.add(edge);
            });
        });

        return DetectorResult.of(nodes, edges);
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
            Matcher m = DEFINE_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String configKey = m.group(1);
            if (seenKeys.contains(configKey)) continue;
            seenKeys.add(configKey);

            String nodeId = "config:" + configKey;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.CONFIG_DEFINITION);
            node.setLabel(configKey);
            node.setFilePath(ctx.filePath());
            node.setLineStart(i + 1);
            node.getProperties().put("config_key", configKey);
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->reads_config->" + nodeId);
            edge.setKind(EdgeKind.READS_CONFIG);
            edge.setSourceId(classNodeId);
            edge.setTarget(node);
            edge.setProperties(Map.of("config_key", configKey));
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
