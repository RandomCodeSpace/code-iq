package io.github.randomcodespace.iq.intelligence.resolver.java;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Discovers Java source roots under a project root by walking for the
 * {@code src/main/java} and {@code src/test/java} directories Maven and Gradle
 * both standardize on. Multi-module projects are handled by walking the whole
 * tree — every nested {@code src/(main|test)/java} is a separate root.
 *
 * <p>Determinism: results are returned sorted alphabetically by absolute path.
 * Same project tree → same root list → same {@code CombinedTypeSolver} →
 * same resolution behavior.
 *
 * <p>Symlink safety: {@link Files#walkFileTree} runs with
 * {@link FileVisitOption#FOLLOW_LINKS} disabled, so symlink cycles cannot
 * form. The trade-off — source roots reachable only via symlink are skipped
 * — is the right call for resolution: traversal would otherwise double-count.
 *
 * <p>Plain-layout fallback: if the walk finds no Maven/Gradle source roots
 * but the top-level directory contains {@code src/} with at least one
 * {@code *.java} file, returns {@code [src]} as a single root. This covers
 * scratch projects without a build file.
 */
@Component
public class JavaSourceRootDiscovery {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceRootDiscovery.class);

    /** Directories we never descend into — they don't contain Java sources we care about. */
    private static final Set<String> SKIP_DIRS = Set.of(
            "target", "build", "out", "bin", "dist",
            ".git", ".gradle", ".idea", ".vscode", ".m2", ".cache",
            "node_modules", ".codeiq"
    );

    /**
     * @param projectRoot project root path. May be null or non-existent — both
     *                    return an empty list.
     * @return sorted list of absolute Java source root paths (e.g.
     *         {@code [<root>/service-a/src/main/java, <root>/service-b/src/main/java]}).
     *         Never null, never contains null entries.
     */
    public List<Path> discover(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return List.of();
        }

        Set<Path> roots = new TreeSet<>();
        try {
            Files.walkFileTree(
                    projectRoot,
                    EnumSet.noneOf(FileVisitOption.class), // do NOT follow symlinks
                    Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = nameOrEmpty(dir);
                            if (SKIP_DIRS.contains(name)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (isMavenStyleJavaRoot(dir)) {
                                roots.add(dir);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            // Ignore unreadable entries; resolution is best-effort.
                            log.debug("skipping unreadable path {}: {}", file, exc.getMessage());
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.warn("source root discovery failed for {}: {}", projectRoot, e.getMessage());
            return List.of();
        }

        if (!roots.isEmpty()) {
            return new ArrayList<>(roots);
        }

        // Plain-layout fallback: top-level src/ with at least one .java file.
        Path src = projectRoot.resolve("src");
        if (Files.isDirectory(src) && containsJavaFile(src)) {
            return List.of(src);
        }
        return List.of();
    }

    /** {@code true} iff {@code dir} is {@code .../src/main/java} or {@code .../src/test/java}. */
    private static boolean isMavenStyleJavaRoot(Path dir) {
        if (!"java".equals(nameOrEmpty(dir))) return false;
        Path parent = dir.getParent();
        if (parent == null) return false;
        String parentName = nameOrEmpty(parent);
        if (!"main".equals(parentName) && !"test".equals(parentName)) return false;
        Path grandparent = parent.getParent();
        if (grandparent == null) return false;
        return "src".equals(nameOrEmpty(grandparent));
    }

    private static String nameOrEmpty(Path p) {
        Path name = p.getFileName();
        return name != null ? name.toString() : "";
    }

    /** Cheap probe: does the directory tree under {@code root} have any {@code *.java}? */
    private static boolean containsJavaFile(Path root) {
        // try-with-resources: Files.walk holds an open directory stream; without
        // a close, the file descriptor leaks for every plain-layout fallback
        // scan. Cheap fix.
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> !Files.isDirectory(p))
                    .anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }
}
