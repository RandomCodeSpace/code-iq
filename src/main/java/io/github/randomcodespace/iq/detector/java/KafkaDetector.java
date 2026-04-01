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
import io.github.randomcodespace.iq.detector.DetectorInfo;

/**
 * Detects Kafka consumers (@KafkaListener) and producers (KafkaTemplate.send).
 */
@DetectorInfo(
    name = "kafka",
    category = "messaging",
    description = "Detects Kafka producers, consumers, and topic configurations",
    languages = {"java", "kotlin"},
    nodeKinds = {NodeKind.TOPIC},
    edgeKinds = {EdgeKind.CONSUMES, EdgeKind.PRODUCES},
    properties = {"broker", "group_id", "topic"}
)
@Component
public class KafkaDetector extends AbstractRegexDetector {

    // Matches Java: `public class Foo`, `class Foo`
    // Matches Kotlin: `class Foo`, `data class Foo`, `abstract class Foo`, `internal class Foo`,
    //                 `open class Foo`, `sealed class Foo`, `object Foo` (Kotlin singleton)
    private static final Pattern CLASS_RE = Pattern.compile(
            "(?:(?:public|internal|private|protected|data|abstract|open|sealed|enum|inline|value)\\s+)*" +
            "(?:class|object)\\s+(\\w+)");
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
        return Set.of("java", "kotlin");
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
        io.github.randomcodespace.iq.analyzer.InfrastructureRegistry registry = ctx.registry();

        // @KafkaListener consumers
        for (int i = 0; i < lines.length; i++) {
            Matcher m = KAFKA_LISTENER_RE.matcher(lines[i]);
            if (!m.find()) {
                if (i > 0 && lines[i - 1].contains("@KafkaListener")) {
                    Matcher fallback = Pattern.compile("\"([^\"]+)\"").matcher(lines[i]);
                    if (fallback.find()) {
                        String topic = fallback.group(1);
                        String topicId = ensureTopicNode(topic, seenTopics, nodes, registry);
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put("topic", topic);
                        addEdge(classNodeId, topicId, EdgeKind.CONSUMES,
                                className + " consumes " + topic, props, edges, nodes);
                    }
                }
                continue;
            }
            String topic = m.group(1);
            String topicId = ensureTopicNode(topic, seenTopics, nodes, registry);
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
            String topicId = ensureTopicNode(topic, seenTopics, nodes, registry);
            addEdge(classNodeId, topicId, EdgeKind.PRODUCES,
                    className + " produces to " + topic,
                    Map.of("topic", topic), edges, nodes);
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureTopicNode(String topic, Set<String> seen, List<CodeNode> nodes,
            io.github.randomcodespace.iq.analyzer.InfrastructureRegistry registry) {
        // Use canonical registry id if this topic is registered
        String topicId = "kafka:topic:" + topic;
        if (registry != null) {
            io.github.randomcodespace.iq.analyzer.InfraEndpoint registered = registry.getTopics().get(topic);
            if (registered != null) topicId = "infra:" + registered.id();
        }
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
