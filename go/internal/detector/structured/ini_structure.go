package structured

import (
	"sort"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// IniStructureDetector mirrors Java IniStructureDetector: emits a
// CONFIG_FILE for the file + a CONFIG_KEY for each section, then a
// CONFIG_KEY for every key within each section. CONTAINS edges: file →
// section, section → key.
type IniStructureDetector struct{}

func NewIniStructureDetector() *IniStructureDetector { return &IniStructureDetector{} }

const propINI = "ini"

func (IniStructureDetector) Name() string                        { return "ini_structure" }
func (IniStructureDetector) SupportedLanguages() []string        { return []string{propINI} }
func (IniStructureDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewIniStructureDetector()) }

func (d IniStructureDetector) Detect(ctx *detector.Context) *detector.Result {
	fp := ctx.FilePath
	fileID := propINI + ":" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	nodes = append(nodes, base.BuildFileNode(ctx, propINI))

	if ctx.ParsedData == nil {
		return detector.ResultOf(nodes, edges)
	}
	if base.GetString(ctx.ParsedData, "type") != propINI {
		return detector.ResultOf(nodes, edges)
	}
	data := base.GetMap(ctx.ParsedData, "data")
	if len(data) == 0 {
		return detector.ResultOf(nodes, edges)
	}

	sections := make([]string, 0, len(data))
	for s := range data {
		sections = append(sections, s)
	}
	sort.Strings(sections)
	for _, section := range sections {
		sectionID := propINI + ":" + fp + ":" + section
		sn := model.NewCodeNode(sectionID, model.NodeConfigKey, section)
		sn.FQN = fp + ":" + section
		sn.Module = ctx.ModuleName
		sn.FilePath = fp
		sn.Confidence = base.StructuredDetectorDefaultConfidence
		sn.Properties["section"] = true
		nodes = append(nodes, sn)
		edges = append(edges, model.NewCodeEdge(
			fileID+"->"+sectionID, model.EdgeContains, fileID, sectionID))

		sectionData := base.AsMap(data[section])
		keyNames := make([]string, 0, len(sectionData))
		for k := range sectionData {
			keyNames = append(keyNames, k)
		}
		sort.Strings(keyNames)
		for _, key := range keyNames {
			keyID := propINI + ":" + fp + ":" + section + ":" + key
			kn := model.NewCodeNode(keyID, model.NodeConfigKey, key)
			kn.FQN = fp + ":" + section + ":" + key
			kn.Module = ctx.ModuleName
			kn.FilePath = fp
			kn.Confidence = base.StructuredDetectorDefaultConfidence
			kn.Properties["section"] = section
			nodes = append(nodes, kn)
			edges = append(edges, model.NewCodeEdge(
				sectionID+"->"+keyID, model.EdgeContains, sectionID, keyID))
		}
	}
	return detector.ResultOf(nodes, edges)
}
