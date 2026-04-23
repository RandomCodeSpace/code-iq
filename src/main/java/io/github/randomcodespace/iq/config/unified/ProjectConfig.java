package io.github.randomcodespace.iq.config.unified;
import java.util.List;
public record ProjectConfig(String name, String root, String serviceName, List<ModuleConfig> modules) {
    public static ProjectConfig empty() { return new ProjectConfig(null, null, null, List.of()); }
}
