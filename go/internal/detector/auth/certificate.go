package auth

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// CertificateAuthDetector detects certificate-based authentication (mTLS,
// X.509, TLS config, Azure AD client-cert flows). Mirrors Java
// CertificateAuthDetector — same multi-pattern + auth_type tag table.
type CertificateAuthDetector struct{}

func NewCertificateAuthDetector() *CertificateAuthDetector { return &CertificateAuthDetector{} }

func (CertificateAuthDetector) Name() string { return "certificate_auth" }
func (CertificateAuthDetector) SupportedLanguages() []string {
	return []string{"java", "python", "typescript", "csharp", "json", "yaml"}
}
func (CertificateAuthDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewCertificateAuthDetector()) }

type certPatternDef struct {
	regex    *regexp.Regexp
	authType string
}

var (
	certMtlsPatterns = []certPatternDef{
		{regexp.MustCompile(`\bssl_verify_client\b`), "mtls"},
		{regexp.MustCompile(`\brequestCert\s*:\s*true\b`), "mtls"},
		{regexp.MustCompile(`\bclientAuth\s*=\s*"true"`), "mtls"},
		{regexp.MustCompile(`\bX509AuthenticationFilter\b`), "mtls"},
		{regexp.MustCompile(`\bAddCertificateForwarding\b`), "mtls"},
	}
	certX509Patterns = []certPatternDef{
		{regexp.MustCompile(`\bX509AuthenticationFilter\b`), "x509"},
		{regexp.MustCompile(`\bCertificateAuthenticationDefaults\b`), "x509"},
		{regexp.MustCompile(`\.x509\s*\(`), "x509"},
	}
	certTlsConfigPatterns = []certPatternDef{
		{regexp.MustCompile(`\bjavax\.net\.ssl\.keyStore\b`), "tls_config"},
		{regexp.MustCompile(`\bssl\.SSLContext\b`), "tls_config"},
		{regexp.MustCompile(`\btls\.createServer\b`), "tls_config"},
		{regexp.MustCompile(`(?:cert|key|ca)\s*[=:]\s*(?:fs\.readFileSync\s*\(|['"][\w/.\\-]+\.(?:pem|crt|key|cert)['"])`), "tls_config"},
		{regexp.MustCompile(`\btrustStore\b`), "tls_config"},
	}
	certAzureAdPatterns = []certPatternDef{
		{regexp.MustCompile(`\bAzureAd\b`), "azure_ad"},
		{regexp.MustCompile(`\bAZURE_TENANT_ID\b`), "azure_ad"},
		{regexp.MustCompile(`\bAZURE_CLIENT_ID\b`), "azure_ad"},
		{regexp.MustCompile(`\bmsal\b`), "azure_ad"},
		{regexp.MustCompile(`['"]@azure/msal-browser['"]`), "azure_ad"},
		{regexp.MustCompile(`\bAddMicrosoftIdentityWebApi\b`), "azure_ad"},
		{regexp.MustCompile(`\bClientCertificateCredential\b`), "azure_ad"},
	}
	certCertPathRE = regexp.MustCompile(`['"]([^'"]*\.(?:pem|crt|key|cert|pfx|p12))['"]`)
	certTenantIDRE = regexp.MustCompile(`AZURE_TENANT_ID\s*[=:]\s*['"]?([a-f0-9-]+)['"]?`)
	// certStrictKeywords gate detector entry. STRICT subset: file must
	// contain at least one of these high-signal markers before we even
	// consider running the 20 per-pattern regexes. Loose keywords like
	// ".pem"/".crt"/".cert" are NOT in this set because they show up as
	// path/extension references in millions of unrelated lines (e.g. C#
	// `using System.Security.Cryptography.X509Certificates`) and would
	// turn the per-line gate into a no-op.
	//
	// Profiling on PSScriptAnalyzer (593 files, 203 C#) showed
	// CertificateAuthDetector consuming 99% of indexing CPU before this
	// pre-screen. Tighter gate keeps the detector fast on cert-free repos.
	certStrictKeywords = []string{
		"ssl_verify_client", "requestCert", "clientAuth=",
		"AddCertificateForwarding", "CertificateAuthenticationDefaults",
		".x509(", "X509AuthenticationFilter",
		"javax.net.ssl", "SSLContext", "tls.createServer",
		"trustStore", "AzureAd", "AZURE_TENANT_ID", "AZURE_CLIENT_ID",
		"ClientCertificateCredential", "AddMicrosoftIdentityWebApi",
		"@azure/msal",
	}
)

var certAllPatterns []certPatternDef

func init() {
	certAllPatterns = append(certAllPatterns, certMtlsPatterns...)
	certAllPatterns = append(certAllPatterns, certX509Patterns...)
	certAllPatterns = append(certAllPatterns, certTlsConfigPatterns...)
	certAllPatterns = append(certAllPatterns, certAzureAdPatterns...)
}

// certLineQuickScan returns true if s contains any of the auth-cert
// keywords. Cheap O(n*k) byte scan beats running 20 regex alternation
// engines per line. Used both as a file-level and a per-line gate.
func certLineQuickScan(s string) bool {
	for _, kw := range certStrictKeywords {
		if strings.Contains(s, kw) {
			return true
		}
	}
	return false
}

func (d CertificateAuthDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !certLineQuickScan(text) {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	lines := strings.Split(text, "\n")
	seenLines := map[int]bool{}

	for lineIdx, line := range lines {
		// Per-line pre-screen: skip the 20 regex passes on lines without
		// any cert-auth keyword. ~99% reduction on real codebases.
		if !certLineQuickScan(line) {
			continue
		}
		for _, pdef := range certAllPatterns {
			if seenLines[lineIdx] {
				break
			}
			if pdef.regex.MatchString(line) {
				seenLines[lineIdx] = true
				lineNum := lineIdx + 1
				matched := strings.TrimSpace(line)
				n := model.NewCodeNode(
					"auth:"+ctx.FilePath+":cert:"+itoa(lineNum),
					model.NodeGuard, "Certificate auth ("+pdef.authType+"): "+truncate(matched, 60),
				)
				n.FilePath = ctx.FilePath
				n.LineStart = lineNum
				n.LineEnd = lineNum
				n.Source = "CertificateAuthDetector"
				n.Properties["auth_type"] = pdef.authType
				n.Properties["language"] = ctx.Language
				n.Properties["pattern"] = truncate(matched, 120)

				if cm := certCertPathRE.FindStringSubmatch(line); len(cm) >= 2 {
					n.Properties["cert_path"] = cm[1]
				}
				if tm := certTenantIDRE.FindStringSubmatch(line); len(tm) >= 2 {
					n.Properties["tenant_id"] = tm[1]
				}
				if pdef.authType == "azure_ad" {
					if strings.Contains(line, "ClientCertificateCredential") {
						n.Properties["auth_flow"] = "client_certificate"
					} else if strings.Contains(strings.ToLower(line), "msal") {
						n.Properties["auth_flow"] = "msal"
					}
				}
				nodes = append(nodes, n)
			}
		}
	}
	return detector.ResultOf(nodes, nil)
}
