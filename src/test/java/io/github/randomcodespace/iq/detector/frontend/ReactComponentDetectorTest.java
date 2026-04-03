package io.github.randomcodespace.iq.detector.frontend;

import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReactComponentDetectorTest {

    private final ReactComponentDetector d = new ReactComponentDetector();

    @Test
    void detectsFunctionComponent() {
        DetectorResult r = d.detect(DetectorTestUtils.contextFor("typescript", "export default function MyApp() {\n  return <div/>;\n}"));
        assertEquals(1, r.nodes().size());
        assertEquals(NodeKind.COMPONENT, r.nodes().get(0).getKind());
        assertEquals("MyApp", r.nodes().get(0).getLabel());
    }

    @Test
    void noMatchOnPlainCode() {
        DetectorResult r = d.detect(DetectorTestUtils.contextFor("typescript", "function lowercase() {}"));
        assertEquals(0, r.nodes().size());
    }

    @Test
    void deterministic() {
        DetectorTestUtils.assertDeterministic(d, DetectorTestUtils.contextFor("typescript",
                "export default function App() {}\nexport function useAuth() {}"));
    }

    @Test
    void rendersEdgesScopedToCorrectComponent() {
        // Button is used only in Header; Footer only uses Icon.
        // Header should get RENDERS->Button, Footer should get RENDERS->Icon, not cross-pollinated.
        String source = """
                export const Header = () => {
                  return <Button label="ok" />;
                };

                export const Footer = () => {
                  return <Icon name="home" />;
                };
                """;
        DetectorResult r = d.detect(DetectorTestUtils.contextFor("typescript", source));

        assertEquals(2, r.nodes().size());

        List<String> headerRenders = r.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.RENDERS && e.getSourceId().contains("Header"))
                .map(e -> e.getTarget().getId())
                .toList();
        List<String> footerRenders = r.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.RENDERS && e.getSourceId().contains("Footer"))
                .map(e -> e.getTarget().getId())
                .toList();

        assertTrue(headerRenders.contains("Button"), "Header should render Button");
        assertFalse(headerRenders.contains("Icon"), "Header should NOT render Icon");
        assertTrue(footerRenders.contains("Icon"), "Footer should render Icon");
        assertFalse(footerRenders.contains("Button"), "Footer should NOT render Button");
    }

    @Test
    void siblingComponentRendersEdgePreserved() {
        // Header and Footer are both in the same file; Header uses <Footer>.
        // The RENDERS edge Header->Footer must NOT be dropped.
        String source = """
                export const Header = () => {
                  return <Footer />;
                };

                export const Footer = () => {
                  return <div />;
                };
                """;
        DetectorResult r = d.detect(DetectorTestUtils.contextFor("typescript", source));

        assertEquals(2, r.nodes().size());

        List<String> headerRenders = r.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.RENDERS && e.getSourceId().contains("Header"))
                .map(e -> e.getTarget().getId())
                .toList();

        assertTrue(headerRenders.contains("Footer"), "Header should render Footer (sibling in same file)");
    }

    @Test
    void selfRenderEdgeNotEmitted() {
        // A component that uses its own name as a JSX tag should NOT produce a self-loop RENDERS edge.
        String source = """
                export const Recursive = () => {
                  return <Recursive />;
                };
                """;
        DetectorResult r = d.detect(DetectorTestUtils.contextFor("typescript", source));

        assertEquals(1, r.nodes().size());
        long selfEdges = r.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.RENDERS
                        && e.getSourceId().contains("Recursive")
                        && e.getTarget().getId().equals("Recursive"))
                .count();
        assertEquals(0, selfEdges, "Self-render edge must not be emitted");
    }

    @Test
    void singleComponentGetsAllJsxTags() {
        String source = """
                export default function Dashboard() {
                  return (
                    <Layout>
                      <Sidebar />
                      <MainContent />
                    </Layout>
                  );
                }
                """;
        DetectorResult r = d.detect(DetectorTestUtils.contextFor("typescript", source));
        assertEquals(1, r.nodes().size());
        List<String> rendered = r.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.RENDERS)
                .map(e -> e.getTarget().getId())
                .toList();
        assertTrue(rendered.contains("Layout"));
        assertTrue(rendered.contains("Sidebar"));
        assertTrue(rendered.contains("MainContent"));
    }
}
