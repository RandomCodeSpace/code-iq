package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import picocli.CommandLine;

import java.io.PrintStream;
import java.text.NumberFormat;
import java.util.Map;

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
     * Configure shared CLI options: graph directory + service name.
     * Used by both {@code analyze} and {@code index} commands.
     *
     * <p>Project-level overrides ({@code cache_dir}, {@code max_depth},
     * {@code max_radius} from {@code codeiq.yml} / legacy {@code .osscodeiq.yml})
     * are already resolved at Spring startup by
     * {@link io.github.randomcodespace.iq.config.UnifiedConfigBeans#codeIqConfig}
     * via {@code ConfigResolver} + {@code UnifiedConfigAdapter}. The {@code config}
     * bean passed in already carries those values, so re-reading the file here
     * would be pure redundancy.
     */
    static void configureFromOptions(io.github.randomcodespace.iq.config.CodeIqConfig config,
                                     java.nio.file.Path graphDir, String serviceName) {
        if (graphDir != null) {
            java.nio.file.Path sharedDir = graphDir.toAbsolutePath().normalize();
            config.setCacheDir(sharedDir.toString());
            info("  Graph dir: " + sharedDir + " (shared multi-repo)");
        }
        if (serviceName != null && !serviceName.isBlank()) {
            config.setServiceName(serviceName);
            info("  Service name: " + serviceName);
        }
    }

    /**
     * Print the result summary shared by analyze and index commands:
     * blank line, success banner, blank line, files/nodes/edges/time, and breakdowns.
     */
    static void printResultSummary(AnalysisResult result, NumberFormat nf) {
        long secs = result.elapsed().toSeconds();
        String timeStr = secs > 0 ? secs + "s" : result.elapsed().toMillis() + "ms";

        System.out.println();
        success("[OK] Complete -- "
                + nf.format(result.nodeCount()) + " nodes, "
                + nf.format(result.edgeCount()) + " edges in " + timeStr);
        System.out.println();
        printAnalysisStats(result, nf);
        printBreakdowns(result, nf);
    }

    /**
     * Print files/nodes/edges/time summary lines shared by analyze and index commands.
     * Callers are responsible for printing the preceding success banner and any
     * command-specific extra lines (e.g. "Store: H2…").
     */
    static void printAnalysisStats(AnalysisResult result, NumberFormat nf) {
        long secs = result.elapsed().toSeconds();
        String timeStr = secs > 0 ? secs + "s" : result.elapsed().toMillis() + "ms";
        info("  Files:   " + nf.format(result.totalFiles()) + " discovered, "
                + nf.format(result.filesAnalyzed()) + " analyzed");
        cyan("  Nodes:   " + nf.format(result.nodeCount()));
        cyan("  Edges:   " + nf.format(result.edgeCount()));
        info("  Time:    " + timeStr);
    }

    /**
     * Print node-kind and language breakdown lines shared by analyze and index commands.
     * Prints a blank line before the node breakdown when it is non-empty.
     */
    static void printBreakdowns(AnalysisResult result, NumberFormat nf) {
        if (!result.nodeBreakdown().isEmpty()) {
            System.out.println();
            StringBuilder topNodes = new StringBuilder("  Top node kinds: ");
            result.nodeBreakdown().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> topNodes.append(e.getKey()).append(" (")
                            .append(nf.format(e.getValue())).append("), "));
            if (topNodes.length() > 2) topNodes.setLength(topNodes.length() - 2);
            info(topNodes.toString());
        }

        if (!result.languageBreakdown().isEmpty()) {
            StringBuilder langs = new StringBuilder("  Languages: ");
            result.languageBreakdown().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> langs.append(e.getKey()).append(" (")
                            .append(nf.format(e.getValue())).append("), "));
            if (langs.length() > 2) langs.setLength(langs.length() - 2);
            info(langs.toString());
        }
    }

    /**
     * Escape pipe characters that would break picocli ANSI markup.
     */
    private static String escape(String text) {
        return text.replace("|", "\\|");
    }
}
