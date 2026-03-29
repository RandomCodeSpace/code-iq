package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;

/**
 * Manage the analysis cache (.code-intelligence directory).
 */
@Component
@Command(name = "cache", mixinStandardHelpOptions = true,
        description = "Manage analysis cache",
        subcommands = {
                CacheCommand.StatsSubcommand.class,
                CacheCommand.ClearSubcommand.class
        })
public class CacheCommand implements Runnable {

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Component
    @Command(name = "stats", mixinStandardHelpOptions = true,
            description = "Show cache statistics")
    static class StatsSubcommand implements Callable<Integer> {

        @Parameters(index = "0", defaultValue = ".", description = "Path to codebase")
        private Path path;

        private final CodeIqConfig config;

        StatsSubcommand(CodeIqConfig config) {
            this.config = config;
        }

        @Override
        public Integer call() {
            Path root = path.toAbsolutePath().normalize();
            Path cacheDir = root.resolve(config.getCacheDir());

            if (!Files.isDirectory(cacheDir)) {
                CliOutput.info("No cache found at " + cacheDir);
                return 0;
            }

            try (var walk = Files.walk(cacheDir)) {
                long totalSize = walk.filter(Files::isRegularFile)
                        .mapToLong(f -> {
                            try {
                                return Files.size(f);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();

                long fileCount;
                try (var countWalk = Files.walk(cacheDir)) {
                    fileCount = countWalk.filter(Files::isRegularFile).count();
                }

                CliOutput.bold("Cache statistics:");
                CliOutput.info("  Location: " + cacheDir);
                CliOutput.info("  Files:    " + fileCount);
                CliOutput.info("  Size:     " + formatSize(totalSize));
            } catch (IOException e) {
                CliOutput.error("Failed to read cache: " + e.getMessage());
                return 1;
            }

            return 0;
        }

        private static String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    @Component
    @Command(name = "clear", mixinStandardHelpOptions = true,
            description = "Clear analysis cache")
    static class ClearSubcommand implements Callable<Integer> {

        @Parameters(index = "0", defaultValue = ".", description = "Path to codebase")
        private Path path;

        private final CodeIqConfig config;

        ClearSubcommand(CodeIqConfig config) {
            this.config = config;
        }

        @Override
        public Integer call() {
            Path root = path.toAbsolutePath().normalize();
            Path cacheDir = root.resolve(config.getCacheDir());

            if (!Files.isDirectory(cacheDir)) {
                CliOutput.info("No cache to clear at " + cacheDir);
                return 0;
            }

            try (var walk = Files.walk(cacheDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                CliOutput.warn("Could not delete: " + p);
                            }
                        });
                CliOutput.success("\u2705 Cache cleared: " + cacheDir);
            } catch (IOException e) {
                CliOutput.error("Failed to clear cache: " + e.getMessage());
                return 1;
            }

            return 0;
        }
    }
}
