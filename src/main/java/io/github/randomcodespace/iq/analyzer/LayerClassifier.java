package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic layer classifier for graph nodes.
 * Assigns a {@code layer} property to every node: frontend, backend,
 * infra, shared, or unknown.
 * <p>
 * Rules are evaluated in priority order; first match wins.
 */
@Service
public class LayerClassifier {

    private static final Set<NodeKind> FRONTEND_NODE_KINDS = Set.of(
            NodeKind.COMPONENT, NodeKind.HOOK
    );

    private static final Set<NodeKind> BACKEND_NODE_KINDS = Set.of(
            NodeKind.GUARD, NodeKind.MIDDLEWARE, NodeKind.ENDPOINT,
            NodeKind.REPOSITORY, NodeKind.DATABASE_CONNECTION, NodeKind.QUERY
    );

    private static final Set<NodeKind> INFRA_NODE_KINDS = Set.of(
            NodeKind.INFRA_RESOURCE, NodeKind.AZURE_RESOURCE, NodeKind.AZURE_FUNCTION
    );

    private static final Set<String> INFRA_LANGUAGES = Set.of(
            "terraform", "bicep", "dockerfile"
    );

    private static final Set<NodeKind> SHARED_NODE_KINDS = Set.of(
            NodeKind.CONFIG_FILE, NodeKind.CONFIG_KEY, NodeKind.CONFIG_DEFINITION
    );

    private static final Pattern FRONTEND_PATH_RE = Pattern.compile(
            "(?:^|/)(?:src/)?(?:components|pages|views|app/ui|public)/"
    );

    private static final Pattern BACKEND_PATH_RE = Pattern.compile(
            "(?:^|/)(?:src/)?(?:server|api|controllers|services|routes|handlers)/"
    );

    private static final Pattern FRONTEND_EXT_RE = Pattern.compile(
            "\\.(?:tsx|jsx)$"
    );

    private static final Set<String> FRONTEND_FRAMEWORKS = Set.of(
            "react", "vue", "angular", "svelte", "nextjs"
    );

    private static final Set<String> BACKEND_FRAMEWORKS = Set.of(
            "express", "nestjs", "flask", "django", "fastapi", "spring"
    );

    /**
     * Classify all nodes in the list, setting the {@code layer} property on each.
     */
    public void classify(List<CodeNode> nodes) {
        for (CodeNode node : nodes) {
            node.setLayer(classifyOne(node));
        }
    }

    /**
     * Classify a single node.
     */
    String classifyOne(CodeNode node) {
        // 1. Node kind rules
        if (FRONTEND_NODE_KINDS.contains(node.getKind())) return "frontend";
        if (BACKEND_NODE_KINDS.contains(node.getKind())) return "backend";
        if (INFRA_NODE_KINDS.contains(node.getKind())) return "infra";

        // 2. Language rules
        Object lang = node.getProperties().get("language");
        if (lang instanceof String langStr && INFRA_LANGUAGES.contains(langStr)) {
            return "infra";
        }

        // 3. File path rules
        String filePath = node.getFilePath() != null ? node.getFilePath() : "";
        if (FRONTEND_EXT_RE.matcher(filePath).find()) return "frontend";
        if (FRONTEND_PATH_RE.matcher(filePath).find()) return "frontend";
        if (BACKEND_PATH_RE.matcher(filePath).find()) return "backend";

        // 4. Framework rules
        Object fw = node.getProperties().get("framework");
        if (fw instanceof String fwStr) {
            if (FRONTEND_FRAMEWORKS.contains(fwStr)) return "frontend";
            if (BACKEND_FRAMEWORKS.contains(fwStr)) return "backend";
        }

        // 5. Shared node kinds
        if (SHARED_NODE_KINDS.contains(node.getKind())) return "shared";

        return "unknown";
    }
}
