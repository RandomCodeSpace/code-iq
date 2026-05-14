package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// QuarkusDetector mirrors Java QuarkusDetector. Detects:
//   - @QuarkusTest classes
//   - @ConfigProperty(name = "...") bindings
//   - CDI scopes (@Inject, @Singleton, @ApplicationScoped, @RequestScoped)
//   - @Scheduled(every|cron = "...")
//   - @Transactional, @Startup
//
// REQUIRES a Quarkus-specific discriminator (io.quarkus / io.smallrye /
// @QuarkusTest import) to avoid matching shared annotations against Spring.
type QuarkusDetector struct{}

func NewQuarkusDetector() *QuarkusDetector { return &QuarkusDetector{} }

func (QuarkusDetector) Name() string                        { return "quarkus" }
func (QuarkusDetector) SupportedLanguages() []string        { return []string{"java"} }
func (QuarkusDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewQuarkusDetector()) }

var (
	quarkusTestRE        = regexp.MustCompile(`@QuarkusTest\b`)
	quarkusConfigPropRE  = regexp.MustCompile(`@ConfigProperty\s*\(\s*name\s*=\s*"([^"]+)"`)
	quarkusCdiScopeRE    = regexp.MustCompile(`@(Inject|Singleton|ApplicationScoped|RequestScoped)\b`)
	quarkusScheduledRE   = regexp.MustCompile(`@Scheduled\s*\(\s*(?:every|cron)\s*=\s*"([^"]+)"`)
	quarkusTransactional = regexp.MustCompile(`@Transactional\b`)
	quarkusStartupRE     = regexp.MustCompile(`@Startup\b`)
	quarkusClassRE       = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
)

func (d QuarkusDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	// Discriminator: must mention a Quarkus-only namespace, else bail out.
	hasQuarkus := strings.Contains(text, "io.quarkus") ||
		strings.Contains(text, "io.smallrye") ||
		strings.Contains(text, "@QuarkusTest")
	if !hasQuarkus {
		return detector.EmptyResult()
	}

	// Quick reject when none of the patterns can match (and discriminator was
	// io.quarkus alone — e.g. unrelated import).
	if !strings.Contains(text, "@QuarkusTest") && !strings.Contains(text, "@ConfigProperty") &&
		!strings.Contains(text, "@Singleton") && !strings.Contains(text, "@ApplicationScoped") &&
		!strings.Contains(text, "@RequestScoped") && !strings.Contains(text, "@Scheduled") &&
		!strings.Contains(text, "@Transactional") && !strings.Contains(text, "@Startup") &&
		!strings.Contains(text, "io.quarkus") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode

	var className string
	for _, line := range lines {
		if m := quarkusClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}

	for i, line := range lines {
		lineno := i + 1

		if quarkusTestRE.MatchString(line) {
			label := "@QuarkusTest " + ifEmpty(className, "unknown")
			n := makeQuarkusNode("quarkus:"+ctx.FilePath+":quarkus_test:"+itoaQ(lineno),
				model.NodeClass, label, className, lineno, ctx)
			n.Annotations = append(n.Annotations, "@QuarkusTest")
			n.Properties["test"] = true
			nodes = append(nodes, n)
		}

		if m := quarkusConfigPropRE.FindStringSubmatch(line); m != nil {
			configKey := m[1]
			n := makeQuarkusNode("quarkus:"+ctx.FilePath+":config_property:"+itoaQ(lineno),
				model.NodeConfigKey, "@ConfigProperty("+configKey+")", configKey, lineno, ctx)
			n.Annotations = append(n.Annotations, "@ConfigProperty")
			n.Properties["config_key"] = configKey
			nodes = append(nodes, n)
		}

		if m := quarkusCdiScopeRE.FindStringSubmatch(line); m != nil {
			ann := m[1]
			fqn := ann
			if className != "" {
				fqn = className + "." + ann
			}
			n := makeQuarkusNode(
				"quarkus:"+ctx.FilePath+":cdi_"+strings.ToLower(ann)+":"+itoaQ(lineno),
				model.NodeMiddleware, "@"+ann+" (CDI)", fqn, lineno, ctx)
			n.Annotations = append(n.Annotations, "@"+ann)
			n.Properties["cdi_scope"] = ann
			nodes = append(nodes, n)
		}

		if m := quarkusScheduledRE.FindStringSubmatch(line); m != nil {
			scheduleExpr := m[1]
			fqn := "scheduled"
			if className != "" {
				fqn = className + ".scheduled"
			}
			n := makeQuarkusNode("quarkus:"+ctx.FilePath+":scheduled:"+itoaQ(lineno),
				model.NodeEvent, "@Scheduled("+scheduleExpr+")", fqn, lineno, ctx)
			n.Annotations = append(n.Annotations, "@Scheduled")
			n.Properties["schedule"] = scheduleExpr
			nodes = append(nodes, n)
		}

		if quarkusTransactional.MatchString(line) {
			fqn := "transactional"
			if className != "" {
				fqn = className + ".transactional"
			}
			n := makeQuarkusNode("quarkus:"+ctx.FilePath+":transactional:"+itoaQ(lineno),
				model.NodeMiddleware, "@Transactional", fqn, lineno, ctx)
			n.Annotations = append(n.Annotations, "@Transactional")
			nodes = append(nodes, n)
		}

		if quarkusStartupRE.MatchString(line) {
			label := "@Startup " + ifEmpty(className, "unknown")
			n := makeQuarkusNode("quarkus:"+ctx.FilePath+":startup:"+itoaQ(lineno),
				model.NodeMiddleware, label, className, lineno, ctx)
			n.Annotations = append(n.Annotations, "@Startup")
			nodes = append(nodes, n)
		}
	}

	return detector.ResultOf(nodes, nil)
}

func makeQuarkusNode(id string, kind model.NodeKind, label, fqn string, line int, ctx *detector.Context) *model.CodeNode {
	n := model.NewCodeNode(id, kind, label)
	n.FQN = fqn
	n.FilePath = ctx.FilePath
	n.LineStart = line
	n.Source = "QuarkusDetector"
	n.Properties["framework"] = "quarkus"
	return n
}

func ifEmpty(s, fallback string) string {
	if s == "" {
		return fallback
	}
	return s
}

func itoaQ(i int) string {
	if i == 0 {
		return "0"
	}
	var buf [20]byte
	n := len(buf)
	for i > 0 {
		n--
		buf[n] = byte('0' + i%10)
		i /= 10
	}
	return string(buf[n:])
}
