package io.github.randomcodespace.iq.analyzer;

import java.time.Duration;
import java.util.Map;

/**
 * Result of running the full analysis pipeline.
 *
 * @param totalFiles        total files discovered
 * @param filesAnalyzed     files that were actually analyzed (detectors ran)
 * @param nodeCount         total graph nodes produced
 * @param edgeCount         total graph edges produced
 * @param languageBreakdown count of files per language
 * @param nodeBreakdown       count of nodes per NodeKind value
 * @param frameworkBreakdown  count of nodes per detected framework
 * @param elapsed             wall-clock duration of the analysis
 */
public record AnalysisResult(
        int totalFiles,
        int filesAnalyzed,
        int nodeCount,
        int edgeCount,
        Map<String, Integer> languageBreakdown,
        Map<String, Integer> nodeBreakdown,
        Map<String, Integer> edgeBreakdown,
        Map<String, Integer> frameworkBreakdown,
        Duration elapsed
) {}
