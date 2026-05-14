package mcp

import "fmt"

// Registry collects MCP tools. Insertion order is preserved so
// `tools/list` returns tools in a deterministic order (matches the
// Java side where Spring iterates beans in registration order).
type Registry struct {
	tools []Tool
	seen  map[string]struct{}
}

// NewRegistry returns an empty registry ready for Add calls.
func NewRegistry() *Registry {
	return &Registry{seen: make(map[string]struct{})}
}

// Add registers a tool. Duplicate names, empty names, and nil handlers
// all return an error rather than panicking — RegisterAll wires many
// tools at server boot and one bad entry should not abort the rest.
func (r *Registry) Add(t Tool) error {
	if t.Name == "" {
		return fmt.Errorf("mcp: tool name is empty")
	}
	if t.Handler == nil {
		return fmt.Errorf("mcp: tool %q has nil handler", t.Name)
	}
	if _, dup := r.seen[t.Name]; dup {
		return fmt.Errorf("mcp: tool %q registered twice", t.Name)
	}
	r.seen[t.Name] = struct{}{}
	r.tools = append(r.tools, t)
	return nil
}

// All returns a defensive copy of the registered tools in registration
// order. Mutating the returned slice does not affect the registry.
func (r *Registry) All() []Tool {
	out := make([]Tool, len(r.tools))
	copy(out, r.tools)
	return out
}

// Names returns the registered tool names in registration order. Used
// by `TestRegisterGraphRegistersAllTwentyTools` (and the topology/flow
// analogues) to assert the wiring without inspecting handlers.
func (r *Registry) Names() []string {
	out := make([]string, len(r.tools))
	for i, t := range r.tools {
		out[i] = t.Name
	}
	return out
}
