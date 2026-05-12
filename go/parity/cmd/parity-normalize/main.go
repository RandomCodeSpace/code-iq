// Binary parity-normalize reads a codeiq SQLite cache and writes a normalized
// JSON dump to stdout. Used by the go-parity CI workflow to convert both
// Java and Go outputs into a diff-friendly canonical form.
package main

import (
	"fmt"
	"os"

	"github.com/randomcodespace/codeiq/go/internal/cache"
	"github.com/randomcodespace/codeiq/go/parity"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: parity-normalize <sqlite-path>")
		os.Exit(1)
	}
	c, err := cache.Open(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, "open:", err)
		os.Exit(2)
	}
	defer c.Close()
	out, err := parity.Normalize(c)
	if err != nil {
		fmt.Fprintln(os.Stderr, "normalize:", err)
		os.Exit(2)
	}
	fmt.Print(out)
}
