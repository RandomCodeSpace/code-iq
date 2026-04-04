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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "python.django_models",
    category = "entities",
    description = "Detects Django ORM models and managers",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.ENTITY, NodeKind.REPOSITORY, NodeKind.DATABASE_CONNECTION},
    edgeKinds = {EdgeKind.DEPENDS_ON, EdgeKind.QUERIES, EdgeKind.CONNECTS_TO},
    properties = {"framework", "table_name"}
)
@Component
public class DjangoModelDetector extends AbstractPythonDbDetector {

    // --- Regex patterns (used in both AST body extraction and regex fallback) ---
    private static final Pattern DJANGO_MODEL_RE = Pattern.compile(
            "^class\\s+(\\w+)\\s*\\(\\s*[\\w.]*Model\\s*\\)", Pattern.MULTILINE
    );
    private static final Pattern FK_RE = Pattern.compile(
            "(\\w+)\\s*=\\s*models\\.(?:ForeignKey|OneToOneField)\\s*\\(\\s*[\"']?(\\w+)",
            Pattern.MULTILINE
    );
    private static final Pattern M2M_RE = Pattern.compile(
            "(\\w+)\\s*=\\s*models\\.ManyToManyField\\s*\\(\\s*[\"']?(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern FIELD_RE = Pattern.compile(
            "(\\w+)\\s*=\\s*models\\.(\\w+Field)\\s*\\(", Pattern.MULTILINE
    );
    private static final Pattern META_TABLE_RE = Pattern.compile(
            "db_table\\s*=\\s*[\"'](\\w+)[\"']"
    );
    private static final Pattern META_ORDERING_RE = Pattern.compile(
            "ordering\\s*=\\s*(\\[.*?\\])"
    );
    private static final Pattern MANAGER_RE = Pattern.compile(
            "^class\\s+(\\w+)\\s*\\(\\s*[\\w.]*Manager\\s*\\)", Pattern.MULTILINE
    );
    private static final Pattern MANAGER_ASSIGNMENT_RE = Pattern.compile(
            "(\\w+)\\s*=\\s*(\\w+)\\s*\\(\\s*\\)", Pattern.MULTILINE
    );
    private static final Pattern META_CLASS_RE = Pattern.compile("class\\s+Meta\\s*:");
    private static final Pattern META_END_RE = Pattern.compile("\\n\\s{4}\\S");

