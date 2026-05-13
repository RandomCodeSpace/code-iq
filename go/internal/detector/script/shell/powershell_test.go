package shell

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const psSource = `Import-Module MyModule
. .\helpers.ps1
. "$PSScriptRoot\utils.psm1"

function Deploy-Stack {
    [CmdletBinding()]
    param (
        [Parameter(Mandatory)] [string]$Name,
        [Parameter()] [int]$Port
    )
    Write-Host "deploying $Name on $Port"
}

function Simple-Func {
    Write-Host "hi"
}
`

func TestPowerShellPositive(t *testing.T) {
	d := NewPowerShellDetector()
	r := d.Detect(&detector.Context{FilePath: "Deploy.ps1", Language: "powershell", Content: psSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	// 2 functions
	if kinds[model.NodeMethod] != 2 {
		t.Errorf("expected 2 METHOD, got %d", kinds[model.NodeMethod])
	}
	// 2 params
	if kinds[model.NodeConfigDefinition] != 2 {
		t.Errorf("expected 2 CONFIG_DEFINITION (params), got %d", kinds[model.NodeConfigDefinition])
	}

	// 1 Import-Module + 2 dot-source = 3 IMPORTS
	imports := 0
	for _, e := range r.Edges {
		if e.Kind == model.EdgeImports {
			imports++
		}
	}
	if imports != 3 {
		t.Errorf("expected 3 IMPORTS, got %d", imports)
	}
}

func TestPowerShellAdvancedFunction(t *testing.T) {
	d := NewPowerShellDetector()
	r := d.Detect(&detector.Context{FilePath: "Deploy.ps1", Language: "powershell", Content: psSource})
	advanced := false
	for _, n := range r.Nodes {
		if n.Label == "Deploy-Stack" && n.Properties["advanced_function"] == true {
			advanced = true
		}
	}
	if !advanced {
		t.Error("expected Deploy-Stack to be advanced (CmdletBinding)")
	}
}

func TestPowerShellNegative(t *testing.T) {
	d := NewPowerShellDetector()
	r := d.Detect(&detector.Context{FilePath: "x.ps1", Language: "powershell", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestPowerShellDeterminism(t *testing.T) {
	d := NewPowerShellDetector()
	ctx := &detector.Context{FilePath: "Deploy.ps1", Language: "powershell", Content: psSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
