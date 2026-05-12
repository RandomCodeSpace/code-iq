package linker

import (
	"fmt"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// repoSuffixes is the ordered list of suffixes matched on REPOSITORY labels.
// First match wins, so the order matters: `Repository` before `Repo` so that
// `UserRepository` strips → `User` (not `UserRepository` minus `Repo` →
// `UserRepository`).
var repoSuffixes = []string{"Repository", "Repo", "Dao", "DAO"}

// EntityLinker emits QUERIES edges from REPOSITORY nodes to the ENTITY nodes
// they manage, matched by naming convention (e.g. `UserRepository` →
// `User`, `OrderDao` → `Order`).
//
// Mirrors src/main/java/io/github/randomcodespace/iq/analyzer/linker/EntityLinker.java
// (lines 33-98).
type EntityLinker struct{}

// NewEntityLinker returns a stateless linker.
func NewEntityLinker() *EntityLinker { return &EntityLinker{} }

// Link iterates repositories and matches them to entities by simple-name
// (case-insensitive) after stripping the longest recognised suffix. Skips
// repositories that already have an outbound QUERIES edge to the candidate
// entity to avoid duplicates with what detectors emitted.
func (l *EntityLinker) Link(nodes []*model.CodeNode, edges []*model.CodeEdge) Result {
	var entities, repositories []*model.CodeNode
	for _, n := range nodes {
		switch n.Kind {
		case model.NodeEntity:
			entities = append(entities, n)
		case model.NodeRepository:
			repositories = append(repositories, n)
		}
	}
	if len(entities) == 0 || len(repositories) == 0 {
		return Result{}
	}

	entityByName := make(map[string]*model.CodeNode)
	for _, e := range entities {
		entityByName[strings.ToLower(e.Label)] = e
		if e.FQN != "" {
			simple := e.FQN
			if idx := strings.LastIndex(simple, "."); idx >= 0 {
				simple = simple[idx+1:]
			}
			entityByName[strings.ToLower(simple)] = e
		}
	}

	existing := map[string]struct{}{}
	for _, e := range edges {
		if e.Kind == model.EdgeQueries {
			existing[e.SourceID+"->"+e.TargetID] = struct{}{}
		}
	}

	// Iterate repositories in ID order for determinism (Java side relies on
	// the GraphBuilder snapshot already being sorted; we don't, so sort here).
	sort.Slice(repositories, func(i, j int) bool { return repositories[i].ID < repositories[j].ID })

	var newEdges []*model.CodeEdge
	for _, repo := range repositories {
		for _, suf := range repoSuffixes {
			if !strings.HasSuffix(repo.Label, suf) {
				continue
			}
			base := strings.ToLower(repo.Label[:len(repo.Label)-len(suf)])
			ent, ok := entityByName[base]
			if !ok {
				break // first matching suffix wins, even if entity missing
			}
			key := repo.ID + "->" + ent.ID
			if _, dup := existing[key]; dup {
				break
			}
			newEdges = append(newEdges, &model.CodeEdge{
				ID:         fmt.Sprintf("entity-link:%s->%s", repo.ID, ent.ID),
				Kind:       model.EdgeQueries,
				SourceID:   repo.ID,
				TargetID:   ent.ID,
				Properties: map[string]any{"inferred": true},
			})
			break
		}
	}
	return Result{Edges: newEdges}
}
