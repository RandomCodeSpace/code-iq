package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Links GUARD and MIDDLEWARE nodes to ENDPOINT nodes via PROTECTS edges.
 * <p>
 * Uses file-path proximity as the matching heuristic: a guard or middleware
 * in the same file as an endpoint is assumed to protect that endpoint.
 * This correctly handles class-level Spring Security annotations
 * (@PreAuthorize, @Secured on a class) which appear in the same file as
 * the endpoint methods they protect.
 */
@Component
public class GuardLinker implements Linker {

    private static final Logger log = LoggerFactory.getLogger(GuardLinker.class);

    @Override
    public LinkResult link(List<CodeNode> nodes, List<CodeEdge> edges) {
        // Group guards/middlewares and endpoints by filePath
        Map<String, List<CodeNode>> guardsByFile = new TreeMap<>();
        Map<String, List<CodeNode>> endpointsByFile = new TreeMap<>();

        for (CodeNode node : nodes) {
            String fp = node.getFilePath();
            if (fp == null || fp.isBlank()) continue;

            if (node.getKind() == NodeKind.GUARD || node.getKind() == NodeKind.MIDDLEWARE) {
                guardsByFile.computeIfAbsent(fp, k -> new ArrayList<>()).add(node);
            } else if (node.getKind() == NodeKind.ENDPOINT) {
                endpointsByFile.computeIfAbsent(fp, k -> new ArrayList<>()).add(node);
            }
        }

        if (guardsByFile.isEmpty() || endpointsByFile.isEmpty()) {
            return LinkResult.empty();
        }

        // Collect existing PROTECTS edges to avoid duplicates
        Set<String> existingProtects = new HashSet<>();
        for (CodeEdge edge : edges) {
            if (edge.getKind() == EdgeKind.PROTECTS && edge.getTarget() != null) {
                existingProtects.add(edge.getSourceId() + "->" + edge.getTarget().getId());
            }
        }

        List<CodeEdge> newEdges = new ArrayList<>();

        // Same-file matching: each guard protects all endpoints in the same file
        for (String filePath : new TreeSet<>(guardsByFile.keySet())) {
            List<CodeNode> fileEndpoints = endpointsByFile.get(filePath);
            if (fileEndpoints == null || fileEndpoints.isEmpty()) continue;

            List<CodeNode> fileGuards = guardsByFile.get(filePath);
            for (CodeNode guard : fileGuards) {
                for (CodeNode endpoint : fileEndpoints) {
                    String key = guard.getId() + "->" + endpoint.getId();
                    if (!existingProtects.contains(key)) {
                        var edge = new CodeEdge();
                        edge.setId("guard-link:" + guard.getId() + "->" + endpoint.getId());
                        edge.setKind(EdgeKind.PROTECTS);
                        edge.setSourceId(guard.getId());
                        edge.setTarget(endpoint);
                        edge.setProperties(Map.of("inferred", true));
                        newEdges.add(edge);
                        existingProtects.add(key);
                    }
                }
            }
        }

        if (!newEdges.isEmpty()) {
            log.debug("GuardLinker created {} PROTECTS edges", newEdges.size());
        }
        return LinkResult.ofEdges(newEdges);
    }
}
