package structured

import (
	"path"
	"sort"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// PackageJsonDetector mirrors Java PackageJsonDetector. Emits a MODULE for
// the package + a METHOD per script + DEPENDS_ON edges to each
// dependency/devDependency.
type PackageJsonDetector struct{}

func NewPackageJsonDetector() *PackageJsonDetector { return &PackageJsonDetector{} }

func (PackageJsonDetector) Name() string                        { return "package_json" }
func (PackageJsonDetector) SupportedLanguages() []string        { return []string{"json"} }
func (PackageJsonDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewPackageJsonDetector()) }

func (d PackageJsonDetector) Detect(ctx *detector.Context) *detector.Result {
	if path.Base(ctx.FilePath) != "package.json" {
		return detector.EmptyResult()
	}
	if ctx.ParsedData == nil {
		return detector.EmptyResult()
	}
	pkg := base.GetMap(ctx.ParsedData, "data")
	if len(pkg) == 0 {
		return detector.EmptyResult()
	}

	fp := ctx.FilePath
	moduleID := "npm:" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	pkgName := base.GetStringOrDefault(pkg, "name", fp)
	props := map[string]any{"package_name": pkgName}
	if v := base.GetString(pkg, "version"); v != "" {
		props["version"] = v
	}
	mn := model.NewCodeNode(moduleID, model.NodeModule, pkgName)
	mn.FQN = pkgName
	mn.Module = ctx.ModuleName
	mn.FilePath = fp
	mn.Confidence = base.StructuredDetectorDefaultConfidence
	for k, v := range props {
		mn.Properties[k] = v
	}
	nodes = append(nodes, mn)

	for _, depKey := range []string{"dependencies", "devDependencies"} {
		deps := base.GetMap(pkg, depKey)
		depNames := make([]string, 0, len(deps))
		for n := range deps {
			depNames = append(depNames, n)
		}
		sort.Strings(depNames)
		for _, depName := range depNames {
			e := model.NewCodeEdge(moduleID+"->npm:"+depName,
				model.EdgeDependsOn, moduleID, "npm:"+depName)
			e.Confidence = base.StructuredDetectorDefaultConfidence
			e.Properties["dep_type"] = depKey
			if s, ok := deps[depName].(string); ok {
				e.Properties["version_spec"] = s
			}
			edges = append(edges, e)
		}
	}

	scripts := base.GetMap(pkg, "scripts")
	scriptNames := make([]string, 0, len(scripts))
	for n := range scripts {
		scriptNames = append(scriptNames, n)
	}
	sort.Strings(scriptNames)
	for _, name := range scriptNames {
		scriptID := "npm:" + fp + ":script:" + name
		sn := model.NewCodeNode(scriptID, model.NodeMethod, "npm run "+name)
		sn.Module = ctx.ModuleName
		sn.FilePath = fp
		sn.Confidence = base.StructuredDetectorDefaultConfidence
		sn.Properties["script_name"] = name
		if cmd, ok := scripts[name].(string); ok {
			sn.Properties["command"] = cmd
		}
		nodes = append(nodes, sn)
		edges = append(edges, model.NewCodeEdge(
			moduleID+"->"+scriptID, model.EdgeContains, moduleID, scriptID))
	}
	return detector.ResultOf(nodes, edges)
}
