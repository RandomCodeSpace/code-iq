package analyzer

import (
	"os"
	"path/filepath"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// writeFile is a tiny helper for these tests — writes content to dir/relPath,
// creating parent directories.
func writeFile(t *testing.T, root, rel, content string) {
	t.Helper()
	full := filepath.Join(root, rel)
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
		t.Fatalf("write %s: %v", rel, err)
	}
}

// serviceByLabel finds a SERVICE node in a result by its label.
func serviceByLabel(t *testing.T, nodes []*model.CodeNode, label string) *model.CodeNode {
	t.Helper()
	for _, n := range nodes {
		if n.Kind == model.NodeService && n.Label == label {
			return n
		}
	}
	t.Fatalf("no service node with label %q (have %d nodes)", label, len(nodes))
	return nil
}

// TestServiceDetectorTwoModules: pom.xml at root + package.json under api/ →
// 2 SERVICE nodes; root extracted from artifactId; api extracted from name.
func TestServiceDetectorTwoModules(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "pom.xml", `<project>
  <artifactId>my-java-app</artifactId>
</project>`)
	writeFile(t, root, "api/package.json", `{"name":"api-server"}`)

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "projectfallback", root)

	if len(r.Nodes) != 2 {
		labels := make([]string, 0, len(r.Nodes))
		for _, n := range r.Nodes {
			labels = append(labels, n.Label)
		}
		sort.Strings(labels)
		t.Fatalf("want 2 service nodes, got %d: %v", len(r.Nodes), labels)
	}
	mavenSvc := serviceByLabel(t, r.Nodes, "my-java-app")
	if got := mavenSvc.Properties["build_tool"]; got != "maven" {
		t.Fatalf("maven svc build_tool = %v, want maven", got)
	}
	if got := mavenSvc.Properties["detected_from"]; got != "pom.xml" {
		t.Fatalf("maven svc detected_from = %v, want pom.xml", got)
	}
	if mavenSvc.Layer != model.LayerBackend {
		t.Fatalf("maven svc layer = %v, want backend", mavenSvc.Layer)
	}
	if mavenSvc.ID != "service:.:my-java-app" {
		t.Fatalf("maven svc id = %q, want service:.:my-java-app", mavenSvc.ID)
	}

	npmSvc := serviceByLabel(t, r.Nodes, "api-server")
	if got := npmSvc.Properties["build_tool"]; got != "npm" {
		t.Fatalf("npm svc build_tool = %v, want npm", got)
	}
	if npmSvc.ID != "service:api:api-server" {
		t.Fatalf("npm svc id = %q, want service:api:api-server", npmSvc.ID)
	}
}

// TestServiceDetectorPathQualifiedIDsBreakCollision: two modules in different
// directories that share the same service name MUST get distinct IDs so Kuzu's
// BulkLoadNodes COPY doesn't abort on duplicate primary key. Pre-fix this
// emitted "service:checkbox" twice and the whole batch was rejected.
func TestServiceDetectorPathQualifiedIDsBreakCollision(t *testing.T) {
	root := t.TempDir()
	// Two distinct Python modules in different folders, both named "checkbox".
	writeFile(t, root, "frontend/widgets/checkbox/pyproject.toml", `[project]
name = "checkbox"
`)
	writeFile(t, root, "backend/components/checkbox/pyproject.toml", `[project]
name = "checkbox"
`)

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "p", root)

	if len(r.Nodes) != 2 {
		t.Fatalf("want 2 service nodes, got %d", len(r.Nodes))
	}

	ids := map[string]bool{}
	for _, n := range r.Nodes {
		if n.Label != "checkbox" {
			t.Errorf("want both labels = checkbox, got %q", n.Label)
		}
		if ids[n.ID] {
			t.Fatalf("duplicate service ID %q — Kuzu BulkLoad would abort here", n.ID)
		}
		ids[n.ID] = true
	}

	want := map[string]bool{
		"service:frontend/widgets/checkbox:checkbox": true,
		"service:backend/components/checkbox:checkbox": true,
	}
	for id := range want {
		if !ids[id] {
			t.Errorf("missing expected ID %q. got: %v", id, ids)
		}
	}
}

// TestServiceDetectorDirectoryFallback: build file with no extractable name →
// service name falls back to directory (or projectDir for root).
func TestServiceDetectorDirectoryFallback(t *testing.T) {
	root := t.TempDir()
	// requirements.txt has no name extractor — falls back to directory.
	writeFile(t, root, "services/payment/requirements.txt", "flask==2.0\n")

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "rootproj", root)

	if len(r.Nodes) != 1 {
		t.Fatalf("want 1 node, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Label != "payment" {
		t.Fatalf("label = %q, want payment", r.Nodes[0].Label)
	}
}

