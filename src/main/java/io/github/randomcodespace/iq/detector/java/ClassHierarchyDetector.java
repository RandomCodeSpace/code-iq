package io.github.randomcodespace.iq.detector.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
 * Detects Java class hierarchies using JavaParser AST with regex fallback.
 * Finds classes, interfaces, enums, annotation types, and their inheritance relationships.
 */
@DetectorInfo(
    name = "java.class_hierarchy",
    category = "structures",
    description = "Detects Java class hierarchy (classes, interfaces, enums, annotations, inheritance)",
    parser = ParserType.JAVAPARSER,
    languages = {"java"},
    nodeKinds = {NodeKind.ABSTRACT_CLASS, NodeKind.ANNOTATION_TYPE, NodeKind.CLASS, NodeKind.ENUM, NodeKind.INTERFACE},
    edgeKinds = {EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS}
)
@Component
public class ClassHierarchyDetector extends AbstractJavaParserDetector {

    private static final String PROP_INTERFACES = "interfaces";
    private static final String PROP_IS_ABSTRACT = "is_abstract";
    private static final String PROP_IS_FINAL = "is_final";
    private static final String PROP_VISIBILITY = "visibility";

    // ---- Regex patterns for fallback ----
    private static final Pattern CLASS_DECL_RE = Pattern.compile(
            "(public\\s+|protected\\s+|private\\s+)?(abstract\\s+)?(final\\s+)?class\\s+(\\w+)"
                    + "(?:\\s+extends\\s+(\\w+))?"
                    + "(?:\\s+implements\\s+([\\w,\\s]+))?");
    private static final Pattern INTERFACE_DECL_RE = Pattern.compile(
            "(public\\s+|protected\\s+|private\\s+)?interface\\s+(\\w+)"
                    + "(?:\\s+extends\\s+([\\w,\\s]+))?");
    private static final Pattern ENUM_DECL_RE = Pattern.compile(
            "(public\\s+|protected\\s+|private\\s+)?enum\\s+(\\w+)"
                    + "(?:\\s+implements\\s+([\\w,\\s]+))?");
    private static final Pattern ANNOTATION_TYPE_RE = Pattern.compile(
            "(public\\s+|protected\\s+|private\\s+)?@interface\\s+(\\w+)");

