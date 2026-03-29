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
 * Detects Azure Service Bus and Event Hub usage.
 */
@Component
public class AzureMessagingDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern SB_SENDER_CLIENT_RE = Pattern.compile("\\bServiceBusSenderClient\\b");
    private static final Pattern SB_RECEIVER_CLIENT_RE = Pattern.compile("\\bServiceBusReceiverClient\\b");
    private static final Pattern SB_PROCESSOR_CLIENT_RE = Pattern.compile("\\bServiceBusProcessorClient\\b");
    private static final Pattern SB_CLIENT_RE = Pattern.compile("\\bServiceBusClient\\b");
    private static final Pattern SB_CLIENT_BUILDER_RE = Pattern.compile("\\bServiceBusClientBuilder\\b");
    private static final Pattern EH_PRODUCER_RE = Pattern.compile("\\bEventHubProducerClient\\b");
    private static final Pattern EH_CONSUMER_RE = Pattern.compile("\\bEventHubConsumerClient\\b");
    private static final Pattern EH_PROCESSOR_RE = Pattern.compile("\\bEventProcessorClient\\b");
    private static final Pattern QUEUE_NAME_RE = Pattern.compile("(?:queueName|queue)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern TOPIC_NAME_RE = Pattern.compile("(?:topicName|topic)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern EH_NAME_RE = Pattern.compile("(?:eventHubName|eventHub)\\s*\\(\\s*\"([^\"]+)\"");

    @Override
    public String getName() {
        return "azure_messaging";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java", "typescript", "javascript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("ServiceBus") && !text.contains("EventHub") && !text.contains("azure-messaging")) {
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
        Set<String> seenSbQueues = new LinkedHashSet<>();
        Set<String> seenSbTopics = new LinkedHashSet<>();
        Set<String> seenEventHubs = new LinkedHashSet<>();

        boolean isSbSender = SB_SENDER_CLIENT_RE.matcher(text).find();
        boolean isSbReceiver = SB_RECEIVER_CLIENT_RE.matcher(text).find()
                || SB_PROCESSOR_CLIENT_RE.matcher(text).find();
        boolean isEhProducer = EH_PRODUCER_RE.matcher(text).find();
        boolean isEhConsumer = EH_CONSUMER_RE.matcher(text).find()
                || EH_PROCESSOR_RE.matcher(text).find();
        boolean hasSbClient = SB_CLIENT_RE.matcher(text).find()
                || SB_CLIENT_BUILDER_RE.matcher(text).find();

        List<String> queueNames = new ArrayList<>();
        List<String> topicNames = new ArrayList<>();
        List<String> ehNames = new ArrayList<>();

        for (String line : lines) {
            Matcher m = QUEUE_NAME_RE.matcher(line);
            if (m.find()) queueNames.add(m.group(1));
            m = TOPIC_NAME_RE.matcher(line);
            if (m.find()) topicNames.add(m.group(1));
            m = EH_NAME_RE.matcher(line);
            if (m.find()) ehNames.add(m.group(1));
        }

        for (String qname : queueNames) {
            String queueId = ensureSbQueueNode(qname, seenSbQueues, nodes);
            if (isSbSender) addMessagingEdge(classNodeId, queueId, EdgeKind.SENDS_TO,
                    className + " sends to " + qname, Map.of("queue", qname), edges);
            if (isSbReceiver) addMessagingEdge(classNodeId, queueId, EdgeKind.RECEIVES_FROM,
                    className + " receives from " + qname, Map.of("queue", qname), edges);
        }

        for (String tname : topicNames) {
            String topicId = ensureSbTopicNode(tname, seenSbTopics, nodes);
            if (isSbSender) addMessagingEdge(classNodeId, topicId, EdgeKind.SENDS_TO,
                    className + " sends to " + tname, Map.of("topic", tname), edges);
            if (isSbReceiver) addMessagingEdge(classNodeId, topicId, EdgeKind.RECEIVES_FROM,
                    className + " receives from " + tname, Map.of("topic", tname), edges);
        }

        for (String ehname : ehNames) {
            String ehId = ensureEventhubNode(ehname, seenEventHubs, nodes);
            if (isEhProducer) addMessagingEdge(classNodeId, ehId, EdgeKind.SENDS_TO,
                    className + " sends to " + ehname, Map.of("event_hub", ehname), edges);
            if (isEhConsumer) addMessagingEdge(classNodeId, ehId, EdgeKind.RECEIVES_FROM,
                    className + " receives from " + ehname, Map.of("event_hub", ehname), edges);
        }

        // Generic fallbacks
        if (isSbSender && queueNames.isEmpty() && topicNames.isEmpty()) {
            nodes.add(genericNode("azure:servicebus:__sender__", NodeKind.QUEUE, "azure:servicebus:sender",
                    Map.of("broker", "azure_servicebus", "role", "sender")));
            addMessagingEdge(classNodeId, "azure:servicebus:__sender__", EdgeKind.SENDS_TO,
                    className + " sends to Azure Service Bus", Map.of(), edges);
        } else if (isSbReceiver && queueNames.isEmpty() && topicNames.isEmpty()) {
            nodes.add(genericNode("azure:servicebus:__receiver__", NodeKind.QUEUE, "azure:servicebus:receiver",
                    Map.of("broker", "azure_servicebus", "role", "receiver")));
            addMessagingEdge(classNodeId, "azure:servicebus:__receiver__", EdgeKind.RECEIVES_FROM,
                    className + " receives from Azure Service Bus", Map.of(), edges);
        } else if (hasSbClient && queueNames.isEmpty() && topicNames.isEmpty() && !isSbSender && !isSbReceiver) {
            nodes.add(genericNode("azure:servicebus:__client__", NodeKind.QUEUE, "azure:servicebus:client",
                    Map.of("broker", "azure_servicebus", "role", "client")));
            addMessagingEdge(classNodeId, "azure:servicebus:__client__", EdgeKind.CONNECTS_TO,
                    className + " connects to Azure Service Bus", Map.of(), edges);
        }

        if (isEhProducer && ehNames.isEmpty()) {
            nodes.add(genericNode("azure:eventhub:__producer__", NodeKind.TOPIC, "azure:eventhub:producer",
                    Map.of("broker", "azure_eventhub", "role", "producer")));
            addMessagingEdge(classNodeId, "azure:eventhub:__producer__", EdgeKind.SENDS_TO,
                    className + " sends to Azure Event Hub", Map.of(), edges);
        } else if (isEhConsumer && ehNames.isEmpty()) {
            nodes.add(genericNode("azure:eventhub:__consumer__", NodeKind.TOPIC, "azure:eventhub:consumer",
                    Map.of("broker", "azure_eventhub", "role", "consumer")));
            addMessagingEdge(classNodeId, "azure:eventhub:__consumer__", EdgeKind.RECEIVES_FROM,
                    className + " receives from Azure Event Hub", Map.of(), edges);
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureSbQueueNode(String name, Set<String> seen, List<CodeNode> nodes) {
        String nodeId = "azure:servicebus:" + name;
        if (!seen.contains(name)) {
            seen.add(name);
            nodes.add(genericNode(nodeId, NodeKind.QUEUE, "azure:servicebus:" + name,
                    Map.of("broker", "azure_servicebus", "queue", name)));
        }
        return nodeId;
    }

    private String ensureSbTopicNode(String name, Set<String> seen, List<CodeNode> nodes) {
        String nodeId = "azure:servicebus:" + name;
        if (!seen.contains(name)) {
            seen.add(name);
            nodes.add(genericNode(nodeId, NodeKind.TOPIC, "azure:servicebus:" + name,
                    Map.of("broker", "azure_servicebus", "topic", name)));
        }
        return nodeId;
    }

    private String ensureEventhubNode(String name, Set<String> seen, List<CodeNode> nodes) {
        String nodeId = "azure:eventhub:" + name;
        if (!seen.contains(name)) {
            seen.add(name);
            nodes.add(genericNode(nodeId, NodeKind.TOPIC, "azure:eventhub:" + name,
                    Map.of("broker", "azure_eventhub", "event_hub", name)));
        }
        return nodeId;
    }

    private CodeNode genericNode(String id, NodeKind kind, String label, Map<String, Object> props) {
        CodeNode node = new CodeNode();
        node.setId(id);
        node.setKind(kind);
        node.setLabel(label);
        node.setProperties(new LinkedHashMap<>(props));
        return node;
    }

    private void addMessagingEdge(String sourceId, String targetId, EdgeKind kind, String label,
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
