package io.github.randomcodespace.iq.detector.jvm.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Apache ActiveMQ usage — both ActiveMQ Classic ({@code
 * org.apache.activemq}) and ActiveMQ Artemis ({@code
 * org.apache.activemq.artemis}). Both products ship a class literally
 * named {@code ActiveMQConnectionFactory}, so the broker flavour is
 * disambiguated by the surrounding import / FQN.
 */
@DetectorInfo(
    name = "active_mq",
    category = "messaging",
    description = "Detects Apache ActiveMQ (Classic and Artemis) queue and topic connections",
    languages = {"java"},
    nodeKinds = {NodeKind.MESSAGE_QUEUE, NodeKind.QUEUE, NodeKind.TOPIC},
    edgeKinds = {EdgeKind.CONNECTS_TO, EdgeKind.RECEIVES_FROM, EdgeKind.SENDS_TO},
    properties = {"broker", "queue", "topic", "broker_url", "factory_type"}
)
@Component
public class ActiveMqDetector extends AbstractJavaMessagingDetector {
    private static final String PROP_BROKER = "broker";
    private static final String PROP_BROKER_URL = "broker_url";
    private static final String PROP_FACTORY_TYPE = "factory_type";
    private static final String PROP_QUEUE = "queue";
    private static final String PROP_TOPIC = "topic";

    private static final String BROKER_AMQ_CLASSIC = "activemq";
    private static final String BROKER_AMQ_ARTEMIS = "activemq_artemis";

    // Distinguishes Classic vs Artemis by import path or FQN. If neither
    // shows up but the bare class name does, default to Classic (it's the
    // older, more common product).
    private static final Pattern ARTEMIS_IMPORT_RE = Pattern.compile(
            "import\\s+org\\.apache\\.activemq\\.artemis\\.|org\\.apache\\.activemq\\.artemis\\.");
    private static final Pattern CLASSIC_IMPORT_RE = Pattern.compile(
            "import\\s+org\\.apache\\.activemq\\.(?!artemis\\.)");

    // Connection-factory class references (both products share names).
    private static final Pattern FACTORY_RE = Pattern.compile(
            "\\b(ActiveMQConnectionFactory|ActiveMQQueueConnectionFactory|"
                    + "ActiveMQTopicConnectionFactory|ActiveMQJMSConnectionFactory|"
                    + "ActiveMQXAConnectionFactory|PooledConnectionFactory)\\b");

    // Broker URLs. Two grammars to support:
    //   1. scheme://host:port — tcp/ssl/nio/udp/vm/amqp/stomp/mqtt/ws/wss
    //      with optional ActiveMQ +nio / +ssl modifiers.
    //   2. failover:(...) — ActiveMQ's failover transport, which uses
    //      the form `failover:(tcp://a,tcp://b)?opts` or
    //      `failover:tcp://a,tcp://b`. The scheme is followed by ":" and
    //      then either "(" or another scheme — NOT "://".
    private static final Pattern BROKER_URL_RE = Pattern.compile(
            "\"((?:(?:tcp|ssl|nio|udp|vm|amqp|stomp|mqtt|ws|wss)"
                    + "(?:\\+nio|\\+ssl)?://[^\"]+|failover:[^\"]+))\"");

    // Spring Boot config keys — application.properties / application.yml.
    private static final Pattern SPRING_BROKER_URL_RE = Pattern.compile(
            "(?m)^\\s*spring\\.(activemq|artemis)\\.broker[._-]url\\s*[=:]\\s*(\\S+)");

    // Destination instantiation and per-API patterns.
    private static final Pattern AMQ_QUEUE_RE = Pattern.compile(
            "new\\s+ActiveMQQueue\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern AMQ_TOPIC_RE = Pattern.compile(
            "new\\s+ActiveMQTopic\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CREATE_QUEUE_RE = Pattern.compile(
            "createQueue\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CREATE_TOPIC_RE = Pattern.compile(
            "createTopic\\s*\\(\\s*\"([^\"]+)\"");

