package cli

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
)

// TestCypherCommandJSONOutput asserts `codeiq cypher "MATCH (n) RETURN
// count(n) AS c"` emits a JSON object with a `rows` array containing the
// node count.
func TestCypherCommandJSONOutput(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"cypher",
		"MATCH (n:CodeNode) RETURN count(n) AS c",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("cypher: %v\n%s", err, out.String())
	}
	var got map[string]any
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("cypher output is not valid JSON: %v\n%s", err, out.String())
	}
	rows, ok := got["rows"].([]any)
	if !ok {
		t.Fatalf("cypher output missing `rows` array: %s", out.String())
	}
	if len(rows) == 0 {
		t.Fatalf("expected at least one row, got %d", len(rows))
	}
	first, ok := rows[0].(map[string]any)
	if !ok {
		t.Fatalf("first row not a map: %v", rows[0])
	}
	if _, ok := first["c"]; !ok {
		t.Fatalf("first row missing `c` column: %v", first)
	}
}

// TestCypherCommandRejectsMutation asserts a CREATE statement is rejected
// at the mutation gate before reaching Kuzu.
func TestCypherCommandRejectsMutation(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"cypher",
		"CREATE (:CodeNode {id: 'x'})",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	err := root.Execute()
	if err == nil {
		t.Fatalf("expected mutation rejection, got success:\n%s", out.String())
	}
	if !strings.Contains(err.Error(), "read-only") && !strings.Contains(err.Error(), "CREATE") {
		t.Fatalf("error must mention read-only / CREATE: %v", err)
	}
}

// TestCypherCommandTable asserts the --table flag renders an aligned table
// with the column header on the first line.
func TestCypherCommandTable(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"cypher",
		"MATCH (n:CodeNode) RETURN count(n) AS c",
		"--table",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("cypher: %v\n%s", err, out.String())
	}
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	if len(lines) < 2 {
		t.Fatalf("expected header + at least one row, got:\n%s", out.String())
	}
	if !strings.Contains(lines[0], "c") {
		t.Errorf("first line must contain column header `c`, got %q", lines[0])
	}
}
