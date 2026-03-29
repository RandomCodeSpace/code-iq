package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.detector.DetectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Discovers files in a repository directory.
 * <p>
 * For git repos, tries {@code git ls-files} first (fast, respects .gitignore).
 * Falls back to {@link Files#walkFileTree} with exclude patterns from config.
 * Results are sorted by path for deterministic ordering.
 */
@Service
public class FileDiscovery {

    private static final Logger log = LoggerFactory.getLogger(FileDiscovery.class);

    /** Default directories to exclude from scanning. */
    private static final Set<String> DEFAULT_EXCLUDES = Set.of(
            "node_modules", "build", "target", "dist", ".git", "__pycache__",
            "venv", ".venv", ".gradle", ".idea", ".vscode", ".code-intelligence",
            ".tox", ".mypy_cache", ".pytest_cache", "vendor", "bower_components",
            ".next", ".nuxt", "coverage", ".nyc_output", "bin", "obj"
    );

    /** Default maximum file size in bytes (1 MB). */
    private static final long DEFAULT_MAX_FILE_SIZE = 1_048_576L;

    private final CodeIqConfig config;

    public FileDiscovery(CodeIqConfig config) {
        this.config = config;
    }

    /**
     * Discover files under {@code repoPath}, returning a deterministically-ordered list.
     */
    public List<DiscoveredFile> discover(Path repoPath) {
        Path root = repoPath.toAbsolutePath().normalize();
        List<DiscoveredFile> result;

        if (isGitRepo(root)) {
            result = discoverViaGit(root);
        } else {
            result = discoverViaWalk(root);
        }

        // Sort for deterministic ordering
        result.sort(Comparator.comparing(f -> f.path().toString()));
        log.debug("Discovered {} files in {}", result.size(), root);
        return result;
    }

    // ------------------------------------------------------------------
    // Git-based discovery
    // ------------------------------------------------------------------

    private boolean isGitRepo(Path root) {
        try {
            var process = new ProcessBuilder("git", "rev-parse", "--git-dir")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            process.getInputStream().close();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private List<DiscoveredFile> discoverViaGit(Path root) {
        try {
            var process = new ProcessBuilder("git", "ls-files")
                    .directory(root.toFile())
                    .start();

            String output;
            try (var is = process.getInputStream()) {
                output = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            process.waitFor();

            List<DiscoveredFile> result = new ArrayList<>();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                Path relPath = Path.of(trimmed);
                Path absPath = root.resolve(relPath);

                if (!Files.isRegularFile(absPath)) continue;
                if (isExcluded(relPath)) continue;

                String language = DetectorUtils.deriveLanguage(trimmed);
                if (language == null) continue;

                long size;
                try {
                    size = Files.size(absPath);
                } catch (IOException e) {
                    continue;
                }
                if (size > DEFAULT_MAX_FILE_SIZE) continue;

                result.add(new DiscoveredFile(relPath, language, size));
            }
            return result;

        } catch (IOException | InterruptedException e) {
            log.warn("git ls-files failed, falling back to filesystem walk", e);
            return discoverViaWalk(root);
        }
    }

    // ------------------------------------------------------------------
    // Filesystem walk fallback
    // ------------------------------------------------------------------

    private List<DiscoveredFile> discoverViaWalk(Path root) {
        List<DiscoveredFile> result = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (DEFAULT_EXCLUDES.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (attrs.size() > DEFAULT_MAX_FILE_SIZE) return FileVisitResult.CONTINUE;

                    Path relPath = root.relativize(file);
                    if (isExcluded(relPath)) return FileVisitResult.CONTINUE;

                    String language = DetectorUtils.deriveLanguage(relPath.toString());
                    if (language == null) return FileVisitResult.CONTINUE;

                    result.add(new DiscoveredFile(relPath, language, attrs.size()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("Could not visit file: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to walk directory: {}", root, e);
        }

        return result;
    }

    // ------------------------------------------------------------------
    // Exclusion
    // ------------------------------------------------------------------

    private boolean isExcluded(Path relPath) {
        for (Path component : relPath) {
            if (DEFAULT_EXCLUDES.contains(component.toString())) {
                return true;
            }
        }
        return false;
    }
}