// TestServiceDetectorRootProjectDirFallback: a build file in the project root
// with no extractable name falls back to projectDir, not "".
func TestServiceDetectorRootProjectDirFallback(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "requirements.txt", "flask\n")

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "topproj", root)

	if len(r.Nodes) != 1 {
		t.Fatalf("want 1 node, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Label != "topproj" {
		t.Fatalf("label = %q, want topproj", r.Nodes[0].Label)
	}
}

// TestServiceDetectorPythonPriority: pyproject.toml beats setup.py beats
// requirements.txt beats manage.py in the same directory.
func TestServiceDetectorPythonPriority(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "svc/pyproject.toml", `[project]
name = "winning-name"
`)
	writeFile(t, root, "svc/setup.py", `setup(name="loser1")`)
	writeFile(t, root, "svc/requirements.txt", "flask\n")
	writeFile(t, root, "svc/manage.py", `# django entry`)

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "p", root)

	if len(r.Nodes) != 1 {
		t.Fatalf("want 1 node, got %d", len(r.Nodes))
	}
	sn := r.Nodes[0]
	if sn.Label != "winning-name" {
		t.Fatalf("label = %q, want winning-name", sn.Label)
	}
	if got := sn.Properties["detected_from"]; got != "pyproject.toml" {
		t.Fatalf("detected_from = %v, want pyproject.toml", got)
	}
}

// TestServiceDetectorSupplementalDoesNotOverride: a Dockerfile next to a
// pom.xml does NOT downgrade the build_tool to "docker".
func TestServiceDetectorSupplementalDoesNotOverride(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "Dockerfile", "FROM eclipse-temurin:25\n")
	writeFile(t, root, "pom.xml", `<project><artifactId>real-app</artifactId></project>`)

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "p", root)

	if len(r.Nodes) != 1 {
		t.Fatalf("want 1 node, got %d", len(r.Nodes))
	}
	sn := r.Nodes[0]
	if sn.Label != "real-app" {
		t.Fatalf("label = %q, want real-app", sn.Label)
	}
	if got := sn.Properties["build_tool"]; got != "maven" {
		t.Fatalf("build_tool = %v, want maven (not docker)", got)
	}
}

// TestServiceDetectorSkipsBlacklistedDirs: build files inside node_modules,
// .git, target, build, dist, .venv, vendor MUST be ignored.
func TestServiceDetectorSkipsBlacklistedDirs(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "pom.xml", `<project><artifactId>top</artifactId></project>`)
	// Each of these should be skipped:
	writeFile(t, root, "node_modules/some-pkg/package.json", `{"name":"nope"}`)
	writeFile(t, root, ".git/hooks/package.json", `{"name":"git-nope"}`)
	writeFile(t, root, "target/embedded/pom.xml", `<project><artifactId>tgt-nope</artifactId></project>`)
	writeFile(t, root, "build/output/package.json", `{"name":"build-nope"}`)
	writeFile(t, root, "dist/output/package.json", `{"name":"dist-nope"}`)
	writeFile(t, root, ".venv/lib/pyproject.toml", `name = "venv-nope"`)
	writeFile(t, root, "vendor/something/go.mod", "module foo.example/nope\n")

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "p", root)

	if len(r.Nodes) != 1 {
		labels := make([]string, 0, len(r.Nodes))
		for _, n := range r.Nodes {
			labels = append(labels, n.Label)
		}
		sort.Strings(labels)
		t.Fatalf("want 1 node (only root pom), got %d: %v", len(r.Nodes), labels)
	}
	if r.Nodes[0].Label != "top" {
		t.Fatalf("label = %q, want top", r.Nodes[0].Label)
	}
}

// TestServiceDetectorCsprojSuffix: a *.csproj file triggers the dotnet module
// even though "X.csproj" is not in the exact-filename map.
func TestServiceDetectorCsprojSuffix(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "MyApp/MyApp.csproj", `<Project Sdk="Microsoft.NET.Sdk"></Project>`)

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "p", root)

	if len(r.Nodes) != 1 {
		t.Fatalf("want 1 node, got %d", len(r.Nodes))
	}
	sn := r.Nodes[0]
	if got := sn.Properties["build_tool"]; got != "dotnet" {
		t.Fatalf("build_tool = %v, want dotnet", got)
	}
	if got := sn.Properties["detected_from"]; got != "MyApp.csproj" {
		t.Fatalf("detected_from = %v, want MyApp.csproj", got)
	}
	// Directory-based name fallback (no extractor for .csproj).
	if sn.Label != "MyApp" {
		t.Fatalf("label = %q, want MyApp", sn.Label)
	}
}

