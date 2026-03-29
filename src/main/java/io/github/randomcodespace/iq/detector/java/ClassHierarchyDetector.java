package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
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
 * Detects Java class hierarchies using regex (port of tree-sitter-based Python detector).
 */
@Component
public class ClassHierarchyDetector extends AbstractRegexDetector {

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
                props.put("visibility", visibility);
                props.put("is_abstract", isAbstract);
                props.put("is_final", isFinal);
                if (superclass != null) props.put("superclass", superclass);
                if (!interfaces.isEmpty()) props.put("interfaces", interfaces);

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
                props.put("visibility", visibility);
                props.put("is_abstract", false);
                props.put("is_final", false);
                if (!extended.isEmpty()) props.put("interfaces", extended);

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
                props.put("visibility", visibility);
                props.put("is_abstract", false);
                props.put("is_final", false);
                if (!interfaces.isEmpty()) props.put("interfaces", interfaces);

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
                props.put("visibility", visibility);
                props.put("is_abstract", false);
                props.put("is_final", false);

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
