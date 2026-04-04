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
 * Detects WebSocket endpoints and message handlers.
 */
@DetectorInfo(
    name = "websocket",
    category = "endpoints",
    description = "Detects WebSocket endpoints (@ServerEndpoint, STOMP destinations)",
    languages = {"java"},
    nodeKinds = {NodeKind.WEBSOCKET_ENDPOINT},
    edgeKinds = {EdgeKind.EXPOSES, EdgeKind.PRODUCES},
    properties = {"destination", "path", "protocol"}
)
@Component
public class WebSocketDetector extends AbstractRegexDetector {
    private static final String PROP_DESTINATION = "destination";
    private static final String PROP_PROTOCOL = "protocol";
    private static final String PROP_TYPE = "type";
    private static final String PROP_WEBSOCKET = "websocket";


    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern SERVER_ENDPOINT_RE = Pattern.compile("@ServerEndpoint\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern MESSAGE_MAPPING_RE = Pattern.compile("@MessageMapping\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern SEND_TO_RE = Pattern.compile("@SendTo\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern SEND_TO_USER_RE = Pattern.compile("@SendToUser\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern STOMP_ENDPOINT_RE = Pattern.compile(
            "registerStompEndpoints.*?\\.addEndpoint\\s*\\(\\s*\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern MESSAGING_TEMPLATE_RE = Pattern.compile(
            "(?:simpMessagingTemplate|messagingTemplate)\\s*\\.(?:convertAndSend|convertAndSendToUser)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern METHOD_RE = Pattern.compile("(?:public|protected|private)?\\s*(?:[\\w<>\\[\\],?\\s]+)\\s+(\\w+)\\s*\\(");

    @Override
    public String getName() {
        return PROP_WEBSOCKET;
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("@ServerEndpoint") && !text.contains("@MessageMapping") && !text.contains("WebSocketHandler")
                && !text.contains("registerStompEndpoints") && !text.contains("SimpMessagingTemplate")
                && !text.contains("simpMessagingTemplate") && !text.contains("messagingTemplate")
                && !text.contains("@SendTo") && !text.contains("@SendToUser")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher cm = CLASS_RE.matcher(lines[i]);
            if (cm.find()) { className = cm.group(1); break; }
        }
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;

        // @ServerEndpoint (JSR 356)
        for (Matcher m = SERVER_ENDPOINT_RE.matcher(text); m.find(); ) {
            String path = m.group(1);
            int lineNum = findLineNumber(text, m.start());
            String wsId = "ws:endpoint:" + path;
            CodeNode node = new CodeNode();
            node.setId(wsId);
            node.setKind(NodeKind.WEBSOCKET_ENDPOINT);
            node.setLabel("WS " + path);
            node.setFqn(className + ":" + path);
            node.setFilePath(ctx.filePath());
            node.setLineStart(lineNum);
            node.getAnnotations().add("@ServerEndpoint");
            node.getProperties().put("path", path);
            node.getProperties().put(PROP_PROTOCOL, PROP_WEBSOCKET);
            node.getProperties().put(PROP_TYPE, "jsr356");
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->exposes->" + wsId);
            edge.setKind(EdgeKind.EXPOSES);
            edge.setSourceId(classNodeId);
            edge.setTarget(node);
            edges.add(edge);
        }

        // @MessageMapping (Spring STOMP)
        for (int i = 0; i < lines.length; i++) {
            Matcher m = MESSAGE_MAPPING_RE.matcher(lines[i]);
            if (!m.find()) continue;
            String destination = m.group(1);
            String methodName = null;
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                Matcher mm = METHOD_RE.matcher(lines[k]);
                if (mm.find()) { methodName = mm.group(1); break; }
            }
            String wsId = "ws:message:" + destination;
            CodeNode node = new CodeNode();
            node.setId(wsId);
            node.setKind(NodeKind.WEBSOCKET_ENDPOINT);
            node.setLabel("WS MSG " + destination);
            node.setFqn(className + "." + (methodName != null ? methodName : "unknown"));
            node.setFilePath(ctx.filePath());
            node.setLineStart(i + 1);
            node.getAnnotations().add("@MessageMapping");
            node.getProperties().put(PROP_DESTINATION, destination);
            node.getProperties().put(PROP_PROTOCOL, PROP_WEBSOCKET);
            node.getProperties().put(PROP_TYPE, "stomp");
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->exposes->" + wsId);
            edge.setKind(EdgeKind.EXPOSES);
            edge.setSourceId(classNodeId);
            edge.setTarget(node);
            edges.add(edge);

            // Check for @SendTo
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                Matcher st = SEND_TO_RE.matcher(lines[k]);
                if (!st.find()) st = SEND_TO_USER_RE.matcher(lines[k]);
                if (st.find()) {
                    String sendDest = st.group(1);
                    String sendId = "ws:topic:" + sendDest;
                    CodeNode sendNode = new CodeNode();
                    sendNode.setId(sendId);
                    sendNode.setKind(NodeKind.WEBSOCKET_ENDPOINT);
                    sendNode.setLabel("WS TOPIC " + sendDest);
                    sendNode.getProperties().put(PROP_DESTINATION, sendDest);
                    sendNode.getProperties().put(PROP_PROTOCOL, PROP_WEBSOCKET);
                    nodes.add(sendNode);

                    CodeEdge sendEdge = new CodeEdge();
                    sendEdge.setId(wsId + "->produces->" + sendId);
                    sendEdge.setKind(EdgeKind.PRODUCES);
                    sendEdge.setSourceId(wsId);
                    sendEdge.setTarget(sendNode);
                    edges.add(sendEdge);
                }
            }
        }

        // STOMP endpoint registration
        for (Matcher m = STOMP_ENDPOINT_RE.matcher(text); m.find(); ) {
            String path = m.group(1);
            String wsId = "ws:stomp:" + path;
            int lineNum = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId(wsId);
            node.setKind(NodeKind.WEBSOCKET_ENDPOINT);
            node.setLabel("STOMP " + path);
            node.setFilePath(ctx.filePath());
            node.setLineStart(lineNum);
            node.getProperties().put("path", path);
            node.getProperties().put(PROP_PROTOCOL, "stomp");
            node.getProperties().put(PROP_TYPE, "stomp_endpoint");
            nodes.add(node);
        }

        // SimpMessagingTemplate sends
        for (Matcher m = MESSAGING_TEMPLATE_RE.matcher(text); m.find(); ) {
            String destination = m.group(1);
            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->produces->ws:topic:" + destination);
            edge.setKind(EdgeKind.PRODUCES);
            edge.setSourceId(classNodeId);
            edge.setTarget(new CodeNode("ws:topic:" + destination, NodeKind.WEBSOCKET_ENDPOINT, destination));
            edge.setProperties(Map.of(PROP_DESTINATION, destination));
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
