// Package markup holds Markdown / other markup-language detectors.
package markup

import (
	"path"
	"regexp"
	"strconv"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// MarkdownStructureDetector detects Markdown headings, internal links, and
// emits a MODULE node for the document. Mirrors Java MarkdownStructureDetector.
type MarkdownStructureDetector struct{}

func NewMarkdownStructureDetector() *MarkdownStructureDetector {
	return &MarkdownStructureDetector{}
}

func (MarkdownStructureDetector) Name() string                 { return "markdown_structure" }
func (MarkdownStructureDetector) SupportedLanguages() []string { return []string{"markdown"} }
func (MarkdownStructureDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewMarkdownStructureDetector()) }

var (
	mdHeadingRE  = regexp.MustCompile(`^(#{1,6})\s+(.+)$`)
	mdLinkRE     = regexp.MustCompile(`\[([^\]]+)\]\(([^)]+)\)`)
	mdExternalRE = regexp.MustCompile(`^https?://`)
)

func (d MarkdownStructureDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	lines := strings.Split(text, "\n")

	// Find first H1 to use as module label
	var firstH1 string
	for _, line := range lines {
		if m := mdHeadingRE.FindStringSubmatch(line); len(m) >= 3 {
			if len(m[1]) == 1 {
				firstH1 = strings.TrimSpace(m[2])
				break
			}
		}
	}

	moduleLabel := firstH1
	if moduleLabel == "" {
		moduleLabel = base.FileName(fp)
	}
	moduleID := "md:" + fp
	moduleNode := model.NewCodeNode(moduleID, model.NodeModule, moduleLabel)
	moduleNode.FQN = fp
	moduleNode.FilePath = fp
	moduleNode.LineStart = 1
	moduleNode.Source = "MarkdownStructureDetector"
	nodes = append(nodes, moduleNode)

	// Headings → CONFIG_KEY + CONTAINS edge
	for i, line := range lines {
		m := mdHeadingRE.FindStringSubmatch(line)
		if len(m) < 3 {
			continue
		}
		level := len(m[1])
		heading := strings.TrimSpace(m[2])
		lineNum := i + 1
		headingID := "md:" + fp + ":heading:" + strconv.Itoa(lineNum)

		n := model.NewCodeNode(headingID, model.NodeConfigKey, heading)
		n.FQN = fp + ":heading:" + heading
		n.FilePath = fp
		n.LineStart = lineNum
		n.Source = "MarkdownStructureDetector"
		n.Properties["level"] = level
		n.Properties["text"] = heading
		nodes = append(nodes, n)

		e := model.NewCodeEdge(
			moduleID+":contains:"+headingID, model.EdgeContains, moduleID, headingID,
		)
		e.Source = "MarkdownStructureDetector"
		edges = append(edges, e)
	}

	// Internal links → DEPENDS_ON.
	//
	// Target IDs are resolved relative to fp's directory so a link from
	// docs/a.md → ./b.md becomes the canonical md:docs/b.md target. The
	// referenced file may not have been indexed yet (forward ref), but
	// when it IS indexed its own MarkdownStructureDetector emission
	// creates the same md:<path> node — GraphBuilder's dedup map
	// stitches the two together. Pre-fix, the target was the raw link
	// path (e.g. "./b.md") which never matched anything and the edge
	// got dropped at Snapshot's phantom filter.
	for _, line := range lines {
		for _, m := range mdLinkRE.FindAllStringSubmatch(line, -1) {
			linkText := m[1]
			linkTarget := m[2]
			if mdExternalRE.MatchString(linkTarget) {
				continue
			}
			linkPath := strings.SplitN(linkTarget, "#", 2)[0]
			if linkPath == "" {
				continue
			}
			resolved := resolveMarkdownLink(fp, linkPath)
			targetID := "md:" + resolved
			e := model.NewCodeEdge(
				moduleID+":depends_on:"+targetID, model.EdgeDependsOn, moduleID, targetID,
			)
			e.Source = "MarkdownStructureDetector"
			e.Properties["link_text"] = linkText
			e.Properties["link_target"] = linkPath
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}

// resolveMarkdownLink turns a link from sourceFP's perspective into a
// repo-relative forward-slash path that matches the moduleID format used
// by other Markdown files. Absolute links (leading "/") are returned
// without the slash. Relative links are joined against the source's
// directory and cleaned ("./", "../" resolved). Result is always
// forward-slash-only for cross-platform stability.
func resolveMarkdownLink(sourceFP, target string) string {
	target = strings.TrimSpace(target)
	if strings.HasPrefix(target, "/") {
		return strings.TrimPrefix(target, "/")
	}
	dir := path.Dir(strings.ReplaceAll(sourceFP, "\\", "/"))
	if dir == "." || dir == "" {
		return path.Clean(target)
	}
	return path.Clean(dir + "/" + target)
}
