package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import org.antlr.v4.runtime.tree.ParseTree;
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

@Component
public class PrismaORMDetector extends AbstractAntlrDetector {

    private static final Pattern PRISMA_OP_RE = Pattern.compile(
            "prisma\\.(\\w+)\\.(findMany|findFirst|findUnique|create|update|delete|upsert|count|aggregate|groupBy)\\s*\\("
    );

    private static final Pattern PRISMA_CLIENT_RE = Pattern.compile(
            "new\\s+PrismaClient\\s*\\(|PrismaClient\\s*\\("
    );

    private static final Pattern PRISMA_IMPORT_RE = Pattern.compile(
            "(?:import|require)\\s*\\(?[^)]*['\"]@prisma/client['\"]"
    );

    private static final Pattern PRISMA_TRANSACTION_RE = Pattern.compile(
            "prisma\\.\\$transaction\\s*\\("
    );

    @Override
    public String getName() {
        return "prisma_orm";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        return AntlrParserFactory.parse(ctx.language(), ctx.content());
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        boolean hasTransaction = PRISMA_TRANSACTION_RE.matcher(text).find();

        // PrismaClient instantiation -> DATABASE_CONNECTION
        Matcher matcher = PRISMA_CLIENT_RE.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String nodeId = "prisma:" + filePath + ":client:" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.DATABASE_CONNECTION);
            node.setLabel("PrismaClient");
            node.setFqn(filePath + "::PrismaClient");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("framework", "prisma");
            if (hasTransaction) {
                node.getProperties().put("transaction", true);
            }
            nodes.add(node);
        }

        // @prisma/client imports -> IMPORTS edge
        matcher = PRISMA_IMPORT_RE.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            CodeEdge edge = new CodeEdge();
            edge.setId(filePath + "->imports->@prisma/client:" + line);
            edge.setKind(EdgeKind.IMPORTS);
            edge.setSourceId(filePath);
            edge.getProperties().put("line", line);
            edges.add(edge);
        }

        // prisma model operations -> ENTITY nodes + QUERIES edges
        Map<String, String> seenModels = new LinkedHashMap<>();
        matcher = PRISMA_OP_RE.matcher(text);
        while (matcher.find()) {
            String modelName = matcher.group(1);
            String operation = matcher.group(2);
            int line = findLineNumber(text, matcher.start());

            if (!seenModels.containsKey(modelName)) {
                String modelId = "prisma:" + filePath + ":model:" + modelName;
                seenModels.put(modelName, modelId);
                CodeNode node = new CodeNode();
                node.setId(modelId);
                node.setKind(NodeKind.ENTITY);
                node.setLabel(modelName);
                node.setFqn(filePath + "::" + modelName);
                node.setModule(moduleName);
                node.setFilePath(filePath);
                node.setLineStart(line);
                node.getProperties().put("framework", "prisma");
                nodes.add(node);
            }

            CodeEdge edge = new CodeEdge();
            edge.setId(filePath + "->queries->" + seenModels.get(modelName) + ":" + line);
            edge.setKind(EdgeKind.QUERIES);
            edge.setSourceId(filePath);
            edge.getProperties().put("operation", operation);
            edge.getProperties().put("line", line);
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
