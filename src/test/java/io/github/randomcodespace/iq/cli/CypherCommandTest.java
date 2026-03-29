package io.github.randomcodespace.iq.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CypherCommandTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capture;

    @BeforeEach
    void setUp() {
        capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void cypherShowsRestApiGuidance() {
        var cmd = new CypherCommand();
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute("MATCH (n) RETURN n LIMIT 10");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("REST API"), "Should mention REST API");
        assertTrue(output.contains("MATCH (n) RETURN n LIMIT 10"),
                "Should echo the query");
    }
}
