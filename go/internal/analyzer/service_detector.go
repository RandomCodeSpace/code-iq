package analyzer

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// ServiceDetector walks the filesystem for build files (30+ build systems)
// and emits SERVICE nodes with CONTAINS edges to their child nodes. Mirrors
// src/main/java/io/github/randomcodespace/iq/analyzer/ServiceDetector.java.
//
// Filesystem-driven by design — not all build files produce CodeNodes during
// index, so we cannot rely on the node list alone.
type ServiceDetector struct{}

// ServiceDetectionResult holds the new SERVICE nodes and the CONTAINS edges
// produced by a Detect call. The Detect call also mutates the incoming
// `nodes` slice in place by stamping each node's `service` property.
type ServiceDetectionResult struct {
	Nodes []*model.CodeNode
	Edges []*model.CodeEdge
}

// buildFiles maps exact build-file filenames to their build tool name.
// Mirrors BUILD_FILES in ServiceDetector.java lines 60-120.
var buildFiles = map[string]string{
	// Java/JVM
	"pom.xml":             "maven",
	"build.gradle":        "gradle",
	"build.gradle.kts":    "gradle",
	"settings.gradle":     "gradle",
	"settings.gradle.kts": "gradle",
	"build.xml":           "ant",
	"build.sbt":           "sbt",
	"project.clj":         "leiningen",
	// JS/TS
	"package.json": "npm",
	"deno.json":    "deno",
	"deno.jsonc":   "deno",
	// Go
	"go.mod": "go",
	// Rust
	"Cargo.toml": "cargo",
	// Python
	"pyproject.toml":   "python",
	"setup.py":         "python",
	"setup.cfg":        "python",
	"Pipfile":          "python",
	"requirements.txt": "python",
	"manage.py":        "django",
	// Ruby
	"Gemfile": "ruby",
	// PHP
	"composer.json": "php",
	// .NET (csproj etc. handled by suffix below)
	"Directory.Build.props": "dotnet",
	// Swift
	"Package.swift": "swift",
	// Elixir
	"mix.exs": "elixir",
	// Dart / Flutter
	"pubspec.yaml": "dart",
	// Haskell
	"stack.yaml": "haskell",
	// Zig
	"build.zig": "zig",
	// OCaml
	"dune-project": "ocaml",
	// R
	"DESCRIPTION": "r",
	// Bazel
	"BUILD":       "bazel",
	"BUILD.bazel": "bazel",
	// Mono-repo orchestrators (supplemental, like Docker)
	"nx.json":    "nx",
	"lerna.json": "lerna",
	"turbo.json": "turbo",
	"rush.json":  "rush",
	// Docker (supplemental — doesn't override real build tools)
	"Dockerfile":          "docker",
	"docker-compose.yml":  "docker",
	"docker-compose.yaml": "docker",
	"compose.yml":         "docker",
	"compose.yaml":        "docker",
}

// suffixBuildFiles handles cases where the filename ends with a specific
// suffix (e.g. MyApp.csproj). Order does not matter — first match wins per
// directory.
var suffixBuildFiles = []struct {
	suffix, tool string
}{
	{".csproj", "dotnet"},
	{".fsproj", "dotnet"},
	{".vbproj", "dotnet"},
	{".gemspec", "ruby"},
	{".cabal", "haskell"},
	{".nimble", "nim"},
}

// supplementalTools are signals (docker, monorepo orchestrators) that don't
// override a real build tool already detected in the same directory.
var supplementalTools = map[string]struct{}{
	"docker": {}, "nx": {}, "lerna": {}, "turbo": {}, "rush": {},
}

// pythonBuildFiles is the priority order: index 0 wins.
// pyproject.toml > setup.py > requirements.txt > manage.py.
var pythonBuildFiles = []string{
	"pyproject.toml", "setup.py", "requirements.txt", "manage.py",
}

// skipDirs are directory names pruned entirely during the filesystem walk.
var skipDirs = map[string]struct{}{
	"node_modules": {}, ".git": {}, "target": {}, "build": {},
	"dist": {}, ".gradle": {}, ".idea": {}, ".vscode": {},
	"__pycache__": {}, ".tox": {}, ".eggs": {}, "venv": {},
	".venv": {}, "vendor": {}, ".bundle": {}, "_build": {}, "deps": {},
}

// moduleInfo is per-directory build-file bookkeeping.
type moduleInfo struct{ dir, tool, file string }

