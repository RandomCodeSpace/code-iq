package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared base class for Java messaging detectors (JMS, RabbitMQ, IBM MQ, TIBCO EMS).
 * Provides common patterns for class name extraction and edge creation.
 */
public abstract class AbstractJavaMessagingDetector extends AbstractRegexDetector {

    protected static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");

    /**
     * Extract the first class name from the source text.
     * Returns null if no class is found.
     */
    protected String extractClassName(String text) {
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) {
                return cm.group(1);
            }
        }
        return null;
    }

    /**
     * Create and add a messaging edge (CONSUMES, PRODUCES, SENDS_TO, RECEIVES_FROM, etc.).
     */
    protected void addMessagingEdge(String sourceId, String targetId, EdgeKind kind, String label,
                                    Map<String, Object> props, List<CodeEdge> edges) {
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->" + kind.getValue() + "->" + targetId);
        edge.setKind(kind);
        edge.setSourceId(sourceId);
        edge.setTarget(new CodeNode(targetId, NodeKind.QUEUE, label));
        edge.setProperties(new LinkedHashMap<>(props));
        edges.add(edge);
    }
}
