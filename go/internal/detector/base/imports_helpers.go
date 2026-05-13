// Anchor helpers — Plan §1.2 follow-on.
//
// Many regex detectors emit cross-file "imports" / "depends_on" edges
// using the source file path and the imported name as endpoints. Both
// endpoints were free-form strings with no matching CodeNode, so every
// such edge got dropped at GraphBuilder.Snapshot's phantom filter.
//
// EnsureFileAnchor and EnsureExternalAnchor materialize anchor nodes so
// the edges survive. The GraphBuilder dedup map collapses the per-file
// and per-external nodes across files at zero extra cost (every Python
// file importing "requests" gets one shared py:external:requests node).
package base

import (
	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// EnsureFileAnchor returns the canonical ID of the file-as-module anchor
// node for ctx.FilePath and appends the node to nodes once. Caller must
// pass the same `seen` map across invocations within a single detector
// run (or nil for one-shot calls).
//
// langPrefix scopes the anchor namespace ("py" for Python, "ts" for
// TypeScript, etc.) so cross-language detectors don't collide on the
// same path.
//
// Detector source/confidence are stamped onto the anchor — pick a
// confidence that's at-or-below the actual emission detector so the
// merge rule (higher wins) doesn't accidentally demote a high-confidence
// emission later.
func EnsureFileAnchor(ctx *detector.Context, langPrefix, detectorName string, conf model.Confidence, nodes *[]*model.CodeNode, seen map[string]bool) string {
	id := langPrefix + ":file:" + ctx.FilePath
	if seen != nil && seen[id] {
		return id
	}
	if seen != nil {
		seen[id] = true
	}
	n := model.NewCodeNode(id, model.NodeModule, ctx.FilePath)
	n.FilePath = ctx.FilePath
	n.Source = detectorName
	n.Confidence = conf
	n.Properties["module_type"] = langPrefix + "_file"
	*nodes = append(*nodes, n)
	return id
}

// EnsureExternalAnchor returns the canonical ID of an external module /
// package / image target and appends it to nodes once per unique name.
// idPrefix scopes the namespace ("py:external", "rust:external",
// "docker:image", etc.).
func EnsureExternalAnchor(name, idPrefix, detectorName string, conf model.Confidence, nodes *[]*model.CodeNode, seen map[string]bool) string {
	id := idPrefix + ":" + name
	if seen != nil && seen[id] {
		return id
	}
	if seen != nil {
		seen[id] = true
	}
	n := model.NewCodeNode(id, model.NodeExternal, name)
	n.Source = detectorName
	n.Confidence = conf
	n.Properties["module"] = name
	*nodes = append(*nodes, n)
	return id
}
