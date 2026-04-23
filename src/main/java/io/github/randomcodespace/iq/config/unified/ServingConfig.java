package io.github.randomcodespace.iq.config.unified;
public record ServingConfig(Integer port, String bindAddress, Boolean readOnly, Neo4jConfig neo4j) {
    public static ServingConfig empty() { return new ServingConfig(null, null, null, Neo4jConfig.empty()); }
}
