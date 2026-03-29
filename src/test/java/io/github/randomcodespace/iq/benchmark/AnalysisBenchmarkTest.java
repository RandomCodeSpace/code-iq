package io.github.randomcodespace.iq.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.*;

/**
 * Integration benchmarks that run against real codebases.
 *
 * Only runs when BENCHMARK_DIR env var is set.
 * Example: BENCHMARK_DIR=~/projects/testDir mvn test -Dtest=AnalysisBenchmarkTest
 */
class AnalysisBenchmarkTest {

    private static final String BENCHMARK_DIR = System.getenv("BENCHMARK_DIR");

    @Test
    @EnabledIfEnvironmentVariable(named = "BENCHMARK_DIR", matches = ".+")
    void benchmarkFileDiscovery() {
        // Walk ~/projects/testDir/spring-boot and count files
        // Report: file count, time taken, files/sec
        Path dir = Path.of(BENCHMARK_DIR, "spring-boot");
        if (!Files.isDirectory(dir)) return;

        Instant start = Instant.now();
        long count = 0;
        try (var stream = Files.walk(dir)) {
            count = stream.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            // skip
        }
        Duration elapsed = Duration.between(start, Instant.now());

        System.out.printf("=== File Discovery Benchmark ===%n");
        System.out.printf("Directory: %s%n", dir);
        System.out.printf("Files found: %d%n", count);
        System.out.printf("Time: %d ms%n", elapsed.toMillis());
        System.out.printf("Rate: %.0f files/sec%n", count * 1000.0 / elapsed.toMillis());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BENCHMARK_DIR", matches = ".+")
    void benchmarkFileReading() {
        // Read all files in spring-boot, measure I/O throughput
        Path dir = Path.of(BENCHMARK_DIR, "spring-boot");
        if (!Files.isDirectory(dir)) return;

        Instant start = Instant.now();
        long totalBytes = 0;
        long fileCount = 0;
        try (var stream = Files.walk(dir)) {
            var files = stream.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.toString();
                    return name.endsWith(".java") || name.endsWith(".xml") ||
                           name.endsWith(".yaml") || name.endsWith(".yml") ||
                           name.endsWith(".properties") || name.endsWith(".json");
                })
                .toList();
            for (Path file : files) {
                try {
                    byte[] content = Files.readAllBytes(file);
                    totalBytes += content.length;
                    fileCount++;
                } catch (Exception e) {
                    // skip unreadable files
                }
            }
        } catch (Exception e) {
            // skip
        }
        Duration elapsed = Duration.between(start, Instant.now());

        System.out.printf("%n=== File Reading Benchmark ===%n");
        System.out.printf("Files read: %d%n", fileCount);
        System.out.printf("Total bytes: %,d%n", totalBytes);
        System.out.printf("Time: %d ms%n", elapsed.toMillis());
        System.out.printf("Rate: %.0f files/sec, %.1f MB/sec%n",
            fileCount * 1000.0 / elapsed.toMillis(),
            totalBytes / 1024.0 / 1024.0 / (elapsed.toMillis() / 1000.0));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BENCHMARK_DIR", matches = ".+")
    void benchmarkRegexDetection() {
        // Read Java files from spring-boot, run regex patterns, measure throughput
        Path dir = Path.of(BENCHMARK_DIR, "spring-boot");
        if (!Files.isDirectory(dir)) return;

        // Compile common Spring regex patterns
        var patterns = java.util.List.of(
            java.util.regex.Pattern.compile("@(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)\\s*\\("),
            java.util.regex.Pattern.compile("@(Entity|Table|Column)"),
            java.util.regex.Pattern.compile("@(Service|Repository|Controller|Component|RestController)"),
            java.util.regex.Pattern.compile("@(Autowired|Inject|Value)")
        );

        Instant start = Instant.now();
        long matchCount = 0;
        long fileCount = 0;
        try (var stream = Files.walk(dir)) {
            var javaFiles = stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
            for (Path file : javaFiles) {
                try {
                    String content = Files.readString(file);
                    fileCount++;
                    for (var pattern : patterns) {
                        var matcher = pattern.matcher(content);
                        while (matcher.find()) {
                            matchCount++;
                        }
                    }
                } catch (Exception e) {
                    // skip
                }
            }
        } catch (Exception e) {
            // skip
        }
        Duration elapsed = Duration.between(start, Instant.now());

        System.out.printf("%n=== Regex Detection Benchmark ===%n");
        System.out.printf("Java files scanned: %d%n", fileCount);
        System.out.printf("Total matches: %d%n", matchCount);
        System.out.printf("Time: %d ms%n", elapsed.toMillis());
        System.out.printf("Rate: %.0f files/sec%n", fileCount * 1000.0 / Math.max(1, elapsed.toMillis()));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BENCHMARK_DIR", matches = ".+")
    void benchmarkVirtualThreadParallelism() {
        // Read + regex scan all Java files using virtual threads
        Path dir = Path.of(BENCHMARK_DIR, "spring-boot");
        if (!Files.isDirectory(dir)) return;

        var pattern = java.util.regex.Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping|Entity|Service|Repository|Controller|Component)");

        try (var stream = Files.walk(dir)) {
            var javaFiles = stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .toList();

            // Sequential baseline
            Instant seqStart = Instant.now();
            long seqMatches = 0;
            for (Path file : javaFiles) {
                try {
                    String content = Files.readString(file);
                    var matcher = pattern.matcher(content);
                    while (matcher.find()) seqMatches++;
                } catch (Exception e) {}
            }
            Duration seqElapsed = Duration.between(seqStart, Instant.now());

            // Virtual threads
            Instant vtStart = Instant.now();
            java.util.concurrent.atomic.AtomicLong vtMatches = new java.util.concurrent.atomic.AtomicLong();
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = javaFiles.stream()
                    .map(file -> executor.submit(() -> {
                        try {
                            String content = Files.readString(file);
                            var m = pattern.matcher(content);
                            long count = 0;
                            while (m.find()) count++;
                            vtMatches.addAndGet(count);
                        } catch (Exception e) {}
                    }))
                    .toList();
                for (var f : futures) f.get();
            }
            Duration vtElapsed = Duration.between(vtStart, Instant.now());

            System.out.printf("%n=== Virtual Thread Parallelism Benchmark ===%n");
            System.out.printf("Java files: %d%n", javaFiles.size());
            System.out.printf("Sequential: %d ms (%d matches)%n", seqElapsed.toMillis(), seqMatches);
            System.out.printf("Virtual threads: %d ms (%d matches)%n", vtElapsed.toMillis(), vtMatches.get());
            System.out.printf("Speedup: %.1fx%n", (double) seqElapsed.toMillis() / Math.max(1, vtElapsed.toMillis()));

        } catch (Exception e) {
            System.out.printf("Benchmark failed: %s%n", e.getMessage());
        }
    }
}
