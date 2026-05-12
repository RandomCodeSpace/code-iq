package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// ModuleDepsDetector mirrors Java ModuleDepsDetector. Routes to Maven (pom.xml),
// Gradle settings, or Gradle build script branches by filename suffix.
type ModuleDepsDetector struct{}

func NewModuleDepsDetector() *ModuleDepsDetector { return &ModuleDepsDetector{} }

func (ModuleDepsDetector) Name() string                 { return "module_deps" }
func (ModuleDepsDetector) SupportedLanguages() []string { return []string{"java", "xml", "gradle"} }
func (ModuleDepsDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewModuleDepsDetector()) }

var (
	mdGradleDepRE = regexp.MustCompile(
		`(?:implementation|api|compile|compileOnly|runtimeOnly|testImplementation)\s+(?:project\s*\(\s*['"]([^'"]+)['"]\s*\)|['"]([^'"]+)['"])`,
	)
	mdGradleSettingsRE = regexp.MustCompile(`include\s+['"]([^'"]+)['"]`)
	mdGroupIDRE        = regexp.MustCompile(`<groupId>([^<]+)</groupId>`)
	mdArtifactIDRE     = regexp.MustCompile(`<artifactId>([^<]+)</artifactId>`)
	mdModuleRE         = regexp.MustCompile(`<module>([^<]+)</module>`)
	mdDepBlockRE       = regexp.MustCompile(`(?s)<dependency>\s*(.*?)\s*</dependency>`)
)

func (d ModuleDepsDetector) Detect(ctx *detector.Context) *detector.Result {
	fp := ctx.FilePath
	if strings.HasSuffix(fp, "pom.xml") {
		return d.detectMaven(ctx)
	}
	if strings.HasSuffix(fp, "settings.gradle") || strings.HasSuffix(fp, "settings.gradle.kts") {
		return d.detectGradleSettings(ctx)
	}
	if strings.HasSuffix(fp, ".gradle") || strings.HasSuffix(fp, ".gradle.kts") {
		return d.detectGradle(ctx)
	}
	return detector.EmptyResult()
}

func (d ModuleDepsDetector) detectMaven(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	// Extract top-level groupId/artifactId from text before first <dependencies>.
	topSection := text
	if depsIdx := strings.Index(text, "<dependencies>"); depsIdx > 0 {
		topSection = text[:depsIdx]
	}
	groupID := "unknown"
	if m := mdGroupIDRE.FindStringSubmatch(topSection); m != nil {
		groupID = m[1]
	}
	artifactID := "unknown"
	if m := mdArtifactIDRE.FindStringSubmatch(topSection); m != nil {
		artifactID = m[1]
	}

	moduleID := "module:" + groupID + ":" + artifactID
	mod := model.NewCodeNode(moduleID, model.NodeModule, artifactID)
	mod.FQN = groupID + ":" + artifactID
	mod.FilePath = ctx.FilePath
	mod.LineStart = 1
	mod.Source = "ModuleDepsDetector"
	mod.Properties["group_id"] = groupID
	mod.Properties["artifact_id"] = artifactID
	mod.Properties["build_tool"] = "maven"
	nodes = append(nodes, mod)

	// Sub-modules
	for _, mm := range mdModuleRE.FindAllStringSubmatch(text, -1) {
		subModule := mm[1]
		subID := "module:" + groupID + ":" + subModule
		sub := model.NewCodeNode(subID, model.NodeModule, subModule)
		sub.FQN = groupID + ":" + subModule
		sub.Source = "ModuleDepsDetector"
		sub.Properties["build_tool"] = "maven"
		sub.Properties["parent"] = artifactID
		nodes = append(nodes, sub)
		edges = append(edges, model.NewCodeEdge(moduleID+"->contains->"+subID, model.EdgeContains, moduleID, subID))
	}

	// Dependencies
	for _, dm := range mdDepBlockRE.FindAllStringSubmatch(text, -1) {
		block := dm[1]
		depGroup := "unknown"
		if g := mdGroupIDRE.FindStringSubmatch(block); g != nil {
			depGroup = g[1]
		}
		am := mdArtifactIDRE.FindStringSubmatch(block)
		if am == nil {
			continue
		}
		depArtifact := am[1]
		depID := "module:" + depGroup + ":" + depArtifact
		e := model.NewCodeEdge(moduleID+"->depends_on->"+depID, model.EdgeDependsOn, moduleID, depID)
		e.Properties["group_id"] = depGroup
		e.Properties["artifact_id"] = depArtifact
		edges = append(edges, e)
	}

	return detector.ResultOf(nodes, edges)
}

func (d ModuleDepsDetector) detectGradle(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	moduleName := ctx.ModuleName
	if moduleName == "" {
		fp := ctx.FilePath
		if lastSlash := strings.LastIndex(fp, "/"); lastSlash > 0 {
			dir := fp[:lastSlash]
			if prevSlash := strings.LastIndex(dir, "/"); prevSlash >= 0 {
				moduleName = dir[prevSlash+1:]
			} else {
				moduleName = dir
			}
		} else {
			moduleName = fp
		}
	}
	moduleID := "module:" + moduleName

	mod := model.NewCodeNode(moduleID, model.NodeModule, moduleName)
	mod.FQN = moduleName
	mod.FilePath = ctx.FilePath
	mod.LineStart = 1
	mod.Source = "ModuleDepsDetector"
	mod.Properties["build_tool"] = "gradle"
	nodes = append(nodes, mod)

	for _, line := range lines {
		m := mdGradleDepRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		projectDep := m[1]
		externalDep := m[2]
		switch {
		case projectDep != "":
			depName := strings.TrimLeft(projectDep, ":")
			depID := "module:" + depName
			e := model.NewCodeEdge(moduleID+"->depends_on->"+depID, model.EdgeDependsOn, moduleID, depID)
			e.Properties["type"] = "project"
			edges = append(edges, e)
		case externalDep != "" && strings.Contains(externalDep, ":"):
			parts := strings.Split(externalDep, ":")
			depID := "module:" + externalDep
			if len(parts) >= 2 {
				depID = "module:" + parts[0] + ":" + parts[1]
			}
			e := model.NewCodeEdge(moduleID+"->depends_on->"+depID, model.EdgeDependsOn, moduleID, depID)
			e.Properties["coordinate"] = externalDep
			e.Properties["type"] = "external"
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}

func (d ModuleDepsDetector) detectGradleSettings(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	for _, m := range mdGradleSettingsRE.FindAllStringSubmatch(text, -1) {
		modulePath := strings.TrimLeft(m[1], ":")
		moduleID := "module:" + modulePath
		n := model.NewCodeNode(moduleID, model.NodeModule, modulePath)
		n.FQN = modulePath
		n.FilePath = ctx.FilePath
		n.Source = "ModuleDepsDetector"
		n.Properties["build_tool"] = "gradle"
		nodes = append(nodes, n)
	}
	return detector.ResultOf(nodes, nil)
}
