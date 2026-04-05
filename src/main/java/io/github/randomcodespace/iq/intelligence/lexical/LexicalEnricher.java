package io.github.randomcodespace.iq.intelligence.lexical;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Enriches {@link CodeNode} instances with lexical metadata before Neo4j bulk-load.
 *
 * <p>Populates two properties in the node's {@code properties} map:
 * <ul>
 *   <li>{@value #KEY_LEX_COMMENT} — extracted doc comment / docstring for the symbol.</li>
 *   <li>{@value #KEY_LEX_CONFIG_KEYS} — config key path for config-typed nodes.</li>
 * </ul>
 *
 * <p>These are stored as {@code prop_lex_comment} and {@code prop_lex_config_keys} in Neo4j
 * (via the {@code prop_*} round-trip convention) and indexed by {@code lexical_index}.
 */
@Component
public class LexicalEnricher {

    private static final Logger log = LoggerFactory.getLogger(LexicalEnricher.class);

    /** Property key for doc comment text stored in CodeNode.properties. */
    public static final String KEY_LEX_COMMENT = "lex_comment";

    /** Property key for config key path stored in CodeNode.properties. */
    public static final String KEY_LEX_CONFIG_KEYS = "lex_config_keys";

    /**
     * Enrich all nodes with lexical metadata extracted from source files.
     *
     * <p>Nodes are grouped by {@code filePath} so that each source file is read at most once,
     * avoiding redundant I/O when many nodes originate from the same file.
     *
     * @param nodes    All enriched nodes (post-linker, post-classifier).
     * @param rootPath Absolute root path of the analysed repository.
     */
    public void enrich(List<CodeNode> nodes, Path rootPath) {
        int commented = 0;
        int configKeyed = 0;

        // Group doc-comment candidates by filePath (TreeMap for deterministic order).
        // Nodes without a filePath or lineStart, or non-candidate kinds, are handled inline.
        Map<String, List<CodeNode>> nodesByFile = new TreeMap<>();
        for (CodeNode node : nodes) {
            if (enrichConfigKeys(node)) configKeyed++;

            if (node.getFilePath() != null && node.getLineStart() != null
                    && isDocCommentCandidate(node.getKind())) {
                nodesByFile.computeIfAbsent(node.getFilePath(), k -> new ArrayList<>()).add(node);
            }
        }

        // Process each file group: read once, enrich all nodes from that file.
        for (var entry : nodesByFile.entrySet()) {
            String filePath = entry.getKey();
            List<CodeNode> fileNodes = entry.getValue();

            Path absFile = rootPath.resolve(filePath).normalize();
            if (!absFile.startsWith(rootPath)) continue; // path traversal guard

            List<String> lines;
            try {
                lines = Files.readAllLines(absFile, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("Could not read file for doc comment extraction: {}", absFile, e);
                continue;
            }

            String language = SnippetStore.inferLanguage(filePath);
            for (CodeNode node : fileNodes) {
                if (enrichDocCommentFromLines(node, lines, language)) commented++;
            }
            // lines eligible for GC after this iteration
        }

        log.info("Lexical enrichment: {} doc comments, {} config key entries indexed",
                commented, configKeyed);
    }

    /**
     * Extract and store the doc comment for the given node using pre-read file lines.
     *
     * @return true if a comment was found and stored.
     */
    private boolean enrichDocCommentFromLines(CodeNode node, List<String> lines, String language) {
        String comment = DocCommentExtractor.extract(lines, language, node.getLineStart());
        if (comment != null && !comment.isBlank()) {
            node.getProperties().put(KEY_LEX_COMMENT, comment);
            return true;
        }
        return false;
    }

    /**
     * For config-typed nodes, store the label/fqn as the config key path.
     *
     * @return true if the node was a config node and the key was stored.
     */
    private static boolean enrichConfigKeys(CodeNode node) {
        if (node.getKind() != NodeKind.CONFIG_KEY
                && node.getKind() != NodeKind.CONFIG_FILE
                && node.getKind() != NodeKind.CONFIG_DEFINITION) {
            return false;
        }
        String keyPath = node.getFqn() != null ? node.getFqn() : node.getLabel();
        if (keyPath != null && !keyPath.isBlank()) {
            node.getProperties().put(KEY_LEX_CONFIG_KEYS, keyPath);
            return true;
        }
        return false;
    }

    /** True for node kinds that typically carry doc comments. */
    private static boolean isDocCommentCandidate(NodeKind kind) {
        return switch (kind) {
            case CLASS, ABSTRACT_CLASS, INTERFACE, ENUM, ANNOTATION_TYPE,
                 METHOD, ENDPOINT, ENTITY, SERVICE, REPOSITORY,
                 COMPONENT, GUARD, MIDDLEWARE -> true;
            default -> false;
        };
    }
}
