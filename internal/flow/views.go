// Package flow generates architecture flow diagrams from the codeiq graph.
//
// Mirrors src/main/java/io/github/randomcodespace/iq/flow/ — `View` is the
// enum of supported diagram views, `Engine` queries the graph, the
// builders in this file produce a `Diagram` per view, and `Renderer` emits
// Mermaid / JSON / DOT / YAML.
//
// Five views match the Java side exactly:
//   - overview: 4 subgraphs (CI, Infrastructure, Application, Security)
//   - ci:       CI/CD pipeline detail (workflows, jobs, triggers)
//   - deploy:   K8s / Docker / Terraform topology
//   - runtime:  endpoints / entities / messaging grouped by layer
//   - auth:     guards / endpoints / protection coverage
package flow

// View is a single supported flow view. The string value is the canonical
// identifier used in CLI args, MCP tool params, and stored output paths.
type View string

const (
	// ViewOverview is the high-level architecture overview.
	ViewOverview View = "overview"
	// ViewCI is the CI/CD pipeline detail view.
	ViewCI View = "ci"
	// ViewDeploy is the deployment topology view.
	ViewDeploy View = "deploy"
	// ViewRuntime is the runtime architecture view.
	ViewRuntime View = "runtime"
	// ViewAuth is the auth / security view.
	ViewAuth View = "auth"
)

// AllViews returns every supported view in declaration order. The order
// matches the Java side `FlowEngine.AVAILABLE_VIEWS` constant.
func AllViews() []View {
	return []View{ViewOverview, ViewCI, ViewDeploy, ViewRuntime, ViewAuth}
}

// IsKnownView reports whether the supplied string identifies a built-in
// view. Used by the `flow` CLI and the `generate_flow` MCP tool to reject
// typos before opening the graph.
func IsKnownView(s string) bool {
	switch View(s) {
	case ViewOverview, ViewCI, ViewDeploy, ViewRuntime, ViewAuth:
		return true
	}
	return false
}
