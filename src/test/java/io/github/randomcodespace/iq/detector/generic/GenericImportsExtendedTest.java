package io.github.randomcodespace.iq.detector.generic;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenericImportsExtendedTest {

    private final GenericImportsDetector d = new GenericImportsDetector();

    @Test
    void detectsRubyRequire() {
        String code = """
                require 'json'
                require_relative 'helper'
                class UserService < BaseService
                  def create_user
                  end
                  def delete_user
                  end
                end
                """;
        var r = d.detect(DetectorTestUtils.contextFor("ruby", code));
        assertFalse(r.nodes().isEmpty());
        assertFalse(r.edges().isEmpty());
    }

    @Test
    void detectsSwiftImportAndClass() {
        String code = """
                import Foundation
                import UIKit
                class ViewController: UIViewController {
                    override func viewDidLoad() {
                    }
                    func configure() {
                    }
                }
                struct Config {
                }
                """;
        var r = d.detect(DetectorTestUtils.contextFor("swift", code));
        assertFalse(r.nodes().isEmpty());
    }

    @Test
    void detectsPerlPackageAndSub() {
        String code = """
                package MyApp::Controller;
                use strict;
                use warnings;
                use Moose;
                sub new {
                    my $class = shift;
                }
                sub handle_request {
                }
                """;
        var r = d.detect(DetectorTestUtils.contextFor("perl", code));
        assertFalse(r.nodes().isEmpty());
    }

    @Test
    void detectsLuaRequireAndFunction() {
        String code = """
                local json = require("cjson")
                local http = require("socket.http")
                function handle_request(req)
                end
                local function helper(x)
                end
                """;
        var r = d.detect(DetectorTestUtils.contextFor("lua", code));
        assertFalse(r.nodes().isEmpty());
    }

    @Test
    void detectsDartImportAndClass() {
        String code = """
                import 'dart:convert';
                import 'package:flutter/material.dart';
                abstract class BaseWidget extends StatefulWidget {
                }
                class MyWidget extends BaseWidget implements Disposable {
                }
                """;
        var r = d.detect(DetectorTestUtils.contextFor("dart", code));
        assertFalse(r.nodes().isEmpty());
    }

    @Test
    void detectsRLibraryAndFunction() {
        String code = """
                library(ggplot2)
                require(dplyr)
                process_data <- function(df) {
                    df %>% filter(x > 0)
                }
                analyze <- function(data) {
                    summary(data)
                }
                """;
        var r = d.detect(DetectorTestUtils.contextFor("r", code));
        assertFalse(r.nodes().isEmpty());
    }

    @Test
    void emptyContentReturnsEmpty() {
        var r = d.detect(DetectorTestUtils.contextFor("ruby", ""));
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void unsupportedLanguageReturnsEmpty() {
        var r = d.detect(DetectorTestUtils.contextFor("java", "import java.util.List;"));
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void isDeterministic() {
        String code = "require 'json'\nclass Foo < Bar\nend\ndef baz\nend\n";
        DetectorTestUtils.assertDeterministic(d, DetectorTestUtils.contextFor("ruby", code));
    }
}
