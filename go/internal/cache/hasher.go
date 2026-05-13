package cache

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
)

// HashFile returns the lowercase hex SHA-256 digest of the file at path.
// Output matches Java io.github.randomcodespace.iq.cache.FileHasher.hash —
// 64 hex chars, lowercase, SHA-256.
func HashFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// HashString returns the lowercase hex SHA-256 of s (UTF-8 bytes).
// Mirrors Java FileHasher.hashString.
func HashString(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}
