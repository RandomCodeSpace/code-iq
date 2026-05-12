package cli

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/buildinfo"
)

func TestVersionTextFormat(t *testing.T) {
	var buf bytes.Buffer
	if err := printVersion(&buf, false); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.HasPrefix(out, "codeiq "+buildinfo.Version) {
		t.Errorf("expected prefix \"codeiq %s\", got %q", buildinfo.Version, out)
	}
	for _, want := range []string{"commit:", "built:", "go:", "platform:", "features:"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing line %q in output:\n%s", want, out)
		}
	}
}

func TestVersionJSONFormat(t *testing.T) {
	var buf bytes.Buffer
	if err := printVersion(&buf, true); err != nil {
		t.Fatal(err)
	}
	var obj map[string]any
	if err := json.Unmarshal(buf.Bytes(), &obj); err != nil {
		t.Fatalf("invalid JSON: %v\n%s", err, buf.String())
	}
	wantKeys := []string{"version", "commit", "commit_dirty", "built", "go_version", "platform", "features"}
	for _, k := range wantKeys {
		if _, ok := obj[k]; !ok {
			t.Errorf("missing JSON key %q in %v", k, obj)
		}
	}
}

func TestVersionCommitDirtyMarker(t *testing.T) {
	orig := buildinfo.Dirty
	t.Cleanup(func() { buildinfo.Dirty = orig })

	buildinfo.Dirty = "true"
	var buf bytes.Buffer
	_ = printVersion(&buf, false)
	if !strings.Contains(buf.String(), "(dirty)") {
		t.Errorf("dirty marker missing when Dirty=true:\n%s", buf.String())
	}
	buildinfo.Dirty = "false"
	buf.Reset()
	_ = printVersion(&buf, false)
	if !strings.Contains(buf.String(), "(clean)") {
		t.Errorf("clean marker missing when Dirty=false:\n%s", buf.String())
	}
}
