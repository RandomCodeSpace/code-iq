package io.github.randomcodespace.iq.detector.sql;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMigrationDetectorTest {

    private final SqlMigrationDetector detector = new SqlMigrationDetector();

    // -- Positive: raw SQL --

    @Test
    void rawSqlCreateTableEmitsEntity() {
        String sql = """
                CREATE TABLE users (
                    id INT PRIMARY KEY,
                    name VARCHAR(100)
                );
                CREATE VIEW active_users AS SELECT * FROM users;
                CREATE SCHEMA analytics;
                """;
        DetectorContext ctx = new DetectorContext("schema.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);

        List<CodeNode> sqlEntities = sqlEntitiesOf(result);
        assertEquals(3, sqlEntities.size(), "expected 3 SQL_ENTITY nodes");
        assertTrue(hasEntity(sqlEntities, "users", "table"));
        assertTrue(hasEntity(sqlEntities, "active_users", "view"));
        assertTrue(hasEntity(sqlEntities, "analytics", "schema"));
    }

    @Test
    void foreignKeyEmitsReferencesTableEdge() {
        String sql = """
                CREATE TABLE users (id INT PRIMARY KEY);
                CREATE TABLE orders (
                    id INT PRIMARY KEY,
                    user_id INT,
                    FOREIGN KEY (user_id) REFERENCES users(id)
                );
                """;
        DetectorContext ctx = new DetectorContext("schema.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);

        boolean hasFk = result.edges().stream()
                .anyMatch(e -> e.getKind() == EdgeKind.REFERENCES_TABLE
                        && e.getSourceId().endsWith(":orders")
                        && e.getTarget().getId().endsWith(":users"));
        assertTrue(hasFk, "expected REFERENCES_TABLE edge orders -> users");
    }

    @Test
    void dropTableIsSkipped() {
        String sql = "DROP TABLE obsolete_thing;";
        DetectorContext ctx = new DetectorContext("cleanup.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty(), "DROP TABLE should not emit nodes");
    }

    @Test
    void createIndexEnrichesOwningTable() {
        String sql = """
                CREATE TABLE users (id INT);
                CREATE INDEX idx_users_id ON users(id);
                """;
        DetectorContext ctx = new DetectorContext("ix.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);
        CodeNode users = firstEntity(result, "users");
        assertNotNull(users);
        assertEquals("idx_users_id", users.getProperties().get("indexes"));
    }

    @Test
    void alterTableAddColumnEnrichesEntity() {
        String sql = """
                CREATE TABLE users (id INT);
                ALTER TABLE users ADD COLUMN email VARCHAR(255);
                """;
        DetectorContext ctx = new DetectorContext("alter.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);
        CodeNode users = firstEntity(result, "users");
        assertNotNull(users);
        assertEquals("email", users.getProperties().get("columns_added"));
    }

    // -- Positive: Flyway --

    @Test
    void flywayFilenameParsedAsVersionedMigration() {
        String sql = "CREATE TABLE customers (id INT PRIMARY KEY);";
        DetectorContext ctx = new DetectorContext(
                "src/main/resources/db/migration/V1_2__add_customers.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);

        CodeNode migration = firstMigration(result);
        assertNotNull(migration, "expected MIGRATION node for Flyway file");
        assertEquals("flyway", migration.getProperties().get("format"));
        assertEquals("1.2", migration.getProperties().get("version"));

        long migrates = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MIGRATES).count();
        assertEquals(1, migrates);
    }

    // -- Positive: Alembic --

    @Test
    void alembicOpCreateTableDetected() {
        String py = """
                from alembic import op
                import sqlalchemy as sa

                def upgrade():
                    op.create_table('accounts', sa.Column('id', sa.Integer()))
                    op.add_column('accounts', sa.Column('email', sa.String()))
                    op.create_foreign_key('fk_o_a', 'orders', 'accounts', ['account_id'], ['id'])
                """;
        DetectorContext ctx = new DetectorContext(
                "alembic/versions/abc123_create_accounts.py", "python", py);
        DetectorResult result = detector.detect(ctx);

        assertTrue(hasEntity(sqlEntitiesOf(result), "accounts", "table"));
        CodeNode accounts = firstEntity(result, "accounts");
        assertNotNull(accounts);
        assertEquals("email", accounts.getProperties().get("columns_added"));
        assertTrue(result.edges().stream()
                .anyMatch(e -> e.getKind() == EdgeKind.REFERENCES_TABLE));
        CodeNode mig = firstMigration(result);
        assertNotNull(mig);
        assertEquals("alembic", mig.getProperties().get("format"));
    }

    // -- Positive: Liquibase XML --

    @Test
    void liquibaseXmlChangeSetDetected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog>
                  <changeSet id="1" author="alice">
                    <createTable tableName="products" schemaName="shop">
                      <column name="id" type="int"/>
                    </createTable>
                    <addForeignKeyConstraint
                        baseTableName="line_items"
                        referencedTableName="products"
                        baseColumnNames="product_id"
                        referencedColumnNames="id"
                        constraintName="fk_li_p"/>
                  </changeSet>
                </databaseChangeLog>
                """;
        DetectorContext ctx = new DetectorContext(
                "src/main/resources/db/changelog.xml", "xml", xml);
        DetectorResult result = detector.detect(ctx);

        assertTrue(hasEntity(sqlEntitiesOf(result), "products", "table"));
        assertTrue(result.edges().stream()
                .anyMatch(e -> e.getKind() == EdgeKind.REFERENCES_TABLE
                        && e.getSourceId().endsWith(":line_items")
                        && e.getTarget().getId().endsWith(":products")));
        assertEquals("liquibase", firstMigration(result).getProperties().get("format"));
    }

    // -- Positive: Liquibase YAML --

    @Test
    void liquibaseYamlChangeSetDetected() {
        String yaml = """
                databaseChangeLog:
                  - changeSet:
                      id: 1
                      author: bob
                      changes:
                        - createTable:
                            tableName: invoices
                            columns:
                              - column:
                                  name: id
                                  type: int
                        - addForeignKeyConstraint:
                            baseTableName: invoice_items
                            referencedTableName: invoices
                            baseColumnNames: invoice_id
                            referencedColumnNames: id
                """;
        DetectorContext ctx = new DetectorContext(
                "src/main/resources/db/db.changelog-master.yml", "yaml", yaml);
        DetectorResult result = detector.detect(ctx);

        assertTrue(hasEntity(sqlEntitiesOf(result), "invoices", "table"),
                "invoices table must be detected from Liquibase YAML");
        assertTrue(result.edges().stream()
                .anyMatch(e -> e.getKind() == EdgeKind.REFERENCES_TABLE
                        && e.getSourceId().endsWith(":invoice_items")
                        && e.getTarget().getId().endsWith(":invoices")));
    }

    @Test
    void liquibaseYamlChangeSet_largeIntermediateContent_completesQuickly() {
        // Regression guard for SonarCloud S5998 / S5852 on LQ_*_YAML: the reluctant-outer
        // `(?:\s++[^\n]*+\n)*?` patterns were rewritten with a negative-lookahead +
        // possessive outer so stack/runtime cannot blow up on many indented lines.
        // Pathological input: a createTable with 500 indented column lines preceding
        // tableName — the pre-fix reluctant walk would scale quadratically.
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            columns.append("                                  - column_").append(i).append(": stub_value\n");
        }
        String yaml = """
                databaseChangeLog:
                  - changeSet:
                      id: 1
                      author: bob
                      changes:
                        - createTable:
                """
                + columns
                + "                            tableName: wide_table\n";
        DetectorContext ctx = new DetectorContext(
                "src/main/resources/db/db.changelog-wide.yml", "yaml", yaml);

        long start = System.nanoTime();
        DetectorResult result = detector.detect(ctx);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(hasEntity(sqlEntitiesOf(result), "wide_table", "table"),
                "wide_table must still be detected after the anti-backtracking rewrite");
        // 2s is 100x the observed fast-path time; we only care that it doesn't blow up
        // exponentially — the exact threshold isn't load-bearing.
        assertTrue(elapsedMs < 2000,
                "detection of wide_table should complete in under 2s, was " + elapsedMs + "ms");
    }

    // -- Positive: Rails --

    @Test
    void railsCreateTableDetected() {
        String rb = """
                class CreateOrders < ActiveRecord::Migration[7.0]
                  def change
                    create_table :orders do |t|
                      t.integer :customer_id
                      t.timestamps
                    end
                    add_column :orders, :note, :text
                    add_foreign_key :orders, :customers
                  end
                end
                """;
        DetectorContext ctx = new DetectorContext(
                "db/migrate/20240115120000_create_orders.rb", "ruby", rb);
        DetectorResult result = detector.detect(ctx);

        assertTrue(hasEntity(sqlEntitiesOf(result), "orders", "table"));
        CodeNode orders = firstEntity(result, "orders");
        assertEquals("note", orders.getProperties().get("columns_added"));
        assertTrue(result.edges().stream()
                .anyMatch(e -> e.getKind() == EdgeKind.REFERENCES_TABLE
                        && e.getSourceId().endsWith(":orders")
                        && e.getTarget().getId().endsWith(":customers")));

        CodeNode mig = firstMigration(result);
        assertEquals("rails", mig.getProperties().get("format"));
        assertEquals("20240115120000", mig.getProperties().get("version"));
    }

    // -- Positive: Prisma --

    @Test
    void prismaMigrationSqlDetected() {
        String sql = "CREATE TABLE Post (id INT PRIMARY KEY);";
        DetectorContext ctx = new DetectorContext(
                "prisma/migrations/20240101120000_init/migration.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);

        assertTrue(hasEntity(sqlEntitiesOf(result), "Post", "table"));
        CodeNode mig = firstMigration(result);
        assertEquals("prisma", mig.getProperties().get("format"));
        assertEquals("20240101120000_init", mig.getProperties().get("version"));
    }

    // -- Negative --

    @Test
    void plainPythonFileIgnored() {
        String py = """
                def hello():
                    print("op.create_table is just a comment here")
                """;
        DetectorContext ctx = new DetectorContext("app/utils.py", "python", py);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty(),
                "non-alembic .py outside versions/ must not produce nodes");
    }

    @Test
    void plainYamlIgnored() {
        String yaml = """
                name: build
                on:
                  push:
                    branches: [main]
                jobs:
                  test:
                    runs-on: ubuntu-latest
                """;
        DetectorContext ctx = new DetectorContext(".github/workflows/ci.yml", "yaml", yaml);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty(),
                "arbitrary YAML must not produce sql_migration nodes");
    }

    @Test
    void emptyContentReturnsEmptyResult() {
        DetectorContext ctx = new DetectorContext("empty.sql", "sql", "");
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void alembicPathWithoutMarkerIsIgnored() {
        // Even under versions/, without the alembic marker we must NOT fire.
        String py = """
                # random script that happens to live under versions/
                def helper():
                    return 1
                """;
        DetectorContext ctx = new DetectorContext(
                "alembic/versions/abc.py", "python", py);
        DetectorResult result = detector.detect(ctx);
        assertTrue(result.nodes().isEmpty());
    }

    // -- Determinism --

    @Test
    void determinismIdenticalOutputAcrossRuns() {
        String sql = """
                CREATE TABLE a (id INT);
                CREATE TABLE b (id INT, FOREIGN KEY (id) REFERENCES a(id));
                CREATE TABLE c (id INT, FOREIGN KEY (id) REFERENCES a(id));
                CREATE INDEX ix1 ON a(id);
                CREATE INDEX ix2 ON a(id);
                """;
        DetectorContext ctx = new DetectorContext(
                "db/migration/V1__multi.sql", "sql", sql);
        DetectorTestUtils.assertDeterministic(detector, ctx);

        // Stronger check: byte-equal ID order across runs.
        DetectorResult r1 = detector.detect(ctx);
        DetectorResult r2 = detector.detect(ctx);
        assertEquals(
                r1.nodes().stream().map(CodeNode::getId).toList(),
                r2.nodes().stream().map(CodeNode::getId).toList(),
                "node id order must be byte-equal across runs");
        assertEquals(
                r1.edges().stream().map(CodeEdge::getId).toList(),
                r2.edges().stream().map(CodeEdge::getId).toList(),
                "edge id order must be byte-equal across runs");
    }

    // -- Helpers --

    private static List<CodeNode> sqlEntitiesOf(DetectorResult r) {
        return r.nodes().stream().filter(n -> n.getKind() == NodeKind.SQL_ENTITY).toList();
    }

    private static boolean hasEntity(List<CodeNode> nodes, String name, String type) {
        return nodes.stream().anyMatch(n ->
                name.equals(n.getLabel())
                        && type.equals(n.getProperties().get("sql_object_type")));
    }

    private static CodeNode firstEntity(DetectorResult r, String name) {
        return r.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.SQL_ENTITY && name.equals(n.getLabel()))
                .findFirst().orElse(null);
    }

    private static CodeNode firstMigration(DetectorResult r) {
        return r.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.MIGRATION)
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unused")
    private static void requireFalse(boolean cond, String msg) {
        assertFalse(cond, msg);
    }
}
