package parser

import "testing"

func TestParseTypeScript_RootIsProgram(t *testing.T) {
	src := []byte(`export const x: number = 1;`)
	tree, err := Parse(LanguageTypeScript, src)
	if err != nil {
		t.Fatal(err)
	}
	defer tree.Close()
	root := tree.Root.RootNode()
	if root.HasError() {
		t.Fatalf("parse errors: %s", root.String())
	}
	if root.Type() != "program" {
		t.Fatalf("root type = %q, want \"program\"", root.Type())
	}
}

func TestParseByName_TypeScript(t *testing.T) {
	tree, err := ParseByName("typescript", []byte(`const a = () => 1`))
	if err != nil {
		t.Fatal(err)
	}
	defer tree.Close()
	if tree.Root.RootNode().Type() != "program" {
		t.Fatalf("unexpected root type")
	}
}

func TestLanguageFromExtension_TypeScript(t *testing.T) {
	for _, ext := range []string{".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"} {
		if got := LanguageFromExtension(ext); got != LanguageTypeScript {
			t.Errorf("LanguageFromExtension(%q) = %v, want LanguageTypeScript", ext, got)
		}
	}
}
