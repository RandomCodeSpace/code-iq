package evidence

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestEmptyPackUnsupportedWithNote(t *testing.T) {
	pack := EmptyPack(nil, "no symbol")
	if pack.CapabilityLevel != CapUnsupported {
		t.Fatalf("CapabilityLevel = %q, want UNSUPPORTED", pack.CapabilityLevel)
	}
	if len(pack.DegradationNotes) != 1 || pack.DegradationNotes[0] != "no symbol" {
		t.Errorf("DegradationNotes = %v, want [no symbol]", pack.DegradationNotes)
	}
	// Empty pack must keep zero-length non-nil slices so JSON serializes
	// as `[]` not `null` — the MCP envelope contract requires arrays.
	if pack.MatchedSymbols == nil {
		t.Error("MatchedSymbols should be non-nil empty slice")
	}
	if pack.RelatedFiles == nil {
		t.Error("RelatedFiles should be non-nil empty slice")
	}
	if pack.References == nil {
		t.Error("References should be non-nil empty slice")
	}
	if pack.Snippets == nil {
		t.Error("Snippets should be non-nil empty slice")
	}
	if pack.Provenance == nil {
		t.Error("Provenance should be non-nil empty slice")
	}
}

func TestEmptyPackBlankNoteOmitted(t *testing.T) {
	pack := EmptyPack(nil, "")
	if len(pack.DegradationNotes) != 0 {
		t.Fatalf("blank note should produce empty notes, got %v", pack.DegradationNotes)
	}
}

func TestEmptyPackJSONShapeMatchesSnakeCase(t *testing.T) {
	pack := EmptyPack(nil, "")
	b, err := json.Marshal(pack)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	got := string(b)
	for _, key := range []string{
		`"matched_symbols":`,
		`"related_files":`,
		`"references":`,
		`"snippets":`,
		`"provenance":`,
		`"degradation_notes":`,
		`"capability_level":`,
	} {
		if !strings.Contains(got, key) {
			t.Errorf("JSON missing %q\n%s", key, got)
		}
	}
}

func TestRequestEmptyDetects(t *testing.T) {
	cases := []struct {
		req     Request
		isEmpty bool
	}{
		{Request{}, true},
		{Request{Symbol: "  "}, true},
		{Request{FilePath: "\t"}, true},
		{Request{Symbol: "X"}, false},
		{Request{FilePath: "src/X.java"}, false},
	}
	for _, c := range cases {
		if got := c.req.IsEmpty(); got != c.isEmpty {
			t.Errorf("Request{%+v}.IsEmpty() = %v, want %v", c.req, got, c.isEmpty)
		}
	}
}

func TestCapabilityConstants(t *testing.T) {
	if string(CapExact) != "EXACT" {
		t.Errorf("CapExact = %q", CapExact)
	}
	if string(CapPartial) != "PARTIAL" {
		t.Errorf("CapPartial = %q", CapPartial)
	}
	if string(CapLexicalOnly) != "LEXICAL_ONLY" {
		t.Errorf("CapLexicalOnly = %q", CapLexicalOnly)
	}
	if string(CapUnsupported) != "UNSUPPORTED" {
		t.Errorf("CapUnsupported = %q", CapUnsupported)
	}
}
