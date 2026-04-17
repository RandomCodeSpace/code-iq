package io.github.randomcodespace.iq.config.unified;
import java.util.List;
public record IndexingConfig(
        List<String> languages, List<String> include, List<String> exclude,
        Boolean incremental, String cacheDir, String parallelism, Integer batchSize) {
    public static IndexingConfig empty() {
        return new IndexingConfig(List.of(), List.of(), List.of(), null, null, null, null);
    }
}
