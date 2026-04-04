package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.typescript.TypeScriptParser;
import io.github.randomcodespace.iq.grammar.typescript.TypeScriptParserBaseListener;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "typescript_structures",
    category = "structures",
    description = "Detects TypeScript/JavaScript classes, interfaces, functions, enums, and imports",
    parser = ParserType.ANTLR,
    languages = {"typescript", "javascript"},
    nodeKinds = {NodeKind.CLASS, NodeKind.ENUM, NodeKind.INTERFACE, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.IMPORTS}
)
@Component
public class TypeScriptStructuresDetector extends AbstractAntlrDetector {
    private static final String PROP_ASYNC = "async";
    private static final String PROP_TYPESCRIPT = "typescript";


    private static final Pattern INTERFACE_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?interface\\s+(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern TYPE_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?type\\s+(\\w+)\\s*=", Pattern.MULTILINE
    );
    private static final Pattern CLASS_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern FUNC_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?(default\\s+)?(?:(async)\\s+)?function\\s+(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern CONST_FUNC_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?const\\s+(\\w+)\\s*=\\s*(?:(async)\\s+)?\\(", Pattern.MULTILINE
    );
    private static final Pattern ENUM_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:const\\s+)?enum\\s+(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern IMPORT_RE = Pattern.compile(
            "import\\s+.*?\\s+from\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE
    );
    private static final Pattern NAMESPACE_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?namespace\\s+(\\w+)", Pattern.MULTILINE
    );

