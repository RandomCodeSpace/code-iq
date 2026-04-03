package io.github.randomcodespace.iq.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileInventoryTest {

    @Test
    void entries_areSortedByPath() {
        var entries = List.of(
                new FileEntry("z/File.java", "java", 100, null, FileClassification.SOURCE),
                new FileEntry("a/Main.java", "java", 200, null, FileClassification.SOURCE),
                new FileEntry("m/Config.yml", "yaml", 50, null, FileClassification.CONFIG)
        );
        var inventory = new FileInventory(entries);

        var paths = inventory.entries().stream().map(FileEntry::path).toList();
        assertThat(paths).containsExactly("a/Main.java", "m/Config.yml", "z/File.java");
    }

    @Test
    void entries_areImmutable() {
        var inventory = new FileInventory(List.of(
                new FileEntry("src/Foo.java", "java", 10, null, FileClassification.SOURCE)
        ));
        assertThat(inventory.entries()).hasSize(1);
        // Attempting to modify should throw
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> inventory.entries().add(new FileEntry("src/Bar.java", "java", 10, null, FileClassification.SOURCE)));
    }

    @Test
    void countsByClassification_correctCounts() {
        var inventory = new FileInventory(List.of(
                new FileEntry("src/A.java", "java", 10, null, FileClassification.SOURCE),
                new FileEntry("src/B.java", "java", 10, null, FileClassification.SOURCE),
                new FileEntry("src/test/ATest.java", "java", 10, null, FileClassification.TEST),
                new FileEntry("config.yml", "yaml", 10, null, FileClassification.CONFIG)
        ));

        Map<FileClassification, Long> counts = inventory.countsByClassification();
        assertThat(counts.get(FileClassification.SOURCE)).isEqualTo(2L);
        assertThat(counts.get(FileClassification.TEST)).isEqualTo(1L);
        assertThat(counts.get(FileClassification.CONFIG)).isEqualTo(1L);
    }

    @Test
    void totalBytes_sumsAllFiles() {
        var inventory = new FileInventory(List.of(
                new FileEntry("a.java", "java", 100, null, FileClassification.SOURCE),
                new FileEntry("b.java", "java", 200, null, FileClassification.SOURCE)
        ));
        assertThat(inventory.totalBytes()).isEqualTo(300L);
    }

    @Test
    void empty_inventoryConstant() {
        assertThat(FileInventory.EMPTY.entries()).isEmpty();
        assertThat(FileInventory.EMPTY.totalFiles()).isZero();
    }

    @Test
    void toSummary_containsExpectedKeys() {
        var inventory = new FileInventory(List.of(
                new FileEntry("src/Main.java", "java", 500, null, FileClassification.SOURCE),
                new FileEntry("README.md", "markdown", 100, null, FileClassification.DOC)
        ));
        var summary = inventory.toSummary();

        assertThat(summary).containsKey("total_files");
        assertThat(summary).containsKey("total_bytes");
        assertThat(summary).containsKey("by_classification");
        assertThat(summary).containsKey("by_language");
        assertThat(summary.get("total_files")).isEqualTo(2);
        assertThat(summary.get("total_bytes")).isEqualTo(600L);
    }

    @Test
    void fileEntry_classify_testPaths() {
        assertThat(FileEntry.classify("src/test/java/FooTest.java", "java")).isEqualTo(FileClassification.TEST);
        assertThat(FileEntry.classify("src/main/Foo.java", "java")).isEqualTo(FileClassification.SOURCE);
        assertThat(FileEntry.classify("application.yml", "yaml")).isEqualTo(FileClassification.CONFIG);
        assertThat(FileEntry.classify("README.md", "markdown")).isEqualTo(FileClassification.DOC);
        assertThat(FileEntry.classify("target/generated/Foo.java", "java")).isEqualTo(FileClassification.GENERATED);
    }

    @Test
    void determinism_sameInputProducesSameOutput() {
        var entries1 = List.of(
                new FileEntry("c.java", "java", 30, null, FileClassification.SOURCE),
                new FileEntry("a.java", "java", 10, null, FileClassification.SOURCE),
                new FileEntry("b.java", "java", 20, null, FileClassification.SOURCE)
        );
        var entries2 = List.of(
                new FileEntry("b.java", "java", 20, null, FileClassification.SOURCE),
                new FileEntry("c.java", "java", 30, null, FileClassification.SOURCE),
                new FileEntry("a.java", "java", 10, null, FileClassification.SOURCE)
        );

        var inv1 = new FileInventory(entries1);
        var inv2 = new FileInventory(entries2);

        assertThat(inv1.entries()).isEqualTo(inv2.entries());
    }
}
