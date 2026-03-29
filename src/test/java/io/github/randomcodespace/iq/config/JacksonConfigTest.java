package io.github.randomcodespace.iq.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JacksonConfigTest {

    @Test
    void objectMapperBeanIsCreated() {
        JacksonConfig config = new JacksonConfig();
        ObjectMapper mapper = config.objectMapper();
        assertNotNull(mapper);
    }

    @Test
    void objectMapperCanSerializeEmptyBeans() throws Exception {
        JacksonConfig config = new JacksonConfig();
        ObjectMapper mapper = config.objectMapper();
        // This should not throw with FAIL_ON_EMPTY_BEANS disabled
        String json = mapper.writeValueAsString(new Object());
        assertNotNull(json);
    }
}
