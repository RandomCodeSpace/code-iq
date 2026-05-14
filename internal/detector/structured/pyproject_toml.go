package structured

import (
	"path"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// PyprojectTomlDetector mirrors Java PyprojectTomlDetector. Emits a MODULE
// for the project + a CONFIG_DEFINITION per script entry point. Supports
// both PEP 621 (`[project]`) and Poetry (`[tool.poetry]`) layouts.
type PyprojectTomlDetector struct{}

func NewPyprojectTomlDetector() *PyprojectTomlDetector { return &PyprojectTomlDetector{} }

func (PyprojectTomlDetector) Name() string                        { return "pyproject_toml" }
func (PyprojectTomlDetector) SupportedLanguages() []string        { return []string{"toml"} }
func (PyprojectTomlDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewPyprojectTomlDetector()) }

func (d PyprojectTomlDetector) Detect(ctx *detector.Context) *detector.Result {
	if path.Base(ctx.FilePath) != "pyproject.toml" {
		return detector.EmptyResult()
	}
	if ctx.ParsedData == nil {
		return detector.EmptyResult()
	}
	data := base.GetMap(ctx.ParsedData, "data")
	if len(data) == 0 {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	moduleID := "pypi:" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	projectSection := base.GetMap(data, "project")
	toolSection := base.GetMap(data, "tool")
	poetrySection := base.GetMap(toolSection, "poetry")

	pkgName := base.GetString(projectSection, "name")
	if pkgName == "" {
		pkgName = base.GetString(poetrySection, "name")
	}
	if pkgName == "" {
		pkgName = fp
	}
	props := map[string]any{"package_name": pkgName}
	if v := base.GetString(projectSection, "version"); v != "" {
		props["version"] = v
	} else if v := base.GetString(poetrySection, "version"); v != "" {
		props["version"] = v
	}
	if v := base.GetString(projectSection, "description"); v != "" {
		props["description"] = v
	} else if v := base.GetString(poetrySection, "description"); v != "" {
		props["description"] = v
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

	// PEP 621 dependencies (list of strings)
	for _, depSpec := range base.GetList(projectSection, "dependencies") {
		s, ok := depSpec.(string)
		if !ok {
			continue
		}
		depName := parsePEPDepName(s)
		if depName == "" {
			continue
		}
		e := model.NewCodeEdge(moduleID+"->pypi:"+depName, model.EdgeDependsOn,
			moduleID, "pypi:"+depName)
		e.Confidence = base.StructuredDetectorDefaultConfidence
		e.Properties["dep_spec"] = s
		edges = append(edges, e)
	}

	// Poetry style: [tool.poetry].dependencies is a map
	poetryDeps := base.GetMap(poetrySection, "dependencies")
	poetryDepNames := make([]string, 0, len(poetryDeps))
	for n := range poetryDeps {
		if strings.EqualFold(n, "python") {
			continue
		}
		poetryDepNames = append(poetryDepNames, n)
	}
	sort.Strings(poetryDepNames)
	for _, depName := range poetryDepNames {
		e := model.NewCodeEdge(moduleID+"->pypi:"+depName, model.EdgeDependsOn,
			moduleID, "pypi:"+depName)
		e.Confidence = base.StructuredDetectorDefaultConfidence
		if s, ok := poetryDeps[depName].(string); ok {
			e.Properties["version_spec"] = s
		}
		edges = append(edges, e)
	}

	// Scripts: merge project.scripts + tool.poetry.scripts. Iterate sorted.
	scripts := base.GetMap(projectSection, "scripts")
	poetryScripts := base.GetMap(poetrySection, "scripts")
	allScripts := map[string]any{}
	for k, v := range scripts {
		allScripts[k] = v
	}
	for k, v := range poetryScripts {
		allScripts[k] = v
	}
	scriptNames := make([]string, 0, len(allScripts))
	for n := range allScripts {
		scriptNames = append(scriptNames, n)
	}
	sort.Strings(scriptNames)
	for _, name := range scriptNames {
		scriptID := "pypi:" + fp + ":script:" + name
		sn := model.NewCodeNode(scriptID, model.NodeConfigDefinition, name)
		sn.FQN = pkgName + ":script:" + name
		sn.Module = ctx.ModuleName
		sn.FilePath = fp
		sn.Confidence = base.StructuredDetectorDefaultConfidence
		sn.Properties["script_name"] = name
		if s, ok := allScripts[name].(string); ok {
			sn.Properties["target"] = s
		}
		nodes = append(nodes, sn)
		edges = append(edges, model.NewCodeEdge(
			moduleID+"->"+scriptID, model.EdgeContains, moduleID, scriptID))
	}
	return detector.ResultOf(nodes, edges)
}

// parsePEPDepName extracts the package name from a PEP 508 / requirements
// specifier. Mirrors the Java parseDepName helper.
func parsePEPDepName(spec string) string {
	spec = strings.TrimSpace(spec)
	if spec == "" {
		return ""
	}
	for _, ch := range []rune{'>', '=', '<', '!', '[', ';', '@', ' '} {
		if i := strings.IndexRune(spec, ch); i > 0 {
			spec = spec[:i]
		}
	}
	return strings.TrimSpace(spec)
}
