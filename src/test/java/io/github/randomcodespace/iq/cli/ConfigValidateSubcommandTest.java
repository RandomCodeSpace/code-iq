package io.github.randomcodespace.iq.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidateSubcommandTest {

    @Test
    void validFileReturnsZero(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("codeiq.yml");
        Files.writeString(cfg, "serving:\n  port: 8080\n");
        ConfigValidateSubcommand cmd = new ConfigValidateSubcommand();
        cmd.setPath(cfg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cmd.setOut(new PrintStream(out));
        int rc = cmd.call();
        assertEquals(0, rc);
        assertTrue(out.toString().contains("OK"), "expected OK in output, got: " + out);
    }

    @Test
    void invalidFileReturnsOneAndListsErrors(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("codeiq.yml");
        Files.writeString(cfg, "serving:\n  port: 99999\n"); // out of range
        ConfigValidateSubcommand cmd = new ConfigValidateSubcommand();
        cmd.setPath(cfg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cmd.setOut(new PrintStream(out));
        int rc = cmd.call();
        assertEquals(1, rc);
        assertTrue(
                out.toString().contains("serving.port"),
                "expected field path in error, got: " + out);
    }
}
