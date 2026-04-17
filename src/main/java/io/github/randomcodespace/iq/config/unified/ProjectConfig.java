package io.github.randomcodespace.iq.config.unified;
import java.util.List;
public record ProjectConfig(String name, String root, List<ModuleConfig> modules) {
    public static ProjectConfig empty() { return new ProjectConfig(null, ".", List.of()); }
}
