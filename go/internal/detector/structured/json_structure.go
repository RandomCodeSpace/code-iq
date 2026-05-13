package structured

import (
	"sort"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// JsonStructureDetector mirrors Java JsonStructureDetector: emits a
// CONFIG_FILE for the file plus a CONFIG_KEY + CONTAINS edge per top-level
// key.
type JsonStructureDetector struct{}

func NewJsonStructureDetector() *JsonStructureDetector { return &JsonStructureDetector{} }

const propJSON = "json"

func (JsonStructureDetector) Name() string                        { return "json_structure" }
func (JsonStructureDetector) SupportedLanguages() []string        { return []string{propJSON} }
func (JsonStructureDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewJsonStructureDetector()) }

func (d JsonStructureDetector) Detect(ctx *detector.Context) *detector.Result {
	fp := ctx.FilePath
	fileID := propJSON + ":" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	nodes = append(nodes, base.BuildFileNode(ctx, propJSON))

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
		base.AddKeyNode(fileID, fp, k, propJSON, ctx, &nodes, &edges)
	}
	return detector.ResultOf(nodes, edges)
}
