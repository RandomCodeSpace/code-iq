package io.github.randomcodespace.iq.config.unified;
public record Neo4jConfig(String dir, Integer pageCacheMb, Integer heapInitialMb, Integer heapMaxMb) {
    public static Neo4jConfig empty() { return new Neo4jConfig(null, null, null, null); }
}
