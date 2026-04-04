package io.github.randomcodespace.iq.detector.frontend;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;

/**
 * Shared helper methods for frontend component detectors (Angular, React, Vue).
 * Provides common node creation patterns for components and hooks.
 */
final class FrontendDetectorHelper {

    private FrontendDetectorHelper() {}

    /**
     * Create a frontend component node with standard fields.
     *
     * @param framework   framework name (e.g. "angular", "react", "vue")
     * @param filePath    source file path
     * @param idType      node type for ID (e.g. "component", "service")
     * @param className   the component class/function name
     * @param kind        the node kind (COMPONENT, HOOK, MIDDLEWARE)
     * @param line        1-based line number
     * @return the configured CodeNode
     */
    static CodeNode createComponentNode(String framework, String filePath, String idType,
                                        String className, NodeKind kind, int line) {
        CodeNode node = new CodeNode();
        node.setId(framework + ":" + filePath + ":" + idType + ":" + className);
        node.setKind(kind);
        node.setLabel(className);
        node.setFqn(filePath + "::" + className);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("framework", framework);
        return node;
    }

    /**
     * Calculate the 1-based line number from a character offset in text.
     */
    static int lineAt(String text, int offset) {
        return text.substring(0, offset).split("\n", -1).length;
    }
}
