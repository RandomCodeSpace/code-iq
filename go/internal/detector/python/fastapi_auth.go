package python

import (
	"fmt"
	"regexp"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// FastAPIAuthDetector ports
// io.github.randomcodespace.iq.detector.python.FastAPIAuthDetector.
type FastAPIAuthDetector struct{}

func NewFastAPIAuthDetector() *FastAPIAuthDetector { return &FastAPIAuthDetector{} }

func (FastAPIAuthDetector) Name() string                        { return "fastapi_auth" }
func (FastAPIAuthDetector) SupportedLanguages() []string        { return []string{"python"} }
func (FastAPIAuthDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewFastAPIAuthDetector()) }

var (
	fastapiDependsAuthRE   = regexp.MustCompile(`Depends\(\s*(get_current[\w]*|require_auth[\w]*|auth[\w]*)\s*\)`)
	fastapiSecurityRE      = regexp.MustCompile(`Security\(\s*(\w+)`)
	fastapiHTTPBearerRE    = regexp.MustCompile(`HTTPBearer\s*\(`)
	fastapiOAuth2PwdBearerRE = regexp.MustCompile(`OAuth2PasswordBearer\s*\(\s*tokenUrl\s*=\s*["']([^"']*)["']`)
	fastapiHTTPBasicRE     = regexp.MustCompile(`HTTPBasic\s*\(`)
)

func (d FastAPIAuthDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	mk := func(id, label, annotation string, line int, props map[string]any) {
		n := model.NewCodeNode(id, model.NodeGuard, label)
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "FastAPIAuthDetector"
		n.Confidence = model.ConfidenceLexical
		if annotation != "" {
			n.Annotations = []string{annotation}
		}
		n.Properties["auth_type"] = "fastapi"
		n.Properties["auth_required"] = true
		for k, v := range props {
			n.Properties[k] = v
		}
		nodes = append(nodes, n)
	}

	for _, m := range fastapiDependsAuthRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		dep := text[m[2]:m[3]]
		mk(
			fmt.Sprintf("auth:%s:Depends:%d", filePath, line),
			"Depends("+dep+")", "Depends("+dep+")", line,
			map[string]any{"auth_flow": "oauth2", "dependency": dep},
		)
	}

	for _, m := range fastapiSecurityRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		scheme := text[m[2]:m[3]]
		mk(
			fmt.Sprintf("auth:%s:Security:%d", filePath, line),
			"Security("+scheme+")", "Security("+scheme+")", line,
			map[string]any{"auth_flow": "oauth2", "scheme": scheme},
		)
	}

	for _, m := range fastapiHTTPBearerRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		mk(
			fmt.Sprintf("auth:%s:HTTPBearer:%d", filePath, line),
			"HTTPBearer()", "HTTPBearer", line,
			map[string]any{"auth_flow": "bearer"},
		)
	}

	for _, m := range fastapiOAuth2PwdBearerRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		tokenURL := text[m[2]:m[3]]
		mk(
			fmt.Sprintf("auth:%s:OAuth2PasswordBearer:%d", filePath, line),
			"OAuth2PasswordBearer("+tokenURL+")", "OAuth2PasswordBearer", line,
			map[string]any{"auth_flow": "oauth2", "token_url": tokenURL},
		)
	}

	for _, m := range fastapiHTTPBasicRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		mk(
			fmt.Sprintf("auth:%s:HTTPBasic:%d", filePath, line),
			"HTTPBasic()", "HTTPBasic", line,
			map[string]any{"auth_flow": "basic"},
		)
	}

	return detector.ResultOf(nodes, nil)
}
