package main

import (
	"fmt"

	"github.com/randomcodespace/codeiq/internal/detector"
	// Same blank imports as the CLI uses
	_ "github.com/randomcodespace/codeiq/internal/detector/auth"
	_ "github.com/randomcodespace/codeiq/internal/detector/csharp"
	_ "github.com/randomcodespace/codeiq/internal/detector/frontend"
	_ "github.com/randomcodespace/codeiq/internal/detector/generic"
	_ "github.com/randomcodespace/codeiq/internal/detector/golang"
	_ "github.com/randomcodespace/codeiq/internal/detector/iac"
	_ "github.com/randomcodespace/codeiq/internal/detector/jvm/java"
	_ "github.com/randomcodespace/codeiq/internal/detector/jvm/kotlin"
	_ "github.com/randomcodespace/codeiq/internal/detector/jvm/scala"
	_ "github.com/randomcodespace/codeiq/internal/detector/markup"
	_ "github.com/randomcodespace/codeiq/internal/detector/proto"
	_ "github.com/randomcodespace/codeiq/internal/detector/python"
	_ "github.com/randomcodespace/codeiq/internal/detector/script/shell"
	_ "github.com/randomcodespace/codeiq/internal/detector/sql"
	_ "github.com/randomcodespace/codeiq/internal/detector/structured"
	_ "github.com/randomcodespace/codeiq/internal/detector/systems/cpp"
	_ "github.com/randomcodespace/codeiq/internal/detector/systems/rust"
	_ "github.com/randomcodespace/codeiq/internal/detector/typescript"
)

func main() {
	for _, lang := range []string{"terraform", "csharp", "kotlin", "vue", "bash", "rust", "powershell"} {
		dets := detector.Default.For(lang)
		fmt.Printf("%-12s: %d detectors\n", lang, len(dets))
	}
}
