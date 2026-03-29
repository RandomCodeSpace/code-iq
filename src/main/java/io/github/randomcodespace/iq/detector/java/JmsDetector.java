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
 * Detects JMS consumers and producers.
 */
@Component
public class JmsDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern JMS_LISTENER_RE = Pattern.compile(
            "@JmsListener\\s*\\(\\s*(?:.*?destination\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern JMS_SEND_RE = Pattern.compile(
            "(?:jmsTemplate|JmsTemplate)\\s*\\.(?:send|convertAndSend)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CONTAINER_FACTORY_RE = Pattern.compile("containerFactory\\s*=\\s*\"([^\"]+)\"");

    @Override
    public String getName() {
        return "jms";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("@JmsListener") && !text.contains("jmsTemplate") && !text.contains("JmsTemplate")) {
            return DetectorResult.empty();
        }

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
        Set<String> seenQueues = new LinkedHashSet<>();

        // @JmsListener consumers
        for (int i = 0; i < lines.length; i++) {
            Matcher m = JMS_LISTENER_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String destination = m.group(1);
            String queueId = ensureQueueNode(destination, seenQueues, nodes);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("destination", destination);
            Matcher cf = CONTAINER_FACTORY_RE.matcher(lines[i]);
            if (cf.find()) props.put("container_factory", cf.group(1));
            addEdge(classNodeId, queueId, EdgeKind.CONSUMES,
                    className + " consumes from " + destination, props, edges);
        }

        // JmsTemplate sends
        for (int i = 0; i < lines.length; i++) {
            Matcher m = JMS_SEND_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String destination = m.group(1);
            String queueId = ensureQueueNode(destination, seenQueues, nodes);
            addEdge(classNodeId, queueId, EdgeKind.PRODUCES,
                    className + " produces to " + destination,
                    Map.of("destination", destination), edges);
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureQueueNode(String destination, Set<String> seen, List<CodeNode> nodes) {
        String queueId = "jms:queue:" + destination;
        if (!seen.contains(destination)) {
            seen.add(destination);
            CodeNode node = new CodeNode();
            node.setId(queueId);
            node.setKind(NodeKind.QUEUE);
            node.setLabel("jms:" + destination);
            node.getProperties().put("broker", "jms");
            node.getProperties().put("destination", destination);
            nodes.add(node);
        }
        return queueId;
    }

    private void addEdge(String sourceId, String targetId, EdgeKind kind, String label,
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
