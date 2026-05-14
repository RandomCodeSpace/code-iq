package model

import (
	"encoding/json"
	"fmt"
	"strings"
)

// Confidence is the three-tier confidence ladder: pattern → structure → resolved.
// Values are ordered such that LEXICAL < SYNTACTIC < RESOLVED — direct integer
// comparison matches the Java Comparable contract.
type Confidence int

const (
	ConfidenceLexical   Confidence = iota // regex / textual pattern only
	ConfidenceSyntactic                   // AST / parse tree match
	ConfidenceResolved                    // resolved via SymbolResolver
)

// Score returns the canonical numeric mapping from the Java side:
// LEXICAL=0.6, SYNTACTIC=0.8, RESOLVED=0.95.
func (c Confidence) Score() float64 {
	switch c {
	case ConfidenceLexical:
		return 0.6
	case ConfidenceSyntactic:
		return 0.8
	case ConfidenceResolved:
		return 0.95
	default:
		return 0
	}
}

func (c Confidence) String() string {
	switch c {
	case ConfidenceLexical:
		return "LEXICAL"
	case ConfidenceSyntactic:
		return "SYNTACTIC"
	case ConfidenceResolved:
		return "RESOLVED"
	default:
		return fmt.Sprintf("confidence(%d)", int(c))
	}
}

// ParseConfidence is case-insensitive.
func ParseConfidence(s string) (Confidence, error) {
	switch strings.ToUpper(strings.TrimSpace(s)) {
	case "LEXICAL":
		return ConfidenceLexical, nil
	case "SYNTACTIC":
		return ConfidenceSyntactic, nil
	case "RESOLVED":
		return ConfidenceResolved, nil
	}
	return 0, fmt.Errorf("unknown Confidence: %q", s)
}

func (c Confidence) MarshalJSON() ([]byte, error) {
	return json.Marshal(c.String())
}

func (c *Confidence) UnmarshalJSON(data []byte) error {
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	parsed, err := ParseConfidence(s)
	if err != nil {
		return err
	}
	*c = parsed
	return nil
}
