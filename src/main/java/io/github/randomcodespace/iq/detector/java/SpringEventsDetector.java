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
 * Detects Spring event listeners and publishers.
 */
@Component
public class SpringEventsDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern EVENT_LISTENER_RE = Pattern.compile("@EventListener");
    private static final Pattern TRANSACTIONAL_EVENT_RE = Pattern.compile("@TransactionalEventListener");
    private static final Pattern PUBLISH_RE = Pattern.compile(
            "(?:applicationEventPublisher|eventPublisher|publisher)\\s*\\.\\s*publishEvent\\s*\\(\\s*"
                    + "(?:new\\s+(\\w+)|(\\w+))");
    private static final Pattern METHOD_PARAM_RE = Pattern.compile(
            "(?:public|protected|private)?\\s*\\w+\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s+\\w+\\)");
    private static final Pattern EVENT_CLASS_RE = Pattern.compile("class\\s+(\\w+)\\s+extends\\s+\\w*Event");

    @Override
    public String getName() {
        return "spring_events";
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

        boolean hasListener = text.contains("@EventListener") || text.contains("@TransactionalEventListener");
        boolean hasPublisher = text.contains("publishEvent");
        Matcher eventClassMatch = EVENT_CLASS_RE.matcher(text);
        boolean hasEventClass = eventClassMatch.find();

        if (!hasListener && !hasPublisher && !hasEventClass) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Find class name
        String className = null;
        for (String line : lines) {
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) {
                className = cm.group(1);
                break;
            }
        }

        if (className == null) {
            return DetectorResult.empty();
        }

        String classNodeId = ctx.filePath() + ":" + className;
        Set<String> seenEvents = new LinkedHashSet<>();

        // If this file defines an event class, register it
        if (hasEventClass) {
            String eventName = eventClassMatch.group(1);
            ensureEventNode(eventName, seenEvents, nodes);
        }

        // Detect @EventListener / @TransactionalEventListener
        for (int i = 0; i < lines.length; i++) {
            if (!EVENT_LISTENER_RE.matcher(lines[i]).find() && !TRANSACTIONAL_EVENT_RE.matcher(lines[i]).find()) {
                continue;
            }

            String eventType = null;
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                Matcher pm = METHOD_PARAM_RE.matcher(lines[k]);
                if (pm.find()) {
                    eventType = pm.group(2);
                    break;
                }
            }

            if (eventType != null) {
                String eventId = ensureEventNode(eventType, seenEvents, nodes);
                CodeEdge edge = new CodeEdge();
                edge.setId(classNodeId + "->listens->" + eventId);
                edge.setKind(EdgeKind.LISTENS);
                edge.setSourceId(classNodeId);
                CodeNode targetRef = new CodeNode(eventId, NodeKind.EVENT, eventType);
                edge.setTarget(targetRef);
                edges.add(edge);
            }
        }

        // Detect publishEvent calls
        for (int i = 0; i < lines.length; i++) {
            Matcher m = PUBLISH_RE.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            String eventType = m.group(1) != null ? m.group(1) : m.group(2);
            if (eventType != null) {
                String eventId = ensureEventNode(eventType, seenEvents, nodes);
                CodeEdge edge = new CodeEdge();
                edge.setId(classNodeId + "->publishes->" + eventId);
                edge.setKind(EdgeKind.PUBLISHES);
                edge.setSourceId(classNodeId);
                CodeNode targetRef = new CodeNode(eventId, NodeKind.EVENT, eventType);
                edge.setTarget(targetRef);
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureEventNode(String eventType, Set<String> seenEvents, List<CodeNode> nodes) {
        String eventId = "event:" + eventType;
        if (!seenEvents.contains(eventType)) {
            seenEvents.add(eventType);
            CodeNode node = new CodeNode();
            node.setId(eventId);
            node.setKind(NodeKind.EVENT);
            node.setLabel(eventType);
            node.getProperties().put("event_class", eventType);
            nodes.add(node);
        }
        return eventId;
    }
}
