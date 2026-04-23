package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ANTLR parser infrastructure.
 * Verifies that each language's parser can be instantiated and can parse
 * simple code snippets, and that concurrent parsing is safe.
 */
class AntlrInfrastructureTest {

    static Stream<Arguments> languageSnippets() {
        return Stream.of(
                Arguments.of("python", """
                        def hello(name: str) -> str:
                            return f"Hello, {name}"

                        class Greeter:
                            def greet(self):
                                pass
                        """),
                Arguments.of("go", """
                        package main

                        import "fmt"

                        func hello(name string) string {
                            return fmt.Sprintf("Hello, %s", name)
                        }

                        type Greeter struct {
                            Name string
                        }
                        """),
                Arguments.of("csharp", """
                        using System;

                        namespace MyApp
                        {
                            public class Greeter
                            {
                                public string Hello(string name)
                                {
                                    return $"Hello, {name}";
                                }
                            }
                        }
                        """),
                Arguments.of("rust", """
                        fn hello(name: &str) -> String {
                            format!("Hello, {}", name)
                        }

                        struct Greeter {
                            name: String,
                        }

                        impl Greeter {
                            fn greet(&self) -> String {
                                hello(&self.name)
                            }
                        }
                        """),
                Arguments.of("kotlin", """
                        package com.example

                        fun hello(name: String): String {
                            return "Hello, $name"
                        }

                        class Greeter(val name: String) {
                            fun greet(): String = hello(name)
                        }
                        """),
                Arguments.of("scala", """
                        package com.example

                        object Main {
                          def hello(name: String): String = {
                            s"Hello, $name"
                          }
                        }

                        class Greeter(name: String) {
                          def greet(): String = Main.hello(name)
                        }
                        """),
                Arguments.of("cpp", """
                        #include <string>
                        #include <iostream>

                        std::string hello(const std::string& name) {
                            return "Hello, " + name;
                        }

                        class Greeter {
                        public:
                            std::string name;
                            std::string greet() {
                                return hello(name);
                            }
                        };
                        """)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("languageSnippets")
    void parsesSimpleCodeSnippet(String language, String code) {
        ParseTree tree = AntlrParserFactory.parse(language, code);

        assertNotNull(tree, "Parse tree should not be null for " + language);
        assertTrue(tree.getChildCount() > 0,
                "Parse tree should have children for " + language);
    }

    @Test
    void unsupportedLanguageReturnsNull() {
        assertNull(AntlrParserFactory.parse("brainfuck", "+++."));
        assertNull(AntlrParserFactory.parse(null, "code"));
        assertNull(AntlrParserFactory.parse("python", null));
        assertNull(AntlrParserFactory.parse("python", ""));
        assertNull(AntlrParserFactory.parse("python", "   "));
    }

    @Test
    void isSupportedReportsCorrectly() {
        assertTrue(AntlrParserFactory.isSupported("python"));
        assertTrue(AntlrParserFactory.isSupported("Python")); // case-insensitive
        assertTrue(AntlrParserFactory.isSupported("typescript"));
        assertTrue(AntlrParserFactory.isSupported("cpp"));
        assertFalse(AntlrParserFactory.isSupported("brainfuck"));
        assertFalse(AntlrParserFactory.isSupported(null));
    }

    @Test
    void typeScriptAndJavaScriptSkippedForPerformance() {
        // TypeScript/JavaScript ANTLR grammars have exponential ATN prediction on certain
        // inputs. All TS/JS detectors have comprehensive regex fallback, so ANTLR is skipped.
        String tsCode = """
                interface Greeter {
                    greet(): string;
                }
                """;
        String jsCode = """
                function hello(name) {
                    return `Hello, ${name}`;
                }
                """;
        assertNull(AntlrParserFactory.parse("typescript", tsCode),
                "TypeScript should be skipped (regex fallback)");
        assertNull(AntlrParserFactory.parse("javascript", jsCode),
                "JavaScript should be skipped (regex fallback)");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("languageSnippets")
    void deterministicParsing(String language, String code) {
        // Parse twice, verify same tree structure
        ParseTree tree1 = AntlrParserFactory.parse(language, code);
        ParseTree tree2 = AntlrParserFactory.parse(language, code);

        assertNotNull(tree1);
        assertNotNull(tree2);
        assertEquals(tree1.toStringTree(), tree2.toStringTree(),
                "Parse tree should be identical across runs for " + language);
    }

    @Test
    void concurrentParsingIsSafe() throws InterruptedException {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<String> results = new CopyOnWriteArrayList<>();

        String pythonCode = """
                def hello():
                    return "world"
                """;

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ParseTree tree = AntlrParserFactory.parse("python", pythonCode);
                    assertNotNull(tree);
                    results.add(tree.toStringTree());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Threads should complete within 30s");
        executor.shutdown();

        assertTrue(errors.isEmpty(),
                "No errors should occur during concurrent parsing: " + errors);
        assertEquals(threadCount, results.size());

        // All results should be identical (determinism)
        String expected = results.getFirst();
        for (String result : results) {
            assertEquals(expected, result,
                    "All threads should produce the same parse tree");
        }
    }

    @Test
    void abstractAntlrDetectorFallsBackToRegex() {
        // Test that a concrete subclass properly falls back when parse returns null
        var detector = new AbstractAntlrDetector() {
            @Override
            public String getName() { return "test-detector"; }

            @Override
            public java.util.Set<String> getSupportedLanguages() {
                return java.util.Set.of("test");
            }

            @Override
            protected ParseTree parse(DetectorContext ctx) {
                return null; // Simulate unsupported language
            }

            @Override
            protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
                fail("Should not be called when parse returns null");
                return DetectorResult.empty();
            }

            @Override
            protected DetectorResult detectWithRegex(DetectorContext ctx) {
                // Return non-empty to prove fallback was called
                return DetectorResult.of(List.of(), List.of());
            }
        };

        DetectorResult result = detector.detect(
                new DetectorContext("test.ts", "test", "some code"));
        assertNotNull(result, "Fallback should return a result");
    }

    @Test
    void abstractAntlrDetectorFallsBackOnException() {
        var detector = new AbstractAntlrDetector() {
            @Override
            public String getName() { return "test-detector"; }

            @Override
            public java.util.Set<String> getSupportedLanguages() {
                return java.util.Set.of("test");
            }

            @Override
            protected ParseTree parse(DetectorContext ctx) {
                throw new RuntimeException("Simulated parse failure");
            }

            @Override
            protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
                fail("Should not be called when parse throws");
                return DetectorResult.empty();
            }
        };

        // Should not throw - falls back gracefully
        DetectorResult result = detector.detect(
                new DetectorContext("test.ts", "test", "some code"));
        assertNotNull(result);
    }
}
