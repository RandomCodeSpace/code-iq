// Binary codeiq is the codeiq CLI entry point. All logic lives in
// internal/cli; this file is just the os.Exit shim.
package main

import (
	"os"

	"github.com/randomcodespace/codeiq/internal/cli"
)

func main() {
	os.Exit(cli.Execute())
}
