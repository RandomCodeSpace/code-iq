package io.github.randomcodespace.iq.cli;

import picocli.CommandLine;

import java.io.PrintStream;

/**
 * Utility class for rich ANSI-colored CLI output.
 * Uses Picocli's built-in ANSI support for cross-platform color rendering.
 */
final class CliOutput {

    private CliOutput() {}

    private static final CommandLine.Help.Ansi ANSI = CommandLine.Help.Ansi.AUTO;

    static void info(String message) {
        print(System.out, message);
    }

    static void info(PrintStream out, String message) {
        print(out, message);
    }

    static void success(String message) {
        print(System.out, "@|bold,green " + escape(message) + "|@");
    }

    static void success(PrintStream out, String message) {
        print(out, "@|bold,green " + escape(message) + "|@");
    }

    static void warn(String message) {
        print(System.err, "@|bold,yellow " + escape(message) + "|@");
    }

    static void error(String message) {
        print(System.err, "@|bold,red " + escape(message) + "|@");
    }

    static void step(String emoji, String message) {
        print(System.out, emoji + " " + message);
    }

    static void step(PrintStream out, String emoji, String message) {
        print(out, emoji + " " + message);
    }

    static void cyan(String message) {
        print(System.out, "@|cyan " + escape(message) + "|@");
    }

    static void cyan(PrintStream out, String message) {
        print(out, "@|cyan " + escape(message) + "|@");
    }

    static void bold(String message) {
        print(System.out, "@|bold " + escape(message) + "|@");
    }

    static void bold(PrintStream out, String message) {
        print(out, "@|bold " + escape(message) + "|@");
    }

    /**
     * Print a formatted ANSI string.
     */
    static void print(PrintStream out, String ansiFormatted) {
        out.println(ANSI.string(ansiFormatted));
    }

    /**
     * Format an ANSI string without printing.
     */
    static String format(String ansiFormatted) {
        return ANSI.string(ansiFormatted);
    }

    /**
     * Escape pipe characters that would break picocli ANSI markup.
     */
    private static String escape(String text) {
        return text.replace("|", "\\|");
    }
}
