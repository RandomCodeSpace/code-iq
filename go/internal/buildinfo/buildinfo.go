// Package buildinfo exposes version/commit/date/dirty strings that the release
// pipeline injects via -ldflags -X. When no ldflags are set (e.g. local
// `go build` or `go test`), the defaults below are used. None of the functions
// here panic; --version is required to succeed in all build modes (spec §7.1).
package buildinfo

import "runtime"

// Injected at link time via goreleaser:
//
//   -X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Version={{.Version}}'
//   -X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Commit={{.ShortCommit}}'
//   -X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Date={{.Date}}'
//   -X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Dirty={{.IsGitDirty}}'
var (
	Version = "dev"
	Commit  = "unknown"
	Date    = "unknown"
	Dirty   = "false"
)

// Platform returns "<GOOS>/<GOARCH>", e.g. "linux/amd64".
func Platform() string {
	return runtime.GOOS + "/" + runtime.GOARCH
}

// GoVersion returns the Go toolchain version the binary was built with.
func GoVersion() string {
	return runtime.Version()
}

// DirtyBool parses Dirty ("true"/"false") into a bool. Anything not "true"
// (case-sensitive) is false.
func DirtyBool() bool {
	return Dirty == "true"
}

// Features returns the compile-time feature flags. "kuzu" joined the list in
// phase 2 with the Kuzu wrapper landing under internal/graph.
func Features() []string {
	return []string{"cgo", "kuzu", "sqlite", "tree-sitter"}
}
