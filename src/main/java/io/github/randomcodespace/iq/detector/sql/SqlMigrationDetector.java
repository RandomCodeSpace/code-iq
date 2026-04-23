package io.github.randomcodespace.iq.detector.sql;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects schema-level entities (tables, views, schemas) from raw SQL DDL and
 * framework-specific migration files (Flyway, Liquibase XML/YAML, Alembic, Rails,
 * Prisma), emitting {@link NodeKind#SQL_ENTITY} nodes, {@link NodeKind#MIGRATION}
 * nodes, and {@link EdgeKind#REFERENCES_TABLE} / {@link EdgeKind#MIGRATES} edges.
 * <p>
 * Path discriminators are mandatory -- a plain {@code .py}, {@code .rb},
 * {@code .xml}, or {@code .yml} file will NOT be treated as a migration unless
 * the filename/path pattern matches OR a framework-specific marker is present.
 * <p>
 * Stateless -- all parsing state is method-local.
 */
@DetectorInfo(
        name = "sql_migration",
        category = "database",
        description = "Extracts schema entities from raw SQL and migration files "
                + "(Flyway, Liquibase, Alembic, Rails, Prisma)",
        languages = {"sql", "python", "ruby", "xml", "yaml"},
        nodeKinds = {NodeKind.SQL_ENTITY, NodeKind.MIGRATION},
        edgeKinds = {EdgeKind.MIGRATES, EdgeKind.REFERENCES_TABLE},
        properties = {"schema", "table", "sql_object_type", "format", "version",
                "indexes", "columns_added"}
)
@Component
public class SqlMigrationDetector extends AbstractRegexDetector {

    private static final Logger log = LoggerFactory.getLogger(SqlMigrationDetector.class);

    // -- Node id / property keys (extracted constants; used 3+ times below) --
    private static final String NS_SQL = "sql";
    private static final String NS_MIGRATION = "migration";
    private static final String PROP_SQL_OBJECT_TYPE = "sql_object_type";
    private static final String PROP_SCHEMA = "schema";
    private static final String PROP_TABLE = "table";
    private static final String PROP_FORMAT = "format";
    private static final String PROP_VERSION = "version";
    private static final String PROP_INDEXES = "indexes";
    private static final String PROP_COLUMNS_ADDED = "columns_added";
    private static final String PROP_APPLIED_TO = "applied_to";
    private static final String OBJECT_TABLE = "table";
    private static final String OBJECT_VIEW = "view";
    private static final String OBJECT_SCHEMA = "schema";

    private static final String FMT_RAW = "raw";
    private static final String FMT_FLYWAY = "flyway";
    private static final String FMT_LIQUIBASE = "liquibase";
    private static final String FMT_ALEMBIC = "alembic";
    private static final String FMT_RAILS = "rails";
    private static final String FMT_PRISMA = "prisma";

    // -- Path discriminators (normalized to forward slashes before matching) --
    private static final Pattern FLYWAY_PATH = Pattern.compile(
            "(?:^|/)V\\d+(?:_\\d+)*+__.+\\.sql$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAILS_PATH = Pattern.compile(
            "(?:^|/)db/migrate/\\d{14}_.+\\.rb$");
    private static final Pattern ALEMBIC_PATH = Pattern.compile(
            "(?:^|/)versions/.+\\.py$");
    private static final Pattern PRISMA_PATH = Pattern.compile(
            "(?:^|/)migrations/.+/migration\\.sql$");
    private static final Pattern LIQUIBASE_PATH = Pattern.compile(
            "(?:^|/)(?:changelog|db\\.changelog[^/]*)\\.(?:xml|ya?ml)$",
            Pattern.CASE_INSENSITIVE);

    // -- Raw SQL DDL patterns --
    private static final Pattern SQL_CREATE_TABLE = Pattern.compile(
            "CREATE\\s++TABLE\\s++(?:IF\\s++NOT\\s++EXISTS\\s++)?+(?:(\\w++)\\.)?(\\w++)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_CREATE_VIEW = Pattern.compile(
            "CREATE\\s++(?:OR\\s++REPLACE\\s++)?+VIEW\\s++(?:IF\\s++NOT\\s++EXISTS\\s++)?+"
                    + "(?:(\\w++)\\.)?(\\w++)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_CREATE_SCHEMA = Pattern.compile(
            "CREATE\\s++SCHEMA\\s++(?:IF\\s++NOT\\s++EXISTS\\s++)?+(\\w++)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_ALTER_TABLE_ADD = Pattern.compile(
            "ALTER\\s++TABLE\\s++(?:(\\w++)\\.)?(\\w++)\\s++ADD\\s++(?:COLUMN\\s++)?+(\\w++)\\s++(\\w++)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_DROP_TABLE = Pattern.compile(
            "DROP\\s++TABLE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_CREATE_INDEX = Pattern.compile(
            "CREATE\\s++(?:UNIQUE\\s++)?+INDEX\\s++(?:IF\\s++NOT\\s++EXISTS\\s++)?+(\\w++)"
                    + "\\s++ON\\s++(?:(\\w++)\\.)?(\\w++)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_FK = Pattern.compile(
            "FOREIGN\\s++KEY\\s*+\\([^)]*+\\)\\s++REFERENCES\\s++(?:(\\w++)\\.)?(\\w++)",
            Pattern.CASE_INSENSITIVE);

    // -- Alembic op.* patterns --
    private static final Pattern ALEMBIC_MARKER = Pattern.compile(
            "\\bfrom\\s++alembic\\b|\\bop\\.create_table\\b|\\bop\\.add_column\\b");
    private static final Pattern ALEMBIC_CREATE_TABLE = Pattern.compile(
            "op\\.create_table\\(\\s*+['\"](\\w++)['\"]");
    private static final Pattern ALEMBIC_ADD_COLUMN = Pattern.compile(
            "op\\.add_column\\(\\s*+['\"](\\w++)['\"]\\s*+,\\s*+sa\\.Column\\(\\s*+['\"](\\w++)['\"]");
    private static final Pattern ALEMBIC_CREATE_INDEX = Pattern.compile(
            "op\\.create_index\\(\\s*+['\"](\\w++)['\"]\\s*+,\\s*+['\"](\\w++)['\"]");
    private static final Pattern ALEMBIC_CREATE_FK = Pattern.compile(
            "op\\.create_foreign_key\\(\\s*+['\"][^'\"]*+['\"]\\s*+,\\s*+['\"](\\w++)['\"]\\s*+,"
                    + "\\s*+['\"](\\w++)['\"]");

    // -- Rails migration patterns --
    private static final Pattern RAILS_CREATE_TABLE = Pattern.compile(
            "create_table\\s++:(\\w++)");
    private static final Pattern RAILS_ADD_COLUMN = Pattern.compile(
            "add_column\\s++:(\\w++)\\s*+,\\s*+:(\\w++)");
    private static final Pattern RAILS_ADD_FK = Pattern.compile(
            "add_foreign_key\\s++:(\\w++)\\s*+,\\s*+:(\\w++)");

    // -- Liquibase XML tag patterns (simple regex over the raw text) --
    private static final Pattern LQ_CREATE_TABLE_XML = Pattern.compile(
            "<createTable\\b[^>]*?\\btableName\\s*+=\\s*+\"(\\w++)\"[^>]*?"
                    + "(?:\\bschemaName\\s*+=\\s*+\"(\\w++)\")?");
    private static final Pattern LQ_ADD_COLUMN_XML = Pattern.compile(
            "<addColumn\\b[^>]*?\\btableName\\s*+=\\s*+\"(\\w++)\"");
    private static final Pattern LQ_ADD_FK_XML = Pattern.compile(
            "<addForeignKeyConstraint\\b[^>]*?\\bbaseTableName\\s*+=\\s*+\"(\\w++)\""
                    + "[^>]*?\\breferencedTableName\\s*+=\\s*+\"(\\w++)\"");

    // -- Liquibase YAML patterns (regex-based; avoids pulling in SnakeYAML here) --
    // Intermediate lines use a negative-lookahead + possessive `*+` instead of reluctant `*?`:
    // no backtracking (prevents SonarCloud S5998 stack-overflow / S5852 ReDoS), same
    // semantics — lines that *would* match the target key are excluded from the skip group,
    // so the outer match terminates exactly where the reluctant version would have.
    private static final Pattern LQ_CREATE_TABLE_YAML = Pattern.compile(
            "createTable\\s*+:[^\\n]*+\\n"
                    + "(?:(?!\\s++tableName\\s*+:)\\s++[^\\n]*+\\n)*+"
                    + "\\s++tableName\\s*+:\\s*+([\\w\"']++)");
    private static final Pattern LQ_ADD_FK_YAML = Pattern.compile(
            "addForeignKeyConstraint\\s*+:[^\\n]*+\\n"
                    + "(?:(?!\\s++baseTableName\\s*+:)\\s++[^\\n]*+\\n)*+"
                    + "\\s++baseTableName\\s*+:\\s*+([\\w\"']++)[^\\n]*+\\n"
                    + "(?:(?!\\s++referencedTableName\\s*+:)\\s++[^\\n]*+\\n)*+"
                    + "\\s++referencedTableName\\s*+:\\s*+([\\w\"']++)");

    // -- Flyway version parsing --
    private static final Pattern FLYWAY_VERSION = Pattern.compile(
            "^V(\\d++(?:_\\d++)*+)__", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAILS_VERSION = Pattern.compile(
            "^(\\d{14})_");

    @Override
    public String getName() {
        return "sql_migration";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("sql", "python", "ruby", "xml", "yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String content = ctx.content();
        String filePath = ctx.filePath();
        if (content == null || content.isEmpty() || filePath == null) {
            return DetectorResult.empty();
        }
        String normalized = filePath.replace('\\', '/');
        String lang = ctx.language();
        String lowerName = extractFileName(normalized).toLowerCase(Locale.ROOT);

        String format = classifyFormat(normalized, lowerName, lang, content);
        if (format == null) {
            return DetectorResult.empty();
        }

        ParseState state = new ParseState(ctx, normalized);
        switch (format) {
            case FMT_FLYWAY -> {
                state.migrationFormat = FMT_FLYWAY;
                state.migrationVersion = parseFlywayVersion(lowerName);
                parseRawSql(content, state);
            }
            case FMT_PRISMA -> {
                state.migrationFormat = FMT_PRISMA;
                // Version is the parent directory name.
                state.migrationVersion = parsePrismaVersion(normalized);
                parseRawSql(content, state);
            }
            case FMT_ALEMBIC -> {
                state.migrationFormat = FMT_ALEMBIC;
                parseAlembic(content, state);
            }
            case FMT_RAILS -> {
                state.migrationFormat = FMT_RAILS;
                state.migrationVersion = parseRailsVersion(lowerName);
                parseRails(content, state);
            }
            case FMT_LIQUIBASE -> {
                state.migrationFormat = FMT_LIQUIBASE;
                if (lowerName.endsWith(".xml")) {
                    parseLiquibaseXml(content, state);
                } else {
                    parseLiquibaseYaml(content, state);
                }
            }
            case FMT_RAW -> parseRawSql(content, state);
            default -> { /* unreachable */ }
        }

        return state.toResult();
    }

    // -- Format classification (discriminator guards) --

    private String classifyFormat(String path, String lowerName, String lang, String content) {
        if (PRISMA_PATH.matcher(path).find()) return FMT_PRISMA;
        if (FLYWAY_PATH.matcher(path).find()) return FMT_FLYWAY;
        if (RAILS_PATH.matcher(path).find()) return FMT_RAILS;
        if (LIQUIBASE_PATH.matcher(path).find()) return FMT_LIQUIBASE;
        if (ALEMBIC_PATH.matcher(path).find() && ALEMBIC_MARKER.matcher(content).find()) {
            return FMT_ALEMBIC;
        }
        // Raw .sql fallback -- only for actual SQL files (extension or declared language).
        if (lowerName.endsWith(".sql") || "sql".equals(lang)) {
            return FMT_RAW;
        }
        return null;
    }

    // -- Raw SQL parsing (shared by Flyway, Prisma, and bare .sql) --

    private void parseRawSql(String content, ParseState state) {
        for (IndexedLine line : iterLines(content)) {
            parseSqlLine(line.text(), line.lineNumber(), state);
        }
    }

    private void parseSqlLine(String line, int lineNum, ParseState state) {
        Matcher m = SQL_CREATE_TABLE.matcher(line);
        if (m.find()) {
            state.addOrGetSqlEntity(m.group(1), m.group(2), OBJECT_TABLE, lineNum);
            return;
        }
        m = SQL_CREATE_VIEW.matcher(line);
        if (m.find()) {
            state.addOrGetSqlEntity(m.group(1), m.group(2), OBJECT_VIEW, lineNum);
            return;
        }
        m = SQL_CREATE_SCHEMA.matcher(line);
        if (m.find()) {
            state.addOrGetSqlEntity(null, m.group(1), OBJECT_SCHEMA, lineNum);
            return;
        }
        m = SQL_ALTER_TABLE_ADD.matcher(line);
        if (m.find()) {
            SqlEntityRef ref = state.addOrGetSqlEntity(m.group(1), m.group(2), OBJECT_TABLE, lineNum);
            state.appendListProp(ref.id, PROP_COLUMNS_ADDED, m.group(3));
            return;
        }
        if (SQL_DROP_TABLE.matcher(line).find()) {
            log.debug("Skipping DROP TABLE in {} at line {}", state.filePath, lineNum);
            return;
        }
        m = SQL_CREATE_INDEX.matcher(line);
        if (m.find()) {
            String idxName = m.group(1);
            SqlEntityRef ref = state.addOrGetSqlEntity(m.group(2), m.group(3), OBJECT_TABLE, lineNum);
            state.appendListProp(ref.id, PROP_INDEXES, idxName);
            return;
        }
        m = SQL_FK.matcher(line);
        if (m.find() && state.lastTableId != null) {
            // Capture source BEFORE resolving target -- addOrGetSqlEntity for a TABLE
            // mutates lastTableId on the state object.
            String sourceId = state.lastTableId;
            SqlEntityRef target = state.addOrGetSqlEntity(m.group(1), m.group(2), OBJECT_TABLE, lineNum);
            state.addReferencesEdge(sourceId, target.id);
            state.lastTableId = sourceId; // restore owning-table context for subsequent FKs
        }
    }

    // -- Alembic (Python) parsing --

    private void parseAlembic(String content, ParseState state) {
        for (IndexedLine line : iterLines(content)) {
            String text = line.text();
            Matcher m = ALEMBIC_CREATE_TABLE.matcher(text);
            if (m.find()) {
                state.addOrGetSqlEntity(null, m.group(1), OBJECT_TABLE, line.lineNumber());
                continue;
            }
            m = ALEMBIC_ADD_COLUMN.matcher(text);
            if (m.find()) {
                SqlEntityRef ref = state.addOrGetSqlEntity(null, m.group(1), OBJECT_TABLE, line.lineNumber());
                state.appendListProp(ref.id, PROP_COLUMNS_ADDED, m.group(2));
                continue;
            }
            m = ALEMBIC_CREATE_INDEX.matcher(text);
            if (m.find()) {
                SqlEntityRef ref = state.addOrGetSqlEntity(null, m.group(2), OBJECT_TABLE, line.lineNumber());
                state.appendListProp(ref.id, PROP_INDEXES, m.group(1));
                continue;
            }
            m = ALEMBIC_CREATE_FK.matcher(text);
            if (m.find()) {
                String sourceId = state.addOrGetSqlEntity(null, m.group(1), OBJECT_TABLE, line.lineNumber()).id;
                String targetId = state.addOrGetSqlEntity(null, m.group(2), OBJECT_TABLE, line.lineNumber()).id;
                state.addReferencesEdge(sourceId, targetId);
            }
        }
    }

    // -- Rails parsing --

    private void parseRails(String content, ParseState state) {
        for (IndexedLine line : iterLines(content)) {
            String text = line.text();
            Matcher m = RAILS_CREATE_TABLE.matcher(text);
            if (m.find()) {
                state.addOrGetSqlEntity(null, m.group(1), OBJECT_TABLE, line.lineNumber());
                continue;
            }
            m = RAILS_ADD_COLUMN.matcher(text);
            if (m.find()) {
                SqlEntityRef ref = state.addOrGetSqlEntity(null, m.group(1), OBJECT_TABLE, line.lineNumber());
                state.appendListProp(ref.id, PROP_COLUMNS_ADDED, m.group(2));
                continue;
            }
            m = RAILS_ADD_FK.matcher(text);
            if (m.find()) {
                SqlEntityRef source = state.addOrGetSqlEntity(null, m.group(1), OBJECT_TABLE, line.lineNumber());
                SqlEntityRef target = state.addOrGetSqlEntity(null, m.group(2), OBJECT_TABLE, line.lineNumber());
                state.addReferencesEdge(source.id, target.id);
            }
        }
    }

    // -- Liquibase XML parsing (regex-based; no DOM parse to keep things simple) --

    private void parseLiquibaseXml(String content, ParseState state) {
        Matcher m = LQ_CREATE_TABLE_XML.matcher(content);
        while (m.find()) {
            int lineNum = findLineNumber(content, m.start());
            state.addOrGetSqlEntity(m.group(2), m.group(1), OBJECT_TABLE, lineNum);
        }
        m = LQ_ADD_COLUMN_XML.matcher(content);
        while (m.find()) {
            int lineNum = findLineNumber(content, m.start());
            state.addOrGetSqlEntity(null, m.group(1), OBJECT_TABLE, lineNum);
        }
        m = LQ_ADD_FK_XML.matcher(content);
        while (m.find()) {
            int lineNum = findLineNumber(content, m.start());
            SqlEntityRef source = state.addOrGetSqlEntity(null, m.group(1), OBJECT_TABLE, lineNum);
            SqlEntityRef target = state.addOrGetSqlEntity(null, m.group(2), OBJECT_TABLE, lineNum);
            state.addReferencesEdge(source.id, target.id);
        }
    }

    // -- Liquibase YAML parsing --

    private void parseLiquibaseYaml(String content, ParseState state) {
        Matcher m = LQ_CREATE_TABLE_YAML.matcher(content);
        while (m.find()) {
            int lineNum = findLineNumber(content, m.start());
            state.addOrGetSqlEntity(null, stripQuotes(m.group(1)), OBJECT_TABLE, lineNum);
        }
        m = LQ_ADD_FK_YAML.matcher(content);
        while (m.find()) {
            int lineNum = findLineNumber(content, m.start());
            SqlEntityRef source = state.addOrGetSqlEntity(null, stripQuotes(m.group(1)), OBJECT_TABLE, lineNum);
            SqlEntityRef target = state.addOrGetSqlEntity(null, stripQuotes(m.group(2)), OBJECT_TABLE, lineNum);
            state.addReferencesEdge(source.id, target.id);
        }
    }

    // -- Helpers --

    private static String extractFileName(String normalizedPath) {
        int slash = normalizedPath.lastIndexOf('/');
        return slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if ((first == '"' || first == '\'') && first == last) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String parseFlywayVersion(String fileName) {
        Matcher m = FLYWAY_VERSION.matcher(fileName);
        return m.find() ? m.group(1).replace('_', '.') : null;
    }

    private static String parseRailsVersion(String fileName) {
        Matcher m = RAILS_VERSION.matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    private static String parsePrismaVersion(String path) {
        // .../migrations/<version>/migration.sql
        int end = path.lastIndexOf("/migration.sql");
        if (end <= 0) return null;
        int start = path.lastIndexOf('/', end - 1);
        return start >= 0 ? path.substring(start + 1, end) : path.substring(0, end);
    }

    /**
     * Per-invocation state. NOT a field on the detector -- the detector itself
     * is stateless; this record-like holder is constructed fresh per {@code detect()} call.
     */
    private static final class ParseState {
        final DetectorContext ctx;
        final String filePath;
        // Deterministic order: insertion order. We sort on emit.
        final Map<String, CodeNode> sqlEntities = new LinkedHashMap<>();
        final Map<String, CodeEdge> refEdges = new LinkedHashMap<>();
        // Most recently touched table id (for raw SQL FKs that land on the next line).
        String lastTableId;
        // Migration metadata (null for non-migration raw SQL).
        String migrationFormat;
        String migrationVersion;

        ParseState(DetectorContext ctx, String filePath) {
            this.ctx = ctx;
            this.filePath = filePath;
        }

        SqlEntityRef addOrGetSqlEntity(String schema, String name, String objectType, int lineNum) {
            String normSchema = schema != null ? schema : "";
            String id = NS_SQL + ":" + normSchema + ":" + name;
            CodeNode node = sqlEntities.get(id);
            if (node == null) {
                node = new CodeNode(id, NodeKind.SQL_ENTITY, name);
                node.setFqn(normSchema.isEmpty() ? name : normSchema + "." + name);
                node.setModule(ctx.moduleName());
                node.setFilePath(filePath);
                node.setLineStart(lineNum);
                Map<String, Object> props = new TreeMap<>();
                props.put(PROP_SQL_OBJECT_TYPE, objectType);
                if (schema != null) {
                    props.put(PROP_SCHEMA, schema);
                }
                props.put(PROP_TABLE, name);
                node.setProperties(props);
                sqlEntities.put(id, node);
            }
            if (OBJECT_TABLE.equals(objectType)) {
                lastTableId = id;
            }
            return new SqlEntityRef(id, node);
        }

        void appendListProp(String nodeId, String key, String value) {
            CodeNode node = sqlEntities.get(nodeId);
            if (node == null) return;
            Map<String, Object> props = node.getProperties();
            Object existing = props.get(key);
            String combined;
            if (existing instanceof String s && !s.isEmpty()) {
                if (s.contains(value)) {
                    combined = s; // de-duplicate
                } else {
                    combined = s + "," + value;
                }
            } else {
                combined = value;
            }
            props.put(key, combined);
        }

        void addReferencesEdge(String sourceId, String targetId) {
            if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
                return;
            }
            String edgeId = sourceId + "->" + targetId + ":references_table";
            if (refEdges.containsKey(edgeId)) return;
            CodeEdge edge = new CodeEdge();
            edge.setId(edgeId);
            edge.setKind(EdgeKind.REFERENCES_TABLE);
            edge.setSourceId(sourceId);
            edge.setTarget(new CodeNode(targetId, null, null));
            refEdges.put(edgeId, edge);
        }

        DetectorResult toResult() {
            List<CodeNode> nodes = new ArrayList<>(sqlEntities.values());
            List<CodeEdge> edges = new ArrayList<>(refEdges.values());

            // Build the MIGRATION node (if applicable) and link to every SQL_ENTITY
            // we created/altered -- but ONLY if we actually found schema entities.
            if (migrationFormat != null && !sqlEntities.isEmpty()) {
                String migId = NS_MIGRATION + ":" + filePath;
                CodeNode migNode = new CodeNode(migId, NodeKind.MIGRATION, filePath);
                migNode.setFqn(filePath);
                migNode.setModule(ctx.moduleName());
                migNode.setFilePath(filePath);
                migNode.setLineStart(1);

                Map<String, Object> migProps = new TreeMap<>();
                migProps.put(PROP_FORMAT, migrationFormat);
                if (migrationVersion != null) {
                    migProps.put(PROP_VERSION, migrationVersion);
                }
                List<String> appliedTo = new ArrayList<>(sqlEntities.keySet());
                appliedTo.sort(Comparator.naturalOrder());
                migProps.put(PROP_APPLIED_TO, String.join(",", appliedTo));
                migNode.setProperties(migProps);

                nodes.add(migNode);

                for (String sqlId : appliedTo) {
                    CodeEdge migratesEdge = new CodeEdge();
                    migratesEdge.setId(migId + "->" + sqlId + ":migrates");
                    migratesEdge.setKind(EdgeKind.MIGRATES);
                    migratesEdge.setSourceId(migId);
                    migratesEdge.setTarget(new CodeNode(sqlId, null, null));
                    edges.add(migratesEdge);
                }
            }

            // Determinism: sort by id before emitting.
            nodes.sort(Comparator.comparing(CodeNode::getId));
            edges.sort(Comparator.comparing(CodeEdge::getId));
            return DetectorResult.of(nodes, edges);
        }
    }

    private record SqlEntityRef(String id, CodeNode node) {}
}
