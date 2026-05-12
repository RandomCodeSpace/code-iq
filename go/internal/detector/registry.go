package detector

import (
	"fmt"
	"sort"
	"sync"
)

// Registry holds detectors registered at process startup. Lookups by language
// return deterministic (name-sorted) slices so detector iteration order is
// stable across runs — determinism is non-negotiable per CLAUDE.md.
type Registry struct {
	mu        sync.RWMutex
	byName    map[string]Detector
	byLang    map[string][]Detector
	allSorted []Detector
}

// NewRegistry returns an empty registry.
func NewRegistry() *Registry {
	return &Registry{
		byName: make(map[string]Detector),
		byLang: make(map[string][]Detector),
	}
}

// Default is the process-wide default registry. Detector init() funcs call
// RegisterDefault to add themselves.
var Default = NewRegistry()

// RegisterDefault registers d with the process-wide Default registry. Panics
// on duplicate name (programmer error — must be caught at boot, not silently
// swallowed).
func RegisterDefault(d Detector) {
	Default.Register(d)
}

// Register adds d to this registry. Panics on duplicate name.
func (r *Registry) Register(d Detector) {
	r.mu.Lock()
	defer r.mu.Unlock()
	name := d.Name()
	if _, exists := r.byName[name]; exists {
		panic(fmt.Sprintf("detector: duplicate registration: %q", name))
	}
	r.byName[name] = d
	for _, lang := range d.SupportedLanguages() {
		r.byLang[lang] = append(r.byLang[lang], d)
		sort.Slice(r.byLang[lang], func(i, j int) bool {
			return r.byLang[lang][i].Name() < r.byLang[lang][j].Name()
		})
	}
	r.allSorted = append(r.allSorted, d)
	sort.Slice(r.allSorted, func(i, j int) bool {
		return r.allSorted[i].Name() < r.allSorted[j].Name()
	})
}

// For returns detectors registered for lang, sorted by name. Returns nil
// (not empty slice) when no detector matches.
func (r *Registry) For(lang string) []Detector {
	r.mu.RLock()
	defer r.mu.RUnlock()
	src := r.byLang[lang]
	if len(src) == 0 {
		return nil
	}
	out := make([]Detector, len(src))
	copy(out, src)
	return out
}

// All returns every registered detector, sorted by name.
func (r *Registry) All() []Detector {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Detector, len(r.allSorted))
	copy(out, r.allSorted)
	return out
}

// ByName fetches a single detector by its name. Returns nil if absent.
func (r *Registry) ByName(name string) Detector {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.byName[name]
}
