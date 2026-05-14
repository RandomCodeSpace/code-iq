package model

import (
	"encoding/json"
	"fmt"
	"strings"
)

// CapabilityLevel mirrors src/main/java/.../intelligence/CapabilityLevel.java.
// Used by language extractors to describe how thoroughly a feature/language is
// covered. Distinct from Confidence (per-edge confidence ladder) — capability
// is a property of an *extractor*, not a single fact.
type CapabilityLevel int

const (
	// CapabilityExact - full semantic understanding (AST-level, cross-file).
	CapabilityExact CapabilityLevel = iota
	// CapabilityPartial - some constructs detected, others may be missed.
	CapabilityPartial
	// CapabilityLexicalOnly - lexical/text search only, no structural analysis.
	CapabilityLexicalOnly
	// CapabilityUnsupported - language or feature is not supported.
	CapabilityUnsupported
)

func (c CapabilityLevel) String() string {
	switch c {
	case CapabilityExact:
		return "EXACT"
	case CapabilityPartial:
		return "PARTIAL"
	case CapabilityLexicalOnly:
		return "LEXICAL_ONLY"
	case CapabilityUnsupported:
		return "UNSUPPORTED"
	default:
		return fmt.Sprintf("capability(%d)", int(c))
	}
}

// ParseCapabilityLevel is case-insensitive.
func ParseCapabilityLevel(s string) (CapabilityLevel, error) {
	switch strings.ToUpper(strings.TrimSpace(s)) {
	case "EXACT":
		return CapabilityExact, nil
	case "PARTIAL":
		return CapabilityPartial, nil
	case "LEXICAL_ONLY":
		return CapabilityLexicalOnly, nil
	case "UNSUPPORTED":
		return CapabilityUnsupported, nil
	}
	return 0, fmt.Errorf("unknown CapabilityLevel: %q", s)
}

func (c CapabilityLevel) MarshalJSON() ([]byte, error) {
	return json.Marshal(c.String())
}

func (c *CapabilityLevel) UnmarshalJSON(data []byte) error {
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	parsed, err := ParseCapabilityLevel(s)
	if err != nil {
		return err
	}
	*c = parsed
	return nil
}