    // Producer/consumer affordances.
    private static final Pattern SEND_RE = Pattern.compile("\\bsend\\s*\\(");
    private static final Pattern PUBLISH_RE = Pattern.compile("\\bpublish\\s*\\(");
    private static final Pattern RECEIVE_RE = Pattern.compile("\\breceive\\s*\\(");
    private static final Pattern ON_MESSAGE_RE = Pattern.compile("\\bonMessage\\s*\\(");
    private static final Pattern PRODUCER_RE = Pattern.compile("\\bMessageProducer\\b");
    private static final Pattern CONSUMER_RE = Pattern.compile("\\bMessageConsumer\\b");

    @Override
    public String getName() {
        return "active_mq";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        // Quick-reject: must mention either an import/FQN of activemq or one
        // of the distinctive class names. Avoids the lines×patterns loop on
        // ~all non-messaging Java files.
        boolean hasArtemis = ARTEMIS_IMPORT_RE.matcher(text).find();
        boolean hasClassic = !hasArtemis && CLASSIC_IMPORT_RE.matcher(text).find();
        boolean hasClassRef = text.contains("ActiveMQConnectionFactory")
                || text.contains("ActiveMQQueue")
                || text.contains("ActiveMQTopic")
                || text.contains("ActiveMQJMSConnectionFactory");
        boolean hasSpringConfig = text.contains("spring.activemq.")
                || text.contains("spring.artemis.");
        if (!hasArtemis && !hasClassic && !hasClassRef && !hasSpringConfig) {
            return DetectorResult.empty();
        }

        // Disambiguate broker flavour. Default to Classic when the bare class
        // name appears with no import context (older codebases sometimes
        // shadow imports via wildcards).
        String broker = hasArtemis ? BROKER_AMQ_ARTEMIS : BROKER_AMQ_CLASSIC;

        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        Set<String> seenQueues = new LinkedHashSet<>();
        Set<String> seenTopics = new LinkedHashSet<>();

        // Spring Boot config — application.properties / application.yml.
        // These files don't have a class context, so we emit a broker node
        // alone; the application-level CONNECTS_TO edge is added by the
        // class-context branch below if any.
        Matcher springM = SPRING_BROKER_URL_RE.matcher(text);
        while (springM.find()) {
            String flavor = springM.group(1).toLowerCase();
            String detectedBroker = "artemis".equals(flavor) ? BROKER_AMQ_ARTEMIS : BROKER_AMQ_CLASSIC;
            String url = springM.group(2).replaceAll("[\"']", "");
            String nodeId = "amq:server:" + detectedBroker + ":" + url;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MESSAGE_QUEUE);
            node.setLabel(detectedBroker + ":" + url);
            node.getProperties().put(PROP_BROKER, detectedBroker);
            node.getProperties().put(PROP_BROKER_URL, url);
            nodes.add(node);
        }

        // For class-context edges we need a class name. .properties / .yaml
        // won't have one — that's fine, we already emitted the broker node
        // above, just skip the rest.
        String className = extractClassName(text);
        if (className == null) {
            return DetectorResult.of(nodes, edges);
        }

        String classNodeId = ctx.filePath() + ":" + className;
        String[] lines = text.split("\n", -1);

        boolean isProducer = SEND_RE.matcher(text).find()
                || PUBLISH_RE.matcher(text).find()
                || PRODUCER_RE.matcher(text).find();
        boolean isConsumer = RECEIVE_RE.matcher(text).find()
                || ON_MESSAGE_RE.matcher(text).find()
                || CONSUMER_RE.matcher(text).find();

