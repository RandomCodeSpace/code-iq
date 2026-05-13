package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const jdbcSample = `import java.sql.DriverManager;
import org.springframework.jdbc.core.JdbcTemplate;

public class UserRepo {
    private final JdbcTemplate jdbcTemplate;

    public Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://db.example.com:5432/mydb");
    }
}`

func TestJdbcPositive(t *testing.T) {
	d := NewJdbcDetector()
	r := d.Detect(&detector.Context{FilePath: "src/UserRepo.java", Language: "java", Content: jdbcSample})
	if len(r.Nodes) < 2 {
		t.Fatalf("expected >=2 db nodes, got %d", len(r.Nodes))
	}
	hasPg := false
	for _, n := range r.Nodes {
		if n.Kind == model.NodeDatabaseConnection {
			if n.Properties["db_type"] == "postgresql" {
				hasPg = true
			}
		}
	}
	if !hasPg {
		t.Errorf("expected postgresql db_type")
	}
}

func TestJdbcNegative(t *testing.T) {
	d := NewJdbcDetector()
	r := d.Detect(&detector.Context{FilePath: "src/X.java", Language: "java", Content: "class X {}"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0, got %d", len(r.Nodes))
	}
}

func TestJdbcDeterminism(t *testing.T) {
	d := NewJdbcDetector()
	c := &detector.Context{FilePath: "src/X.java", Language: "java", Content: jdbcSample}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
