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
 * Detects IBM MQ queue manager, queue, and topic usage.
 */
@Component
public class IbmMqDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern QM_NEW_RE = Pattern.compile("new\\s+MQQueueManager\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern ACCESS_QUEUE_RE = Pattern.compile("accessQueue\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern MQ_TOPIC_DECL_RE = Pattern.compile("\\bMQTopic\\b");
    private static final Pattern JMS_CREATE_QUEUE_RE = Pattern.compile("createQueue\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern JMS_CREATE_TOPIC_RE = Pattern.compile("createTopic\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern MQ_PUT_RE = Pattern.compile("\\bput\\s*\\(");
    private static final Pattern MQ_GET_RE = Pattern.compile("\\bget\\s*\\(");

    @Override
    public String getName() {
        return "ibm_mq";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("MQQueueManager") && !text.contains("JmsConnectionFactory")
                && !text.contains("com.ibm.mq") && !text.contains("MQQueue")) {
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
        Set<String> seenQms = new LinkedHashSet<>();
        Set<String> seenQueues = new LinkedHashSet<>();
        Set<String> seenTopics = new LinkedHashSet<>();

        boolean hasPut = MQ_PUT_RE.matcher(text).find();
        boolean hasGet = MQ_GET_RE.matcher(text).find();

        // MQQueueManager instantiation
        for (int i = 0; i < lines.length; i++) {
            Matcher m = QM_NEW_RE.matcher(lines[i]);
            if (m.find()) {
                String qmName = m.group(1);
                String qmId = ensureNode("ibmmq:qm:" + qmName, qmName, NodeKind.MESSAGE_QUEUE,
                        "ibmmq:qm:" + qmName, Map.of("broker", "ibm_mq", "queue_manager", qmName),
                        seenQms, nodes);
                addEdge(classNodeId, qmId, EdgeKind.CONNECTS_TO,
                        className + " connects to queue manager " + qmName,
                        Map.of("queue_manager", qmName), edges);
            }
        }

        // accessQueue calls
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ACCESS_QUEUE_RE.matcher(lines[i]);
            if (m.find()) {
                String queueName = m.group(1);
                String queueId = ensureNode("ibmmq:queue:" + queueName, queueName, NodeKind.QUEUE,
                        "ibmmq:queue:" + queueName, Map.of("broker", "ibm_mq", "queue", queueName),
                        seenQueues, nodes);
                if (hasPut) addEdge(classNodeId, queueId, EdgeKind.SENDS_TO,
                        className + " sends to " + queueName, Map.of("queue", queueName), edges);
                if (hasGet) addEdge(classNodeId, queueId, EdgeKind.RECEIVES_FROM,
                        className + " receives from " + queueName, Map.of("queue", queueName), edges);
                if (!hasPut && !hasGet) addEdge(classNodeId, queueId, EdgeKind.CONNECTS_TO,
                        className + " accesses " + queueName, Map.of("queue", queueName), edges);
            }
        }

        // JMS createQueue/createTopic
        for (int i = 0; i < lines.length; i++) {
            Matcher m = JMS_CREATE_QUEUE_RE.matcher(lines[i]);
            if (m.find()) ensureNode("ibmmq:queue:" + m.group(1), m.group(1), NodeKind.QUEUE,
                    "ibmmq:queue:" + m.group(1), Map.of("broker", "ibm_mq", "queue", m.group(1)),
                    seenQueues, nodes);
            m = JMS_CREATE_TOPIC_RE.matcher(lines[i]);
            if (m.find()) ensureNode("ibmmq:topic:" + m.group(1), m.group(1), NodeKind.TOPIC,
                    "ibmmq:topic:" + m.group(1), Map.of("broker", "ibm_mq", "topic", m.group(1)),
                    seenTopics, nodes);
        }

        if (MQ_TOPIC_DECL_RE.matcher(text).find() && seenTopics.isEmpty()) {
            CodeNode node = new CodeNode();
            node.setId("ibmmq:topic:__unknown__");
            node.setKind(NodeKind.TOPIC);
            node.setLabel("ibmmq:topic:unknown");
            node.getProperties().put("broker", "ibm_mq");
            nodes.add(node);
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureNode(String id, String name, NodeKind kind, String label,
                              Map<String, Object> props, Set<String> seen, List<CodeNode> nodes) {
        if (!seen.contains(name)) {
            seen.add(name);
            CodeNode node = new CodeNode();
            node.setId(id);
            node.setKind(kind);
            node.setLabel(label);
            node.setProperties(new LinkedHashMap<>(props));
            nodes.add(node);
        }
        return id;
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
