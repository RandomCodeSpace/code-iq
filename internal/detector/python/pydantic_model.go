package python

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// PydanticModelDetector ports
// io.github.randomcodespace.iq.detector.python.PydanticModelDetector.
type PydanticModelDetector struct{}

func NewPydanticModelDetector() *PydanticModelDetector { return &PydanticModelDetector{} }

func (PydanticModelDetector) Name() string                        { return "python.pydantic_models" }
func (PydanticModelDetector) SupportedLanguages() []string        { return []string{"python"} }
func (PydanticModelDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewPydanticModelDetector()) }

var (
	pydanticClassRE = regexp.MustCompile(
		`(?m)^class\s+(\w+)\s*\(\s*(\w*(?:BaseModel|BaseSettings)\w*)\s*\)`)
	pydanticFieldRE     = regexp.MustCompile(`(?m)^\s+(\w+)\s*:\s*(\w[\w\[\], |]*)`)
	pydanticValidatorRE = regexp.MustCompile(`(?m)@(?:validator|field_validator)\s*\(\s*["'](\w+)`)
	pydanticConfigClsRE = regexp.MustCompile(`(?m)^\s+class\s+Config\s*:`)
	pydanticConfigAttrRE = regexp.MustCompile(`(?m)^\s{8}(\w+)\s*=\s*(.+)`)
)

func (d PydanticModelDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName
	known := make(map[string]string)

	for _, m := range pydanticClassRE.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		baseClass := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])

		// Slice class body to next top-level class or EOF.
		classStart := m[0]
		afterEnd := m[1]
		classBody := text[classStart:]
		if next := pyNextClassRE.FindStringIndex(text[afterEnd:]); next != nil {
			classBody = text[classStart : afterEnd+next[0]]
		}

		isSettings := strings.Contains(baseClass, "BaseSettings")

		// Fields
		var fields []string
		fieldTypes := make(map[string]string)
		for _, fm := range pydanticFieldRE.FindAllStringSubmatch(classBody, -1) {
			fname := fm[1]
			ftype := strings.TrimSpace(fm[2])
			if fname == "class" || fname == "Config" || fname == "model_config" {
				continue
			}
			fields = append(fields, fname)
			fieldTypes[fname] = ftype
		}

		// Validators
		var validators []string
		for _, vm := range pydanticValidatorRE.FindAllStringSubmatch(classBody, -1) {
			validators = append(validators, vm[1])
		}

		// Config sub-class properties
		configProps := make(map[string]string)
		if cm := pydanticConfigClsRE.FindStringIndex(classBody); cm != nil {
			configBlock := classBody[cm[1]:]
			// Cut to the next non-indented line (start of next class/EOF).
			if idx := strings.Index(configBlock, "\n\n"); idx >= 0 {
				configBlock = configBlock[:idx]
			}
			for _, am := range pydanticConfigAttrRE.FindAllStringSubmatch(configBlock, -1) {
				configProps[am[1]] = strings.TrimSpace(am[2])
			}
		}

		nk := model.NodeEntity
		if isSettings {
			nk = model.NodeConfigDefinition
		}

		id := "pydantic:" + filePath + ":model:" + className
		known[className] = id
		n := model.NewCodeNode(id, nk, className)
		n.FQN = filePath + "::" + className
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "PydanticModelDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = validators
		n.Properties["fields"] = fields
		n.Properties["field_types"] = fieldTypes
		n.Properties["framework"] = "pydantic"
		n.Properties["base_class"] = baseClass
		if len(configProps) > 0 {
			n.Properties["config"] = configProps
		}
		nodes = append(nodes, n)

		// EXTENDS edge to known parent
		if tgt, ok := known[baseClass]; ok {
			e := model.NewCodeEdge(id+"->extends->"+tgt, model.EdgeExtends, id, tgt)
			e.Source = "PydanticModelDetector"
			e.Confidence = model.ConfidenceLexical
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}
