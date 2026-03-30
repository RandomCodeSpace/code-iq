package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "typescript.graphql_resolvers",
    category = "endpoints",
    description = "Detects TypeScript/JS GraphQL resolvers (Apollo, type-graphql, Nexus)",
    parser = ParserType.REGEX,
    languages = {"typescript", "javascript"},
    nodeKinds = {NodeKind.CLASS, NodeKind.ENDPOINT},
    properties = {"framework", "protocol"}
)
@Component
public class GraphQLResolverDetector extends AbstractAntlrDetector {

    private static final Pattern NESTJS_RESOLVER = Pattern.compile(
            "@Resolver\\(\\s*(?:of\\s*=>\\s*)?(\\w+)?\\s*\\)\\s*\\n\\s*(?:export\\s+)?class\\s+(\\w+)"
    );

    private static final Pattern NESTJS_QUERY = Pattern.compile(
            "@(Query|Mutation|Subscription)\\(.*?\\)\\s*\\n\\s*(?:async\\s+)?(\\w+)"
    );

    private static final Pattern TYPEDEF_PATTERN = Pattern.compile(
            "type\\s+(Query|Mutation|Subscription)\\s*\\{([^}]+)\\}"
    );

    private static final Pattern RESOLVER_FIELD_PATTERN = Pattern.compile(
            "(\\w+)\\s*(?:\\([^)]*\\))?\\s*:"
    );

    @Override
    public String getName() {
        return "typescript.graphql_resolvers";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        // NestJS-style resolvers
        Matcher matcher = NESTJS_RESOLVER.matcher(text);
        while (matcher.find()) {
            String entityType = matcher.group(1);
            String className = matcher.group(2);
            int line = findLineNumber(text, matcher.start());

            String classId = "class:" + filePath + "::" + className;
            CodeNode node = new CodeNode();
            node.setId(classId);
            node.setKind(NodeKind.CLASS);
            node.setLabel(className);
            node.setFqn(filePath + "::" + className);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getAnnotations().add("@Resolver");
            node.getProperties().put("framework", "nestjs-graphql");
            node.getProperties().put("entity_type", entityType);
            nodes.add(node);
        }

        // NestJS @Query/@Mutation/@Subscription
        matcher = NESTJS_QUERY.matcher(text);
        while (matcher.find()) {
            String opType = matcher.group(1);
            String funcName = matcher.group(2);
            int line = findLineNumber(text, matcher.start());

            String nodeId = "endpoint:" + (moduleName != null ? moduleName : "") + ":graphql:" + opType + ":" + funcName;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel("GraphQL " + opType + ": " + funcName);
            node.setFqn(filePath + "::" + funcName);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("protocol", "GraphQL");
            node.getProperties().put("operation_type", opType.toLowerCase());
            node.getProperties().put("field_name", funcName);
            nodes.add(node);
        }

        // Schema-defined resolvers
        matcher = TYPEDEF_PATTERN.matcher(text);
        while (matcher.find()) {
            String opType = matcher.group(1);
            String fieldsBlock = matcher.group(2);
            int baseLine = findLineNumber(text, matcher.start());

            Matcher fieldMatcher = RESOLVER_FIELD_PATTERN.matcher(fieldsBlock);
            while (fieldMatcher.find()) {
                String fieldName = fieldMatcher.group(1);
                String nodeId = "endpoint:" + (moduleName != null ? moduleName : "") + ":graphql:" + opType + ":" + fieldName;

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.ENDPOINT);
                node.setLabel("GraphQL " + opType + ": " + fieldName);
                node.setModule(moduleName);
                node.setFilePath(filePath);
                node.setLineStart(baseLine);
                node.getProperties().put("protocol", "GraphQL");
                node.getProperties().put("operation_type", opType.toLowerCase());
                node.getProperties().put("field_name", fieldName);
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, List.of());
    }
}
