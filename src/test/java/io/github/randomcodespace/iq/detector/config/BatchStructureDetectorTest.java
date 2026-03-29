package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BatchStructureDetectorTest {

    private final BatchStructureDetector detector = new BatchStructureDetector();

    @Test
    void positiveMatch() {
        String batch = """
                @ECHO OFF
                REM Build script
                SET PROJECT_DIR=src

                :BUILD
                echo Building...
                CALL :TEST

                :TEST
                echo Testing...
                """;
        DetectorContext ctx = new DetectorContext("build.bat", "batch", batch);
        DetectorResult result = detector.detect(ctx);

        // 1 module + 2 labels + 1 SET variable = 4 nodes
        assertEquals(4, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_DEFINITION));
        // CONTAINS edges + CALLS edge
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONTAINS));
    }

    @Test
    void negativeMatch_emptyContent() {
        DetectorContext ctx = new DetectorContext("empty.bat", "batch", "");
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        String batch = ":START\necho hello\nSET X=1\nCALL :START";
        DetectorContext ctx = new DetectorContext("test.bat", "batch", batch);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
