package structured

import (
	"path"
	"regexp"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// TsconfigJsonDetector mirrors Java TsconfigJsonDetector. Emits a CONFIG_FILE
// node for tsconfig.json and a CONFIG_KEY per tracked compiler option, with
// DEPENDS_ON edges to `extends` and `references[*].path`.
type TsconfigJsonDetector struct{}

func NewTsconfigJsonDetector() *TsconfigJsonDetector { return &TsconfigJsonDetector{} }

func (TsconfigJsonDetector) Name() string                        { return "tsconfig_json" }
func (TsconfigJsonDetector) SupportedLanguages() []string        { return []string{"json"} }
func (TsconfigJsonDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewTsconfigJsonDetector()) }

var tsconfigBaseRE = regexp.MustCompile(`^tsconfig(?:\..+)?\.json$`)

var tsTrackedCompilerOptions = []string{"strict", "target", "module", "outDir", "rootDir"}

func (d TsconfigJsonDetector) Detect(ctx *detector.Context) *detector.Result {
	bname := path.Base(ctx.FilePath)
	if !tsconfigBaseRE.MatchString(bname) {
		return detector.EmptyResult()
	}
	if ctx.ParsedData == nil {
		return detector.EmptyResult()
	}
	cfg := base.GetMap(ctx.ParsedData, "data")
	if len(cfg) == 0 {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	configID := "tsconfig:" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	cn := model.NewCodeNode(configID, model.NodeConfigFile, bname)
	cn.FQN = fp
	cn.Module = ctx.ModuleName
	cn.FilePath = fp
	cn.Confidence = base.StructuredDetectorDefaultConfidence
	cn.Properties["config_type"] = "tsconfig"
	nodes = append(nodes, cn)

	if ext := base.GetString(cfg, "extends"); ext != "" {
		e := model.NewCodeEdge(configID+"->"+ext, model.EdgeDependsOn, configID, ext)
		e.Confidence = base.StructuredDetectorDefaultConfidence
		e.Properties["relation"] = "extends"
		edges = append(edges, e)
	}
	for _, ref := range base.GetList(cfg, "references") {
		refMap := base.AsMap(ref)
		refPath := base.GetString(refMap, "path")
		if refPath == "" {
			continue
		}
		e := model.NewCodeEdge(configID+"->"+refPath, model.EdgeDependsOn, configID, refPath)
		e.Confidence = base.StructuredDetectorDefaultConfidence
		e.Properties["relation"] = "reference"
		edges = append(edges, e)
	}

	compilerOptions := base.GetMap(cfg, "compilerOptions")
	for _, opt := range tsTrackedCompilerOptions {
		v, ok := compilerOptions[opt]
		if !ok {
			continue
		}
		keyID := "tsconfig:" + fp + ":option:" + opt
		kn := model.NewCodeNode(keyID, model.NodeConfigKey, "compilerOptions."+opt)
		kn.Module = ctx.ModuleName
		kn.FilePath = fp
		kn.Confidence = base.StructuredDetectorDefaultConfidence
		kn.Properties["key"] = opt
		kn.Properties["value"] = v
		nodes = append(nodes, kn)
		edges = append(edges, model.NewCodeEdge(configID+"->"+keyID, model.EdgeContains, configID, keyID))
	}
	return detector.ResultOf(nodes, edges)
}
