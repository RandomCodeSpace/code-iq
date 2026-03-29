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
 * Detects Azure Functions triggers and bindings.
 */
@Component
public class AzureFunctionsDetector extends AbstractRegexDetector {

    private static final Pattern FUNCTION_NAME_RE = Pattern.compile("@FunctionName\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern HTTP_TRIGGER_RE = Pattern.compile("@HttpTrigger\\s*\\(");
    private static final Pattern SB_QUEUE_RE = Pattern.compile("@ServiceBusQueueTrigger\\s*\\([^)]*queueName\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SB_TOPIC_RE = Pattern.compile("@ServiceBusTopicTrigger\\s*\\([^)]*topicName\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern EH_TRIGGER_RE = Pattern.compile("@EventHubTrigger\\s*\\([^)]*eventHubName\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TIMER_RE = Pattern.compile("@TimerTrigger\\s*\\([^)]*schedule\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern COSMOS_TRIGGER_RE = Pattern.compile("@CosmosDB(?:Trigger|Input|Output)\\s*\\(");
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");

    @Override
    public String getName() {
        return "azure_functions";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java", "csharp", "typescript", "javascript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("FunctionName") && !text.contains("@FunctionName") && !text.contains("@HttpTrigger")) {
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

        for (int i = 0; i < lines.length; i++) {
            Matcher fnMatch = FUNCTION_NAME_RE.matcher(lines[i]);
            if (!fnMatch.find()) continue;

            String funcName = fnMatch.group(1);
            String funcNodeId = "azure:func:" + funcName;
            String contextLines = String.join("\n",
                    Arrays.copyOfRange(lines, i, Math.min(i + 15, lines.length)));

            Map<String, Object> props = new LinkedHashMap<>();

            if (HTTP_TRIGGER_RE.matcher(contextLines).find()) {
                props.put("trigger_type", "http");
                nodes.add(funcNode(funcNodeId, funcName, className, i + 1, ctx,
                        List.of("@FunctionName", "@HttpTrigger"), props));

                String endpointId = funcNodeId + ":endpoint";
                CodeNode epNode = new CodeNode();
                epNode.setId(endpointId);
                epNode.setKind(NodeKind.ENDPOINT);
                epNode.setLabel("HTTP " + funcName);
                epNode.setFilePath(ctx.filePath());
                epNode.setLineStart(i + 1);
                epNode.getProperties().put("http_trigger", true);
                epNode.getProperties().put("function_name", funcName);
                nodes.add(epNode);

                addEdge(funcNodeId, endpointId, EdgeKind.EXPOSES,
                        funcName + " exposes HTTP endpoint", edges, epNode);
                continue;
            }

            Matcher sbq = SB_QUEUE_RE.matcher(contextLines);
            if (sbq.find()) {
                String queueName = sbq.group(1);
                props.put("trigger_type", "serviceBusQueue");
                props.put("queue_name", queueName);
                nodes.add(funcNode(funcNodeId, funcName, className, i + 1, ctx,
                        List.of("@FunctionName", "@ServiceBusQueueTrigger"), props));

                String queueNodeId = "azure:servicebus:queue:" + queueName;
                CodeNode qNode = new CodeNode(queueNodeId, NodeKind.QUEUE, "servicebus:" + queueName);
                qNode.getProperties().put("broker", "azure_servicebus");
                qNode.getProperties().put("queue", queueName);
                nodes.add(qNode);
                addEdge(queueNodeId, funcNodeId, EdgeKind.TRIGGERS,
                        "queue " + queueName + " triggers " + funcName, edges, null);
                continue;
            }

            Matcher sbt = SB_TOPIC_RE.matcher(contextLines);
            if (sbt.find()) {
                String topicName = sbt.group(1);
                props.put("trigger_type", "serviceBusTopic");
                props.put("topic_name", topicName);
                nodes.add(funcNode(funcNodeId, funcName, className, i + 1, ctx,
                        List.of("@FunctionName", "@ServiceBusTopicTrigger"), props));

                String topicNodeId = "azure:servicebus:topic:" + topicName;
                CodeNode tNode = new CodeNode(topicNodeId, NodeKind.TOPIC, "servicebus:" + topicName);
                tNode.getProperties().put("broker", "azure_servicebus");
                tNode.getProperties().put("topic", topicName);
                nodes.add(tNode);
                addEdge(topicNodeId, funcNodeId, EdgeKind.TRIGGERS,
                        "topic " + topicName + " triggers " + funcName, edges, null);
                continue;
            }

            Matcher ehm = EH_TRIGGER_RE.matcher(contextLines);
            if (ehm.find()) {
                String hubName = ehm.group(1);
                props.put("trigger_type", "eventHub");
                props.put("event_hub_name", hubName);
                nodes.add(funcNode(funcNodeId, funcName, className, i + 1, ctx,
                        List.of("@FunctionName", "@EventHubTrigger"), props));

                String hubNodeId = "azure:eventhub:" + hubName;
                CodeNode hNode = new CodeNode(hubNodeId, NodeKind.TOPIC, "eventhub:" + hubName);
                hNode.getProperties().put("broker", "azure_eventhub");
                hNode.getProperties().put("event_hub", hubName);
                nodes.add(hNode);
                addEdge(hubNodeId, funcNodeId, EdgeKind.TRIGGERS,
                        "event hub " + hubName + " triggers " + funcName, edges, null);
                continue;
            }

            Matcher tm = TIMER_RE.matcher(contextLines);
            if (tm.find()) {
                String schedule = tm.group(1);
                props.put("trigger_type", "timer");
                props.put("schedule", schedule);
                nodes.add(funcNode(funcNodeId, funcName, className, i + 1, ctx,
                        List.of("@FunctionName", "@TimerTrigger"), props));
                continue;
            }

            if (COSMOS_TRIGGER_RE.matcher(contextLines).find()) {
                props.put("trigger_type", "cosmosDB");
                nodes.add(funcNode(funcNodeId, funcName, className, i + 1, ctx,
                        List.of("@FunctionName", "@CosmosDBTrigger"), props));

                String resNodeId = "azure:cosmos:func:" + funcName;
                CodeNode rNode = new CodeNode(resNodeId, NodeKind.AZURE_RESOURCE, "cosmosdb:" + funcName);
                rNode.getProperties().put("cosmos_type", "trigger");
                rNode.getProperties().put("function_name", funcName);
                nodes.add(rNode);
                addEdge(resNodeId, funcNodeId, EdgeKind.TRIGGERS,
                        "CosmosDB triggers " + funcName, edges, null);
                continue;
            }

            props.put("trigger_type", "unknown");
            nodes.add(funcNode(funcNodeId, funcName, className, i + 1, ctx,
                    List.of("@FunctionName"), props));
        }

        return DetectorResult.of(nodes, edges);
    }

    private CodeNode funcNode(String id, String funcName, String className, int line,
                              DetectorContext ctx, List<String> annotations, Map<String, Object> props) {
        CodeNode node = new CodeNode();
        node.setId(id);
        node.setKind(NodeKind.AZURE_FUNCTION);
        node.setLabel(funcName);
        node.setFqn(className != null ? className + "." + funcName : funcName);
        node.setFilePath(ctx.filePath());
        node.setLineStart(line);
        node.setAnnotations(new ArrayList<>(annotations));
        node.setProperties(new LinkedHashMap<>(props));
        return node;
    }

    private void addEdge(String sourceId, String targetId, EdgeKind kind, String label,
                         List<CodeEdge> edges, CodeNode targetNode) {
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->" + kind.getValue() + "->" + targetId);
        edge.setKind(kind);
        edge.setSourceId(sourceId);
        if (targetNode != null) {
            edge.setTarget(targetNode);
        } else {
            edge.setTarget(new CodeNode(targetId, NodeKind.AZURE_FUNCTION, label));
        }
        edges.add(edge);
    }
}