// Detect walks `projectRoot`, identifies module boundaries, creates SERVICE
// nodes and CONTAINS edges. `projectDir` is used as the fallback service
// name for the root module when no name can be extracted from the build
// file.
//
// As a side effect, each node in `nodes` whose filePath falls under a
// detected module has its `service` property set to that service's label.
func (sd *ServiceDetector) Detect(nodes []*model.CodeNode, edges []*model.CodeEdge,
	projectDir string, projectRoot string) ServiceDetectionResult {
	modules := map[string]moduleInfo{}
	if projectRoot != "" {
		sd.walkFilesystem(projectRoot, modules)
	}
	if len(modules) == 0 {
		modules[""] = moduleInfo{dir: "", tool: "unknown", file: ""}
	}

	// Sort dirs deepest-first so longer prefixes match before their parent
	// modules during child assignment.
	dirs := make([]string, 0, len(modules))
	for k := range modules {
		dirs = append(dirs, k)
	}
	sort.Slice(dirs, func(i, j int) bool {
		if len(dirs[i]) != len(dirs[j]) {
			return len(dirs[i]) > len(dirs[j])
		}
		return dirs[i] < dirs[j]
	})

	serviceNodes := make([]*model.CodeNode, 0, len(dirs))
	serviceByDir := map[string]*model.CodeNode{}
	for _, dir := range dirs {
		info := modules[dir]
		name := sd.extractServiceName(dir, info, projectDir, projectRoot)
		sn := &model.CodeNode{
			ID:          "service:" + name,
			Kind:        model.NodeService,
			Label:       name,
			FilePath:    ifBlank(dir, "."),
			Layer:       model.LayerBackend,
			Confidence:  model.ConfidenceLexical,
			Annotations: []string{},
			Properties: map[string]any{
				"build_tool":     info.tool,
				"detected_from":  info.file,
				"endpoint_count": 0,
				"entity_count":   0,
			},
		}
		serviceNodes = append(serviceNodes, sn)
		serviceByDir[dir] = sn
	}

	endpointCounts := map[string]int{}
	entityCounts := map[string]int{}
	var newEdges []*model.CodeEdge
	for _, n := range nodes {
		p := n.FilePath
		var matchDir string
		found := false
		for _, dir := range dirs {
			if dir == "" || strings.HasPrefix(p, dir+"/") || p == dir {
				matchDir = dir
				found = true
				break
			}
		}
		if !found {
			if _, ok := modules[""]; ok {
				matchDir = ""
			} else {
				continue
			}
		}
		sn := serviceByDir[matchDir]
		if sn == nil {
			continue
		}
		if n.Properties == nil {
			n.Properties = map[string]any{}
		}
		n.Properties["service"] = sn.Label
		newEdges = append(newEdges, &model.CodeEdge{
			ID:         fmt.Sprintf("edge:service:%s:contains:%s", sn.Label, n.ID),
			Kind:       model.EdgeContains,
			SourceID:   sn.ID,
			TargetID:   n.ID,
			Confidence: model.ConfidenceLexical,
			Properties: map[string]any{},
		})
		switch n.Kind {
		case model.NodeEndpoint:
			endpointCounts[sn.Label]++
		case model.NodeEntity:
			entityCounts[sn.Label]++
		}
	}
	for _, sn := range serviceNodes {
		sn.Properties["endpoint_count"] = endpointCounts[sn.Label]
		sn.Properties["entity_count"] = entityCounts[sn.Label]
	}
	return ServiceDetectionResult{Nodes: serviceNodes, Edges: newEdges}
}

func ifBlank(v, fallback string) string {
	if v == "" {
		return fallback
	}
	return v
}

// walkFilesystem traverses `root` and registers a moduleInfo per directory
// that has a recognised build file. Skipped directories (skipDirs) are
// pruned via fs.SkipDir.
func (sd *ServiceDetector) walkFilesystem(root string, modules map[string]moduleInfo) {
	_ = filepath.WalkDir(root, func(p string, ent fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if ent.IsDir() {
			// Don't prune the root itself — its name might match a skipDir
			// (e.g. someone running on /tmp/.venv) but we still want to
			// scan it.
			if p == root {
				return nil
			}
			if _, skip := skipDirs[ent.Name()]; skip {
				return fs.SkipDir
			}
			return nil
		}
		rel, err := filepath.Rel(root, filepath.Dir(p))
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if rel == "." {
			rel = ""
		}
		name := ent.Name()
		// Suffix-based first (csproj etc.)
		for _, s := range suffixBuildFiles {
			if strings.HasSuffix(name, s.suffix) {
				if _, present := modules[rel]; !present {
					modules[rel] = moduleInfo{dir: rel, tool: s.tool, file: name}
				}
				return nil
			}
		}
		tool, ok := buildFiles[name]
		if !ok {
			return nil
		}
		sd.registerModule(modules, rel, tool, name)
		return nil
	})
}

// registerModule mirrors the priority rules at ServiceDetector.java lines
// 391-416: supplemental tools don't override real ones; python files have a
// strict priority order; gradle settings.* doesn't override build.gradle.
func (sd *ServiceDetector) registerModule(modules map[string]moduleInfo, dir, tool, file string) {
	existing, present := modules[dir]
	if _, suppl := supplementalTools[tool]; suppl && present {
		return
	}
	if present && isPython(tool) && !isPython(existing.tool) {
		return
	}
	if present && isPython(tool) && isPython(existing.tool) {
		if pythonPriority(file) >= pythonPriority(existing.file) {
			return
		}
	}
	if tool == "gradle" && present && existing.tool == "gradle" &&
		strings.HasPrefix(file, "settings.") {
		return
	}
	modules[dir] = moduleInfo{dir: dir, tool: tool, file: file}
}