// TestServiceDetectorAssignsChildrenAndContainsEdges: nodes get a service
// property + a CONTAINS edge from the deepest matching service.
func TestServiceDetectorAssignsChildrenAndContainsEdges(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "pom.xml", `<project><artifactId>top</artifactId></project>`)
	writeFile(t, root, "api/package.json", `{"name":"api"}`)

	nodes := []*model.CodeNode{
		{ID: "n:1", Kind: model.NodeClass, FilePath: "src/main/java/X.java"},
		{ID: "n:2", Kind: model.NodeEndpoint, FilePath: "api/routes/users.ts"},
		{ID: "n:3", Kind: model.NodeEntity, FilePath: "api/models/user.ts"},
	}

	d := &ServiceDetector{}
	r := d.Detect(nodes, nil, "p", root)

	// 2 services + 3 contains edges.
	if len(r.Nodes) != 2 {
		t.Fatalf("want 2 services, got %d", len(r.Nodes))
	}
	if len(r.Edges) != 3 {
		t.Fatalf("want 3 contains edges, got %d", len(r.Edges))
	}
	// Deepest match: nodes 2+3 land on "api", node 1 lands on "top".
	got := map[string]string{}
	for _, n := range nodes {
		got[n.ID], _ = n.Properties["service"].(string)
	}
	if got["n:1"] != "top" {
		t.Fatalf("n:1 service = %q, want top", got["n:1"])
	}
	if got["n:2"] != "api" {
		t.Fatalf("n:2 service = %q, want api", got["n:2"])
	}
	if got["n:3"] != "api" {
		t.Fatalf("n:3 service = %q, want api", got["n:3"])
	}

	// Counts on services.
	apiSvc := serviceByLabel(t, r.Nodes, "api")
	if got := apiSvc.Properties["endpoint_count"]; got != 1 {
		t.Fatalf("api endpoint_count = %v, want 1", got)
	}
	if got := apiSvc.Properties["entity_count"]; got != 1 {
		t.Fatalf("api entity_count = %v, want 1", got)
	}
	topSvc := serviceByLabel(t, r.Nodes, "top")
	if got := topSvc.Properties["endpoint_count"]; got != 0 {
		t.Fatalf("top endpoint_count = %v, want 0", got)
	}
}

// TestServiceDetectorNoBuildFilesEmitsSingleUnknown: empty repo (no build
// files) → one synthesised "unknown" service using projectDir as the label.
func TestServiceDetectorNoBuildFilesEmitsSingleUnknown(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "README.md", "# nothing here\n")

	d := &ServiceDetector{}
	r := d.Detect(nil, nil, "lonely", root)

	if len(r.Nodes) != 1 {
		t.Fatalf("want 1 node, got %d", len(r.Nodes))
	}
	sn := r.Nodes[0]
	if sn.Label != "lonely" {
		t.Fatalf("label = %q, want lonely", sn.Label)
	}
	if got := sn.Properties["build_tool"]; got != "unknown" {
		t.Fatalf("build_tool = %v, want unknown", got)
	}
}

// TestServiceDetectorDeterminism: two identical runs over the same tree
// produce service node lists with identical labels (order may differ between
// runs but membership and metadata must match).
func TestServiceDetectorDeterminism(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root, "pom.xml", `<project><artifactId>a</artifactId></project>`)
	writeFile(t, root, "svc1/package.json", `{"name":"b"}`)
	writeFile(t, root, "svc2/go.mod", "module example.com/c\n")

	d := &ServiceDetector{}
	collect := func() []string {
		r := d.Detect(nil, nil, "p", root)
		out := make([]string, 0, len(r.Nodes))
		for _, n := range r.Nodes {
			out = append(out, n.Label+"|"+n.Properties["build_tool"].(string))
		}
		sort.Strings(out)
		return out
	}
	a := collect()
	b := collect()
	if len(a) != len(b) {
		t.Fatalf("len mismatch %d vs %d", len(a), len(b))
	}
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("determinism broken at %d: %q vs %q", i, a[i], b[i])
		}
	}
}
