// Package auth holds cross-cutting authentication-related detectors.
package auth

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// LdapAuthDetector detects LDAP / Active Directory authentication
// configuration across Java, Python, TypeScript, and C#. Mirrors Java
// LdapAuthDetector.
type LdapAuthDetector struct{}

func NewLdapAuthDetector() *LdapAuthDetector { return &LdapAuthDetector{} }

func (LdapAuthDetector) Name() string { return "ldap_auth" }
func (LdapAuthDetector) SupportedLanguages() []string {
	return []string{"java", "python", "typescript", "csharp"}
}
func (LdapAuthDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewLdapAuthDetector()) }

var (
	ldapJavaPatterns = []*regexp.Regexp{
		regexp.MustCompile(`\bLdapContextSource\b`),
		regexp.MustCompile(`\bLdapTemplate\b`),
		regexp.MustCompile(`\bActiveDirectoryLdapAuthenticationProvider\b`),
		regexp.MustCompile(`@EnableLdapRepositories\b`),
	}
	ldapPythonPatterns = []*regexp.Regexp{
		regexp.MustCompile(`\bldap3\.Connection\b`),
		regexp.MustCompile(`\bldap3\.Server\b`),
		regexp.MustCompile(`\bAUTH_LDAP_SERVER_URI\b`),
		regexp.MustCompile(`\bAUTH_LDAP_BIND_DN\b`),
	}
	ldapTsPatterns = []*regexp.Regexp{
		regexp.MustCompile(`require\s*\(\s*['"]ldapjs['"]\s*\)`),
		regexp.MustCompile(`(?:import\s+.*\s+from\s+['"]ldapjs['"]|import\s+ldapjs\b)`),
		regexp.MustCompile(`['"]passport-ldapauth['"]`),
	}
	ldapCsharpPatterns = []*regexp.Regexp{
		regexp.MustCompile(`\bSystem\.DirectoryServices\b`),
		regexp.MustCompile(`\bLdapConnection\b`),
		regexp.MustCompile(`\bDirectoryEntry\b`),
	}
	ldapPreScreen = regexp.MustCompile(`(?i:ldap)|DirectoryServices|DirectoryEntry`)
)

var ldapPatternsByLang = map[string][]*regexp.Regexp{
	"java":       ldapJavaPatterns,
	"python":     ldapPythonPatterns,
	"typescript": ldapTsPatterns,
	"csharp":     ldapCsharpPatterns,
}

func (d LdapAuthDetector) Detect(ctx *detector.Context) *detector.Result {
	patterns, ok := ldapPatternsByLang[ctx.Language]
	if !ok {
		return detector.EmptyResult()
	}
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !ldapPreScreen.MatchString(text) {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	lines := strings.Split(text, "\n")
	seenLines := map[int]bool{}

	for lineIdx, line := range lines {
		for _, pat := range patterns {
			if seenLines[lineIdx] {
				break
			}
			if pat.MatchString(line) {
				seenLines[lineIdx] = true
				lineNum := lineIdx + 1
				matched := strings.TrimSpace(line)
				n := model.NewCodeNode(
					"auth:"+ctx.FilePath+":ldap:"+itoa(lineNum),
					model.NodeGuard, "LDAP auth: "+truncate(matched, 80),
				)
				n.FilePath = ctx.FilePath
				n.LineStart = lineNum
				n.LineEnd = lineNum
				n.Source = "LdapAuthDetector"
				n.Properties["auth_type"] = "ldap"
				n.Properties["language"] = ctx.Language
				n.Properties["pattern"] = truncate(matched, 120)
				nodes = append(nodes, n)
			}
		}
	}
	return detector.ResultOf(nodes, nil)
}
