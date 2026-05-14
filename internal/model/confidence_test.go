package model

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestConfidenceScores(t *testing.T) {
	cases := map[Confidence]float64{
		ConfidenceLexical:   0.6,
		ConfidenceSyntactic: 0.8,
		ConfidenceResolved:  0.95,
	}
	for c, want := range cases {
		if got := c.Score(); got != want {
			t.Errorf("%v.Score() = %v, want %v", c, got, want)
		}
	}
}

func TestConfidenceOrdering(t *testing.T) {
	if !(ConfidenceLexical < ConfidenceSyntactic) {
		t.Error("LEXICAL should be < SYNTACTIC")
	}
	if !(ConfidenceSyntactic < ConfidenceResolved) {
		t.Error("SYNTACTIC should be < RESOLVED")
	}
}

func TestConfidenceString(t *testing.T) {
	if ConfidenceLexical.String() != "LEXICAL" {
		t.Errorf("LEXICAL string = %q", ConfidenceLexical.String())
	}
	if ConfidenceResolved.String() != "RESOLVED" {
		t.Errorf("RESOLVED string = %q", ConfidenceResolved.String())
	}
}

func TestConfidenceParseCaseInsensitive(t *testing.T) {
	for _, in := range []string{"lexical", "LEXICAL", "Lexical", "  lexical "} {
		c, err := ParseConfidence(strings.TrimSpace(in))
		if err != nil {
			t.Errorf("ParseConfidence(%q) error = %v", in, err)
			continue
		}
		if c != ConfidenceLexical {
			t.Errorf("ParseConfidence(%q) = %v, want LEXICAL", in, c)
		}
	}
	if _, err := ParseConfidence("nope"); err == nil {
		t.Error("ParseConfidence(\"nope\") err = nil, want non-nil")
	}
}

func TestConfidenceJSON(t *testing.T) {
	b, err := json.Marshal(ConfidenceResolved)
	if err != nil {
		t.Fatal(err)
	}
	if string(b) != `"RESOLVED"` {
		t.Fatalf("Marshal = %s", b)
	}
	var c Confidence
	if err := json.Unmarshal([]byte(`"SYNTACTIC"`), &c); err != nil {
		t.Fatal(err)
	}
	if c != ConfidenceSyntactic {
		t.Fatal("Unmarshal mismatch")
	}
}
