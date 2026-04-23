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

    /** Convenience bundle: a freshly-wired subcommand with captured stdout/stderr. */
    private record Harness(
            ConfigValidateSubcommand cmd, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        static Harness at(Path path) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            ConfigValidateSubcommand cmd =
                    new ConfigValidateSubcommand(new PrintStream(o), new PrintStream(e));
            cmd.setPath(path);
            return new Harness(cmd, o, e);
        }

        String stdout() {
            return out.toString();
        }

        String stderr() {
            return err.toString();
        }
    }

    @Test
    void validFileReturnsZeroAndWritesOkToStdout(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("codeiq.yml");
        Files.writeString(cfg, "serving:\n  port: 8080\n");
        Harness h = Harness.at(cfg);

        int rc = h.cmd.call();

        assertEquals(0, rc);
        assertTrue(h.stdout().contains("OK"), "expected OK in stdout, got: " + h.stdout());
        assertEquals("", h.stderr(), "stderr must be empty on valid config, got: " + h.stderr());
    }

    @Test
    void invalidFileReturnsOneAndListsErrorsOnStderr(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("codeiq.yml");
        Files.writeString(cfg, "serving:\n  port: 99999\n"); // out of range
        Harness h = Harness.at(cfg);

        int rc = h.cmd.call();

        assertEquals(1, rc);
        assertTrue(
                h.stderr().contains("serving.port"),
                "expected field path in stderr, got: " + h.stderr());
        assertEquals(
                "",
                h.stdout(),
                "stdout must be empty when the config is invalid, got: " + h.stdout());
    }

    @Test
    void missingFileReturnsOneAndPrintsLoadErrorToStderr(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.yml");
        Harness h = Harness.at(missing);

        int rc = h.cmd.call();

        assertEquals(1, rc);
        assertTrue(
                h.stderr().contains("Load error"),
                "expected 'Load error' in stderr, got: " + h.stderr());
        assertEquals(
                "",
                h.stdout(),
                "stdout must be empty on load failure, got: " + h.stdout());
    }

    @Test
    void malformedYamlReturnsOneAndPrintsLoadErrorToStderr(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("codeiq.yml");
        // Unclosed quoted string + mixed indentation -- SnakeYAML rejects this.
        Files.writeString(cfg, "serving:\n  port: \"8080\n  host: \"broken\n");
        Harness h = Harness.at(cfg);

        int rc = h.cmd.call();

        assertEquals(1, rc);
        assertTrue(
                h.stderr().contains("Load error"),
                "expected 'Load error' in stderr, got: " + h.stderr());
    }

    @Test
    void emptyFileIsValidAndReturnsZero(@TempDir Path tmp) throws Exception {
        // An empty codeiq.yml parses to an empty overlay; merged with the built-in
        // defaults the resulting effective config satisfies ConfigValidator.
        Path cfg = tmp.resolve("codeiq.yml");
        Files.writeString(cfg, "");
        Harness h = Harness.at(cfg);

        int rc = h.cmd.call();

        assertEquals(0, rc);
        assertTrue(h.stdout().contains("OK"), "expected OK in stdout, got: " + h.stdout());
    }

    @Test
    void validationErrorsPrintedInSortedOrder(@TempDir Path tmp) throws Exception {
        // Craft a YAML that trips three distinct validator field paths. After the
        // Comparator applied in call(), the expected alphabetical-by-fieldPath
        // order is: indexing.batch_size, mcp.transport, serving.port.
        Path cfg = tmp.resolve("codeiq.yml");
        Files.writeString(
                cfg,
                """
                serving:
                  port: 99999
                indexing:
                  batch_size: 0
                mcp:
                  transport: carrier-pigeon
                """);
        Harness h = Harness.at(cfg);

        int rc = h.cmd.call();

        assertEquals(1, rc);
        String stderr = h.stderr();
        int idxBatch = stderr.indexOf("indexing.batch_size");
        int idxTransport = stderr.indexOf("mcp.transport");
        int idxPort = stderr.indexOf("serving.port");
        assertTrue(idxBatch >= 0, "missing indexing.batch_size in: " + stderr);
        assertTrue(idxTransport >= 0, "missing mcp.transport in: " + stderr);
        assertTrue(idxPort >= 0, "missing serving.port in: " + stderr);
        assertTrue(
                idxBatch < idxTransport && idxTransport < idxPort,
                "errors must be sorted by fieldPath. got order indices: "
                        + idxBatch
                        + "/"
                        + idxTransport
                        + "/"
                        + idxPort
                        + "; stderr was:\n"
                        + stderr);
    }
}
