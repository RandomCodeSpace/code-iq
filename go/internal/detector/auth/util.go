package auth

import "strconv"

// itoa is a tiny strconv.Itoa wrapper for readable call sites in this package.
func itoa(n int) string { return strconv.Itoa(n) }

// truncate returns s clipped to at most max bytes (no ellipsis added —
// matches Java's String.substring(0, n) semantics).
func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max]
}
