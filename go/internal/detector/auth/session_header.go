package auth

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// SessionHeaderAuthDetector detects session-, header-, and API-key-based
// authentication. Mirrors Java SessionHeaderAuthDetector.
type SessionHeaderAuthDetector struct{}

func NewSessionHeaderAuthDetector() *SessionHeaderAuthDetector {
	return &SessionHeaderAuthDetector{}
}

func (SessionHeaderAuthDetector) Name() string { return "session_header_auth" }
func (SessionHeaderAuthDetector) SupportedLanguages() []string {
	return []string{"java", "python", "typescript"}
}
func (SessionHeaderAuthDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewSessionHeaderAuthDetector()) }

type sessionPatternDef struct {
	regex    *regexp.Regexp
	authType string
	nodeKind model.NodeKind
}

var (
	sessionSessionPatterns = []sessionPatternDef{
		{regexp.MustCompile(`['"]express-session['"]`), "session", model.NodeMiddleware},
		{regexp.MustCompile(`['"]cookie-session['"]`), "session", model.NodeMiddleware},
		{regexp.MustCompile(`@SessionAttributes\b`), "session", model.NodeGuard},
		{regexp.MustCompile(`\bSessionMiddleware\b`), "session", model.NodeMiddleware},
		{regexp.MustCompile(`\bHttpSession\b`), "session", model.NodeGuard},
		{regexp.MustCompile(`\bSESSION_ENGINE\b`), "session", model.NodeGuard},
	}
	sessionHeaderPatterns = []sessionPatternDef{
		{regexp.MustCompile(`(?i)['"]X-API-Key['"]`), "header", model.NodeGuard},
		{regexp.MustCompile(`(?i)(?:req|request|ctx)\.headers?\s*\[\s*['"]authorization['"]\s*]`), "header", model.NodeGuard},
		{regexp.MustCompile(`(?i)getHeader\s*\(\s*['"]Authorization['"]`), "header", model.NodeGuard},
	}
	sessionApiKeyPatterns = []sessionPatternDef{
		{regexp.MustCompile(`(?i)(?:req|request)\.headers?\s*\[\s*['"]x-api-key['"]\s*]`), "api_key", model.NodeGuard},
		{regexp.MustCompile(`(?i)\bapi[_-]?key\s*[=:]\s*`), "api_key", model.NodeGuard},
		{regexp.MustCompile(`(?i)\bvalidate_?api_?key\b`), "api_key", model.NodeGuard},
	}
	sessionCsrfPatterns = []sessionPatternDef{
		{regexp.MustCompile(`@csrf_protect\b`), "csrf", model.NodeGuard},
		{regexp.MustCompile(`\bcsrf_exempt\b`), "csrf", model.NodeGuard},
		{regexp.MustCompile(`\bCsrfViewMiddleware\b`), "csrf", model.NodeMiddleware},
		{regexp.MustCompile(`['"]csurf['"]`), "csrf", model.NodeMiddleware},
	}
	sessionPreScreen = regexp.MustCompile(
		`express-session|cookie-session|@SessionAttributes|SessionMiddleware|` +
			`HttpSession|SESSION_ENGINE|` +
			`(?i:X-API|Authorization|api[_-]?key|csurf|csrf|getHeader)`,
	)
)

var sessionAllPatterns []sessionPatternDef
var sessionIDTag = map[string]string{
	"session": "session",
	"header":  "header",
	"api_key": "apikey",
	"csrf":    "csrf",
}

func init() {
	sessionAllPatterns = append(sessionAllPatterns, sessionSessionPatterns...)
	sessionAllPatterns = append(sessionAllPatterns, sessionHeaderPatterns...)
	sessionAllPatterns = append(sessionAllPatterns, sessionApiKeyPatterns...)
	sessionAllPatterns = append(sessionAllPatterns, sessionCsrfPatterns...)
}

func (d SessionHeaderAuthDetector) Detect(ctx *detector.Context) *detector.Result {
	switch ctx.Language {
	case "java", "python", "typescript":
		// ok
	default:
		return detector.EmptyResult()
	}
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !sessionPreScreen.MatchString(text) {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	lines := strings.Split(text, "\n")
	seenLines := map[int]bool{}

	for lineIdx, line := range lines {
		for _, pdef := range sessionAllPatterns {
			if seenLines[lineIdx] {
				break
			}
			if pdef.regex.MatchString(line) {
				seenLines[lineIdx] = true
				lineNum := lineIdx + 1
				matched := strings.TrimSpace(line)
				tag := sessionIDTag[pdef.authType]
				n := model.NewCodeNode(
					"auth:"+ctx.FilePath+":"+tag+":"+itoa(lineNum),
					pdef.nodeKind, pdef.authType+" auth: "+truncate(matched, 70),
				)
				n.FilePath = ctx.FilePath
				n.LineStart = lineNum
				n.LineEnd = lineNum
				n.Source = "SessionHeaderAuthDetector"
				n.Properties["auth_type"] = pdef.authType
				n.Properties["language"] = ctx.Language
				n.Properties["pattern"] = truncate(matched, 120)
				nodes = append(nodes, n)
			}
		}
	}
	return detector.ResultOf(nodes, nil)
}
