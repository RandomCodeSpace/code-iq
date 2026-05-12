package parser

import (
	"testing"
)

func TestLanguageFromExtension(t *testing.T) {
	cases := map[string]Language{
		".java": LanguageJava,
		".py":   LanguagePython,
		".txt":  LanguageUnknown,
		".pyw":  LanguagePython,
	}
	for ext, want := range cases {
		if got := LanguageFromExtension(ext); got != want {
			t.Errorf("LanguageFromExtension(%q) = %v, want %v", ext, got, want)
		}
	}
}

func TestParserUnknownLanguage(t *testing.T) {
	_, err := Parse(LanguageUnknown, []byte("anything"))
	if err == nil {
		t.Fatal("Parse(unknown) err = nil, want non-nil")
	}
}
