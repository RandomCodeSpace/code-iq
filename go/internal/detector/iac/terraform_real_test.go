package iac

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
)

// TestTerraform_RealFile catches the regression where TerraformDetector
// fires on synthetic fixtures but produces zero nodes on the public
// terraform-aws-eks repository (see PR #130 benchmark report).
func TestTerraform_RealFile(t *testing.T) {
	// Inline a representative slice of terraform-aws-eks/main.tf.
	content := `data "aws_partition" "current" {
  count = local.create ? 1 : 0
}
data "aws_caller_identity" "current" {
  count = local.create ? 1 : 0
}

resource "aws_eks_cluster" "this" {
  count = local.create ? 1 : 0

  name     = var.name
  role_arn = local.cluster_role
  version  = var.kubernetes_version
}

variable "name" {
  type    = string
  default = "my-cluster"
}

output "cluster_endpoint" {
  value = try(aws_eks_cluster.this[0].endpoint, null)
}

module "vpc" {
  source = "terraform-aws-modules/vpc/aws"
}
`
	d := NewTerraformDetector()
	r := d.Detect(&detector.Context{
		FilePath: "main.tf",
		Language: "terraform",
		Content:  content,
	})
	wantMin := 6 // 2 data + 1 resource + 1 var + 1 output + 1 module
	if len(r.Nodes) < wantMin {
		t.Fatalf("expected >=%d nodes, got %d", wantMin, len(r.Nodes))
	}
}

// TestTerraform_AwsEksHEAD verifies the detector against the actual file
// from terraform-aws-eks/main.tf when present. Skips if the local clone
// isn't available.
func TestTerraform_AwsEksHEAD(t *testing.T) {
	path := "/home/dev/projects/polyglot-bench/terraform-aws-eks/main.tf"
	if _, err := os.Stat(path); err != nil {
		t.Skipf("test fixture not present: %s", filepath.Base(path))
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	d := NewTerraformDetector()
	r := d.Detect(&detector.Context{
		FilePath: "main.tf",
		Language: "terraform",
		Content:  string(b),
	})
	if len(r.Nodes) == 0 {
		t.Fatalf("expected nodes from main.tf, got 0 (file is %d bytes)", len(b))
	}
	t.Logf("main.tf: %d nodes", len(r.Nodes))
}
