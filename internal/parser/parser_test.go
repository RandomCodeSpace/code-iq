package parser

import (
	"testing"
)

func TestParseJavaHelloWorld(t *testing.T) {
	src := []byte(`public class Hello { public static void main(String[] args) { System.out.println("hi"); } }`)
	tree, err := Parse(LanguageJava, src)
	if err != nil {
		t.Fatal(err)
	}
	defer tree.Close()
	if tree.Root == nil {
		t.Fatal("Root is nil")
	}
	root := tree.Root.RootNode()
	if root.HasError() {
		t.Fatalf("parse had errors: %s", root.String())
	}
	if root.Type() != "program" {
		t.Fatalf("root type = %q, want \"program\"", root.Type())
	}
}

func TestParsePythonHelloWorld(t *testing.T) {
	src := []byte("def hi():\n    print('hi')\n")
	tree, err := Parse(LanguagePython, src)
	if err != nil {
		t.Fatal(err)
	}
	defer tree.Close()
	root := tree.Root.RootNode()
	if root.HasError() {
		t.Fatalf("parse errors: %s", root.String())
	}
	if root.Type() != "module" {
		t.Fatalf("root type = %q, want \"module\"", root.Type())
	}
}
