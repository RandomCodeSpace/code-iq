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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Links classes to their owning modules via CONTAINS edges.
 * <p>
 * Groups nodes by their {@code module} field and creates MODULE nodes
 * with CONTAINS edges pointing to each member node.
 */
@Component
public class ModuleContainmentLinker implements Linker {

    private static final Logger log = LoggerFactory.getLogger(ModuleContainmentLinker.class);

    @Override
    public LinkResult link(List<CodeNode> nodes, List<CodeEdge> edges) {
        // Collect existing module node IDs
        Set<String> existingModuleIds = new HashSet<>();
        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.MODULE) {
                existingModuleIds.add(node.getId());
            }
        }

        // Group non-MODULE nodes by module name
        Map<String, List<CodeNode>> nodesByModule = new HashMap<>();
        for (CodeNode node : nodes) {
            if (node.getModule() != null && !node.getModule().isEmpty()
                    && node.getKind() != NodeKind.MODULE) {
                nodesByModule.computeIfAbsent(node.getModule(), k -> new ArrayList<>()).add(node);
            }
        }

        if (nodesByModule.isEmpty()) {
            return LinkResult.empty();
        }

        // Check existing CONTAINS edges to avoid duplicates
        Set<String> existingContains = new HashSet<>();
        for (CodeEdge edge : edges) {
            if (edge.getKind() == EdgeKind.CONTAINS && edge.getTarget() != null) {
                existingContains.add(edge.getSourceId() + "->" + edge.getTarget().getId());
            }
        }

        List<CodeNode> newNodes = new ArrayList<>();
        List<CodeEdge> newEdges = new ArrayList<>();

        for (var entry : nodesByModule.entrySet()) {
            String moduleName = entry.getKey();
            List<CodeNode> members = entry.getValue();
            String moduleId = "module:" + moduleName;

            // Create module node if it doesn't exist
            if (!existingModuleIds.contains(moduleId)) {
                var moduleNode = new CodeNode(moduleId, NodeKind.MODULE, moduleName);
                moduleNode.setFqn(moduleName);
                moduleNode.setModule(moduleName);
                newNodes.add(moduleNode);
                existingModuleIds.add(moduleId);
            }

            for (CodeNode member : members) {
                String key = moduleId + "->" + member.getId();
                if (!existingContains.contains(key)) {
                    var edge = new CodeEdge();
                    edge.setId("module-link:" + moduleId + "->" + member.getId());
                    edge.setKind(EdgeKind.CONTAINS);
                    edge.setSourceId(moduleId);
                    edge.setTarget(member);
                    edge.setProperties(Map.of("inferred", true));
                    newEdges.add(edge);
                    existingContains.add(key);
                }
            }
        }

        if (!newEdges.isEmpty()) {
            log.debug("ModuleContainmentLinker created {} nodes, {} edges",
                    newNodes.size(), newEdges.size());
        }
        return new LinkResult(newNodes, newEdges);
    }
}
