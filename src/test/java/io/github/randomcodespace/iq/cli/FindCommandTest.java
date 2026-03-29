package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FindCommandTest {

    @ParameterizedTest
    @CsvSource({
            "endpoints,ENDPOINT",
            "endpoint,ENDPOINT",
            "guards,GUARD",
            "guard,GUARD",
            "entities,ENTITY",
            "entity,ENTITY",
            "components,COMPONENT",
            "component,COMPONENT",
            "middleware,MIDDLEWARE",
            "hooks,HOOK",
            "hook,HOOK",
            "configs,CONFIG_FILE",
            "config,CONFIG_FILE",
            "modules,MODULE",
            "module,MODULE",
            "queries,QUERY",
            "query,QUERY",
            "topics,TOPIC",
            "topic,TOPIC",
            "events,EVENT",
            "event,EVENT",
            "classes,CLASS",
            "class,CLASS",
            "methods,METHOD",
            "interfaces,INTERFACE"
    })
    void resolveKindMapsCorrectly(String input, String expectedKind) {
        NodeKind result = FindCommand.resolveKind(input);
        assertEquals(NodeKind.valueOf(expectedKind), result);
    }

    @Test
    void resolveKindReturnsNullForUnknown() {
        assertNull(FindCommand.resolveKind("bogus"));
        assertNull(FindCommand.resolveKind(null));
    }
}
