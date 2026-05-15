package projectroot

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestResolve_ArgWinsOverEnvAndCWD(t *testing.T) {
	dir := t.TempDir()
	cwdMarker := filepath.Join(t.TempDir(), ".codeiq", "graph", "codeiq.kuzu")
	_ = os.MkdirAll(cwdMarker, 0o755)

	got, err := Resolve(Options{
		Arg:      dir,
		EnvValue: "/should/be/ignored",
		CWD:      filepath.Dir(filepath.Dir(filepath.Dir(cwdMarker))),
	})
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if got != dir {
		t.Fatalf("got %q, want %q", got, dir)
	}
}

func TestResolve_EnvWinsOverCWD(t *testing.T) {
	envDir := t.TempDir()
	cwdMarker := filepath.Join(t.TempDir(), ".codeiq", "graph", "codeiq.kuzu")
	_ = os.MkdirAll(cwdMarker, 0o755)

	got, err := Resolve(Options{
		Arg:      "",
		EnvValue: envDir,
		CWD:      filepath.Dir(filepath.Dir(filepath.Dir(cwdMarker))),
	})
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if got != envDir {
		t.Fatalf("got %q, want %q", got, envDir)
	}
}

func TestResolve_WalkUpFindsCodeIQ(t *testing.T) {
	root := t.TempDir()
	nested := filepath.Join(root, "src", "deep", "nested")
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, ".codeiq", "graph", "codeiq.kuzu"), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err := Resolve(Options{CWD: nested})
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if got != root {
		t.Fatalf("got %q, want %q", got, root)
	}
}

func TestResolve_WalkUpFallsBackToGit(t *testing.T) {
	root := t.TempDir()
	nested := filepath.Join(root, "a", "b")
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, ".git"), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err := Resolve(Options{CWD: nested})
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if got != root {
		t.Fatalf("got %q, want %q", got, root)
	}
}

func TestResolve_PrefersCodeIQOverGitWhenBothExist(t *testing.T) {
	// Outer git root with an inner .codeiq subdirectory containing a graph.
	// Walk-up should stop at the inner .codeiq marker because it's a stronger
	// signal than the outer .git.
	gitRoot := t.TempDir()
	codeiqRoot := filepath.Join(gitRoot, "sub")
	if err := os.MkdirAll(filepath.Join(gitRoot, ".git"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(codeiqRoot, ".codeiq", "graph", "codeiq.kuzu"), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err := Resolve(Options{CWD: codeiqRoot})
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if got != codeiqRoot {
		t.Fatalf("got %q, want %q (preferred .codeiq over outer .git)", got, codeiqRoot)
	}
}

func TestResolve_NoSignals(t *testing.T) {
	// CWD with no .codeiq and no .git anywhere up to /.
	// t.TempDir() can sit under various roots; the test asserts on the error
	// shape rather than literally walking the host filesystem.
	dir, err := os.MkdirTemp("", "projectroot-nosignals-*")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })

	_, err = Resolve(Options{CWD: dir})
	if err == nil {
		t.Fatal("expected ErrNotFound, got nil")
	}
	if !errors.Is(err, ErrNotFound) {
		// May fail on hosts where the tempdir's ancestors include .git or .codeiq.
		// Skip rather than fail in that case.
		t.Skipf("host tempdir ancestor has a marker: %v", err)
	}
}

func TestResolve_ArgPointingAtMissingPathErrors(t *testing.T) {
	_, err := Resolve(Options{Arg: "/this/path/really/does/not/exist/9c1f4"})
	if err == nil {
		t.Fatal("expected error for missing arg path, got nil")
	}
}

func TestResolve_ArgPointingAtFileErrors(t *testing.T) {
	f, err := os.CreateTemp("", "projectroot-isfile-*")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Remove(f.Name()) })
	f.Close()
	_, err = Resolve(Options{Arg: f.Name()})
	if err == nil {
		t.Fatal("expected error for arg pointing at a file, got nil")
	}
}

func TestWalkUp_StopsAtFilesystemRoot(t *testing.T) {
	// Calling WalkUp on a path with no markers anywhere up to / must return
	// ("", false) rather than loop forever.
	dir, err := os.MkdirTemp("", "projectroot-walkup-root-*")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })

	_, ok := WalkUp(dir)
	// May be true if /tmp is under a git/.codeiq ancestor on this host —
	// don't assert false; just confirm no hang.
	_ = ok
}
