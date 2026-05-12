package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const repositorySample = `@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("SELECT u FROM User u WHERE u.email = ?1")
    User findByEmail(String email);
}
`

func TestRepositoryPositive(t *testing.T) {
	d := NewRepositoryDetector()
	ctx := &detector.Context{FilePath: "src/UserRepository.java", Language: "java", Content: repositorySample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var hasRepo bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeRepository && n.Label == "UserRepository" {
			hasRepo = true
			if n.Properties["framework"] != "spring_boot" {
				t.Errorf("repo missing framework=spring_boot, got %v", n.Properties["framework"])
			}
			if n.Properties["entity_type"] != "User" {
				t.Errorf("entity_type wrong: %v", n.Properties["entity_type"])
			}
			if n.Properties["extends"] != "JpaRepository" {
				t.Errorf("extends wrong: %v", n.Properties["extends"])
			}
		}
	}
	if !hasRepo {
		t.Error("missing UserRepository node")
	}
	// QUERIES edge to *:User
	var hasQuery bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeQueries && e.TargetID == "*:User" {
			hasQuery = true
		}
	}
	if !hasQuery {
		t.Error("missing QUERIES edge to *:User")
	}
}

func TestRepositoryNegative(t *testing.T) {
	d := NewRepositoryDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestRepositoryDeterminism(t *testing.T) {
	d := NewRepositoryDetector()
	ctx := &detector.Context{FilePath: "src/UserRepository.java", Language: "java", Content: repositorySample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic count")
	}
}
