"""Tests for the generic multi-language imports detector."""

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.generic.imports_detector import GenericImportsDetector
from osscodeiq.models.graph import NodeKind, EdgeKind


def _ctx(content: str, language: str, path: str = "test_file"):
    return DetectorContext(
        file_path=path, language=language, content=content.encode(), module_name="test",
    )


class TestGenericImportsDetector:
    def setup_method(self):
        self.detector = GenericImportsDetector()

    def test_unsupported_language(self):
        result = self.detector.detect(_ctx("something", "python", "test.py"))
        assert len(result.nodes) == 0
        assert len(result.edges) == 0

    # ---- Ruby ----
    def test_ruby_require(self):
        src = "require 'json'\nrequire_relative 'utils'"
        result = self.detector.detect(_ctx(src, "ruby", "app.rb"))
        imports = [e for e in result.edges if e.kind == EdgeKind.IMPORTS]
        assert len(imports) == 2
        targets = {e.target for e in imports}
        assert "json" in targets
        assert "utils" in targets

    def test_ruby_class_with_inheritance(self):
        src = "class Dog < Animal\n  def bark\n    puts 'woof'\n  end\nend"
        result = self.detector.detect(_ctx(src, "ruby", "dog.rb"))
        classes = [n for n in result.nodes if n.kind == NodeKind.CLASS]
        assert len(classes) == 1
        assert classes[0].label == "Dog"
        extends = [e for e in result.edges if e.kind == EdgeKind.EXTENDS]
        assert len(extends) == 1
        assert extends[0].target == "Animal"

    def test_ruby_module_and_method(self):
        src = "module Utils\n  def helper\n  end\nend"
        result = self.detector.detect(_ctx(src, "ruby", "utils.rb"))
        modules = [n for n in result.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        methods = [n for n in result.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 1

    # ---- Swift ----
    def test_swift_import(self):
        src = "import Foundation\nimport UIKit"
        result = self.detector.detect(_ctx(src, "swift", "App.swift"))
        imports = [e for e in result.edges if e.kind == EdgeKind.IMPORTS]
        assert len(imports) == 2

    def test_swift_class_with_inheritance(self):
        src = "class ViewController: UIViewController, UITableViewDelegate {\n  func viewDidLoad() {\n  }\n}"
        result = self.detector.detect(_ctx(src, "swift", "VC.swift"))
        classes = [n for n in result.nodes if n.kind == NodeKind.CLASS]
        assert len(classes) == 1
        extends = [e for e in result.edges if e.kind == EdgeKind.EXTENDS]
        assert len(extends) >= 1

    def test_swift_protocol(self):
        src = "protocol Drawable {\n  func draw()\n}"
        result = self.detector.detect(_ctx(src, "swift", "Proto.swift"))
        protos = [n for n in result.nodes if n.kind == NodeKind.INTERFACE]
        assert len(protos) == 1
        assert protos[0].label == "Drawable"

    def test_swift_struct_and_enum(self):
        src = "struct Point {\n  var x: Int\n}\nenum Direction {\n  case north\n}"
        result = self.detector.detect(_ctx(src, "swift", "Types.swift"))
        structs = [n for n in result.nodes if n.kind == NodeKind.CLASS and n.properties.get("type") == "struct"]
        assert len(structs) == 1
        enums = [n for n in result.nodes if n.kind == NodeKind.ENUM]
        assert len(enums) == 1

    # ---- Perl ----
    def test_perl_use(self):
        src = "use strict;\nuse warnings;\nuse Data::Dumper;"
        result = self.detector.detect(_ctx(src, "perl", "script.pl"))
        imports = [e for e in result.edges if e.kind == EdgeKind.IMPORTS]
        assert len(imports) == 3

    def test_perl_package_and_sub(self):
        src = "package MyApp::Utils;\nsub process {\n  my ($self) = @_;\n}"
        result = self.detector.detect(_ctx(src, "perl", "Utils.pm"))
        modules = [n for n in result.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "MyApp::Utils"
        methods = [n for n in result.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 1

    # ---- Lua ----
    def test_lua_require(self):
        src = "local json = require('cjson')\nlocal utils = require('lib.utils')"
        result = self.detector.detect(_ctx(src, "lua", "main.lua"))
        imports = [e for e in result.edges if e.kind == EdgeKind.IMPORTS]
        assert len(imports) == 2

    def test_lua_function(self):
        src = "function greet(name)\n  print('Hello ' .. name)\nend\nlocal function helper()\nend"
        result = self.detector.detect(_ctx(src, "lua", "funcs.lua"))
        methods = [n for n in result.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 2

    # ---- Dart ----
    def test_dart_import(self):
        src = "import 'dart:io';\nimport 'package:flutter/material.dart';"
        result = self.detector.detect(_ctx(src, "dart", "main.dart"))
        imports = [e for e in result.edges if e.kind == EdgeKind.IMPORTS]
        assert len(imports) == 2

    def test_dart_class_extends_implements(self):
        src = "class MyWidget extends StatelessWidget implements Comparable {\n}"
        result = self.detector.detect(_ctx(src, "dart", "widget.dart"))
        classes = [n for n in result.nodes if n.kind == NodeKind.CLASS]
        assert len(classes) == 1
        extends = [e for e in result.edges if e.kind == EdgeKind.EXTENDS]
        assert len(extends) == 1
        implements = [e for e in result.edges if e.kind == EdgeKind.IMPLEMENTS]
        assert len(implements) == 1

    # ---- R ----
    def test_r_library(self):
        src = "library(ggplot2)\nrequire(dplyr)"
        result = self.detector.detect(_ctx(src, "r", "analysis.R"))
        imports = [e for e in result.edges if e.kind == EdgeKind.IMPORTS]
        assert len(imports) == 2

    def test_r_function(self):
        src = "process_data <- function(df) {\n  df %>% filter(x > 0)\n}"
        result = self.detector.detect(_ctx(src, "r", "funcs.R"))
        methods = [n for n in result.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 1
        assert methods[0].label == "process_data"

    # ---- Determinism ----
    def test_determinism(self):
        src = "require 'a'\nclass Foo < Bar\n  def baz\n  end\nend"
        r1 = self.detector.detect(_ctx(src, "ruby", "test.rb"))
        r2 = self.detector.detect(_ctx(src, "ruby", "test.rb"))
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert [(e.source, e.target) for e in r1.edges] == [(e.source, e.target) for e in r2.edges]
