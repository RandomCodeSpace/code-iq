package review

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type stubGraph struct{ note string }

func (s stubGraph) EvidenceForFile(path string) string { return s.note + " for " + path }

func TestService_BuildPrompt_HasFilesAndEvidence(t *testing.T) {
	s := NewService(&Client{Config: Config{}}, stubGraph{note: "stub-evidence"})
	files := []ChangedFile{
		{Path: "a.go", AddedLines: 2, RemovedLines: 1, Hunks: []string{"@@ -1 +1,2 @@\n+x\n+y\n-z\n"}},
	}
	got := s.buildPrompt("base", "head", files)
	if !strings.Contains(got, "Reviewing base..head") {
		t.Errorf("missing header: %q", got)
	}
	if !strings.Contains(got, "## File: a.go") {
		t.Errorf("missing file block")
	}
	if !strings.Contains(got, "stub-evidence for a.go") {
		t.Errorf("missing graph evidence")
	}
	if !strings.Contains(got, "+x") {
		t.Errorf("diff hunk not embedded")
	}
}

// TestService_Review_EndToEnd_FixtureRepo — Plan §3.5 ReviewCommandIT.
// Build a 2-commit git repo in t.TempDir(), point ReviewService at it,
// stub the LLM endpoint, assert the report flows through.
func TestService_Review_EndToEnd_FixtureRepo(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git not available")
	}
	dir := t.TempDir()
	must := func(args ...string) {
		t.Helper()
		c := exec.Command("git", args...)
		c.Dir = dir
		if out, err := c.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	mustEnv := func(env []string, args ...string) {
		t.Helper()
		c := exec.Command("git", args...)
		c.Dir = dir
		c.Env = append(c.Env, env...)
		if out, err := c.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	must("init", "-q", "-b", "main")
	must("config", "user.email", "t@t")
	must("config", "user.name", "T")
	// Initial commit
	if err := writeF(filepath.Join(dir, "foo.go"), "package foo\n"); err != nil {
		t.Fatal(err)
	}
	must("add", ".")
	mustEnv([]string{"GIT_AUTHOR_DATE=2026-05-13T00:00:00", "GIT_COMMITTER_DATE=2026-05-13T00:00:00"}, "commit", "-q", "-m", "init")
	// Second commit
	if err := writeF(filepath.Join(dir, "foo.go"), "package foo\n\nfunc Bar() {}\n"); err != nil {
		t.Fatal(err)
	}
	must("add", ".")
	mustEnv([]string{"GIT_AUTHOR_DATE=2026-05-13T00:01:00", "GIT_COMMITTER_DATE=2026-05-13T00:01:00"}, "commit", "-q", "-m", "add Bar")

	// Stub LLM
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(chatResponse{
			Model: "stub-model",
			Choices: []struct {
				Message chatMessage `json:"message"`
			}{
				{Message: chatMessage{Content: `{"summary":"adds Bar","findings":[]}`}},
			},
		})
	}))
	defer server.Close()

	s := NewService(NewClient(Config{
		Endpoint: server.URL, Model: "stub-model", Timeout: 5 * time.Second,
	}), nil)
	rep, err := s.Review(context.Background(), dir, "HEAD~1", "HEAD", nil)
	if err != nil {
		t.Fatalf("Review: %v", err)
	}
	if rep.Summary != "adds Bar" {
		t.Errorf("summary = %q", rep.Summary)
	}
}

func writeF(path, body string) error {
	return os.WriteFile(path, []byte(body), 0644)
}
