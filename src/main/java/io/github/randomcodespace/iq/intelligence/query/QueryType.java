package io.github.randomcodespace.iq.intelligence.query;

/**
 * Enumeration of query intents the query planner can route.
 * Each type maps to one or more {@link CapabilityDimension}s for routing decisions.
 */
public enum QueryType {
    /** Locate symbol definitions (classes, functions, methods, variables). */
    FIND_SYMBOL,
    /** Find all usages/references of a symbol across the codebase. */
    FIND_REFERENCES,
    /** Find callers of a function or method. */
    FIND_CALLERS,
    /** Find modules or packages that a given module depends on. */
    FIND_DEPENDENCIES,
    /** Full-text / lexical search across source files. */
    SEARCH_TEXT,
    /** Locate configuration files and structured config values. */
    FIND_CONFIG
}
