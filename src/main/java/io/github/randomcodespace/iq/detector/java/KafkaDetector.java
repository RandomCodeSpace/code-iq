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
 * Detects Kafka consumers (@KafkaListener) and producers (KafkaTemplate.send).
 */
@Component
public class KafkaDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern KAFKA_LISTENER_RE = Pattern.compile(
            "@KafkaListener\\s*\\(\\s*(?:.*?topics?\\s*=\\s*)?[\\{\"]?\\s*\"([^\"]+)\"");
    private static final Pattern KAFKA_SEND_RE = Pattern.compile(
            "(?:kafkaTemplate|KafkaTemplate)\\s*\\.send\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern GROUP_ID_RE = Pattern.compile("groupId\\s*=\\s*\"([^\"]+)\"");

    @Override
    public String getName() {
        return "kafka";
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

        if (!text.contains("KafkaListener") && !text.contains("KafkaTemplate") && !text.contains("kafkaTemplate")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        for (String line : lines) {
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) {
                className = cm.group(1);
                break;
            }
        }
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;
        Set<String> seenTopics = new LinkedHashSet<>();

        // @KafkaListener consumers
        for (int i = 0; i < lines.length; i++) {
            Matcher m = KAFKA_LISTENER_RE.matcher(lines[i]);
            if (!m.find()) {
                if (i > 0 && lines[i - 1].contains("@KafkaListener")) {
                    Matcher fallback = Pattern.compile("\"([^\"]+)\"").matcher(lines[i]);
                    if (fallback.find()) {
                        String topic = fallback.group(1);
                        String topicId = ensureTopicNode(topic, seenTopics, nodes);
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put("topic", topic);
                        addEdge(classNodeId, topicId, EdgeKind.CONSUMES,
                                className + " consumes " + topic, props, edges, nodes);
                    }
                }
                continue;
            }
            String topic = m.group(1);
            String topicId = ensureTopicNode(topic, seenTopics, nodes);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("topic", topic);
            Matcher gm = GROUP_ID_RE.matcher(lines[i]);
            if (gm.find()) props.put("group_id", gm.group(1));
            addEdge(classNodeId, topicId, EdgeKind.CONSUMES,
                    className + " consumes " + topic, props, edges, nodes);
        }

        // KafkaTemplate.send producers
        for (int i = 0; i < lines.length; i++) {
            Matcher m = KAFKA_SEND_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String topic = m.group(1);
            String topicId = ensureTopicNode(topic, seenTopics, nodes);
            addEdge(classNodeId, topicId, EdgeKind.PRODUCES,
                    className + " produces to " + topic,
                    Map.of("topic", topic), edges, nodes);
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureTopicNode(String topic, Set<String> seen, List<CodeNode> nodes) {
        String topicId = "kafka:topic:" + topic;
        if (!seen.contains(topic)) {
            seen.add(topic);
            CodeNode node = new CodeNode();
            node.setId(topicId);
            node.setKind(NodeKind.TOPIC);
            node.setLabel("kafka:" + topic);
            node.getProperties().put("broker", "kafka");
            node.getProperties().put("topic", topic);
            nodes.add(node);
        }
        return topicId;
    }

    private void addEdge(String sourceId, String targetId, EdgeKind kind, String label,
                         Map<String, Object> props, List<CodeEdge> edges, List<CodeNode> nodes) {
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->" + kind.getValue() + "->" + targetId);
        edge.setKind(kind);
        edge.setSourceId(sourceId);
        CodeNode targetRef = new CodeNode(targetId, NodeKind.TOPIC, label);
        edge.setTarget(targetRef);
        edge.setProperties(new LinkedHashMap<>(props));
        edges.add(edge);
    }
}
