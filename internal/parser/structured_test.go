package parser

import (
	"testing"
)

func TestParseTOMLUnquotesKeys(t *testing.T) {
	// `.cherry_picker.toml` in apache/airflow has `"check_sha" = "..."` —
	// a quoted top-level key. Pre-fix the key string included the literal
	// quotes which propagated into node IDs like
	// `toml:.cherry_picker.toml:"check_sha"`, and CONTAINS edges then
	// referenced PKs that the bulk-load couldn't resolve.
	src := []byte(`team = "apache"
repo = "airflow"
"check_sha" = "abc123"
'literal_key' = "single-quoted"
`)
	env := parseTOML(src)
	data, ok := env["data"].(map[string]any)
	if !ok {
		t.Fatalf("envelope missing data map: %#v", env)
	}
	for k, want := range map[string]string{
		"team":        "apache",
		"repo":        "airflow",
		"check_sha":   "abc123",
		"literal_key": "single-quoted",
	} {
		got, ok := data[k].(string)
		if !ok {
			t.Errorf("key %q missing or non-string: %#v", k, data[k])
			continue
		}
		if got != want {
			t.Errorf("data[%q] = %q, want %q", k, got, want)
		}
	}
	// Negative: a quoted form must NOT appear as its own key.
	for _, badKey := range []string{`"check_sha"`, `'literal_key'`} {
		if _, exists := data[badKey]; exists {
			t.Errorf("data still has quote-bearing key %q — unquote not applied", badKey)
		}
	}
}

func TestParseTOMLUnquotesSectionHeaders(t *testing.T) {
	// Less common in practice, but TOML spec allows `["foo.bar"]` quoted
	// section headers. Same fix applies — unquote before using as map key.
	src := []byte(`["quoted-section"]
inner = "v"
`)
	env := parseTOML(src)
	data := env["data"].(map[string]any)
	if _, ok := data["quoted-section"]; !ok {
		t.Errorf("missing top-level section 'quoted-section': %#v", data)
	}
	if _, ok := data[`"quoted-section"`]; ok {
		t.Errorf("section header retained literal quotes — unquote not applied")
	}
}
