package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
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
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "kafka_js",
    category = "messaging",
    description = "Detects KafkaJS producers, consumers, and admin operations",
    parser = ParserType.REGEX,
    languages = {"typescript", "javascript"},
    nodeKinds = {NodeKind.DATABASE_CONNECTION, NodeKind.EVENT, NodeKind.TOPIC},
    edgeKinds = {EdgeKind.CONSUMES, EdgeKind.PRODUCES},
    properties = {"broker", "group_id", "topic"}
)
@Component
public class KafkaJSDetector extends AbstractAntlrDetector {

    private static final Pattern KAFKA_NEW_RE = Pattern.compile("new\\s+Kafka\\s*\\(\\s*\\{");
    private static final Pattern PRODUCER_RE = Pattern.compile("\\.producer\\s*\\(\\s*\\)");
    private static final Pattern PRODUCER_SEND_RE = Pattern.compile(
            "\\.send\\s*\\(\\s*\\{\\s*topic\\s*:\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern CONSUMER_RE = Pattern.compile(
            "\\.consumer\\s*\\(\\s*\\{\\s*groupId\\s*:\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern SUBSCRIBE_RE = Pattern.compile(
            "\\.subscribe\\s*\\(\\s*\\{\\s*topic\\s*:\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern RUN_EACH_RE = Pattern.compile(
            "\\.run\\s*\\(\\s*\\{\\s*eachMessage\\s*:"
    );

    @Override
    public String getName() {
        return "kafka_js";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript");
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
        String fp = ctx.filePath();
        String moduleName = ctx.moduleName();

        if (!text.contains("Kafka") && !text.contains("kafka")) {
            return DetectorResult.empty();
        }

        Set<String> seenTopics = new HashSet<>();
        String fileNodeId = "kafka_js:" + fp;

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineno = i + 1;

            // new Kafka({ -> DATABASE_CONNECTION
            if (KAFKA_NEW_RE.matcher(line).find()) {
                CodeNode node = new CodeNode();
                node.setId("kafka_js:" + fp + ":connection:" + lineno);
                node.setKind(NodeKind.DATABASE_CONNECTION);
                node.setLabel("KafkaJS connection");
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineno);
                node.getProperties().put("broker", "kafka");
                node.getProperties().put("library", "kafkajs");
                nodes.add(node);
            }

            // .producer() -> TOPIC node
            if (PRODUCER_RE.matcher(line).find()) {
                CodeNode node = new CodeNode();
                node.setId("kafka_js:" + fp + ":producer:" + lineno);
                node.setKind(NodeKind.TOPIC);
                node.setLabel("kafka:producer");
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineno);
                node.getProperties().put("role", "producer");
                nodes.add(node);
            }

            // .send({ topic: 'name' }) -> TOPIC + PRODUCES edge
            Matcher m = PRODUCER_SEND_RE.matcher(line);
            if (m.find()) {
                String topic = m.group(1);
                String topicId = ensureTopic(nodes, seenTopics, fp, moduleName, topic, lineno);
                CodeEdge edge = new CodeEdge();
                edge.setId(fileNodeId + "->produces->" + topicId);
                edge.setKind(EdgeKind.PRODUCES);
                edge.setSourceId(fileNodeId);
                edge.getProperties().put("topic", topic);
                edges.add(edge);
            }

            // .consumer({ groupId: 'group' })
            m = CONSUMER_RE.matcher(line);
            if (m.find()) {
                String groupId = m.group(1);
                CodeNode node = new CodeNode();
                node.setId("kafka_js:" + fp + ":consumer:" + lineno);
                node.setKind(NodeKind.TOPIC);
                node.setLabel("kafka:consumer:" + groupId);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineno);
                node.getProperties().put("role", "consumer");
                node.getProperties().put("group_id", groupId);
                nodes.add(node);
            }

            // .subscribe({ topic: 'name' }) -> CONSUMES edge
            m = SUBSCRIBE_RE.matcher(line);
            if (m.find()) {
                String topic = m.group(1);
                String topicId = ensureTopic(nodes, seenTopics, fp, moduleName, topic, lineno);
                CodeEdge edge = new CodeEdge();
                edge.setId(fileNodeId + "->consumes->" + topicId);
                edge.setKind(EdgeKind.CONSUMES);
                edge.setSourceId(fileNodeId);
                edge.getProperties().put("topic", topic);
                edges.add(edge);
            }

            // .run({ eachMessage: }) -> EVENT node
            if (RUN_EACH_RE.matcher(line).find()) {
                CodeNode node = new CodeNode();
                node.setId("kafka_js:" + fp + ":event:" + lineno);
                node.setKind(NodeKind.EVENT);
                node.setLabel("kafka:eachMessage");
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineno);
                node.getProperties().put("handler", "eachMessage");
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private String ensureTopic(List<CodeNode> nodes, Set<String> seenTopics,
                               String fp, String moduleName, String topic, int lineno) {
        String topicId = "kafka_js:" + fp + ":topic:" + topic;
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
            node.getProperties().put("topic", topic);
            nodes.add(node);
        }
        return topicId;
    }
}
