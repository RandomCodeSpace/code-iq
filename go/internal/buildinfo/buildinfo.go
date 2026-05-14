// Package buildinfo exposes version/commit/date/dirty strings that the release
// pipeline injects via -ldflags -X. When ldflags are not set, an init() fallback
// reads `runtime/debug.BuildInfo` so `go install ...@v0.3.0` and local
// `go build` from a git checkout still produce a binary that reports its
// origin. None of the functions here panic; --version is required to succeed
// in all build modes (spec §7.1).
//
// Resolution priority per field:
//  1. -ldflags -X (release builds via goreleaser) — highest priority
//  2. runtime/debug.BuildInfo — when running `go install …@<tag>` or building
//     from a git checkout. `Main.Version` carries the module tag (or
//     pseudo-version), and `Settings[vcs.*]` carries the commit/time/dirty
//     flag that the toolchain stamps in module-aware builds (Go ≥ 1.18).
//  3. Defaults ("dev" / "unknown") — last resort, e.g. cross-compiled
//     stripped binaries with vcs stamping disabled.
package buildinfo

import (
	"runtime"
	"runtime/debug"
	"sync"
)

// Injected at link time via goreleaser:
//
//	-X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Version={{.Version}}'
//	-X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Commit={{.ShortCommit}}'
//	-X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Date={{.Date}}'
//	-X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Dirty={{.IsGitDirty}}'
//
// init() below populates any var still at its default from
// runtime/debug.BuildInfo so binaries built via `go install` or plain
// `go build` from a git checkout still self-identify.
var (
	Version = "dev"
	Commit  = "unknown"
	Date    = "unknown"
	Dirty   = "false"
)

// hydrateOnce guards the BuildInfo fallback so the second init call within the
// same process (a possible scenario in tests that re-import the package) is a
// no-op.
var hydrateOnce sync.Once

func init() { hydrate() }

// hydrate fills any var still at its default from runtime/debug.BuildInfo.
// Idempotent.
func hydrate() {
	hydrateOnce.Do(func() {
		info, ok := debug.ReadBuildInfo()
		if !ok {
			return
		}
		// Main.Version is the module version. "(devel)" is what the toolchain
		// emits for `go build` without a tagged version — no useful signal.
		if Version == "dev" && info.Main.Version != "" && info.Main.Version != "(devel)" {
			Version = info.Main.Version
		}
		for _, s := range info.Settings {
			switch s.Key {
			case "vcs.revision":
				if Commit == "unknown" && len(s.Value) >= 7 {
					Commit = s.Value[:7]
				}
			case "vcs.time":
				if Date == "unknown" && s.Value != "" {
					Date = s.Value
				}
			case "vcs.modified":
				// Only override the default ("false") when we have a positive
				// signal — never demote a goreleaser-set "true".
				if Dirty == "false" && s.Value == "true" {
					Dirty = "true"
				}
			}
		}
	})
}

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
