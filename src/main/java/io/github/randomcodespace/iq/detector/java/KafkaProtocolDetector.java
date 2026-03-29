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
 * Detects classes extending AbstractRequest or AbstractResponse (Kafka binary protocol messages).
 */
@Component
public class KafkaProtocolDetector extends AbstractRegexDetector {

    private static final Pattern PROTOCOL_MSG_RE = Pattern.compile(
            "class\\s+(\\w+)\\s+extends\\s+(AbstractRequest|AbstractResponse)(?!\\.)" + "\\b");

    @Override
    public String getName() {
        return "kafka_protocol";
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

        if (!text.contains("AbstractRequest") && !text.contains("AbstractResponse")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher m = PROTOCOL_MSG_RE.matcher(lines[i]);
            if (!m.find()) continue;

            String className = m.group(1);
            String parentClass = m.group(2);
            String protocolType = "AbstractRequest".equals(parentClass) ? "request" : "response";

            String nodeId = ctx.filePath() + ":" + className;

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.PROTOCOL_MESSAGE);
            node.setLabel(className);
            node.setFilePath(ctx.filePath());
            node.setLineStart(i + 1);
            node.getProperties().put("protocol_type", protocolType);
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(nodeId + "->extends->*:" + parentClass);
            edge.setKind(EdgeKind.EXTENDS);
            edge.setSourceId(nodeId);
            edge.setTarget(new CodeNode("*:" + parentClass, NodeKind.PROTOCOL_MESSAGE, parentClass));
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
