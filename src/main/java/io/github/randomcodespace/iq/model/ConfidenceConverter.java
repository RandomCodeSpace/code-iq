package io.github.randomcodespace.iq.model;

import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.convert.Neo4jPersistentPropertyConverter;

/**
 * Converts between {@link Confidence} and its uppercase string name for
 * Neo4j storage. Stores {@code "LEXICAL"} / {@code "SYNTACTIC"} /
 * {@code "RESOLVED"} so Cypher filters like
 * {@code WHERE n.confidence = 'RESOLVED'} match without case folding.
 */
public class ConfidenceConverter implements Neo4jPersistentPropertyConverter<Confidence> {

    @Override
    public Value write(Confidence confidence) {
        return org.neo4j.driver.Values.value(confidence != null ? confidence.name() : null);
    }

    @Override
    public Confidence read(Value source) {
        if (source == null || source.isNull()) return Confidence.LEXICAL;
        return Confidence.fromString(source.asString());
    }
}
