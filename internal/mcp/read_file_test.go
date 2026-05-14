package mcp

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestReadFileInRepoFileSucceeds(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "hello.txt"), []byte("hello world\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "hello.txt", MaxBytes: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	if got.Content != "hello world\n" {
		t.Fatalf("content = %q", got.Content)
	}
	if got.Path != "hello.txt" {
		t.Fatalf("path = %q", got.Path)
	}
	if !strings.HasPrefix(got.MimeType, "text/") {
		t.Fatalf("mime = %q, want text/* prefix", got.MimeType)
	}
}

func TestReadFileSymlinkOutOfRepoBlocked(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("nope"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(outside, "secret.txt"), filepath.Join(root, "link.txt")); err != nil {
		t.Skipf("symlink unsupported on this filesystem: %v", err)
	}
	_, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "link.txt", MaxBytes: 1 << 20})
	if err == nil {
		t.Fatal("expected error for out-of-repo symlink")
	}
	if !strings.Contains(err.Error(), "traversal") {
		t.Fatalf("err = %v, want traversal substring", err)
	}
}

func TestReadFilePathTraversalBlocked(t *testing.T) {
	root := t.TempDir()
	_, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "../../etc/passwd", MaxBytes: 1 << 20})
	if err == nil {
		t.Fatal("expected error for ../../ traversal")
	}
	if !strings.Contains(err.Error(), "traversal") {
		t.Fatalf("err = %v, want traversal substring", err)
	}
}

func TestReadFileAbsolutePathRejected(t *testing.T) {
	root := t.TempDir()
	_, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "/etc/passwd", MaxBytes: 1 << 20})
	if err == nil {
		t.Fatal("expected error for absolute path")
	}
	if !strings.Contains(err.Error(), "absolute") {
		t.Fatalf("err = %v, want absolute substring", err)
	}
}

func TestReadFileBinaryBlocked(t *testing.T) {
	root := t.TempDir()
	// ELF magic prefix — net/http sniffer will not classify this as text/*.
	bin := []byte{0x7f, 'E', 'L', 'F', 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	if err := os.WriteFile(filepath.Join(root, "bin"), bin, 0o644); err != nil {
		t.Fatal(err)
	}
	_, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "bin", MaxBytes: 1 << 20})
	if err == nil {
		t.Fatal("expected mime-type rejection for binary")
	}
	if !strings.Contains(err.Error(), "content type") {
		t.Fatalf("err = %v, want content type substring", err)
	}
}

func TestReadFileOversizeTruncated(t *testing.T) {
	root := t.TempDir()
	data := make([]byte, 1024)
	for i := range data {
		data[i] = 'a'
	}
	if err := os.WriteFile(filepath.Join(root, "big.txt"), data, 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "big.txt", MaxBytes: 256})
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Content) != 256 || !got.Truncated {
		t.Fatalf("oversize: len=%d truncated=%v", len(got.Content), got.Truncated)
	}
}

func TestReadFileLineRange(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "f.txt"), []byte("a\nb\nc\nd\ne\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "f.txt", StartLine: 2, EndLine: 4, MaxBytes: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	if got.Content != "b\nc\nd\n" {
		t.Fatalf("range content = %q", got.Content)
	}
	if got.StartLine != 2 || got.EndLine != 4 {
		t.Fatalf("start/end = %d/%d, want 2/4", got.StartLine, got.EndLine)
	}
}

func TestReadFileMissingPath(t *testing.T) {
	root := t.TempDir()
	_, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "nope.txt", MaxBytes: 1 << 20})
	if err == nil {
		t.Fatal("expected error for missing file")
	}
}

func TestReadFileDirectoryRejected(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	_, err := ReadRepoFile(ReadFileRequest{Root: root, Path: "sub", MaxBytes: 1 << 20})
	if err == nil {
		t.Fatal("expected error when path is a directory")
	}
}

func TestReadFileEmptyRootOrPath(t *testing.T) {
	if _, err := ReadRepoFile(ReadFileRequest{Path: "x", MaxBytes: 1024}); err == nil {
		t.Fatal("expected error for empty root")
	}
	if _, err := ReadRepoFile(ReadFileRequest{Root: t.TempDir(), MaxBytes: 1024}); err == nil {
		t.Fatal("expected error for empty path")
	}
}
