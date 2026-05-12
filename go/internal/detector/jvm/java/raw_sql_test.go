package java

import (
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
)

const rawSqlSample = `public class UserRepository {
    @Query(value = "SELECT * FROM users WHERE id = ?1", nativeQuery = true)
    public User findById(Long id) { return null; }

    public void doIt(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.query("SELECT name FROM accounts WHERE active = true", rowMapper);
    }

    public void emQuery(EntityManager em) {
        em.createNativeQuery("UPDATE orders SET status = 'paid' WHERE id = ?");
    }
}
`

func TestRawSqlPositive(t *testing.T) {
	d := NewRawSqlDetector()
	r := d.Detect(&detector.Context{FilePath: "src/UserRepository.java", Language: "java", Content: rawSqlSample})
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 query nodes, got %d", len(r.Nodes))
	}
	sources := map[string]bool{}
	for _, n := range r.Nodes {
		src, _ := n.Properties["source"].(string)
		sources[src] = true
	}
	for _, want := range []string{"annotation", "jdbc_template", "entity_manager"} {
		if !sources[want] {
			t.Errorf("missing source=%s", want)
		}
	}
	// Verify table extraction
	foundUsers := false
	for _, n := range r.Nodes {
		tables, _ := n.Properties["tables"].([]string)
		for _, t := range tables {
			if strings.EqualFold(t, "users") {
				foundUsers = true
			}
		}
	}
	if !foundUsers {
		t.Errorf("expected 'users' table extracted from FROM clause")
	}
}

func TestRawSqlNegative(t *testing.T) {
	d := NewRawSqlDetector()
	r := d.Detect(&detector.Context{FilePath: "src/X.java", Language: "java", Content: "public class X { void f() {} }"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0, got %d", len(r.Nodes))
	}
}

func TestRawSqlDeterminism(t *testing.T) {
	d := NewRawSqlDetector()
	c := &detector.Context{FilePath: "src/X.java", Language: "java", Content: rawSqlSample}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("node[%d] id drift: %q vs %q", i, r1.Nodes[i].ID, r2.Nodes[i].ID)
		}
	}
}
