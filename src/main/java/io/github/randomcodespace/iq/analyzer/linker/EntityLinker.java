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
 * Links JPA entities to repositories that query them.
 * <p>
 * Scans for ENTITY and REPOSITORY nodes and creates QUERIES edges
 * from repositories to the entities they manage, matching by naming
 * convention (e.g. UserRepository -> User entity).
 */
@Component
public class EntityLinker implements Linker {

    private static final Logger log = LoggerFactory.getLogger(EntityLinker.class);

    private static final String[] REPO_SUFFIXES = {"Repository", "Repo", "Dao", "DAO"};

    @Override
    public LinkResult link(List<CodeNode> nodes, List<CodeEdge> edges) {
        List<CodeNode> entities = new ArrayList<>();
        List<CodeNode> repositories = new ArrayList<>();

        for (CodeNode node : nodes) {
            if (node.getKind() == NodeKind.ENTITY) {
                entities.add(node);
            } else if (node.getKind() == NodeKind.REPOSITORY) {
                repositories.add(node);
            }
        }

        if (entities.isEmpty() || repositories.isEmpty()) {
            return LinkResult.empty();
        }

        // Build entity lookup by simple name (lowercase)
        Map<String, CodeNode> entityByName = new HashMap<>();
        for (CodeNode entity : entities) {
            entityByName.put(entity.getLabel().toLowerCase(), entity);
            if (entity.getFqn() != null) {
                String simple = entity.getFqn();
                int dot = simple.lastIndexOf('.');
                if (dot >= 0) {
                    simple = simple.substring(dot + 1);
                }
                entityByName.put(simple.toLowerCase(), entity);
            }
        }

        // Check existing QUERIES edges to avoid duplicates
        Set<String> existingQueries = new HashSet<>();
        for (CodeEdge edge : edges) {
            if (edge.getKind() == EdgeKind.QUERIES && edge.getTarget() != null) {
                existingQueries.add(edge.getSourceId() + "->" + edge.getTarget().getId());
            }
        }

        List<CodeEdge> newEdges = new ArrayList<>();
        for (CodeNode repo : repositories) {
            String repoName = repo.getLabel();
            for (String suffix : REPO_SUFFIXES) {
                if (repoName.endsWith(suffix)) {
                    String entityName = repoName.substring(0, repoName.length() - suffix.length()).toLowerCase();
                    CodeNode entity = entityByName.get(entityName);
                    if (entity != null) {
                        String key = repo.getId() + "->" + entity.getId();
                        if (!existingQueries.contains(key)) {
                            var edge = new CodeEdge();
                            edge.setId("entity-link:" + repo.getId() + "->" + entity.getId());
                            edge.setKind(EdgeKind.QUERIES);
                            edge.setSourceId(repo.getId());
                            edge.setTarget(entity);
                            edge.setProperties(Map.of("inferred", true));
                            newEdges.add(edge);
                        }
                    }
                    break; // Only try first matching suffix
                }
            }
        }

        if (!newEdges.isEmpty()) {
            log.debug("EntityLinker created {} edges", newEdges.size());
        }
        return LinkResult.ofEdges(newEdges);
    }
}
