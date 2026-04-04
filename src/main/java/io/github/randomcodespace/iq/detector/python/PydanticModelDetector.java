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
    name = "python.pydantic_models",
    category = "entities",
    description = "Detects Pydantic models (BaseModel, Settings) and their fields",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.CONFIG_DEFINITION, NodeKind.ENTITY},
    edgeKinds = {EdgeKind.EXTENDS},
    properties = {"base_class", "framework"}
)
@Component
public class PydanticModelDetector extends AbstractPythonAntlrDetector {

    // --- Regex patterns ---
    private static final Pattern PYDANTIC_CLASS_RE = Pattern.compile(
            "^class\\s+(\\w+)\\s*\\(\\s*(\\w*(?:BaseModel|BaseSettings)\\w*)\\s*\\)", Pattern.MULTILINE
    );
    private static final Pattern FIELD_RE = Pattern.compile(
            "^\\s+(\\w+)\\s*:\\s*(\\w[\\w\\[\\], |]*)", Pattern.MULTILINE
    );
    private static final Pattern VALIDATOR_RE = Pattern.compile(
            "@(?:validator|field_validator)\\s*\\(\\s*[\"'](\\w+)", Pattern.MULTILINE
    );
    private static final Pattern CONFIG_CLASS_RE = Pattern.compile(
            "^\\s+class\\s+Config\\s*:", Pattern.MULTILINE
    );
    private static final Pattern CONFIG_ATTR_RE = Pattern.compile(
            "^\\s{8}(\\w+)\\s*=\\s*(.+)", Pattern.MULTILINE
    );
    private static final Pattern CONFIG_END_RE = Pattern.compile("\\n\\S");

    @Override
    public String getName() {
        return "python.pydantic_models";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();
        Map<String, String> knownModels = new HashMap<>();

        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterClassdef(Python3Parser.ClassdefContext classCtx) {
                if (classCtx.name() == null) return;
                String className = classCtx.name().getText();
                String bases = getBaseClassesText(classCtx);
                if (bases == null) return;
                if (!bases.contains("BaseModel") && !bases.contains("BaseSettings")) return;

                String baseClass = bases.trim();
                int line = lineOf(classCtx);
                String classBody = extractClassBody(text, classCtx);

                String nodeId = "pydantic:" + filePath + ":model:" + className;
                nodes.add(createPydanticNode(nodeId, className, filePath, moduleName, line, baseClass, classBody));
                knownModels.put(className, nodeId);
                addExtendsEdge(nodeId, baseClass, knownModels, edges);
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

        Map<String, String> knownModels = new HashMap<>();

        Matcher classMatcher = PYDANTIC_CLASS_RE.matcher(text);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            String baseClass = classMatcher.group(2);
            int line = findLineNumber(text, classMatcher.start());

            int classStart = classMatcher.start();
            Matcher nextClassMatcher = NEXT_CLASS_RE.matcher(text.substring(classMatcher.end()));
            String classBody;
            if (nextClassMatcher.find()) {
                classBody = text.substring(classStart, classMatcher.end() + nextClassMatcher.start());
            } else {
                classBody = text.substring(classStart);
            }

            String nodeId = "pydantic:" + filePath + ":model:" + className;
            nodes.add(createPydanticNode(nodeId, className, filePath, moduleName, line, baseClass, classBody));
            knownModels.put(className, nodeId);
            addExtendsEdge(nodeId, baseClass, knownModels, edges);
        }

        return DetectorResult.of(nodes, edges);
    }

    // --- Shared helpers ---

    private static CodeNode createPydanticNode(String nodeId, String className, String filePath,
            String moduleName, int line, String baseClass, String classBody) {
        boolean isSettings = baseClass.contains("BaseSettings");

        List<String> fields = new ArrayList<>();
        Map<String, String> fieldTypes = extractFieldsAndTypes(classBody, fields);
        List<String> validators = extractValidators(classBody);
        Map<String, String> configProps = extractConfigProps(classBody);

        NodeKind nodeKind = isSettings ? NodeKind.CONFIG_DEFINITION : NodeKind.ENTITY;

        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(nodeKind);
        node.setLabel(className);
        node.setFqn(filePath + "::" + className);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.setAnnotations(validators);
        node.getProperties().put("fields", fields);
        node.getProperties().put("field_types", fieldTypes);
        node.getProperties().put("framework", "pydantic");
        node.getProperties().put("base_class", baseClass);
        if (!configProps.isEmpty()) {
            node.getProperties().put("config", configProps);
        }
        return node;
    }

    private static Map<String, String> extractFieldsAndTypes(String classBody, List<String> fields) {
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        Matcher fieldMatcher = FIELD_RE.matcher(classBody);
        while (fieldMatcher.find()) {
            String fname = fieldMatcher.group(1);
            String ftype = fieldMatcher.group(2).trim();
            if (!fname.equals("class") && !fname.equals("Config") && !fname.equals("model_config")) {
                fields.add(fname);
                fieldTypes.put(fname, ftype);
            }
        }
        return fieldTypes;
    }

    private static List<String> extractValidators(String classBody) {
        List<String> validators = new ArrayList<>();
        Matcher validatorMatcher = VALIDATOR_RE.matcher(classBody);
        while (validatorMatcher.find()) {
            validators.add(validatorMatcher.group(1));
        }
        return validators;
    }

    private static Map<String, String> extractConfigProps(String classBody) {
        Map<String, String> configProps = new LinkedHashMap<>();
        Matcher configMatch = CONFIG_CLASS_RE.matcher(classBody);
        if (configMatch.find()) {
            int configBlockStart = configMatch.end();
            int configBlockEnd = classBody.length();
            Matcher configEndMatcher = CONFIG_END_RE.matcher(classBody.substring(configBlockStart));
            if (configEndMatcher.find()) {
                configBlockEnd = configBlockStart + configEndMatcher.start();
            }
            String configBlock = classBody.substring(configBlockStart, configBlockEnd);
            Matcher attrMatcher = CONFIG_ATTR_RE.matcher(configBlock);
            while (attrMatcher.find()) {
                configProps.put(attrMatcher.group(1), attrMatcher.group(2).trim());
            }
        }
        return configProps;
    }

    private static void addExtendsEdge(String nodeId, String baseClass,
            Map<String, String> knownModels, List<CodeEdge> edges) {
        if (knownModels.containsKey(baseClass)) {
            CodeEdge edge = new CodeEdge();
            edge.setId(nodeId + "->extends->" + knownModels.get(baseClass));
            edge.setKind(EdgeKind.EXTENDS);
            edge.setSourceId(nodeId);
            edges.add(edge);
        }
    }

}
