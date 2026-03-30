package io.github.randomcodespace.iq.model;

/**
 * Types of edges (relationships) in the OSSCodeIQ graph.
 * Mirrors the 26 edge kinds from the Python implementation.
 */
public enum EdgeKind {

    DEPENDS_ON("depends_on"),
    IMPORTS("imports"),
    EXTENDS("extends"),
    IMPLEMENTS("implements"),
    CALLS("calls"),
    INJECTS("injects"),
    EXPOSES("exposes"),
    QUERIES("queries"),
    MAPS_TO("maps_to"),
    PRODUCES("produces"),
    CONSUMES("consumes"),
    PUBLISHES("publishes"),
    LISTENS("listens"),
    INVOKES_RMI("invokes_rmi"),
    EXPORTS_RMI("exports_rmi"),
    READS_CONFIG("reads_config"),
    MIGRATES("migrates"),
    CONTAINS("contains"),
    DEFINES("defines"),
    OVERRIDES("overrides"),
    CONNECTS_TO("connects_to"),
    TRIGGERS("triggers"),
    PROVISIONS("provisions"),
    SENDS_TO("sends_to"),
    RECEIVES_FROM("receives_from"),
    PROTECTS("protects"),
    RENDERS("renders");

    private final String value;

    private static final java.util.Map<String, EdgeKind> BY_VALUE;
    static {
        java.util.Map<String, EdgeKind> map = new java.util.HashMap<>();
        for (EdgeKind kind : values()) {
            map.put(kind.value, kind);
        }
        BY_VALUE = java.util.Collections.unmodifiableMap(map);
    }

    EdgeKind(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Look up an EdgeKind by its string value (O(1) via static map).
     *
     * @param value the lowercase string value (e.g. "depends_on", "invokes_rmi")
     * @return the matching EdgeKind
     * @throws IllegalArgumentException if no match found
     */
    public static EdgeKind fromValue(String value) {
        EdgeKind kind = BY_VALUE.get(value);
        if (kind == null) {
            throw new IllegalArgumentException("Unknown EdgeKind value: " + value);
        }
        return kind;
    }
}
