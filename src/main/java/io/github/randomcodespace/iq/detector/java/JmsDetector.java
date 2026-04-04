package io.github.randomcodespace.iq.detector.java;

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

/**
 * Detects JMS consumers and producers.
 */
@DetectorInfo(
    name = "jms",
    category = "messaging",
    description = "Detects JMS queue producers and consumers (@JmsListener, JmsTemplate)",
    languages = {"java"},
    nodeKinds = {NodeKind.QUEUE},
    edgeKinds = {EdgeKind.CONSUMES, EdgeKind.PRODUCES},
    properties = {"broker", "destination"}
)
@Component
public class JmsDetector extends AbstractJavaMessagingDetector {

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

        String className = extractClassName(text);
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;
        Set<String> seenQueues = new LinkedHashSet<>();

        // @JmsListener consumers
        for (int i = 0; i < lines.length; i++) {
            Matcher m = JMS_LISTENER_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String destination = m.group(1);
            String queueId = ensureQueueNode("jms", destination, seenQueues, nodes);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("destination", destination);
            Matcher cf = CONTAINER_FACTORY_RE.matcher(lines[i]);
            if (cf.find()) props.put("container_factory", cf.group(1));
            addMessagingEdge(classNodeId, queueId, EdgeKind.CONSUMES,
                    className + " consumes from " + destination, props, edges);
        }

        // JmsTemplate sends
        for (int i = 0; i < lines.length; i++) {
            Matcher m = JMS_SEND_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String destination = m.group(1);
            String queueId = ensureQueueNode("jms", destination, seenQueues, nodes);
            addMessagingEdge(classNodeId, queueId, EdgeKind.PRODUCES,
                    className + " produces to " + destination,
                    Map.of("destination", destination), edges);
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureQueueNode(String broker, String destination, Set<String> seen, List<CodeNode> nodes) {
        String queueId = broker + ":queue:" + destination;
        if (seen.add(destination)) {
            CodeNode node = new CodeNode();
            node.setId(queueId);
            node.setKind(NodeKind.QUEUE);
            node.setLabel(broker + ":" + destination);
            node.getProperties().put("broker", broker);
            node.getProperties().put("destination", destination);
            nodes.add(node);
        }
        return queueId;
    }
}
