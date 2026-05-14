package structured

import (
	"sort"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// YamlStructureDetector mirrors Java YamlStructureDetector: emits a
// CONFIG_FILE node for the file plus a CONFIG_KEY node + CONTAINS edge for
// each top-level key (across all documents for multi-doc YAML).
type YamlStructureDetector struct{}

func NewYamlStructureDetector() *YamlStructureDetector { return &YamlStructureDetector{} }

const propYaml = "yaml"

func (YamlStructureDetector) Name() string                        { return "yaml_structure" }
func (YamlStructureDetector) SupportedLanguages() []string        { return []string{propYaml} }
func (YamlStructureDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewYamlStructureDetector()) }

func (d YamlStructureDetector) Detect(ctx *detector.Context) *detector.Result {
	fp := ctx.FilePath
	fileID := propYaml + ":" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	// Always emit the file node (matches Java behaviour).
	nodes = append(nodes, base.BuildFileNode(ctx, propYaml))

	if ctx.ParsedData == nil {
		return detector.ResultOf(nodes, edges)
	}
	pd := ctx.ParsedData
	docType, _ := pd["type"].(string)

	// Collect top-level keys deterministically (sorted).
	keySet := map[string]bool{}
	switch docType {
	case "yaml_multi":
		for _, doc := range base.GetList(pd, "documents") {
			for k := range base.AsMap(doc) {
				keySet[k] = true
			}
		}
	case "yaml":
		for k := range base.GetMap(pd, "data") {
			keySet[k] = true
		}
	}
	keys := make([]string, 0, len(keySet))
	for k := range keySet {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		base.AddKeyNode(fileID, fp, k, propYaml, ctx, &nodes, &edges)
	}
	return detector.ResultOf(nodes, edges)
}
