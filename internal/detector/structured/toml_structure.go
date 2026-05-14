package structured

import (
	"sort"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// TomlStructureDetector mirrors Java TomlStructureDetector. Emits a
// CONFIG_FILE for the file + a CONFIG_KEY for each top-level key; map-valued
// keys are flagged with `section=true`.
type TomlStructureDetector struct{}

func NewTomlStructureDetector() *TomlStructureDetector { return &TomlStructureDetector{} }

const propTOML = "toml"

func (TomlStructureDetector) Name() string                        { return "toml_structure" }
func (TomlStructureDetector) SupportedLanguages() []string        { return []string{propTOML} }
func (TomlStructureDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewTomlStructureDetector()) }

func (d TomlStructureDetector) Detect(ctx *detector.Context) *detector.Result {
	fp := ctx.FilePath
	fileID := propTOML + ":" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	nodes = append(nodes, base.BuildFileNode(ctx, propTOML))

	if ctx.ParsedData == nil {
		return detector.ResultOf(nodes, edges)
	}
	data := base.GetMap(ctx.ParsedData, "data")
	if len(data) == 0 {
		return detector.ResultOf(nodes, edges)
	}
	keys := make([]string, 0, len(data))
	for k := range data {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		keyID := propTOML + ":" + fp + ":" + k
		n := model.NewCodeNode(keyID, model.NodeConfigKey, k)
		n.FQN = fp + ":" + k
		n.Module = ctx.ModuleName
		n.FilePath = fp
		n.Confidence = base.StructuredDetectorDefaultConfidence
		if _, isMap := data[k].(map[string]any); isMap {
			n.Properties["section"] = true
		}
		nodes = append(nodes, n)
		e := model.NewCodeEdge(fileID+"->"+keyID, model.EdgeContains, fileID, keyID)
		e.Confidence = base.StructuredDetectorDefaultConfidence
		edges = append(edges, e)
	}
	return detector.ResultOf(nodes, edges)
}
