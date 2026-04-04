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
 * Detects RabbitMQ consumers and producers.
 */
@DetectorInfo(
    name = "rabbitmq",
    category = "messaging",
    description = "Detects RabbitMQ queues, exchanges, and bindings",
    languages = {"java"},
    nodeKinds = {NodeKind.QUEUE},
    edgeKinds = {EdgeKind.CONSUMES, EdgeKind.PRODUCES},
    properties = {"broker", "exchange", "queue", "routing_key"}
)
@Component
public class RabbitmqDetector extends AbstractJavaMessagingDetector {

    private static final Pattern RABBIT_LISTENER_RE = Pattern.compile(
            "@RabbitListener\\s*\\(\\s*(?:.*?queues?\\s*=\\s*)?[\\{\"]?\\s*\"([^\"]+)\"");
    private static final Pattern RABBIT_SEND_RE = Pattern.compile(
            "(?:rabbitTemplate|RabbitTemplate)\\s*\\.(?:convertAndSend|send)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern EXCHANGE_RE = Pattern.compile(
            "(?:DirectExchange|TopicExchange|FanoutExchange|HeadersExchange)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern ROUTING_KEY_RE = Pattern.compile("routingKey\\s*=\\s*\"([^\"]+)\"");

    @Override
    public String getName() {
        return "rabbitmq";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("@RabbitListener") && !text.contains("RabbitTemplate") && !text.contains("rabbitTemplate")
                && !text.contains("DirectExchange") && !text.contains("TopicExchange") && !text.contains("FanoutExchange")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = extractClassName(text);
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;
        Set<String> seenQueues = new LinkedHashSet<>();

        // @RabbitListener consumers
        for (int i = 0; i < lines.length; i++) {
            Matcher m = RABBIT_LISTENER_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String queue = m.group(1);
            String queueId = ensureQueueNode(queue, seenQueues, nodes);
            addMessagingEdge(classNodeId, queueId, EdgeKind.CONSUMES, queue,
                    Map.of("queue", queue), edges);
        }

        // RabbitTemplate sends
        for (int i = 0; i < lines.length; i++) {
            Matcher m = RABBIT_SEND_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String exchangeOrQueue = m.group(1);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("exchange", exchangeOrQueue);
            Matcher rk = ROUTING_KEY_RE.matcher(lines[i]);
            if (rk.find()) props.put("routing_key", rk.group(1));

            String queueId = "rabbitmq:exchange:" + exchangeOrQueue;
            if (!seenQueues.contains(exchangeOrQueue)) {
                seenQueues.add(exchangeOrQueue);
                CodeNode node = new CodeNode();
                node.setId(queueId);
                node.setKind(NodeKind.QUEUE);
                node.setLabel("rabbitmq:" + exchangeOrQueue);
                node.getProperties().put("broker", "rabbitmq");
                node.getProperties().put("exchange", exchangeOrQueue);
                nodes.add(node);
            }

            addMessagingEdge(classNodeId, queueId, EdgeKind.PRODUCES, exchangeOrQueue, props, edges);
        }

        // Exchange declarations
        for (Matcher m = EXCHANGE_RE.matcher(text); m.find(); ) {
            String exchangeName = m.group(1);
            int lineNum = findLineNumber(text, m.start());
            String exchangeId = "rabbitmq:exchange:" + exchangeName;
            if (!seenQueues.contains(exchangeName)) {
                seenQueues.add(exchangeName);
                CodeNode node = new CodeNode();
                node.setId(exchangeId);
                node.setKind(NodeKind.QUEUE);
                node.setLabel("rabbitmq:exchange:" + exchangeName);
                node.setFilePath(ctx.filePath());
                node.setLineStart(lineNum);
                node.getProperties().put("broker", "rabbitmq");
                node.getProperties().put("exchange", exchangeName);
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureQueueNode(String queue, Set<String> seen, List<CodeNode> nodes) {
        String queueId = "rabbitmq:queue:" + queue;
        if (!seen.contains(queue)) {
            seen.add(queue);
            CodeNode node = new CodeNode();
            node.setId(queueId);
            node.setKind(NodeKind.QUEUE);
            node.setLabel("rabbitmq:" + queue);
            node.getProperties().put("broker", "rabbitmq");
            node.getProperties().put("queue", queue);
            nodes.add(node);
        }
        return queueId;
    }
}
