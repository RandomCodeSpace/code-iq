package io.github.randomcodespace.iq.detector.typescript;

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
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "sequelize_orm",
    category = "database",
    description = "Detects Sequelize ORM models, associations, and connections",
    parser = ParserType.REGEX,
    languages = {"typescript", "javascript"},
    nodeKinds = {NodeKind.DATABASE_CONNECTION, NodeKind.ENTITY},
    edgeKinds = {EdgeKind.DEPENDS_ON, EdgeKind.QUERIES},
    properties = {"framework", "operation"}
)
@Component
public class SequelizeORMDetector extends AbstractTypeScriptDetector {
    private static final String PROP_FRAMEWORK = "framework";
    private static final String PROP_SEQUELIZE = "sequelize";


    private static final Pattern DEFINE_RE = Pattern.compile(
            "sequelize\\.define\\s*\\(\\s*['\"](\\w+)['\"]"
    );

    private static final Pattern EXTENDS_MODEL_RE = Pattern.compile(
            "class\\s+(\\w+)\\s+extends\\s+Model\\s*\\{"
    );

    private static final Pattern CONNECTION_RE = Pattern.compile(
            "new\\s+Sequelize(?:\\.Sequelize)?\\s*\\("
    );

    private static final Pattern ASSOCIATION_RE = Pattern.compile(
            "(\\w+)\\.(belongsTo|hasMany|hasOne|belongsToMany)\\s*\\(\\s*(\\w+)"
    );

    private static final Pattern QUERY_RE = Pattern.compile(
            "(\\w+)\\.(findAll|findOne|findByPk|findOrCreate|create|bulkCreate|update|destroy|count|max|min|sum)\\s*\\("
    );

    @Override
    public String getName() {
        return "sequelize_orm";
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        Map<String, String> seenModels = new LinkedHashMap<>();

        // Sequelize connection -> DATABASE_CONNECTION
        Matcher matcher = CONNECTION_RE.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            CodeNode node = new CodeNode();
            node.setId("sequelize:" + filePath + ":connection:" + line);
            node.setKind(NodeKind.DATABASE_CONNECTION);
            node.setLabel("Sequelize");
            node.setFqn(filePath + "::Sequelize");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_SEQUELIZE);
            nodes.add(node);
        }

        // sequelize.define('ModelName', { ... }) -> ENTITY
        matcher = DEFINE_RE.matcher(text);
        while (matcher.find()) {
            String modelName = matcher.group(1);
            int line = findLineNumber(text, matcher.start());
            String modelId = "sequelize:" + filePath + ":model:" + modelName;
            seenModels.put(modelName, modelId);
            CodeNode node = new CodeNode();
            node.setId(modelId);
            node.setKind(NodeKind.ENTITY);
            node.setLabel(modelName);
            node.setFqn(filePath + "::" + modelName);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_SEQUELIZE);
            node.getProperties().put("definition", "define");
            nodes.add(node);
        }

        // class X extends Model -> ENTITY
        matcher = EXTENDS_MODEL_RE.matcher(text);
        while (matcher.find()) {
            String className = matcher.group(1);
            int line = findLineNumber(text, matcher.start());
            if (!seenModels.containsKey(className)) {
                String modelId = "sequelize:" + filePath + ":model:" + className;
                seenModels.put(className, modelId);
                CodeNode node = new CodeNode();
                node.setId(modelId);
                node.setKind(NodeKind.ENTITY);
                node.setLabel(className);
                node.setFqn(filePath + "::" + className);
                node.setModule(moduleName);
                node.setFilePath(filePath);
                node.setLineStart(line);
                node.getProperties().put(PROP_FRAMEWORK, PROP_SEQUELIZE);
                node.getProperties().put("definition", "class");
                nodes.add(node);
            }
        }

        // Associations -> DEPENDS_ON edges
        matcher = ASSOCIATION_RE.matcher(text);
        while (matcher.find()) {
            String sourceModel = matcher.group(1);
            String assocType = matcher.group(2);
            String targetModel = matcher.group(3);
            int line = findLineNumber(text, matcher.start());
            String sourceId = seenModels.getOrDefault(sourceModel,
                    "sequelize:" + filePath + ":model:" + sourceModel);
            String targetId = seenModels.getOrDefault(targetModel,
                    "sequelize:" + filePath + ":model:" + targetModel);
            CodeEdge edge = new CodeEdge();
            edge.setId(sourceId + "->" + assocType + "->" + targetId + ":" + line);
            edge.setKind(EdgeKind.DEPENDS_ON);
            edge.setSourceId(sourceId);
            edge.getProperties().put("association", assocType);
            edge.getProperties().put("line", line);
            edges.add(edge);
        }

        // Query operations -> QUERIES edges
        matcher = QUERY_RE.matcher(text);
        while (matcher.find()) {
            String modelName = matcher.group(1);
            String operation = matcher.group(2);
            int line = findLineNumber(text, matcher.start());
            String targetId = seenModels.getOrDefault(modelName,
                    "sequelize:" + filePath + ":model:" + modelName);
            CodeEdge edge = new CodeEdge();
            edge.setId(filePath + "->queries->" + targetId + ":" + line);
            edge.setKind(EdgeKind.QUERIES);
            edge.setSourceId(filePath);
            edge.getProperties().put("operation", operation);
            edge.getProperties().put("line", line);
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
