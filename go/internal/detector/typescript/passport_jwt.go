package typescript

import (
	"fmt"
	"regexp"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// PassportJwtDetector ports
// io.github.randomcodespace.iq.detector.typescript.PassportJwtDetector.
type PassportJwtDetector struct{}

func NewPassportJwtDetector() *PassportJwtDetector { return &PassportJwtDetector{} }

func (PassportJwtDetector) Name() string                 { return "typescript.passport_jwt" }
func (PassportJwtDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (PassportJwtDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewPassportJwtDetector()) }

var (
	passportUseRE        = regexp.MustCompile(`passport\.use\(\s*new\s+(\w+Strategy)\s*\(`)
	passportAuthRE       = regexp.MustCompile(`passport\.authenticate\(\s*['"](\w+)['"]`)
	jwtVerifyRE          = regexp.MustCompile(`jwt\.verify\s*\(`)
	requireExpressJwtRE  = regexp.MustCompile(`require\(\s*['"]express-jwt['"]\s*\)`)
	importExpressJwtRE   = regexp.MustCompile(`import\s+\{[^}]*\bexpressjwt\b[^}]*\}\s+from\s+['"]express-jwt['"]`)
)

func (d PassportJwtDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	addNode := func(id, label, fqn string, kind model.NodeKind, line int, props map[string]any) {
		n := model.NewCodeNode(id, kind, label)
		n.FQN = fqn
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "PassportJwtDetector"
		n.Confidence = model.ConfidenceLexical
		for k, v := range props {
			n.Properties[k] = v
		}
		nodes = append(nodes, n)
	}

	for _, m := range passportUseRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		strategy := text[m[2]:m[3]]
		addNode(
			fmt.Sprintf("auth:%s:passport.use(%s):%d", filePath, strategy, line),
			"passport.use("+strategy+")",
			filePath+"::passport.use("+strategy+")",
			model.NodeGuard, line,
			map[string]any{"auth_type": "passport", "strategy": strategy},
		)
	}

	for _, m := range passportAuthRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		strategy := text[m[2]:m[3]]
		addNode(
			fmt.Sprintf("auth:%s:passport.authenticate(%s):%d", filePath, strategy, line),
			"passport.authenticate('"+strategy+"')",
			filePath+"::passport.authenticate("+strategy+")",
			model.NodeMiddleware, line,
			map[string]any{"auth_type": "jwt", "strategy": strategy},
		)
	}

	for _, m := range jwtVerifyRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		addNode(
			fmt.Sprintf("auth:%s:jwt.verify:%d", filePath, line),
			"jwt.verify()",
			filePath+"::jwt.verify",
			model.NodeMiddleware, line,
			map[string]any{"auth_type": "jwt"},
		)
	}

	for _, m := range requireExpressJwtRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		addNode(
			fmt.Sprintf("auth:%s:require(express-jwt):%d", filePath, line),
			"require('express-jwt')",
			filePath+"::require(express-jwt)",
			model.NodeMiddleware, line,
			map[string]any{"auth_type": "jwt", "library": "express-jwt"},
		)
	}

	for _, m := range importExpressJwtRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		addNode(
			fmt.Sprintf("auth:%s:import(expressjwt):%d", filePath, line),
			"import { expressjwt }",
			filePath+"::import(expressjwt)",
			model.NodeMiddleware, line,
			map[string]any{"auth_type": "jwt", "library": "express-jwt"},
		)
	}

	return detector.ResultOf(nodes, nil)
}
