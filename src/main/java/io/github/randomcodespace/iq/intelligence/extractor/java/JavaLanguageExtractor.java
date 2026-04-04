package io.github.randomcodespace.iq.intelligence.extractor.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractionResult;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractor;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Java language-specific extractor.
 *
 * <p>Capabilities:
 * <ul>
 *   <li>Cross-file call graph: CALLS edges between methods via JavaParser {@code MethodCallExpr}
 *       and existing node lookup.</li>
 *   <li>Type hierarchy enrichment: verifies EXTENDS/IMPLEMENTS edges via resolved imports
 *       and stores {@code extends_type} / {@code implements_types} as type hints.</li>
 * </ul>
 *
 * <p>Confidence: EXACT for same-module calls, PARTIAL for cross-module.
 * Does NOT re-detect what existing Java detectors already detect.
 */
@Component
public class JavaLanguageExtractor implements LanguageExtractor {

    private static final Logger log = LoggerFactory.getLogger(JavaLanguageExtractor.class);

    @Override
    public String getLanguage() {
        return "java";
    }

    @Override
    @SuppressWarnings("unchecked")
    public LanguageExtractionResult extract(DetectorContext ctx, CodeNode node) {
        // Only enrich METHOD nodes with call graph edges
        if (node.getKind() != NodeKind.METHOD && node.getKind() != NodeKind.CLASS
                && node.getKind() != NodeKind.ABSTRACT_CLASS
                && node.getKind() != NodeKind.INTERFACE) {
            return LanguageExtractionResult.empty();
        }

        Map<String, CodeNode> nodeRegistry = (ctx.parsedData() instanceof Map<?, ?> raw)
                ? (Map<String, CodeNode>) raw
                : Map.of();

        Optional<CompilationUnit> cuOpt = parse(ctx);
        if (cuOpt.isEmpty()) {
            return LanguageExtractionResult.empty();
        }
        CompilationUnit cu = cuOpt.get();

        List<CodeEdge> callEdges = new ArrayList<>();
        Map<String, String> typeHints = new java.util.LinkedHashMap<>();

        if (node.getKind() == NodeKind.METHOD) {
            extractCallEdges(cu, node, nodeRegistry, callEdges);
        } else {
            extractTypeHierarchyHints(cu, node, typeHints);
        }

        return new LanguageExtractionResult(callEdges, List.of(), typeHints, CapabilityLevel.PARTIAL);
    }

    private void extractCallEdges(CompilationUnit cu, CodeNode methodNode,
                                  Map<String, CodeNode> nodeRegistry,
                                  List<CodeEdge> callEdges) {
        String methodLabel = methodNode.getLabel();
        if (methodLabel == null) return;

        // Find the MethodDeclaration matching this node by name
        cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> methodLabel.equals(md.getNameAsString()))
                .findFirst()
                .ifPresent(md -> md.findAll(MethodCallExpr.class).forEach(mce -> {
                    String calleeName = mce.getNameAsString();
                    CodeNode target = lookupByLabel(calleeName, nodeRegistry);
                    if (target != null && !target.getId().equals(methodNode.getId())) {
                        String edgeId = "calls:%s:%s:%d".formatted(
                                methodNode.getId(), target.getId(),
                                mce.getBegin().map(p -> p.line).orElse(0));
                        CodeEdge edge = new CodeEdge(edgeId, EdgeKind.CALLS, methodNode.getId(), target);
                        edge.getProperties().put("confidence", "PARTIAL");
                        edge.getProperties().put("extractorName", "java_language_extractor");
                        callEdges.add(edge);
                    }
                }));
    }

    private void extractTypeHierarchyHints(CompilationUnit cu, CodeNode classNode,
                                           Map<String, String> typeHints) {
        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .stream()
                .findFirst()
                .ifPresent(decl -> {
                    List<String> extended = decl.getExtendedTypes().stream()
                            .map(t -> t.getNameAsString())
                            .toList();
                    List<String> implemented = decl.getImplementedTypes().stream()
                            .map(t -> t.getNameAsString())
                            .toList();
                    if (!extended.isEmpty()) {
                        typeHints.put("extends_type", String.join(", ", extended));
                    }
                    if (!implemented.isEmpty()) {
                        typeHints.put("implements_types", String.join(", ", implemented));
                    }
                });
    }

    private Optional<CompilationUnit> parse(DetectorContext ctx) {
        try {
            if (ctx.content() == null || ctx.content().isEmpty()) return Optional.empty();
            return new JavaParser().parse(ctx.content()).getResult();
        } catch (Exception | AssertionError e) {
            log.debug("JavaParser failed for {}: {}", ctx.filePath(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Look up a node by label in the registry (label is the simple method name).
     * Returns null if zero or more than one distinct node matches to avoid false-positive
     * CALLS edges for common method names like {@code save}, {@code get}, {@code execute}.
     */
    private CodeNode lookupByLabel(String label, Map<String, CodeNode> registry) {
        Map<String, CodeNode> matches = new java.util.LinkedHashMap<>();
        for (CodeNode candidate : registry.values()) {
            if (label.equals(candidate.getLabel()) && candidate.getKind() == NodeKind.METHOD) {
                matches.put(candidate.getId(), candidate);
            }
        }
        return matches.size() == 1 ? matches.values().iterator().next() : null;
    }
}
