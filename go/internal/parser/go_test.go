package parser

import "testing"

func TestParseGo_RootIsSourceFile(t *testing.T) {
	src := []byte("package main\nfunc main() {}\n")
	tree, err := Parse(LanguageGo, src)
	if err != nil {
		t.Fatal(err)
	}
	defer tree.Close()
	root := tree.Root.RootNode()
	if root.HasError() {
		t.Fatalf("parse errors: %s", root.String())
	}
	if root.Type() != "source_file" {
		t.Fatalf("root type = %q, want \"source_file\"", root.Type())
	}
}

func TestParseByName_Go(t *testing.T) {
	tree, err := ParseByName("go", []byte("package x\n"))
	if err != nil {
		t.Fatal(err)
	}
	defer tree.Close()
	if tree.Root.RootNode().Type() != "source_file" {
		t.Fatal("unexpected root type")
	}
	// "golang" alias should work too.
	tree2, err := ParseByName("golang", []byte("package x\n"))
	if err != nil {
		t.Fatal(err)
	}
	defer tree2.Close()
}

func TestLanguageFromExtension_Go(t *testing.T) {
	if got := LanguageFromExtension(".go"); got != LanguageGo {
		t.Errorf("LanguageFromExtension(.go) = %v, want LanguageGo", got)
	}
}
