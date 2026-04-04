package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "kafka_python",
    category = "messaging",
    description = "Detects Python Kafka producers and consumers (kafka-python, confluent-kafka)",
    parser = ParserType.REGEX,
    languages = {"python"},
    nodeKinds = {NodeKind.TOPIC},
    edgeKinds = {EdgeKind.CONSUMES, EdgeKind.IMPORTS, EdgeKind.PRODUCES},
    properties = {"broker", "topic"}
)
@Component
public class KafkaPythonDetector extends AbstractAntlrDetector {
    private static final String PROP_PRODUCER = "producer";
    private static final String PROP_ROLE = "role";
    private static final String PROP_TOPIC = "topic";


    // --- Regex patterns ---
    private static final Pattern PRODUCER_RE = Pattern.compile(
            "(KafkaProducer|AIOKafkaProducer)\\s*\\(", Pattern.MULTILINE
    );
    private static final Pattern CONFLUENT_PRODUCER_RE = Pattern.compile(
            "Producer\\s*\\(\\s*\\{", Pattern.MULTILINE
    );
    private static final Pattern CONSUMER_RE = Pattern.compile(
            "(KafkaConsumer|AIOKafkaConsumer)\\s*\\(", Pattern.MULTILINE
    );
    private static final Pattern CONFLUENT_CONSUMER_RE = Pattern.compile(
            "Consumer\\s*\\(\\s*\\{", Pattern.MULTILINE
    );
    private static final Pattern SEND_RE = Pattern.compile(
            "\\.send\\s*\\(\\s*['\"]([^'\"]+)['\"]", Pattern.MULTILINE
    );
    private static final Pattern PRODUCE_RE = Pattern.compile(
            "\\.produce\\s*\\(\\s*['\"]([^'\"]+)['\"]", Pattern.MULTILINE
    );
    private static final Pattern SUBSCRIBE_RE = Pattern.compile(
            "\\.subscribe\\s*\\(\\s*\\[\\s*['\"]([^'\"]+)['\"]", Pattern.MULTILINE
    );
    private static final Pattern IMPORT_RE = Pattern.compile(
            "(?:from|import)\\s+(confluent_kafka|kafka|aiokafka)\\b", Pattern.MULTILINE
    );

    private static final List<String> KAFKA_KEYWORDS = List.of(
            "KafkaProducer", "KafkaConsumer",
            "AIOKafkaProducer", "AIOKafkaConsumer",
            "confluent_kafka", "from kafka",
            "import kafka", "Producer(", "Consumer("
    );

    @Override
    public String getName() {
        return "kafka_python";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("python");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }
        String fp = ctx.filePath();
        String moduleName = ctx.moduleName();

        boolean hasKafka = false;
        for (String kw : KAFKA_KEYWORDS) {
            if (text.contains(kw)) {
                hasKafka = true;
                break;
            }
        }
        if (!hasKafka) {
            return DetectorResult.empty();
        }

        Set<String> seenTopics = new HashSet<>();
        String fileNodeId = "kafka_py:" + fp;
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            int lineno = i + 1;
            if (PRODUCER_RE.matcher(lines[i]).find() || CONFLUENT_PRODUCER_RE.matcher(lines[i]).find()) {
                CodeNode node = new CodeNode();
                node.setId("kafka_py:" + fp + ":producer:" + lineno);
                node.setKind(NodeKind.TOPIC);
                node.setLabel("kafka:producer");
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineno);
                node.getProperties().put(PROP_ROLE, PROP_PRODUCER);
                nodes.add(node);
            }
        }

        for (int i = 0; i < lines.length; i++) {
            int lineno = i + 1;
            if (CONSUMER_RE.matcher(lines[i]).find() || CONFLUENT_CONSUMER_RE.matcher(lines[i]).find()) {
                CodeNode node = new CodeNode();
                node.setId("kafka_py:" + fp + ":consumer:" + lineno);
                node.setKind(NodeKind.TOPIC);
                node.setLabel("kafka:consumer");
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineno);
                node.getProperties().put(PROP_ROLE, "consumer");
                nodes.add(node);
            }
        }

        for (int i = 0; i < lines.length; i++) {
            int lineno = i + 1;
            Matcher sm = SEND_RE.matcher(lines[i]);
            if (sm.find() && lines[i].contains("send")) {
                String topic = sm.group(1);
                String topicId = ensureTopic(nodes, seenTopics, fp, moduleName, topic, PROP_PRODUCER, lineno);
                CodeEdge edge = new CodeEdge();
                edge.setId(fileNodeId + "->produces->" + topicId);
                edge.setKind(EdgeKind.PRODUCES);
                edge.setSourceId(fileNodeId);
                edge.getProperties().put(PROP_TOPIC, topic);
                edges.add(edge);
                continue;
            }
            Matcher pm = PRODUCE_RE.matcher(lines[i]);
            if (pm.find()) {
                String topic = pm.group(1);
                String topicId = ensureTopic(nodes, seenTopics, fp, moduleName, topic, PROP_PRODUCER, lineno);
                CodeEdge edge = new CodeEdge();
                edge.setId(fileNodeId + "->produces->" + topicId);
                edge.setKind(EdgeKind.PRODUCES);
                edge.setSourceId(fileNodeId);
                edge.getProperties().put(PROP_TOPIC, topic);
                edges.add(edge);
            }
        }

        for (int i = 0; i < lines.length; i++) {
            int lineno = i + 1;
            Matcher subm = SUBSCRIBE_RE.matcher(lines[i]);
            if (subm.find()) {
                String topic = subm.group(1);
                String topicId = ensureTopic(nodes, seenTopics, fp, moduleName, topic, "consumer", lineno);
                CodeEdge edge = new CodeEdge();
                edge.setId(fileNodeId + "->consumes->" + topicId);
                edge.setKind(EdgeKind.CONSUMES);
                edge.setSourceId(fileNodeId);
                edge.getProperties().put(PROP_TOPIC, topic);
                edges.add(edge);
            }
        }

        for (String line : lines) {
            Matcher im = IMPORT_RE.matcher(line);
            if (im.find()) {
                String lib = im.group(1);
                CodeEdge edge = new CodeEdge();
                edge.setId(fileNodeId + "->imports->kafka_py:lib:" + lib);
                edge.setKind(EdgeKind.IMPORTS);
                edge.setSourceId(fileNodeId);
                edge.getProperties().put("library", lib);
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureTopic(List<CodeNode> nodes, Set<String> seenTopics,
                                String fp, String moduleName,
                                String topic, String role, int lineno) {
        String topicId = "kafka_py:" + fp + ":topic:" + topic;
        if (!seenTopics.contains(topic)) {
            seenTopics.add(topic);
            CodeNode node = new CodeNode();
            node.setId(topicId);
            node.setKind(NodeKind.TOPIC);
            node.setLabel("kafka:" + topic);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(lineno);
            node.getProperties().put("broker", "kafka");
            node.getProperties().put(PROP_TOPIC, topic);
            node.getProperties().put(PROP_ROLE, role);
            nodes.add(node);
        }
        return topicId;
    }
}