func isPython(t string) bool { return t == "python" || t == "django" }

func pythonPriority(file string) int {
	for i, f := range pythonBuildFiles {
		if f == file {
			return i
		}
	}
	return len(pythonBuildFiles)
}

// extractServiceName tries the build file content first, then falls back to
// directory-based naming. Matches Java extractServiceName.
func (sd *ServiceDetector) extractServiceName(dir string, info moduleInfo,
	projectDir, projectRoot string) string {
	if projectRoot != "" && info.file != "" {
		if name := sd.readNameFromBuildFile(projectRoot, dir, info); name != "" {
			return name
		}
	}
	if dir == "" {
		if projectDir != "" {
			return projectDir
		}
		return "root"
	}
	if idx := strings.LastIndex(dir, "/"); idx >= 0 {
		return dir[idx+1:]
	}
	return dir
}

// readNameFromBuildFile reads `projectRoot/dir/info.file` and runs the
// per-tool extractor. Returns "" on read failure or no match.
func (sd *ServiceDetector) readNameFromBuildFile(root, dir string, info moduleInfo) string {
	full := filepath.Join(root, dir, info.file)
	content, err := os.ReadFile(full)
	if err != nil {
		return ""
	}
	s := string(content)
	switch info.tool {
	case "maven":
		return extractFromPom(s)
	case "npm":
		return extractFromPackageJSON(s)
	case "go":
		return extractFromGoMod(s)
	case "cargo":
		return matchFirst(reCargoName, s)
	case "python":
		if info.file == "pyproject.toml" {
			return matchFirst(rePyProjectName, s)
		}
		if info.file == "setup.py" {
			return matchFirst(reSetupPyName, s)
		}
		return ""
	case "gradle":
		if strings.HasPrefix(info.file, "settings.") {
			return matchFirst(reGradleSettingsName, s)
		}
		return ""
	case "sbt":
		return matchFirst(reSbtName, s)
	case "php":
		name := matchFirst(reComposerName, s)
		if i := strings.LastIndex(name, "/"); i >= 0 {
			name = name[i+1:]
		}
		return name
	case "elixir":
		return matchFirst(reMixAppName, s)
	case "dart":
		return matchFirst(rePubspecName, s)
	}
	return ""
}

var (
	rePomArtifactID      = regexp.MustCompile(`<artifactId>\s*([^<]+?)\s*</artifactId>`)
	rePackageJSONName    = regexp.MustCompile(`"name"\s*:\s*"([^"]+)"`)
	reGoModModule        = regexp.MustCompile(`(?m)^module\s+(\S+)`)
	reCargoName          = regexp.MustCompile(`(?m)^name\s*=\s*"([^"]+)"`)
	rePyProjectName      = regexp.MustCompile(`(?m)^name\s*=\s*"([^"]+)"`)
	reSetupPyName        = regexp.MustCompile(`name\s*=\s*['"]([^'"]+)['"]`)
	reGradleSettingsName = regexp.MustCompile(`rootProject\.name\s*=\s*['"]([^'"]+)['"]`)
	reSbtName            = regexp.MustCompile(`name\s*:=\s*"([^"]+)"`)
	reComposerName       = regexp.MustCompile(`"name"\s*:\s*"([^"]+)"`)
	reMixAppName         = regexp.MustCompile(`app:\s*:([\w]+)`)
	rePubspecName        = regexp.MustCompile(`(?m)^name:\s*(\S+)`)
)

func extractFromPom(s string) string {
	search := s
	if idx := strings.Index(s, "</parent>"); idx > 0 {
		search = s[idx:]
	}
	return matchFirst(rePomArtifactID, search)
}

func extractFromPackageJSON(s string) string {
	name := matchFirst(rePackageJSONName, s)
	if name == "" {
		return ""
	}
	// Validate as JSON before trusting (cheap, gives same result on bad input).
	var m map[string]any
	_ = json.Unmarshal([]byte(s), &m)
	if i := strings.LastIndex(name, "/"); i >= 0 {
		name = name[i+1:]
	}
	return name
}

func extractFromGoMod(s string) string {
	mod := matchFirst(reGoModModule, s)
	if i := strings.LastIndex(mod, "/"); i >= 0 {
		mod = mod[i+1:]
	}
	return mod
}

func matchFirst(re *regexp.Regexp, s string) string {
	m := re.FindStringSubmatch(s)
	if len(m) < 2 {
		return ""
	}
	return strings.TrimSpace(m[1])
}