    @Override
    public String getName() {
        return "java.class_hierarchy";
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
        List<CodeEdge> edges = new ArrayList<>();

        // Process all class/interface declarations (including inner classes)
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String name = decl.getNameAsString();
            String fqn = resolveFqn(cu, name);
            String nodeId = ctx.filePath() + ":" + name;
            int line = decl.getBegin().map(p -> p.line).orElse(1);
            int lineEnd = decl.getEnd().map(p -> p.line).orElse(line);

            boolean isInterface = decl.isInterface();
            boolean isAbstract = decl.isAbstract();
            boolean isFinal = decl.isFinal();
            String visibility = resolveVisibility(decl);

            NodeKind kind;
            if (isInterface) {
                kind = NodeKind.INTERFACE;
            } else if (isAbstract) {
                kind = NodeKind.ABSTRACT_CLASS;
            } else {
                kind = NodeKind.CLASS;
            }

            Map<String, Object> props = new LinkedHashMap<>();
            props.put(PROP_VISIBILITY, visibility);
            props.put(PROP_IS_ABSTRACT, isAbstract);
            props.put(PROP_IS_FINAL, isFinal);

            // Extended types
            List<String> extendedTypes = new ArrayList<>();
            for (ClassOrInterfaceType ext : decl.getExtendedTypes()) {
                extendedTypes.add(ext.getNameAsString());
            }
            if (!extendedTypes.isEmpty()) {
                if (isInterface) {
                    props.put(PROP_INTERFACES, extendedTypes);
                } else {
                    props.put("superclass", extendedTypes.get(0));
                }
            }

            // Implemented types
            List<String> implementedTypes = new ArrayList<>();
            for (ClassOrInterfaceType impl : decl.getImplementedTypes()) {
                implementedTypes.add(impl.getNameAsString());
            }
            if (!implementedTypes.isEmpty()) {
                props.put(PROP_INTERFACES, implementedTypes);
            }

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(kind);
            node.setLabel(name);
            node.setFqn(fqn);
            node.setFilePath(ctx.filePath());
            node.setLineStart(line);
            node.setLineEnd(lineEnd);
            node.setProperties(props);
            nodes.add(node);

            // EXTENDS edges
            if (!isInterface) {
                for (String superclass : extendedTypes) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(nodeId + "->extends->*:" + superclass);
                    edge.setKind(EdgeKind.EXTENDS);
                    edge.setSourceId(nodeId);
                    edge.setTarget(new CodeNode("*:" + superclass, NodeKind.CLASS, superclass));
                    edges.add(edge);
                }
            } else {
                // Interfaces extend other interfaces
                for (String ext : extendedTypes) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(nodeId + "->extends->*:" + ext);
                    edge.setKind(EdgeKind.EXTENDS);
                    edge.setSourceId(nodeId);
                    edge.setTarget(new CodeNode("*:" + ext, NodeKind.INTERFACE, ext));
                    edges.add(edge);
                }
            }

            // IMPLEMENTS edges
            for (String iface : implementedTypes) {
                CodeEdge edge = new CodeEdge();
                edge.setId(nodeId + "->implements->*:" + iface);
                edge.setKind(EdgeKind.IMPLEMENTS);
                edge.setSourceId(nodeId);
                edge.setTarget(new CodeNode("*:" + iface, NodeKind.INTERFACE, iface));
                edges.add(edge);
            }
        });

        // Process enum declarations
        cu.findAll(EnumDeclaration.class).forEach(decl -> {
            String name = decl.getNameAsString();
            String fqn = resolveFqn(cu, name);
            String nodeId = ctx.filePath() + ":" + name;
            int line = decl.getBegin().map(p -> p.line).orElse(1);
            int lineEnd = decl.getEnd().map(p -> p.line).orElse(line);

            String visibility = decl.isPublic() ? "public"
                    : decl.isProtected() ? "protected"
                    : decl.isPrivate() ? "private"
                    : "package-private";

            List<String> interfaces = new ArrayList<>();
            for (ClassOrInterfaceType impl : decl.getImplementedTypes()) {
                interfaces.add(impl.getNameAsString());
            }

            Map<String, Object> props = new LinkedHashMap<>();
            props.put(PROP_VISIBILITY, visibility);
            props.put(PROP_IS_ABSTRACT, false);
            props.put(PROP_IS_FINAL, false);
            if (!interfaces.isEmpty()) props.put(PROP_INTERFACES, interfaces);

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENUM);
            node.setLabel(name);
            node.setFqn(fqn);
            node.setFilePath(ctx.filePath());
            node.setLineStart(line);
            node.setLineEnd(lineEnd);
            node.setProperties(props);
            nodes.add(node);

            for (String iface : interfaces) {
                CodeEdge edge = new CodeEdge();
                edge.setId(nodeId + "->implements->*:" + iface);
                edge.setKind(EdgeKind.IMPLEMENTS);
                edge.setSourceId(nodeId);
                edge.setTarget(new CodeNode("*:" + iface, NodeKind.INTERFACE, iface));
                edges.add(edge);
            }
        });

        // Process annotation type declarations
        cu.findAll(AnnotationDeclaration.class).forEach(decl -> {
            String name = decl.getNameAsString();
            String fqn = resolveFqn(cu, name);
            String nodeId = ctx.filePath() + ":" + name;
            int line = decl.getBegin().map(p -> p.line).orElse(1);
            int lineEnd = decl.getEnd().map(p -> p.line).orElse(line);

            String visibility = decl.isPublic() ? "public"
                    : decl.isProtected() ? "protected"
                    : decl.isPrivate() ? "private"
                    : "package-private";

            Map<String, Object> props = new LinkedHashMap<>();
            props.put(PROP_VISIBILITY, visibility);
            props.put(PROP_IS_ABSTRACT, false);
            props.put(PROP_IS_FINAL, false);

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ANNOTATION_TYPE);
            node.setLabel(name);
            node.setFqn(fqn);
            node.setFilePath(ctx.filePath());
            node.setLineStart(line);
            node.setLineEnd(lineEnd);
            node.setProperties(props);
            nodes.add(node);
        });

        return DetectorResult.of(nodes, edges);
    }

    private String resolveVisibility(ClassOrInterfaceDeclaration decl) {
        if (decl.isPublic()) return "public";
        if (decl.isProtected()) return "protected";
        if (decl.isPrivate()) return "private";
        return "package-private";
    }

    // ==================== Regex fallback ====================

    private DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            // Class declarations
            Matcher cm = CLASS_DECL_RE.matcher(lines[i]);
            if (cm.find()) {
                String visibility = parseVisibility(cm.group(1));
                boolean isAbstract = cm.group(2) != null;
                boolean isFinal = cm.group(3) != null;
                String name = cm.group(4);
                String superclass = cm.group(5);
                List<String> interfaces = parseTypeList(cm.group(6));

                String nodeId = ctx.filePath() + ":" + name;
                NodeKind kind = isAbstract ? NodeKind.ABSTRACT_CLASS : NodeKind.CLASS;

                Map<String, Object> props = new LinkedHashMap<>();
                props.put(PROP_VISIBILITY, visibility);
                props.put(PROP_IS_ABSTRACT, isAbstract);
                props.put(PROP_IS_FINAL, isFinal);
                if (superclass != null) props.put("superclass", superclass);
                if (!interfaces.isEmpty()) props.put(PROP_INTERFACES, interfaces);

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(kind);
                node.setLabel(name);
                node.setFqn(name);
                node.setFilePath(ctx.filePath());
                node.setLineStart(i + 1);
                node.setProperties(props);
                nodes.add(node);

                if (superclass != null) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(nodeId + "->extends->*:" + superclass);
                    edge.setKind(EdgeKind.EXTENDS);
                    edge.setSourceId(nodeId);
                    edge.setTarget(new CodeNode("*:" + superclass, NodeKind.CLASS, superclass));
                    edges.add(edge);
                }
                for (String iface : interfaces) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(nodeId + "->implements->*:" + iface);
                    edge.setKind(EdgeKind.IMPLEMENTS);
                    edge.setSourceId(nodeId);
                    edge.setTarget(new CodeNode("*:" + iface, NodeKind.INTERFACE, iface));
                    edges.add(edge);
                }
                continue;
            }

            // Interface declarations
            Matcher im = INTERFACE_DECL_RE.matcher(lines[i]);
            if (im.find()) {
                String visibility = parseVisibility(im.group(1));
                String name = im.group(2);
                List<String> extended = parseTypeList(im.group(3));

                String nodeId = ctx.filePath() + ":" + name;
                Map<String, Object> props = new LinkedHashMap<>();
                props.put(PROP_VISIBILITY, visibility);
                props.put(PROP_IS_ABSTRACT, false);
                props.put(PROP_IS_FINAL, false);
                if (!extended.isEmpty()) props.put(PROP_INTERFACES, extended);

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.INTERFACE);
                node.setLabel(name);
                node.setFqn(name);
                node.setFilePath(ctx.filePath());
                node.setLineStart(i + 1);
                node.setProperties(props);
                nodes.add(node);

                for (String ext : extended) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(nodeId + "->extends->*:" + ext);
                    edge.setKind(EdgeKind.EXTENDS);
                    edge.setSourceId(nodeId);
                    edge.setTarget(new CodeNode("*:" + ext, NodeKind.INTERFACE, ext));
                    edges.add(edge);
                }
                continue;
            }

            // Enum declarations
            Matcher em = ENUM_DECL_RE.matcher(lines[i]);
            if (em.find()) {
                String visibility = parseVisibility(em.group(1));
                String name = em.group(2);
                List<String> interfaces = parseTypeList(em.group(3));

                String nodeId = ctx.filePath() + ":" + name;
                Map<String, Object> props = new LinkedHashMap<>();
                props.put(PROP_VISIBILITY, visibility);
                props.put(PROP_IS_ABSTRACT, false);
                props.put(PROP_IS_FINAL, false);
                if (!interfaces.isEmpty()) props.put(PROP_INTERFACES, interfaces);

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.ENUM);
                node.setLabel(name);
                node.setFqn(name);
                node.setFilePath(ctx.filePath());
                node.setLineStart(i + 1);
                node.setProperties(props);
                nodes.add(node);

                for (String iface : interfaces) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(nodeId + "->implements->*:" + iface);
                    edge.setKind(EdgeKind.IMPLEMENTS);
                    edge.setSourceId(nodeId);
                    edge.setTarget(new CodeNode("*:" + iface, NodeKind.INTERFACE, iface));
                    edges.add(edge);
                }
                continue;
            }

            // Annotation type
            Matcher am = ANNOTATION_TYPE_RE.matcher(lines[i]);
            if (am.find()) {
                String visibility = parseVisibility(am.group(1));
                String name = am.group(2);

                String nodeId = ctx.filePath() + ":" + name;
                Map<String, Object> props = new LinkedHashMap<>();
                props.put(PROP_VISIBILITY, visibility);
                props.put(PROP_IS_ABSTRACT, false);
                props.put(PROP_IS_FINAL, false);

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.ANNOTATION_TYPE);
                node.setLabel(name);
                node.setFqn(name);
                node.setFilePath(ctx.filePath());
                node.setLineStart(i + 1);
                node.setProperties(props);
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private String parseVisibility(String modifier) {
        if (modifier == null) return "package-private";
        String trimmed = modifier.trim();
        if (trimmed.equals("public") || trimmed.equals("protected") || trimmed.equals("private")) {
            return trimmed;
        }
        return "package-private";
    }

    private List<String> parseTypeList(String typeList) {
        if (typeList == null || typeList.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String t : typeList.split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
