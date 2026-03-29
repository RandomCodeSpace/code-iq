package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlStructureDetectorTest {

    private final SqlStructureDetector detector = new SqlStructureDetector();

    @Test
    void positiveMatch_tablesAndForeignKeys() {
        String sql = """
                CREATE TABLE users (
                    id INT PRIMARY KEY,
                    name VARCHAR(100)
                );

                CREATE TABLE orders (
                    id INT PRIMARY KEY,
                    user_id INT REFERENCES users(id)
                );

                CREATE VIEW active_users AS SELECT * FROM users;

                CREATE INDEX idx_user_name ON users(name);
                """;
        DetectorContext ctx = new DetectorContext("schema.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);

        // 2 tables + 1 view + 1 index = 4 nodes
        assertEquals(4, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENTITY));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_DEFINITION));
        // 1 FK edge
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void positiveMatch_procedure() {
        String sql = "CREATE OR REPLACE PROCEDURE update_stats\nAS BEGIN\nEND;";
        DetectorContext ctx = new DetectorContext("procs.sql", "sql", sql);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n ->
                "procedure".equals(n.getProperties().get("entity_type"))));
    }

    @Test
    void negativeMatch_emptyContent() {
        DetectorContext ctx = new DetectorContext("empty.sql", "sql", "");
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        String sql = "CREATE TABLE t1 (id INT);\nCREATE TABLE t2 (id INT REFERENCES t1(id));";
        DetectorContext ctx = new DetectorContext("schema.sql", "sql", sql);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
