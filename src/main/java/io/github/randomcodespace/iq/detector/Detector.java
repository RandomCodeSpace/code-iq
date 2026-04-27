package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.model.Confidence;

import java.util.Set;

public interface Detector {
    String getName();
    Set<String> getSupportedLanguages();
    DetectorResult detect(DetectorContext ctx);

    /**
     * Confidence floor for nodes and edges this detector emits without explicitly
     * setting one. Stamped by the orchestrator (see {@code DetectorEmissionDefaults})
     * onto every emission whose {@code source} is still null — i.e. the detector
     * didn't explicitly stamp anything. Default is {@link Confidence#LEXICAL} — the
     * least-committal floor; base classes override to bump up to
     * {@link Confidence#SYNTACTIC} for AST-backed detection.
     *
     * <p>A detector with stronger evidence (e.g. a resolved symbol) should call
     * {@code node.setConfidence(Confidence.RESOLVED)} explicitly — the stamping
     * pass leaves explicitly-stamped values alone (it keys off {@code source ==
     * null}).
     */
    default Confidence defaultConfidence() {
        return Confidence.LEXICAL;
    }
}
