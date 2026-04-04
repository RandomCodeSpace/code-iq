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
 * Detects TIBCO EMS queue and topic usage.
 */
@DetectorInfo(
    name = "tibco_ems",
    category = "messaging",
    description = "Detects TIBCO EMS queue and topic connections",
    languages = {"java"},
    nodeKinds = {NodeKind.MESSAGE_QUEUE, NodeKind.QUEUE, NodeKind.TOPIC},
    edgeKinds = {EdgeKind.CONNECTS_TO, EdgeKind.RECEIVES_FROM, EdgeKind.SENDS_TO},
    properties = {"broker", "queue", "topic"}
)
@Component
public class TibcoEmsDetector extends AbstractJavaMessagingDetector {

    private static final Pattern TIBJMS_FACTORY_RE = Pattern.compile(
            "\\b(TibjmsConnectionFactory|TibjmsQueueConnectionFactory|TibjmsTopicConnectionFactory)\\b");
    private static final Pattern SERVER_URL_RE = Pattern.compile("\"(tcp://[^\"]+)\"");
    private static final Pattern CREATE_QUEUE_RE = Pattern.compile("createQueue\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CREATE_TOPIC_RE = Pattern.compile("createTopic\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern SEND_RE = Pattern.compile("\\bsend\\s*\\(");
    private static final Pattern PUBLISH_RE = Pattern.compile("\\bpublish\\s*\\(");
    private static final Pattern RECEIVE_RE = Pattern.compile("\\breceive\\s*\\(");
    private static final Pattern ON_MESSAGE_RE = Pattern.compile("\\bonMessage\\s*\\(");
    private static final Pattern PRODUCER_RE = Pattern.compile("\\bMessageProducer\\b");
    private static final Pattern CONSUMER_RE = Pattern.compile("\\bMessageConsumer\\b");
    private static final Pattern TIBJMS_QUEUE_RE = Pattern.compile("new\\s+TibjmsQueue\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern TIBJMS_TOPIC_RE = Pattern.compile("new\\s+TibjmsTopic\\s*\\(\\s*\"([^\"]+)\"");

    @Override
    public String getName() {
        return "tibco_ems";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("tibjms") && !text.contains("TibjmsConnectionFactory")
                && !text.contains("com.tibco") && !text.contains("TIBJMS")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = extractClassName(text);
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;
        Set<String> seenQueues = new LinkedHashSet<>();
        Set<String> seenTopics = new LinkedHashSet<>();

        boolean isProducer = SEND_RE.matcher(text).find() || PUBLISH_RE.matcher(text).find()
                || PRODUCER_RE.matcher(text).find();
        boolean isConsumer = RECEIVE_RE.matcher(text).find() || ON_MESSAGE_RE.matcher(text).find()
                || CONSUMER_RE.matcher(text).find();

        // Connection factory
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIBJMS_FACTORY_RE.matcher(lines[i]);
            if (m.find()) {
                String factoryType = m.group(1);
                List<String> serverUrls = new ArrayList<>();
                for (int j = Math.max(0, i - 1); j < Math.min(lines.length, i + 4); j++) {
                    Matcher urlM = SERVER_URL_RE.matcher(lines[j]);
                    if (urlM.find()) serverUrls.add(urlM.group(1));
                }

                String nodeId = "ems:server:" + factoryType;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.MESSAGE_QUEUE);
                node.setLabel("ems:" + factoryType);
                node.getProperties().put("broker", "tibco_ems");
                node.getProperties().put("factory_type", factoryType);
                if (!serverUrls.isEmpty()) node.getProperties().put("server_url", serverUrls.get(0));
                nodes.add(node);

                CodeEdge edge = new CodeEdge();
                edge.setId(classNodeId + "->connects_to->" + nodeId);
                edge.setKind(EdgeKind.CONNECTS_TO);
                edge.setSourceId(classNodeId);
                edge.setTarget(node);
                edge.setProperties(Map.of("factory_type", factoryType));
                edges.add(edge);
            }
        }

        // createQueue / createTopic
        for (int i = 0; i < lines.length; i++) {
            Matcher m = CREATE_QUEUE_RE.matcher(lines[i]);
            if (m.find()) {
                String queueName = m.group(1);
                String queueId = ensureQueueNode(queueName, seenQueues, nodes);
                if (isProducer) addMessagingEdge(classNodeId, queueId, EdgeKind.SENDS_TO,
                        className + " sends to " + queueName, Map.of("queue", queueName), edges);
                if (isConsumer) addMessagingEdge(classNodeId, queueId, EdgeKind.RECEIVES_FROM,
                        className + " receives from " + queueName, Map.of("queue", queueName), edges);
            }
            m = CREATE_TOPIC_RE.matcher(lines[i]);
            if (m.find()) {
                String topicName = m.group(1);
                String topicId = ensureTopicNode(topicName, seenTopics, nodes);
                if (isProducer) addMessagingEdge(classNodeId, topicId, EdgeKind.SENDS_TO,
                        className + " sends to " + topicName, Map.of("topic", topicName), edges);
                if (isConsumer) addMessagingEdge(classNodeId, topicId, EdgeKind.RECEIVES_FROM,
                        className + " receives from " + topicName, Map.of("topic", topicName), edges);
            }
        }

        // TibjmsQueue / TibjmsTopic direct instantiation
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIBJMS_QUEUE_RE.matcher(lines[i]);
            if (m.find()) ensureQueueNode(m.group(1), seenQueues, nodes);
            m = TIBJMS_TOPIC_RE.matcher(lines[i]);
            if (m.find()) ensureTopicNode(m.group(1), seenTopics, nodes);
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureQueueNode(String name, Set<String> seen, List<CodeNode> nodes) {
        String id = "ems:queue:" + name;
        if (!seen.contains(name)) {
            seen.add(name);
            CodeNode node = new CodeNode();
            node.setId(id);
            node.setKind(NodeKind.QUEUE);
            node.setLabel("ems:queue:" + name);
            node.getProperties().put("broker", "tibco_ems");
            node.getProperties().put("queue", name);
            nodes.add(node);
        }
        return id;
    }

    private String ensureTopicNode(String name, Set<String> seen, List<CodeNode> nodes) {
        String id = "ems:topic:" + name;
        if (!seen.contains(name)) {
            seen.add(name);
            CodeNode node = new CodeNode();
            node.setId(id);
            node.setKind(NodeKind.TOPIC);
            node.setLabel("ems:topic:" + name);
            node.getProperties().put("broker", "tibco_ems");
            node.getProperties().put("topic", name);
            nodes.add(node);
        }
        return id;
    }

}
