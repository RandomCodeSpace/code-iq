package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.python.Python3ParserBaseListener;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "python.sqlalchemy_models",
    category = "entities",
    description = "Detects SQLAlchemy ORM models and table mappings",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.ENTITY, NodeKind.DATABASE_CONNECTION},
    edgeKinds = {EdgeKind.MAPS_TO, EdgeKind.CONNECTS_TO},
    properties = {"columns", "framework", "table_name"}
)
@Component
public class SQLAlchemyModelDetector extends AbstractPythonDbDetector {

    // --- Regex patterns ---
    private static final Pattern MODEL_PATTERN = Pattern.compile(
            "class\\s+(\\w+)\\(([^)]*(?:Base|Model|DeclarativeBase)[^)]*)\\):"
    );
    private static final Pattern TABLE_NAME = Pattern.compile(
            "__tablename__\\s*=\\s*['\"]((\\w+))['\"]"
    );
    private static final Pattern COLUMN_PATTERN = Pattern.compile(
            "(\\w+)\\s*(?::\\s*Mapped\\[.*?\\])?\\s*=\\s*(?:Column|mapped_column)\\("
    );
    private static final Pattern RELATIONSHIP_PATTERN = Pattern.compile(
            "(\\w+)\\s*(?::\\s*Mapped\\[.*?\\])?\\s*=\\s*relationship\\(\\s*['\"]((\\w+))['\"]"
    );
    @Override
    public String getName() {
        return "python.sqlalchemy_models";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterClassdef(Python3Parser.ClassdefContext classCtx) {
                if (classCtx.name() == null) return;
                String className = classCtx.name().getText();
                String bases = getBaseClassesText(classCtx);
                if (bases == null) return;
                if (!bases.contains("Base") && !bases.contains("Model") && !bases.contains("DeclarativeBase")) return;

                int line = lineOf(classCtx);
                String classBody = extractClassBody(text, classCtx);

                String tableName = extractTableName(classBody, className);
                List<String> columns = extractColumns(classBody);

                String nodeId = "entity:" + (moduleName != null ? moduleName : "") + ":" + className;
                nodes.add(createEntityNode(nodeId, className, filePath, moduleName, line, tableName, columns));
                addDbEdge(nodeId, ctx.registry(), nodes, edges);

                addRelationshipEdges(classBody, nodeId, moduleName, edges);
            }
        }, tree);

        return DetectorResult.of(nodes, edges);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        Matcher modelMatcher = MODEL_PATTERN.matcher(text);
        while (modelMatcher.find()) {
            String className = modelMatcher.group(1);
            int line = findLineNumber(text, modelMatcher.start());

            int classStart = modelMatcher.start();
            Matcher nextClassMatcher = NEXT_CLASS_RE.matcher(text.substring(modelMatcher.end()));
            String classBody;
            if (nextClassMatcher.find()) {
                classBody = text.substring(classStart, modelMatcher.end() + nextClassMatcher.start());
            } else {
                classBody = text.substring(classStart);
            }

            String tableName = extractTableName(classBody, className);
            List<String> columns = extractColumns(classBody);

            String nodeId = "entity:" + (moduleName != null ? moduleName : "") + ":" + className;
            nodes.add(createEntityNode(nodeId, className, filePath, moduleName, line, tableName, columns));
            addDbEdge(nodeId, ctx.registry(), nodes, edges);

            addRelationshipEdges(classBody, nodeId, moduleName, edges);
        }

        return DetectorResult.of(nodes, edges);
    }

    // --- Shared helpers ---

    private static String extractTableName(String classBody, String className) {
        Matcher tableMatch = TABLE_NAME.matcher(classBody);
        return tableMatch.find() ? tableMatch.group(1) : className.toLowerCase() + "s";
    }

    private static List<String> extractColumns(String classBody) {
        List<String> columns = new ArrayList<>();
        Matcher colMatcher = COLUMN_PATTERN.matcher(classBody);
        while (colMatcher.find()) {
            columns.add(colMatcher.group(1));
        }
        return columns;
    }

    private static CodeNode createEntityNode(String nodeId, String className, String filePath,
            String moduleName, int line, String tableName, List<String> columns) {
        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(NodeKind.ENTITY);
        node.setLabel(className);
        node.setFqn(filePath + "::" + className);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("table_name", tableName);
        node.getProperties().put("columns", columns);
        node.getProperties().put("framework", "sqlalchemy");
        return node;
    }

    private static void addRelationshipEdges(String classBody, String nodeId, String moduleName,
            List<CodeEdge> edges) {
        Matcher relMatcher = RELATIONSHIP_PATTERN.matcher(classBody);
        while (relMatcher.find()) {
            String targetClass = relMatcher.group(2);
            String targetId = "entity:" + (moduleName != null ? moduleName : "") + ":" + targetClass;
            CodeEdge edge = new CodeEdge();
            edge.setId(nodeId + "->maps_to->" + targetId);
            edge.setKind(EdgeKind.MAPS_TO);
            edge.setSourceId(nodeId);
            edge.setTarget(new CodeNode("*:" + targetClass, NodeKind.ENTITY, targetClass));
            edges.add(edge);
        }
    }

}
