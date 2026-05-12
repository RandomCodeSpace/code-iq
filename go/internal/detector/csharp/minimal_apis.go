package csharp

import (
	"regexp"
	"strconv"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// MinimalApisDetector detects ASP.NET Core Minimal API endpoints
// (.MapGet/.MapPost/...) plus Use/AddAuthentication/Authorization guards.
// Mirrors Java CSharpMinimalApisDetector.
type MinimalApisDetector struct{}

func NewMinimalApisDetector() *MinimalApisDetector { return &MinimalApisDetector{} }

func (MinimalApisDetector) Name() string                        { return "csharp_minimal_apis" }
func (MinimalApisDetector) SupportedLanguages() []string        { return []string{"csharp"} }
func (MinimalApisDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewMinimalApisDetector()) }

var (
	minApisMapRE     = regexp.MustCompile(`(?m)\.Map(Get|Post|Put|Delete|Patch)\s*\(\s*"([^"]*)"`)
	minApisBuilderRE = regexp.MustCompile(`(?m)WebApplication\.CreateBuilder\s*\(`)
	minApisUseAuthRE = regexp.MustCompile(`(?m)\.Use(Authentication|Authorization)\s*\(`)
	minApisAddAuthRE = regexp.MustCompile(`(?m)\.Add(Authentication|Authorization)\s*\(`)
)

const propDotnetMinimalApi = "dotnet_minimal_api"

func (d MinimalApisDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	var appModuleID string

	// Find WebApplication.CreateBuilder => app MODULE
	if loc := minApisBuilderRE.FindStringIndex(text); loc != nil {
		appModuleID = "dotnet:" + filePath + ":app"
		n := model.NewCodeNode(appModuleID, model.NodeModule, "WebApplication("+filePath+")")
		n.FQN = filePath
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, loc[0])
		n.Source = "CSharpMinimalApisDetector"
		n.Properties["framework"] = propDotnetMinimalApi
		nodes = append(nodes, n)
	}

	// MapGet/MapPost/etc endpoints
	for _, m := range minApisMapRE.FindAllStringSubmatchIndex(text, -1) {
		httpMethod := strings.ToUpper(text[m[2]:m[3]])
		path := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		endpointID := "dotnet:" + filePath + ":endpoint:" + httpMethod + ":" + path + ":" + strconv.Itoa(line)

		n := model.NewCodeNode(endpointID, model.NodeEndpoint, httpMethod+" "+path)
		n.FQN = httpMethod + " " + path
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "CSharpMinimalApisDetector"
		n.Properties["http_method"] = httpMethod
		n.Properties["path"] = path
		n.Properties["framework"] = propDotnetMinimalApi
		nodes = append(nodes, n)

		if appModuleID != "" {
			e := model.NewCodeEdge(
				appModuleID+":exposes:"+endpointID,
				model.EdgeExposes, appModuleID, endpointID,
			)
			e.Source = "CSharpMinimalApisDetector"
			edges = append(edges, e)
		}
	}

	// Guards from .UseAuthentication/Authorization
	for _, m := range minApisUseAuthRE.FindAllStringSubmatchIndex(text, -1) {
		authType := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"dotnet:"+filePath+":guard:Use"+authType+":"+strconv.Itoa(line),
			model.NodeGuard, "Use"+authType,
		)
		n.FQN = "Use" + authType
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "CSharpMinimalApisDetector"
		n.Properties["guard_type"] = strings.ToLower(authType)
		n.Properties["framework"] = propDotnetMinimalApi
		nodes = append(nodes, n)
	}

	// Guards from .AddAuthentication/Authorization
	for _, m := range minApisAddAuthRE.FindAllStringSubmatchIndex(text, -1) {
		authType := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"dotnet:"+filePath+":guard:Add"+authType+":"+strconv.Itoa(line),
			model.NodeGuard, "Add"+authType,
		)
		n.FQN = "Add" + authType
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "CSharpMinimalApisDetector"
		n.Properties["guard_type"] = strings.ToLower(authType)
		n.Properties["framework"] = propDotnetMinimalApi
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}
