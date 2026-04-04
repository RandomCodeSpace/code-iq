package io.github.randomcodespace.iq.detector.rust;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RustStructuresDetectorTest {

    private final RustStructuresDetector d = new RustStructuresDetector();

    @Test
    void detectsStruct() {
        String code = "pub struct User {\n    name: String,\n    age: u32,\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "User".equals(n.getLabel())));
    }

    @Test
    void detectsTrait() {
        String code = "pub trait Serializable {\n    fn serialize(&self) -> String;\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE && "Serializable".equals(n.getLabel())));
    }

    @Test
    void detectsEnum() {
        String code = "pub enum Color {\n    Red,\n    Green,\n    Blue,\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENUM && "Color".equals(n.getLabel())));
    }

    @Test
    void detectsFunction() {
        String code = "pub fn process(input: &str) -> Result<String, Error> {\n    Ok(input.to_string())\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "process".equals(n.getLabel())));
    }

    @Test
    void detectsAsyncFunction() {
        String code = "pub async fn fetch_data(url: &str) -> reqwest::Result<String> {\n    Ok(String::new())\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "fetch_data".equals(n.getLabel())));
    }

    @Test
    void detectsMod() {
        String code = "pub mod handlers;\nmod internal;\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.MODULE).count());
    }

    @Test
    void detectsUseStatement() {
        String code = "use std::collections::HashMap;\nuse crate::models::User;\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsImplBlock() {
        String code = "struct Foo {}\nimpl Foo {\n    fn bar(&self) {}\n}\n";
        DetectorResult r = d.detect(ctx(code));
        // Should produce a DEFINES edge (impl without for)
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEFINES));
    }

    @Test
    void detectsImplTrait() {
        String code = "trait Display {}\nstruct Foo {}\nimpl Display for Foo {\n    fn fmt(&self) {}\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPLEMENTS));
    }

    @Test
    void detectsMacro() {
        String code = "macro_rules! my_macro {\n    () => {};\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getLabel().contains("my_macro")));
    }

    @Test
    void detectsStructAndTrait() {
        DetectorResult r = d.detect(ctx("pub struct User {}\npub trait Serialize {}"));
        assertTrue(r.nodes().size() >= 2);
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.rs", "rust", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("rust_structures", d.getName());
    }

    @Test
    void supportedLanguagesContainsRust() {
        assertTrue(d.getSupportedLanguages().contains("rust"));
    }

    @Test
    void deterministic() {
        String code = """
                use std::collections::HashMap;
                mod handlers;
                pub struct Config {
                    name: String,
                }
                pub trait Configurable {
                    fn configure(&self);
                }
                pub enum Status { Active, Inactive }
                impl Config {
                    pub fn new() -> Self { Config { name: String::new() } }
                }
                impl Configurable for Config {
                    fn configure(&self) {}
                }
                pub fn run() {}
                macro_rules! log_info { ($msg:expr) => {}; }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("rust", content);
    }
}
