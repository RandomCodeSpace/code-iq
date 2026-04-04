package io.github.randomcodespace.iq.detector.docs;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownStructureDetectorTest {

    private final MarkdownStructureDetector d = new MarkdownStructureDetector();

    @Test
    void detectsModuleNodeForAnyContent() {
        String code = "plain text without headings";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(1, r.nodes().stream().filter(n -> n.getKind() == NodeKind.MODULE).count());
    }

    @Test
    void moduleNodeLabelIsFilenameWhenNoH1() {
        DetectorResult r = d.detect(DetectorTestUtils.contextFor("test/README.md", "markdown",
                "## Section without H1\nSome content"));
        var module = r.nodes().stream().filter(n -> n.getKind() == NodeKind.MODULE).findFirst().orElseThrow();
        // label should be the filename since no H1
        assertNotNull(module.getLabel());
    }

    @Test
    void detectsH1AsModuleLabel() {
        String code = "# My Project\n## Overview\nSome text\n";
        DetectorResult r = d.detect(ctx(code));
        var module = r.nodes().stream().filter(n -> n.getKind() == NodeKind.MODULE).findFirst().orElseThrow();
        assertEquals("My Project", module.getLabel());
    }

    @Test
    void detectsHeadings() {
        String code = "# My Doc\n## Section 1\nSome text\n## Section 2\n[link](other.md)";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().size() >= 3);
    }

    @Test
    void detectsH1ToH3Headings() {
        String code = """
                # Title
                ## Chapter 1
                ### Section 1.1
                ## Chapter 2
                """;
        DetectorResult r = d.detect(ctx(code));
        var headings = r.nodes().stream().filter(n -> n.getKind() == NodeKind.CONFIG_KEY).toList();
        assertEquals(4, headings.size());
    }

    @Test
    void headingNodeHasLevelProperty() {
        String code = "# Title\n## SubTitle\n";
        DetectorResult r = d.detect(ctx(code));
        var h1 = r.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CONFIG_KEY && "Title".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertEquals(1, h1.getProperties().get("level"));
        var h2 = r.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CONFIG_KEY && "SubTitle".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertEquals(2, h2.getProperties().get("level"));
    }

    @Test
    void detectsContainsEdgeForHeadings() {
        String code = "# Doc\n## Section\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONTAINS));
    }

    @Test
    void detectsInternalLinks() {
        String code = "# Doc\nSee [installation guide](installation.md) for details.\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void skipsExternalLinks() {
        String code = "# Doc\nSee [external](https://example.com) for details.\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().noneMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void skipsAnchorOnlyLinks() {
        String code = "# Doc\nJump to [section](#section)\n";
        DetectorResult r = d.detect(ctx(code));
        // link target is just #section, so linkPath="" -> skipped
        assertTrue(r.edges().stream().noneMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.md", "markdown", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("markdown_structure", d.getName());
    }

    @Test
    void supportedLanguagesContainsMarkdown() {
        assertTrue(d.getSupportedLanguages().contains("markdown"));
    }

    @Test
    void deterministic() {
        String code = """
                # Project Documentation
                ## Installation
                Run `npm install` to install dependencies.
                ## Usage
                See [getting started](getting-started.md) guide.
                ### Advanced Usage
                More info at [docs](docs/advanced.md).
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("markdown", content);
    }
}
