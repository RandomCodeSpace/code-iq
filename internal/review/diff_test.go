package review

import (
	"testing"
)

func TestParseDiff_FileWithSingleHunk(t *testing.T) {
	raw := `diff --git a/src/foo.go b/src/foo.go
index abc..def 100644
--- a/src/foo.go
+++ b/src/foo.go
@@ -1,5 +1,6 @@
 package foo

+import "fmt"
 func Bar() {
-	println("old")
+	fmt.Println("new")
 }
`
	files, err := ParseDiff(raw)
	if err != nil {
		t.Fatalf("ParseDiff: %v", err)
	}
	if len(files) != 1 {
		t.Fatalf("expected 1 file, got %d", len(files))
	}
	f := files[0]
	if f.Path != "src/foo.go" {
		t.Errorf("path = %q want src/foo.go", f.Path)
	}
	if f.AddedLines != 2 {
		t.Errorf("added = %d want 2", f.AddedLines)
	}
	if f.RemovedLines != 1 {
		t.Errorf("removed = %d want 1", f.RemovedLines)
	}
	if len(f.Hunks) != 1 {
		t.Errorf("hunks = %d want 1", len(f.Hunks))
	}
}

func TestParseDiff_MultipleFiles(t *testing.T) {
	raw := `diff --git a/a.txt b/a.txt
index 0..1 100644
--- a/a.txt
+++ b/a.txt
@@ -1 +1 @@
-a
+A
diff --git a/b.txt b/b.txt
new file mode 100644
index 0..2
--- /dev/null
+++ b/b.txt
@@ -0,0 +1 @@
+B
`
	files, err := ParseDiff(raw)
	if err != nil {
		t.Fatalf("ParseDiff: %v", err)
	}
	if len(files) != 2 {
		t.Fatalf("expected 2 files, got %d", len(files))
	}
	if files[0].Path != "a.txt" || files[1].Path != "b.txt" {
		t.Errorf("paths = %q, %q", files[0].Path, files[1].Path)
	}
}

func TestParseDiff_Empty(t *testing.T) {
	files, err := ParseDiff("")
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 0 {
		t.Errorf("expected 0 files from empty diff, got %d", len(files))
	}
}
