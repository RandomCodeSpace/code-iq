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
    private static final String PROP_BACKEND = "backend";
    private static final String PROP_FRONTEND = "frontend";
    private static final String PROP_UNKNOWN = "unknown";


    private static final Set<NodeKind> FRONTEND_NODE_KINDS = Set.of(
            NodeKind.COMPONENT, NodeKind.HOOK
    );

    private static final Set<NodeKind> BACKEND_NODE_KINDS = Set.of(
            NodeKind.GUARD, NodeKind.MIDDLEWARE, NodeKind.ENDPOINT,
            NodeKind.REPOSITORY, NodeKind.DATABASE_CONNECTION, NodeKind.QUERY,
            NodeKind.ENTITY, NodeKind.MIGRATION, NodeKind.SERVICE,
            NodeKind.TOPIC, NodeKind.QUEUE, NodeKind.EVENT,
            NodeKind.MESSAGE_QUEUE, NodeKind.RMI_INTERFACE,
            NodeKind.WEBSOCKET_ENDPOINT
    );

    private static final Set<NodeKind> INFRA_NODE_KINDS = Set.of(
            NodeKind.INFRA_RESOURCE, NodeKind.AZURE_RESOURCE, NodeKind.AZURE_FUNCTION,
            NodeKind.SQL_ENTITY
    );

    private static final Set<String> INFRA_LANGUAGES = Set.of(
            "terraform", "bicep", "dockerfile"
    );

    private static final Set<NodeKind> SHARED_NODE_KINDS = Set.of(
            NodeKind.CONFIG_FILE, NodeKind.CONFIG_KEY, NodeKind.CONFIG_DEFINITION,
            NodeKind.PROTOCOL_MESSAGE
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
            "express", "nestjs", "flask", "django", "fastapi", "spring",
            "spring_boot", "spring_mvc", "spring_data", "spring_security",
            "gin", "echo", "fiber", "actix", "rocket", "axum",
            "asp.net", "koa", "hapi", "fastify"
    );

    // -- Fallback path heuristics (applied only to PROP_UNKNOWN nodes) --

    private static final Pattern BACKEND_PACKAGE_PATH_RE = Pattern.compile(
            "(?:^|/|\\.)(?:controller|controllers|api|web|rest|resource|resources|"
            + "model|models|entity|entities|domain|dto|dtos|"
            + "repository|repositories|dao|persistence|"
            + "service|services|business|logic|"
            + "routes|handlers|handler|middleware|middlewares|schemas)(?:/|\\.|$)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SHARED_PACKAGE_PATH_RE = Pattern.compile(
            "(?:^|/|\\.)(?:config|configuration|util|utils|helper|helpers|common|shared|"
            + "exception|exceptions|constants|enums)(?:/|\\.|$)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FRONTEND_PACKAGE_PATH_RE = Pattern.compile(
            "(?:^|/|\\.)(?:components|views|pages|ui|widgets|screens|templates|layouts)(?:/|\\.|$)",
            Pattern.CASE_INSENSITIVE
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
        if (FRONTEND_NODE_KINDS.contains(node.getKind())) return PROP_FRONTEND;
        if (BACKEND_NODE_KINDS.contains(node.getKind())) return PROP_BACKEND;
        if (INFRA_NODE_KINDS.contains(node.getKind())) return "infra";

        // 2. Language rules
        Object lang = node.getProperties().get("language");
        if (lang instanceof String langStr && INFRA_LANGUAGES.contains(langStr)) {
            return "infra";
        }

        // 3. File path rules
        String filePath = node.getFilePath() != null ? node.getFilePath() : "";
        if (FRONTEND_EXT_RE.matcher(filePath).find()) return PROP_FRONTEND;
        if (FRONTEND_PATH_RE.matcher(filePath).find()) return PROP_FRONTEND;
        if (BACKEND_PATH_RE.matcher(filePath).find()) return PROP_BACKEND;

        // 4. Framework rules
        Object fw = node.getProperties().get("framework");
        if (fw instanceof String fwStr) {
            if (FRONTEND_FRAMEWORKS.contains(fwStr)) return PROP_FRONTEND;
            if (BACKEND_FRAMEWORKS.contains(fwStr)) return PROP_BACKEND;
        }

        // 5. Shared node kinds
        if (SHARED_NODE_KINDS.contains(node.getKind())) return "shared";

        // 6. Fallback: package/path heuristics for remaining PROP_UNKNOWN nodes
        return classifyByPathFallback(node);
    }

    /**
     * Fallback classification using package names and file paths.
     * Only called for nodes not matched by any earlier rule.
     */
    private String classifyByPathFallback(CodeNode node) {
        String filePath = node.getFilePath() != null ? node.getFilePath() : "";
        String nodeId = node.getId() != null ? node.getId() : "";

        // Combine file path and node ID for matching (ID often contains package info)
        String combined = filePath + "|" + nodeId;

        // Check frontend path patterns first (components, views, pages, etc.)
        if (FRONTEND_PACKAGE_PATH_RE.matcher(combined).find()) return PROP_FRONTEND;

        // Check backend path patterns (controller, model, repository, service, etc.)
        if (BACKEND_PACKAGE_PATH_RE.matcher(combined).find()) return PROP_BACKEND;

        // Check shared path patterns (config, util, common, etc.)
        if (SHARED_PACKAGE_PATH_RE.matcher(combined).find()) return "shared";

        // Java-specific: check for standard Java/Spring package conventions in file path
        if (filePath.endsWith(".java") || filePath.endsWith(".kt") || filePath.endsWith(".scala")) {
            return classifyJavaByPath(filePath);
        }

        return PROP_UNKNOWN;
    }

    /**
     * Java-specific path classification: files under src/main/java in typical
     * Spring/Java project structures are almost always backend code.
     */
    private String classifyJavaByPath(String filePath) {
        // Files in src/main/java or src/main/kotlin are virtually always backend
        if (filePath.contains("src/main/java/") || filePath.contains("src/main/kotlin/")) {
            return PROP_BACKEND;
        }
        return PROP_UNKNOWN;
    }
}
