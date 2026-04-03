package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Links messaging producers to consumers via shared topic/queue/event names.
 * <p>
 * Scans for TOPIC/QUEUE/EVENT/MESSAGE_QUEUE nodes and matches producer edges
 * (PRODUCES, SENDS_TO, PUBLISHES) with consumer edges (CONSUMES, RECEIVES_FROM,
 * LISTENS) on the same topic label to create direct producer-to-consumer
 * CALLS edges.
 */
@Component
public class TopicLinker implements Linker {

    private static final Logger log = LoggerFactory.getLogger(TopicLinker.class);

    @Override
    public LinkResult link(List<CodeNode> nodes, List<CodeEdge> edges) {
        // Collect topic/queue/event/message_queue nodes by label
        Map<String, List<String>> topicIdsByLabel = new TreeMap<>();
        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.TOPIC || node.getKind() == NodeKind.QUEUE
                    || node.getKind() == NodeKind.EVENT || node.getKind() == NodeKind.MESSAGE_QUEUE) {
                topicIdsByLabel
                        .computeIfAbsent(node.getLabel(), k -> new ArrayList<>())
                        .add(node.getId());
            }
        }

        if (topicIdsByLabel.isEmpty()) {
            return LinkResult.empty();
        }

        // Map topic_id -> producer node ids
        Map<String, List<String>> producersByTopic = new TreeMap<>();
        // Map topic_id -> consumer node ids
        Map<String, List<String>> consumersByTopic = new TreeMap<>();

        for (CodeEdge edge : edges) {
            if (edge.getTarget() == null) continue;
            EdgeKind kind = edge.getKind();
            if (kind == EdgeKind.PRODUCES || kind == EdgeKind.SENDS_TO || kind == EdgeKind.PUBLISHES) {
                producersByTopic
                        .computeIfAbsent(edge.getTarget().getId(), k -> new ArrayList<>())
                        .add(edge.getSourceId());
            } else if (kind == EdgeKind.CONSUMES || kind == EdgeKind.RECEIVES_FROM || kind == EdgeKind.LISTENS) {
                consumersByTopic
                        .computeIfAbsent(edge.getTarget().getId(), k -> new ArrayList<>())
                        .add(edge.getSourceId());
            }
        }

        // Build node lookup for creating target references
        Map<String, CodeNode> nodeById = new HashMap<>();
        for (CodeNode node : nodes) {
            nodeById.put(node.getId(), node);
        }

        // Create CALLS edges from producers to consumers on the same topic
        List<CodeEdge> newEdges = new ArrayList<>();
        for (var entry : topicIdsByLabel.entrySet()) {
            String label = entry.getKey();
            List<String> topicIds = entry.getValue();

            Set<String> producers = new TreeSet<>();
            Set<String> consumers = new TreeSet<>();
            for (String tid : topicIds) {
                List<String> prods = producersByTopic.get(tid);
                if (prods != null) producers.addAll(prods);
                List<String> cons = consumersByTopic.get(tid);
                if (cons != null) consumers.addAll(cons);
            }

            for (String prod : producers) {
                for (String cons : consumers) {
                    if (!prod.equals(cons)) {
                        CodeNode targetNode = nodeById.get(cons);
                        if (targetNode != null) {
                            var edge = new CodeEdge();
                            edge.setId("topic-link:" + prod + "->" + cons);
                            edge.setKind(EdgeKind.CALLS);
                            edge.setSourceId(prod);
                            edge.setTarget(targetNode);
                            edge.setProperties(Map.of(
                                    "inferred", true,
                                    "topic", label
                            ));
                            newEdges.add(edge);
                        }
                    }
                }
            }
        }

        if (!newEdges.isEmpty()) {
            log.debug("TopicLinker created {} edges", newEdges.size());
        }
        return LinkResult.ofEdges(newEdges);
    }
}
