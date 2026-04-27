package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;

/**
 * Stamps the orchestrator-managed confidence + source defaults onto a
 * {@link DetectorResult}. This is invoked by the analyzer / index pipeline
 * after each {@link Detector#detect(DetectorContext)} call so detectors stay
 * blissfully unaware of the bookkeeping.
 *
 * <p><strong>Stamping rule</strong> — for every node and edge in the result:
 * <ul>
 *   <li>If {@code getSource() == null} (i.e. the detector did not explicitly
 *       stamp anything), the entry is treated as "wants defaults":
 *       <ul>
 *         <li>{@code source} is set to the detector's class simple name.</li>
 *         <li>{@code confidence} is set to {@link Detector#defaultConfidence()}.</li>
 *       </ul>
 *   </li>
 *   <li>If {@code getSource() != null} (the detector stamped explicitly),
 *       both fields are left alone — the detector knows what it's doing.</li>
 * </ul>
 *
 * <p>The {@code source==null} sentinel is what lets us distinguish "detector
 * didn't think about confidence" from "detector intentionally chose LEXICAL."
 * Confidence is never null at rest (the model setter normalizes), so confidence
 * alone can't tell us that.
 */
public final class DetectorEmissionDefaults {

    private DetectorEmissionDefaults() { }

    /**
     * Apply orchestrator defaults to every node + edge in the result. Mutates
     * the model objects in place — the result record itself is unchanged.
     *
     * @param result   the detector's emission (must not be null)
     * @param detector the detector that produced it (used for source name +
     *                 default confidence)
     */
    public static void applyDefaults(DetectorResult result, Detector detector) {
        if (result == null || detector == null) return;
        String defaultSource = detector.getClass().getSimpleName();
        Confidence defaultConfidence = detector.defaultConfidence();

        for (CodeNode node : result.nodes()) {
            if (node.getSource() == null) {
                node.setSource(defaultSource);
                node.setConfidence(defaultConfidence);
            }
        }
        for (CodeEdge edge : result.edges()) {
            if (edge.getSource() == null) {
                edge.setSource(defaultSource);
                edge.setConfidence(defaultConfidence);
            }
        }
    }
}
