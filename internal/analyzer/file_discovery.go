package analyzer

import (
	"bytes"
	"io/fs"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/parser"
)

// DefaultExcludeDirs mirrors the Java FileDiscovery.DEFAULT_EXCLUDES set.
var DefaultExcludeDirs = map[string]bool{
	"node_modules": true, "build": true, "target": true, "dist": true,
	"out": true, "bin": true, "obj": true,
	".git": true, ".svn": true, ".idea": true, ".vscode": true,
	".eclipse": true, ".settings": true,
	"__pycache__": true, "venv": true, ".venv": true, ".tox": true,
	".mypy_cache": true, ".pytest_cache": true, ".eggs": true,
	".gradle": true, ".mvn": true,
	"bower_components": true, ".next": true, ".nuxt": true, "coverage": true,
	".nyc_output": true, ".parcel-cache": true, ".turbo": true, ".cache": true,
	"vendor":  true,
	".codeiq": true,
}

// DiscoveredFile is one file discovered for analysis.
type DiscoveredFile struct {
	AbsPath  string
	RelPath  string // forward-slash, relative to root
	Language parser.Language
	Ext      string
}

// FileDiscovery walks a repo and emits language-tagged files. Uses
// `git ls-files -co --exclude-standard` first; falls back to fs walk.
type FileDiscovery struct{}

// NewFileDiscovery returns a discovery instance.
func NewFileDiscovery() *FileDiscovery { return &FileDiscovery{} }

// Discover walks root and returns files sorted by RelPath.
func (d *FileDiscovery) Discover(root string) ([]DiscoveredFile, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	files, err := d.gitLsFiles(abs)
	if err != nil || len(files) == 0 {
		files, err = d.walkFS(abs)
		if err != nil {
			return nil, err
		}
	}
	sort.Slice(files, func(i, j int) bool { return files[i].RelPath < files[j].RelPath })
	return files, nil
}

func (d *FileDiscovery) gitLsFiles(root string) ([]DiscoveredFile, error) {
	cmd := exec.Command("git", "-C", root, "ls-files", "-co", "--exclude-standard")
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil {
		return nil, err
	}
	var files []DiscoveredFile
	for _, line := range strings.Split(out.String(), "\n") {
		rel := strings.TrimSpace(line)
		if rel == "" {
			continue
		}
		df, ok := makeDiscoveredFile(root, rel)
		if !ok {
			continue
		}
		files = append(files, df)
	}
	return files, nil
}

func (d *FileDiscovery) walkFS(root string) ([]DiscoveredFile, error) {
	var files []DiscoveredFile
	err := filepath.WalkDir(root, func(path string, dent fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if dent.IsDir() {
			if DefaultExcludeDirs[dent.Name()] {
				return filepath.SkipDir
			}
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		rel = filepath.ToSlash(rel)
		df, ok := makeDiscoveredFile(root, rel)
		if !ok {
			return nil
		}
		files = append(files, df)
		return nil
	})
	return files, err
}

func makeDiscoveredFile(root, rel string) (DiscoveredFile, bool) {
	rel = filepath.ToSlash(rel)
	for _, seg := range strings.Split(rel, "/") {
		if DefaultExcludeDirs[seg] {
			return DiscoveredFile{}, false
		}
	}
	ext := strings.ToLower(filepath.Ext(rel))
	lang := parser.LanguageFromExtension(ext)
	if lang == parser.LanguageUnknown {
		return DiscoveredFile{}, false
	}
	return DiscoveredFile{
		AbsPath:  filepath.Join(root, filepath.FromSlash(rel)),
		RelPath:  rel,
		Language: lang,
		Ext:      ext,
	}, true
}
