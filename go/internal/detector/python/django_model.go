package python

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// DjangoModelDetector detects Django ORM models (class Foo(models.Model)) plus
// ForeignKey / ManyToManyField edges. Phase 1 = regex; AST in phase 4.
type DjangoModelDetector struct{}

func NewDjangoModelDetector() *DjangoModelDetector { return &DjangoModelDetector{} }

func (DjangoModelDetector) Name() string                        { return "python.django_models" }
func (DjangoModelDetector) SupportedLanguages() []string        { return []string{"python"} }
func (DjangoModelDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewDjangoModelDetector()) }

var (
	djangoModelRE = regexp.MustCompile(`(?m)^class\s+(\w+)\s*\(\s*[\w.]*Model\s*\)`)
	djangoFKRE    = regexp.MustCompile(`(?m)(\w+)\s*=\s*models\.(?:ForeignKey|OneToOneField)\s*\(\s*["']?(\w+)`)
	djangoTableRE = regexp.MustCompile(`db_table\s*=\s*["'](\w+)["']`)
)

func (d DjangoModelDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if !strings.Contains(text, "models.Model") {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	// Per-class scan: find each `class X(...Model):` and collect its body span
	// (everything indented under it until the next non-indented line).
	matches := djangoModelRE.FindAllStringSubmatchIndex(text, -1)
	for i, m := range matches {
		className := text[m[2]:m[3]]
		bodyStart := m[1]
		bodyEnd := len(text)
		if i+1 < len(matches) {
			bodyEnd = matches[i+1][0]
		}
		body := text[bodyStart:bodyEnd]

		id := ctx.FilePath + ":" + className
		n := model.NewCodeNode(id, model.NodeEntity, className)
		n.FQN = className
		n.FilePath = ctx.FilePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "DjangoModelDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "django"
		if tm := djangoTableRE.FindStringSubmatch(body); len(tm) >= 2 {
			n.Properties["table_name"] = tm[1]
		}
		nodes = append(nodes, n)

		for _, fk := range djangoFKRE.FindAllStringSubmatch(body, -1) {
			targetClass := fk[2]
			targetID := ctx.FilePath + ":" + targetClass
			edgeID := id + "->" + targetID
			e := model.NewCodeEdge(edgeID, model.EdgeDependsOn, id, targetID)
			e.Source = "DjangoModelDetector"
			e.Confidence = model.ConfidenceLexical
			e.Properties["framework"] = "django"
			e.Properties["relationship"] = "foreign_key"
			edges = append(edges, e)
		}
	}
	return detector.ResultOf(nodes, edges)
}
