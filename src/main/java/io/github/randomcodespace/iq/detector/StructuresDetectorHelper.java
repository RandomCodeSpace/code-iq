package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;

import java.util.List;

/**
 * Shared helper methods for language structure detectors (Scala, Kotlin, etc.)
 * that detect classes, interfaces, objects, methods, and imports using regex.
 */
public final class StructuresDetectorHelper {

    private StructuresDetectorHelper() {}

    /**
     * Create and add an IMPORTS edge.
     */
    public static void addImportEdge(String filePath, String target, List<CodeEdge> edges) {
        CodeEdge e = new CodeEdge();
        e.setId(filePath + ":imports:" + target);
        e.setKind(EdgeKind.IMPORTS);
        e.setSourceId(filePath);
        e.setTarget(new CodeNode(target, NodeKind.MODULE, target));
        edges.add(e);
    }

    /**
     * Create a code node with standard fields for structure detection.
     */
    public static CodeNode createStructureNode(String filePath, String name, NodeKind kind, int lineStart) {
        CodeNode n = new CodeNode();
        n.setId(filePath + ":" + name);
        n.setKind(kind);
        n.setLabel(name);
        n.setFqn(name);
        n.setFilePath(filePath);
        n.setLineStart(lineStart);
        return n;
    }

    /**
     * Create and add an EXTENDS edge.
     */
    public static void addExtendsEdge(String sourceNodeId, String targetName, NodeKind targetKind,
                                       List<CodeEdge> edges) {
        CodeEdge e = new CodeEdge();
        e.setId(sourceNodeId + ":extends:" + targetName);
        e.setKind(EdgeKind.EXTENDS);
        e.setSourceId(sourceNodeId);
        e.setTarget(new CodeNode(targetName, targetKind, targetName));
        edges.add(e);
    }

    /**
     * Create and add an IMPLEMENTS edge.
     */
    public static void addImplementsEdge(String sourceNodeId, String targetName, List<CodeEdge> edges) {
        CodeEdge e = new CodeEdge();
        e.setId(sourceNodeId + ":implements:" + targetName);
        e.setKind(EdgeKind.IMPLEMENTS);
        e.setSourceId(sourceNodeId);
        e.setTarget(new CodeNode(targetName, NodeKind.INTERFACE, targetName));
        edges.add(e);
    }
}
