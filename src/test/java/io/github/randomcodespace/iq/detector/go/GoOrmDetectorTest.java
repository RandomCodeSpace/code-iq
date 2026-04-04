package io.github.randomcodespace.iq.detector.go;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoOrmDetectorTest {

    private final GoOrmDetector d = new GoOrmDetector();

    @Test
    void detectsGormModel() {
        String code = "import \"gorm.io/gorm\"\ntype User struct {\n  gorm.Model\n  Name string\n}";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().size() >= 1);
        assertEquals(NodeKind.ENTITY, r.nodes().get(0).getKind());
    }

    @Test
    void gormModelHasFrameworkProperty() {
        String code = "import \"gorm.io/gorm\"\ntype Product struct {\n  gorm.Model\n}";
        DetectorResult r = d.detect(ctx(code));
        var entity = r.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("gorm", entity.getProperties().get("framework"));
    }

    @Test
    void detectsGormAutoMigrate() {
        String code = "import \"gorm.io/gorm\"\ndb.AutoMigrate(&User{}, &Product{})";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MIGRATION));
    }

    @Test
    void detectsGormQueryEdges() {
        String code = """
                import "gorm.io/gorm"
                func getUsers(db *gorm.DB) {
                    db.Find(&users)
                    db.Where("active = ?", true).First(&user)
                    db.Create(&newUser)
                    db.Save(&user)
                    db.Delete(&user)
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertFalse(r.edges().stream().filter(e -> e.getKind() == EdgeKind.QUERIES).findAny().isEmpty());
    }

    @Test
    void detectsSqlxConnection() {
        String code = """
                import "github.com/jmoiron/sqlx"
                func connect() {
                    db := sqlx.Connect("postgres", dsn)
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.DATABASE_CONNECTION
                && "sqlx".equals(n.getProperties().get("framework"))));
    }

    @Test
    void detectsSqlxQueryEdges() {
        String code = """
                import "github.com/jmoiron/sqlx"
                func query(db *sqlx.DB) {
                    db.Select(&users, "SELECT * FROM users")
                    db.Get(&user, "SELECT * FROM users WHERE id=$1", 1)
                    db.NamedExec("INSERT INTO users VALUES (:name)", user)
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertFalse(r.edges().stream().filter(e -> e.getKind() == EdgeKind.QUERIES).findAny().isEmpty());
    }

    @Test
    void detectsDatabaseSqlConnection() {
        String code = """
                import "database/sql"
                func connect() {
                    db, _ := sql.Open("mysql", dsn)
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.DATABASE_CONNECTION
                && "database_sql".equals(n.getProperties().get("framework"))));
    }

    @Test
    void detectsDatabaseSqlQueryEdges() {
        String code = """
                import "database/sql"
                func query(db *sql.DB) {
                    rows, _ := db.Query("SELECT * FROM items")
                    row := db.QueryRow("SELECT * FROM items WHERE id = ?", 1)
                    db.Exec("DELETE FROM items WHERE id = ?", 1)
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertFalse(r.edges().stream().filter(e -> e.getKind() == EdgeKind.QUERIES).findAny().isEmpty());
    }

    @Test
    void noMatchOnPlainGoCode() {
        DetectorResult r = d.detect(ctx("package main\nfunc main() {}"));
        assertEquals(0, r.nodes().size());
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.go", "go", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("go_orm", d.getName());
    }

    @Test
    void supportedLanguagesContainsGo() {
        assertTrue(d.getSupportedLanguages().contains("go"));
    }

    @Test
    void deterministic() {
        String code = """
                import "gorm.io/gorm"
                type User struct {
                  gorm.Model
                  Name string
                }
                type Order struct {
                  gorm.Model
                  Total float64
                }
                func setup(db *gorm.DB) {
                  db.AutoMigrate(&User{}, &Order{})
                  db.Create(&User{Name: "test"})
                  db.Find(&users)
                  db.Where("name = ?", "test").First(&user)
                }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("go", content);
    }
}
