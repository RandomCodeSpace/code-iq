package io.github.randomcodespace.iq.intelligence;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deterministic inventory of all files discovered in a repository.
 * Entries are always sorted by path for reproducibility.
 *
 * @param entries Sorted, immutable list of file entries.
 */
public record FileInventory(List<FileEntry> entries) {

    /** Canonical constructor — sorts and makes the list immutable. */
    public FileInventory(List<FileEntry> entries) {
        var sorted = entries.stream().sorted().toList();
        this.entries = Collections.unmodifiableList(sorted);
    }

    /** Total number of files. */
    public int totalFiles() {
        return entries.size();
    }

    /** Count of files per {@link FileClassification}. */
    public Map<FileClassification, Long> countsByClassification() {
        return entries.stream()
                .collect(Collectors.groupingBy(FileEntry::classification, Collectors.counting()));
    }

    /** Count of files per language. */
    public Map<String, Long> countsByLanguage() {
        return entries.stream()
                .collect(Collectors.groupingBy(FileEntry::language, Collectors.counting()));
    }

    /** Sum of all file sizes in bytes. */
    public long totalBytes() {
        return entries.stream().mapToLong(FileEntry::sizeBytes).sum();
    }

    /**
     * Build a compact summary map suitable for inclusion in the v3 manifest.
     */
    public Map<String, Object> toSummary() {
        var summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("total_files", totalFiles());
        summary.put("total_bytes", totalBytes());
        var byCls = countsByClassification().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name().toLowerCase(), Map.Entry::getValue));
        summary.put("by_classification", byCls);
        var byLang = countsByLanguage().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, java.util.LinkedHashMap::new));
        summary.put("by_language", byLang);
        return summary;
    }

    /** Empty inventory constant. */
    public static final FileInventory EMPTY = new FileInventory(List.of());
}