    @Override
    public String getName() {
        return "typescript_structures";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of(PROP_TYPESCRIPT, "javascript");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        // Use the dedicated TypeScript ANTLR grammar for .ts files;
        // for .js files or very large files, fall back to regex
        if (ctx.content().length() > 500_000) {
            return null; // triggers regex fallback
        }
        if ("typescript".equals(ctx.language())) {
            return AntlrParserFactory.parse(PROP_TYPESCRIPT, ctx.content());
        }
        // JavaScript files use the JS grammar (or fall back to regex if parse fails)
        return AntlrParserFactory.parse("javascript", ctx.content());
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String fp = ctx.filePath();
        String moduleName = ctx.moduleName();
        Set<String> existingIds = new LinkedHashSet<>();

        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(new TypeScriptParserBaseListener() {

            @Override
            public void enterInterfaceDeclaration(TypeScriptParser.InterfaceDeclarationContext ruleCtx) {
                if (ruleCtx.identifier() == null) return;
                String name = ruleCtx.identifier().getText();
                String nodeId = "ts:" + fp + ":interface:" + name;
                if (!existingIds.add(nodeId)) return;

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.INTERFACE);
                node.setLabel(name);
                node.setFqn(name);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineOf(ruleCtx));
                nodes.add(node);
            }

            @Override
            public void enterTypeAliasDeclaration(TypeScriptParser.TypeAliasDeclarationContext ruleCtx) {
                if (ruleCtx.identifier() == null) return;
                String name = ruleCtx.identifier().getText();
                String nodeId = "ts:" + fp + ":type:" + name;
                if (!existingIds.add(nodeId)) return;

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.CLASS);
                node.setLabel(name);
                node.setFqn(name);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineOf(ruleCtx));
                node.getProperties().put("type_alias", true);
                nodes.add(node);
            }

            @Override
            public void enterClassDeclaration(TypeScriptParser.ClassDeclarationContext ruleCtx) {
                if (ruleCtx.identifier() == null) return;
                String name = ruleCtx.identifier().getText();
                String nodeId = "ts:" + fp + ":class:" + name;
                if (!existingIds.add(nodeId)) return;

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.CLASS);
                node.setLabel(name);
                node.setFqn(name);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineOf(ruleCtx));
                nodes.add(node);
            }

            @Override
            public void enterFunctionDeclaration(TypeScriptParser.FunctionDeclarationContext ruleCtx) {
                if (ruleCtx.identifier() == null) return;
                String funcName = ruleCtx.identifier().getText();
                String nodeId = "ts:" + fp + ":func:" + funcName;
                if (!existingIds.add(nodeId)) return;

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.METHOD);
                node.setLabel(funcName);
                node.setFqn(funcName);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineOf(ruleCtx));
                if (ruleCtx.Async() != null) {
                    node.getProperties().put(PROP_ASYNC, true);
                }
                nodes.add(node);
            }

            @Override
            public void enterEnumDeclaration(TypeScriptParser.EnumDeclarationContext ruleCtx) {
                if (ruleCtx.identifier() == null) return;
                String name = ruleCtx.identifier().getText();
                String nodeId = "ts:" + fp + ":enum:" + name;
                if (!existingIds.add(nodeId)) return;

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.ENUM);
                node.setLabel(name);
                node.setFqn(name);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineOf(ruleCtx));
                nodes.add(node);
            }

            @Override
            public void enterNamespaceDeclaration(TypeScriptParser.NamespaceDeclarationContext ruleCtx) {
                if (ruleCtx.namespaceName() == null) return;
                String name = ruleCtx.namespaceName().getText();
                String nodeId = "ts:" + fp + ":namespace:" + name;
                if (!existingIds.add(nodeId)) return;

                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.MODULE);
                node.setLabel(name);
                node.setFqn(name);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(lineOf(ruleCtx));
                nodes.add(node);
            }

            @Override
            public void enterImportStatement(TypeScriptParser.ImportStatementContext ruleCtx) {
                // Extract module path from the import statement
                var fromBlock = ruleCtx.importFromBlock();
                if (fromBlock == null) return;

                String modulePath = null;
                if (fromBlock.importFrom() != null) {
                    // import { X } from 'module' or import X from 'module'
                    var importFrom = fromBlock.importFrom();
                    if (importFrom.StringLiteral() != null) {
                        modulePath = stripQuotes(importFrom.StringLiteral().getText());
                    }
                } else if (fromBlock.StringLiteral() != null) {
                    // import 'module' (side-effect import)
                    modulePath = stripQuotes(fromBlock.StringLiteral().getText());
                }

                if (modulePath != null && !modulePath.isEmpty()) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(fp + "->imports->" + modulePath);
                    edge.setKind(EdgeKind.IMPORTS);
                    edge.setSourceId(fp);
                    edges.add(edge);
                }
            }
        }, tree);

        // If ANTLR found results, compare with regex to pick the richer result.
        // The ANTLR grammar may miss some TS-specific constructs (const arrow funcs,
        // type aliases) that regex handles well. Prefer whichever finds more structures.
        DetectorResult astResult = DetectorResult.of(nodes, edges);
        if (nodes.isEmpty() && edges.isEmpty()) {
            return detectWithRegex(ctx);
        }
        DetectorResult regexResult = detectWithRegex(ctx);
        if (regexResult.nodes().size() > astResult.nodes().size()) {
            return regexResult;
        }
        return astResult;
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String fp = ctx.filePath();
        String moduleName = ctx.moduleName();
        Set<String> existingIds = new LinkedHashSet<>();

        // Interfaces
        Matcher m = INTERFACE_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":interface:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.INTERFACE);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Type aliases
        m = TYPE_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":type:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.CLASS);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            node.getProperties().put("type_alias", true);
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Classes
        m = CLASS_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":class:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.CLASS);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Named functions
        m = FUNC_RE.matcher(text);
        while (m.find()) {
            boolean isDefault = m.group(1) != null;
            boolean isAsync = m.group(2) != null;
            String funcName = m.group(3);
            String nodeId = "ts:" + fp + ":func:" + funcName;

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.METHOD);
            node.setLabel(funcName);
            node.setFqn(funcName);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            if (isDefault) node.getProperties().put("default", true);
            if (isAsync) node.getProperties().put(PROP_ASYNC, true);
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Arrow / const functions
        m = CONST_FUNC_RE.matcher(text);
        while (m.find()) {
            String funcName = m.group(1);
            boolean isAsync = m.group(2) != null;
            String nodeId = "ts:" + fp + ":func:" + funcName;
            if (existingIds.contains(nodeId)) continue;

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.METHOD);
            node.setLabel(funcName);
            node.setFqn(funcName);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            if (isAsync) node.getProperties().put(PROP_ASYNC, true);
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Enums
        m = ENUM_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":enum:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENUM);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Imports
        m = IMPORT_RE.matcher(text);
        while (m.find()) {
            String modulePath = m.group(1);
            CodeEdge edge = new CodeEdge();
            edge.setId(fp + "->imports->" + modulePath);
            edge.setKind(EdgeKind.IMPORTS);
            edge.setSourceId(fp);
            edges.add(edge);
        }

        // Namespaces
        m = NAMESPACE_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":namespace:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MODULE);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            nodes.add(node);
            existingIds.add(nodeId);
        }

        return DetectorResult.of(nodes, edges);
    }

    private static String stripQuotes(String text) {
        if (text != null && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
