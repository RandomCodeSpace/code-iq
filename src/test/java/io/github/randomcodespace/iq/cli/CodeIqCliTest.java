package io.github.randomcodespace.iq.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIqCliTest {

    @Test
    void cliHasCorrectName() {
        var cli = new CodeIqCli();
        var cmdLine = new CommandLine(cli);
        assertEquals("code-iq", cmdLine.getCommandName());
    }

    @Test
    void cliHasAllSubcommands() {
        var cli = new CodeIqCli();
        var cmdLine = new CommandLine(cli);
        var subcommands = cmdLine.getSubcommands();

        String[] expectedNames = {
                "analyze", "serve", "graph", "query", "find",
                "cypher", "flow", "bundle", "cache", "stats",
                "plugins", "version"
        };

        for (String name : expectedNames) {
            assertNotNull(subcommands.get(name),
                    "Missing subcommand: " + name);
        }
        assertEquals(12, expectedNames.length);
    }

    @Test
    void cliHasVersionOption() {
        var cli = new CodeIqCli();
        var cmdLine = new CommandLine(cli);
        assertTrue(cmdLine.getMixins().containsKey("mixinStandardHelpOptions"),
                "Should have standard help options mixin");
    }

    @Test
    void helpDoesNotThrow() {
        var cli = new CodeIqCli();
        var cmdLine = new CommandLine(cli);
        int exitCode = cmdLine.execute("--help");
        assertEquals(0, exitCode);
    }
}