        // Connection factory + nearby broker URL.
        for (int i = 0; i < lines.length; i++) {
            Matcher m = FACTORY_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String factoryType = m.group(1);
            String url = null;
            for (int j = Math.max(0, i - 1); j < Math.min(lines.length, i + 4); j++) {
                Matcher urlM = BROKER_URL_RE.matcher(lines[j]);
                if (urlM.find()) {
                    url = urlM.group(1);
                    break;
                }
            }

            String nodeId = "amq:server:" + broker + ":" + factoryType
                    + (url != null ? ":" + url : "");
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MESSAGE_QUEUE);
            node.setLabel(broker + ":" + factoryType);
            node.getProperties().put(PROP_BROKER, broker);
            node.getProperties().put(PROP_FACTORY_TYPE, factoryType);
            if (url != null) node.getProperties().put(PROP_BROKER_URL, url);
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->connects_to->" + nodeId);
            edge.setKind(EdgeKind.CONNECTS_TO);
            edge.setSourceId(classNodeId);
            edge.setTarget(node);
            edge.setProperties(Map.of(PROP_FACTORY_TYPE, factoryType));
            edges.add(edge);
        }

        // Direct destination instantiation: new ActiveMQQueue("...") /
        // new ActiveMQTopic("...").
        for (String line : lines) {
            Matcher mq = AMQ_QUEUE_RE.matcher(line);
            if (mq.find()) {
                String name = mq.group(1);
                String qid = ensureQueueNode(name, broker, seenQueues, nodes);
                if (isProducer) addMessagingEdge(classNodeId, qid, EdgeKind.SENDS_TO,
                        className + " sends to " + name, Map.of(PROP_QUEUE, name), edges);
                if (isConsumer) addMessagingEdge(classNodeId, qid, EdgeKind.RECEIVES_FROM,
                        className + " receives from " + name, Map.of(PROP_QUEUE, name), edges);
            }
            Matcher mt = AMQ_TOPIC_RE.matcher(line);
            if (mt.find()) {
                String name = mt.group(1);
                String tid = ensureTopicNode(name, broker, seenTopics, nodes);
                if (isProducer) addMessagingEdge(classNodeId, tid, EdgeKind.SENDS_TO,
                        className + " sends to " + name, Map.of(PROP_TOPIC, name), edges);
                if (isConsumer) addMessagingEdge(classNodeId, tid, EdgeKind.RECEIVES_FROM,
                        className + " receives from " + name, Map.of(PROP_TOPIC, name), edges);
            }
        }

        // session.createQueue("...") / session.createTopic("...") — only
        // attribute these to ActiveMQ when the file already mentions an AMQ
        // factory or import to avoid double-counting against JmsDetector.
        boolean isAmqContext = hasArtemis || hasClassic || hasClassRef;
        if (isAmqContext) {
            for (String line : lines) {
                Matcher cq = CREATE_QUEUE_RE.matcher(line);
                if (cq.find()) {
                    String name = cq.group(1);
                    String qid = ensureQueueNode(name, broker, seenQueues, nodes);
                    if (isProducer) addMessagingEdge(classNodeId, qid, EdgeKind.SENDS_TO,
                            className + " sends to " + name, Map.of(PROP_QUEUE, name), edges);
                    if (isConsumer) addMessagingEdge(classNodeId, qid, EdgeKind.RECEIVES_FROM,
                            className + " receives from " + name, Map.of(PROP_QUEUE, name), edges);
                }
                Matcher ct = CREATE_TOPIC_RE.matcher(line);
                if (ct.find()) {
                    String name = ct.group(1);
                    String tid = ensureTopicNode(name, broker, seenTopics, nodes);
                    if (isProducer) addMessagingEdge(classNodeId, tid, EdgeKind.SENDS_TO,
                            className + " sends to " + name, Map.of(PROP_TOPIC, name), edges);
                    if (isConsumer) addMessagingEdge(classNodeId, tid, EdgeKind.RECEIVES_FROM,
                            className + " receives from " + name, Map.of(PROP_TOPIC, name), edges);
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureQueueNode(String name, String broker, Set<String> seen, List<CodeNode> nodes) {
        String id = "amq:queue:" + broker + ":" + name;
        if (!seen.contains(name)) {
            seen.add(name);
            CodeNode node = new CodeNode();
            node.setId(id);
            node.setKind(NodeKind.QUEUE);
            node.setLabel(broker + ":queue:" + name);
            node.getProperties().put(PROP_BROKER, broker);
            node.getProperties().put(PROP_QUEUE, name);
            nodes.add(node);
        }
        return id;
    }

    private String ensureTopicNode(String name, String broker, Set<String> seen, List<CodeNode> nodes) {
        String id = "amq:topic:" + broker + ":" + name;
        if (!seen.contains(name)) {
            seen.add(name);
            CodeNode node = new CodeNode();
            node.setId(id);
            node.setKind(NodeKind.TOPIC);
            node.setLabel(broker + ":topic:" + name);
            node.getProperties().put(PROP_BROKER, broker);
            node.getProperties().put(PROP_TOPIC, name);
            nodes.add(node);
        }
        return id;
    }
}
