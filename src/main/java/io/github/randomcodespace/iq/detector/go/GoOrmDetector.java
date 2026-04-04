package io.github.randomcodespace.iq.detector.go;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
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
    name = "go_orm",
    category = "database",
    description = "Detects Go ORM usage (GORM models, queries, migrations, connections)",
    parser = ParserType.REGEX,
    languages = {"go"},
    nodeKinds = {NodeKind.DATABASE_CONNECTION, NodeKind.ENTITY, NodeKind.MIGRATION, NodeKind.QUERY},
    edgeKinds = {EdgeKind.QUERIES},
    properties = {"framework", "operation"}
)
@Component
public class GoOrmDetector extends AbstractAntlrDetector {
    private static final String PROP_DATABASE_SQL = "database_sql";
    private static final String PROP_FRAMEWORK = "framework";
    private static final String PROP_GORM = "gorm";
    private static final String PROP_OP = "op";
    private static final String PROP_OPERATION = "operation";
    private static final String PROP_SQLX = "sqlx";


    private static final Pattern GORM_MODEL_RE = Pattern.compile("type\\s+(?<name>\\w+)\\s+struct\\s*\\{[^}]*gorm\\.Model", Pattern.DOTALL);
    private static final Pattern GORM_MIGRATE_RE = Pattern.compile("\\.AutoMigrate\\s*\\(", Pattern.MULTILINE);
    private static final Pattern GORM_QUERY_RE = Pattern.compile("\\.(?<op>Create|Find|Where|First|Save|Delete)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern SQLX_CONNECT_RE = Pattern.compile("sqlx\\.(?<op>Connect|Open)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern SQLX_QUERY_RE = Pattern.compile("\\.(?<op>Select|Get|NamedExec)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern SQL_OPEN_RE = Pattern.compile("sql\\.Open\\s*\\(", Pattern.MULTILINE);
    private static final Pattern SQL_QUERY_RE = Pattern.compile("\\.(?<op>Query|QueryRow|Exec)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern HAS_GORM_RE = Pattern.compile("\"gorm\\.io/");
    private static final Pattern HAS_SQLX_RE = Pattern.compile("\"github\\.com/jmoiron/sqlx\"");
    private static final Pattern HAS_DATABASE_SQL_RE = Pattern.compile("\"database/sql\"");

    @Override
    public String getName() {
        return "go_orm";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("go");
    }

    private static String detectOrm(String text) {
        if (HAS_GORM_RE.matcher(text).find()) return PROP_GORM;
        if (HAS_SQLX_RE.matcher(text).find()) return PROP_SQLX;
        if (HAS_DATABASE_SQL_RE.matcher(text).find()) return PROP_DATABASE_SQL;
        return null;
    }
    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String filePath = ctx.filePath();
        String orm = detectOrm(text);

        // GORM entity models
        Matcher m = GORM_MODEL_RE.matcher(text);
        while (m.find()) {
            String modelName = m.group("name");
            int line = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId("go_orm:" + filePath + ":entity:" + modelName + ":" + line);
            node.setKind(NodeKind.ENTITY);
            node.setLabel(modelName);
            node.setFqn(filePath + "::" + modelName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_GORM);
            node.getProperties().put("type", "model");
            nodes.add(node);
        }

        // GORM migrations
        m = GORM_MIGRATE_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId("go_orm:" + filePath + ":migration:" + line);
            node.setKind(NodeKind.MIGRATION);
            node.setLabel("AutoMigrate");
            node.setFqn(filePath + "::AutoMigrate");
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_GORM);
            node.getProperties().put("type", "auto_migrate");
            nodes.add(node);
        }

        // GORM queries
        if ("gorm".equals(orm)) {
            m = GORM_QUERY_RE.matcher(text);
            while (m.find()) {
                String op = m.group(PROP_OP);
                int line = findLineNumber(text, m.start());
                String sourceId = "go_orm:" + filePath + ":query:" + op + ":" + line;
                CodeEdge edge = new CodeEdge();
                edge.setId(filePath + ":gorm:" + op + ":" + line);
                edge.setKind(EdgeKind.QUERIES);
                edge.setSourceId(filePath);
                edge.setTarget(new CodeNode(sourceId, NodeKind.QUERY, "gorm." + op));
                edge.getProperties().put(PROP_FRAMEWORK, PROP_GORM);
                edge.getProperties().put(PROP_OPERATION, op);
                edges.add(edge);
            }
        }

        // sqlx connections
        m = SQLX_CONNECT_RE.matcher(text);
        while (m.find()) {
            String op = m.group(PROP_OP);
            int line = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId("go_orm:" + filePath + ":connection:sqlx:" + line);
            node.setKind(NodeKind.DATABASE_CONNECTION);
            node.setLabel("sqlx." + op);
            node.setFqn(filePath + "::sqlx." + op);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_SQLX);
            node.getProperties().put(PROP_OPERATION, op);
            nodes.add(node);
        }

        // sqlx queries
        if ("sqlx".equals(orm)) {
            m = SQLX_QUERY_RE.matcher(text);
            while (m.find()) {
                String op = m.group(PROP_OP);
                int line = findLineNumber(text, m.start());
                String targetId = "go_orm:" + filePath + ":query:sqlx:" + op + ":" + line;
                CodeEdge edge = new CodeEdge();
                edge.setId(filePath + ":sqlx:" + op + ":" + line);
                edge.setKind(EdgeKind.QUERIES);
                edge.setSourceId(filePath);
                edge.setTarget(new CodeNode(targetId, NodeKind.QUERY, "sqlx." + op));
                edge.getProperties().put(PROP_FRAMEWORK, PROP_SQLX);
                edge.getProperties().put(PROP_OPERATION, op);
                edges.add(edge);
            }
        }

        // database/sql connections
        m = SQL_OPEN_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            CodeNode node = new CodeNode();
            node.setId("go_orm:" + filePath + ":connection:sql:" + line);
            node.setKind(NodeKind.DATABASE_CONNECTION);
            node.setLabel("sql.Open");
            node.setFqn(filePath + "::sql.Open");
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_DATABASE_SQL);
            node.getProperties().put(PROP_OPERATION, "Open");
            nodes.add(node);
        }

        // database/sql queries
        if ("database_sql".equals(orm)) {
            m = SQL_QUERY_RE.matcher(text);
            while (m.find()) {
                String op = m.group(PROP_OP);
                int line = findLineNumber(text, m.start());
                String targetId = "go_orm:" + filePath + ":query:sql:" + op + ":" + line;
                CodeEdge edge = new CodeEdge();
                edge.setId(filePath + ":sql:" + op + ":" + line);
                edge.setKind(EdgeKind.QUERIES);
                edge.setSourceId(filePath);
                edge.setTarget(new CodeNode(targetId, NodeKind.QUERY, "sql." + op));
                edge.getProperties().put(PROP_FRAMEWORK, PROP_DATABASE_SQL);
                edge.getProperties().put(PROP_OPERATION, op);
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
