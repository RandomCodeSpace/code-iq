module github.com/randomcodespace/codeiq

// Minimum Go version that can compile this module — clamped at 1.25.0
// because github.com/modelcontextprotocol/go-sdk v1.6 (Phase 3, MCP
// server) declares `go 1.25.0`. `go mod tidy` rewrites anything lower
// back to 1.25.0. Bumping out of 1.25 should wait until a release of
// that SDK that targets 1.26+.
go 1.25.0

// Actual build toolchain. Pinned to 1.25.7 — 1.26+ isn't on enough
// developer machines yet. CI pins the same version (.github/workflows/
// go-ci.yml + go-parity.yml).
toolchain go1.25.10

require github.com/mattn/go-sqlite3 v1.14.44

require (
	github.com/google/uuid v1.6.0
	github.com/kuzudb/go-kuzu v0.11.3
	github.com/modelcontextprotocol/go-sdk v1.6.0
	github.com/pmezard/go-difflib v1.0.0
	github.com/smacker/go-tree-sitter v0.0.0-20240827094217-dd81d9e9be82
	github.com/spf13/cobra v1.10.2
	github.com/spf13/pflag v1.0.10
	gopkg.in/yaml.v3 v3.0.1
)

require (
	github.com/google/jsonschema-go v0.4.3 // indirect
	github.com/inconshreveable/mousetrap v1.1.0 // indirect
	github.com/segmentio/asm v1.1.3 // indirect
	github.com/segmentio/encoding v0.5.4 // indirect
	github.com/shopspring/decimal v1.4.0 // indirect
	github.com/yosida95/uritemplate/v3 v3.0.2 // indirect
	golang.org/x/oauth2 v0.35.0 // indirect
	golang.org/x/sys v0.41.0 // indirect
)
