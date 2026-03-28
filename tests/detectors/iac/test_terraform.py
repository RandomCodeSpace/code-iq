"""Tests for TerraformDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.iac.terraform import TerraformDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="main.tf"):
    return DetectorContext(
        file_path=path,
        language="terraform",
        content=content.encode(),
    )


class TestTerraformDetector:
    def setup_method(self):
        self.detector = TerraformDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "terraform"
        assert self.detector.supported_languages == ("terraform",)

    def test_detects_resources_and_variables(self):
        hcl = '''\
provider "aws" {
  region = "us-east-1"
}

variable "instance_type" {
  default = "t2.micro"
}

resource "aws_instance" "web" {
  ami           = "ami-123456"
  instance_type = var.instance_type
}

output "instance_id" {
  value = aws_instance.web.id
}
'''
        ctx = _ctx(hcl)
        r = self.detector.detect(ctx)
        # INFRA_RESOURCE nodes: provider + resource
        infra = [n for n in r.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra) == 2
        labels = {n.label for n in infra}
        assert "aws_instance.web" in labels
        assert "provider.aws" in labels
        # CONFIG_DEFINITION nodes: variable + output
        config_defs = [n for n in r.nodes if n.kind == NodeKind.CONFIG_DEFINITION]
        assert len(config_defs) == 2
        config_labels = {n.label for n in config_defs}
        assert "var.instance_type" in config_labels
        assert "output.instance_id" in config_labels

    def test_irrelevant_content_returns_empty(self):
        ctx = _ctx("# just a comment\nlocals {\n  foo = bar\n}")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        hcl = 'resource "azurerm_resource_group" "rg" {\n  name = "test"\n}\n'
        ctx = _ctx(hcl)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_module_with_source(self):
        hcl = '''\
module "vpc" {
  source = "terraform-aws-modules/vpc/aws"
  cidr   = "10.0.0.0/16"
}
'''
        ctx = _ctx(hcl)
        r = self.detector.detect(ctx)
        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "module.vpc"
        assert modules[0].properties["source"] == "terraform-aws-modules/vpc/aws"
        dep_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(dep_edges) == 1

    def test_data_source(self):
        hcl = 'data "aws_ami" "latest" {\n  most_recent = true\n}\n'
        ctx = _ctx(hcl)
        r = self.detector.detect(ctx)
        infra = [n for n in r.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra) == 1
        assert infra[0].label == "data.aws_ami.latest"
        assert infra[0].properties.get("data_source") is True

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