    @Override
    public String getName() {
        return "python.django_models";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        // First pass: detect managers
        Map<String, String> managerNames = new HashMap<>();
        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterClassdef(Python3Parser.ClassdefContext classCtx) {
                if (classCtx.name() == null) return;
                String className = classCtx.name().getText();
                String bases = getBaseClassesText(classCtx);
                if (bases != null && bases.contains("Manager")) {
                    int line = lineOf(classCtx);
                    String nodeId = "django:" + filePath + ":manager:" + className;
                    managerNames.put(className, nodeId);
                    nodes.add(createManagerNode(nodeId, className, filePath, moduleName, line));
                }
            }
        }, tree);

        // Second pass: detect models
        // Use regex on the class body to extract fields, meta, FK, M2M (complex nested patterns)
        // This is a hybrid approach - AST for class detection, regex for body parsing
        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterClassdef(Python3Parser.ClassdefContext classCtx) {
                if (classCtx.name() == null) return;
                String className = classCtx.name().getText();
                String bases = getBaseClassesText(classCtx);
                if (bases == null || !bases.matches(".*\\bModel\\b.*")) return;

                int line = lineOf(classCtx);
                String classBody = extractClassBody(text, classCtx);

                Map<String, String> fields = extractFields(classBody);
                String[] meta = extractMeta(classBody);

                String nodeId = "django:" + filePath + ":model:" + className;
                nodes.add(createModelNode(nodeId, className, filePath, moduleName, line, fields, meta[0], meta[1]));
                addDbEdge(nodeId, ctx.registry(), nodes, edges);

                addFkEdges(classBody, nodeId, filePath, edges);
                addM2mEdges(classBody, nodeId, filePath, edges);
                addManagerAssignmentEdges(classBody, nodeId, managerNames, edges);
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

        // Detect managers
        Map<String, String> managerNames = new HashMap<>();
        Matcher mgrMatcher = MANAGER_RE.matcher(text);
        while (mgrMatcher.find()) {
            String mgrName = mgrMatcher.group(1);
            int line = findLineNumber(text, mgrMatcher.start());
            String nodeId = "django:" + filePath + ":manager:" + mgrName;
            managerNames.put(mgrName, nodeId);
            nodes.add(createManagerNode(nodeId, mgrName, filePath, moduleName, line));
        }

        // Detect models
        Matcher modelMatcher = DJANGO_MODEL_RE.matcher(text);
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

            Map<String, String> fields = extractFields(classBody);
            String[] meta = extractMeta(classBody);

            String nodeId = "django:" + filePath + ":model:" + className;
            nodes.add(createModelNode(nodeId, className, filePath, moduleName, line, fields, meta[0], meta[1]));
            addDbEdge(nodeId, ctx.registry(), nodes, edges);

            addFkEdges(classBody, nodeId, filePath, edges);
            addM2mEdges(classBody, nodeId, filePath, edges);
            addManagerAssignmentEdges(classBody, nodeId, managerNames, edges);
        }

        return DetectorResult.of(nodes, edges);
    }

    // --- Shared helpers ---

    private static CodeNode createManagerNode(String nodeId, String name, String filePath,
            String moduleName, int line) {
        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(NodeKind.REPOSITORY);
        node.setLabel(name);
        node.setFqn(filePath + "::" + name);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("framework", "django");
        node.getProperties().put("type", "manager");
        return node;
    }

    private static CodeNode createModelNode(String nodeId, String className, String filePath,
            String moduleName, int line, Map<String, String> fields, String tableName, String ordering) {
        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(NodeKind.ENTITY);
        node.setLabel(className);
        node.setFqn(filePath + "::" + className);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("fields", fields);
        node.getProperties().put("framework", "django");
        if (tableName != null) {
            node.getProperties().put("table_name", tableName);
        }
        if (ordering != null) {
            node.getProperties().put("ordering", ordering);
        }
        return node;
    }

    private static Map<String, String> extractFields(String classBody) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher fieldMatcher = FIELD_RE.matcher(classBody);
        while (fieldMatcher.find()) {
            fields.put(fieldMatcher.group(1), fieldMatcher.group(2));
        }
        return fields;
    }

    /**
     * Extract Meta class properties (table_name and ordering) from a class body.
     * @return a two-element array: [tableName, ordering] (either may be null)
     */
    private static String[] extractMeta(String classBody) {
        String tableName = null;
        String ordering = null;
        Matcher metaMatch = META_CLASS_RE.matcher(classBody);
        if (metaMatch.find()) {
            int metaStart = metaMatch.end();
            int metaEnd = classBody.length();
            Matcher metaEndMatcher = META_END_RE.matcher(classBody.substring(metaStart));
            if (metaEndMatcher.find()) {
                metaEnd = metaStart + metaEndMatcher.start();
            }
            String metaBlock = classBody.substring(metaStart, metaEnd);
            Matcher tableMatch = META_TABLE_RE.matcher(metaBlock);
            if (tableMatch.find()) {
                tableName = tableMatch.group(1);
            }
            Matcher orderingMatch = META_ORDERING_RE.matcher(metaBlock);
            if (orderingMatch.find()) {
                ordering = orderingMatch.group(1);
            }
        }
        return new String[]{tableName, ordering};
    }

    private static void addFkEdges(String classBody, String nodeId, String filePath, List<CodeEdge> edges) {
        Matcher fkMatcher = FK_RE.matcher(classBody);
        while (fkMatcher.find()) {
            String targetClassName = fkMatcher.group(2);
            edges.add(createDependsOnEdge(nodeId, filePath, targetClassName));
        }
    }

    private static void addM2mEdges(String classBody, String nodeId, String filePath, List<CodeEdge> edges) {
        Matcher m2mMatcher = M2M_RE.matcher(classBody);
        while (m2mMatcher.find()) {
            String targetClassName = m2mMatcher.group(2);
            edges.add(createDependsOnEdge(nodeId, filePath, targetClassName));
        }
    }

    private static CodeEdge createDependsOnEdge(String nodeId, String filePath, String targetClassName) {
        String targetId = "django:" + filePath + ":model:" + targetClassName;
        CodeEdge edge = new CodeEdge();
        edge.setId(nodeId + "->depends_on->" + targetId);
        edge.setKind(EdgeKind.DEPENDS_ON);
        edge.setSourceId(nodeId);
        edge.setTarget(new CodeNode("*:" + targetClassName, NodeKind.ENTITY, targetClassName));
        return edge;
    }

    private static void addManagerAssignmentEdges(String classBody, String nodeId,
            Map<String, String> managerNames, List<CodeEdge> edges) {
        Matcher maMatcher = MANAGER_ASSIGNMENT_RE.matcher(classBody);
        while (maMatcher.find()) {
            String mgrClass = maMatcher.group(2);
            if (managerNames.containsKey(mgrClass)) {
                CodeEdge edge = new CodeEdge();
                edge.setId(nodeId + "->queries->" + managerNames.get(mgrClass));
                edge.setKind(EdgeKind.QUERIES);
                edge.setSourceId(nodeId);
                edges.add(edge);
            }
        }
    }

}
