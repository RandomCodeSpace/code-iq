package cli

// Side-effect imports: each detector package's init() registers itself with
// the process-wide Default registry. Without these imports the linker would
// drop the packages and the CLI binary would ship with the registry empty.
//
// Keep this list flat (leaf packages only) and exhaustive — any detector
// package added under internal/detector/ must land here too.
import (
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
