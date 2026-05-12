package lexical

import "testing"

func TestExtractJavadocBlock(t *testing.T) {
	lines := []string{
		"package x;",
		"",
		"/**",
		" * Returns the user.",
		" * @param id user id",
		" */",
		"public User get(int id) {",
	}
	got := Extract(lines, "java", 7)
	want := "Returns the user. @param id user id"
	if got != want {
		t.Fatalf("Javadoc extract = %q, want %q", got, want)
	}
}

func TestExtractJavadocSingleLineBlock(t *testing.T) {
	lines := []string{
		"/** Returns the user. */",
		"public User get() {}",
	}
	got := Extract(lines, "java", 2)
	want := "Returns the user."
	if got != want {
		t.Fatalf("single-line block = %q, want %q", got, want)
	}
}

func TestExtractJSDocWithParams(t *testing.T) {
	lines := []string{
		"/**",
		" * Add two numbers.",
		" * @param {number} a",
		" * @param {number} b",
		" */",
		"function add(a, b) {",
	}
	got := Extract(lines, "typescript", 6)
	want := "Add two numbers. @param {number} a @param {number} b"
	if got != want {
		t.Fatalf("JSDoc extract = %q, want %q", got, want)
	}
}

func TestExtractCppDoxygenSingleLineBlock(t *testing.T) {
	lines := []string{
		"/** @brief Computes pi. */",
		"double pi();",
	}
	got := Extract(lines, "cpp", 2)
	want := "@brief Computes pi."
	if got != want {
		t.Fatalf("Doxygen extract = %q, want %q", got, want)
	}
}

func TestExtractGoLineComments(t *testing.T) {
	lines := []string{
		"package main",
		"",
		"// Greet prints hello.",
		"// Use it sparingly.",
		"func Greet() {}",
	}
	got := Extract(lines, "go", 5)
	want := "Greet prints hello. Use it sparingly."
	if got != want {
		t.Fatalf("go line comments = %q, want %q", got, want)
	}
}

func TestExtractRustTripleSlash(t *testing.T) {
	lines := []string{
		"/// Returns the answer.",
		"/// Always 42.",
		"fn answer() -> i32 { 42 }",
	}
	got := Extract(lines, "rust", 3)
	want := "Returns the answer. Always 42."
	if got != want {
		t.Fatalf("rust /// = %q, want %q", got, want)
	}
}

func TestExtractPythonSingleLineDocstring(t *testing.T) {
	lines := []string{
		"def add(a, b):",
		`    """Return the sum."""`,
		"    return a + b",
	}
	got := Extract(lines, "python", 1)
	want := "Return the sum."
	if got != want {
		t.Fatalf("python single-line = %q, want %q", got, want)
	}
}

func TestExtractPythonMultiLineDoubleQuoted(t *testing.T) {
	lines := []string{
		"def add(a, b):",
		`    """`,
		"    Return the sum.",
		"    Of two numbers.",
		`    """`,
		"    return a + b",
	}
	got := Extract(lines, "python", 1)
	want := "Return the sum. Of two numbers."
	if got != want {
		t.Fatalf("python multi-line double = %q, want %q", got, want)
	}
}

func TestExtractPythonMultiLineSingleQuoted(t *testing.T) {
	lines := []string{
		"def add(a, b):",
		"    '''",
		"    Return the sum.",
		"    '''",
		"    return a + b",
	}
	got := Extract(lines, "python", 1)
	want := "Return the sum."
	if got != want {
		t.Fatalf("python multi-line single = %q, want %q", got, want)
	}
}

func TestExtractSkipsAnnotationLines(t *testing.T) {
	lines := []string{
		"/**",
		" * Returns the user.",
		" */",
		"@Override",
		"@Deprecated",
		"public User get() {}",
	}
	got := Extract(lines, "java", 6)
	want := "Returns the user."
	if got != want {
		t.Fatalf("annotation walk-back = %q, want %q", got, want)
	}
}

func TestExtractAbortsOnBlankLineGap(t *testing.T) {
	lines := []string{
		"/** Stale comment. */",
		"",
		"int x = 5;",
		"",
		"public User get() {}",
	}
	got := Extract(lines, "java", 5)
	if got != "" {
		t.Fatalf("blank-line gap should abort scan, got %q", got)
	}
}

func TestExtractEmptyInputs(t *testing.T) {
	if Extract(nil, "java", 1) != "" {
		t.Fatal("nil lines should return empty")
	}
	if Extract([]string{"x"}, "java", 0) != "" {
		t.Fatal("lineStart 0 should return empty")
	}
	if Extract([]string{"x"}, "java", 5) != "" {
		t.Fatal("out-of-range lineStart should return empty")
	}
}

func TestExtractNoCommentReturnsEmpty(t *testing.T) {
	lines := []string{
		"package x;",
		"public User get() {}",
	}
	if Extract(lines, "java", 2) != "" {
		t.Fatal("no comment should return empty")
	}
	if Extract(lines, "go", 2) != "" {
		t.Fatal("no comment go should return empty")
	}
	if Extract(lines, "python", 1) != "" {
		t.Fatal("no docstring should return empty")
	}
}
